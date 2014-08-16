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

import android.app.Notification;
import android.app.Notification.Builder;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;
import android.text.format.DateUtils;

import com.android.email.activity.setup.AccountSecurity;
import com.android.email.activity.setup.HeadlessAccountSettingsLoader;
import com.android.email.provider.EmailProvider;
import com.android.email.service.EmailServiceUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.mail.preferences.FolderPreferences;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.Clock;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.NotificationUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Class that manages notifications.
 */
public class NotificationController {
    private static final String LOG_TAG = LogTag.getLogTag();

    private static final int NOTIFICATION_ID_ATTACHMENT_WARNING = 3;
    private static final int NOTIFICATION_ID_PASSWORD_EXPIRING = 4;
    private static final int NOTIFICATION_ID_PASSWORD_EXPIRED = 5;

    private static final int NOTIFICATION_ID_BASE_MASK = 0xF0000000;
    private static final int NOTIFICATION_ID_BASE_LOGIN_WARNING = 0x20000000;
    private static final int NOTIFICATION_ID_BASE_SECURITY_NEEDED = 0x30000000;
    private static final int NOTIFICATION_ID_BASE_SECURITY_CHANGED = 0x40000000;

    private static NotificationThread sNotificationThread;
    private static Handler sNotificationHandler;
    private static NotificationController sInstance;
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final Clock mClock;
    /** Maps account id to its observer */
    private final Map<Long, ContentObserver> mNotificationMap =
            new HashMap<Long, ContentObserver>();
    private ContentObserver mAccountObserver;

