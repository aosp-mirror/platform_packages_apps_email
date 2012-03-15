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
import android.text.TextUtils;
import android.util.Log;

import com.android.email.LegacyConversions;
import com.android.email.NotificationController;
import com.android.email.mail.Store;
import com.android.email.provider.Utilities;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Logging;
import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.internet.MimeUtility;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.FetchProfile;
import com.android.emailcommon.mail.Flag;
import com.android.emailcommon.mail.Folder;
import com.android.emailcommon.mail.Folder.FolderType;
import com.android.emailcommon.mail.Folder.MessageRetrievalListener;
import com.android.emailcommon.mail.Folder.MessageUpdateCallbacks;
import com.android.emailcommon.mail.Folder.OpenMode;
import com.android.emailcommon.mail.Message;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.mail.Part;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.utility.AttachmentUtilities;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;

public class ImapService extends Service {
    private static final String TAG = "ImapService";
    private static final int MAX_SMALL_MESSAGE_SIZE = (25 * 1024);

    private static final Flag[] FLAG_LIST_SEEN = new Flag[] { Flag.SEEN };
    private static final Flag[] FLAG_LIST_FLAGGED = new Flag[] { Flag.FLAGGED };
    private static final Flag[] FLAG_LIST_ANSWERED = new Flag[] { Flag.ANSWERED };

    /**
     * Simple cache for last search result mailbox by account and serverId, since the most common
     * case will be repeated use of the same mailbox
     */
    private static long mLastSearchAccountKey = Account.NO_ACCOUNT;
    private static String mLastSearchServerId = null;
    private static Mailbox mLastSearchRemoteMailbox = null;

    /**
     * Cache search results by account; this allows for "load more" support without having to
     * redo the search (which can be quite slow).  SortableMessage is a smallish class, so memory
     * shouldn't be an issue
     */
    private static final HashMap<Long, SortableMessage[]> sSearchResults =
        new HashMap<Long, SortableMessage[]>();

