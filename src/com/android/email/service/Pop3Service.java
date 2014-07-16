/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.email.service;

import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.email.NotificationController;
import com.android.email.mail.Store;
import com.android.email.mail.store.Pop3Store;
import com.android.email.mail.store.Pop3Store.Pop3Folder;
import com.android.email.mail.store.Pop3Store.Pop3Message;
import com.android.email.provider.Utilities;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Logging;
import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.Folder.OpenMode;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.AttachmentColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.AttachmentState;
import com.android.mail.utils.LogUtils;

import org.apache.james.mime4j.EOLConvertingInputStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class Pop3Service extends Service {
    private static final String TAG = "Pop3Service";
    private static final int DEFAULT_SYNC_COUNT = 100;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }

    /**
     * Create our EmailService implementation here.
     */
    private final EmailServiceStub mBinder = new EmailServiceStub() {
        @Override
        public void loadAttachment(final IEmailServiceCallback callback, final long accountId,
                final long attachmentId, final boolean background) throws RemoteException {
            Attachment att = Attachment.restoreAttachmentWithId(mContext, attachmentId);
            if (att == null || att.mUiState != AttachmentState.DOWNLOADING) return;
            long inboxId = Mailbox.findMailboxOfType(mContext, att.mAccountKey, Mailbox.TYPE_INBOX);
            if (inboxId == Mailbox.NO_MAILBOX) return;
            // We load attachments during a sync
            requestSync(inboxId, true, 0);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        mBinder.init(this);
        return mBinder;
    }

    /**
     * Start foreground synchronization of the specified folder. This is called
     * by synchronizeMailbox or checkMail. TODO this should use ID's instead of
     * fully-restored objects
     *
     * @param account
     * @param folder
     * @param deltaMessageCount the requested change in number of messages to sync.
     * @return The status code for whether this operation succeeded.
     * @throws MessagingException
     */
    public static int synchronizeMailboxSynchronous(Context context, final Account account,
            final Mailbox folder, final int deltaMessageCount) throws MessagingException {
        TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(context, account));
        NotificationController nc = NotificationController.getInstance(context);
        try {
            synchronizePop3Mailbox(context, account, folder, deltaMessageCount);
            // Clear authentication notification for this account
            nc.cancelLoginFailedNotification(account.mId);
        } catch (MessagingException e) {
            if (Logging.LOGD) {
                LogUtils.v(Logging.LOG_TAG, "synchronizeMailbox", e);
            }
            if (e instanceof AuthenticationFailedException) {
                // Generate authentication notification
                nc.showLoginFailedNotificationSynchronous(account.mId, true /* incoming */);
            }
            throw e;
        }
        // TODO: Rather than use exceptions as logic aobve, return the status and handle it
        // correctly in caller.
        return EmailServiceStatus.SUCCESS;
    }

    /**
     * Lightweight record for the first pass of message sync, where I'm just
     * seeing if the local message requires sync. Later (for messages that need
     * syncing) we'll do a full readout from the DB.
     */
    private static class LocalMessageInfo {
        private static final int COLUMN_ID = 0;
        private static final int COLUMN_FLAG_LOADED = 1;
        private static final int COLUMN_SERVER_ID = 2;
        private static final String[] PROJECTION = new String[] {
                EmailContent.RECORD_ID, MessageColumns.FLAG_LOADED, SyncColumns.SERVER_ID
        };

        final long mId;
        final int mFlagLoaded;
        final String mServerId;

        public LocalMessageInfo(Cursor c) {
            mId = c.getLong(COLUMN_ID);
            mFlagLoaded = c.getInt(COLUMN_FLAG_LOADED);
            mServerId = c.getString(COLUMN_SERVER_ID);
            // Note: mailbox key and account key not needed - they are projected
            // for the SELECT
        }
    }

    /**
     * Load the structure and body of messages not yet synced
     *
     * @param account the account we're syncing
     * @param remoteFolder the (open) Folder we're working on
     * @param unsyncedMessages an array of Message's we've got headers for
     * @param toMailbox the destination mailbox we're syncing
     * @throws MessagingException
     */
    static void loadUnsyncedMessages(final Context context, final Account account,
            Pop3Folder remoteFolder, ArrayList<Pop3Message> unsyncedMessages,
            final Mailbox toMailbox) throws MessagingException {

        if (MailActivityEmail.DEBUG) {
            LogUtils.d(TAG, "Loading " + unsyncedMessages.size() + " unsynced messages");
        }

        try {
            int cnt = unsyncedMessages.size();
            // They are in most recent to least recent order, process them that way.
            for (int i = 0; i < cnt; i++) {
                final Pop3Message message = unsyncedMessages.get(i);
                remoteFolder.fetchBody(message, Pop3Store.FETCH_BODY_SANE_SUGGESTED_SIZE / 76,
                        null);
                int flag = EmailContent.Message.FLAG_LOADED_COMPLETE;
                if (!message.isComplete()) {
                    // TODO: when the message is not complete, this should mark the message as
                    // partial.  When that change is made, we need to make sure that:
                    // 1) Partial messages are shown in the conversation list
                    // 2) We are able to download the rest of the message/attachment when the
                    //    user requests it.
                     flag = EmailContent.Message.FLAG_LOADED_PARTIAL;
                }
                if (MailActivityEmail.DEBUG) {
                    LogUtils.d(TAG, "Message is " + (message.isComplete() ? "" : "NOT ")
                            + "complete");
                }
                // If message is incomplete, create a "fake" attachment
                Utilities.copyOneMessageToProvider(context, message, account, toMailbox, flag);
            }
        } catch (IOException e) {
            throw new MessagingException(MessagingException.IOERROR);
        }
    }

    private static class FetchCallback implements EOLConvertingInputStream.Callback {
        private final ContentResolver mResolver;
        private final Uri mAttachmentUri;
        private final ContentValues mContentValues = new ContentValues();

        FetchCallback(ContentResolver resolver, Uri attachmentUri) {
            mResolver = resolver;
            mAttachmentUri = attachmentUri;
        }

        @Override
        public void report(int bytesRead) {
            mContentValues.put(AttachmentColumns.UI_DOWNLOADED_SIZE, bytesRead);
            mResolver.update(mAttachmentUri, mContentValues, null, null);
        }
    }

    /**
     * Synchronizer
     *
     * @param account the account to sync
     * @param mailbox the mailbox to sync
     * @param deltaMessageCount the requested change to number of messages to sync
     * @throws MessagingException
     */
    private synchronized static void synchronizePop3Mailbox(final Context context, final Account account,
            final Mailbox mailbox, final int deltaMessageCount) throws MessagingException {
        // TODO Break this into smaller pieces
        ContentResolver resolver = context.getContentResolver();

        // We only sync Inbox
        if (mailbox.mType != Mailbox.TYPE_INBOX) {
            return;
        }

        // Get the message list from EmailProvider and create an index of the uids

        Cursor localUidCursor = null;
        HashMap<String, LocalMessageInfo> localMessageMap = new HashMap<String, LocalMessageInfo>();

        try {
            localUidCursor = resolver.query(
                    EmailContent.Message.CONTENT_URI,
                    LocalMessageInfo.PROJECTION,
                    MessageColumns.MAILBOX_KEY + "=?",
                    new String[] {
                            String.valueOf(mailbox.mId)
                    },
                    null);
            while (localUidCursor.moveToNext()) {
                LocalMessageInfo info = new LocalMessageInfo(localUidCursor);
                localMessageMap.put(info.mServerId, info);
            }
        } finally {
            if (localUidCursor != null) {
                localUidCursor.close();
            }
        }

        // Open the remote folder and create the remote folder if necessary

        Pop3Store remoteStore = (Pop3Store)Store.getInstance(account, context);
        // The account might have been deleted
        if (remoteStore == null)
            return;
        Pop3Folder remoteFolder = (Pop3Folder)remoteStore.getFolder(mailbox.mServerId);

        // Open the remote folder. This pre-loads certain metadata like message
        // count.
        remoteFolder.open(OpenMode.READ_WRITE);

        String[] accountIdArgs = new String[] { Long.toString(account.mId) };
        long trashMailboxId = Mailbox.findMailboxOfType(context, account.mId, Mailbox.TYPE_TRASH);
        Cursor updates = resolver.query(
                EmailContent.Message.UPDATED_CONTENT_URI,
                EmailContent.Message.ID_COLUMN_PROJECTION,
                EmailContent.MessageColumns.ACCOUNT_KEY + "=?", accountIdArgs,
                null);
        try {
            // loop through messages marked as deleted
            while (updates.moveToNext()) {
                long id = updates.getLong(Message.ID_COLUMNS_ID_COLUMN);
                EmailContent.Message currentMsg =
                        EmailContent.Message.restoreMessageWithId(context, id);
                if (currentMsg.mMailboxKey == trashMailboxId) {
                    // Delete this on the server
                    Pop3Message popMessage =
                            (Pop3Message)remoteFolder.getMessage(currentMsg.mServerId);
                    if (popMessage != null) {
                        remoteFolder.deleteMessage(popMessage);
                    }
                }
                // Finally, delete the update
                Uri uri = ContentUris.withAppendedId(EmailContent.Message.UPDATED_CONTENT_URI, id);
                context.getContentResolver().delete(uri, null, null);
            }
        } finally {
            updates.close();
        }

        // Get the remote message count.
        final int remoteMessageCount = remoteFolder.getMessageCount();

        // Save the folder message count.
        mailbox.updateMessageCount(context, remoteMessageCount);

        // Create a list of messages to download
        Pop3Message[] remoteMessages = new Pop3Message[0];
        final ArrayList<Pop3Message> unsyncedMessages = new ArrayList<Pop3Message>();
        HashMap<String, Pop3Message> remoteUidMap = new HashMap<String, Pop3Message>();

        if (remoteMessageCount > 0) {
            /*
             * Get all messageIds in the mailbox.
             * We don't necessarily need to sync all of them.
             */
            remoteMessages = remoteFolder.getMessages(remoteMessageCount, remoteMessageCount);
            LogUtils.d(Logging.LOG_TAG, "remoteMessageCount " + remoteMessageCount);

            /*
             * TODO: It would be nicer if the default sync window were time based rather than
             * count based, but POP3 does not support time based queries, and the UIDL command
             * does not report timestamps. To handle this, we would need to load a block of
             * Ids, sync those messages to get the timestamps, and then load more Ids until we
             * have filled out our window.
             */
            int count = 0;
            int countNeeded = DEFAULT_SYNC_COUNT;
            for (final Pop3Message message : remoteMessages) {
                final String uid = message.getUid();
                remoteUidMap.put(uid, message);
            }

            /*
             * Figure out which messages we need to sync. Start at the most recent ones, and keep
             * going until we hit one of four end conditions:
             * 1. We currently have zero local messages. In this case, we will sync the most recent
             * DEFAULT_SYNC_COUNT, then stop.
             * 2. We have some local messages, and after encountering them, we find some older
             * messages that do not yet exist locally. In this case, we will load whichever came
             * before the ones we already had locally, and also deltaMessageCount additional
             * older messages.
             * 3. We have some local messages, but after examining the most recent
             * DEFAULT_SYNC_COUNT remote messages, we still have not encountered any that exist
             * locally. In this case, we'll stop adding new messages to sync, leaving a gap between
             * the ones we've just loaded and the ones we already had.
             * 4. We examine all of the remote messages before running into any of our count
             * limitations.
             */
            for (final Pop3Message message : remoteMessages) {
                final String uid = message.getUid();
                final LocalMessageInfo localMessage = localMessageMap.get(uid);
                if (localMessage == null) {
                    count++;
                } else {
                    // We have found a message that already exists locally. We may or may not
                    // need to keep looking, depending on what deltaMessageCount is.
                    LogUtils.d(Logging.LOG_TAG, "found a local message, need " +
                            deltaMessageCount + " more remote messages");
                    countNeeded = deltaMessageCount;
                    count = 0;
                }

                // localMessage == null -> message has never been created (not even headers)
                // mFlagLoaded != FLAG_LOADED_COMPLETE -> message failed to sync completely
                if (localMessage == null ||
                        (localMessage.mFlagLoaded != EmailContent.Message.FLAG_LOADED_COMPLETE &&
                                localMessage.mFlagLoaded != Message.FLAG_LOADED_PARTIAL)) {
                    LogUtils.d(Logging.LOG_TAG, "need to sync " + uid);
                    unsyncedMessages.add(message);
                } else {
                    LogUtils.d(Logging.LOG_TAG, "don't need to sync " + uid);
                }

                if (count >= countNeeded) {
                    LogUtils.d(Logging.LOG_TAG, "loaded " + count + " messages, stopping");
                    break;
                }
            }
        } else {
            if (MailActivityEmail.DEBUG) {
                LogUtils.d(TAG, "*** Message count is zero??");
            }
            remoteFolder.close(false);
            return;
        }

        // Get "attachments" to be loaded
        Cursor c = resolver.query(Attachment.CONTENT_URI, Attachment.CONTENT_PROJECTION,
                AttachmentColumns.ACCOUNT_KEY + "=? AND " +
                        AttachmentColumns.UI_STATE + "=" + AttachmentState.DOWNLOADING,
                new String[] {Long.toString(account.mId)}, null);
        try {
            final ContentValues values = new ContentValues();
            while (c.moveToNext()) {
                values.put(AttachmentColumns.UI_STATE, UIProvider.AttachmentState.SAVED);
                Attachment att = new Attachment();
                att.restore(c);
                Message msg = Message.restoreMessageWithId(context, att.mMessageKey);
                if (msg == null || (msg.mFlagLoaded == Message.FLAG_LOADED_COMPLETE)) {
                    values.put(AttachmentColumns.UI_DOWNLOADED_SIZE, att.mSize);
                    resolver.update(ContentUris.withAppendedId(Attachment.CONTENT_URI, att.mId),
                            values, null, null);
                    continue;
                } else {
                    String uid = msg.mServerId;
                    Pop3Message popMessage = remoteUidMap.get(uid);
                    if (popMessage != null) {
                        Uri attUri = ContentUris.withAppendedId(Attachment.CONTENT_URI, att.mId);
                        try {
                            remoteFolder.fetchBody(popMessage, -1,
                                    new FetchCallback(resolver, attUri));
                        } catch (IOException e) {
                            throw new MessagingException(MessagingException.IOERROR);
                        }

                        // Say we've downloaded the attachment
                        values.put(AttachmentColumns.UI_STATE, AttachmentState.SAVED);
                        resolver.update(attUri, values, null, null);

                        int flag = EmailContent.Message.FLAG_LOADED_COMPLETE;
                        if (!popMessage.isComplete()) {
                            LogUtils.e(TAG, "How is this possible?");
                        }
                        Utilities.copyOneMessageToProvider(
                                context, popMessage, account, mailbox, flag);
                        // Get rid of the temporary attachment
                        resolver.delete(attUri, null, null);

                    } else {
                        // TODO: Should we mark this attachment as failed so we don't
                        // keep trying to download?
                        LogUtils.e(TAG, "Could not find message for attachment " + uid);
                    }
                }
            }
        } finally {
            c.close();
        }

        // Remove any messages that are in the local store but no longer on the remote store.
        HashSet<String> localUidsToDelete = new HashSet<String>(localMessageMap.keySet());
        localUidsToDelete.removeAll(remoteUidMap.keySet());
        for (String uidToDelete : localUidsToDelete) {
            LogUtils.d(Logging.LOG_TAG, "need to delete " + uidToDelete);
            LocalMessageInfo infoToDelete = localMessageMap.get(uidToDelete);

            // Delete associated data (attachment files)
            // Attachment & Body records are auto-deleted when we delete the
            // Message record
            AttachmentUtilities.deleteAllAttachmentFiles(context, account.mId,
                    infoToDelete.mId);

            // Delete the message itself
            Uri uriToDelete = ContentUris.withAppendedId(
                    EmailContent.Message.CONTENT_URI, infoToDelete.mId);
            resolver.delete(uriToDelete, null, null);

            // Delete extra rows (e.g. synced or deleted)
            Uri updateRowToDelete = ContentUris.withAppendedId(
                    EmailContent.Message.UPDATED_CONTENT_URI, infoToDelete.mId);
            resolver.delete(updateRowToDelete, null, null);
            Uri deleteRowToDelete = ContentUris.withAppendedId(
                    EmailContent.Message.DELETED_CONTENT_URI, infoToDelete.mId);
            resolver.delete(deleteRowToDelete, null, null);
        }

        LogUtils.d(TAG, "loadUnsynchedMessages " + unsyncedMessages.size());
        // Load messages we need to sync
        loadUnsyncedMessages(context, account, remoteFolder, unsyncedMessages, mailbox);

        // Clean up and report results
        remoteFolder.close(false);
    }
}
