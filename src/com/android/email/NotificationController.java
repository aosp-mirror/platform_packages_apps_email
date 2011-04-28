/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.email;

import com.android.email.activity.ContactStatusLoader;
import com.android.email.activity.Welcome;
import com.android.email.activity.setup.AccountSecurity;
import com.android.email.activity.setup.AccountSettingsXL;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.google.common.annotations.VisibleForTesting;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.TextAppearanceSpan;

/**
 * Class that manages notifications.
 */
public class NotificationController {
    private static final int NOTIFICATION_ID_SECURITY_NEEDED = 1;
    /** Reserved for {@link com.android.exchange.CalendarSyncEnabler} */
    @SuppressWarnings("unused")
    private static final int NOTIFICATION_ID_EXCHANGE_CALENDAR_ADDED = 2;
    private static final int NOTIFICATION_ID_ATTACHMENT_WARNING = 3;
    private static final int NOTIFICATION_ID_PASSWORD_EXPIRING = 4;
    private static final int NOTIFICATION_ID_PASSWORD_EXPIRED = 5;

    private static final int NOTIFICATION_ID_BASE_NEW_MESSAGES = 0x10000000;
    private static final int NOTIFICATION_ID_BASE_LOGIN_WARNING = 0x20000000;

    private static NotificationController sInstance;
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final AudioManager mAudioManager;
    private final Bitmap mGenericSenderIcon;
    private final Clock mClock;

    /** Constructor */
    @VisibleForTesting
    NotificationController(Context context, Clock clock) {
        mContext = context.getApplicationContext();
        mNotificationManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mGenericSenderIcon = BitmapFactory.decodeResource(mContext.getResources(),
                R.drawable.ic_contact_picture);
        mClock = clock;
    }

