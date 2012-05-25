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
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.TextAppearanceSpan;
import android.util.Log;

import com.android.email.activity.ContactStatusLoader;
import com.android.email.activity.setup.AccountSecurity;
import com.android.email.activity.setup.AccountSettings;
import com.android.email.provider.EmailProvider;
import com.android.email.service.EmailBroadcastProcessorService;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.android.mail.providers.Conversation;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.Utils;
import com.google.common.annotations.VisibleForTesting;

import java.util.HashMap;
import java.util.HashSet;

/**
 * Class that manages notifications.
 */
public class NotificationController {
    private static final String TAG = "NotificationController";

    /** Reserved for {@link com.android.exchange.CalendarSyncEnabler} */
    @SuppressWarnings("unused")
    private static final int NOTIFICATION_ID_EXCHANGE_CALENDAR_ADDED = 2;
    private static final int NOTIFICATION_ID_ATTACHMENT_WARNING = 3;
    private static final int NOTIFICATION_ID_PASSWORD_EXPIRING = 4;
    private static final int NOTIFICATION_ID_PASSWORD_EXPIRED = 5;

    private static final int NOTIFICATION_ID_BASE_MASK = 0xF0000000;
    private static final int NOTIFICATION_ID_BASE_NEW_MESSAGES = 0x10000000;
    private static final int NOTIFICATION_ID_BASE_LOGIN_WARNING = 0x20000000;
    private static final int NOTIFICATION_ID_BASE_SECURITY_NEEDED = 0x30000000;
    private static final int NOTIFICATION_ID_BASE_SECURITY_CHANGED = 0x40000000;

    /** Selection to retrieve accounts that should we notify user for changes */
    private final static String NOTIFIED_ACCOUNT_SELECTION =
        Account.FLAGS + "&" + Account.FLAGS_NOTIFY_NEW_MAIL + " != 0";

    private static final String NEW_MAIL_MAILBOX_ID = "com.android.email.new_mail.mailboxId";
    private static final String NEW_MAIL_MESSAGE_ID = "com.android.email.new_mail.messageId";
    private static final String NEW_MAIL_MESSAGE_COUNT = "com.android.email.new_mail.messageCount";
    private static final String NEW_MAIL_UNREAD_COUNT = "com.android.email.new_mail.unreadCount";