    /**
     * We write this into the serverId field of messages that will never be upsynced.
     */
    private static final String LOCAL_SERVERID_PREFIX = "Local-";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }

    // Callbacks as set up via setCallback
    private static final RemoteCallbackList<IEmailServiceCallback> mCallbackList =
            new RemoteCallbackList<IEmailServiceCallback>();

    private interface ServiceCallbackWrapper {
        public void call(IEmailServiceCallback cb) throws RemoteException;
    }

    /**
     * Proxy that can be used by various sync adapters to tie into ExchangeService's callback system
     * Used this way:  ExchangeService.callback().callbackMethod(args...);
     * The proxy wraps checking for existence of a ExchangeService instance
     * Failures of these callbacks can be safely ignored.
     */
    static private final IEmailServiceCallback.Stub sCallbackProxy =
            new IEmailServiceCallback.Stub() {

        /**
         * Broadcast a callback to the everyone that's registered
         *
         * @param wrapper the ServiceCallbackWrapper used in the broadcast
         */
        private synchronized void broadcastCallback(ServiceCallbackWrapper wrapper) {
            RemoteCallbackList<IEmailServiceCallback> callbackList = mCallbackList;
            if (callbackList != null) {
                // Call everyone on our callback list
                int count = callbackList.beginBroadcast();
                try {
                    for (int i = 0; i < count; i++) {
                        try {
                            wrapper.call(callbackList.getBroadcastItem(i));
                        } catch (RemoteException e) {
                            // Safe to ignore
                        } catch (RuntimeException e) {
                            // We don't want an exception in one call to prevent other calls, so
                            // we'll just log this and continue
                            Log.e(TAG, "Caught RuntimeException in broadcast", e);
                        }
                    }
                } finally {
                    // No matter what, we need to finish the broadcast
                    callbackList.finishBroadcast();
                }
            }
        }

        @Override
        public void loadAttachmentStatus(final long messageId, final long attachmentId,
                final int status, final int progress) {
            broadcastCallback(new ServiceCallbackWrapper() {
                @Override
                public void call(IEmailServiceCallback cb) throws RemoteException {
                    cb.loadAttachmentStatus(messageId, attachmentId, status, progress);
                }
            });
        }

        @Override
        public void loadMessageStatus(final long messageId, final int status, final int progress) {
            broadcastCallback(new ServiceCallbackWrapper() {
                @Override
                public void call(IEmailServiceCallback cb) throws RemoteException {
                    cb.loadMessageStatus(messageId, status, progress);
                }
            });
        }

        @Override
        public void sendMessageStatus(final long messageId, final String subject, final int status,
                final int progress) {
            broadcastCallback(new ServiceCallbackWrapper() {
                @Override
                public void call(IEmailServiceCallback cb) throws RemoteException {
                    cb.sendMessageStatus(messageId, subject, status, progress);
                }
            });
        }

        @Override
        public void syncMailboxListStatus(final long accountId, final int status,
                final int progress) {
            broadcastCallback(new ServiceCallbackWrapper() {
                @Override
                public void call(IEmailServiceCallback cb) throws RemoteException {
                    cb.syncMailboxListStatus(accountId, status, progress);
                }
            });
        }

        @Override
        public void syncMailboxStatus(final long mailboxId, final int status,
                final int progress) {
            broadcastCallback(new ServiceCallbackWrapper() {
                @Override
                public void call(IEmailServiceCallback cb) throws RemoteException {
                    cb.syncMailboxStatus(mailboxId, status, progress);
                }
            });
        }
    };

    /**
     * Create our EmailService implementation here.
     */
    private final EmailServiceStub mBinder = new EmailServiceStub() {

        @Override
        public void setCallback(IEmailServiceCallback cb) throws RemoteException {
            mCallbackList.register(cb);
        }

        @Override
        public int searchMessages(long accountId, SearchParams searchParams, long destMailboxId) {
            try {
                return searchMailboxImpl(getApplicationContext(), accountId, searchParams,
                        destMailboxId);
            } catch (MessagingException e) {
            }
            return 0;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        mBinder.init(this, sCallbackProxy);
        return mBinder;
    }

    private static void sendMailboxStatus(Mailbox mailbox, int status) {
        try {
            sCallbackProxy.syncMailboxStatus(mailbox.mId, status, 0);
        } catch (RemoteException e) {
        }
    }

    /**
     * Start foreground synchronization of the specified folder. This is called by
     * synchronizeMailbox or checkMail.
     * TODO this should use ID's instead of fully-restored objects
     * @param account
     * @param folder
     * @throws MessagingException
     */
    public static void synchronizeMailboxSynchronous(Context context, final Account account,
            final Mailbox folder) throws MessagingException {
        sendMailboxStatus(folder, EmailServiceStatus.IN_PROGRESS);

        TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(context, account));
        if ((folder.mFlags & Mailbox.FLAG_HOLDS_MAIL) == 0) {
            sendMailboxStatus(folder, EmailServiceStatus.SUCCESS);
        }
        NotificationController nc = NotificationController.getInstance(context);
        try {
            processPendingActionsSynchronous(context, account);
            synchronizeMailboxGeneric(context, account, folder);
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
     * Lightweight record for the first pass of message sync, where I'm just seeing if
     * the local message requires sync.  Later (for messages that need syncing) we'll do a full
     * readout from the DB.
     */
    private static class LocalMessageInfo {
        private static final int COLUMN_ID = 0;
        private static final int COLUMN_FLAG_READ = 1;
        private static final int COLUMN_FLAG_FAVORITE = 2;
        private static final int COLUMN_FLAG_LOADED = 3;
        private static final int COLUMN_SERVER_ID = 4;
        private static final int COLUMN_FLAGS =  7;
        private static final String[] PROJECTION = new String[] {
            EmailContent.RECORD_ID,
            MessageColumns.FLAG_READ, MessageColumns.FLAG_FAVORITE, MessageColumns.FLAG_LOADED,
            SyncColumns.SERVER_ID, MessageColumns.MAILBOX_KEY, MessageColumns.ACCOUNT_KEY,
            MessageColumns.FLAGS
        };

        final long mId;
        final boolean mFlagRead;
        final boolean mFlagFavorite;
        final int mFlagLoaded;
        final String mServerId;
        final int mFlags;

        public LocalMessageInfo(Cursor c) {
            mId = c.getLong(COLUMN_ID);
            mFlagRead = c.getInt(COLUMN_FLAG_READ) != 0;
            mFlagFavorite = c.getInt(COLUMN_FLAG_FAVORITE) != 0;
            mFlagLoaded = c.getInt(COLUMN_FLAG_LOADED);
            mServerId = c.getString(COLUMN_SERVER_ID);
            mFlags = c.getInt(COLUMN_FLAGS);
            // Note: mailbox key and account key not needed - they are projected for the SELECT
        }
    }

    /**
     * Load the structure and body of messages not yet synced
     * @param account the account we're syncing
     * @param remoteFolder the (open) Folder we're working on
     * @param unsyncedMessages an array of Message's we've got headers for
     * @param toMailbox the destination mailbox we're syncing
     * @throws MessagingException
     */
    static void loadUnsyncedMessages(final Context context, final Account account,
            Folder remoteFolder, ArrayList<Message> unsyncedMessages, final Mailbox toMailbox)
            throws MessagingException {

        // 1. Divide the unsynced messages into small & large (by size)

        // TODO doing this work here (synchronously) is problematic because it prevents the UI
        // from affecting the order (e.g. download a message because the user requested it.)  Much
        // of this logic should move out to a different sync loop that attempts to update small
        // groups of messages at a time, as a background task.  However, we can't just return
        // (yet) because POP messages don't have an envelope yet....

        ArrayList<Message> largeMessages = new ArrayList<Message>();
        ArrayList<Message> smallMessages = new ArrayList<Message>();
        for (Message message : unsyncedMessages) {
            if (message.getSize() > (MAX_SMALL_MESSAGE_SIZE)) {
                largeMessages.add(message);
            } else {
                smallMessages.add(message);
            }
        }

        // 2. Download small messages

        // TODO Problems with this implementation.  1. For IMAP, where we get a real envelope,
        // this is going to be inefficient and duplicate work we've already done.  2.  It's going
        // back to the DB for a local message that we already had (and discarded).

        // For small messages, we specify "body", which returns everything (incl. attachments)
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.BODY);
        remoteFolder.fetch(smallMessages.toArray(new Message[smallMessages.size()]), fp,
                new MessageRetrievalListener() {
                    @Override
                    public void messageRetrieved(Message message) {
                        // Store the updated message locally and mark it fully loaded
                        Utilities.copyOneMessageToProvider(context, message, account, toMailbox,
                                EmailContent.Message.FLAG_LOADED_COMPLETE);
                    }

                    @Override
                    public void loadAttachmentProgress(int progress) {
                    }
        });

        // 3. Download large messages.  We ask the server to give us the message structure,
        // but not all of the attachments.
        fp.clear();
        fp.add(FetchProfile.Item.STRUCTURE);
        remoteFolder.fetch(largeMessages.toArray(new Message[largeMessages.size()]), fp, null);
        for (Message message : largeMessages) {
            if (message.getBody() == null) {
                // POP doesn't support STRUCTURE mode, so we'll just do a partial download
                // (hopefully enough to see some/all of the body) and mark the message for
                // further download.
                fp.clear();
                fp.add(FetchProfile.Item.BODY_SANE);
                //  TODO a good optimization here would be to make sure that all Stores set
                //  the proper size after this fetch and compare the before and after size. If
                //  they equal we can mark this SYNCHRONIZED instead of PARTIALLY_SYNCHRONIZED
                remoteFolder.fetch(new Message[] { message }, fp, null);

                // Store the partially-loaded message and mark it partially loaded
                Utilities.copyOneMessageToProvider(context, message, account, toMailbox,
                        EmailContent.Message.FLAG_LOADED_PARTIAL);
            } else {
                // We have a structure to deal with, from which
                // we can pull down the parts we want to actually store.
                // Build a list of parts we are interested in. Text parts will be downloaded
                // right now, attachments will be left for later.
                ArrayList<Part> viewables = new ArrayList<Part>();
                ArrayList<Part> attachments = new ArrayList<Part>();
                MimeUtility.collectParts(message, viewables, attachments);
                // Download the viewables immediately
                for (Part part : viewables) {
                    fp.clear();
                    fp.add(part);
                    // TODO what happens if the network connection dies? We've got partial
                    // messages with incorrect status stored.
                    remoteFolder.fetch(new Message[] { message }, fp, null);
                }
                // Store the updated message locally and mark it fully loaded
                Utilities.copyOneMessageToProvider(context, message, account, toMailbox,
                        EmailContent.Message.FLAG_LOADED_COMPLETE);
            }
        }

    }

    public static void downloadFlagAndEnvelope(final Context context, final Account account,
            final Mailbox mailbox, Folder remoteFolder, ArrayList<Message> unsyncedMessages,
            HashMap<String, LocalMessageInfo> localMessageMap, final ArrayList<Long> unseenMessages)
            throws MessagingException {
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.FLAGS);
        fp.add(FetchProfile.Item.ENVELOPE);

        final HashMap<String, LocalMessageInfo> localMapCopy;
        if (localMessageMap != null)
            localMapCopy = new HashMap<String, LocalMessageInfo>(localMessageMap);
        else {
            localMapCopy = new HashMap<String, LocalMessageInfo>();
        }

        remoteFolder.fetch(unsyncedMessages.toArray(new Message[0]), fp,
                new MessageRetrievalListener() {
                    @Override
                    public void messageRetrieved(Message message) {
                        try {
                            // Determine if the new message was already known (e.g. partial)
                            // And create or reload the full message info
                            LocalMessageInfo localMessageInfo =
                                localMapCopy.get(message.getUid());
                            EmailContent.Message localMessage = null;
                            if (localMessageInfo == null) {
                                localMessage = new EmailContent.Message();
                            } else {
                                localMessage = EmailContent.Message.restoreMessageWithId(
                                        context, localMessageInfo.mId);
                            }

                            if (localMessage != null) {
                                try {
                                    // Copy the fields that are available into the message
                                    LegacyConversions.updateMessageFields(localMessage,
                                            message, account.mId, mailbox.mId);
                                    // Commit the message to the local store
                                    Utilities.saveOrUpdate(localMessage, context);
                                    // Track the "new" ness of the downloaded message
                                    if (!message.isSet(Flag.SEEN) && unseenMessages != null) {
                                        unseenMessages.add(localMessage.mId);
                                    }
                                } catch (MessagingException me) {
                                    Log.e(Logging.LOG_TAG,
                                            "Error while copying downloaded message." + me);
                                }

                            }
                        }
                        catch (Exception e) {
                            Log.e(Logging.LOG_TAG,
                                    "Error while storing downloaded message." + e.toString());
                        }
                    }

                    @Override
                    public void loadAttachmentProgress(int progress) {
                    }
                });

    }

    /**
     * Synchronizer for IMAP.
     *
     * TODO Break this method up into smaller chunks.
     *
     * @param account the account to sync
     * @param mailbox the mailbox to sync
     * @return results of the sync pass
     * @throws MessagingException
     */
    private static void synchronizeMailboxGeneric(final Context context,
            final Account account, final Mailbox mailbox) throws MessagingException {

        /*
         * A list of IDs for messages that were downloaded and did not have the seen flag set.
         * This serves as the "true" new message count reported to the user via notification.
         */
        final ArrayList<Long> unseenMessages = new ArrayList<Long>();

        ContentResolver resolver = context.getContentResolver();

        // 0.  We do not ever sync DRAFTS or OUTBOX (down or up)
        if (mailbox.mType == Mailbox.TYPE_DRAFTS || mailbox.mType == Mailbox.TYPE_OUTBOX) {
            return;
        }

        // 1.  Get the message list from the local store and create an index of the uids

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

        // 2.  Open the remote folder and create the remote folder if necessary

        Store remoteStore = Store.getInstance(account, context);
        // The account might have been deleted
        if (remoteStore == null) return;
        Folder remoteFolder = remoteStore.getFolder(mailbox.mServerId);

        /*
         * If the folder is a "special" folder we need to see if it exists
         * on the remote server. It if does not exist we'll try to create it. If we
         * can't create we'll abort. This will happen on every single Pop3 folder as
         * designed and on Imap folders during error conditions. This allows us
         * to treat Pop3 and Imap the same in this code.
         */
        if (mailbox.mType == Mailbox.TYPE_TRASH || mailbox.mType == Mailbox.TYPE_SENT
                || mailbox.mType == Mailbox.TYPE_DRAFTS) {
            if (!remoteFolder.exists()) {
                if (!remoteFolder.create(FolderType.HOLDS_MESSAGES)) {
                    return;
                }
            }
        }

        // 3, Open the remote folder. This pre-loads certain metadata like message count.
        remoteFolder.open(OpenMode.READ_WRITE);

        // 4. Trash any remote messages that are marked as trashed locally.
        // TODO - this comment was here, but no code was here.

        // 5. Get the remote message count.
        int remoteMessageCount = remoteFolder.getMessageCount();
        ContentValues values = new ContentValues();
        values.put(MailboxColumns.TOTAL_COUNT, remoteMessageCount);
        mailbox.update(context, values);

        // 6. Determine the limit # of messages to download
        int visibleLimit = mailbox.mVisibleLimit;
        if (visibleLimit <= 0) {
            visibleLimit = MailActivityEmail.VISIBLE_LIMIT_DEFAULT;
        }

        // 7.  Create a list of messages to download
        Message[] remoteMessages = new Message[0];
        final ArrayList<Message> unsyncedMessages = new ArrayList<Message>();
        HashMap<String, Message> remoteUidMap = new HashMap<String, Message>();

        if (remoteMessageCount > 0) {
            /*
             * Message numbers start at 1.
             */
            int remoteStart = Math.max(0, remoteMessageCount - visibleLimit) + 1;
            int remoteEnd = remoteMessageCount;
            remoteMessages = remoteFolder.getMessages(remoteStart, remoteEnd, null);
            // TODO Why are we running through the list twice? Combine w/ for loop below
            for (Message message : remoteMessages) {
                remoteUidMap.put(message.getUid(), message);
            }

            /*
             * Get a list of the messages that are in the remote list but not on the
             * local store, or messages that are in the local store but failed to download
             * on the last sync. These are the new messages that we will download.
             * Note, we also skip syncing messages which are flagged as "deleted message" sentinels,
             * because they are locally deleted and we don't need or want the old message from
             * the server.
             */
            for (Message message : remoteMessages) {
                LocalMessageInfo localMessage = localMessageMap.get(message.getUid());
                // localMessage == null -> message has never been created (not even headers)
                // mFlagLoaded = UNLOADED -> message created, but none of body loaded
                // mFlagLoaded = PARTIAL -> message created, a "sane" amt of body has been loaded
                // mFlagLoaded = COMPLETE -> message body has been completely loaded
                // mFlagLoaded = DELETED -> message has been deleted
                // Only the first two of these are "unsynced", so let's retrieve them
                if (localMessage == null ||
                        (localMessage.mFlagLoaded == EmailContent.Message.FLAG_LOADED_UNLOADED)) {
                    unsyncedMessages.add(message);
                }
            }
        }

        // 8.  Download basic info about the new/unloaded messages (if any)
        /*
         * Fetch the flags and envelope only of the new messages. This is intended to get us
         * critical data as fast as possible, and then we'll fill in the details.
         */
        if (unsyncedMessages.size() > 0) {
            downloadFlagAndEnvelope(context, account, mailbox, remoteFolder, unsyncedMessages,
                    localMessageMap, unseenMessages);
        }

        // 9. Refresh the flags for any messages in the local store that we didn't just download.
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.FLAGS);
        remoteFolder.fetch(remoteMessages, fp, null);
        boolean remoteSupportsSeen = false;
        boolean remoteSupportsFlagged = false;
        boolean remoteSupportsAnswered = false;
        for (Flag flag : remoteFolder.getPermanentFlags()) {
            if (flag == Flag.SEEN) {
                remoteSupportsSeen = true;
            }
            if (flag == Flag.FLAGGED) {
                remoteSupportsFlagged = true;
            }
            if (flag == Flag.ANSWERED) {
                remoteSupportsAnswered = true;
            }
        }
        // Update SEEN/FLAGGED/ANSWERED (star) flags (if supported remotely - e.g. not for POP3)
        if (remoteSupportsSeen || remoteSupportsFlagged || remoteSupportsAnswered) {
            for (Message remoteMessage : remoteMessages) {
                LocalMessageInfo localMessageInfo = localMessageMap.get(remoteMessage.getUid());
                if (localMessageInfo == null) {
                    continue;
                }
                boolean localSeen = localMessageInfo.mFlagRead;
                boolean remoteSeen = remoteMessage.isSet(Flag.SEEN);
                boolean newSeen = (remoteSupportsSeen && (remoteSeen != localSeen));
                boolean localFlagged = localMessageInfo.mFlagFavorite;
                boolean remoteFlagged = remoteMessage.isSet(Flag.FLAGGED);
                boolean newFlagged = (remoteSupportsFlagged && (localFlagged != remoteFlagged));
                int localFlags = localMessageInfo.mFlags;
                boolean localAnswered = (localFlags & EmailContent.Message.FLAG_REPLIED_TO) != 0;
                boolean remoteAnswered = remoteMessage.isSet(Flag.ANSWERED);
                boolean newAnswered = (remoteSupportsAnswered && (localAnswered != remoteAnswered));
                if (newSeen || newFlagged || newAnswered) {
                    Uri uri = ContentUris.withAppendedId(
                            EmailContent.Message.CONTENT_URI, localMessageInfo.mId);
                    ContentValues updateValues = new ContentValues();
                    updateValues.put(MessageColumns.FLAG_READ, remoteSeen);
                    updateValues.put(MessageColumns.FLAG_FAVORITE, remoteFlagged);
                    if (remoteAnswered) {
                        localFlags |= EmailContent.Message.FLAG_REPLIED_TO;
                    } else {
                        localFlags &= ~EmailContent.Message.FLAG_REPLIED_TO;
                    }
                    updateValues.put(MessageColumns.FLAGS, localFlags);
                    resolver.update(uri, updateValues, null, null);
                }
            }
        }

        // 10. Remove any messages that are in the local store but no longer on the remote store.
        HashSet<String> localUidsToDelete = new HashSet<String>(localMessageMap.keySet());
        localUidsToDelete.removeAll(remoteUidMap.keySet());
        for (String uidToDelete : localUidsToDelete) {
            LocalMessageInfo infoToDelete = localMessageMap.get(uidToDelete);

            // Delete associated data (attachment files)
            // Attachment & Body records are auto-deleted when we delete the Message record
            AttachmentUtilities.deleteAllAttachmentFiles(context, account.mId,
                    infoToDelete.mId);

            // Delete the message itself
            Uri uriToDelete = ContentUris.withAppendedId(
                    EmailContent.Message.CONTENT_URI, infoToDelete.mId);
            resolver.delete(uriToDelete, null, null);

            // Delete extra rows (e.g. synced or deleted)
            Uri syncRowToDelete = ContentUris.withAppendedId(
                    EmailContent.Message.UPDATED_CONTENT_URI, infoToDelete.mId);
            resolver.delete(syncRowToDelete, null, null);
            Uri deletERowToDelete = ContentUris.withAppendedId(
                    EmailContent.Message.UPDATED_CONTENT_URI, infoToDelete.mId);
            resolver.delete(deletERowToDelete, null, null);
        }

        loadUnsyncedMessages(context, account, remoteFolder, unsyncedMessages, mailbox);

        // 14. Clean up and report results
        remoteFolder.close(false);
    }

    /**
     * Find messages in the updated table that need to be written back to server.
     *
     * Handles:
     *   Read/Unread
     *   Flagged
     *   Append (upload)
     *   Move To Trash
     *   Empty trash
     * TODO:
     *   Move
     *
     * @param account the account to scan for pending actions
     * @throws MessagingException
     */
    private static void processPendingActionsSynchronous(Context context, Account account)
           throws MessagingException {
        TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(context, account));
        String[] accountIdArgs = new String[] { Long.toString(account.mId) };

        // Handle deletes first, it's always better to get rid of things first
        processPendingDeletesSynchronous(context, account, accountIdArgs);

        // Handle uploads (currently, only to sent messages)
        processPendingUploadsSynchronous(context, account, accountIdArgs);

        // Now handle updates / upsyncs
        processPendingUpdatesSynchronous(context, account, accountIdArgs);
    }

    /**
     * Get the mailbox corresponding to the remote location of a message; this will normally be
     * the mailbox whose _id is mailboxKey, except for search results, where we must look it up
     * by serverId
     * @param message the message in question
     * @return the mailbox in which the message resides on the server
     */
    private static Mailbox getRemoteMailboxForMessage(Context context,
            EmailContent.Message message) {
        // If this is a search result, use the protocolSearchInfo field to get the server info
        if (!TextUtils.isEmpty(message.mProtocolSearchInfo)) {
            long accountKey = message.mAccountKey;
            String protocolSearchInfo = message.mProtocolSearchInfo;
            if (accountKey == mLastSearchAccountKey &&
                    protocolSearchInfo.equals(mLastSearchServerId)) {
                return mLastSearchRemoteMailbox;
            }
            Cursor c =  context.getContentResolver().query(Mailbox.CONTENT_URI,
                    Mailbox.CONTENT_PROJECTION, Mailbox.PATH_AND_ACCOUNT_SELECTION,
                    new String[] {protocolSearchInfo, Long.toString(accountKey)},
                    null);
            try {
                if (c.moveToNext()) {
                    Mailbox mailbox = new Mailbox();
                    mailbox.restore(c);
                    mLastSearchAccountKey = accountKey;
                    mLastSearchServerId = protocolSearchInfo;
                    mLastSearchRemoteMailbox = mailbox;
                    return mailbox;
                } else {
                    return null;
                }
            } finally {
                c.close();
            }
        } else {
            return Mailbox.restoreMailboxWithId(context, message.mMailboxKey);
        }
    }

    /**
     * Scan for messages that are in the Message_Deletes table, look for differences that
     * we can deal with, and do the work.
     *
     * @param account
     * @param resolver
     * @param accountIdArgs
     */
    private static void processPendingDeletesSynchronous(Context context, Account account,
            String[] accountIdArgs) {
        Cursor deletes = context.getContentResolver().query(
                EmailContent.Message.DELETED_CONTENT_URI,
                EmailContent.Message.CONTENT_PROJECTION,
                EmailContent.MessageColumns.ACCOUNT_KEY + "=?", accountIdArgs,
                EmailContent.MessageColumns.MAILBOX_KEY);
        long lastMessageId = -1;
        try {
            // Defer setting up the store until we know we need to access it
            Store remoteStore = null;
            // loop through messages marked as deleted
            while (deletes.moveToNext()) {
                boolean deleteFromTrash = false;

                EmailContent.Message oldMessage =
                        EmailContent.getContent(deletes, EmailContent.Message.class);

                if (oldMessage != null) {
                    lastMessageId = oldMessage.mId;

                    Mailbox mailbox = getRemoteMailboxForMessage(context, oldMessage);
                    if (mailbox == null) {
                        continue; // Mailbox removed. Move to the next message.
                    }
                    deleteFromTrash = mailbox.mType == Mailbox.TYPE_TRASH;

                    // Load the remote store if it will be needed
                    if (remoteStore == null && deleteFromTrash) {
                        remoteStore = Store.getInstance(account, context);
                    }

                    // Dispatch here for specific change types
                    if (deleteFromTrash) {
                        // Move message to trash
                        processPendingDeleteFromTrash(context, remoteStore, account, mailbox,
                                oldMessage);
                    }
                }

                // Finally, delete the update
                Uri uri = ContentUris.withAppendedId(EmailContent.Message.DELETED_CONTENT_URI,
                        oldMessage.mId);
                context.getContentResolver().delete(uri, null, null);
            }
        } catch (MessagingException me) {
            // Presumably an error here is an account connection failure, so there is
            // no point in continuing through the rest of the pending updates.
            if (MailActivityEmail.DEBUG) {
                Log.d(Logging.LOG_TAG, "Unable to process pending delete for id="
                            + lastMessageId + ": " + me);
            }
        } finally {
            deletes.close();
        }
    }

    /**
     * Scan for messages that are in Sent, and are in need of upload,
     * and send them to the server.  "In need of upload" is defined as:
     *  serverId == null (no UID has been assigned)
     * or
     *  message is in the updated list
     *
     * Note we also look for messages that are moving from drafts->outbox->sent.  They never
     * go through "drafts" or "outbox" on the server, so we hang onto these until they can be
     * uploaded directly to the Sent folder.
     *
     * @param account
     * @param resolver
     * @param accountIdArgs
     */
    private static void processPendingUploadsSynchronous(Context context, Account account,
            String[] accountIdArgs) {
        ContentResolver resolver = context.getContentResolver();
        // Find the Sent folder (since that's all we're uploading for now
        Cursor mailboxes = resolver.query(Mailbox.CONTENT_URI, Mailbox.ID_PROJECTION,
                MailboxColumns.ACCOUNT_KEY + "=?"
                + " and " + MailboxColumns.TYPE + "=" + Mailbox.TYPE_SENT,
                accountIdArgs, null);
        long lastMessageId = -1;
        try {
            // Defer setting up the store until we know we need to access it
            Store remoteStore = null;
            while (mailboxes.moveToNext()) {
                long mailboxId = mailboxes.getLong(Mailbox.ID_PROJECTION_COLUMN);
                String[] mailboxKeyArgs = new String[] { Long.toString(mailboxId) };
                // Demand load mailbox
                Mailbox mailbox = null;

                // First handle the "new" messages (serverId == null)
                Cursor upsyncs1 = resolver.query(EmailContent.Message.CONTENT_URI,
                        EmailContent.Message.ID_PROJECTION,
                        EmailContent.Message.MAILBOX_KEY + "=?"
                        + " and (" + EmailContent.Message.SERVER_ID + " is null"
                        + " or " + EmailContent.Message.SERVER_ID + "=''" + ")",
                        mailboxKeyArgs,
                        null);
                try {
                    while (upsyncs1.moveToNext()) {
                        // Load the remote store if it will be needed
                        if (remoteStore == null) {
                            remoteStore = Store.getInstance(account, context);
                        }
                        // Load the mailbox if it will be needed
                        if (mailbox == null) {
                            mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
                            if (mailbox == null) {
                                continue; // Mailbox removed. Move to the next message.
                            }
                        }
                        // upsync the message
                        long id = upsyncs1.getLong(EmailContent.Message.ID_PROJECTION_COLUMN);
                        lastMessageId = id;
                        processUploadMessage(context, remoteStore, account, mailbox, id);
                    }
                } finally {
                    if (upsyncs1 != null) {
                        upsyncs1.close();
                    }
                }

                // Next, handle any updates (e.g. edited in place, although this shouldn't happen)
                Cursor upsyncs2 = resolver.query(EmailContent.Message.UPDATED_CONTENT_URI,
                        EmailContent.Message.ID_PROJECTION,
                        EmailContent.MessageColumns.MAILBOX_KEY + "=?", mailboxKeyArgs,
                        null);
                try {
                    while (upsyncs2.moveToNext()) {
                        // Load the remote store if it will be needed
                        if (remoteStore == null) {
                            remoteStore = Store.getInstance(account, context);
                        }
                        // Load the mailbox if it will be needed
                        if (mailbox == null) {
                            mailbox = Mailbox.restoreMailboxWithId(context, mailboxId);
                            if (mailbox == null) {
                                continue; // Mailbox removed. Move to the next message.
                            }
                        }
                        // upsync the message
                        long id = upsyncs2.getLong(EmailContent.Message.ID_PROJECTION_COLUMN);
                        lastMessageId = id;
                        processUploadMessage(context, remoteStore, account, mailbox, id);
                    }
                } finally {
                    if (upsyncs2 != null) {
                        upsyncs2.close();
                    }
                }
            }
        } catch (MessagingException me) {
            // Presumably an error here is an account connection failure, so there is
            // no point in continuing through the rest of the pending updates.
            if (MailActivityEmail.DEBUG) {
                Log.d(Logging.LOG_TAG, "Unable to process pending upsync for id="
                        + lastMessageId + ": " + me);
            }
        } finally {
            if (mailboxes != null) {
                mailboxes.close();
            }
        }
    }

    /**
     * Scan for messages that are in the Message_Updates table, look for differences that
     * we can deal with, and do the work.
     *
     * @param account
     * @param resolver
     * @param accountIdArgs
     */
    private static void processPendingUpdatesSynchronous(Context context, Account account,
            String[] accountIdArgs) {
        ContentResolver resolver = context.getContentResolver();
        Cursor updates = resolver.query(EmailContent.Message.UPDATED_CONTENT_URI,
                EmailContent.Message.CONTENT_PROJECTION,
                EmailContent.MessageColumns.ACCOUNT_KEY + "=?", accountIdArgs,
                EmailContent.MessageColumns.MAILBOX_KEY);
        long lastMessageId = -1;
        try {
            // Defer setting up the store until we know we need to access it
            Store remoteStore = null;
            // Demand load mailbox (note order-by to reduce thrashing here)
            Mailbox mailbox = null;
            // loop through messages marked as needing updates
            while (updates.moveToNext()) {
                boolean changeMoveToTrash = false;
                boolean changeRead = false;
                boolean changeFlagged = false;
                boolean changeMailbox = false;
                boolean changeAnswered = false;

                EmailContent.Message oldMessage =
                    EmailContent.getContent(updates, EmailContent.Message.class);
                lastMessageId = oldMessage.mId;
                EmailContent.Message newMessage =
                    EmailContent.Message.restoreMessageWithId(context, oldMessage.mId);
                if (newMessage != null) {
                    mailbox = Mailbox.restoreMailboxWithId(context, newMessage.mMailboxKey);
                    if (mailbox == null) {
                        continue; // Mailbox removed. Move to the next message.
                    }
                    if (oldMessage.mMailboxKey != newMessage.mMailboxKey) {
                        if (mailbox.mType == Mailbox.TYPE_TRASH) {
                            changeMoveToTrash = true;
                        } else {
                            changeMailbox = true;
                        }
                    }
                    changeRead = oldMessage.mFlagRead != newMessage.mFlagRead;
                    changeFlagged = oldMessage.mFlagFavorite != newMessage.mFlagFavorite;
                    changeAnswered = (oldMessage.mFlags & EmailContent.Message.FLAG_REPLIED_TO) !=
                        (newMessage.mFlags & EmailContent.Message.FLAG_REPLIED_TO);
               }

                // Load the remote store if it will be needed
                if (remoteStore == null &&
                        (changeMoveToTrash || changeRead || changeFlagged || changeMailbox ||
                                changeAnswered)) {
                    remoteStore = Store.getInstance(account, context);
                }

                // Dispatch here for specific change types
                if (changeMoveToTrash) {
                    // Move message to trash
                    processPendingMoveToTrash(context, remoteStore, account, mailbox, oldMessage,
                            newMessage);
                } else if (changeRead || changeFlagged || changeMailbox || changeAnswered) {
                    processPendingDataChange(context, remoteStore, mailbox, changeRead,
                            changeFlagged, changeMailbox, changeAnswered, oldMessage, newMessage);
                }

                // Finally, delete the update
                Uri uri = ContentUris.withAppendedId(EmailContent.Message.UPDATED_CONTENT_URI,
                        oldMessage.mId);
                resolver.delete(uri, null, null);
            }

        } catch (MessagingException me) {
            // Presumably an error here is an account connection failure, so there is
            // no point in continuing through the rest of the pending updates.
            if (MailActivityEmail.DEBUG) {
                Log.d(Logging.LOG_TAG, "Unable to process pending update for id="
                            + lastMessageId + ": " + me);
            }
        } finally {
            updates.close();
        }
    }

    /**
     * Upsync an entire message.  This must also unwind whatever triggered it (either by
     * updating the serverId, or by deleting the update record, or it's going to keep happening
     * over and over again.
     *
     * Note:  If the message is being uploaded into an unexpected mailbox, we *do not* upload.
     * This is to avoid unnecessary uploads into the trash.  Although the caller attempts to select
     * only the Drafts and Sent folders, this can happen when the update record and the current
     * record mismatch.  In this case, we let the update record remain, because the filters
     * in processPendingUpdatesSynchronous() will pick it up as a move and handle it (or drop it)
     * appropriately.
     *
     * @param resolver
     * @param remoteStore
     * @param account
     * @param mailbox the actual mailbox
     * @param messageId
     */
    private static void processUploadMessage(Context context, Store remoteStore,
            Account account, Mailbox mailbox, long messageId)
            throws MessagingException {
        EmailContent.Message newMessage =
            EmailContent.Message.restoreMessageWithId(context, messageId);
        boolean deleteUpdate = false;
        if (newMessage == null) {
            deleteUpdate = true;
            Log.d(Logging.LOG_TAG, "Upsync failed for null message, id=" + messageId);
        } else if (mailbox.mType == Mailbox.TYPE_DRAFTS) {
            deleteUpdate = false;
            Log.d(Logging.LOG_TAG, "Upsync skipped for mailbox=drafts, id=" + messageId);
        } else if (mailbox.mType == Mailbox.TYPE_OUTBOX) {
            deleteUpdate = false;
            Log.d(Logging.LOG_TAG, "Upsync skipped for mailbox=outbox, id=" + messageId);
        } else if (mailbox.mType == Mailbox.TYPE_TRASH) {
            deleteUpdate = false;
            Log.d(Logging.LOG_TAG, "Upsync skipped for mailbox=trash, id=" + messageId);
        } else if (newMessage != null && newMessage.mMailboxKey != mailbox.mId) {
            deleteUpdate = false;
            Log.d(Logging.LOG_TAG, "Upsync skipped; mailbox changed, id=" + messageId);
        } else {
//            Log.d(Logging.LOG_TAG, "Upsyc triggered for message id=" + messageId);
//            deleteUpdate = processPendingAppend(context, remoteStore, account, mailbox,
            //newMessage);
        }
        if (deleteUpdate) {
            // Finally, delete the update (if any)
            Uri uri = ContentUris.withAppendedId(
                    EmailContent.Message.UPDATED_CONTENT_URI, messageId);
            context.getContentResolver().delete(uri, null, null);
        }
    }

    /**
     * Upsync changes to read, flagged, or mailbox
     *
     * @param remoteStore the remote store for this mailbox
     * @param mailbox the mailbox the message is stored in
     * @param changeRead whether the message's read state has changed
     * @param changeFlagged whether the message's flagged state has changed
     * @param changeMailbox whether the message's mailbox has changed
     * @param oldMessage the message in it's pre-change state
     * @param newMessage the current version of the message
     */
    private static void processPendingDataChange(final Context context, Store remoteStore,
            Mailbox mailbox, boolean changeRead, boolean changeFlagged, boolean changeMailbox,
            boolean changeAnswered, EmailContent.Message oldMessage,
            final EmailContent.Message newMessage) throws MessagingException {
        // New mailbox is the mailbox this message WILL be in (same as the one it WAS in if it isn't
        // being moved
        Mailbox newMailbox = mailbox;
        // Mailbox is the original remote mailbox (the one we're acting on)
        mailbox = getRemoteMailboxForMessage(context, oldMessage);

        // 0. No remote update if the message is local-only
        if (newMessage.mServerId == null || newMessage.mServerId.equals("")
                || newMessage.mServerId.startsWith(LOCAL_SERVERID_PREFIX) || (mailbox == null)) {
            return;
        }

        // 1. No remote update for DRAFTS or OUTBOX
        if (mailbox.mType == Mailbox.TYPE_DRAFTS || mailbox.mType == Mailbox.TYPE_OUTBOX) {
            return;
        }

        // 2. Open the remote store & folder
        Folder remoteFolder = remoteStore.getFolder(mailbox.mServerId);
        if (!remoteFolder.exists()) {
            return;
        }
        remoteFolder.open(OpenMode.READ_WRITE);
        if (remoteFolder.getMode() != OpenMode.READ_WRITE) {
            return;
        }

        // 3. Finally, apply the changes to the message
        Message remoteMessage = remoteFolder.getMessage(newMessage.mServerId);
        if (remoteMessage == null) {
            return;
        }
        if (MailActivityEmail.DEBUG) {
            Log.d(Logging.LOG_TAG,
                    "Update for msg id=" + newMessage.mId
                    + " read=" + newMessage.mFlagRead
                    + " flagged=" + newMessage.mFlagFavorite
                    + " answered="
                    + ((newMessage.mFlags & EmailContent.Message.FLAG_REPLIED_TO) != 0)
                    + " new mailbox=" + newMessage.mMailboxKey);
        }
        Message[] messages = new Message[] { remoteMessage };
        if (changeRead) {
            remoteFolder.setFlags(messages, FLAG_LIST_SEEN, newMessage.mFlagRead);
        }
        if (changeFlagged) {
            remoteFolder.setFlags(messages, FLAG_LIST_FLAGGED, newMessage.mFlagFavorite);
        }
        if (changeAnswered) {
            remoteFolder.setFlags(messages, FLAG_LIST_ANSWERED,
                    (newMessage.mFlags & EmailContent.Message.FLAG_REPLIED_TO) != 0);
        }
        if (changeMailbox) {
            Folder toFolder = remoteStore.getFolder(newMailbox.mServerId);
            if (!remoteFolder.exists()) {
                return;
            }
            // We may need the message id to search for the message in the destination folder
            remoteMessage.setMessageId(newMessage.mMessageId);
            // Copy the message to its new folder
            remoteFolder.copyMessages(messages, toFolder, new MessageUpdateCallbacks() {
                @Override
                public void onMessageUidChange(Message message, String newUid) {
                    ContentValues cv = new ContentValues();
                    cv.put(EmailContent.Message.SERVER_ID, newUid);
                    // We only have one message, so, any updates _must_ be for it. Otherwise,
                    // we'd have to cycle through to find the one with the same server ID.
                    context.getContentResolver().update(ContentUris.withAppendedId(
                            EmailContent.Message.CONTENT_URI, newMessage.mId), cv, null, null);
                }
                @Override
                public void onMessageNotFound(Message message) {
                }
            });
            // Delete the message from the remote source folder
            remoteMessage.setFlag(Flag.DELETED, true);
            remoteFolder.expunge();
        }
        remoteFolder.close(false);
    }

    /**
     * Process a pending trash message command.
     *
     * @param remoteStore the remote store we're working in
     * @param account The account in which we are working
     * @param newMailbox The local trash mailbox
     * @param oldMessage The message copy that was saved in the updates shadow table
     * @param newMessage The message that was moved to the mailbox
     */
    private static void processPendingMoveToTrash(final Context context, Store remoteStore,
            Account account, Mailbox newMailbox, EmailContent.Message oldMessage,
            final EmailContent.Message newMessage) throws MessagingException {

        // 0. No remote move if the message is local-only
        if (newMessage.mServerId == null || newMessage.mServerId.equals("")
                || newMessage.mServerId.startsWith(LOCAL_SERVERID_PREFIX)) {
            return;
        }

        // 1. Escape early if we can't find the local mailbox
        // TODO smaller projection here
        Mailbox oldMailbox = getRemoteMailboxForMessage(context, oldMessage);
        if (oldMailbox == null) {
            // can't find old mailbox, it may have been deleted.  just return.
            return;
        }
        // 2. We don't support delete-from-trash here
        if (oldMailbox.mType == Mailbox.TYPE_TRASH) {
            return;
        }

        // 3. If DELETE_POLICY_NEVER, simply write back the deleted sentinel and return
        //
        // This sentinel takes the place of the server-side message, and locally "deletes" it
        // by inhibiting future sync or display of the message.  It will eventually go out of
        // scope when it becomes old, or is deleted on the server, and the regular sync code
        // will clean it up for us.
        if (account.getDeletePolicy() == Account.DELETE_POLICY_NEVER) {
            EmailContent.Message sentinel = new EmailContent.Message();
            sentinel.mAccountKey = oldMessage.mAccountKey;
            sentinel.mMailboxKey = oldMessage.mMailboxKey;
            sentinel.mFlagLoaded = EmailContent.Message.FLAG_LOADED_DELETED;
            sentinel.mFlagRead = true;
            sentinel.mServerId = oldMessage.mServerId;
            sentinel.save(context);

            return;
        }

        // The rest of this method handles server-side deletion

        // 4.  Find the remote mailbox (that we deleted from), and open it
        Folder remoteFolder = remoteStore.getFolder(oldMailbox.mServerId);
        if (!remoteFolder.exists()) {
            return;
        }

        remoteFolder.open(OpenMode.READ_WRITE);
        if (remoteFolder.getMode() != OpenMode.READ_WRITE) {
            remoteFolder.close(false);
            return;
        }

        // 5. Find the remote original message
        Message remoteMessage = remoteFolder.getMessage(oldMessage.mServerId);
        if (remoteMessage == null) {
            remoteFolder.close(false);
            return;
        }

        // 6. Find the remote trash folder, and create it if not found
        Folder remoteTrashFolder = remoteStore.getFolder(newMailbox.mServerId);
        if (!remoteTrashFolder.exists()) {
            /*
             * If the remote trash folder doesn't exist we try to create it.
             */
            remoteTrashFolder.create(FolderType.HOLDS_MESSAGES);
        }

        // 7.  Try to copy the message into the remote trash folder
        // Note, this entire section will be skipped for POP3 because there's no remote trash
        if (remoteTrashFolder.exists()) {
            /*
             * Because remoteTrashFolder may be new, we need to explicitly open it
             */
            remoteTrashFolder.open(OpenMode.READ_WRITE);
            if (remoteTrashFolder.getMode() != OpenMode.READ_WRITE) {
                remoteFolder.close(false);
                remoteTrashFolder.close(false);
                return;
            }

            remoteFolder.copyMessages(new Message[] { remoteMessage }, remoteTrashFolder,
                    new Folder.MessageUpdateCallbacks() {
                @Override
                public void onMessageUidChange(Message message, String newUid) {
                    // update the UID in the local trash folder, because some stores will
                    // have to change it when copying to remoteTrashFolder
                    ContentValues cv = new ContentValues();
                    cv.put(EmailContent.Message.SERVER_ID, newUid);
                    context.getContentResolver().update(newMessage.getUri(), cv, null, null);
                }

                /**
                 * This will be called if the deleted message doesn't exist and can't be
                 * deleted (e.g. it was already deleted from the server.)  In this case,
                 * attempt to delete the local copy as well.
                 */
                @Override
                public void onMessageNotFound(Message message) {
                    context.getContentResolver().delete(newMessage.getUri(), null, null);
                }
            });
            remoteTrashFolder.close(false);
        }

        // 8. Delete the message from the remote source folder
        remoteMessage.setFlag(Flag.DELETED, true);
        remoteFolder.expunge();
        remoteFolder.close(false);
    }

    /**
     * Process a pending trash message command.
     *
     * @param remoteStore the remote store we're working in
     * @param account The account in which we are working
     * @param oldMailbox The local trash mailbox
     * @param oldMessage The message that was deleted from the trash
     */
    private static void processPendingDeleteFromTrash(Context context, Store remoteStore,
            Account account, Mailbox oldMailbox, EmailContent.Message oldMessage)
            throws MessagingException {

        // 1. We only support delete-from-trash here
        if (oldMailbox.mType != Mailbox.TYPE_TRASH) {
            return;
        }

        // 2.  Find the remote trash folder (that we are deleting from), and open it
        Folder remoteTrashFolder = remoteStore.getFolder(oldMailbox.mServerId);
        if (!remoteTrashFolder.exists()) {
            return;
        }

        remoteTrashFolder.open(OpenMode.READ_WRITE);
        if (remoteTrashFolder.getMode() != OpenMode.READ_WRITE) {
            remoteTrashFolder.close(false);
            return;
        }

        // 3. Find the remote original message
        Message remoteMessage = remoteTrashFolder.getMessage(oldMessage.mServerId);
        if (remoteMessage == null) {
            remoteTrashFolder.close(false);
            return;
        }

        // 4. Delete the message from the remote trash folder
        remoteMessage.setFlag(Flag.DELETED, true);
        remoteTrashFolder.expunge();
        remoteTrashFolder.close(false);
    }

    /**
     * A message and numeric uid that's easily sortable
     */
    private static class SortableMessage {
        private final Message mMessage;
        private final long mUid;

        SortableMessage(Message message, long uid) {
            mMessage = message;
            mUid = uid;
        }
    }

    public int searchMailbox(Context context, long accountId, SearchParams searchParams,
            long destMailboxId) throws MessagingException {
        try {
            return searchMailboxImpl(context, accountId, searchParams, destMailboxId);
        } finally {
            // Tell UI
        }
    }

    private int searchMailboxImpl(final Context context, long accountId, SearchParams searchParams,
            final long destMailboxId) throws MessagingException {
        final Account account = Account.restoreAccountWithId(context, accountId);
        final Mailbox mailbox = Mailbox.restoreMailboxWithId(context, searchParams.mMailboxId);
        final Mailbox destMailbox = Mailbox.restoreMailboxWithId(context, destMailboxId);
        if (account == null || mailbox == null || destMailbox == null) {
            Log.d(Logging.LOG_TAG, "Attempted search for " + searchParams
                    + " but account or mailbox information was missing");
            return 0;
        }

        // Tell UI that we're loading messages

        Store remoteStore = Store.getInstance(account, context);
        Folder remoteFolder = remoteStore.getFolder(mailbox.mServerId);
        remoteFolder.open(OpenMode.READ_WRITE);

        SortableMessage[] sortableMessages = new SortableMessage[0];
        if (searchParams.mOffset == 0) {
            // Get the "bare" messages (basically uid)
            Message[] remoteMessages = remoteFolder.getMessages(searchParams, null);
            int remoteCount = remoteMessages.length;
            if (remoteCount > 0) {
                sortableMessages = new SortableMessage[remoteCount];
                int i = 0;
                for (Message msg : remoteMessages) {
                    sortableMessages[i++] = new SortableMessage(msg, Long.parseLong(msg.getUid()));
                }
                // Sort the uid's, most recent first
                // Note: Not all servers will be nice and return results in the order of request;
                // those that do will see messages arrive from newest to oldest
                Arrays.sort(sortableMessages, new Comparator<SortableMessage>() {
                    @Override
                    public int compare(SortableMessage lhs, SortableMessage rhs) {
                        return lhs.mUid > rhs.mUid ? -1 : lhs.mUid < rhs.mUid ? 1 : 0;
                    }
                });
                sSearchResults.put(accountId, sortableMessages);
            }
        } else {
            sortableMessages = sSearchResults.get(accountId);
        }

        final int numSearchResults = sortableMessages.length;
        final int numToLoad =
            Math.min(numSearchResults - searchParams.mOffset, searchParams.mLimit);
        if (numToLoad <= 0) {
            return 0;
        }

        final ArrayList<Message> messageList = new ArrayList<Message>();
        for (int i = searchParams.mOffset; i < numToLoad + searchParams.mOffset; i++) {
            messageList.add(sortableMessages[i].mMessage);
        }
        // Get everything in one pass, rather than two (as in sync); this starts getting us
        // usable results quickly.
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.FLAGS);
        fp.add(FetchProfile.Item.ENVELOPE);
        fp.add(FetchProfile.Item.STRUCTURE);
        fp.add(FetchProfile.Item.BODY_SANE);
        remoteFolder.fetch(messageList.toArray(new Message[0]), fp,
                new MessageRetrievalListener() {
            @Override
            public void messageRetrieved(Message message) {
                try {
                    // Determine if the new message was already known (e.g. partial)
                    // And create or reload the full message info
                    EmailContent.Message localMessage = new EmailContent.Message();
                    try {
                        // Copy the fields that are available into the message
                        LegacyConversions.updateMessageFields(localMessage,
                                message, account.mId, mailbox.mId);
                        // Commit the message to the local store
                        Utilities.saveOrUpdate(localMessage, context);
                        localMessage.mMailboxKey = destMailboxId;
                        // We load 50k or so; maybe it's complete, maybe not...
                        int flag = EmailContent.Message.FLAG_LOADED_COMPLETE;
                        // We store the serverId of the source mailbox into protocolSearchInfo
                        // This will be used by loadMessageForView, etc. to use the proper remote
                        // folder
                        localMessage.mProtocolSearchInfo = mailbox.mServerId;
                        if (message.getSize() > Store.FETCH_BODY_SANE_SUGGESTED_SIZE) {
                            flag = EmailContent.Message.FLAG_LOADED_PARTIAL;
                        }
                        Utilities.copyOneMessageToProvider(context, message, localMessage, flag);
                    } catch (MessagingException me) {
                        Log.e(Logging.LOG_TAG,
                                "Error while copying downloaded message." + me);
                    }
                } catch (Exception e) {
                    Log.e(Logging.LOG_TAG,
                            "Error while storing downloaded message." + e.toString());
                }
            }

            @Override
            public void loadAttachmentProgress(int progress) {
            }
        });
        return numSearchResults;
    }
}