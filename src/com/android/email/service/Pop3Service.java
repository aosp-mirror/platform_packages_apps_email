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
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

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
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceCallback;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.mail.providers.UIProvider;
import com.android.mail.providers.UIProvider.AccountCapabilities;
import com.android.mail.providers.UIProvider.AttachmentState;

import org.apache.james.mime4j.EOLConvertingInputStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

public class Pop3Service extends Service {
    private static final String TAG = "Pop3Service";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }

    // Callbacks as set up via setCallback
    private static final RemoteCallbackList<IEmailServiceCallback> mCallbackList =
            new RemoteCallbackList<IEmailServiceCallback>();

    private static final EmailServiceCallback sCallbackProxy =
            new EmailServiceCallback(mCallbackList);

    /**
     * Create our EmailService implementation here.
     */
    private final EmailServiceStub mBinder = new EmailServiceStub() {

        @Override
        public void setCallback(IEmailServiceCallback cb) throws RemoteException {
            mCallbackList.register(cb);
        }

        @Override
        public int getCapabilities(Account acct) throws RemoteException {
            return AccountCapabilities.UNDO;
        }

        @Override
        public void loadAttachment(long attachmentId, boolean background) throws RemoteException {
            Attachment att = Attachment.restoreAttachmentWithId(mContext, attachmentId);
            if (att == null || att.mUiState != AttachmentState.DOWNLOADING) return;
            long inboxId = Mailbox.findMailboxOfType(mContext, att.mAccountKey, Mailbox.TYPE_INBOX);
            if (inboxId == Mailbox.NO_MAILBOX) return;
            // We load attachments during a sync
            startSync(inboxId, true, 0);
        }

        @Override
        public void serviceUpdated(String emailAddress) throws RemoteException {
            // Not required for POP3
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        mBinder.init(this, sCallbackProxy);
        return mBinder;
    }

    private static void sendMailboxStatus(Mailbox mailbox, int status) {
            sCallbackProxy.syncMailboxStatus(mailbox.mId, status, 0);
    }

    /**
     * Start foreground synchronization of the specified folder. This is called
     * by synchronizeMailbox or checkMail. TODO this should use ID's instead of
     * fully-restored objects
     *
     * @param account
     * @param folder
     * @param deltaMessageCount the requested change in number of messages to sync.
     * @throws MessagingException
     */
    public static void synchronizeMailboxSynchronous(Context context, final Account account,
            final Mailbox folder, final int deltaMessageCount) throws MessagingException {
        sendMailboxStatus(folder, EmailServiceStatus.IN_PROGRESS);

        TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(context, account));
        if ((folder.mFlags & Mailbox.FLAG_HOLDS_MAIL) == 0) {
            sendMailboxStatus(folder, EmailServiceStatus.SUCCESS);
        }
        NotificationController nc = NotificationController.getInstance(context);
        try {
            synchronizePop3Mailbox(context, account, folder, deltaMessageCount);
            // Clear authentication notification for this account
            nc.cancelLoginFailedNotification(account.mId);
            sendMailboxStatus(folder, EmailServiceStatus.SUCCESS);
        } catch (MessagingException e) {
            if (Logging.LOGD) {
                Log.v(Logging.LOG_TAG, "synchronizeMailbox", e);
            }
            if (e instanceof AuthenticationFailedException) {
                // Generate authentication notification
                nc.showLoginFailedNotification(account.mId);
            }
            sendMailboxStatus(folder, e.getExceptionType());
            throw e;
        }
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
            Log.d(TAG, "Loading " + unsyncedMessages.size() + " unsynced messages");
        }
        try {
            int cnt = unsyncedMessages.size();
            // We'll load them from most recent to oldest
            for (int i = cnt - 1; i >= 0; i--) {
                Pop3Message message = unsyncedMessages.get(i);
                remoteFolder.fetchBody(message, Pop3Store.FETCH_BODY_SANE_SUGGESTED_SIZE / 76,
                        null);
                int flag = EmailContent.Message.FLAG_LOADED_COMPLETE;
                if (!message.isComplete()) {
                     flag = EmailContent.Message.FLAG_LOADED_UNKNOWN;
                }
                if (MailActivityEmail.DEBUG) {
                    Log.d(TAG, "Message is " + (message.isComplete() ? "" : "NOT ") + "complete");
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
    private static void synchronizePop3Mailbox(final Context context, final Account account,
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
                    EmailContent.MessageColumns.ACCOUNT_KEY + "=?" +
                            " AND " + MessageColumns.MAILBOX_KEY + "=?",
                    new String[] {
                            String.valueOf(account.mId),
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

        // Update the total count and determine new message count to sync.
        final int messageCount = mailbox.handleCountsForSync(context, remoteMessageCount,
                deltaMessageCount);

        // Create a list of messages to download
        Pop3Message[] remoteMessages = new Pop3Message[0];
        final ArrayList<Pop3Message> unsyncedMessages = new ArrayList<Pop3Message>();
        HashMap<String, Pop3Message> remoteUidMap = new HashMap<String, Pop3Message>();

        if (remoteMessageCount > 0) {
            /*
             * Message numbers start at 1.
             */
            remoteMessages = remoteFolder.getMessages(remoteMessageCount, messageCount);

            /*
             * Get a list of the messages that are in the remote list but not on
             * the local store, or messages that are in the local store but
             * failed to download on the last sync. These are the new messages
             * that we will download. Note, we also skip syncing messages which
             * are flagged as "deleted message" sentinels, because they are
             * locally deleted and we don't need or want the old message from
             * the server.
             */
            for (Pop3Message message : remoteMessages) {
                String uid = message.getUid();
                remoteUidMap.put(uid, message);
                LocalMessageInfo localMessage = localMessageMap.get(uid);
                // localMessage == null -> message has never been created (not even headers)
                // mFlagLoaded = UNLOADED -> message created, but none of body loaded
                if (localMessage == null ||
                        (localMessage.mFlagLoaded == EmailContent.Message.FLAG_LOADED_UNLOADED)) {
                    unsyncedMessages.add(message);
                }
            }
        } else {
            if (MailActivityEmail.DEBUG) {
                Log.d(TAG, "*** Message count is zero??");
            }
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
                            Log.e(TAG, "How is this possible?");
                        }
                        Utilities.copyOneMessageToProvider(
                                context, popMessage, account, mailbox, flag);
                        // Get rid of the temporary attachment
                        resolver.delete(attUri, null, null);

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

        // Load messages we need to sync
        loadUnsyncedMessages(context, account, remoteFolder, unsyncedMessages, mailbox);

        // Clean up and report results
        remoteFolder.close(false);
    }
}
