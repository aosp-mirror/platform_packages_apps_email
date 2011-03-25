/*
 * Copyright (C) 2008 The Android Open Source Project
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

import com.android.email.mail.FetchProfile;
import com.android.email.mail.Flag;
import com.android.email.mail.Folder;
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Part;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.email.mail.StoreSynchronizer;
import com.android.email.mail.Folder.FolderType;
import com.android.email.mail.Folder.MessageRetrievalListener;
import com.android.email.mail.Folder.OpenMode;
import com.android.email.mail.internet.MimeBodyPart;
import com.android.email.mail.internet.MimeHeader;
import com.android.email.mail.internet.MimeMultipart;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.AttachmentColumns;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.MessageColumns;
import com.android.email.provider.EmailContent.SyncColumns;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Starts a long running (application) Thread that will run through commands
 * that require remote mailbox access. This class is used to serialize and
 * prioritize these commands. Each method that will submit a command requires a
 * MessagingListener instance to be provided. It is expected that that listener
 * has also been added as a registered listener using addListener(). When a
 * command is to be executed, if the listener that was provided with the command
 * is no longer registered the command is skipped. The design idea for the above
 * is that when an Activity starts it registers as a listener. When it is paused
 * it removes itself. Thus, any commands that that activity submitted are
 * removed from the queue once the activity is no longer active.
 */
public class MessagingController implements Runnable {

    /**
     * The maximum message size that we'll consider to be "small". A small message is downloaded
     * in full immediately instead of in pieces. Anything over this size will be downloaded in
     * pieces with attachments being left off completely and downloaded on demand.
     *
     *
     * 25k for a "small" message was picked by educated trial and error.
     * http://answers.google.com/answers/threadview?id=312463 claims that the
     * average size of an email is 59k, which I feel is too large for our
     * blind download. The following tests were performed on a download of
     * 25 random messages.
     * <pre>
     * 5k - 61 seconds,
     * 25k - 51 seconds,
     * 55k - 53 seconds,
     * </pre>
     * So 25k gives good performance and a reasonable data footprint. Sounds good to me.
     */
    private static final int MAX_SMALL_MESSAGE_SIZE = (25 * 1024);

    private static final Flag[] FLAG_LIST_SEEN = new Flag[] { Flag.SEEN };
    private static final Flag[] FLAG_LIST_FLAGGED = new Flag[] { Flag.FLAGGED };

    /**
     * We write this into the serverId field of messages that will never be upsynced.
     */
    private static final String LOCAL_SERVERID_PREFIX = "Local-";

    /**
     * Projections & CVs used by pruneCachedAttachments
     */
    private static final String[] PRUNE_ATTACHMENT_PROJECTION = new String[] {
        AttachmentColumns.LOCATION
    };
    private static final ContentValues PRUNE_ATTACHMENT_CV = new ContentValues();
    static {
        PRUNE_ATTACHMENT_CV.putNull(AttachmentColumns.CONTENT_URI);
    }

    private static MessagingController sInstance = null;
    private final BlockingQueue<Command> mCommands = new LinkedBlockingQueue<Command>();
    private final Thread mThread;

    /**
     * All access to mListeners *must* be synchronized
     */
    private final GroupMessagingListener mListeners = new GroupMessagingListener();
    private boolean mBusy;
    private final Context mContext;

    protected MessagingController(Context _context) {
        mContext = _context;

        mThread = new Thread(this);
        mThread.start();
    }

    /**
     * Gets or creates the singleton instance of MessagingController. Application is used to
     * provide a Context to classes that need it.
     * @param application
     * @return
     */
    public synchronized static MessagingController getInstance(Context _context) {
        if (sInstance == null) {
            sInstance = new MessagingController(_context);
        }
        return sInstance;
    }

    /**
     * Inject a mock controller.  Used only for testing.  Affects future calls to getInstance().
     */
    public static void injectMockController(MessagingController mockController) {
        sInstance = mockController;
    }