    /** Constructor */
    protected NotificationController(Context context, Clock clock) {
        mContext = context.getApplicationContext();
        EmailContent.init(context);
        mNotificationManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
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
     * Return whether or not a notification, based on the passed-in id, needs to be "ongoing"
     * @param notificationId the notification id to check
     * @return whether or not the notification must be "ongoing"
     */
    private static boolean needsOngoingNotification(int notificationId) {
        // "Security needed" must be ongoing so that the user doesn't close it; otherwise, sync will
        // be prevented until a reboot.  Consider also doing this for password expired.
        return (notificationId & NOTIFICATION_ID_BASE_MASK) == NOTIFICATION_ID_BASE_SECURITY_NEEDED;
    }

    /**
     * Returns a {@link android.support.v4.app.NotificationCompat.Builder} for an event with the
     * given account. The account contains specific rules on ring tone usage and these will be used
     * to modify the notification behaviour.
     *
     * @param accountId The id of the account this notification is being built for.
     * @param ticker Text displayed when the notification is first shown. May be {@code null}.
     * @param title The first line of text. May NOT be {@code null}.
     * @param contentText The second line of text. May NOT be {@code null}.
     * @param intent The intent to start if the user clicks on the notification.
     * @param largeIcon A large icon. May be {@code null}
     * @param number A number to display using {@link Builder#setNumber(int)}. May be {@code null}.
     * @param enableAudio If {@code false}, do not play any sound. Otherwise, play sound according
     *        to the settings for the given account.
     * @return A {@link Notification} that can be sent to the notification service.
     */
    private NotificationCompat.Builder createBaseAccountNotificationBuilder(long accountId,
            String ticker, CharSequence title, String contentText, Intent intent, Bitmap largeIcon,
            Integer number, boolean enableAudio, boolean ongoing) {
        // Pending Intent
        PendingIntent pending = null;
        if (intent != null) {
            pending = PendingIntent.getActivity(
                    mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        // NOTE: the ticker is not shown for notifications in the Holo UX
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext)
                .setContentTitle(title)
                .setContentText(contentText)
                .setContentIntent(pending)
                .setLargeIcon(largeIcon)
                .setNumber(number == null ? 0 : number)
                .setSmallIcon(R.drawable.ic_notification_mail_24dp)
                .setWhen(mClock.getTime())
                .setTicker(ticker)
                .setOngoing(ongoing);

        if (enableAudio) {
            Account account = Account.restoreAccountWithId(mContext, accountId);
            setupSoundAndVibration(builder, account);
        }

        return builder;
    }

    /**
     * Generic notifier for any account.  Uses notification rules from account.
     *
     * @param accountId The account id this notification is being built for.
     * @param ticker Text displayed when the notification is first shown. May be {@code null}.
     * @param title The first line of text. May NOT be {@code null}.
     * @param contentText The second line of text. May NOT be {@code null}.
     * @param intent The intent to start if the user clicks on the notification.
     * @param notificationId The ID of the notification to register with the service.
     */
    private void showNotification(long accountId, String ticker, String title,
            String contentText, Intent intent, int notificationId) {
        final NotificationCompat.Builder builder = createBaseAccountNotificationBuilder(accountId,
                ticker, title, contentText, intent, null, null, true,
                needsOngoingNotification(notificationId));
        mNotificationManager.notify(notificationId, builder.build());
    }

    /**
     * Tells the notification controller if it should be watching for changes to the message table.
     * This is the main life cycle method for message notifications. When we stop observing
     * database changes, we save the state [e.g. message ID and count] of the most recent
     * notification shown to the user. And, when we start observing database changes, we restore
     * the saved state.
     */
    public void watchForMessages() {
        ensureHandlerExists();
        // Run this on the message notification handler
        sNotificationHandler.post(new Runnable() {
            @Override
            public void run() {
                ContentResolver resolver = mContext.getContentResolver();

                // otherwise, start new observers for all notified accounts
                registerMessageNotification(Account.ACCOUNT_ID_COMBINED_VIEW);
                // If we're already observing account changes, don't do anything else
                if (mAccountObserver == null) {
                    LogUtils.i(LOG_TAG, "Observing account changes for notifications");
                    mAccountObserver = new AccountContentObserver(sNotificationHandler, mContext);
                    resolver.registerContentObserver(Account.NOTIFIER_URI, true, mAccountObserver);
                }
            }
        });
    }

    /**
     * Ensures the notification handler exists and is ready to handle requests.
     */

    /**
     * TODO: Notifications jump around too much because we get too many content updates.
     * We should try to make the provider generate fewer updates instead.
     */

    private static final int NOTIFICATION_DELAYED_MESSAGE = 0;
    private static final long NOTIFICATION_DELAY = 15 * DateUtils.SECOND_IN_MILLIS;
    // True if we're coalescing notification updates
    private static boolean sNotificationDelayedMessagePending;
    // True if accounts have changed and we need to refresh everything
    private static boolean sRefreshAllNeeded;
    // Set of accounts we need to regenerate notifications for
    private static final HashSet<Long> sRefreshAccountSet = new HashSet<Long>();
    // These should all be accessed on-thread, but just in case...
    private static final Object sNotificationDelayedMessageLock = new Object();

    private static synchronized void ensureHandlerExists() {
        if (sNotificationThread == null) {
            sNotificationThread = new NotificationThread();
            sNotificationHandler = new Handler(sNotificationThread.getLooper(),
                    new Handler.Callback() {
                        @Override
                        public boolean handleMessage(final android.os.Message message) {
                            /**
                             * To reduce spamming the notifications, we quiesce updates for a few
                             * seconds to batch them up, then handle them here.
                             */
                            LogUtils.d(LOG_TAG, "Delayed notification processing");
                            synchronized (sNotificationDelayedMessageLock) {
                                sNotificationDelayedMessagePending = false;
                                final Context context = (Context)message.obj;
                                if (sRefreshAllNeeded) {
                                    sRefreshAllNeeded = false;
                                    refreshAllNotificationsInternal(context);
                                }
                                for (final Long accountId : sRefreshAccountSet) {
                                    refreshNotificationsForAccountInternal(context, accountId);
                                }
                                sRefreshAccountSet.clear();
                            }
                            return true;
                        }
                    });
        }
    }

    /**
     * Registers an observer for changes to mailboxes in the given account.
     * NOTE: This must be called on the notification handler thread.
     * @param accountId The ID of the account to register the observer for. May be
     *                  {@link Account#ACCOUNT_ID_COMBINED_VIEW} to register observers for all
     *                  accounts that allow for user notification.
     */
    private void registerMessageNotification(final long accountId) {
        ContentResolver resolver = mContext.getContentResolver();
        if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
            Cursor c = resolver.query(
                    Account.CONTENT_URI, EmailContent.ID_PROJECTION,
                    null, null, null);
            try {
                while (c.moveToNext()) {
                    long id = c.getLong(EmailContent.ID_PROJECTION_COLUMN);
                    registerMessageNotification(id);
                }
            } finally {
                c.close();
            }
        } else {
            ContentObserver obs = mNotificationMap.get(accountId);
            if (obs != null) return;  // we're already observing; nothing to do
            LogUtils.i(LOG_TAG, "Registering for notifications for account " + accountId);
            ContentObserver observer = new MessageContentObserver(
                    sNotificationHandler, mContext, accountId);
            resolver.registerContentObserver(Message.NOTIFIER_URI, true, observer);
            mNotificationMap.put(accountId, observer);
            // Now, ping the observer for any initial notifications
            observer.onChange(true);
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
    private void unregisterMessageNotification(final long accountId) {
        ContentResolver resolver = mContext.getContentResolver();
        if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
            LogUtils.i(LOG_TAG, "Unregistering notifications for all accounts");
            // cancel all existing message observers
            for (ContentObserver observer : mNotificationMap.values()) {
                resolver.unregisterContentObserver(observer);
            }
            mNotificationMap.clear();
        } else {
            LogUtils.i(LOG_TAG, "Unregistering notifications for account " + accountId);
            ContentObserver observer = mNotificationMap.remove(accountId);
            if (observer != null) {
                resolver.unregisterContentObserver(observer);
            }
        }
    }

    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_CONVERSATION = "conversationUri";
    public static final String EXTRA_FOLDER = "folder";

    /** Sets up the notification's sound and vibration based upon account details. */
    private void setupSoundAndVibration(
            NotificationCompat.Builder builder, Account account) {
        String ringtoneUri = Settings.System.DEFAULT_NOTIFICATION_URI.toString();
        boolean vibrate = false;

        // Use the Inbox notification preferences
        final Cursor accountCursor = mContext.getContentResolver().query(EmailProvider.uiUri(
                "uiaccount", account.mId), UIProvider.ACCOUNTS_PROJECTION, null, null, null);

        com.android.mail.providers.Account uiAccount = null;
        try {
            if (accountCursor.moveToFirst()) {
                uiAccount = com.android.mail.providers.Account.builder().buildFrom(accountCursor);
            }
        } finally {
            accountCursor.close();
        }

        if (uiAccount != null) {
            final Cursor folderCursor =
                    mContext.getContentResolver().query(uiAccount.settings.defaultInbox,
                            UIProvider.FOLDERS_PROJECTION, null, null, null);

            if (folderCursor == null) {
                // This can happen when the notification is for the security policy notification
                // that happens before the account is setup
                LogUtils.w(LOG_TAG, "Null folder cursor for mailbox %s",
                        uiAccount.settings.defaultInbox);
            } else {
                Folder folder = null;
                try {
                    if (folderCursor.moveToFirst()) {
                        folder = new Folder(folderCursor);
                    }
                } finally {
                    folderCursor.close();
                }

                if (folder != null) {
                    final FolderPreferences folderPreferences = new FolderPreferences(
                            mContext, uiAccount.getEmailAddress(), folder, true /* inbox */);

                    ringtoneUri = folderPreferences.getNotificationRingtoneUri();
                    vibrate = folderPreferences.isNotificationVibrateEnabled();
                } else {
                    LogUtils.e(LOG_TAG,
                            "Null folder for mailbox %s", uiAccount.settings.defaultInbox);
                }
            }
        } else {
            LogUtils.e(LOG_TAG, "Null uiAccount for account id %d", account.mId);
        }

        int defaults = Notification.DEFAULT_LIGHTS;
        if (vibrate) {
            defaults |= Notification.DEFAULT_VIBRATE;
        }

        builder.setSound(TextUtils.isEmpty(ringtoneUri) ? null : Uri.parse(ringtoneUri))
            .setDefaults(defaults);
    }

    /**
     * Show (or update) a notification that the given attachment could not be forwarded. This
     * is a very unusual case, and perhaps we shouldn't even send a notification. For now,
     * it's helpful for debugging.
     *
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    public void showDownloadForwardFailedNotificationSynchronous(Attachment attachment) {
        final Message message = Message.restoreMessageWithId(mContext, attachment.mMessageKey);
        if (message == null) return;
        final Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, message.mMailboxKey);
        showNotification(mailbox.mAccountKey,
                mContext.getString(R.string.forward_download_failed_ticker),
                mContext.getString(R.string.forward_download_failed_title),
                attachment.mFileName,
                null,
                NOTIFICATION_ID_ATTACHMENT_WARNING);
    }

    /**
     * Returns a notification ID for login failed notifications for the given account account.
     */
    private static int getLoginFailedNotificationId(long accountId) {
        return NOTIFICATION_ID_BASE_LOGIN_WARNING + (int)accountId;
    }

    /**
     * Show (or update) a notification that there was a login failure for the given account.
     *
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    public void showLoginFailedNotificationSynchronous(long accountId, boolean incoming) {
        final Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) return;
        final Mailbox mailbox = Mailbox.restoreMailboxOfType(mContext, accountId,
                Mailbox.TYPE_INBOX);
        if (mailbox == null) return;

        final Intent settingsIntent;
        if (incoming) {
            settingsIntent = new Intent(Intent.ACTION_VIEW,
                    HeadlessAccountSettingsLoader.getIncomingSettingsUri(accountId));
        } else {
            settingsIntent = new Intent(Intent.ACTION_VIEW,
                    HeadlessAccountSettingsLoader.getOutgoingSettingsUri(accountId));
        }
        showNotification(mailbox.mAccountKey,
                mContext.getString(R.string.login_failed_ticker, account.mDisplayName),
                mContext.getString(R.string.login_failed_title),
                account.getDisplayName(),
                settingsIntent,
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
    public void showPasswordExpiringNotificationSynchronous(long accountId) {
        final Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) return;

        final Intent intent = AccountSecurity.actionDevicePasswordExpirationIntent(mContext,
                accountId, false);
        final String accountName = account.getDisplayName();
        final String ticker =
            mContext.getString(R.string.password_expire_warning_ticker_fmt, accountName);
        final String title = mContext.getString(R.string.password_expire_warning_content_title);
        showNotification(accountId, ticker, title, accountName, intent,
                NOTIFICATION_ID_PASSWORD_EXPIRING);
    }

    /**
     * Show (or update) a notification that the user's password has expired. The given account
     * is used to update the display text, but, all accounts share the same notification ID.
     *
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    public void showPasswordExpiredNotificationSynchronous(long accountId) {
        final Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) return;

        final Intent intent = AccountSecurity.actionDevicePasswordExpirationIntent(mContext,
                accountId, true);
        final String accountName = account.getDisplayName();
        final String ticker = mContext.getString(R.string.password_expired_ticker);
        final String title = mContext.getString(R.string.password_expired_content_title);
        showNotification(accountId, ticker, title, accountName, intent,
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
     * Show (or update) a security needed notification. If tapped, the user is taken to a
     * dialog asking whether he wants to update his settings.
     */
    public void showSecurityNeededNotification(Account account) {
        Intent intent = AccountSecurity.actionUpdateSecurityIntent(mContext, account.mId, true);
        String accountName = account.getDisplayName();
        String ticker =
            mContext.getString(R.string.security_needed_ticker_fmt, accountName);
        String title = mContext.getString(R.string.security_notification_content_update_title);
        showNotification(account.mId, ticker, title, accountName, intent,
                (int)(NOTIFICATION_ID_BASE_SECURITY_NEEDED + account.mId));
    }

    /**
     * Show (or update) a security changed notification. If tapped, the user is taken to the
     * account settings screen where he can view the list of enforced policies
     */
    public void showSecurityChangedNotification(Account account) {
        final Intent intent = new Intent(Intent.ACTION_VIEW,
                HeadlessAccountSettingsLoader.getIncomingSettingsUri(account.getId()));
        final String accountName = account.getDisplayName();
        final String ticker =
            mContext.getString(R.string.security_changed_ticker_fmt, accountName);
        final String title =
                mContext.getString(R.string.security_notification_content_change_title);
        showNotification(account.mId, ticker, title, accountName, intent,
                (int)(NOTIFICATION_ID_BASE_SECURITY_CHANGED + account.mId));
    }

    /**
     * Show (or update) a security unsupported notification. If tapped, the user is taken to the
     * account settings screen where he can view the list of unsupported policies
     */
    public void showSecurityUnsupportedNotification(Account account) {
        final Intent intent = new Intent(Intent.ACTION_VIEW,
                HeadlessAccountSettingsLoader.getIncomingSettingsUri(account.getId()));
        final String accountName = account.getDisplayName();
        final String ticker =
            mContext.getString(R.string.security_unsupported_ticker_fmt, accountName);
        final String title =
                mContext.getString(R.string.security_notification_content_unsupported_title);
        showNotification(account.mId, ticker, title, accountName, intent,
                (int)(NOTIFICATION_ID_BASE_SECURITY_NEEDED + account.mId));
   }

    /**
     * Cancels all security needed notifications.
     */
    public void cancelSecurityNeededNotification() {
        EmailAsyncTask.runAsyncParallel(new Runnable() {
            @Override
            public void run() {
                Cursor c = mContext.getContentResolver().query(Account.CONTENT_URI,
                        Account.ID_PROJECTION, null, null, null);
                try {
                    while (c.moveToNext()) {
                        long id = c.getLong(Account.ID_PROJECTION_COLUMN);
                        mNotificationManager.cancel(
                               (int)(NOTIFICATION_ID_BASE_SECURITY_NEEDED + id));
                    }
                }
                finally {
                    c.close();
                }
            }});
    }

    /**
     * Cancels all notifications for the specified account id. This includes new mail notifications,
     * as well as special login/security notifications.
     */
    public static void cancelNotifications(final Context context, final Account account) {
        final EmailServiceUtils.EmailServiceInfo serviceInfo
                = EmailServiceUtils.getServiceInfoForAccount(context, account.mId);
        if (serviceInfo == null) {
            LogUtils.d(LOG_TAG, "Can't cancel notification for missing account %d", account.mId);
            return;
        }
        final android.accounts.Account notifAccount
                = account.getAccountManagerAccount(serviceInfo.accountType);

        NotificationUtils.clearAccountNotifications(context, notifAccount);

        final NotificationManager notificationManager = getInstance(context).mNotificationManager;

        notificationManager.cancel((int) (NOTIFICATION_ID_BASE_LOGIN_WARNING + account.mId));
        notificationManager.cancel((int) (NOTIFICATION_ID_BASE_SECURITY_NEEDED + account.mId));
        notificationManager.cancel((int) (NOTIFICATION_ID_BASE_SECURITY_CHANGED + account.mId));
    }

    private static void refreshNotificationsForAccount(final Context context,
            final long accountId) {
        synchronized (sNotificationDelayedMessageLock) {
            if (sNotificationDelayedMessagePending) {
                sRefreshAccountSet.add(accountId);
            } else {
                ensureHandlerExists();
                sNotificationHandler.sendMessageDelayed(
                        android.os.Message.obtain(sNotificationHandler,
                                NOTIFICATION_DELAYED_MESSAGE, context), NOTIFICATION_DELAY);
                sNotificationDelayedMessagePending = true;
                refreshNotificationsForAccountInternal(context, accountId);
            }
        }
    }

    private static void refreshNotificationsForAccountInternal(final Context context,
            final long accountId) {
        final Uri accountUri = EmailProvider.uiUri("uiaccount", accountId);

        final ContentResolver contentResolver = context.getContentResolver();

        final Cursor mailboxCursor = contentResolver.query(
                ContentUris.withAppendedId(EmailContent.MAILBOX_NOTIFICATION_URI, accountId),
                null, null, null, null);
        try {
            while (mailboxCursor.moveToNext()) {
                final long mailboxId =
                        mailboxCursor.getLong(EmailContent.NOTIFICATION_MAILBOX_ID_COLUMN);
                if (mailboxId == 0) continue;

                final int unseenCount = mailboxCursor.getInt(
                        EmailContent.NOTIFICATION_MAILBOX_UNSEEN_COUNT_COLUMN);

                final int unreadCount;
                // If nothing is unseen, clear the notification
                if (unseenCount == 0) {
                    unreadCount = 0;
                } else {
                    unreadCount = mailboxCursor.getInt(
                            EmailContent.NOTIFICATION_MAILBOX_UNREAD_COUNT_COLUMN);
                }

                final Uri folderUri = EmailProvider.uiUri("uifolder", mailboxId);


                LogUtils.d(LOG_TAG, "Changes to account " + accountId + ", folder: "
                        + mailboxId + ", unreadCount: " + unreadCount + ", unseenCount: "
                        + unseenCount);

                final Intent intent = new Intent(UIProvider.ACTION_UPDATE_NOTIFICATION);
                intent.setPackage(context.getPackageName());
                intent.setType(EmailProvider.EMAIL_APP_MIME_TYPE);

                intent.putExtra(UIProvider.UpdateNotificationExtras.EXTRA_ACCOUNT, accountUri);
                intent.putExtra(UIProvider.UpdateNotificationExtras.EXTRA_FOLDER, folderUri);
                intent.putExtra(UIProvider.UpdateNotificationExtras.EXTRA_UPDATED_UNREAD_COUNT,
                        unreadCount);
                intent.putExtra(UIProvider.UpdateNotificationExtras.EXTRA_UPDATED_UNSEEN_COUNT,
                        unseenCount);

                context.sendOrderedBroadcast(intent, null);
            }
        } finally {
            mailboxCursor.close();
        }
    }

    public static void handleUpdateNotificationIntent(Context context, Intent intent) {
        final Uri accountUri =
                intent.getParcelableExtra(UIProvider.UpdateNotificationExtras.EXTRA_ACCOUNT);
        final Uri folderUri =
                intent.getParcelableExtra(UIProvider.UpdateNotificationExtras.EXTRA_FOLDER);
        final int unreadCount = intent.getIntExtra(
                UIProvider.UpdateNotificationExtras.EXTRA_UPDATED_UNREAD_COUNT, 0);
        final int unseenCount = intent.getIntExtra(
                UIProvider.UpdateNotificationExtras.EXTRA_UPDATED_UNSEEN_COUNT, 0);

        final ContentResolver contentResolver = context.getContentResolver();

        final Cursor accountCursor = contentResolver.query(accountUri,
                UIProvider.ACCOUNTS_PROJECTION,  null, null, null);

        if (accountCursor == null) {
            LogUtils.e(LOG_TAG, "Null account cursor for account " + accountUri);
            return;
        }

        com.android.mail.providers.Account account = null;
        try {
            if (accountCursor.moveToFirst()) {
                account = com.android.mail.providers.Account.builder().buildFrom(accountCursor);
            }
        } finally {
            accountCursor.close();
        }

        if (account == null) {
            LogUtils.d(LOG_TAG, "Tried to create a notification for a missing account "
                    + accountUri);
            return;
        }

        final Cursor folderCursor = contentResolver.query(folderUri, UIProvider.FOLDERS_PROJECTION,
                null, null, null);

        if (folderCursor == null) {
            LogUtils.e(LOG_TAG, "Null folder cursor for account " + accountUri + ", mailbox "
                    + folderUri);
            return;
        }

        Folder folder = null;
        try {
            if (folderCursor.moveToFirst()) {
                folder = new Folder(folderCursor);
            } else {
                LogUtils.e(LOG_TAG, "Empty folder cursor for account " + accountUri + ", mailbox "
                        + folderUri);
                return;
            }
        } finally {
            folderCursor.close();
        }

        // TODO: we don't always want getAttention to be true, but we don't necessarily have a
        // good heuristic for when it should or shouldn't be.
        NotificationUtils.sendSetNewEmailIndicatorIntent(context, unreadCount, unseenCount,
                account, folder, true /* getAttention */);
    }

    private static void refreshAllNotifications(final Context context) {
        synchronized (sNotificationDelayedMessageLock) {
            if (sNotificationDelayedMessagePending) {
                sRefreshAllNeeded = true;
            } else {
                ensureHandlerExists();
                sNotificationHandler.sendMessageDelayed(
                        android.os.Message.obtain(sNotificationHandler,
                                NOTIFICATION_DELAYED_MESSAGE, context), NOTIFICATION_DELAY);
                sNotificationDelayedMessagePending = true;
                refreshAllNotificationsInternal(context);
            }
        }
    }

    private static void refreshAllNotificationsInternal(final Context context) {
        NotificationUtils.resendNotifications(
                context, false, null, null, null /* ContactPhotoFetcher */);
    }

    /**
     * Observer invoked whenever a message we're notifying the user about changes.
     */
    private static class MessageContentObserver extends ContentObserver {
        private final Context mContext;
        private final long mAccountId;

        public MessageContentObserver(
                final Handler handler, final Context context, final long accountId) {
            super(handler);
            mContext = context;
            mAccountId = accountId;
        }

        @Override
        public void onChange(final boolean selfChange) {
            refreshNotificationsForAccount(mContext, mAccountId);
        }
    }

    /**
     * Observer invoked whenever an account is modified. This could mean the user changed the
     * notification settings.
     */
    private static class AccountContentObserver extends ContentObserver {
        private final Context mContext;
        public AccountContentObserver(final Handler handler, final Context context) {
            super(handler);
            mContext = context;
        }

        @Override
        public void onChange(final boolean selfChange) {
            final ContentResolver resolver = mContext.getContentResolver();
            final Cursor c = resolver.query(Account.CONTENT_URI, EmailContent.ID_PROJECTION,
                null, null, null);
            final Set<Long> newAccountList = new HashSet<Long>();
            final Set<Long> removedAccountList = new HashSet<Long>();
            if (c == null) {
                // Suspender time ... theoretically, this will never happen
                LogUtils.wtf(LOG_TAG, "#onChange(); NULL response for account id query");
                return;
            }
            try {
                while (c.moveToNext()) {
                    long accountId = c.getLong(EmailContent.ID_PROJECTION_COLUMN);
                    newAccountList.add(accountId);
                }
            } finally {
                c.close();
            }
            // NOTE: Looping over three lists is not necessarily the most efficient. However, the
            // account lists are going to be very small, so, this will not be necessarily bad.
            // Cycle through existing notification list and adjust as necessary
            for (final long accountId : sInstance.mNotificationMap.keySet()) {
                if (!newAccountList.remove(accountId)) {
                    // account id not in the current set of notifiable accounts
                    removedAccountList.add(accountId);
                }
            }
            // A new account was added to the notification list
            for (final long accountId : newAccountList) {
                sInstance.registerMessageNotification(accountId);
            }
            // An account was removed from the notification list
            for (final long accountId : removedAccountList) {
                sInstance.unregisterMessageNotification(accountId);
            }

            refreshAllNotifications(mContext);
        }
    }

    /**
     * Thread to handle all notification actions through its own {@link Looper}.
     */
    private static class NotificationThread implements Runnable {
        /** Lock to ensure proper initialization */
        private final Object mLock = new Object();
        /** The {@link Looper} that handles messages for this thread */
        private Looper mLooper;

        public NotificationThread() {
            new Thread(null, this, "EmailNotification").start();
            synchronized (mLock) {
                while (mLooper == null) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException ex) {
                        // Loop around and wait again
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

        public Looper getLooper() {
            return mLooper;
        }
    }
}
