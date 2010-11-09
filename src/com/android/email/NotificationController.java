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
import com.android.email.mail.Address;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Message;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.net.Uri;
import android.text.TextUtils;

/**
 * Class that manages notifications.
 *
 * TODO Gather all notification related code here
 */
public class NotificationController {
    public static final int NOTIFICATION_ID_SECURITY_NEEDED = 1;
    public static final int NOTIFICATION_ID_EXCHANGE_CALENDAR_ADDED = 2;
    public static final int NOTIFICATION_ID_ATTACHMENT_WARNING = 3;

    private static final int NOTIFICATION_ID_BASE_NEW_MESSAGES = 0x10000000;
    private static final int NOTIFICATION_ID_BASE_LOGIN_WARNING = 0x20000000;

    private static NotificationController sInstance;
    private final Context mContext;
    private final NotificationManager mNotificationManager;
    private final AudioManager mAudioManager;

    /** Constructor */
    private NotificationController(Context context) {
        mContext = context.getApplicationContext();
        mNotificationManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Singleton access */
    public static synchronized NotificationController getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new NotificationController(context);
        }
        return sInstance;
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
                Notification n = createNewMessageNotification(accountId, unseenMessageCount,
                        justFetchedCount);
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

    private Bitmap[] getNotificationBitmaps(Bitmap senderPhoto) {
        // TODO Should we cache these objects?  (bitmaps and arrays)
        // They don't have to be on this process's memory once we post a notification request to
        // the system, and decodeResource() seems to be reasonably fast.  We don't want them to
        // take up memory when not necessary.
        Bitmap appIcon = BitmapFactory.decodeResource(mContext.getResources(), R.drawable.icon);
        if (senderPhoto == null) {
            return new Bitmap[] {appIcon};
        } else {
            return new Bitmap[] {senderPhoto, appIcon};
        }
    }

    /**
     * Create a notification
     *
     * Don't call it on the UI thread.
     *
     * TODO Test it when the UI is settled.
     */
    private Notification createNewMessageNotification(long accountId, int unseenMessageCount,
            int justFetchedCount) {
        final Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) {
            return null;
        }
        // Get the latest message
        final Message message = Message.getLatestIncomingMessage(mContext, accountId);
        if (message == null) {
            return null; // no message found???
        }

        final String senderName = Address.toFriendly(Address.unpack(message.mFrom));
        final String subject = message.mSubject;
        final Bitmap senderPhoto = getSenderPhoto(message);

        // Intent to open inbox
        PendingIntent contentIntent = PendingIntent.getActivity(mContext, 0,
                Welcome.createOpenAccountInboxIntent(mContext, accountId),
                PendingIntent.FLAG_UPDATE_CURRENT);

        final String notificationTitle;
        if (justFetchedCount == 1) {
            notificationTitle = senderName;
        } else {
            notificationTitle = mContext.getResources().getQuantityString(
                    R.plurals.notification_sender_name_multi_messages, justFetchedCount - 1,
                    senderName, justFetchedCount - 1);
        }
        final String content = subject;
        final String numNewMessages;
        final int numAccounts = EmailContent.count(mContext, Account.CONTENT_URI);
        if (numAccounts == 1) {
            numNewMessages = mContext.getResources().getQuantityString(
                    R.plurals.notification_num_new_messages_single_account, unseenMessageCount,
                    unseenMessageCount, account.mDisplayName);
        } else {
            numNewMessages = mContext.getResources().getQuantityString(
                    R.plurals.notification_num_new_messages_multi_account, unseenMessageCount,
                    unseenMessageCount, account.mDisplayName);
        }

        Notification notification = new Notification(R.drawable.stat_notify_email_generic,
                mContext.getString(R.string.notification_new_title), System.currentTimeMillis());
        notification.setLatestEventInfo(mContext, notificationTitle, subject, contentIntent);

        notification.tickerTitle = notificationTitle;
        // STOPSHIPO numNewMessages should be the 3rd line on expanded notification.  But it's not
        // clear how to do that yet.
        // For now we just append it to subject.
        notification.tickerSubtitle = subject + "  " + numNewMessages;
        notification.tickerIcons = getNotificationBitmaps(senderPhoto);

        setupNotificationSoundAndVibrationFromAccount(notification, account);
        return notification;
    }

    private boolean isRingerModeSilent() {
        return mAudioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
    }

    private void setupNotificationSoundAndVibrationFromAccount(Notification notification,
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
     * Generic warning notification
     */
    public void showWarningNotification(int id, String tickerText, String notificationText,
            Intent intent) {
        PendingIntent pendingIntent = null;
        if (intent != null) {
            pendingIntent = PendingIntent.getActivity(mContext, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
        }
        Notification n = new Notification(android.R.drawable.stat_notify_error, tickerText,
                System.currentTimeMillis());
        n.setLatestEventInfo(mContext, tickerText, notificationText, pendingIntent);
        n.flags = Notification.FLAG_AUTO_CANCEL;
        mNotificationManager.notify(id, n);
    }

    /**
     * Alert the user that an attachment couldn't be forwarded.  This is a very unusual case, and
     * perhaps we shouldn't even send a notification. For now, it's helpful for debugging.
     */
    public void showDownloadForwardFailedNotification(Attachment att) {
        showWarningNotification(NOTIFICATION_ID_ATTACHMENT_WARNING,
                mContext.getString(R.string.forward_download_failed_ticker),
                mContext.getString(R.string.forward_download_failed_notification,
                        att.mFileName), null);
    }

    /**
     * Alert the user that login failed for the specified account
     */
    private int getLoginFailedNotificationId(long accountId) {
        return NOTIFICATION_ID_BASE_LOGIN_WARNING + (int)accountId;
    }

    // NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
    public void showLoginFailedNotification(long accountId) {
        final Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) return;
        showWarningNotification(getLoginFailedNotificationId(accountId),
                mContext.getString(R.string.login_failed_ticker, account.mDisplayName),
                mContext.getString(R.string.login_failed_notification),
                AccountSettingsXL.createAccountSettingsIntent(mContext, accountId));
    }

    public void cancelLoginFailedNotification(long accountId) {
        mNotificationManager.cancel(getLoginFailedNotificationId(accountId));
    }
}
