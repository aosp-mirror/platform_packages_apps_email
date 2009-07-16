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

import com.android.email.mail.Address;
import com.android.email.mail.FetchProfile;
import com.android.email.mail.Flag;
import com.android.email.mail.Folder;
import com.android.email.mail.Message;
import com.android.email.mail.MessageRetrievalListener;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Part;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.email.mail.StoreSynchronizer;
import com.android.email.mail.Folder.FolderType;
import com.android.email.mail.Folder.OpenMode;
import com.android.email.mail.internet.MimeMessage;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.mail.store.LocalStore;
import com.android.email.mail.store.LocalStore.LocalFolder;
import com.android.email.mail.store.LocalStore.LocalMessage;
import com.android.email.mail.store.LocalStore.PendingCommand;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.MessageColumns;
import com.android.email.provider.EmailContent.SyncColumns;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.util.Config;
import android.util.Log;

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

    private static final String PENDING_COMMAND_TRASH =
        "com.android.email.MessagingController.trash";
    private static final String PENDING_COMMAND_MARK_READ =
        "com.android.email.MessagingController.markRead";
    private static final String PENDING_COMMAND_APPEND =
        "com.android.email.MessagingController.append";

    private static MessagingController inst = null;
    private BlockingQueue<Command> mCommands = new LinkedBlockingQueue<Command>();
    private Thread mThread;

    /**
     * All access to mListeners *must* be synchronized
     */
    private GroupMessagingListener mListeners = new GroupMessagingListener();
    private boolean mBusy;
    private Context mContext;

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
        if (inst == null) {
            inst = new MessagingController(_context);
        }
        return inst;
    }

    /**
     * Inject a mock controller.  Used only for testing.  Affects future calls to getInstance().
     */
    public static void injectMockController(MessagingController mockController) {
        inst = mockController;
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

        private static final String[] PROJECTION = new String[] {
            EmailContent.RECORD_ID,
            MailboxColumns.DISPLAY_NAME, MailboxColumns.ACCOUNT_KEY,
        };
        
        long mId;
        String mDisplayName;
        long mAccountKey;
        
        public LocalMailboxInfo(Cursor c) {
            mId = c.getLong(COLUMN_ID);
            mDisplayName = c.getString(COLUMN_DISPLAY_NAME);
            mAccountKey = c.getLong(COLUMN_ACCOUNT_KEY);
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
    public void listFolders(final EmailContent.Account account, MessagingListener listener) {
        mListeners.listFoldersStarted(account);
        put("listFolders", listener, new Runnable() {
            public void run() {
                Cursor localFolderCursor = null;
                try {
                    // Step 1:  Get remote folders, make a list, and add any local folders
                    // that don't already exist.

                    Store store = Store.getInstance(account.getStoreUri(mContext), mContext, null);

                    Folder[] remoteFolders = store.getPersonalNamespaces();
                    updateAccountFolderNames(account, remoteFolders);

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
                            Uri uri = ContentUris.withAppendedId(
                                    EmailContent.Mailbox.CONTENT_URI, localInfo.mId);
                            mContext.getContentResolver().delete(uri, null, null);
                        }

                        // Now do the adds
                        remoteFolderNames.removeAll(localFolderNames);
                        for (String remoteNameToAdd : remoteFolderNames) {
                            EmailContent.Mailbox box = new EmailContent.Mailbox();
                            box.mDisplayName = remoteNameToAdd;
                            // box.mServerId;
                            // box.mParentServerId;
                            box.mAccountKey = account.mId;
                            box.mType = inferMailboxTypeFromName(account, remoteNameToAdd);
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
                    mListeners.listFoldersFinished(account);
                } catch (Exception e) {
                    mListeners.listFoldersFailed(account, "");
                } finally {
                    if (localFolderCursor != null) {
                        localFolderCursor.close();
                    }
                }
            }
        });
    }

    /**
     * Temporarily:  Infer mailbox type from mailbox name.  This should probably be
     * mutated into something that the stores can provide directly, instead of the two-step
     * where we scan and report.
     */
    public int inferMailboxTypeFromName(EmailContent.Account account, String mailboxName) {
        if (mailboxName == null || mailboxName.length() == 0) {
            return EmailContent.Mailbox.TYPE_MAIL;
        }
        if (mailboxName.equals(Email.INBOX)) {
            return EmailContent.Mailbox.TYPE_INBOX;
        }
        if (mailboxName.equals(account.getTrashFolderName(mContext))) {
            return EmailContent.Mailbox.TYPE_TRASH;
        }
        if (mailboxName.equals(account.getOutboxFolderName(mContext))) {
            return EmailContent.Mailbox.TYPE_OUTBOX;
        }
        if (mailboxName.equals(account.getDraftsFolderName(mContext))) {
            return EmailContent.Mailbox.TYPE_DRAFTS;
        }
        if (mailboxName.equals(account.getSentFolderName(mContext))) {
            return EmailContent.Mailbox.TYPE_SENT;
        }

        return EmailContent.Mailbox.TYPE_MAIL;
    }

    /**
     * Asks the store for a list of server-specific folder names and, if provided, updates
     * the account record for future getFolder() operations.
     *
     * NOTE:  Inbox is not queried, because we require it to be INBOX, and outbox is not
     * queried, because outbox is local-only.
     *
     * TODO: Rewrite this to use simple folder tagging and none of this account nonsense
     */
    /* package */ void updateAccountFolderNames(EmailContent.Account account,
            Folder[] remoteFolders) {
        String trash = null;
        String sent = null;
        String drafts = null;

        for (Folder folder : remoteFolders) {
            Folder.FolderRole role = folder.getRole();
            if (role == Folder.FolderRole.TRASH) {
                trash = folder.getName();
            } else if (role == Folder.FolderRole.SENT) {
                sent = folder.getName();
            } else if (role == Folder.FolderRole.DRAFTS) {
                drafts = folder.getName();
            }
        }
/*
        // Do not update when null (defaults are already in place)
        boolean commit = false;
        if (trash != null && !trash.equals(account.getTrashFolderName(mContext))) {
            account.setTrashFolderName(trash);
            commit = true;
        }
        if (sent != null && !sent.equals(account.getSentFolderName(mContext))) {
            account.setSentFolderName(sent);
            commit = true;
        }
        if (drafts != null && !drafts.equals(account.getDraftsFolderName(mContext))) {
            account.setDraftsFolderName(drafts);
            commit = true;
        }
        if (commit) {
            account.saveOrUpdate(mContext);
        }
*/
    }

    /**
     * List the local message store for the given folder. This work is done
     * synchronously.
     *
     * @param account
     * @param folder
     * @param listener
     * @throws MessagingException
     */
/*
    public void listLocalMessages(final EmailContent.Account account, final String folder,
            MessagingListener listener) {
        synchronized (mListeners) {
            for (MessagingListener l : mListeners) {
                l.listLocalMessagesStarted(account, folder);
            }
        }

        try {
            Store localStore = Store.getInstance(account.getLocalStoreUri(mContext), mContext,
                    null);
            Folder localFolder = localStore.getFolder(folder);
            localFolder.open(OpenMode.READ_WRITE, null);
            Message[] localMessages = localFolder.getMessages(null);
            ArrayList<Message> messages = new ArrayList<Message>();
            for (Message message : localMessages) {
                if (!message.isSet(Flag.DELETED)) {
                    messages.add(message);
                }
            }
            synchronized (mListeners) {
                for (MessagingListener l : mListeners) {
                    l.listLocalMessages(account, folder, messages.toArray(new Message[0]));
                }
                for (MessagingListener l : mListeners) {
                    l.listLocalMessagesFinished(account, folder);
                }
            }
        }
        catch (Exception e) {
            synchronized (mListeners) {
                for (MessagingListener l : mListeners) {
                    l.listLocalMessagesFailed(account, folder, e.getMessage());
                }
            }
        }
    }
*/

    /**
     * Increase the window size for a given mailbox, and load more from server.
     */
    public void loadMoreMessages(EmailContent.Account account, EmailContent.Mailbox folder,
            MessagingListener listener) {

        // TODO redo implementation
/*
        try {
            Store.StoreInfo info = Store.StoreInfo.getStoreInfo(account.getStoreUri(mContext),
                    mContext);
            LocalStore localStore = (LocalStore) Store.getInstance(
                    account.getLocalStoreUri(mContext), mContext, null);
            LocalFolder localFolder = (LocalFolder) localStore.getFolder(folder);
            int oldLimit = localFolder.getVisibleLimit();
            if (oldLimit <= 0) {
                oldLimit = info.mVisibleLimitDefault;
            }
            localFolder.setVisibleLimit(oldLimit + info.mVisibleLimitIncrement);
            synchronizeMailbox(account, folder, listener);
        }
        catch (MessagingException me) {
            throw new RuntimeException("Unable to set visible limit on folder", me);
        }
*/
    }

    public void resetVisibleLimits(EmailContent.Account account) {
        try {
            Store.StoreInfo info = Store.StoreInfo.getStoreInfo(account.getStoreUri(mContext),
                    mContext);
            if (info != null) {
                LocalStore localStore = (LocalStore) Store.getInstance(
                        account.getLocalStoreUri(mContext), mContext, null);
                localStore.resetVisibleLimits(info.mVisibleLimitDefault);
            }
        }
        catch (MessagingException e) {
            Log.e(Email.LOG_TAG, "Unable to reset visible limits", e);
        }
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
        mListeners.synchronizeMailboxStarted(account, folder);
        put("synchronizeMailbox", listener, new Runnable() {
            public void run() {
                synchronizeMailboxSynchronous(account, folder);
            }
        });
    }

    /**
     * Start foreground synchronization of the specified folder. This is called by
     * synchronizeMailbox or checkMail.
     * @param account
     * @param folder
     * @param listener
     */
    private void synchronizeMailboxSynchronous(final EmailContent.Account account,
            final EmailContent.Mailbox folder) {
        mListeners.synchronizeMailboxStarted(account, folder);
        try {
            processPendingCommandsSynchronous(account);

            StoreSynchronizer.SyncResults results;

            // Select generic sync or store-specific sync
            final LocalStore localStore =
                (LocalStore) Store.getInstance(account.getLocalStoreUri(mContext), mContext, null);
            Store remoteStore = Store.getInstance(account.getStoreUri(mContext), mContext,
                    localStore.getPersistentCallbacks());
            StoreSynchronizer customSync = remoteStore.getMessageSynchronizer();
            if (customSync == null) {
                results = synchronizeMailboxGeneric(account, folder);
            } else {
                results = customSync.SynchronizeMessagesSynchronous(
                        account, folder, mListeners, mContext);
            }
            mListeners.synchronizeMailboxFinished(account, 
                                                  folder,
                                                  results.mTotalMessages, 
                                                  results.mNewMessages);
        } catch (MessagingException e) {
            if (Email.LOGD) {
                Log.v(Email.LOG_TAG, "synchronizeMailbox", e);
            }
            mListeners.synchronizeMailboxFailed(account, folder, e);
        }
    }
    
    // TODO move all this to top
/*
        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_DISPLAY_NAME_COLUMN = 1;
        public static final int CONTENT_TIMESTAMP_COLUMN = 2;
        public static final int CONTENT_SUBJECT_COLUMN = 3;
        public static final int CONTENT_PREVIEW_COLUMN = 4;
        public static final int CONTENT_FLAG_READ_COLUMN = 5;
        public static final int CONTENT_FLAG_LOADED_COLUMN = 6;
        public static final int CONTENT_FLAG_FAVORITE_COLUMN = 7;
        public static final int CONTENT_FLAG_ATTACHMENT_COLUMN = 8;
        public static final int CONTENT_FLAGS_COLUMN = 9;
        public static final int CONTENT_TEXT_INFO_COLUMN = 10;
        public static final int CONTENT_HTML_INFO_COLUMN = 11;
        public static final int CONTENT_BODY_ID_COLUMN = 12;
        public static final int CONTENT_SERVER_ID_COLUMN = 13;
        public static final int CONTENT_CLIENT_ID_COLUMN = 14;
        public static final int CONTENT_MESSAGE_ID_COLUMN = 15;
        public static final int CONTENT_THREAD_ID_COLUMN = 16;
        public static final int CONTENT_MAILBOX_KEY_COLUMN = 17;
        public static final int CONTENT_ACCOUNT_KEY_COLUMN = 18;
        public static final int CONTENT_REFERENCE_KEY_COLUMN = 19;
        public static final int CONTENT_SENDER_LIST_COLUMN = 20;
        public static final int CONTENT_FROM_LIST_COLUMN = 21;
        public static final int CONTENT_TO_LIST_COLUMN = 22;
        public static final int CONTENT_CC_LIST_COLUMN = 23;
        public static final int CONTENT_BCC_LIST_COLUMN = 24;
        public static final int CONTENT_REPLY_TO_COLUMN = 25;
        public static final int CONTENT_SERVER_VERSION_COLUMN = 26;
        public static final String[] CONTENT_PROJECTION = new String[] { 
            RECORD_ID, MessageColumns.DISPLAY_NAME, MessageColumns.TIMESTAMP, 
            MessageColumns.SUBJECT, MessageColumns.PREVIEW, MessageColumns.FLAG_READ,
            MessageColumns.FLAG_LOADED, MessageColumns.FLAG_FAVORITE,
            MessageColumns.FLAG_ATTACHMENT, MessageColumns.FLAGS, MessageColumns.TEXT_INFO,
            MessageColumns.HTML_INFO, MessageColumns.BODY_ID, SyncColumns.SERVER_ID,
            MessageColumns.CLIENT_ID, MessageColumns.MESSAGE_ID, MessageColumns.THREAD_ID,
            MessageColumns.MAILBOX_KEY, MessageColumns.ACCOUNT_KEY, MessageColumns.REFERENCE_KEY,
            MessageColumns.SENDER_LIST, MessageColumns.FROM_LIST, MessageColumns.TO_LIST,
            MessageColumns.CC_LIST, MessageColumns.BCC_LIST, MessageColumns.REPLY_TO_LIST,
            SyncColumns.SERVER_VERSION
        };
*/

    /**
     * Lightweight record for the first pass of message sync, where I'm just seeing if
     * the local message requires sync.  Later (for messages that need syncing) we'll do a full
     * readout from the DB.
     */
    private static class LocalMessageInfo {
        private static final int COLUMN_ID = 0;
        private static final int COLUMN_FLAG_READ = 1;
        private static final int COLUMN_FLAG_LOADED = 2;
        private static final int COLUMN_SERVER_ID = 3;
        private static final int COLUMN_MAILBOX_KEY = 4;
        private static final int COLUMN_ACCOUNT_KEY = 5;
        private static final String[] PROJECTION = new String[] {
            EmailContent.RECORD_ID,
            MessageColumns.FLAG_READ, MessageColumns.FLAG_LOADED,
            SyncColumns.SERVER_ID, MessageColumns.MAILBOX_KEY, MessageColumns.ACCOUNT_KEY
        };
        
        int mCursorIndex;
        long mId;
        boolean mFlagRead;
        int mFlagLoaded;
        String mServerId;
        
        public LocalMessageInfo(Cursor c) {
            mCursorIndex = c.getPosition();
            mId = c.getLong(COLUMN_ID);
            mFlagRead = c.getInt(COLUMN_FLAG_READ) != 0;
            mFlagLoaded = c.getInt(COLUMN_FLAG_LOADED);
            mServerId = c.getString(COLUMN_SERVER_ID);
            // Note: mailbox key and account key not needed - they are projected for the SELECT
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
        
        // 1.  Get the message list from the local store and create an index of the uids
        
        Cursor localUidCursor = null;
        HashMap<String, LocalMessageInfo> localMessageMap = new HashMap<String, LocalMessageInfo>();
        
        try {
            localUidCursor = mContext.getContentResolver().query(
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
        if (folder.equals(account.getTrashFolderName(mContext)) ||
                folder.equals(account.getSentFolderName(mContext)) ||
                folder.equals(account.getDraftsFolderName(mContext))) {
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
        // TODO decide where to persist the visible limit (account?) until we switch UI model
        int visibleLimit = -1;  // localFolder.getVisibleLimit();
        if (visibleLimit <= 0) {
            Store.StoreInfo info = Store.StoreInfo.getStoreInfo(account.getStoreUri(mContext),
                    mContext);
            visibleLimit = info.mVisibleLimitDefault;
            // localFolder.setVisibleLimit(visibleLimit);
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
             */
            for (Message message : remoteMessages) {
                LocalMessageInfo localMessage = localMessageMap.get(message.getUid());
                if (localMessage == null) {
                    newMessageCount++;
                }
                if (localMessage == null || 
                        localMessage.mFlagLoaded != EmailContent.Message.LOADED) {
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
                        public void messageFinished(Message message, int number, int ofTotal) {
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
                                        updateMessageFields(localMessage, 
                                                message, account.mId, folder.mId);
                                        // Commit the message to the local store
                                        localMessage.saveOrUpdate(mContext);
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

                        public void messageStarted(String uid, int number, int ofTotal) {
                        }
                    });
        }

        // 9. Refresh the flags for any messages in the local store that we didn't just download.
        FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.FLAGS);
        remoteFolder.fetch(remoteMessages, fp, null);
        boolean remoteSupportsSeenFlag = false;
        for (Flag flag : remoteFolder.getPermanentFlags()) {
            if (flag == Flag.SEEN) {
                remoteSupportsSeenFlag = true;
            }
        }
        // Update the SEEN flag (if supported remotely - e.g. not for POP3)
        if (remoteSupportsSeenFlag) {
            for (Message remoteMessage : remoteMessages) {
                LocalMessageInfo localMessageInfo = localMessageMap.get(remoteMessage.getUid());
                if (localMessageInfo == null) {
                    continue;
                }
                boolean localSeen = localMessageInfo.mFlagRead;
                boolean remoteSeen = remoteMessage.isSet(Flag.SEEN);
                if (remoteSeen != localSeen) {
                    Uri uri = ContentUris.withAppendedId(
                            EmailContent.Message.CONTENT_URI, localMessageInfo.mId);
                    ContentValues updateValues = new ContentValues();
                    updateValues.put(EmailContent.Message.FLAG_READ, remoteSeen ? 1 : 0);
                    mContext.getContentResolver().update(uri, updateValues, null, null);
                }
            }
        }

        // 10. Compute and store the unread message count.

        int remoteUnreadMessageCount = remoteFolder.getUnreadMessageCount();
        if (remoteUnreadMessageCount == -1) {
            if (remoteSupportsSeenFlag) {
                /*
                 * If remote folder doesn't supported unread message count but supports
                 * seen flag, use local folder's unread message count and the size of
                 * new messages. This mode is not used for POP3, or IMAP.
                 */
                
                remoteUnreadMessageCount = folder.mUnreadCount + newMessages.size();
            } else {
                /*
                 * If remote folder doesn't supported unread message count and doesn't
                 * support seen flag, use localUnreadCount and newMessageCount which
                 * don't rely on remote SEEN flag.  This mode is used by POP3.
                 */
                remoteUnreadMessageCount = localUnreadCount + newMessageCount;
            }
        } else {
            /*
             * If remote folder supports unread message count, use remoteUnreadMessageCount.
             * This mode is used by IMAP.
             */
         }
        Uri uri = ContentUris.withAppendedId(EmailContent.Mailbox.CONTENT_URI, folder.mId);
        ContentValues updateValues = new ContentValues();
        updateValues.put(EmailContent.Mailbox.UNREAD_COUNT, remoteUnreadMessageCount);
        mContext.getContentResolver().update(uri, updateValues, null, null);

        // 11. Remove any messages that are in the local store but no longer on the remote store.

        HashSet<String> localUidsToDelete = new HashSet<String>(localMessageMap.keySet());
        localUidsToDelete.removeAll(remoteUidMap.keySet());
        for (String uidToDelete : localUidsToDelete) {
            LocalMessageInfo infoToDelete = localMessageMap.get(uidToDelete);
            
            Uri uriToDelete = ContentUris.withAppendedId(
                    EmailContent.Message.CONTENT_URI, infoToDelete.mId);
            mContext.getContentResolver().delete(uriToDelete, null, null);
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

        fp = new FetchProfile();
        fp.add(FetchProfile.Item.BODY);
        remoteFolder.fetch(smallMessages.toArray(new Message[smallMessages.size()]), fp,
                new MessageRetrievalListener() {
                    public void messageFinished(Message message, int number, int ofTotal) {
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
                                    localMessage = EmailContent.getContent(
                                            c, EmailContent.Message.class);
                                }
                            } finally {
                                if (c != null) {
                                    c.close();
                                }
                            }

                            if (localMessage != null) {
                                EmailContent.Body body = EmailContent.Body.restoreBodyWithId(
                                        mContext, localMessage.mId);
                                if (body == null) {
                                    body = new EmailContent.Body();
                                }
                                try {
                                    // Copy the fields that are available into the message
                                    updateMessageFields(localMessage,
                                            message, account.mId, folder.mId);
                                    updateBodyFields(body, localMessage, message);
                                    // TODO should updateMessageFields do this for us?
                                    // localMessage.mFlagLoaded = EmailContent.Message.LOADED;
                                    // Commit the message to the local store
                                    localMessage.saveOrUpdate(mContext);
                                    body.saveOrUpdate(mContext);
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
    
                    public void messageStarted(String uid, int number, int ofTotal) {
                    }
        });

        // 14. Download large messages

        // 15. Clean up and report results

        remoteFolder.close(false);
        // TODO - more

        // Original sync code.  Using for reference, will delete when done.
        if (false) {
        /*
         * Grab the content of the small messages first. This is going to
         * be very fast and at very worst will be a single up of a few bytes and a single
         * download of 625k.
         */
        fp = new FetchProfile();
        fp.add(FetchProfile.Item.BODY);
        remoteFolder.fetch(smallMessages.toArray(new Message[smallMessages.size()]),
                fp, new MessageRetrievalListener() {
            public void messageFinished(Message message, int number, int ofTotal) {
//                try {
//                    // Store the updated message locally
//                    localFolder.appendMessages(new Message[] {
//                        message
//                    });
//
//                    Message localMessage = localFolder.getMessage(message.getUid());
//
//                    // Set a flag indicating this message has now be fully downloaded
//                    localMessage.setFlag(Flag.X_DOWNLOADED_FULL, true);
//
//                    // Update the listener with what we've found
//                    synchronized (mListeners) {
//                        for (MessagingListener l : mListeners) {
//                            l.synchronizeMailboxNewMessage(
//                                    account,
//                                    folder,
//                                    localMessage);
//                        }
//                    }
//                }
//                catch (MessagingException me) {
//
//                }
            }

            public void messageStarted(String uid, int number, int ofTotal) {
            }
        });

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

        return new StoreSynchronizer.SyncResults(0, 0);

    }
    
    /**
     * Copy field-by-field from a "store" message to a "provider" message
     * @param message The message we've just downloaded
     * @param localMessage The message we'd like to write into the DB
     * @result true if dirty (changes were made)
     */
    /* package */ boolean updateMessageFields(EmailContent.Message localMessage, Message message,
            long accountId, long mailboxId) throws MessagingException {

        Address[] from = message.getFrom();
        Address[] to = message.getRecipients(Message.RecipientType.TO);
        Address[] cc = message.getRecipients(Message.RecipientType.CC);
        Address[] bcc = message.getRecipients(Message.RecipientType.BCC);
        Address[] replyTo = message.getReplyTo();
        String subject = message.getSubject();
        Date sentDate = message.getSentDate();
        
        if (from != null && from.length > 0) {
            localMessage.mDisplayName = from[0].toFriendly();
        }
        if (sentDate != null) {
            localMessage.mTimeStamp = sentDate.getTime();
        }
        if (subject != null) {
            localMessage.mSubject = subject;
        }
//        public String mPreview;
//        public boolean mFlagRead = false;

        // Keep the message in the "unloaded" state until it has (at least) a display name.
        // This prevents early flickering of empty messages in POP download.
        if (localMessage.mFlagLoaded != EmailContent.Message.LOADED) {
            if (localMessage.mDisplayName == null || "".equals(localMessage.mDisplayName)) {
                localMessage.mFlagLoaded = EmailContent.Message.NOT_LOADED;
            } else {
                localMessage.mFlagLoaded = EmailContent.Message.PARTIALLY_LOADED;
            }
        }
//        public boolean mFlagFavorite = false;
//        public boolean mFlagAttachment = false;
//        public int mFlags = 0;
//
//        public String mTextInfo;
//        public String mHtmlInfo;
//
        localMessage.mServerId = message.getUid();
//        public int mServerIntId;
//        public String mClientId;
//        public String mMessageId;
//        public String mThreadId;
//
//        public long mBodyKey;
        localMessage.mMailboxKey = mailboxId;
        localMessage.mAccountKey = accountId;
//        public long mReferenceKey;
//
//        public String mSender;
        if (from != null && from.length > 0) {
            localMessage.mFrom = Address.pack(from);
        }
            
        if (to != null && to.length > 0) {
            localMessage.mTo = Address.pack(to);
        }
        if (cc != null && cc.length > 0) {
            localMessage.mCc = Address.pack(cc);
        }
        if (bcc != null && bcc.length > 0) {
            localMessage.mBcc = Address.pack(bcc);
        }
        if (replyTo != null && replyTo.length > 0) {
            localMessage.mReplyTo = Address.pack(replyTo);
        }
//        
//        public String mServerVersion;
//
//        public String mText;
//        public String mHtml;
//
//        // Can be used while building messages, but is NOT saved by the Provider
//        transient public ArrayList<Attachment> mAttachments = null;
//
//        public static final int UNREAD = 0;
//        public static final int READ = 1;
//        public static final int DELETED = 2;
//
//        public static final int NOT_LOADED = 0;
//        public static final int LOADED = 1;
//        public static final int PARTIALLY_LOADED = 2;

        return true;
    }
    
    /**
     * Copy body text (plain and/or HTML) from MimeMessage to provider Message
     */
    /* package */ boolean updateBodyFields(EmailContent.Body body,
            EmailContent.Message localMessage, Message message) throws MessagingException {

        body.mMessageKey = localMessage.mId;

        Part htmlPart = MimeUtility.findFirstPartByMimeType(message, "text/html");
        Part textPart = MimeUtility.findFirstPartByMimeType(message, "text/plain");

        if (textPart != null) {
            String text = MimeUtility.getTextFromPart(textPart);
            if (text != null) {
                localMessage.mTextInfo = "X;X;8;" + text.length()*2;
                body.mTextContent = text;
            }
        }
        if (htmlPart != null) {
            String html = MimeUtility.getTextFromPart(htmlPart);
            if (html != null) {
                localMessage.mHtmlInfo = "X;X;8;" + html.length()*2;
                body.mHtmlContent = html;
            }
        }
        return true;
    }

    private void queuePendingCommand(EmailContent.Account account, PendingCommand command) {
        try {
            LocalStore localStore = (LocalStore) Store.getInstance(
                    account.getLocalStoreUri(mContext), mContext, null);
            localStore.addPendingCommand(command);
        }
        catch (Exception e) {
            throw new RuntimeException("Unable to enqueue pending command", e);
        }
    }

    private void processPendingCommands(final EmailContent.Account account) {
        put("processPendingCommands", null, new Runnable() {
            public void run() {
                try {
                    processPendingCommandsSynchronous(account);
                }
                catch (MessagingException me) {
                    if (Email.LOGD) {
                        Log.v(Email.LOG_TAG, "processPendingCommands", me);
                    }
                    /*
                     * Ignore any exceptions from the commands. Commands will be processed
                     * on the next round.
                     */
                }
            }
        });
    }

    private void processPendingCommandsSynchronous(EmailContent.Account account)
            throws MessagingException {
        LocalStore localStore = (LocalStore) Store.getInstance(
                account.getLocalStoreUri(mContext), mContext, null);
        ArrayList<PendingCommand> commands = localStore.getPendingCommands();
        for (PendingCommand command : commands) {
            /*
             * We specifically do not catch any exceptions here. If a command fails it is
             * most likely due to a server or IO error and it must be retried before any
             * other command processes. This maintains the order of the commands.
             */
            if (PENDING_COMMAND_APPEND.equals(command.command)) {
                processPendingAppend(command, account);
            }
            else if (PENDING_COMMAND_MARK_READ.equals(command.command)) {
                processPendingMarkRead(command, account);
            }
            else if (PENDING_COMMAND_TRASH.equals(command.command)) {
                processPendingTrash(command, account);
            }
            localStore.removePendingCommand(command);
        }
    }

    /**
     * Process a pending append message command. This command uploads a local message to the
     * server, first checking to be sure that the server message is not newer than
     * the local message. Once the local message is successfully processed it is deleted so
     * that the server message will be synchronized down without an additional copy being
     * created.
     * TODO update the local message UID instead of deleteing it
     *
     * @param command arguments = (String folder, String uid)
     * @param account
     * @throws MessagingException
     */
    private void processPendingAppend(PendingCommand command, EmailContent.Account account)
            throws MessagingException {
        String folder = command.arguments[0];
        String uid = command.arguments[1];

        LocalStore localStore = (LocalStore) Store.getInstance(
                account.getLocalStoreUri(mContext), mContext, null);
        LocalFolder localFolder = (LocalFolder) localStore.getFolder(folder);
        LocalMessage localMessage = (LocalMessage) localFolder.getMessage(uid);

        if (localMessage == null) {
            return;
        }

        Store remoteStore = Store.getInstance(account.getStoreUri(mContext), mContext,
                localStore.getPersistentCallbacks());
        Folder remoteFolder = remoteStore.getFolder(folder);
        if (!remoteFolder.exists()) {
            if (!remoteFolder.create(FolderType.HOLDS_MESSAGES)) {
                return;
            }
        }
        remoteFolder.open(OpenMode.READ_WRITE, localFolder.getPersistentCallbacks());
        if (remoteFolder.getMode() != OpenMode.READ_WRITE) {
            return;
        }

        Message remoteMessage = null;
        if (!localMessage.getUid().startsWith("Local")
                && !localMessage.getUid().contains("-")) {
            remoteMessage = remoteFolder.getMessage(localMessage.getUid());
        }

        if (remoteMessage == null) {
            /*
             * If the message does not exist remotely we just upload it and then
             * update our local copy with the new uid.
             */
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.BODY);
            localFolder.fetch(new Message[] { localMessage }, fp, null);
            String oldUid = localMessage.getUid();
            remoteFolder.appendMessages(new Message[] { localMessage });
            localFolder.changeUid(localMessage);
            mListeners.messageUidChanged(account, folder, oldUid, localMessage.getUid());
        }
        else {
            /*
             * If the remote message exists we need to determine which copy to keep.
             */
            /*
             * See if the remote message is newer than ours.
             */
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            remoteFolder.fetch(new Message[] { remoteMessage }, fp, null);
            Date localDate = localMessage.getInternalDate();
            Date remoteDate = remoteMessage.getInternalDate();
            if (remoteDate.compareTo(localDate) > 0) {
                /*
                 * If the remote message is newer than ours we'll just
                 * delete ours and move on. A sync will get the server message
                 * if we need to be able to see it.
                 */
                localMessage.setFlag(Flag.DELETED, true);
            }
            else {
                /*
                 * Otherwise we'll upload our message and then delete the remote message.
                 */
                fp.clear();
                fp = new FetchProfile();
                fp.add(FetchProfile.Item.BODY);
                localFolder.fetch(new Message[] { localMessage }, fp, null);
                String oldUid = localMessage.getUid();
                remoteFolder.appendMessages(new Message[] { localMessage });
                localFolder.changeUid(localMessage);
                mListeners.messageUidChanged(account, folder, oldUid, localMessage.getUid());
                remoteMessage.setFlag(Flag.DELETED, true);
            }
        }
    }

    /**
     * Process a pending trash message command.
     *
     * @param command arguments = (String folder, String uid)
     * @param account
     * @throws MessagingException
     */
    private void processPendingTrash(PendingCommand command, final EmailContent.Account account)
            throws MessagingException {
        String folder = command.arguments[0];
        String uid = command.arguments[1];

        final LocalStore localStore = (LocalStore) Store.getInstance(
                account.getLocalStoreUri(mContext), mContext, null);
        LocalFolder localFolder = (LocalFolder) localStore.getFolder(folder);

        Store remoteStore = Store.getInstance(account.getStoreUri(mContext), mContext,
                localStore.getPersistentCallbacks());
        Folder remoteFolder = remoteStore.getFolder(folder);
        if (!remoteFolder.exists()) {
            return;
        }
        remoteFolder.open(OpenMode.READ_WRITE, localFolder.getPersistentCallbacks());
        if (remoteFolder.getMode() != OpenMode.READ_WRITE) {
            remoteFolder.close(false);
            return;
        }

        Message remoteMessage = null;
        if (!uid.startsWith("Local")) {
            remoteMessage = remoteFolder.getMessage(uid);
        }
        if (remoteMessage == null) {
            remoteFolder.close(false);
            return;
        }

        Folder remoteTrashFolder = remoteStore.getFolder(account.getTrashFolderName(mContext));
        /*
         * Attempt to copy the remote message to the remote trash folder.
         */
        if (!remoteTrashFolder.exists()) {
            /*
             * If the remote trash folder doesn't exist we try to create it.
             */
            remoteTrashFolder.create(FolderType.HOLDS_MESSAGES);
        }

        if (remoteTrashFolder.exists()) {
            /*
             * Because remoteTrashFolder may be new, we need to explicitly open it
             * and pass in the persistence callbacks.
             */
            final LocalFolder localTrashFolder =
                (LocalFolder) localStore.getFolder(account.getTrashFolderName(mContext));
            remoteTrashFolder.open(OpenMode.READ_WRITE, localTrashFolder.getPersistentCallbacks());
            if (remoteTrashFolder.getMode() != OpenMode.READ_WRITE) {
                remoteFolder.close(false);
                remoteTrashFolder.close(false);
                return;
            }

            remoteFolder.copyMessages(new Message[] { remoteMessage }, remoteTrashFolder,
                    new Folder.MessageUpdateCallbacks() {
                public void onMessageUidChange(Message message, String newUid)
                        throws MessagingException {
                    // update the UID in the local trash folder, because some stores will
                    // have to change it when copying to remoteTrashFolder
                    LocalMessage localMessage =
                        (LocalMessage) localTrashFolder.getMessage(message.getUid());
                    if(localMessage != null) {
                        localMessage.setUid(newUid);
                        localTrashFolder.updateMessage(localMessage);
                    }
                }

                /**
                 * This will be called if the deleted message doesn't exist and can't be
                 * deleted (e.g. it was already deleted from the server.)  In this case,
                 * attempt to delete the local copy as well.
                 */
                public void onMessageNotFound(Message message) throws MessagingException {
                    LocalMessage localMessage =
                        (LocalMessage) localTrashFolder.getMessage(message.getUid());
                    if (localMessage != null) {
                        localMessage.setFlag(Flag.DELETED, true);
                    }
                }

            }
            );
            remoteTrashFolder.close(false);
        }

        remoteMessage.setFlag(Flag.DELETED, true);
        remoteFolder.expunge();
        remoteFolder.close(false);
    }

    /**
     * Processes a pending mark read or unread command.
     *
     * @param command arguments = (String folder, String uid, boolean read)
     * @param account
     */
    private void processPendingMarkRead(PendingCommand command, EmailContent.Account account)
            throws MessagingException {
        String folder = command.arguments[0];
        String uid = command.arguments[1];
        boolean read = Boolean.parseBoolean(command.arguments[2]);

        LocalStore localStore = (LocalStore) Store.getInstance(
                account.getLocalStoreUri(mContext), mContext, null);
        LocalFolder localFolder = (LocalFolder) localStore.getFolder(folder);

        Store remoteStore = Store.getInstance(account.getStoreUri(mContext), mContext,
                localStore.getPersistentCallbacks());
        Folder remoteFolder = remoteStore.getFolder(folder);
        if (!remoteFolder.exists()) {
            return;
        }
        remoteFolder.open(OpenMode.READ_WRITE, localFolder.getPersistentCallbacks());
        if (remoteFolder.getMode() != OpenMode.READ_WRITE) {
            return;
        }
        Message remoteMessage = null;
        if (!uid.startsWith("Local")
                && !uid.contains("-")) {
            remoteMessage = remoteFolder.getMessage(uid);
        }
        if (remoteMessage == null) {
            return;
        }
        remoteMessage.setFlag(Flag.SEEN, read);
    }

    /**
     * Mark the message with the given account, folder and uid either Seen or not Seen.
     * @param account
     * @param folder
     * @param uid
     * @param seen
     */
    public void markMessageRead(
            final EmailContent.Account account,
            final String folder,
            final String uid,
            final boolean seen) {
        try {
            Store localStore = Store.getInstance(account.getLocalStoreUri(mContext), mContext,
                    null);
            Folder localFolder = localStore.getFolder(folder);
            localFolder.open(OpenMode.READ_WRITE, null);

            Message message = localFolder.getMessage(uid);
            message.setFlag(Flag.SEEN, seen);
            PendingCommand command = new PendingCommand();
            command.command = PENDING_COMMAND_MARK_READ;
            command.arguments = new String[] { folder, uid, Boolean.toString(seen) };
            queuePendingCommand(account, command);
            processPendingCommands(account);
        }
        catch (MessagingException me) {
            throw new RuntimeException(me);
        }
    }

    private void loadMessageForViewRemote(final EmailContent.Account account, final String folder,
            final String uid, MessagingListener listener) {
        put("loadMessageForViewRemote", listener, new Runnable() {
            public void run() {
                try {
                    LocalStore localStore = (LocalStore) Store.getInstance(
                            account.getLocalStoreUri(mContext), mContext, null);
                    LocalFolder localFolder = (LocalFolder) localStore.getFolder(folder);
                    localFolder.open(OpenMode.READ_WRITE, null);

                    Message message = localFolder.getMessage(uid);

                    if (message.isSet(Flag.X_DOWNLOADED_FULL)) {
                        /*
                         * If the message has been synchronized since we were called we'll
                         * just hand it back cause it's ready to go.
                         */
                        FetchProfile fp = new FetchProfile();
                        fp.add(FetchProfile.Item.ENVELOPE);
                        fp.add(FetchProfile.Item.BODY);
                        localFolder.fetch(new Message[] { message }, fp, null);

                        mListeners.loadMessageForViewBodyAvailable(account, folder, uid, message);
                        mListeners.loadMessageForViewFinished(account, folder, uid, message);
                        localFolder.close(false);
                        return;
                    }

                    /*
                     * At this point the message is not available, so we need to download it
                     * fully if possible.
                     */

                    Store remoteStore = Store.getInstance(account.getStoreUri(mContext), mContext,
                            localStore.getPersistentCallbacks());
                    Folder remoteFolder = remoteStore.getFolder(folder);
                    remoteFolder.open(OpenMode.READ_WRITE, localFolder.getPersistentCallbacks());

                    // Get the remote message and fully download it (and save into local store)

                    if (remoteStore.requireStructurePrefetch()) {
                        // For remote stores that require it, prefetch the message structure.
                        FetchProfile fp = new FetchProfile();
                        fp.add(FetchProfile.Item.STRUCTURE);
                        localFolder.fetch(new Message[] { message }, fp, null);

                        ArrayList<Part> viewables = new ArrayList<Part>();
                        ArrayList<Part> attachments = new ArrayList<Part>();
                        MimeUtility.collectParts(message, viewables, attachments);
                        fp.clear();
                        for (Part part : viewables) {
                            fp.add(part);
                        }

                        remoteFolder.fetch(new Message[] { message }, fp, null);

                        // Store the updated message locally
                        localFolder.updateMessage((LocalMessage)message);

                    } else {
                        // Most remote stores can directly obtain the message using only uid
                        Message remoteMessage = remoteFolder.getMessage(uid);
                        FetchProfile fp = new FetchProfile();
                        fp.add(FetchProfile.Item.BODY);
                        remoteFolder.fetch(new Message[] { remoteMessage }, fp, null);

                        // Store the message locally
                        localFolder.appendMessages(new Message[] { remoteMessage });
                    }

                    // Now obtain the local copy for further access & manipulation
                    message = localFolder.getMessage(uid);
                    FetchProfile fp = new FetchProfile();
                    fp.add(FetchProfile.Item.BODY);
                    localFolder.fetch(new Message[] { message }, fp, null);

                    // This is a view message request, so mark it read
                    if (!message.isSet(Flag.SEEN)) {
                        markMessageRead(account, folder, uid, true);
                    }

                    // Mark that this message is now fully synched
                    message.setFlag(Flag.X_DOWNLOADED_FULL, true);

                    mListeners.loadMessageForViewBodyAvailable(account, folder, uid, message);
                    mListeners.loadMessageForViewFinished(account, folder, uid, message);
                    remoteFolder.close(false);
                    localFolder.close(false);
                }
                catch (Exception e) {
                    mListeners.loadMessageForViewFailed(account, folder, uid, e.getMessage());
                }
            }
        });
    }

    public void loadMessageForView(final EmailContent.Account account, final String folder,
            final String uid, MessagingListener listener) {
        mListeners.loadMessageForViewStarted(account, folder, uid);
        try {
            Store localStore = Store.getInstance(account.getLocalStoreUri(mContext), mContext,
                    null);
            LocalFolder localFolder = (LocalFolder) localStore.getFolder(folder);
            localFolder.open(OpenMode.READ_WRITE, null);

            Message message = localFolder.getMessage(uid);
            mListeners.loadMessageForViewHeadersAvailable(account, folder, uid, message);
            if (!message.isSet(Flag.X_DOWNLOADED_FULL)) {
                loadMessageForViewRemote(account, folder, uid, listener);
                localFolder.close(false);
                return;
            }

            if (!message.isSet(Flag.SEEN)) {
                markMessageRead(account, folder, uid, true);
            }

            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.BODY);
            localFolder.fetch(new Message[] {
                message
            }, fp, null);

            mListeners.loadMessageForViewBodyAvailable(account, folder, uid, message);
            mListeners.loadMessageForViewFinished(account, folder, uid, message);
            localFolder.close(false);
        }
        catch (Exception e) {
            mListeners.loadMessageForViewFailed(account, folder, uid, e.getMessage());
        }
    }

    /**
     * Attempts to load the attachment specified by part from the given account and message.
     * @param account
     * @param message
     * @param part
     * @param listener
     */
    public void loadAttachment(
            final EmailContent.Account account,
            final Message message,
            final Part part,
            final Object tag,
            MessagingListener listener) {
        /*
         * Check if the attachment has already been downloaded. If it has there's no reason to
         * download it, so we just tell the listener that it's ready to go.
         */
        try {
            if (part.getBody() != null) {
                mListeners.loadAttachmentStarted(account, message, part, tag, false);
                mListeners.loadAttachmentFinished(account, message, part, tag);
                return;
            }
        }
        catch (MessagingException me) {
            /*
             * If the header isn't there the attachment isn't downloaded yet, so just continue
             * on.
             */
        }

        mListeners.loadAttachmentStarted(account, message, part, tag, true);

        put("loadAttachment", listener, new Runnable() {
            public void run() {
                try {
                    LocalStore localStore = (LocalStore) Store.getInstance(
                            account.getLocalStoreUri(mContext), mContext, null);
                    /*
                     * We clear out any attachments already cached in the entire store and then
                     * we update the passed in message to reflect that there are no cached
                     * attachments. This is in support of limiting the account to having one
                     * attachment downloaded at a time.
                     */
                    localStore.pruneCachedAttachments();
                    ArrayList<Part> viewables = new ArrayList<Part>();
                    ArrayList<Part> attachments = new ArrayList<Part>();
                    MimeUtility.collectParts(message, viewables, attachments);
                    for (Part attachment : attachments) {
                        attachment.setBody(null);
                    }
                    Store remoteStore = Store.getInstance(account.getStoreUri(mContext), mContext,
                            localStore.getPersistentCallbacks());
                    LocalFolder localFolder =
                        (LocalFolder) localStore.getFolder(message.getFolder().getName());
                    Folder remoteFolder = remoteStore.getFolder(message.getFolder().getName());
                    remoteFolder.open(OpenMode.READ_WRITE, localFolder.getPersistentCallbacks());

                    FetchProfile fp = new FetchProfile();
                    fp.add(part);
                    remoteFolder.fetch(new Message[] { message }, fp, null);
                    localFolder.updateMessage((LocalMessage)message);
                    localFolder.close(false);
                    mListeners.loadAttachmentFinished(account, message, part, tag);
                }
                catch (MessagingException me) {
                    if (Email.LOGD) {
                        Log.v(Email.LOG_TAG, "", me);
                    }
                    mListeners.loadAttachmentFailed(account, message, part, tag, me.getMessage());
                }
            }
        });
    }

    /**
     * Stores the given message in the Outbox and starts a sendPendingMessages command to
     * attempt to send the message.
     * @param account
     * @param message
     * @param listener
     */
    public void sendMessage(final EmailContent.Account account, final Message message,
            MessagingListener listener) {
        try {
            Store localStore = Store.getInstance(account.getLocalStoreUri(mContext), mContext,
                    null);
            LocalFolder localFolder =
                (LocalFolder) localStore.getFolder(account.getOutboxFolderName(mContext));
            localFolder.open(OpenMode.READ_WRITE, null);
            localFolder.appendMessages(new Message[] {
                message
            });
            Message localMessage = localFolder.getMessage(message.getUid());
            localMessage.setFlag(Flag.X_DOWNLOADED_FULL, true);
            localFolder.close(false);
            sendPendingMessages(account, null);
        }
        catch (Exception e) {
//            synchronized (mListeners) {
//                for (MessagingListener l : mListeners) {
//                    // TODO general failed
//                }
//            }
        }
    }

    /**
     * Attempt to send any messages that are sitting in the Outbox.
     * @param account
     * @param listener
     */
    public void sendPendingMessages(final EmailContent.Account account, MessagingListener listener) {
        put("sendPendingMessages", listener, new Runnable() {
            public void run() {
                sendPendingMessagesSynchronous(account);
            }
        });
    }

    /**
     * Attempt to send any messages that are sitting in the Outbox.
     * @param account
     * @param listener
     */
    public void sendPendingMessagesSynchronous(final EmailContent.Account account) {
        try {
            LocalStore localStore = (LocalStore) Store.getInstance(
                    account.getLocalStoreUri(mContext), mContext, null);
            Folder localFolder = localStore.getFolder(account.getOutboxFolderName(mContext));
            if (!localFolder.exists()) {
                return;
            }
            localFolder.open(OpenMode.READ_WRITE, null);

            Message[] localMessages = localFolder.getMessages(null);

            /*
             * The profile we will use to pull all of the content
             * for a given local message into memory for sending.
             */
            FetchProfile fp = new FetchProfile();
            fp.add(FetchProfile.Item.ENVELOPE);
            fp.add(FetchProfile.Item.BODY);

            LocalFolder localSentFolder =
                (LocalFolder) localStore.getFolder(account.getSentFolderName(mContext));

            // Determine if upload to "sent" folder is necessary
            Store remoteStore = Store.getInstance(
                    account.getStoreUri(mContext), mContext, localStore.getPersistentCallbacks());
            boolean requireCopyMessageToSentFolder = remoteStore.requireCopyMessageToSentFolder();

            Sender sender = Sender.getInstance(account.getSenderUri(mContext), mContext);
            for (Message message : localMessages) {
                try {
                    localFolder.fetch(new Message[] { message }, fp, null);
                    try {
                        // Send message using Sender
                        message.setFlag(Flag.X_SEND_IN_PROGRESS, true);
                        sender.sendMessage(message);
                        message.setFlag(Flag.X_SEND_IN_PROGRESS, false);

                        // Upload to "sent" folder if not supported server-side
                        if (requireCopyMessageToSentFolder) {
                            localFolder.copyMessages(
                                    new Message[] { message },localSentFolder, null);
                            PendingCommand command = new PendingCommand();
                            command.command = PENDING_COMMAND_APPEND;
                            command.arguments =
                                new String[] { localSentFolder.getName(), message.getUid() };
                            queuePendingCommand(account, command);
                            processPendingCommands(account);
                        }

                        // And delete from outbox
                        message.setFlag(Flag.X_DESTROYED, true);
                    }
                    catch (Exception e) {
                        message.setFlag(Flag.X_SEND_FAILED, true);
                        mListeners.sendPendingMessageFailed(account, message, e);
                    }
                }
                catch (Exception e) {
                    mListeners.sendPendingMessageFailed(account, message, e);
                }
            }
            localFolder.expunge();
            mListeners.sendPendingMessagesCompleted(account);
        }
        catch (Exception e) {
            mListeners.sendPendingMessagesFailed(account, e);
        }
    }

    /**
     * We do the local portion of this synchronously because other activities may have to make
     * updates based on what happens here
     * @param account
     * @param folder
     * @param message
     * @param listener
     */
    public void deleteMessage(final EmailContent.Account account, final String folder,
            final Message message, MessagingListener listener) {
        if (folder.equals(account.getTrashFolderName(mContext))) {
            return;
        }
        try {
            Store localStore = Store.getInstance(account.getLocalStoreUri(mContext), mContext,
                    null);
            Folder localFolder = localStore.getFolder(folder);
            Folder localTrashFolder = localStore.getFolder(account.getTrashFolderName(mContext));

            localFolder.copyMessages(new Message[] { message }, localTrashFolder, null);
            message.setFlag(Flag.DELETED, true);

            if (account.getDeletePolicy() == Account.DELETE_POLICY_ON_DELETE) {
                PendingCommand command = new PendingCommand();
                command.command = PENDING_COMMAND_TRASH;
                command.arguments = new String[] { folder, message.getUid() };
                queuePendingCommand(account, command);
                processPendingCommands(account);
            }
        }
        catch (MessagingException me) {
            throw new RuntimeException("Error deleting message from local store.", me);
        }
    }

    public void emptyTrash(final EmailContent.Account account, MessagingListener listener) {
        put("emptyTrash", listener, new Runnable() {
            public void run() {
                // TODO IMAP
                try {
                    Store localStore = Store.getInstance(
                            account.getLocalStoreUri(mContext), mContext, null);
                    Folder localFolder = localStore.getFolder(account.getTrashFolderName(mContext));
                    localFolder.open(OpenMode.READ_WRITE, null);
                    Message[] messages = localFolder.getMessages(null);
                    localFolder.setFlags(messages, new Flag[] {
                        Flag.DELETED
                    }, true);
                    localFolder.close(true);
                    mListeners.emptyTrashCompleted(account);
                }
                catch (Exception e) {
                    // TODO
                    if (Email.LOGD) {
                        Log.v(Email.LOG_TAG, "emptyTrash");
                    }
                }
            }
        });
    }

    /**
     * Checks mail for one or multiple accounts. If account is null all accounts
     * are checked.
     *
     * TODO:  There is no use case for "check all accounts".  Clean up this API to remove
     * that case.  Callers can supply the appropriate list.
     *
     * TODO:  Better protection against a failure in account n, which should not prevent
     * syncing account in accounts n+1 and beyond.
     *
     * @param context
     * @param accounts List of accounts to check, or null to check all accounts
     * @param listener
     */
    public void checkMail(final Context context, EmailContent.Account[] accounts,
            final MessagingListener listener) {
        /**
         * Note:  The somewhat tortured logic here is to guarantee proper ordering of events:
         *      listeners: checkMailStarted
         *      account 1: list folders
         *      account 1: sync messages
         *      account 2: list folders
         *      account 2: sync messages
         *      ...
         *      account n: list folders
         *      account n: sync messages
         *      listeners: checkMailFinished
         */
        mListeners.checkMailStarted(context, null); // TODO this needs to pass the actual array
        if (accounts == null) {
            // TODO eliminate this use case, implement, or ...?
//            accounts = Preferences.getPreferences(context).getAccounts();
        }
        for (final EmailContent.Account account : accounts) {
            listFolders(account, null);

            put("checkMail", listener, new Runnable() {
                public void run() {
                    sendPendingMessagesSynchronous(account);
                    // TODO find mailbox # for inbox and sync it.
//                    synchronizeMailboxSynchronous(account, Email.INBOX);
                }
            });
        }
        put("checkMailFinished", listener, new Runnable() {
            public void run() {
                mListeners.checkMailFinished(context, null); // TODO this needs to pass actual array
            }
        });
    }

    public void saveDraft(final EmailContent.Account account, final Message message) {
        try {
            Store localStore = Store.getInstance(account.getLocalStoreUri(mContext), mContext,
                    null);
            LocalFolder localFolder =
                (LocalFolder) localStore.getFolder(account.getDraftsFolderName(mContext));
            localFolder.open(OpenMode.READ_WRITE, null);
            localFolder.appendMessages(new Message[] {
                message
            });
            Message localMessage = localFolder.getMessage(message.getUid());
            localMessage.setFlag(Flag.X_DOWNLOADED_FULL, true);

            PendingCommand command = new PendingCommand();
            command.command = PENDING_COMMAND_APPEND;
            command.arguments = new String[] {
                    localFolder.getName(),
                    localMessage.getUid() };
            queuePendingCommand(account, command);
            processPendingCommands(account);
        }
        catch (MessagingException e) {
            Log.e(Email.LOG_TAG, "Unable to save message as draft.", e);
        }
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
