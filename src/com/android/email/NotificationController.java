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
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.TextAppearanceSpan;
import android.util.Log;

import com.android.email.activity.ContactStatusLoader;
import com.android.email.activity.Welcome;
import com.android.email.activity.setup.AccountSecurity;
import com.android.email.activity.setup.AccountSettings;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;
import com.google.common.annotations.VisibleForTesting;

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
    private final Bitmap mGenericMultipleSenderIcon;
    private final Clock mClock;
    // TODO We're maintaining all of our structures based upon the account ID. This is fine
    // for now since the assumption is that we only ever look for changes in an account's
    // INBOX. We should adjust our logic to use the mailbox ID instead.
    /** Maps account id to the message data */
    private final HashMap<Long, ContentObserver> mNotificationMap;
    private ContentObserver mAccountObserver;
    /**
     * Suspend notifications for this account. If {@link Account#NO_ACCOUNT}, no
     * account notifications are suspended. If {@link Account#ACCOUNT_ID_COMBINED_VIEW},
     * notifications for all accounts are suspended.
     */
    private long mSuspendAccountId = Account.NO_ACCOUNT;

    /**
     * Timestamp indicating when the last message notification sound was played.
     * Used for throttling.
     */
    private long mLastMessageNotifyTime;

    /**
     * Minimum interval between notification sounds.
     * Since a long sync (either on account setup or after a long period of being offline) can cause
     * several notifications consecutively, it can be pretty overwhelming to get a barrage of
     * notification sounds. Throttle them using this value.
     */
    private static final long MIN_SOUND_INTERVAL_MS = 15 * 1000; // 15 seconds

    private static boolean isRunningJellybeanOrLater() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN;
    }

    /** Constructor */
    @VisibleForTesting
    NotificationController(Context context, Clock clock) {
        mContext = context.getApplicationContext();
        mNotificationManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        mGenericSenderIcon = BitmapFactory.decodeResource(mContext.getResources(),
                R.drawable.ic_contact_picture);
        mGenericMultipleSenderIcon = BitmapFactory.decodeResource(mContext.getResources(),
                R.drawable.ic_notification_multiple_mail_holo_dark);
        mClock = clock;
        mNotificationMap = new HashMap<Long, ContentObserver>();
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
    private boolean needsOngoingNotification(int notificationId) {
        // "Security needed" must be ongoing so that the user doesn't close it; otherwise, sync will
        // be prevented until a reboot.  Consider also doing this for password expired.
        return notificationId == NOTIFICATION_ID_SECURITY_NEEDED;
    }

    /**
     * Returns a {@link Notification.Builder} for an event with the given account. The account
     * contains specific rules on ring tone usage and these will be used to modify the notification
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
    private Notification.Builder createBaseAccountNotificationBuilder(Account account,
            String ticker, CharSequence title, String contentText, Intent intent, Bitmap largeIcon,
            Integer number, boolean enableAudio, boolean ongoing) {
        // Pending Intent
        PendingIntent pending = null;
        if (intent != null) {
            pending = PendingIntent.getActivity(
                    mContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        // NOTE: the ticker is not shown for notifications in the Holo UX
        final Notification.Builder builder = new Notification.Builder(mContext)
                .setContentTitle(title)
                .setContentText(contentText)
                .setContentIntent(pending)
                .setLargeIcon(largeIcon)
                .setNumber(number == null ? 0 : number)
                .setSmallIcon(R.drawable.stat_notify_email_generic)
                .setWhen(mClock.getTime())
                .setTicker(ticker)
                .setOngoing(ongoing);

        if (enableAudio) {
            setupSoundAndVibration(builder, account);
        }

        return builder;
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
        Notification.Builder builder = createBaseAccountNotificationBuilder(account, ticker, title,
                contentText, intent, null, null, true, needsOngoingNotification(notificationId));
        mNotificationManager.notify(notificationId, builder.getNotification());
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
        if (Email.DEBUG) {
            Log.i(Logging.LOG_TAG, "Notifications being toggled: " + watch);
        }
        // Don't create the thread if we're only going to stop watching
        if (!watch && sNotificationThread == null) return;

        ensureHandlerExists();
        // Run this on the message notification handler
        sNotificationHandler.post(new Runnable() {
            @Override
            public void run() {
                ContentResolver resolver = mContext.getContentResolver();
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
                    if (Email.DEBUG) {
                        Log.i(Logging.LOG_TAG, "Observing account changes for notifications");
                    }
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
            ContentObserver obs = mNotificationMap.get(accountId);
            if (obs != null) return;  // we're already observing; nothing to do

            Mailbox mailbox = Mailbox.restoreMailboxOfType(mContext, accountId, Mailbox.TYPE_INBOX);
            if (mailbox == null) {
                Log.w(Logging.LOG_TAG, "Could not load INBOX for account id: " + accountId);
                return;
            }
            if (Email.DEBUG) {
                Log.i(Logging.LOG_TAG, "Registering for notifications for account " + accountId);
            }
            ContentObserver observer = new MessageContentObserver(
                    sNotificationHandler, mContext, mailbox.mId, accountId);
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
    private void unregisterMessageNotification(long accountId) {
        ContentResolver resolver = mContext.getContentResolver();
        if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
            if (Email.DEBUG) {
                Log.i(Logging.LOG_TAG, "Unregistering notifications for all accounts");
            }
            // cancel all existing message observers
            for (ContentObserver observer : mNotificationMap.values()) {
                resolver.unregisterContentObserver(observer);
            }
            mNotificationMap.clear();
        } else {
            if (Email.DEBUG) {
                Log.i(Logging.LOG_TAG, "Unregistering notifications for account " + accountId);
            }
            ContentObserver observer = mNotificationMap.remove(accountId);
            if (observer != null) {
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
        Bitmap photo = ContactStatusLoader.getContactInfo(mContext, email).mPhoto;

        if (photo != null) {
            final Resources res = mContext.getResources();
            final int idealIconHeight =
                    res.getDimensionPixelSize(android.R.dimen.notification_large_icon_height);
            final int idealIconWidth =
                    res.getDimensionPixelSize(android.R.dimen.notification_large_icon_width);

            if (photo.getHeight() < idealIconHeight) {
                // We should scale this image to fit the intended size
                photo = Bitmap.createScaledBitmap(
                        photo, idealIconWidth, idealIconHeight, true);
            }
        }
        return photo;
    }

    /**
     * Returns a "new message" notification for the given account.
     *
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    @VisibleForTesting
    Notification createNewMessageNotification(long accountId, long mailboxId, Cursor messageCursor,
            long newestMessageId, int unseenMessageCount, int unreadCount) {
        final Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) {
            return null;
        }
        // Get the latest message
        final Message message = Message.restoreMessageWithId(mContext, newestMessageId);
        if (message == null) {
            return null; // no message found???
        }

        String senderName = Address.toFriendly(Address.unpack(message.mFrom));
        if (senderName == null) {
            senderName = ""; // Happens when a message has no from.
        }
        final boolean multipleUnseen = unseenMessageCount > 1;
        final Bitmap senderPhoto = multipleUnseen
                ? mGenericMultipleSenderIcon
                : getSenderPhoto(message);
        final SpannableString title = getNewMessageTitle(senderName, unseenMessageCount);
        // TODO: add in display name on the second line for the text, once framework supports
        // multiline texts.
        final String text = multipleUnseen
                ? account.mDisplayName
                : message.mSubject;
        final Bitmap largeIcon = senderPhoto != null ? senderPhoto : mGenericSenderIcon;
        final Integer number = unreadCount > 1 ? unreadCount : null;
        final Intent intent;
        if (unseenMessageCount > 1) {
            intent = Welcome.createOpenAccountInboxIntent(mContext, accountId);
        } else {
            intent = Welcome.createOpenMessageIntent(
                    mContext, accountId, mailboxId, newestMessageId);
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK |
                Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        long now = mClock.getTime();
        boolean enableAudio = (now - mLastMessageNotifyTime) > MIN_SOUND_INTERVAL_MS;
        final Notification.Builder builder = createBaseAccountNotificationBuilder(
                account, title.toString(), title, text,
                intent, largeIcon, number, enableAudio, false);
        if (isRunningJellybeanOrLater()) {
            // For a new-style notification
            if (multipleUnseen) {
                if (messageCursor != null) {
                    final int maxNumDigestItems = mContext.getResources().getInteger(
                            R.integer.max_num_notification_digest_items);
                    // The body of the notification is the account name, or the label name.
                    builder.setSubText(text);

                    Notification.InboxStyle digest = new Notification.InboxStyle(builder);

                    digest.setBigContentTitle(title);

                    int numDigestItems = 0;
                    // We can assume that the current position of the cursor is on the
                    // newest message
                    do {
                        final long messageId =
                                messageCursor.getLong(EmailContent.ID_PROJECTION_COLUMN);

                        // Get the latest message
                        final Message digestMessage =
                                Message.restoreMessageWithId(mContext, messageId);
                        if (digestMessage != null) {
                            final CharSequence digestLine =
                                    getSingleMessageInboxLine(mContext, digestMessage);
                            digest.addLine(digestLine);
                            numDigestItems++;
                        }
                    } while (numDigestItems <= maxNumDigestItems && messageCursor.moveToNext());

                    // We want to clear the content text in this case. The content text would have
                    // been set in createBaseAccountNotificationBuilder, but since the same string
                    // was set in as the subtext, we don't want to show a duplicate string.
                    builder.setContentText(null);
                }
            } else {
                // The notification content will be the subject of the conversation.
                builder.setContentText(getSingleMessageLittleText(mContext, message.mSubject));

                // The notification subtext will be the subject of the conversation for inbox
                // notifications, or will based on the the label name for user label notifications.
                builder.setSubText(account.mDisplayName);

                final Notification.BigTextStyle bigText = new Notification.BigTextStyle(builder);
                bigText.bigText(getSingleMessageBigText(mContext, message));
            }
        }

        mLastMessageNotifyTime = now;
        return builder.getNotification();
    }

    /**
     * Sets the bigtext for a notification for a single new conversation
     * @param context
     * @param message New message that triggered the notification.
     * @return a {@link CharSequence} suitable for use in {@link Notification.BigTextStyle}
     */
    private static CharSequence getSingleMessageInboxLine(Context context, Message message) {
        final String subject = message.mSubject;
        final String snippet = message.mSnippet;
        final String senders = Address.toFriendly(Address.unpack(message.mFrom));

        final String subjectSnippet = !TextUtils.isEmpty(subject) ? subject : snippet;

        final TextAppearanceSpan notificationPrimarySpan =
                new TextAppearanceSpan(context, R.style.NotificationPrimaryText);

        if (TextUtils.isEmpty(senders)) {
            // If the senders are empty, just use the subject/snippet.
            return subjectSnippet;
        }
        else if (TextUtils.isEmpty(subjectSnippet)) {
            // If the subject/snippet is empty, just use the senders.
            final SpannableString spannableString = new SpannableString(senders);
            spannableString.setSpan(notificationPrimarySpan, 0, senders.length(), 0);

            return spannableString;
        } else {
            final String formatString = context.getResources().getString(
                    R.string.multiple_new_message_notification_item);
            final TextAppearanceSpan notificationSecondarySpan =
                    new TextAppearanceSpan(context, R.style.NotificationSecondaryText);

            final String instantiatedString = String.format(formatString, senders, subjectSnippet);

            final SpannableString spannableString = new SpannableString(instantiatedString);

            final boolean isOrderReversed = formatString.indexOf("%2$s") <
                    formatString.indexOf("%1$s");
            final int primaryOffset =
                    (isOrderReversed ? instantiatedString.lastIndexOf(senders) :
                     instantiatedString.indexOf(senders));
            final int secondaryOffset =
                    (isOrderReversed ? instantiatedString.lastIndexOf(subjectSnippet) :
                     instantiatedString.indexOf(subjectSnippet));
            spannableString.setSpan(notificationPrimarySpan,
                    primaryOffset, primaryOffset + senders.length(), 0);
            spannableString.setSpan(notificationSecondarySpan,
                    secondaryOffset, secondaryOffset + subjectSnippet.length(), 0);
            return spannableString;
        }
    }

    /**
     * Sets the bigtext for a notification for a single new conversation
     * @param context
     * @param subject Subject of the new message that triggered the notification
     * @return a {@link CharSequence} suitable for use in {@link Notification.ContentText}
     */
    private static CharSequence getSingleMessageLittleText(Context context, String subject) {
        if (subject == null) {
            return null;
        }
        final TextAppearanceSpan notificationSubjectSpan = new TextAppearanceSpan(
                context, R.style.NotificationPrimaryText);

        final SpannableString spannableString = new SpannableString(subject);
        spannableString.setSpan(notificationSubjectSpan, 0, subject.length(), 0);

        return spannableString;
    }


    /**
     * Sets the bigtext for a notification for a single new conversation
     * @param context
     * @param message New message that triggered the notification
     * @return a {@link CharSequence} suitable for use in {@link Notification.BigTextStyle}
     */
    private static CharSequence getSingleMessageBigText(Context context, Message message) {
        final TextAppearanceSpan notificationSubjectSpan = new TextAppearanceSpan(
                context, R.style.NotificationPrimaryText);

        final String subject = message.mSubject;
        final String snippet = message.mSnippet;

        if (TextUtils.isEmpty(subject)) {
            // If the subject is empty, just use the snippet.
            return snippet;
        }
        else if (TextUtils.isEmpty(snippet)) {
            // If the snippet is empty, just use the subject.
            final SpannableString spannableString = new SpannableString(subject);
            spannableString.setSpan(notificationSubjectSpan, 0, subject.length(), 0);

            return spannableString;
        } else {
            final String notificationBigTextFormat = context.getResources().getString(
                    R.string.single_new_message_notification_big_text);

            // Localizers may change the order of the parameters, look at how the format
            // string is structured.
            final boolean isSubjectFirst = notificationBigTextFormat.indexOf("%2$s") >
                    notificationBigTextFormat.indexOf("%1$s");
            final String bigText = String.format(notificationBigTextFormat, subject, snippet);
            final SpannableString spannableString = new SpannableString(bigText);

            final int subjectOffset =
                    (isSubjectFirst ? bigText.indexOf(subject) : bigText.lastIndexOf(subject));
            spannableString.setSpan(notificationSubjectSpan,
                    subjectOffset, subjectOffset + subject.length(), 0);

            return spannableString;
        }
    }

    /**
     * Creates a notification title for a new message. If there is only a single message,
     * show the sender name. Otherwise, show "X new messages".
     */
    @VisibleForTesting
    SpannableString getNewMessageTitle(String sender, int unseenCount) {
        String title;
        if (unseenCount > 1) {
            title = String.format(
                    mContext.getString(R.string.notification_multiple_new_messages_fmt),
                    unseenCount);
        } else {
            title = sender;
        }
        return new SpannableString(title);
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
        final boolean vibrate = (flags & Account.FLAGS_VIBRATE) != 0;

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
                MessageColumns.MAILBOX_KEY + "=? AND "
                + MessageColumns.ID + ">? AND "
                + MessageColumns.FLAG_READ + "=0 AND "
                + Message.FLAG_LOADED_SELECTION;
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

            ContentObserver observer = sInstance.mNotificationMap.get(mAccountId);
            if (observer == null) {
                // Notification for a mailbox that we aren't observing; account is probably
                // being deleted.
                Log.w(Logging.LOG_TAG, "Received notification when observer data was null");
                return;
            }
            Account account = Account.restoreAccountWithId(mContext, mAccountId);
            if (account == null) {
                Log.w(Logging.LOG_TAG, "Couldn't find account for changed message notification");
                return;
            }
            long oldMessageId = account.mNotifiedMessageId;
            int oldMessageCount = account.mNotifiedMessageCount;

            ContentResolver resolver = mContext.getContentResolver();
            Long lastSeenMessageId = Utility.getFirstRowLong(
                    mContext, ContentUris.withAppendedId(Mailbox.CONTENT_URI, mMailboxId),
                    new String[] { MailboxColumns.LAST_SEEN_MESSAGE_KEY },
                    null, null, null, 0);
            if (lastSeenMessageId == null) {
                // Mailbox got nuked. Could be that the account is in the process of being deleted
                Log.w(Logging.LOG_TAG, "Couldn't find mailbox for changed message notification");
                return;
            }

            Cursor c = resolver.query(
                    Message.CONTENT_URI, EmailContent.ID_PROJECTION,
                    MESSAGE_SELECTION,
                    new String[] { Long.toString(mMailboxId), Long.toString(lastSeenMessageId) },
                    MessageColumns.ID + " DESC");
            if (c == null) {
                // Couldn't find message info - things may be getting deleted in bulk.
                Log.w(Logging.LOG_TAG, "#onChange(); NULL response for message id query");
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
                    Integer unreadCount = Utility.getFirstRowInt(
                            mContext, ContentUris.withAppendedId(Mailbox.CONTENT_URI, mMailboxId),
                            new String[] { MailboxColumns.UNREAD_COUNT },
                            null, null, null, 0);
                    if (unreadCount == null) {
                        Log.w(Logging.LOG_TAG, "Couldn't find unread count for mailbox");
                        return;
                    }

                    Notification n = sInstance.createNewMessageNotification(
                            mAccountId, mMailboxId, c, newMessageId,
                            newMessageCount, unreadCount);
                    if (n != null) {
                        // Make the notification visible
                        sInstance.mNotificationManager.notify(
                                sInstance.getNewMessageNotificationId(mAccountId), n);
                    }
                }
                // Save away the new values
                ContentValues cv = new ContentValues();
                cv.put(AccountColumns.NOTIFIED_MESSAGE_ID, newMessageId);
                cv.put(AccountColumns.NOTIFIED_MESSAGE_COUNT, newMessageCount);
                resolver.update(ContentUris.withAppendedId(Account.CONTENT_URI, mAccountId), cv,
                        null, null);
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
