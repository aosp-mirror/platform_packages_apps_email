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
import com.android.email.activity.setup.AccountSettings;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;

import com.google.common.annotations.VisibleForTesting;

import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.TextAppearanceSpan;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;

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

    /** Selection to retrieve accounts that should we notify user for changes */
    private final static String NOTIFIED_ACCOUNT_SELECTION =
        Account.FLAGS + "&" + Account.FLAGS_NOTIFY_NEW_MAIL + " != 0";

    private static NotificationThread sNotificationThread;
    private static Handler sNotificationHandler;
    private static NotificationController sInstance;
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final AudioManager mAudioManager;
    private final Bitmap mGenericSenderIcon;
    private final Clock mClock;
    // TODO We're maintaining all of our structures based upon the account ID. This is fine
    // for now since the assumption is that we only ever look for changes in an account's
    // INBOX. We should adjust our logic to use the mailbox ID instead.
    /** Maps account id to the message data */
    private final HashMap<Long, MessageData> mNotificationMap;
    private ContentObserver mAccountObserver;
    /**
     * Suspend notifications for this account. If {@link Account#NO_ACCOUNT}, no
     * account notifications are suspended. If {@link Account#ACCOUNT_ID_COMBINED_VIEW},
     * notifications for all accounts are suspended.
     */
    private long mSuspendAccountId = Account.NO_ACCOUNT;

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
        mNotificationMap = new HashMap<Long, MessageData>();
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
     * @param number A number to display using {@link Builder#setNumber(int)}. May
     *        be {@code null}.
     * @param enableAudio If {@code false}, do not play any sound. Otherwise, play sound according
     *        to the settings for the given account.
     * @return A {@link Notification} that can be sent to the notification service.
     */
    private Notification createAccountNotification(Account account, String ticker,
            CharSequence title, String contentText, Intent intent, Bitmap largeIcon,
            Integer number, boolean enableAudio) {
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

        if (enableAudio) {
            setupSoundAndVibration(builder, account);
        }

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
        Notification notification = createAccountNotification(account, ticker, title, contentText,
                intent, null, null, true);
        mNotificationManager.notify(notificationId, notification);
    }

    /**
     * Returns a notification ID for new message notifications for the given account.
     */
    private int getNewMessageNotificationId(long accountId) {
        // We assume accountId will always be less than 0x0FFFFFFF; is there a better way?
        return (int) (NOTIFICATION_ID_BASE_NEW_MESSAGES + accountId);
    }

    /**
     * Tells the notification controller if it should be watching for changes to the message table.
     * This is the main life cycle method for message notifications. When we stop observing
     * database changes, we save the state [e.g. message ID and count] of the most recent
     * notification shown to the user. And, when we start observing database changes, we restore
     * the saved state.
     * @param watch If {@code true}, we register observers for all accounts whose settings have
     *              notifications enabled. Otherwise, all observers are unregistered.
     */
    public void watchForMessages(final boolean watch) {
        // Don't create the thread if we're only going to stop watching
        if (!watch && sNotificationThread == null) return;

        ensureHandlerExists();
        // Run this on the message notification handler
        sNotificationHandler.post(new Runnable() {
            @Override
            public void run() {
                ContentResolver resolver = mContext.getContentResolver();
                HashMap<Long, long[]> table;
                if (!watch) {
                    unregisterMessageNotification(Account.ACCOUNT_ID_COMBINED_VIEW);
                    if (mAccountObserver != null) {
                        resolver.unregisterContentObserver(mAccountObserver);
                        mAccountObserver = null;
                    }

                    // tear down the event loop
                    sNotificationThread.quit();
                    sNotificationThread = null;
                    return;
                }

                // otherwise, start new observers for all notified accounts
                registerMessageNotification(Account.ACCOUNT_ID_COMBINED_VIEW);
                // If we're already observing account changes, don't do anything else
                if (mAccountObserver == null) {
                    mAccountObserver = new AccountContentObserver(sNotificationHandler, mContext);
                    resolver.registerContentObserver(Account.NOTIFIER_URI, true, mAccountObserver);
                }
            }
        });
    }

    /**
     * Temporarily suspend a single account from receiving notifications. NOTE: only a single
     * account may ever be suspended at a time. So, if this method is invoked a second time,
     * notifications for the previously suspended account will automatically be re-activated.
     * @param suspend If {@code true}, suspend notifications for the given account. Otherwise,
     *              re-activate notifications for the previously suspended account.
     * @param accountId The ID of the account. If this is the special account ID
     *              {@link Account#ACCOUNT_ID_COMBINED_VIEW},  notifications for all accounts are
     *              suspended. If {@code suspend} is {@code false}, the account ID is ignored.
     */
    public void suspendMessageNotification(boolean suspend, long accountId) {
        if (mSuspendAccountId != Account.NO_ACCOUNT) {
            // we're already suspending an account; un-suspend it
            mSuspendAccountId = Account.NO_ACCOUNT;
        }
        if (suspend && accountId != Account.NO_ACCOUNT && accountId > 0L) {
            mSuspendAccountId = accountId;
            if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
                // Only go onto the notification handler if we really, absolutely need to
                ensureHandlerExists();
                sNotificationHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        for (long accountId : mNotificationMap.keySet()) {
                            mNotificationManager.cancel(getNewMessageNotificationId(accountId));
                        }
                    }
                });
            } else {
                mNotificationManager.cancel(getNewMessageNotificationId(accountId));
            }
        }
    }

    /**
     * Ensures the notification handler exists and is ready to handle requests.
     */
    private static synchronized void ensureHandlerExists() {
        if (sNotificationThread == null) {
            sNotificationThread = new NotificationThread();
            sNotificationHandler = new Handler(sNotificationThread.getLooper());
        }
    }

    /**
     * Registers an observer for changes to the INBOX for the given account. Since accounts
     * may only have a single INBOX, we will never have more than one observer for an account.
     * NOTE: This must be called on the notification handler thread.
     * @param accountId The ID of the account to register the observer for. May be
     *                  {@link Account#ACCOUNT_ID_COMBINED_VIEW} to register observers for all
     *                  accounts that allow for user notification.
     */
    private void registerMessageNotification(long accountId) {
        ContentResolver resolver = mContext.getContentResolver();
        if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
            Cursor c = resolver.query(
                    Account.CONTENT_URI, EmailContent.ID_PROJECTION,
                    NOTIFIED_ACCOUNT_SELECTION, null, null);
            try {
                while (c.moveToNext()) {
                    long id = c.getLong(EmailContent.ID_PROJECTION_COLUMN);
                    registerMessageNotification(id);
                }
            } finally {
                c.close();
            }
        } else {
            MessageData data = mNotificationMap.get(accountId);
            if (data != null) return;  // we're already observing; nothing to do

            data = new MessageData();
            Mailbox mailbox = Mailbox.restoreMailboxOfType(mContext, accountId, Mailbox.TYPE_INBOX);
            if (mailbox == null) {
                Log.w(Logging.LOG_TAG, "Could not load INBOX for account id: " + accountId);
                return;
            }
            ContentObserver observer = new MessageContentObserver(
                    sNotificationHandler, mContext, mailbox.mId, accountId);
            resolver.registerContentObserver(Message.NOTIFIER_URI, true, observer);
            data.mObserver = observer;
            mNotificationMap.put(accountId, data);
            // Now, ping the observer for any initial notifications
            data.mObserver.onChange(true);
        }
    }

    /**
     * Unregisters the observer for the given account. If the specified account does not have
     * a registered observer, no action is performed. This will not clear any existing notification
     * for the specified account. Use {@link NotificationManager#cancel(int)}.
     * NOTE: This must be called on the notification handler thread.
     * @param accountId The ID of the account to unregister from. To unregister all accounts that
     *                  have observers, specify an ID of {@link Account#ACCOUNT_ID_COMBINED_VIEW}.
     */
    private void unregisterMessageNotification(long accountId) {
        ContentResolver resolver = mContext.getContentResolver();
        if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
            // cancel all existing message observers
            for (MessageData data : mNotificationMap.values()) {
                ContentObserver observer = data.mObserver;
                resolver.unregisterContentObserver(observer);
            }
            mNotificationMap.clear();
        } else {
            MessageData data = mNotificationMap.remove(accountId);
            if (data != null) {
                ContentObserver observer = data.mObserver;
                resolver.unregisterContentObserver(observer);
            }
        }
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
    Notification createNewMessageNotification(long accountId, long mailboxId, long messageId,
            int unseenMessageCount, boolean enableAudio) {
        final Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) {
            return null;
        }
        // Get the latest message
        final Message message = Message.restoreMessageWithId(mContext, messageId);
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
        final Bitmap largeIcon = senderPhoto != null ? senderPhoto : mGenericSenderIcon;
        final Integer number = unseenMessageCount > 1 ? unseenMessageCount : null;
        final Intent intent;
        if (unseenMessageCount >= 1) {
            intent = Welcome.createOpenMessageIntent(mContext, accountId, mailboxId, messageId);
        } else {
            intent = Welcome.createOpenAccountInboxIntent(mContext, accountId);
        }

        Notification notification = createAccountNotification(account, null, title, subject,
                intent, largeIcon, number, enableAudio);
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
                AccountSettings.createAccountSettingsIntent(mContext, accountId,
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
        mNotificationManager.cancel(NOTIFICATION_ID_PASSWORD_EXPIRING);
        mNotificationManager.cancel(NOTIFICATION_ID_PASSWORD_EXPIRED);
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
        mNotificationManager.cancel(NOTIFICATION_ID_SECURITY_NEEDED);
    }

    /**
     * Observer invoked whenever a message we're notifying the user about changes.
     */
    private static class MessageContentObserver extends ContentObserver {
        /** A selection to get messages the user hasn't seen before */
        private final static String MESSAGE_SELECTION =
            MessageColumns.MAILBOX_KEY + "=? AND " + MessageColumns.ID + ">? AND "
            + MessageColumns.FLAG_READ + "=0";
        private final Context mContext;
        private final long mMailboxId;
        private final long mAccountId;

        public MessageContentObserver(
                Handler handler, Context context, long mailboxId, long accountId) {
            super(handler);
            mContext = context;
            mMailboxId = mailboxId;
            mAccountId = accountId;
        }

        @Override
        public void onChange(boolean selfChange) {
            if (mAccountId == sInstance.mSuspendAccountId
                    || sInstance.mSuspendAccountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
                return;
            }

            MessageData data = sInstance.mNotificationMap.get(mAccountId);
            if (data == null) {
                // notification for a mailbox that we aren't observing; this should not happen
                Log.e(Logging.LOG_TAG, "Received notifiaction when observer data was null");
                return;
            }
            long oldMessageId = data.mNotifiedMessageId;
            int oldMessageCount = data.mNotifiedMessageCount;

            ContentResolver resolver = mContext.getContentResolver();
            long lastSeenMessageId = Utility.getFirstRowLong(
                    mContext, Mailbox.CONTENT_URI,
                    new String[] { MailboxColumns.LAST_SEEN_MESSAGE_KEY },
                    EmailContent.ID_SELECTION,
                    new String[] { Long.toString(mMailboxId) }, null, 0, 0L);
            Cursor c = resolver.query(
                    Message.CONTENT_URI, EmailContent.ID_PROJECTION,
                    MESSAGE_SELECTION,
                    new String[] { Long.toString(mMailboxId), Long.toString(lastSeenMessageId) },
                    MessageColumns.ID + " DESC");
            if (c == null) {
                // Suspender time ... theoretically, this will never happen
                Log.wtf(Logging.LOG_TAG, "#onChange(); NULL response for message id query");
                return;
            }
            try {
                int newMessageCount = c.getCount();
                long newMessageId = 0L;
                if (c.moveToNext()) {
                    newMessageId = c.getLong(EmailContent.ID_PROJECTION_COLUMN);
                }

                if (newMessageCount == 0) {
                    // No messages to notify for; clear the notification
                    int notificationId = sInstance.getNewMessageNotificationId(mAccountId);
                    sInstance.mNotificationManager.cancel(notificationId);
                } else if (newMessageCount != oldMessageCount
                        || (newMessageId != 0 && newMessageId != oldMessageId)) {
                    // Either the count or last message has changed; update the notification
                    boolean playAudio = (oldMessageCount == 0); // play audio on first notification
                    Notification n = sInstance.createNewMessageNotification(
                            mAccountId, mMailboxId, newMessageId, newMessageCount, playAudio);
                    if (n != null) {
                        // Make the notification visible
                        sInstance.mNotificationManager.notify(
                                sInstance.getNewMessageNotificationId(mAccountId), n);
                    }
                }
                data.mNotifiedMessageId = newMessageId;
                data.mNotifiedMessageCount = newMessageCount;
            } finally {
                c.close();
            }
        }
    }

    /**
     * Observer invoked whenever an account is modified. This could mean the user changed the
     * notification settings.
     */
    private static class AccountContentObserver extends ContentObserver {
        private final Context mContext;
        public AccountContentObserver(Handler handler, Context context) {
            super(handler);
            mContext = context;
        }

        @Override
        public void onChange(boolean selfChange) {
            final ContentResolver resolver = mContext.getContentResolver();
            final Cursor c = resolver.query(
                Account.CONTENT_URI, EmailContent.ID_PROJECTION,
                NOTIFIED_ACCOUNT_SELECTION, null, null);
            final HashSet<Long> newAccountList = new HashSet<Long>();
            final HashSet<Long> removedAccountList = new HashSet<Long>();
            if (c == null) {
                // Suspender time ... theoretically, this will never happen
                Log.wtf(Logging.LOG_TAG, "#onChange(); NULL response for account id query");
                return;
            }
            try {
                while (c.moveToNext()) {
                    long accountId = c.getLong(EmailContent.ID_PROJECTION_COLUMN);
                    newAccountList.add(accountId);
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
            // NOTE: Looping over three lists is not necessarily the most efficient. However, the
            // account lists are going to be very small, so, this will not be necessarily bad.
            // Cycle through existing notification list and adjust as necessary
            for (long accountId : sInstance.mNotificationMap.keySet()) {
                if (!newAccountList.remove(accountId)) {
                    // account id not in the current set of notifiable accounts
                    removedAccountList.add(accountId);
                }
            }
            // A new account was added to the notification list
            for (long accountId : newAccountList) {
                sInstance.registerMessageNotification(accountId);
            }
            // An account was removed from the notification list
            for (long accountId : removedAccountList) {
                sInstance.unregisterMessageNotification(accountId);
                int notificationId = sInstance.getNewMessageNotificationId(accountId);
                sInstance.mNotificationManager.cancel(notificationId);
            }
        }
    }

    /** Information about the message(s) we're notifying the user about. */
    private static class MessageData {
        /** The database observer */
        ContentObserver mObserver;
        /** Message ID used in the user notification */
        long mNotifiedMessageId;
        /** Message count used in the user notification */
        int mNotifiedMessageCount;
    }

    /**
     * Thread to handle all notification actions through its own {@link Looper}.
     */
    private static class NotificationThread implements Runnable {
        /** Lock to ensure proper initialization */
        private final Object mLock = new Object();
        /** The {@link Looper} that handles messages for this thread */
        private Looper mLooper;

        NotificationThread() {
            new Thread(null, this, "EmailNotification").start();
            synchronized (mLock) {
                while (mLooper == null) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ex) {
                    }
                }
            }
        }

        @Override
        public void run() {
            synchronized (mLock) {
                Looper.prepare();
                mLooper = Looper.myLooper();
                mLock.notifyAll();
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            Looper.loop();
        }
        void quit() {
            mLooper.quit();
        }
        Looper getLooper() {
            return mLooper;
        }
    }
}
