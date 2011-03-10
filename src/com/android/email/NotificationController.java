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
import com.android.email.activity.setup.AccountSettingsXL;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.utility.Utility;

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

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Class that manages notifications.
 *
 * TODO Gather all notification related code here
 */
public class NotificationController {
    public static final int NOTIFICATION_ID_SECURITY_NEEDED = 1;
    public static final int NOTIFICATION_ID_EXCHANGE_CALENDAR_ADDED = 2;
    public static final int NOTIFICATION_ID_ATTACHMENT_WARNING = 3;
    public static final int NOTIFICATION_ID_PASSWORD_EXPIRING = 4;
    public static final int NOTIFICATION_ID_PASSWORD_EXPIRED = 5;

    private static final int NOTIFICATION_ID_BASE_NEW_MESSAGES = 0x10000000;
    private static final int NOTIFICATION_ID_BASE_LOGIN_WARNING = 0x20000000;

    private static NotificationController sInstance;
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final AudioManager mAudioManager;
    private final Bitmap mGenericSenderIcon;
    private final Clock mClock;

    /** Constructor */
    /* package */ NotificationController(Context context, Clock clock) {
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
     * Generic notifier for any account.  Uses notification rules from account.
     * NOTE:  Ticker is not shown in Holo XL notifications.
     *
     * @param account The account for which the notification is posted
     * @param ticker String for ticker
     * @param contentTitle String for notification content title
     * @param contentText String for notification content text
     * @param intent The intent to launch from the notification
     * @param notificationId The notification id
     */
    public void postAccountNotification(Account account, String ticker, String contentTitle,
            String contentText, Intent intent, int notificationId) {

        // Pending Intent
        PendingIntent pending = null;
        if (intent != null) {
            intent = rewriteForPendingIntent(intent);
            pending =
                PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        // Ringtone & Vibration
        String ringtoneString = account.getRingtone();
        Uri ringTone = (ringtoneString == null) ? null : Uri.parse(ringtoneString);
        boolean vibrate = 0 != (account.mFlags & Account.FLAGS_VIBRATE_ALWAYS);
        boolean vibrateWhenSilent = 0 != (account.mFlags & Account.FLAGS_VIBRATE_WHEN_SILENT);

        // Use the account's notification rules for sound & vibrate (but always notify)
        boolean nowSilent =
            mAudioManager.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE;

        int defaults = Notification.DEFAULT_LIGHTS;
        if (vibrate || (vibrateWhenSilent && nowSilent)) {
            defaults |= Notification.DEFAULT_VIBRATE;
        }

        // Notification
        Notification.Builder nb = new Notification.Builder(mContext);
        nb.setSmallIcon(R.drawable.stat_notify_email_generic);
        nb.setTicker(ticker);
        nb.setContentTitle(contentTitle);
        nb.setContentText(contentText);
        nb.setContentIntent(pending);
        nb.setSound(ringTone);
        nb.setDefaults(defaults);
        Notification notification = nb.getNotification();

        mNotificationManager.notify(notificationId, notification);
    }

    /**
     * Generic notification canceler.
     * @param notificationId The notification id
     */
    public void cancelNotification(int notificationId) {
        mNotificationManager.cancel(notificationId);
    }

    /**
     * @return the "new message" notification ID for an account. It just assumes
     *         accountID won't be too huge. Any other smarter/cleaner way?
     */
    private int getNewMessageNotificationId(long accountId) {
        return (int) (NOTIFICATION_ID_BASE_NEW_MESSAGES + accountId);
    }

    /**
     * Dismiss new message notification
     *
     * @param accountId ID of the target account, or -1 for all accounts.
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
     * Show (or update) the "new message" notification.
     */
    public void showNewMessageNotification(final long accountId, final int unseenMessageCount,
            final int justFetchedCount) {
        Utility.runAsync(new Runnable() {
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
     * @return The sender's photo, if available, or null.
     *
     * Don't call it on the UI thread.
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
        return ContactStatusLoader.load(mContext, email).mPhoto;
    }

    private static final AtomicInteger sSequenceNumber = new AtomicInteger();

    /**
     * Rewrite an intent so that it'll always look unique to {@link PendingIntent}.
     *
     * TODO This should be removed.  Instead, use URIs which is unique to each account to open
     * activities.
     */
    private static Intent rewriteForPendingIntent(Intent original) {
        if (original.getComponent() == null) {
            return original; // Doesn't have a component set -- can't set a URI.
        }
        Uri.Builder builder = new Uri.Builder();
        builder.scheme("content");
        builder.authority("email-dummy");
        builder.appendEncodedPath(Integer.toString(sSequenceNumber.incrementAndGet()));

        // If a componentName is set, the data part won't be used to resolve an intent.
        original.setData(builder.build());
        return original;
    }

    /**
     * Create a notification
     *
     * Don't call it on the UI thread.
     */
    /* package */ Notification createNewMessageNotification(long accountId,
            int unseenMessageCount) {
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

        // Intent to open inbox
        PendingIntent contentIntent = PendingIntent.getActivity(mContext, 0,
                rewriteForPendingIntent(Welcome.createOpenAccountInboxIntent(mContext, accountId)),
                0);

        Notification.Builder builder = new Notification.Builder(mContext)
                .setSmallIcon(R.drawable.stat_notify_email_generic)
                .setWhen(mClock.getTime())
                .setLargeIcon(senderPhoto != null ? senderPhoto : mGenericSenderIcon)
                .setContentTitle(getNotificationTitle(senderName, account.mDisplayName))
                .setContentText(subject)
                .setContentIntent(contentIntent);
        if (unseenMessageCount > 1) {
            builder.setNumber(unseenMessageCount);
        }

        Notification notification = builder.getNotification();

        setupNotificationSoundAndVibrationFromAccount(notification, account);
        return notification;
    }

    /**
     * Creates the notification title.
     *
     * If only 1 account, just show the sender name.
     * If 2+ accounts, make it "SENDER_NAME to RECEIVER_NAME", and gray out the "to RECEIVER_NAME"
     * part.
     */
    /* package */ SpannableString getNotificationTitle(String sender, String receiverDisplayName) {
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

    // Overridden for testing (AudioManager can't be mocked out.)
    /* package */ int getRingerMode() {
        return mAudioManager.getRingerMode();
    }

    /* package */ boolean isRingerModeSilent() {
        return getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
    }

    /* package */ void setupNotificationSoundAndVibrationFromAccount(Notification notification,
            Account account) {
        final int flags = account.mFlags;
        final String ringtoneUri = account.mRingtoneUri;
        final boolean vibrate = (flags & Account.FLAGS_VIBRATE_ALWAYS) != 0;
        final boolean vibrateWhenSilent = (flags & Account.FLAGS_VIBRATE_WHEN_SILENT) != 0;

        notification.sound = (ringtoneUri == null) ? null : Uri.parse(ringtoneUri);

        if (vibrate || (vibrateWhenSilent && isRingerModeSilent())) {
            notification.defaults |= Notification.DEFAULT_VIBRATE;
        }

        // This code is identical to that used by Gmail and GTalk for notifications
        notification.flags |= Notification.FLAG_SHOW_LIGHTS;
        notification.defaults |= Notification.DEFAULT_LIGHTS;
    }

    /**
     * Alert the user that an attachment couldn't be forwarded.  This is a very unusual case, and
     * perhaps we shouldn't even send a notification. For now, it's helpful for debugging.
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    public void showDownloadForwardFailedNotification(Attachment attachment) {
        final Account account = Account.restoreAccountWithId(mContext, attachment.mAccountKey);
        if (account == null) return;
        postAccountNotification(account,
                mContext.getString(R.string.forward_download_failed_ticker),
                mContext.getString(R.string.forward_download_failed_title),
                attachment.mFileName,
                null,
                NOTIFICATION_ID_ATTACHMENT_WARNING);
    }

    /**
     * Alert the user that login failed for the specified account
     */
    private int getLoginFailedNotificationId(long accountId) {
        return NOTIFICATION_ID_BASE_LOGIN_WARNING + (int)accountId;
    }

    /**
     * Alert the user that login failed on a particular account.
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    public void showLoginFailedNotification(long accountId) {
        final Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) return;
        postAccountNotification(account,
                mContext.getString(R.string.login_failed_ticker, account.mDisplayName),
                mContext.getString(R.string.login_failed_title),
                account.getDisplayName(),
                AccountSettingsXL.createAccountSettingsIntent(mContext, accountId,
                        account.mDisplayName),
                getLoginFailedNotificationId(accountId));
    }

    public void cancelLoginFailedNotification(long accountId) {
        mNotificationManager.cancel(getLoginFailedNotificationId(accountId));
    }
}