    private static NotificationThread sNotificationThread;
    private static Handler sNotificationHandler;
    private static NotificationController sInstance;
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final AudioManager mAudioManager;
    private final Bitmap mGenericSenderIcon;
    private final Bitmap mGenericMultipleSenderIcon;
    private final Clock mClock;
    /** Maps account id to its observer */
    private final HashMap<Long, ContentObserver> mNotificationMap;
    private ContentObserver mAccountObserver;

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
        return (notificationId & NOTIFICATION_ID_BASE_MASK) == NOTIFICATION_ID_BASE_SECURITY_NEEDED;
    }

    /**
     * Returns a {@link Notification.Builder}} for an event with the given account. The account
     * contains specific rules on ring tone usage and these will be used to modify the notification
     * behaviour.
     *
     * @param accountId The id of the account this notification is being built for.
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
    private Notification.Builder createBaseAccountNotificationBuilder(long accountId, String ticker,
            CharSequence title, String contentText, Intent intent, Bitmap largeIcon,
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
        final Notification.Builder builder = createBaseAccountNotificationBuilder(accountId, ticker,
                title, contentText, intent, null, null, true,
                needsOngoingNotification(notificationId));
        mNotificationManager.notify(notificationId, builder.getNotification());
    }

    /**
     * Returns a notification ID for new message notifications for the given account.
     */
    private int getNewMessageNotificationId(long mailboxId) {
        // We assume accountId will always be less than 0x0FFFFFFF; is there a better way?
        return (int) (NOTIFICATION_ID_BASE_NEW_MESSAGES + mailboxId);
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
        if (MailActivityEmail.DEBUG) {
            Log.d(Logging.LOG_TAG, "Notifications being toggled: " + watch);
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
                    if (MailActivityEmail.DEBUG) {
                        Log.i(Logging.LOG_TAG, "Observing account changes for notifications");
                    }
                    mAccountObserver = new AccountContentObserver(sNotificationHandler, mContext);
                    resolver.registerContentObserver(Account.NOTIFIER_URI, true, mAccountObserver);
                }
            }
        });
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
     * Registers an observer for changes to mailboxes in the given account.
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
            if (MailActivityEmail.DEBUG) {
                Log.i(Logging.LOG_TAG, "Registering for notifications for account " + accountId);
            }
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
    private void unregisterMessageNotification(long accountId) {
        ContentResolver resolver = mContext.getContentResolver();
        if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
            if (MailActivityEmail.DEBUG) {
                Log.i(Logging.LOG_TAG, "Unregistering notifications for all accounts");
            }
            // cancel all existing message observers
            for (ContentObserver observer : mNotificationMap.values()) {
                resolver.unregisterContentObserver(observer);
            }
            mNotificationMap.clear();
        } else {
            if (MailActivityEmail.DEBUG) {
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

    public static final String EXTRA_ACCOUNT = "account";
    public static final String EXTRA_CONVERSATION = "conversationUri";
    public static final String EXTRA_FOLDER = "folder";

    private Intent createViewConversationIntent(Conversation conversation, Folder folder,
            com.android.mail.providers.Account account) {
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.setDataAndType(conversation.uri, account.mimeType);
        intent.putExtra(EXTRA_ACCOUNT, account);
        intent.putExtra(EXTRA_FOLDER, folder);
        intent.putExtra(EXTRA_CONVERSATION, conversation);
        return intent;
    }
    
    private Cursor getUiCursor(Uri uri, String[] projection) {
        Cursor c = mContext.getContentResolver().query(uri, projection, null, null, null);
        if (c == null) return null;
        if (c.moveToFirst()) {
            return c;
        } else {
            c.close();
            return null;
        }
    }
    
    private Intent createViewConversationIntent(Message message) {
        Cursor c = getUiCursor(EmailProvider.uiUri("uiaccount", message.mAccountKey),
                UIProvider.ACCOUNTS_PROJECTION);
        if (c == null) {
            Log.w(TAG, "Can't find account for message " + message.mId);
            return null;
        }
        com.android.mail.providers.Account acct = new com.android.mail.providers.Account(c);
        c.close();
        c = getUiCursor(EmailProvider.uiUri("uifolder", message.mMailboxKey),
                UIProvider.FOLDERS_PROJECTION);
        if (c == null) {
            Log.w(TAG, "Can't find folder for message " + message.mId + ", folder " +
                    message.mMailboxKey);
            return null;
        }
        Folder folder = new Folder(c);
        c.close();
        c = getUiCursor(EmailProvider.uiUri("uiconversation", message.mId),
                UIProvider.CONVERSATION_PROJECTION);
        if (c == null) {
            Log.w(TAG, "Can't find conversation for message " + message.mId);
            return null;
        }
        Conversation conv = new Conversation(c);
        c.close();
        return createViewConversationIntent(conv, folder, acct);
    }
    
    /**
     * Returns a "new message" notification for the given account.
     *
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    @VisibleForTesting
    Notification createNewMessageNotification(long mailboxId, long newMessageId,
            int unseenMessageCount, int unreadCount) {
        final Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, mailboxId);
        if (mailbox == null) {
            return null;
        }
        final Account account = Account.restoreAccountWithId(mContext, mailbox.mAccountKey);
        if (account == null) {
            return null;
        }
        // Get the latest message
        final Message message = Message.restoreMessageWithId(mContext, newMessageId);
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
        // Show account name if an inbox; otherwise mailbox name
        final String text = multipleUnseen
                ? ((mailbox.mType == Mailbox.TYPE_INBOX) ? account.mDisplayName :
                    mailbox.mDisplayName)
                : message.mSubject;
        final Bitmap largeIcon = senderPhoto != null ? senderPhoto : mGenericSenderIcon;
        final Integer number = unreadCount > 1 ? unreadCount : null;
        Intent intent = createViewConversationIntent(message);
        if (intent == null) {
            return null;
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK |
                Intent.FLAG_ACTIVITY_TASK_ON_HOME);
        long now = mClock.getTime();
        boolean enableAudio = (now - mLastMessageNotifyTime) > MIN_SOUND_INTERVAL_MS;
        final Notification.Builder builder = createBaseAccountNotificationBuilder(
                mailbox.mAccountKey, title.toString(), title, text,
                intent, largeIcon, number, enableAudio, false);
        if (Utils.isRunningJellybeanOrLater()) {
            // For a new-style notification
            if (multipleUnseen) {
                final Cursor messageCursor =
                        mContext.getContentResolver().query(ContentUris.withAppendedId(
                                EmailContent.MAILBOX_NOTIFICATION_URI, mailbox.mAccountKey),
                                EmailContent.NOTIFICATION_PROJECTION, null, null, null);

                if (messageCursor != null && messageCursor.getCount() > 0) {
                    try {
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

                        // We want to clear the content text in this case. The content text would
                        // have been set in createBaseAccountNotificationBuilder, but since the
                        // same string was set in as the subtext, we don't want to show a
                        // duplicate string.
                        builder.setContentText(null);
                    } finally {
                        messageCursor.close();
                    }
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
        Message message = Message.restoreMessageWithId(mContext, attachment.mMessageKey);
        if (message == null) return;
        Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, message.mMailboxKey);
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
        final Mailbox mailbox = Mailbox.restoreMailboxOfType(mContext, account.mId,
                Mailbox.TYPE_INBOX);
        if (mailbox == null) return;
        showNotification(mailbox.mAccountKey,
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
     * Cancels the new message notification for a given mailbox
     */
    public void cancelNewMessageNotification(long mailboxId) {
        mNotificationManager.cancel(getNewMessageNotificationId(mailboxId));
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
        showNotification(accountId, ticker, title, accountName, intent,
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
        Intent intent = AccountSettings.createAccountSettingsIntent(mContext, account.mId, null);
        String accountName = account.getDisplayName();
        String ticker =
            mContext.getString(R.string.security_changed_ticker_fmt, accountName);
        String title = mContext.getString(R.string.security_notification_content_change_title);
        showNotification(account.mId, ticker, title, accountName, intent,
                (int)(NOTIFICATION_ID_BASE_SECURITY_CHANGED + account.mId));
    }

    /**
     * Show (or update) a security unsupported notification. If tapped, the user is taken to the
     * account settings screen where he can view the list of unsupported policies
     */
    public void showSecurityUnsupportedNotification(Account account) {
        Intent intent = AccountSettings.createAccountSettingsIntent(mContext, account.mId, null);
        String accountName = account.getDisplayName();
        String ticker =
            mContext.getString(R.string.security_unsupported_ticker_fmt, accountName);
        String title = mContext.getString(R.string.security_notification_content_unsupported_title);
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
     * Observer invoked whenever a message we're notifying the user about changes.
     */
    private static class MessageContentObserver extends ContentObserver {
        private final Context mContext;
        private final long mAccountId;

        public MessageContentObserver(
                Handler handler, Context context, long accountId) {
            super(handler);
            mContext = context;
            mAccountId = accountId;
        }

        @Override
        public void onChange(boolean selfChange) {
            ContentObserver observer = sInstance.mNotificationMap.get(mAccountId);
            Account account = Account.restoreAccountWithId(mContext, mAccountId);
            if (observer == null || account == null) {
                Log.w(Logging.LOG_TAG, "Couldn't find account for changed message notification");
                return;
            }

            ContentResolver resolver = mContext.getContentResolver();
            Cursor c = resolver.query(ContentUris.withAppendedId(
                    EmailContent.MAILBOX_NOTIFICATION_URI, mAccountId),
                    EmailContent.NOTIFICATION_PROJECTION, null, null, null);
            try {
                while (c.moveToNext()) {
                    long mailboxId = c.getLong(EmailContent.NOTIFICATION_MAILBOX_ID_COLUMN);
                    if (mailboxId == 0) continue;
                    int messageCount =
                            c.getInt(EmailContent.NOTIFICATION_MAILBOX_MESSAGE_COUNT_COLUMN);
                    int unreadCount =
                            c.getInt(EmailContent.NOTIFICATION_MAILBOX_UNREAD_COUNT_COLUMN);

                    Mailbox m = Mailbox.restoreMailboxWithId(mContext, mailboxId);
                    long newMessageId = Utility.getFirstRowLong(mContext,
                            ContentUris.withAppendedId(
                                    EmailContent.MAILBOX_MOST_RECENT_MESSAGE_URI, mailboxId),
                            Message.ID_COLUMN_PROJECTION, null, null, null,
                            Message.ID_MAILBOX_COLUMN_ID, -1L);
                    Log.d(Logging.LOG_TAG, "Changes to " + account.mDisplayName + "/" +
                            m.mDisplayName + ", count: " + messageCount + ", lastNotified: " +
                            m.mLastNotifiedMessageKey + ", mostRecent: " + newMessageId);
                    // Broadcast intent here
                    Intent i = new Intent(EmailBroadcastProcessorService.ACTION_NOTIFY_NEW_MAIL);
                    // Required by UIProvider
                    i.setType(EmailProvider.EMAIL_APP_MIME_TYPE);
                    i.putExtra(UIProvider.UpdateNotificationExtras.EXTRA_FOLDER,
                            Uri.parse(EmailProvider.uiUriString("uifolder", mailboxId)));
                    i.putExtra(UIProvider.UpdateNotificationExtras.EXTRA_ACCOUNT,
                            Uri.parse(EmailProvider.uiUriString("uiaccount", m.mAccountKey)));
                    i.putExtra(UIProvider.UpdateNotificationExtras.EXTRA_UPDATED_UNREAD_COUNT,
                            unreadCount);
                    // Required by our notification controller
                    i.putExtra(NEW_MAIL_MAILBOX_ID, mailboxId);
                    i.putExtra(NEW_MAIL_MESSAGE_ID, newMessageId);
                    i.putExtra(NEW_MAIL_MESSAGE_COUNT, messageCount);
                    i.putExtra(NEW_MAIL_UNREAD_COUNT, unreadCount);
                    mContext.sendOrderedBroadcast(i, null);
                }
            } finally {
                c.close();
            }
        }
    }

    public static void notifyNewMail(Context context, Intent i) {
        Log.d(Logging.LOG_TAG, "Sending notification to system...");
        NotificationController nc = NotificationController.getInstance(context);
        ContentResolver resolver = context.getContentResolver();
        long mailboxId = i.getLongExtra(NEW_MAIL_MAILBOX_ID, -1);
        long newMessageId = i.getLongExtra(NEW_MAIL_MESSAGE_ID, -1);
        int messageCount = i.getIntExtra(NEW_MAIL_MESSAGE_COUNT, 0);
        int unreadCount = i.getIntExtra(NEW_MAIL_UNREAD_COUNT, 0);
        Notification n = nc.createNewMessageNotification(mailboxId, newMessageId,
                messageCount, unreadCount);
        if (n != null) {
            // Make the notification visible
            nc.mNotificationManager.notify(nc.getNewMessageNotificationId(mailboxId), n);
        }
        // Save away the new values
        ContentValues cv = new ContentValues();
        cv.put(MailboxColumns.LAST_NOTIFIED_MESSAGE_KEY, newMessageId);
        cv.put(MailboxColumns.LAST_NOTIFIED_MESSAGE_COUNT, messageCount);
        resolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId), cv,
                null, null);
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
            final Cursor c = resolver.query(Account.CONTENT_URI, EmailContent.ID_PROJECTION,
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