    /** Singleton access */
    public static synchronized NotificationController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new NotificationController(context, Clock.INSTANCE);
        }
        return sInstance;
    }

    /**
     * Returns a {@link Notification} for an event with the given account. The account contains
     * specific rules on ring tone usage and these will be used to modify the notification
     * behaviour.
     *
     * @param account The account this notification is being built for.
     * @param ticker Text displayed when the notification is first shown. May be {@code null}.
     * @param title The first line of text. May NOT be {@code null}.
     * @param contentText The second line of text. May NOT be {@code null}.
     * @param intent The intent to start if the user clicks on the notification.
     * @param largeIcon A large icon. May be {@code null}
     * @param number A number to display using {@link Notification.Builder#setNumber(int)}. May
     *        be {@code null}.
     * @return A {@link Notification} that can be sent to the notification service.
     */
    private Notification createAccountNotification(Account account, String ticker,
            CharSequence title, String contentText, Intent intent, Bitmap largeIcon,
            Integer number) {
        // Pending Intent
        PendingIntent pending = null;
        if (intent != null) {
            pending = PendingIntent.getActivity(
                    mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        // NOTE: the ticker is not shown for notifications in the Holo UX
        Notification.Builder builder = new Notification.Builder(mContext)
                .setContentTitle(title)
                .setContentText(contentText)
                .setContentIntent(pending)
                .setLargeIcon(largeIcon)
                .setNumber(number == null ? 0 : number)
                .setSmallIcon(R.drawable.stat_notify_email_generic)
                .setWhen(mClock.getTime())
                .setTicker(ticker);
        setupSoundAndVibration(builder, account);

        Notification notification = builder.getNotification();
        return notification;
    }

    /**
     * Generic notifier for any account.  Uses notification rules from account.
     *
     * @param account The account this notification is being built for.
     * @param ticker Text displayed when the notification is first shown. May be {@code null}.
     * @param title The first line of text. May NOT be {@code null}.
     * @param contentText The second line of text. May NOT be {@code null}.
     * @param intent The intent to start if the user clicks on the notification.
     * @param notificationId The ID of the notification to register with the service.
     */
    private void showAccountNotification(Account account, String ticker, String title,
            String contentText, Intent intent, int notificationId) {
        Notification notification = //nb.getNotification();
                createAccountNotification(account, ticker, title, contentText, intent, null, null);
        mNotificationManager.notify(notificationId, notification);
    }

    /**
     * Cancels the specified notification.
     *
     * @param notificationId The ID of the notification to register with the service.
     */
    private void cancelNotification(int notificationId) {
        mNotificationManager.cancel(notificationId);
    }

    /**
     * Returns a notification ID for new message notifications for the given account.
     */
    private int getNewMessageNotificationId(long accountId) {
        // We assume accountId will always be less than 0x0FFFFFFF; is there a better way?
        return (int) (NOTIFICATION_ID_BASE_NEW_MESSAGES + accountId);
    }

    /**
     * Cancels a "new message" notification for the specified account.
     *
     * @param accountId The ID of the account to cancel for. If {@code -1}, "new message"
     * notifications for all accounts will be canceled.
     */
    public void cancelNewMessageNotification(long accountId) {
        if (accountId == -1) {
            new Utility.ForEachAccount(mContext) {
                @Override
                protected void performAction(long accountId) {
                    cancelNewMessageNotification(accountId);
                }
            }.execute();
        } else {
            mNotificationManager.cancel(getNewMessageNotificationId(accountId));
        }
    }

    /**
     * Show (or update) a "new message" notification for the given account.
     *
     * @param accountId The ID of the account to display a notification for.
     * @param unseenMessageCount The number of messages in the account that are unseen.
     */
    public void showNewMessageNotification(final long accountId, final int unseenMessageCount,
            final int justFetchedCount) {
        EmailAsyncTask.runAsyncParallel(new Runnable() {
            @Override
            public void run() {
                Notification n = createNewMessageNotification(accountId, unseenMessageCount);
                if (n == null) {
                    return;
                }
                mNotificationManager.notify(getNewMessageNotificationId(accountId), n);
            }
        });
    }

    /**
     * Returns a picture of the sender of the given message. If no picture is available, returns
     * {@code null}.
     *
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    private Bitmap getSenderPhoto(Message message) {
        Address sender = Address.unpackFirst(message.mFrom);
        if (sender == null) {
            return null;
        }
        String email = sender.getAddress();
        if (TextUtils.isEmpty(email)) {
            return null;
        }
        return ContactStatusLoader.getContactInfo(mContext, email).mPhoto;
    }

    /**
     * Returns a "new message" notification for the given account.
     *
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    @VisibleForTesting
    Notification createNewMessageNotification(long accountId, int unseenMessageCount) {
        final Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) {
            return null;
        }
        // Get the latest message
        final Message message = Message.getLatestIncomingMessage(mContext, accountId);
        if (message == null) {
            return null; // no message found???
        }

        String senderName = Address.toFriendly(Address.unpack(message.mFrom));
        if (senderName == null) {
            senderName = ""; // Happens when a message has no from.
        }
        final String subject = message.mSubject;
        final Bitmap senderPhoto = getSenderPhoto(message);
        final SpannableString title = getNewMessageTitle(senderName, account.mDisplayName);
        final Intent intent = Welcome.createOpenAccountInboxIntent(mContext, accountId);
        final Bitmap largeIcon = senderPhoto != null ? senderPhoto : mGenericSenderIcon;
        final Integer number = unseenMessageCount > 1 ? unseenMessageCount : null;

        Notification notification =
                createAccountNotification(account, null, title, subject, intent, largeIcon, number);
        return notification;
    }

    /**
     * Creates a notification title for a new message. If there is only 1 email account, just
     * show the sender name. Otherwise, show both the sender and the account name, but, grey
     * out the account name.
     */
    @VisibleForTesting
    SpannableString getNewMessageTitle(String sender, String receiverDisplayName) {
        final int numAccounts = EmailContent.count(mContext, Account.CONTENT_URI);
        if (numAccounts == 1) {
            return new SpannableString(sender);
        } else {
            // "to [account name]"
            String toAcccount = mContext.getResources().getString(R.string.notification_to_account,
                    receiverDisplayName);
            // "[Sender] to [account name]"
            SpannableString senderToAccount = new SpannableString(sender + " " + toAcccount);

            // "[Sender] to [account name]"
            //           ^^^^^^^^^^^^^^^^^ <- Make this part gray
            TextAppearanceSpan secondarySpan = new TextAppearanceSpan(
                    mContext, R.style.notification_secondary_text);
            senderToAccount.setSpan(secondarySpan, sender.length() + 1, senderToAccount.length(),
                    0);
            return senderToAccount;
        }
    }

    /** Returns the system's current ringer mode */
    @VisibleForTesting
    int getRingerMode() {
        return mAudioManager.getRingerMode();
    }

    /** Sets up the notification's sound and vibration based upon account details. */
    @VisibleForTesting
    void setupSoundAndVibration(Notification.Builder builder, Account account) {
        final int flags = account.mFlags;
        final String ringtoneUri = account.mRingtoneUri;
        final boolean vibrate = (flags & Account.FLAGS_VIBRATE_ALWAYS) != 0;
        final boolean vibrateWhenSilent = (flags & Account.FLAGS_VIBRATE_WHEN_SILENT) != 0;
        final boolean isRingerSilent = getRingerMode() != AudioManager.RINGER_MODE_NORMAL;

        int defaults = Notification.DEFAULT_LIGHTS;
        if (vibrate || (vibrateWhenSilent && isRingerSilent)) {
            defaults |= Notification.DEFAULT_VIBRATE;
        }

        builder.setSound((ringtoneUri == null) ? null : Uri.parse(ringtoneUri))
            .setDefaults(defaults);
    }

    /**
     * Show (or update) a notification that the given attachment could not be forwarded. This
     * is a very unusual case, and perhaps we shouldn't even send a notification. For now,
     * it's helpful for debugging.
     *
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    public void showDownloadForwardFailedNotification(Attachment attachment) {
        final Account account = Account.restoreAccountWithId(mContext, attachment.mAccountKey);
        if (account == null) return;
        showAccountNotification(account,
                mContext.getString(R.string.forward_download_failed_ticker),
                mContext.getString(R.string.forward_download_failed_title),
                attachment.mFileName,
                null,
                NOTIFICATION_ID_ATTACHMENT_WARNING);
    }

    /**
     * Returns a notification ID for login failed notifications for the given account account.
     */
    private int getLoginFailedNotificationId(long accountId) {
        return NOTIFICATION_ID_BASE_LOGIN_WARNING + (int)accountId;
    }

    /**
     * Show (or update) a notification that there was a login failure for the given account.
     *
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    public void showLoginFailedNotification(long accountId) {
        final Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) return;
        showAccountNotification(account,
                mContext.getString(R.string.login_failed_ticker, account.mDisplayName),
                mContext.getString(R.string.login_failed_title),
                account.getDisplayName(),
                AccountSettingsXL.createAccountSettingsIntent(mContext, accountId,
                        account.mDisplayName),
                getLoginFailedNotificationId(accountId));
    }

    /**
     * Cancels the login failed notification for the given account.
     */
    public void cancelLoginFailedNotification(long accountId) {
        mNotificationManager.cancel(getLoginFailedNotificationId(accountId));
    }

    /**
     * Show (or update) a notification that the user's password is expiring. The given account
     * is used to update the display text, but, all accounts share the same notification ID.
     *
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    public void showPasswordExpiringNotification(long accountId) {
        Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) return;

        Intent intent = AccountSecurity.actionDevicePasswordExpirationIntent(mContext,
                accountId, false);
        String accountName = account.getDisplayName();
        String ticker =
            mContext.getString(R.string.password_expire_warning_ticker_fmt, accountName);
        String title = mContext.getString(R.string.password_expire_warning_content_title);
        showAccountNotification(account, ticker, title, accountName, intent,
                NOTIFICATION_ID_PASSWORD_EXPIRING);
    }

    /**
     * Show (or update) a notification that the user's password has expired. The given account
     * is used to update the display text, but, all accounts share the same notification ID.
     *
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    public void showPasswordExpiredNotification(long accountId) {
        Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) return;

        Intent intent = AccountSecurity.actionDevicePasswordExpirationIntent(mContext,
                accountId, true);
        String accountName = account.getDisplayName();
        String ticker = mContext.getString(R.string.password_expired_ticker);
        String title = mContext.getString(R.string.password_expired_content_title);
        showAccountNotification(account, ticker, title, accountName, intent,
                NOTIFICATION_ID_PASSWORD_EXPIRED);
    }

    /**
     * Cancels any password expire notifications [both expired & expiring].
     */
    public void cancelPasswordExpirationNotifications() {
        cancelNotification(NOTIFICATION_ID_PASSWORD_EXPIRING);
        cancelNotification(NOTIFICATION_ID_PASSWORD_EXPIRED);
    }

    /**
     * Show (or update) a security needed notification. The given account is used to update
     * the display text, but, all accounts share the same notification ID.
     */
    public void showSecurityNeededNotification(Account account) {
        Intent intent = AccountSecurity.actionUpdateSecurityIntent(mContext, account.mId, true);
        String accountName = account.getDisplayName();
        String ticker =
            mContext.getString(R.string.security_notification_ticker_fmt, accountName);
        String title = mContext.getString(R.string.security_notification_content_title);
        showAccountNotification(account, ticker, title, accountName, intent,
                NOTIFICATION_ID_SECURITY_NEEDED);
    }

    /**
     * Cancels the security needed notification.
     */
    public void cancelSecurityNeededNotification() {
        cancelNotification(NOTIFICATION_ID_SECURITY_NEEDED);
    }
}