    // TODO: seems that this reading of mBusy isn't thread-safe
    public boolean isBusy() {
        return mBusy;
    }

    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        // TODO: add an end test to this infinite loop
        while (true) {
            Command command;
            try {
                command = mCommands.take();
            } catch (InterruptedException e) {
                continue; //re-test the condition on the eclosing while
            }
            if (command.listener == null || isActiveListener(command.listener)) {
                mBusy = true;
                command.runnable.run();
                mListeners.controllerCommandCompleted(mCommands.size() > 0);
            }
            mBusy = false;
        }
    }

    private void put(String description, MessagingListener listener, Runnable runnable) {
        try {
            Command command = new Command();
            command.listener = listener;
            command.runnable = runnable;
            command.description = description;
            mCommands.add(command);
        }
        catch (IllegalStateException ie) {
            throw new Error(ie);
        }
    }

    public void addListener(MessagingListener listener) {
        mListeners.addListener(listener);
    }

    public void removeListener(MessagingListener listener) {
        mListeners.removeListener(listener);
    }

    private boolean isActiveListener(MessagingListener listener) {
        return mListeners.isActiveListener(listener);
    }

    /**
     * Lightweight class for capturing local mailboxes in an account.  Just the columns
     * necessary for a sync.
     */
    private static class LocalMailboxInfo {
        private static final int COLUMN_ID = 0;
        private static final int COLUMN_DISPLAY_NAME = 1;
        private static final int COLUMN_ACCOUNT_KEY = 2;
        private static final int COLUMN_TYPE = 3;

        private static final String[] PROJECTION = new String[] {
            EmailContent.RECORD_ID,
            MailboxColumns.DISPLAY_NAME, MailboxColumns.ACCOUNT_KEY, MailboxColumns.TYPE,
        };

        final long mId;
        final String mDisplayName;
        final long mAccountKey;
        final int mType;

        public LocalMailboxInfo(Cursor c) {
            mId = c.getLong(COLUMN_ID);
            mDisplayName = c.getString(COLUMN_DISPLAY_NAME);
            mAccountKey = c.getLong(COLUMN_ACCOUNT_KEY);
            mType = c.getInt(COLUMN_TYPE);
        }
    }

    /**
     * Lists folders that are available locally and remotely. This method calls
     * listFoldersCallback for local folders before it returns, and then for
     * remote folders at some later point. If there are no local folders
     * includeRemote is forced by this method. This method should be called from
     * a Thread as it may take several seconds to list the local folders.
     *
     * TODO this needs to cache the remote folder list
     * TODO break out an inner listFoldersSynchronized which could simplify checkMail
     *
     * @param account
     * @param listener
     * @throws MessagingException
     */
    public void listFolders(final long accountId, MessagingListener listener) {
        final EmailContent.Account account =
                EmailContent.Account.restoreAccountWithId(mContext, accountId);
        if (account == null) {
            return;
        }
        mListeners.listFoldersStarted(accountId);
        put("listFolders", listener, new Runnable() {
            public void run() {
                Cursor localFolderCursor = null;
                try {
                    // Step 1:  Get remote folders, make a list, and add any local folders
                    // that don't already exist.

                    Store store = Store.getInstance(account.getStoreUri(mContext), mContext, null);

                    Folder[] remoteFolders = store.getPersonalNamespaces();

                    HashSet<String> remoteFolderNames = new HashSet<String>();
                    for (int i = 0, count = remoteFolders.length; i < count; i++) {
                        remoteFolderNames.add(remoteFolders[i].getName());
                    }

                    HashMap<String, LocalMailboxInfo> localFolders =
                        new HashMap<String, LocalMailboxInfo>();
                    HashSet<String> localFolderNames = new HashSet<String>();
                    localFolderCursor = mContext.getContentResolver().query(
                            EmailContent.Mailbox.CONTENT_URI,
                            LocalMailboxInfo.PROJECTION,
                            EmailContent.MailboxColumns.ACCOUNT_KEY + "=?",
                            new String[] { String.valueOf(account.mId) },
                            null);
                    while (localFolderCursor.moveToNext()) {
                        LocalMailboxInfo info = new LocalMailboxInfo(localFolderCursor);
                        localFolders.put(info.mDisplayName, info);
                        localFolderNames.add(info.mDisplayName);
                    }

                    // Short circuit the rest if the sets are the same (the usual case)
                    if (!remoteFolderNames.equals(localFolderNames)) {

                        // They are different, so we have to do some adds and drops

                        // Drops first, to make things smaller rather than larger
                        HashSet<String> localsToDrop = new HashSet<String>(localFolderNames);
                        localsToDrop.removeAll(remoteFolderNames);
                        for (String localNameToDrop : localsToDrop) {
                            LocalMailboxInfo localInfo = localFolders.get(localNameToDrop);
                            // Exclusion list - never delete local special folders, irrespective
                            // of server-side existence.
                            switch (localInfo.mType) {
                                case Mailbox.TYPE_INBOX:
                                case Mailbox.TYPE_DRAFTS:
                                case Mailbox.TYPE_OUTBOX:
                                case Mailbox.TYPE_SENT:
                                case Mailbox.TYPE_TRASH:
                                    break;
                                default:
                                    // Drop all attachment files related to this mailbox
                                    AttachmentProvider.deleteAllMailboxAttachmentFiles(
                                            mContext, accountId, localInfo.mId);
                                    // Delete the mailbox.  Triggers will take care of
                                    // related Message, Body and Attachment records.
                                    Uri uri = ContentUris.withAppendedId(
                                            EmailContent.Mailbox.CONTENT_URI, localInfo.mId);
                                    mContext.getContentResolver().delete(uri, null, null);
                                    break;
                            }
                        }

                        // Now do the adds
                        remoteFolderNames.removeAll(localFolderNames);
                        for (String remoteNameToAdd : remoteFolderNames) {
                            EmailContent.Mailbox box = new EmailContent.Mailbox();
                            box.mDisplayName = remoteNameToAdd;
                            // box.mServerId;
                            // box.mParentServerId;
                            box.mAccountKey = account.mId;
                            box.mType = LegacyConversions.inferMailboxTypeFromName(
                                    mContext, remoteNameToAdd);
                            // box.mDelimiter;
                            // box.mSyncKey;
                            // box.mSyncLookback;
                            // box.mSyncFrequency;
                            // box.mSyncTime;
                            // box.mUnreadCount;
                            box.mFlagVisible = true;
                            // box.mFlags;
                            box.mVisibleLimit = Email.VISIBLE_LIMIT_DEFAULT;
                            box.save(mContext);
                        }
                    }
                    mListeners.listFoldersFinished(accountId);
                } catch (Exception e) {
                    mListeners.listFoldersFailed(accountId, "");
                } finally {
                    if (localFolderCursor != null) {
                        localFolderCursor.close();
                    }
                }
            }
        });
    }

    /**
     * Start background synchronization of the specified folder.
     * @param account
     * @param folder
     * @param listener
     */
    public void synchronizeMailbox(final EmailContent.Account account,
            final EmailContent.Mailbox folder, MessagingListener listener) {
        /*
         * We don't ever sync the Outbox.
         */
        if (folder.mType == EmailContent.Mailbox.TYPE_OUTBOX) {
            return;
        }
        mListeners.synchronizeMailboxStarted(account.mId, folder.mId);
        put("synchronizeMailbox", listener, new Runnable() {
            public void run() {
                synchronizeMailboxSynchronous(account, folder);
            }
        });
    }

    /**
     * Start foreground synchronization of the specified folder. This is called by
     * synchronizeMailbox or checkMail.
     * TODO this should use ID's instead of fully-restored objects
     * @param account
     * @param folder
     */
    private void synchronizeMailboxSynchronous(final EmailContent.Account account,
            final EmailContent.Mailbox folder) {
        mListeners.synchronizeMailboxStarted(account.mId, folder.mId);
        try {
            processPendingActionsSynchronous(account);

            StoreSynchronizer.SyncResults results;

            // Select generic sync or store-specific sync
            Store remoteStore = Store.getInstance(account.getStoreUri(mContext), mContext, null);
            StoreSynchronizer customSync = remoteStore.getMessageSynchronizer();
            if (customSync == null) {
                results = synchronizeMailboxGeneric(account, folder);
            } else {
                results = customSync.SynchronizeMessagesSynchronous(
                        account, folder, mListeners, mContext);
            }
            mListeners.synchronizeMailboxFinished(account.mId, folder.mId,
                                                  results.mTotalMessages,
                                                  results.mNewMessages);
        } catch (MessagingException e) {
            if (Email.LOGD) {
                Log.v(Email.LOG_TAG, "synchronizeMailbox", e);
            }
            mListeners.synchronizeMailboxFailed(account.mId, folder.mId, e);
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
        private static final String[] PROJECTION = new String[] {
            EmailContent.RECORD_ID,
            MessageColumns.FLAG_READ, MessageColumns.FLAG_FAVORITE, MessageColumns.FLAG_LOADED,
            SyncColumns.SERVER_ID, MessageColumns.MAILBOX_KEY, MessageColumns.ACCOUNT_KEY
        };

        final int mCursorIndex;
        final long mId;
        final boolean mFlagRead;
        final boolean mFlagFavorite;
        final int mFlagLoaded;
        final String mServerId;

        public LocalMessageInfo(Cursor c) {
            mCursorIndex = c.getPosition();
            mId = c.getLong(COLUMN_ID);
            mFlagRead = c.getInt(COLUMN_FLAG_READ) != 0;
            mFlagFavorite = c.getInt(COLUMN_FLAG_FAVORITE) != 0;
            mFlagLoaded = c.getInt(COLUMN_FLAG_LOADED);
            mServerId = c.getString(COLUMN_SERVER_ID);
            // Note: mailbox key and account key not needed - they are projected for the SELECT
        }
    }

    private void saveOrUpdate(EmailContent content) {
        if (content.isSaved()) {
            content.update(mContext, content.toContentValues());
        } else {
            content.save(mContext);
        }
    }

    /**
     * Generic synchronizer - used for POP3 and IMAP.
     *
     * TODO Break this method up into smaller chunks.
     *
     * @param account the account to sync
     * @param folder the mailbox to sync
     * @return results of the sync pass
     * @throws MessagingException
     */
    private StoreSynchronizer.SyncResults synchronizeMailboxGeneric(
            final EmailContent.Account account, final EmailContent.Mailbox folder)
            throws MessagingException {

        Log.d(Email.LOG_TAG, "*** synchronizeMailboxGeneric ***");
        ContentResolver resolver = mContext.getContentResolver();

        // 0.  We do not ever sync DRAFTS or OUTBOX (down or up)
        if (folder.mType == Mailbox.TYPE_DRAFTS || folder.mType == Mailbox.TYPE_OUTBOX) {
            int totalMessages = EmailContent.count(mContext, folder.getUri(), null, null);
            return new StoreSynchronizer.SyncResults(totalMessages, 0);
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
                            String.valueOf(folder.mId)
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

        // 1a. Count the unread messages before changing anything
        int localUnreadCount = EmailContent.count(mContext, EmailContent.Message.CONTENT_URI,
                EmailContent.MessageColumns.ACCOUNT_KEY + "=?" +
                " AND " + MessageColumns.MAILBOX_KEY + "=?" +
                " AND " + MessageColumns.FLAG_READ + "=0",
                new String[] {
                        String.valueOf(account.mId),
                        String.valueOf(folder.mId)
                });

        // 2.  Open the remote folder and create the remote folder if necessary

        Store remoteStore = Store.getInstance(account.getStoreUri(mContext), mContext, null);
        Folder remoteFolder = remoteStore.getFolder(folder.mDisplayName);

        /*
         * If the folder is a "special" folder we need to see if it exists
         * on the remote server. It if does not exist we'll try to create it. If we
         * can't create we'll abort. This will happen on every single Pop3 folder as
         * designed and on Imap folders during error conditions. This allows us
         * to treat Pop3 and Imap the same in this code.
         */
        if (folder.mType == Mailbox.TYPE_TRASH || folder.mType == Mailbox.TYPE_SENT
                || folder.mType == Mailbox.TYPE_DRAFTS) {
            if (!remoteFolder.exists()) {
                if (!remoteFolder.create(FolderType.HOLDS_MESSAGES)) {
                    return new StoreSynchronizer.SyncResults(0, 0);
                }
            }
        }

        // 3, Open the remote folder. This pre-loads certain metadata like message count.
        remoteFolder.open(OpenMode.READ_WRITE, null);

        // 4. Trash any remote messages that are marked as trashed locally.
        // TODO - this comment was here, but no code was here.

        // 5. Get the remote message count.
        int remoteMessageCount = remoteFolder.getMessageCount();

        // 6. Determine the limit # of messages to download
        int visibleLimit = folder.mVisibleLimit;
        if (visibleLimit <= 0) {
            Store.StoreInfo info = Store.StoreInfo.getStoreInfo(account.getStoreUri(mContext),
                    mContext);
            visibleLimit = info.mVisibleLimitDefault;
        }

        // 7.  Create a list of messages to download
        Message[] remoteMessages = new Message[0];
        final ArrayList<Message> unsyncedMessages = new ArrayList<Message>();
        HashMap<String, Message> remoteUidMap = new HashMap<String, Message>();

        int newMessageCount = 0;
        if (remoteMessageCount > 0) {
            /*
             * Message numbers start at 1.
             */
            int remoteStart = Math.max(0, remoteMessageCount - visibleLimit) + 1;
            int remoteEnd = remoteMessageCount;
            remoteMessages = remoteFolder.getMessages(remoteStart, remoteEnd, null);
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
                if (localMessage == null) {
                    newMessageCount++;
                }
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
         * A list of messages that were downloaded and which did not have the Seen flag set.
         * This will serve to indicate the true "new" message count that will be reported to
         * the user via notification.
         */
        final ArrayList<Message> newMessages = new ArrayList<Message>();

        /*
         * Fetch the flags and envelope only of the new messages. This is intended to get us
         * critical data as fast as possible, and then we'll fill in the details.
         */
        if (unsyncedMessages.size() > 0) {
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.FLAGS);
            fp.add(FetchProfile.Item.ENVELOPE);
            final HashMap<String, LocalMessageInfo> localMapCopy =
                new HashMap<String, LocalMessageInfo>(localMessageMap);

            remoteFolder.fetch(unsyncedMessages.toArray(new Message[0]), fp,
                    new MessageRetrievalListener() {
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
                                            mContext, localMessageInfo.mId);
                                }

                                if (localMessage != null) {
                                    try {
                                        // Copy the fields that are available into the message
                                        LegacyConversions.updateMessageFields(localMessage,
                                                message, account.mId, folder.mId);
                                        // Commit the message to the local store
                                        saveOrUpdate(localMessage);
                                        // Track the "new" ness of the downloaded message
                                        if (!message.isSet(Flag.SEEN)) {
                                            newMessages.add(message);
                                        }
                                    } catch (MessagingException me) {
                                        Log.e(Email.LOG_TAG,
                                                "Error while copying downloaded message." + me);
                                    }

                                }
                            }
                            catch (Exception e) {
                                Log.e(Email.LOG_TAG,
                                        "Error while storing downloaded message." + e.toString());
                            }
                        }
                    });
        }

        // 9. Refresh the flags for any messages in the local store that we didn't just download.
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.FLAGS);
        remoteFolder.fetch(remoteMessages, fp, null);
        boolean remoteSupportsSeen = false;
        boolean remoteSupportsFlagged = false;
        for (Flag flag : remoteFolder.getPermanentFlags()) {
            if (flag == Flag.SEEN) {
                remoteSupportsSeen = true;
            }
            if (flag == Flag.FLAGGED) {
                remoteSupportsFlagged = true;
            }
        }
        // Update the SEEN & FLAGGED (star) flags (if supported remotely - e.g. not for POP3)
        if (remoteSupportsSeen || remoteSupportsFlagged) {
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
                if (newSeen || newFlagged) {
                    Uri uri = ContentUris.withAppendedId(
                            EmailContent.Message.CONTENT_URI, localMessageInfo.mId);
                    ContentValues updateValues = new ContentValues();
                    updateValues.put(EmailContent.Message.FLAG_READ, remoteSeen);
                    updateValues.put(EmailContent.Message.FLAG_FAVORITE, remoteFlagged);
                    resolver.update(uri, updateValues, null, null);
                }
            }
        }

        // 10. Compute and store the unread message count.
        // -- no longer necessary - Provider uses DB triggers to keep track

//        int remoteUnreadMessageCount = remoteFolder.getUnreadMessageCount();
//        if (remoteUnreadMessageCount == -1) {
//            if (remoteSupportsSeenFlag) {
//                /*
//                 * If remote folder doesn't supported unread message count but supports
//                 * seen flag, use local folder's unread message count and the size of
//                 * new messages. This mode is not used for POP3, or IMAP.
//                 */
//
//                remoteUnreadMessageCount = folder.mUnreadCount + newMessages.size();
//            } else {
//                /*
//                 * If remote folder doesn't supported unread message count and doesn't
//                 * support seen flag, use localUnreadCount and newMessageCount which
//                 * don't rely on remote SEEN flag.  This mode is used by POP3.
//                 */
//                remoteUnreadMessageCount = localUnreadCount + newMessageCount;
//            }
//        } else {
//            /*
//             * If remote folder supports unread message count, use remoteUnreadMessageCount.
//             * This mode is used by IMAP.
//             */
//         }
//        Uri uri = ContentUris.withAppendedId(EmailContent.Mailbox.CONTENT_URI, folder.mId);
//        ContentValues updateValues = new ContentValues();
//        updateValues.put(EmailContent.Mailbox.UNREAD_COUNT, remoteUnreadMessageCount);
//        resolver.update(uri, updateValues, null, null);

        // 11. Remove any messages that are in the local store but no longer on the remote store.

        HashSet<String> localUidsToDelete = new HashSet<String>(localMessageMap.keySet());
        localUidsToDelete.removeAll(remoteUidMap.keySet());
        for (String uidToDelete : localUidsToDelete) {
            LocalMessageInfo infoToDelete = localMessageMap.get(uidToDelete);

            // Delete associated data (attachment files)
            // Attachment & Body records are auto-deleted when we delete the Message record
            AttachmentProvider.deleteAllAttachmentFiles(mContext, account.mId, infoToDelete.mId);

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

        // 12. Divide the unsynced messages into small & large (by size)

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

        // 13. Download small messages

        // TODO Problems with this implementation.  1. For IMAP, where we get a real envelope,
        // this is going to be inefficient and duplicate work we've already done.  2.  It's going
        // back to the DB for a local message that we already had (and discarded).

        // For small messages, we specify "body", which returns everything (incl. attachments)
        fp = new FetchProfile();
        fp.add(FetchProfile.Item.BODY);
        remoteFolder.fetch(smallMessages.toArray(new Message[smallMessages.size()]), fp,
                new MessageRetrievalListener() {
                    public void messageRetrieved(Message message) {
                        // Store the updated message locally and mark it fully loaded
                        copyOneMessageToProvider(message, account, folder,
                                EmailContent.Message.FLAG_LOADED_COMPLETE);
                    }
        });

        // 14. Download large messages.  We ask the server to give us the message structure,
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
                copyOneMessageToProvider(message, account, folder,
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
                copyOneMessageToProvider(message, account, folder,
                        EmailContent.Message.FLAG_LOADED_COMPLETE);
            }
        }

        // 15. Clean up and report results

        remoteFolder.close(false);
        // TODO - more

        // Original sync code.  Using for reference, will delete when done.
        if (false) {
        /*
         * Now do the large messages that require more round trips.
         */
        fp.clear();
        fp.add(FetchProfile.Item.STRUCTURE);
        remoteFolder.fetch(largeMessages.toArray(new Message[largeMessages.size()]),
                fp, null);
        for (Message message : largeMessages) {
            if (message.getBody() == null) {
                /*
                 * The provider was unable to get the structure of the message, so
                 * we'll download a reasonable portion of the messge and mark it as
                 * incomplete so the entire thing can be downloaded later if the user
                 * wishes to download it.
                 */
                fp.clear();
                fp.add(FetchProfile.Item.BODY_SANE);
                /*
                 *  TODO a good optimization here would be to make sure that all Stores set
                 *  the proper size after this fetch and compare the before and after size. If
                 *  they equal we can mark this SYNCHRONIZED instead of PARTIALLY_SYNCHRONIZED
                 */

                remoteFolder.fetch(new Message[] { message }, fp, null);
                // Store the updated message locally
//                localFolder.appendMessages(new Message[] {
//                    message
//                });

//                Message localMessage = localFolder.getMessage(message.getUid());

                // Set a flag indicating that the message has been partially downloaded and
                // is ready for view.
//                localMessage.setFlag(Flag.X_DOWNLOADED_PARTIAL, true);
            } else {
                /*
                 * We have a structure to deal with, from which
                 * we can pull down the parts we want to actually store.
                 * Build a list of parts we are interested in. Text parts will be downloaded
                 * right now, attachments will be left for later.
                 */

                ArrayList<Part> viewables = new ArrayList<Part>();
                ArrayList<Part> attachments = new ArrayList<Part>();
                MimeUtility.collectParts(message, viewables, attachments);

                /*
                 * Now download the parts we're interested in storing.
                 */
                for (Part part : viewables) {
                    fp.clear();
                    fp.add(part);
                    // TODO what happens if the network connection dies? We've got partial
                    // messages with incorrect status stored.
                    remoteFolder.fetch(new Message[] { message }, fp, null);
                }
                // Store the updated message locally
//                localFolder.appendMessages(new Message[] {
//                    message
//                });

//                Message localMessage = localFolder.getMessage(message.getUid());

                // Set a flag indicating this message has been fully downloaded and can be
                // viewed.
//                localMessage.setFlag(Flag.X_DOWNLOADED_FULL, true);
            }

            // Update the listener with what we've found
//            synchronized (mListeners) {
//                for (MessagingListener l : mListeners) {
//                    l.synchronizeMailboxNewMessage(
//                            account,
//                            folder,
//                            localFolder.getMessage(message.getUid()));
//                }
//            }
        }


        /*
         * Report successful sync
         */
        StoreSynchronizer.SyncResults results = new StoreSynchronizer.SyncResults(
                remoteFolder.getMessageCount(), newMessages.size());

        remoteFolder.close(false);
//        localFolder.close(false);

        return results;
        }

        return new StoreSynchronizer.SyncResults(remoteMessageCount, newMessages.size());
    }

    /**
     * Copy one downloaded message (which may have partially-loaded sections)
     * into a provider message
     *
     * @param message the remote message we've just downloaded
     * @param account the account it will be stored into
     * @param folder the mailbox it will be stored into
     * @param loadStatus when complete, the message will be marked with this status (e.g.
     *        EmailContent.Message.LOADED)
     */
    private void copyOneMessageToProvider(Message message, EmailContent.Account account,
            EmailContent.Mailbox folder, int loadStatus) {
        try {
            EmailContent.Message localMessage = null;
            Cursor c = null;
            try {
                c = mContext.getContentResolver().query(
                        EmailContent.Message.CONTENT_URI,
                        EmailContent.Message.CONTENT_PROJECTION,
                        EmailContent.MessageColumns.ACCOUNT_KEY + "=?" +
                        " AND " + MessageColumns.MAILBOX_KEY + "=?" +
                        " AND " + SyncColumns.SERVER_ID + "=?",
                        new String[] {
                                String.valueOf(account.mId),
                                String.valueOf(folder.mId),
                                String.valueOf(message.getUid())
                        },
                        null);
                if (c.moveToNext()) {
                    localMessage = EmailContent.getContent(c, EmailContent.Message.class);
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
            if (localMessage == null) {
                Log.d(Email.LOG_TAG, "Could not retrieve message from db, UUID="
                        + message.getUid());
                return;
            }

            EmailContent.Body body = EmailContent.Body.restoreBodyWithMessageId(mContext,
                    localMessage.mId);
            if (body == null) {
                body = new EmailContent.Body();
            }
            try {
                // Copy the fields that are available into the message object
                LegacyConversions.updateMessageFields(localMessage, message, account.mId,
                        folder.mId);

                // Now process body parts & attachments
                ArrayList<Part> viewables = new ArrayList<Part>();
                ArrayList<Part> attachments = new ArrayList<Part>();
                MimeUtility.collectParts(message, viewables, attachments);

                LegacyConversions.updateBodyFields(body, localMessage, viewables);

                // Commit the message & body to the local store immediately
                saveOrUpdate(localMessage);
                saveOrUpdate(body);

                // process (and save) attachments
                LegacyConversions.updateAttachments(mContext, localMessage,
                        attachments, false);

                // One last update of message with two updated flags
                localMessage.mFlagLoaded = loadStatus;

                ContentValues cv = new ContentValues();
                cv.put(EmailContent.MessageColumns.FLAG_ATTACHMENT, localMessage.mFlagAttachment);
                cv.put(EmailContent.MessageColumns.FLAG_LOADED, localMessage.mFlagLoaded);
                Uri uri = ContentUris.withAppendedId(EmailContent.Message.CONTENT_URI,
                        localMessage.mId);
                mContext.getContentResolver().update(uri, cv, null, null);

            } catch (MessagingException me) {
                Log.e(Email.LOG_TAG, "Error while copying downloaded message." + me);
            }

        } catch (RuntimeException rte) {
            Log.e(Email.LOG_TAG, "Error while storing downloaded message." + rte.toString());
        } catch (IOException ioe) {
            Log.e(Email.LOG_TAG, "Error while storing attachment." + ioe.toString());
        }
    }

    public void processPendingActions(final long accountId) {
        put("processPendingActions", null, new Runnable() {
            public void run() {
                try {
                    EmailContent.Account account =
                        EmailContent.Account.restoreAccountWithId(mContext, accountId);
                    if (account == null) {
                        return;
                    }
                    processPendingActionsSynchronous(account);
                }
                catch (MessagingException me) {
                    if (Email.LOGD) {
                        Log.v(Email.LOG_TAG, "processPendingActions", me);
                    }
                    /*
                     * Ignore any exceptions from the commands. Commands will be processed
                     * on the next round.
                     */
                }
            }
        });
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
    private void processPendingActionsSynchronous(EmailContent.Account account)
           throws MessagingException {
        ContentResolver resolver = mContext.getContentResolver();
        String[] accountIdArgs = new String[] { Long.toString(account.mId) };

        // Handle deletes first, it's always better to get rid of things first
        processPendingDeletesSynchronous(account, resolver, accountIdArgs);

        // Handle uploads (currently, only to sent messages)
        processPendingUploadsSynchronous(account, resolver, accountIdArgs);

        // Now handle updates / upsyncs
        processPendingUpdatesSynchronous(account, resolver, accountIdArgs);
    }

    /**
     * Scan for messages that are in the Message_Deletes table, look for differences that
     * we can deal with, and do the work.
     *
     * @param account
     * @param resolver
     * @param accountIdArgs
     */
    private void processPendingDeletesSynchronous(EmailContent.Account account,
            ContentResolver resolver, String[] accountIdArgs) {
        Cursor deletes = resolver.query(EmailContent.Message.DELETED_CONTENT_URI,
                EmailContent.Message.CONTENT_PROJECTION,
                EmailContent.MessageColumns.ACCOUNT_KEY + "=?", accountIdArgs,
                EmailContent.MessageColumns.MAILBOX_KEY);
        long lastMessageId = -1;
        try {
            // Defer setting up the store until we know we need to access it
            Store remoteStore = null;
            // Demand load mailbox (note order-by to reduce thrashing here)
            Mailbox mailbox = null;
            // loop through messages marked as deleted
            while (deletes.moveToNext()) {
                boolean deleteFromTrash = false;

                EmailContent.Message oldMessage =
                    EmailContent.getContent(deletes, EmailContent.Message.class);
                lastMessageId = oldMessage.mId;

                if (oldMessage != null) {
                    if (mailbox == null || mailbox.mId != oldMessage.mMailboxKey) {
                        mailbox = Mailbox.restoreMailboxWithId(mContext, oldMessage.mMailboxKey);
                        if (mailbox == null) {
                            continue; // Mailbox removed. Move to the next message.
                        }
                    }
                    deleteFromTrash = mailbox.mType == Mailbox.TYPE_TRASH;
                }

                // Load the remote store if it will be needed
                if (remoteStore == null && deleteFromTrash) {
                    remoteStore = Store.getInstance(account.getStoreUri(mContext), mContext, null);
                }

                // Dispatch here for specific change types
                if (deleteFromTrash) {
                    // Move message to trash
                    processPendingDeleteFromTrash(remoteStore, account, mailbox, oldMessage);
                }

                // Finally, delete the update
                Uri uri = ContentUris.withAppendedId(EmailContent.Message.DELETED_CONTENT_URI,
                        oldMessage.mId);
                resolver.delete(uri, null, null);
            }

        } catch (MessagingException me) {
            // Presumably an error here is an account connection failure, so there is
            // no point in continuing through the rest of the pending updates.
            if (Email.DEBUG) {
                Log.d(Email.LOG_TAG, "Unable to process pending delete for id="
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
    private void processPendingUploadsSynchronous(EmailContent.Account account,
            ContentResolver resolver, String[] accountIdArgs) throws MessagingException {
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
                            remoteStore =
                                Store.getInstance(account.getStoreUri(mContext), mContext, null);
                        }
                        // Load the mailbox if it will be needed
                        if (mailbox == null) {
                            mailbox = Mailbox.restoreMailboxWithId(mContext, mailboxId);
                            if (mailbox == null) {
                                continue; // Mailbox removed. Move to the next message.
                            }
                        }
                        // upsync the message
                        long id = upsyncs1.getLong(EmailContent.Message.ID_PROJECTION_COLUMN);
                        lastMessageId = id;
                        processUploadMessage(resolver, remoteStore, account, mailbox, id);
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
                            remoteStore =
                                Store.getInstance(account.getStoreUri(mContext), mContext, null);
                        }
                        // Load the mailbox if it will be needed
                        if (mailbox == null) {
                            mailbox = Mailbox.restoreMailboxWithId(mContext, mailboxId);
                            if (mailbox == null) {
                                continue; // Mailbox removed. Move to the next message.
                            }
                        }
                        // upsync the message
                        long id = upsyncs2.getLong(EmailContent.Message.ID_PROJECTION_COLUMN);
                        lastMessageId = id;
                        processUploadMessage(resolver, remoteStore, account, mailbox, id);
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
            if (Email.DEBUG) {
                Log.d(Email.LOG_TAG, "Unable to process pending upsync for id="
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
    private void processPendingUpdatesSynchronous(EmailContent.Account account,
            ContentResolver resolver, String[] accountIdArgs) {
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

                EmailContent.Message oldMessage =
                    EmailContent.getContent(updates, EmailContent.Message.class);
                lastMessageId = oldMessage.mId;
                EmailContent.Message newMessage =
                    EmailContent.Message.restoreMessageWithId(mContext, oldMessage.mId);
                if (newMessage != null) {
                    if (mailbox == null || mailbox.mId != newMessage.mMailboxKey) {
                        mailbox = Mailbox.restoreMailboxWithId(mContext, newMessage.mMailboxKey);
                        if (mailbox == null) {
                            continue; // Mailbox removed. Move to the next message.
                        }
                    }
                    changeMoveToTrash = (oldMessage.mMailboxKey != newMessage.mMailboxKey)
                            && (mailbox.mType == Mailbox.TYPE_TRASH);
                    changeRead = oldMessage.mFlagRead != newMessage.mFlagRead;
                    changeFlagged = oldMessage.mFlagFavorite != newMessage.mFlagFavorite;
                }

                // Load the remote store if it will be needed
                if (remoteStore == null && (changeMoveToTrash || changeRead || changeFlagged)) {
                    remoteStore = Store.getInstance(account.getStoreUri(mContext), mContext, null);
                }

                // Dispatch here for specific change types
                if (changeMoveToTrash) {
                    // Move message to trash
                    processPendingMoveToTrash(remoteStore, account, mailbox, oldMessage,
                            newMessage);
                } else if (changeRead || changeFlagged) {
                    processPendingFlagChange(remoteStore, mailbox, changeRead, changeFlagged,
                            newMessage);
                }

                // Finally, delete the update
                Uri uri = ContentUris.withAppendedId(EmailContent.Message.UPDATED_CONTENT_URI,
                        oldMessage.mId);
                resolver.delete(uri, null, null);
            }

        } catch (MessagingException me) {
            // Presumably an error here is an account connection failure, so there is
            // no point in continuing through the rest of the pending updates.
            if (Email.DEBUG) {
                Log.d(Email.LOG_TAG, "Unable to process pending update for id="
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
    private void processUploadMessage(ContentResolver resolver, Store remoteStore,
            EmailContent.Account account, Mailbox mailbox, long messageId)
            throws MessagingException {
        EmailContent.Message newMessage =
            EmailContent.Message.restoreMessageWithId(mContext, messageId);
        boolean deleteUpdate = false;
        if (newMessage == null) {
            deleteUpdate = true;
            Log.d(Email.LOG_TAG, "Upsync failed for null message, id=" + messageId);
        } else if (mailbox.mType == Mailbox.TYPE_DRAFTS) {
            deleteUpdate = false;
            Log.d(Email.LOG_TAG, "Upsync skipped for mailbox=drafts, id=" + messageId);
        } else if (mailbox.mType == Mailbox.TYPE_OUTBOX) {
            deleteUpdate = false;
            Log.d(Email.LOG_TAG, "Upsync skipped for mailbox=outbox, id=" + messageId);
        } else if (mailbox.mType == Mailbox.TYPE_TRASH) {
            deleteUpdate = false;
            Log.d(Email.LOG_TAG, "Upsync skipped for mailbox=trash, id=" + messageId);
        } else if (newMessage != null && newMessage.mMailboxKey != mailbox.mId) {
            deleteUpdate = false;
            Log.d(Email.LOG_TAG, "Upsync skipped; mailbox changed, id=" + messageId);
        } else {
            Log.d(Email.LOG_TAG, "Upsyc triggered for message id=" + messageId);
            deleteUpdate = processPendingAppend(remoteStore, account, mailbox, newMessage);
        }
        if (deleteUpdate) {
            // Finally, delete the update (if any)
            Uri uri = ContentUris.withAppendedId(EmailContent.Message.UPDATED_CONTENT_URI, messageId);
            resolver.delete(uri, null, null);
        }
    }

    /**
     * Upsync changes to read or flagged
     *
     * @param remoteStore
     * @param mailbox
     * @param changeRead
     * @param changeFlagged
     * @param newMessage
     */
    private void processPendingFlagChange(Store remoteStore, Mailbox mailbox, boolean changeRead,
            boolean changeFlagged, EmailContent.Message newMessage) throws MessagingException {

        // 0. No remote update if the message is local-only
        if (newMessage.mServerId == null || newMessage.mServerId.equals("")
                || newMessage.mServerId.startsWith(LOCAL_SERVERID_PREFIX)) {
            return;
        }

        // 1. No remote update for DRAFTS or OUTBOX
        if (mailbox.mType == Mailbox.TYPE_DRAFTS || mailbox.mType == Mailbox.TYPE_OUTBOX) {
            return;
        }

        // 2. Open the remote store & folder
        Folder remoteFolder = remoteStore.getFolder(mailbox.mDisplayName);
        if (!remoteFolder.exists()) {
            return;
        }
        remoteFolder.open(OpenMode.READ_WRITE, null);
        if (remoteFolder.getMode() != OpenMode.READ_WRITE) {
            return;
        }

        // 3. Finally, apply the changes to the message
        Message remoteMessage = remoteFolder.getMessage(newMessage.mServerId);
        if (remoteMessage == null) {
            return;
        }
        if (Email.DEBUG) {
            Log.d(Email.LOG_TAG,
                    "Update flags for msg id=" + newMessage.mId
                    + " read=" + newMessage.mFlagRead
                    + " flagged=" + newMessage.mFlagFavorite);
        }
        Message[] messages = new Message[] { remoteMessage };
        if (changeRead) {
            remoteFolder.setFlags(messages, FLAG_LIST_SEEN, newMessage.mFlagRead);
        }
        if (changeFlagged) {
            remoteFolder.setFlags(messages, FLAG_LIST_FLAGGED, newMessage.mFlagFavorite);
        }
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
    private void processPendingMoveToTrash(Store remoteStore,
            EmailContent.Account account, Mailbox newMailbox, EmailContent.Message oldMessage,
            final EmailContent.Message newMessage) throws MessagingException {

        // 0. No remote move if the message is local-only
        if (newMessage.mServerId == null || newMessage.mServerId.equals("")
                || newMessage.mServerId.startsWith(LOCAL_SERVERID_PREFIX)) {
            return;
        }

        // 1. Escape early if we can't find the local mailbox
        // TODO smaller projection here
        Mailbox oldMailbox = Mailbox.restoreMailboxWithId(mContext, oldMessage.mMailboxKey);
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
            sentinel.save(mContext);

            return;
        }

        // The rest of this method handles server-side deletion

        // 4.  Find the remote mailbox (that we deleted from), and open it
        Folder remoteFolder = remoteStore.getFolder(oldMailbox.mDisplayName);
        if (!remoteFolder.exists()) {
            return;
        }

        remoteFolder.open(OpenMode.READ_WRITE, null);
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
        Folder remoteTrashFolder = remoteStore.getFolder(newMailbox.mDisplayName);
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
            remoteTrashFolder.open(OpenMode.READ_WRITE, null);
            if (remoteTrashFolder.getMode() != OpenMode.READ_WRITE) {
                remoteFolder.close(false);
                remoteTrashFolder.close(false);
                return;
            }

            remoteFolder.copyMessages(new Message[] { remoteMessage }, remoteTrashFolder,
                    new Folder.MessageUpdateCallbacks() {
                public void onMessageUidChange(Message message, String newUid) {
                    // update the UID in the local trash folder, because some stores will
                    // have to change it when copying to remoteTrashFolder
                    ContentValues cv = new ContentValues();
                    cv.put(EmailContent.Message.SERVER_ID, newUid);
                    mContext.getContentResolver().update(newMessage.getUri(), cv, null, null);
                }

                /**
                 * This will be called if the deleted message doesn't exist and can't be
                 * deleted (e.g. it was already deleted from the server.)  In this case,
                 * attempt to delete the local copy as well.
                 */
                public void onMessageNotFound(Message message) {
                    mContext.getContentResolver().delete(newMessage.getUri(), null, null);
                }

            }
            );
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
    private void processPendingDeleteFromTrash(Store remoteStore,
            EmailContent.Account account, Mailbox oldMailbox, EmailContent.Message oldMessage)
            throws MessagingException {

        // 1. We only support delete-from-trash here
        if (oldMailbox.mType != Mailbox.TYPE_TRASH) {
            return;
        }

        // 2.  Find the remote trash folder (that we are deleting from), and open it
        Folder remoteTrashFolder = remoteStore.getFolder(oldMailbox.mDisplayName);
        if (!remoteTrashFolder.exists()) {
            return;
        }

        remoteTrashFolder.open(OpenMode.READ_WRITE, null);
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
     * Process a pending append message command. This command uploads a local message to the
     * server, first checking to be sure that the server message is not newer than
     * the local message.
     *
     * @param remoteStore the remote store we're working in
     * @param account The account in which we are working
     * @param newMailbox The mailbox we're appending to
     * @param message The message we're appending
     * @return true if successfully uploaded
     */
    private boolean processPendingAppend(Store remoteStore, EmailContent.Account account,
            Mailbox newMailbox, EmailContent.Message message)
            throws MessagingException {

        boolean updateInternalDate = false;
        boolean updateMessage = false;
        boolean deleteMessage = false;

        // 1. Find the remote folder that we're appending to and create and/or open it
        Folder remoteFolder = remoteStore.getFolder(newMailbox.mDisplayName);
        if (!remoteFolder.exists()) {
            if (!remoteFolder.canCreate(FolderType.HOLDS_MESSAGES)) {
                // This is POP3, we cannot actually upload.  Instead, we'll update the message
                // locally with a fake serverId (so we don't keep trying here) and return.
                if (message.mServerId == null || message.mServerId.length() == 0) {
                    message.mServerId = LOCAL_SERVERID_PREFIX + message.mId;
                    Uri uri =
                        ContentUris.withAppendedId(EmailContent.Message.CONTENT_URI, message.mId);
                    ContentValues cv = new ContentValues();
                    cv.put(EmailContent.Message.SERVER_ID, message.mServerId);
                    mContext.getContentResolver().update(uri, cv, null, null);
                }
                return true;
            }
            if (!remoteFolder.create(FolderType.HOLDS_MESSAGES)) {
                // This is a (hopefully) transient error and we return false to try again later
                return false;
            }
        }
        remoteFolder.open(OpenMode.READ_WRITE, null);
        if (remoteFolder.getMode() != OpenMode.READ_WRITE) {
            return false;
        }

        // 2. If possible, load a remote message with the matching UID
        Message remoteMessage = null;
        if (message.mServerId != null && message.mServerId.length() > 0) {
            remoteMessage = remoteFolder.getMessage(message.mServerId);
        }

        // 3. If a remote message could not be found, upload our local message
        if (remoteMessage == null) {
            // 3a. Create a legacy message to upload
            Message localMessage = LegacyConversions.makeMessage(mContext, message);

            // 3b. Upload it
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.BODY);
            remoteFolder.appendMessages(new Message[] { localMessage });

            // 3b. And record the UID from the server
            message.mServerId = localMessage.getUid();
            updateInternalDate = true;
            updateMessage = true;
        } else {
            // 4. If the remote message exists we need to determine which copy to keep.
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            remoteFolder.fetch(new Message[] { remoteMessage }, fp, null);
            Date localDate = new Date(message.mServerTimeStamp);
            Date remoteDate = remoteMessage.getInternalDate();
            if (remoteDate != null && remoteDate.compareTo(localDate) > 0) {
                // 4a. If the remote message is newer than ours we'll just
                // delete ours and move on. A sync will get the server message
                // if we need to be able to see it.
                deleteMessage = true;
            } else {
                // 4b. Otherwise we'll upload our message and then delete the remote message.

                // Create a legacy message to upload
                Message localMessage = LegacyConversions.makeMessage(mContext, message);

                // 4c. Upload it
                fp.clear();
                fp = new FetchProfile();
                fp.add(FetchProfile.Item.BODY);
                remoteFolder.appendMessages(new Message[] { localMessage });

                // 4d. Record the UID and new internalDate from the server
                message.mServerId = localMessage.getUid();
                updateInternalDate = true;
                updateMessage = true;

                // 4e. And delete the old copy of the message from the server
                remoteMessage.setFlag(Flag.DELETED, true);
            }
        }

        // 5. If requested, Best-effort to capture new "internaldate" from the server
        if (updateInternalDate && message.mServerId != null) {
            try {
                Message remoteMessage2 = remoteFolder.getMessage(message.mServerId);
                if (remoteMessage2 != null) {
                    FetchProfile fp2 = new FetchProfile();
                    fp2.add(FetchProfile.Item.ENVELOPE);
                    remoteFolder.fetch(new Message[] { remoteMessage2 }, fp2, null);
                    message.mServerTimeStamp = remoteMessage2.getInternalDate().getTime();
                    updateMessage = true;
                }
            } catch (MessagingException me) {
                // skip it - we can live without this
            }
        }

        // 6. Perform required edits to local copy of message
        if (deleteMessage || updateMessage) {
            Uri uri = ContentUris.withAppendedId(EmailContent.Message.CONTENT_URI, message.mId);
            ContentResolver resolver = mContext.getContentResolver();
            if (deleteMessage) {
                resolver.delete(uri, null, null);
            } else if (updateMessage) {
                ContentValues cv = new ContentValues();
                cv.put(EmailContent.Message.SERVER_ID, message.mServerId);
                cv.put(EmailContent.Message.SERVER_TIMESTAMP, message.mServerTimeStamp);
                resolver.update(uri, cv, null, null);
            }
        }

        return true;
    }

    /**
     * Finish loading a message that have been partially downloaded.
     *
     * @param messageId the message to load
     * @param listener the callback by which results will be reported
     */
    public void loadMessageForView(final long messageId, MessagingListener listener) {
        mListeners.loadMessageForViewStarted(messageId);
        put("loadMessageForViewRemote", listener, new Runnable() {
            public void run() {
                try {
                    // 1. Resample the message, in case it disappeared or synced while
                    // this command was in queue
                    EmailContent.Message message =
                        EmailContent.Message.restoreMessageWithId(mContext, messageId);
                    if (message == null) {
                        mListeners.loadMessageForViewFailed(messageId, "Unknown message");
                        return;
                    }
                    if (message.mFlagLoaded == EmailContent.Message.FLAG_LOADED_COMPLETE) {
                        mListeners.loadMessageForViewFinished(messageId);
                        return;
                    }

                    // 2. Open the remote folder.
                    // TODO all of these could be narrower projections
                    // TODO combine with common code in loadAttachment
                    EmailContent.Account account =
                        EmailContent.Account.restoreAccountWithId(mContext, message.mAccountKey);
                    EmailContent.Mailbox mailbox =
                        EmailContent.Mailbox.restoreMailboxWithId(mContext, message.mMailboxKey);
                    if (account == null || mailbox == null) {
                        mListeners.loadMessageForViewFailed(messageId, "null account or mailbox");
                        return;
                    }

                    Store remoteStore =
                        Store.getInstance(account.getStoreUri(mContext), mContext, null);
                    Folder remoteFolder = remoteStore.getFolder(mailbox.mDisplayName);
                    remoteFolder.open(OpenMode.READ_WRITE, null);

                    // 3. Not supported, because IMAP & POP don't use it: structure prefetch
//                  if (remoteStore.requireStructurePrefetch()) {
//                  // For remote stores that require it, prefetch the message structure.
//                  FetchProfile fp = new FetchProfile();
//                  fp.add(FetchProfile.Item.STRUCTURE);
//                  localFolder.fetch(new Message[] { message }, fp, null);
//
//                  ArrayList<Part> viewables = new ArrayList<Part>();
//                  ArrayList<Part> attachments = new ArrayList<Part>();
//                  MimeUtility.collectParts(message, viewables, attachments);
//                  fp.clear();
//                  for (Part part : viewables) {
//                      fp.add(part);
//                  }
//
//                  remoteFolder.fetch(new Message[] { message }, fp, null);
//
//                  // Store the updated message locally
//                  localFolder.updateMessage((LocalMessage)message);

                    // 4. Set up to download the entire message
                    Message remoteMessage = remoteFolder.getMessage(message.mServerId);
                    FetchProfile fp = new FetchProfile();
                    fp.add(FetchProfile.Item.BODY);
                    remoteFolder.fetch(new Message[] { remoteMessage }, fp, null);

                    // 5. Write to provider
                    copyOneMessageToProvider(remoteMessage, account, mailbox,
                            EmailContent.Message.FLAG_LOADED_COMPLETE);

                    // 6. Notify UI
                    mListeners.loadMessageForViewFinished(messageId);

                } catch (MessagingException me) {
                    if (Email.LOGD) Log.v(Email.LOG_TAG, "", me);
                    mListeners.loadMessageForViewFailed(messageId, me.getMessage());
                } catch (RuntimeException rte) {
                    mListeners.loadMessageForViewFailed(messageId, rte.getMessage());
                }
            }
        });
    }

    /**
     * Attempts to load the attachment specified by id from the given account and message.
     * @param account
     * @param message
     * @param part
     * @param listener
     */
    public void loadAttachment(final long accountId, final long messageId, final long mailboxId,
            final long attachmentId, MessagingListener listener) {
        mListeners.loadAttachmentStarted(accountId, messageId, attachmentId, true);

        put("loadAttachment", listener, new Runnable() {
            public void run() {
                try {
                    //1. Check if the attachment is already here and return early in that case
                    File saveToFile = AttachmentProvider.getAttachmentFilename(mContext, accountId,
                            attachmentId);
                    Attachment attachment =
                        Attachment.restoreAttachmentWithId(mContext, attachmentId);
                    if (attachment == null) {
                        mListeners.loadAttachmentFailed(accountId, messageId, attachmentId,
                                "Attachment is null");
                        return;
                    }
                    if (saveToFile.exists() && attachment.mContentUri != null) {
                        mListeners.loadAttachmentFinished(accountId, messageId, attachmentId);
                        return;
                    }

                    // 2. Open the remote folder.
                    // TODO all of these could be narrower projections
                    EmailContent.Account account =
                        EmailContent.Account.restoreAccountWithId(mContext, accountId);
                    EmailContent.Mailbox mailbox =
                        EmailContent.Mailbox.restoreMailboxWithId(mContext, mailboxId);
                    EmailContent.Message message =
                        EmailContent.Message.restoreMessageWithId(mContext, messageId);

                    if (account == null || mailbox == null || message == null) {
                        mListeners.loadAttachmentFailed(accountId, messageId, attachmentId,
                                "Account, mailbox, message or attachment are null");
                        return;
                    }

                    // Pruning.  Policy is to have one downloaded attachment at a time,
                    // per account, to reduce disk storage pressure.
                    pruneCachedAttachments(accountId);

                    Store remoteStore =
                        Store.getInstance(account.getStoreUri(mContext), mContext, null);
                    Folder remoteFolder = remoteStore.getFolder(mailbox.mDisplayName);
                    remoteFolder.open(OpenMode.READ_WRITE, null);

                    // 3. Generate a shell message in which to retrieve the attachment,
                    // and a shell BodyPart for the attachment.  Then glue them together.
                    Message storeMessage = remoteFolder.createMessage(message.mServerId);
                    MimeBodyPart storePart = new MimeBodyPart();
                    storePart.setSize((int)attachment.mSize);
                    storePart.setHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA,
                            attachment.mLocation);
                    storePart.setHeader(MimeHeader.HEADER_CONTENT_TYPE,
                            String.format("%s;\n name=\"%s\"",
                            attachment.mMimeType,
                            attachment.mFileName));
                    // TODO is this always true for attachments?  I think we dropped the
                    // true encoding along the way
                    storePart.setHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING, "base64");

                    MimeMultipart multipart = new MimeMultipart();
                    multipart.setSubType("mixed");
                    multipart.addBodyPart(storePart);

                    storeMessage.setHeader(MimeHeader.HEADER_CONTENT_TYPE, "multipart/mixed");
                    storeMessage.setBody(multipart);

                    // 4. Now ask for the attachment to be fetched
                    FetchProfile fp = new FetchProfile();
                    fp.add(storePart);
                    remoteFolder.fetch(new Message[] { storeMessage }, fp, null);

                    // 5. Save the downloaded file and update the attachment as necessary
                    LegacyConversions.saveAttachmentBody(mContext, storePart, attachment,
                            accountId);

                    // 6. Report success
                    mListeners.loadAttachmentFinished(accountId, messageId, attachmentId);
                }
                catch (MessagingException me) {
                    if (Email.LOGD) Log.v(Email.LOG_TAG, "", me);
                    mListeners.loadAttachmentFailed(accountId, messageId, attachmentId,
                            me.getMessage());
                } catch (IOException ioe) {
                    Log.e(Email.LOG_TAG, "Error while storing attachment." + ioe.toString());
                }
            }});
    }

    /**
     * Erase all stored attachments for a given account.  Rules:
     *   1.  All files in attachment directory are up for deletion
     *   2.  If filename does not match an known attachment id, it's deleted
     *   3.  If the attachment has location data (implying that it's reloadable), it's deleted
     */
    /* package */ void pruneCachedAttachments(long accountId) {
        ContentResolver resolver = mContext.getContentResolver();
        File cacheDir = AttachmentProvider.getAttachmentDirectory(mContext, accountId);
        File[] fileList = cacheDir.listFiles();
        // fileList can be null if the directory doesn't exist or if there's an IOException
        if (fileList == null) return;
        for (File file : fileList) {
            if (file.exists()) {
                long id;
                try {
                    // the name of the file == the attachment id
                    id = Long.valueOf(file.getName());
                    Uri uri = ContentUris.withAppendedId(Attachment.CONTENT_URI, id);
                    Cursor c = resolver.query(uri, PRUNE_ATTACHMENT_PROJECTION, null, null, null);
                    try {
                        if (c.moveToNext()) {
                            // if there is no way to reload the attachment, don't delete it
                            if (c.getString(0) == null) {
                                continue;
                            }
                        }
                    } finally {
                        c.close();
                    }
                    // Clear the content URI field since we're losing the attachment
                    resolver.update(uri, PRUNE_ATTACHMENT_CV, null, null);
                } catch (NumberFormatException nfe) {
                    // ignore filename != number error, and just delete it anyway
                }
                // This file can be safely deleted
                if (!file.delete()) {
                    file.deleteOnExit();
                }
            }
        }
    }

    /**
     * Attempt to send any messages that are sitting in the Outbox.
     * @param account
     * @param listener
     */
    public void sendPendingMessages(final EmailContent.Account account, final long sentFolderId,
            MessagingListener listener) {
        put("sendPendingMessages", listener, new Runnable() {
            public void run() {
                sendPendingMessagesSynchronous(account, sentFolderId);
            }
        });
    }

    /**
     * Attempt to send any messages that are sitting in the Outbox.
     *
     * @param account
     * @param listener
     */
    public void sendPendingMessagesSynchronous(final EmailContent.Account account,
            long sentFolderId) {
        // 1.  Loop through all messages in the account's outbox
        long outboxId = Mailbox.findMailboxOfType(mContext, account.mId, Mailbox.TYPE_OUTBOX);
        if (outboxId == Mailbox.NO_MAILBOX) {
            return;
        }
        ContentResolver resolver = mContext.getContentResolver();
        Cursor c = resolver.query(EmailContent.Message.CONTENT_URI,
                EmailContent.Message.ID_COLUMN_PROJECTION,
                EmailContent.Message.MAILBOX_KEY + "=?", new String[] { Long.toString(outboxId) },
                null);
        try {
            // 2.  exit early
            if (c.getCount() <= 0) {
                return;
            }
            // 3. do one-time setup of the Sender & other stuff
            mListeners.sendPendingMessagesStarted(account.mId, -1);

            Sender sender = Sender.getInstance(mContext, account.getSenderUri(mContext));
            Store remoteStore = Store.getInstance(account.getStoreUri(mContext), mContext, null);
            boolean requireMoveMessageToSentFolder = remoteStore.requireCopyMessageToSentFolder();
            ContentValues moveToSentValues = null;
            if (requireMoveMessageToSentFolder) {
                moveToSentValues = new ContentValues();
                moveToSentValues.put(MessageColumns.MAILBOX_KEY, sentFolderId);
            }

            // 4.  loop through the available messages and send them
            while (c.moveToNext()) {
                long messageId = -1;
                try {
                    messageId = c.getLong(0);
                    mListeners.sendPendingMessagesStarted(account.mId, messageId);
                    sender.sendMessage(messageId);
                } catch (MessagingException me) {
                    // report error for this message, but keep trying others
                    mListeners.sendPendingMessagesFailed(account.mId, messageId, me);
                    continue;
                }
                // 5. move to sent, or delete
                Uri syncedUri =
                    ContentUris.withAppendedId(EmailContent.Message.SYNCED_CONTENT_URI, messageId);
                if (requireMoveMessageToSentFolder) {
                    resolver.update(syncedUri, moveToSentValues, null, null);
                } else {
                    AttachmentProvider.deleteAllAttachmentFiles(mContext, account.mId, messageId);
                    Uri uri =
                        ContentUris.withAppendedId(EmailContent.Message.CONTENT_URI, messageId);
                    resolver.delete(uri, null, null);
                    resolver.delete(syncedUri, null, null);
                }
            }
            // 6. report completion/success
            mListeners.sendPendingMessagesCompleted(account.mId);

        } catch (MessagingException me) {
            mListeners.sendPendingMessagesFailed(account.mId, -1, me);
        } finally {
            c.close();
        }
    }

    /**
     * Checks mail for one or multiple accounts. If account is null all accounts
     * are checked.  This entry point is for use by the mail checking service only, because it
     * gives slightly different callbacks (so the service doesn't get confused by callbacks
     * triggered by/for the foreground UI.
     *
     * TODO clean up the execution model which is unnecessarily threaded due to legacy code
     *
     * @param context
     * @param accountId the account to check
     * @param listener
     */
    public void checkMail(final long accountId, final long tag, final MessagingListener listener) {
        mListeners.checkMailStarted(mContext, accountId, tag);

        // This puts the command on the queue (not synchronous)
        listFolders(accountId, null);

        // Put this on the queue as well so it follows listFolders
        put("checkMail", listener, new Runnable() {
            public void run() {
                // send any pending outbound messages.  note, there is a slight race condition
                // here if we somehow don't have a sent folder, but this should never happen
                // because the call to sendMessage() would have built one previously.
                long inboxId = -1;
                EmailContent.Account account =
                    EmailContent.Account.restoreAccountWithId(mContext, accountId);
                if (account != null) {
                    long sentboxId = Mailbox.findMailboxOfType(mContext, accountId,
                            Mailbox.TYPE_SENT);
                    if (sentboxId != Mailbox.NO_MAILBOX) {
                        sendPendingMessagesSynchronous(account, sentboxId);
                    }
                    // find mailbox # for inbox and sync it.
                    // TODO we already know this in Controller, can we pass it in?
                    inboxId = Mailbox.findMailboxOfType(mContext, accountId, Mailbox.TYPE_INBOX);
                    if (inboxId != Mailbox.NO_MAILBOX) {
                        EmailContent.Mailbox mailbox =
                            EmailContent.Mailbox.restoreMailboxWithId(mContext, inboxId);
                        if (mailbox != null) {
                            synchronizeMailboxSynchronous(account, mailbox);
                        }
                    }
                }
                mListeners.checkMailFinished(mContext, accountId, inboxId, tag);
            }
        });
    }

    private static class Command {
        public Runnable runnable;

        public MessagingListener listener;

        public String description;

        @Override
        public String toString() {
            return description;
        }
    }
}
