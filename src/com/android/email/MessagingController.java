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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;

import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.emailcommon.Logging;
import com.android.emailcommon.TrafficFlags;
import com.android.emailcommon.internet.MimeBodyPart;
import com.android.emailcommon.internet.MimeHeader;
import com.android.emailcommon.internet.MimeMultipart;
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
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.AttachmentColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.EmailContent.SyncColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.emailcommon.utility.ConversionUtilities;
import com.android.emailcommon.utility.Utility;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
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
    private static final Flag[] FLAG_LIST_ANSWERED = new Flag[] { Flag.ANSWERED };

    /**
     * We write this into the serverId field of messages that will never be upsynced.
     */
    private static final String LOCAL_SERVERID_PREFIX = "Local-";

    /**
     * Cache search results by account; this allows for "load more" support without having to
     * redo the search (which can be quite slow).  SortableMessage is a smallish class, so memory
     * shouldn't be an issue
     */
    private static final HashMap<Long, SortableMessage[]> sSearchResults =
        new HashMap<Long, SortableMessage[]>();

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
    private final Controller mController;

    /**
     * Simple cache for last search result mailbox by account and serverId, since the most common
     * case will be repeated use of the same mailbox
     */
    private long mLastSearchAccountKey = Account.NO_ACCOUNT;
    private String mLastSearchServerId = null;
    private Mailbox mLastSearchRemoteMailbox = null;

    protected MessagingController(Context _context, Controller _controller) {
        mContext = _context.getApplicationContext();
        mController = _controller;
        mThread = new Thread(this);
        mThread.start();
    }

    /**
     * Gets or creates the singleton instance of MessagingController. Application is used to
     * provide a Context to classes that need it.
     */
    public synchronized static MessagingController getInstance(Context _context,
            Controller _controller) {
        if (sInstance == null) {
            sInstance = new MessagingController(_context, _controller);
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

    private static final int MAILBOX_COLUMN_ID = 0;
    private static final int MAILBOX_COLUMN_SERVER_ID = 1;
    private static final int MAILBOX_COLUMN_TYPE = 2;

    /** Small projection for just the columns required for a sync. */
    private static final String[] MAILBOX_PROJECTION = new String[] {
        MailboxColumns.ID,
        MailboxColumns.SERVER_ID,
        MailboxColumns.TYPE,
    };

    /**
     * Synchronize the folder list with the remote server. Synchronization occurs in the
     * background and results are passed through the {@link MessagingListener}. If the
     * given listener is not {@code null}, it must have been previously added to the set
     * of listeners using the {@link #addListener(MessagingListener)}. Otherwise, no
     * actions will be performed.
     *
     * TODO this needs to cache the remote folder list
     * TODO break out an inner listFoldersSynchronized which could simplify checkMail
     *
     * @param accountId ID of the account for which to list the folders
     * @param listener A listener to notify
     */
    void listFolders(final long accountId, MessagingListener listener) {
        final Account account = Account.restoreAccountWithId(mContext, accountId);
        if (account == null) {
            Log.i(Logging.LOG_TAG, "Could not load account id " + accountId
                    + ". Has it been removed?");
            return;
        }
        mListeners.listFoldersStarted(accountId);
        put("listFolders", listener, new Runnable() {
            // TODO For now, mailbox addition occurs in the server-dependent store implementation,
            // but, mailbox removal occurs here. Instead, each store should be responsible for
            // content synchronization (addition AND removal) since each store will likely need
            // to implement it's own, unique synchronization methodology.
            public void run() {
                TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(mContext, account));
                Cursor localFolderCursor = null;
                try {
                    // Step 1: Get remote mailboxes
                    Store store = Store.getInstance(account, mContext);
                    Folder[] remoteFolders = store.updateFolders();
                    HashSet<String> remoteFolderNames = new HashSet<String>();
                    for (int i = 0, count = remoteFolders.length; i < count; i++) {
                        remoteFolderNames.add(remoteFolders[i].getName());
                    }

                    // Step 2: Get local mailboxes
                    localFolderCursor = mContext.getContentResolver().query(
                            Mailbox.CONTENT_URI,
                            MAILBOX_PROJECTION,
                            EmailContent.MailboxColumns.ACCOUNT_KEY + "=?",
                            new String[] { String.valueOf(account.mId) },
                            null);

                    // Step 3: Remove any local mailbox not on the remote list
                    while (localFolderCursor.moveToNext()) {
                        String mailboxPath = localFolderCursor.getString(MAILBOX_COLUMN_SERVER_ID);
                        // Short circuit if we have a remote mailbox with the same name
                        if (remoteFolderNames.contains(mailboxPath)) {
                            continue;
                        }

                        int mailboxType = localFolderCursor.getInt(MAILBOX_COLUMN_TYPE);
                        long mailboxId = localFolderCursor.getLong(MAILBOX_COLUMN_ID);
                        switch (mailboxType) {
                            case Mailbox.TYPE_INBOX:
                            case Mailbox.TYPE_DRAFTS:
                            case Mailbox.TYPE_OUTBOX:
                            case Mailbox.TYPE_SENT:
                            case Mailbox.TYPE_TRASH:
                            case Mailbox.TYPE_SEARCH:
                                // Never, ever delete special mailboxes
                                break;
                            default:
                                // Drop all attachment files related to this mailbox
                                AttachmentUtilities.deleteAllMailboxAttachmentFiles(
                                        mContext, accountId, mailboxId);
                                // Delete the mailbox; database triggers take care of related
                                // Message, Body and Attachment records
                                Uri uri = ContentUris.withAppendedId(
                                        Mailbox.CONTENT_URI, mailboxId);
                                mContext.getContentResolver().delete(uri, null, null);
                                break;
                        }
                    }
                    mListeners.listFoldersFinished(accountId);
                } catch (Exception e) {
                    mListeners.listFoldersFailed(accountId, e.toString());
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
    public void synchronizeMailbox(final Account account,
            final Mailbox folder, MessagingListener listener) {
        /*
         * We don't ever sync the Outbox.
         */
        if (folder.mType == Mailbox.TYPE_OUTBOX) {
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
    private void synchronizeMailboxSynchronous(final Account account,
            final Mailbox folder) {
        TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(mContext, account));
        mListeners.synchronizeMailboxStarted(account.mId, folder.mId);
        if ((folder.mFlags & Mailbox.FLAG_HOLDS_MAIL) == 0) {
            // We don't hold messages, so, nothing to synchronize
            mListeners.synchronizeMailboxFinished(account.mId, folder.mId, 0, 0, null);
            return;
        }
        NotificationController nc = NotificationController.getInstance(mContext);
        try {
            processPendingActionsSynchronous(account);

            // Select generic sync or store-specific sync
            SyncResults results = synchronizeMailboxGeneric(account, folder);
            // The account might have been deleted
            if (results == null) return;
            mListeners.synchronizeMailboxFinished(account.mId, folder.mId,
                                                  results.mTotalMessages,
                                                  results.mAddedMessages.size(),
                                                  results.mAddedMessages);
            // Clear authentication notification for this account
            nc.cancelLoginFailedNotification(account.mId);
        } catch (MessagingException e) {
            if (Logging.LOGD) {
                Log.v(Logging.LOG_TAG, "synchronizeMailbox", e);
            }
            if (e instanceof AuthenticationFailedException) {
                // Generate authentication notification
                nc.showLoginFailedNotification(account.mId);
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

    private void saveOrUpdate(EmailContent content, Context context) {
        if (content.isSaved()) {
            content.update(context, content.toContentValues());
        } else {
            content.save(context);
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
    void loadUnsyncedMessages(final Account account, Folder remoteFolder,
            ArrayList<Message> unsyncedMessages, final Mailbox toMailbox)
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
                    public void messageRetrieved(Message message) {
                        // Store the updated message locally and mark it fully loaded
                        copyOneMessageToProvider(message, account, toMailbox,
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
                copyOneMessageToProvider(message, account, toMailbox,
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
                copyOneMessageToProvider(message, account, toMailbox,
                        EmailContent.Message.FLAG_LOADED_COMPLETE);
            }
        }

    }

    public void downloadFlagAndEnvelope(final Account account, final Mailbox mailbox,
            Folder remoteFolder, ArrayList<Message> unsyncedMessages,
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
                                        mContext, localMessageInfo.mId);
                            }

                            if (localMessage != null) {
                                try {
                                    // Copy the fields that are available into the message
                                    LegacyConversions.updateMessageFields(localMessage,
                                            message, account.mId, mailbox.mId);
                                    // Commit the message to the local store
                                    saveOrUpdate(localMessage, mContext);
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

    public int searchMailbox(long accountId, SearchParams searchParams, long destMailboxId)
            throws MessagingException {
        try {
            return searchMailboxImpl(accountId, searchParams, destMailboxId);
        } finally {
            // Tell UI that we're done loading any search results (no harm calling this even if we
            // encountered an error or never sent a "started" message)
            mListeners.synchronizeMailboxFinished(accountId, destMailboxId, 0, 0, null);
        }
    }

    private int searchMailboxImpl(long accountId, SearchParams searchParams,
            final long destMailboxId) throws MessagingException {
        final Account account = Account.restoreAccountWithId(mContext, accountId);
        final Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, searchParams.mMailboxId);
        final Mailbox destMailbox = Mailbox.restoreMailboxWithId(mContext, destMailboxId);
        if (account == null || mailbox == null || destMailbox == null) {
            Log.d(Logging.LOG_TAG, "Attempted search for " + searchParams
                    + " but account or mailbox information was missing");
            return 0;
        }

        // Tell UI that we're loading messages
        mListeners.synchronizeMailboxStarted(accountId, destMailbox.mId);

        Store remoteStore = Store.getInstance(account, mContext);
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
                        saveOrUpdate(localMessage, mContext);
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
                        copyOneMessageToProvider(message, localMessage, flag, mContext);
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


    /**
     * Generic synchronizer - used for POP3 and IMAP.
     *
     * TODO Break this method up into smaller chunks.
     *
     * @param account the account to sync
     * @param mailbox the mailbox to sync
     * @return results of the sync pass
     * @throws MessagingException
     */
    private SyncResults synchronizeMailboxGeneric(final Account account, final Mailbox mailbox)
            throws MessagingException {

        /*
         * A list of IDs for messages that were downloaded and did not have the seen flag set.
         * This serves as the "true" new message count reported to the user via notification.
         */
        final ArrayList<Long> unseenMessages = new ArrayList<Long>();

        Log.d(Logging.LOG_TAG, "*** synchronizeMailboxGeneric ***");
        ContentResolver resolver = mContext.getContentResolver();

        // 0.  We do not ever sync DRAFTS or OUTBOX (down or up)
        if (mailbox.mType == Mailbox.TYPE_DRAFTS || mailbox.mType == Mailbox.TYPE_OUTBOX) {
            int totalMessages = EmailContent.count(mContext, mailbox.getUri(), null, null);
            return new SyncResults(totalMessages, unseenMessages);
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

        Store remoteStore = Store.getInstance(account, mContext);
        // The account might have been deleted
        if (remoteStore == null) return null;
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
                    return new SyncResults(0, unseenMessages);
                }
            }
        }

        // 3, Open the remote folder. This pre-loads certain metadata like message count.
        remoteFolder.open(OpenMode.READ_WRITE);

        // 4. Trash any remote messages that are marked as trashed locally.
        // TODO - this comment was here, but no code was here.

        // 5. Get the remote message count.
        int remoteMessageCount = remoteFolder.getMessageCount();

        // 6. Determine the limit # of messages to download
        int visibleLimit = mailbox.mVisibleLimit;
        if (visibleLimit <= 0) {
            visibleLimit = Email.VISIBLE_LIMIT_DEFAULT;
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
         * Fetch the flags and envelope only of the new messages. This is intended to get us
         * critical data as fast as possible, and then we'll fill in the details.
         */
        if (unsyncedMessages.size() > 0) {
            downloadFlagAndEnvelope(account, mailbox, remoteFolder, unsyncedMessages,
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
            AttachmentUtilities.deleteAllAttachmentFiles(mContext, account.mId,
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

        loadUnsyncedMessages(account, remoteFolder, unsyncedMessages, mailbox);

        // 14. Clean up and report results
        remoteFolder.close(false);

        return new SyncResults(remoteMessageCount, unseenMessages);
    }

    /**
     * Copy one downloaded message (which may have partially-loaded sections)
     * into a newly created EmailProvider Message, given the account and mailbox
     *
     * @param message the remote message we've just downloaded
     * @param account the account it will be stored into
     * @param folder the mailbox it will be stored into
     * @param loadStatus when complete, the message will be marked with this status (e.g.
     *        EmailContent.Message.LOADED)
     */
    public void copyOneMessageToProvider(Message message, Account account,
            Mailbox folder, int loadStatus) {
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
                localMessage.mMailboxKey = folder.mId;
                localMessage.mAccountKey = account.mId;
                copyOneMessageToProvider(message, localMessage, loadStatus, mContext);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * Copy one downloaded message (which may have partially-loaded sections)
     * into an already-created EmailProvider Message
     *
     * @param message the remote message we've just downloaded
     * @param localMessage the EmailProvider Message, already created
     * @param loadStatus when complete, the message will be marked with this status (e.g.
     *        EmailContent.Message.LOADED)
     * @param context the context to be used for EmailProvider
     */
    public void copyOneMessageToProvider(Message message, EmailContent.Message localMessage,
            int loadStatus, Context context) {
        try {

            EmailContent.Body body = EmailContent.Body.restoreBodyWithMessageId(context,
                    localMessage.mId);
            if (body == null) {
                body = new EmailContent.Body();
            }
            try {
                // Copy the fields that are available into the message object
                LegacyConversions.updateMessageFields(localMessage, message,
                        localMessage.mAccountKey, localMessage.mMailboxKey);

                // Now process body parts & attachments
                ArrayList<Part> viewables = new ArrayList<Part>();
                ArrayList<Part> attachments = new ArrayList<Part>();
                MimeUtility.collectParts(message, viewables, attachments);

                ConversionUtilities.updateBodyFields(body, localMessage, viewables);

                // Commit the message & body to the local store immediately
                saveOrUpdate(localMessage, context);
                saveOrUpdate(body, context);

                // process (and save) attachments
                LegacyConversions.updateAttachments(context, localMessage, attachments);

                // One last update of message with two updated flags
                localMessage.mFlagLoaded = loadStatus;

                ContentValues cv = new ContentValues();
                cv.put(EmailContent.MessageColumns.FLAG_ATTACHMENT, localMessage.mFlagAttachment);
                cv.put(EmailContent.MessageColumns.FLAG_LOADED, localMessage.mFlagLoaded);
                Uri uri = ContentUris.withAppendedId(EmailContent.Message.CONTENT_URI,
                        localMessage.mId);
                context.getContentResolver().update(uri, cv, null, null);

            } catch (MessagingException me) {
                Log.e(Logging.LOG_TAG, "Error while copying downloaded message." + me);
            }

        } catch (RuntimeException rte) {
            Log.e(Logging.LOG_TAG, "Error while storing downloaded message." + rte.toString());
        } catch (IOException ioe) {
            Log.e(Logging.LOG_TAG, "Error while storing attachment." + ioe.toString());
        }
    }

    public void processPendingActions(final long accountId) {
        put("processPendingActions", null, new Runnable() {
            public void run() {
                try {
                    Account account = Account.restoreAccountWithId(mContext, accountId);
                    if (account == null) {
                        return;
                    }
                    processPendingActionsSynchronous(account);
                }
                catch (MessagingException me) {
                    if (Logging.LOGD) {
                        Log.v(Logging.LOG_TAG, "processPendingActions", me);
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
    private void processPendingActionsSynchronous(Account account)
           throws MessagingException {
        TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(mContext, account));
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
     * Get the mailbox corresponding to the remote location of a message; this will normally be
     * the mailbox whose _id is mailboxKey, except for search results, where we must look it up
     * by serverId
     * @param message the message in question
     * @return the mailbox in which the message resides on the server
     */
    private Mailbox getRemoteMailboxForMessage(EmailContent.Message message) {
        // If this is a search result, use the protocolSearchInfo field to get the server info
        if (!TextUtils.isEmpty(message.mProtocolSearchInfo)) {
            long accountKey = message.mAccountKey;
            String protocolSearchInfo = message.mProtocolSearchInfo;
            if (accountKey == mLastSearchAccountKey &&
                    protocolSearchInfo.equals(mLastSearchServerId)) {
                return mLastSearchRemoteMailbox;
            }
            Cursor c =  mContext.getContentResolver().query(Mailbox.CONTENT_URI,
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
            return Mailbox.restoreMailboxWithId(mContext, message.mMailboxKey);
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
    private void processPendingDeletesSynchronous(Account account,
            ContentResolver resolver, String[] accountIdArgs) {
        Cursor deletes = resolver.query(EmailContent.Message.DELETED_CONTENT_URI,
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

                    Mailbox mailbox = getRemoteMailboxForMessage(oldMessage);
                    if (mailbox == null) {
                        continue; // Mailbox removed. Move to the next message.
                    }
                    deleteFromTrash = mailbox.mType == Mailbox.TYPE_TRASH;

                    // Load the remote store if it will be needed
                    if (remoteStore == null && deleteFromTrash) {
                        remoteStore = Store.getInstance(account, mContext);
                    }

                    // Dispatch here for specific change types
                    if (deleteFromTrash) {
                        // Move message to trash
                        processPendingDeleteFromTrash(remoteStore, account, mailbox, oldMessage);
                    }
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
    private void processPendingUploadsSynchronous(Account account,
            ContentResolver resolver, String[] accountIdArgs) {
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
                            remoteStore = Store.getInstance(account, mContext);
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
                            remoteStore = Store.getInstance(account, mContext);
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
    private void processPendingUpdatesSynchronous(Account account,
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
                boolean changeMailbox = false;
                boolean changeAnswered = false;

                EmailContent.Message oldMessage =
                    EmailContent.getContent(updates, EmailContent.Message.class);
                lastMessageId = oldMessage.mId;
                EmailContent.Message newMessage =
                    EmailContent.Message.restoreMessageWithId(mContext, oldMessage.mId);
                if (newMessage != null) {
                    mailbox = Mailbox.restoreMailboxWithId(mContext, newMessage.mMailboxKey);
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
                    remoteStore = Store.getInstance(account, mContext);
                }

                // Dispatch here for specific change types
                if (changeMoveToTrash) {
                    // Move message to trash
                    processPendingMoveToTrash(remoteStore, account, mailbox, oldMessage,
                            newMessage);
                } else if (changeRead || changeFlagged || changeMailbox || changeAnswered) {
                    processPendingDataChange(remoteStore, mailbox, changeRead, changeFlagged,
                            changeMailbox, changeAnswered, oldMessage, newMessage);
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
    private void processUploadMessage(ContentResolver resolver, Store remoteStore,
            Account account, Mailbox mailbox, long messageId)
            throws MessagingException {
        EmailContent.Message newMessage =
            EmailContent.Message.restoreMessageWithId(mContext, messageId);
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
            Log.d(Logging.LOG_TAG, "Upsyc triggered for message id=" + messageId);
            deleteUpdate = processPendingAppend(remoteStore, account, mailbox, newMessage);
        }
        if (deleteUpdate) {
            // Finally, delete the update (if any)
            Uri uri = ContentUris.withAppendedId(
                    EmailContent.Message.UPDATED_CONTENT_URI, messageId);
            resolver.delete(uri, null, null);
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
    private void processPendingDataChange(Store remoteStore, Mailbox mailbox, boolean changeRead,
            boolean changeFlagged, boolean changeMailbox, boolean changeAnswered,
            EmailContent.Message oldMessage, final EmailContent.Message newMessage)
            throws MessagingException {
        // New mailbox is the mailbox this message WILL be in (same as the one it WAS in if it isn't
        // being moved
        Mailbox newMailbox = mailbox;
        // Mailbox is the original remote mailbox (the one we're acting on)
        mailbox = getRemoteMailboxForMessage(oldMessage);

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
        if (Email.DEBUG) {
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
                    mContext.getContentResolver().update(ContentUris.withAppendedId(
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
    private void processPendingMoveToTrash(Store remoteStore,
            Account account, Mailbox newMailbox, EmailContent.Message oldMessage,
            final EmailContent.Message newMessage) throws MessagingException {

        // 0. No remote move if the message is local-only
        if (newMessage.mServerId == null || newMessage.mServerId.equals("")
                || newMessage.mServerId.startsWith(LOCAL_SERVERID_PREFIX)) {
            return;
        }

        // 1. Escape early if we can't find the local mailbox
        // TODO smaller projection here
        Mailbox oldMailbox = getRemoteMailboxForMessage(oldMessage);
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
    private void processPendingDeleteFromTrash(Store remoteStore,
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
    private boolean processPendingAppend(Store remoteStore, Account account,
            Mailbox newMailbox, EmailContent.Message message)
            throws MessagingException {

        boolean updateInternalDate = false;
        boolean updateMessage = false;
        boolean deleteMessage = false;

        // 1. Find the remote folder that we're appending to and create and/or open it
        Folder remoteFolder = remoteStore.getFolder(newMailbox.mServerId);
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
        remoteFolder.open(OpenMode.READ_WRITE);
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
                    // TODO combine with common code in loadAttachment
                    Account account = Account.restoreAccountWithId(mContext, message.mAccountKey);
                    Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, message.mMailboxKey);
                    if (account == null || mailbox == null) {
                        mListeners.loadMessageForViewFailed(messageId, "null account or mailbox");
                        return;
                    }
                    TrafficStats.setThreadStatsTag(TrafficFlags.getSyncFlags(mContext, account));

                    Store remoteStore = Store.getInstance(account, mContext);
                    String remoteServerId = mailbox.mServerId;
                    // If this is a search result, use the protocolSearchInfo field to get the
                    // correct remote location
                    if (!TextUtils.isEmpty(message.mProtocolSearchInfo)) {
                        remoteServerId = message.mProtocolSearchInfo;
                    }
                    Folder remoteFolder = remoteStore.getFolder(remoteServerId);
                    remoteFolder.open(OpenMode.READ_WRITE);

                    // 3. Set up to download the entire message
                    Message remoteMessage = remoteFolder.getMessage(message.mServerId);
                    FetchProfile fp = new FetchProfile();
                    fp.add(FetchProfile.Item.BODY);
                    remoteFolder.fetch(new Message[] { remoteMessage }, fp, null);

                    // 4. Write to provider
                    copyOneMessageToProvider(remoteMessage, account, mailbox,
                            EmailContent.Message.FLAG_LOADED_COMPLETE);

                    // 5. Notify UI
                    mListeners.loadMessageForViewFinished(messageId);

                } catch (MessagingException me) {
                    if (Logging.LOGD) Log.v(Logging.LOG_TAG, "", me);
                    mListeners.loadMessageForViewFailed(messageId, me.getMessage());
                } catch (RuntimeException rte) {
                    mListeners.loadMessageForViewFailed(messageId, rte.getMessage());
                }
            }
        });
    }

    /**
     * Attempts to load the attachment specified by id from the given account and message.
     */
    public void loadAttachment(final long accountId, final long messageId, final long mailboxId,
            final long attachmentId, MessagingListener listener, final boolean background) {
        mListeners.loadAttachmentStarted(accountId, messageId, attachmentId, true);

        put("loadAttachment", listener, new Runnable() {
            public void run() {
                try {
                    //1. Check if the attachment is already here and return early in that case
                    Attachment attachment =
                        Attachment.restoreAttachmentWithId(mContext, attachmentId);
                    if (attachment == null) {
                        mListeners.loadAttachmentFailed(accountId, messageId, attachmentId,
                                   new MessagingException("The attachment is null"),
                                   background);
                        return;
                    }
                    if (Utility.attachmentExists(mContext, attachment)) {
                        mListeners.loadAttachmentFinished(accountId, messageId, attachmentId);
                        return;
                    }

                    // 2. Open the remote folder.
                    // TODO all of these could be narrower projections
                    Account account = Account.restoreAccountWithId(mContext, accountId);
                    Mailbox mailbox = Mailbox.restoreMailboxWithId(mContext, mailboxId);
                    EmailContent.Message message =
                        EmailContent.Message.restoreMessageWithId(mContext, messageId);

                    if (account == null || mailbox == null || message == null) {
                        mListeners.loadAttachmentFailed(accountId, messageId, attachmentId,
                                new MessagingException(
                                        "Account, mailbox, message or attachment are null"),
                                background);
                        return;
                    }
                    TrafficStats.setThreadStatsTag(
                            TrafficFlags.getAttachmentFlags(mContext, account));

                    Store remoteStore = Store.getInstance(account, mContext);
                    Folder remoteFolder = remoteStore.getFolder(mailbox.mServerId);
                    remoteFolder.open(OpenMode.READ_WRITE);

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
                    remoteFolder.fetch(new Message[] { storeMessage }, fp,
                            mController.new MessageRetrievalListenerBridge(
                                    messageId, attachmentId));

                    // If we failed to load the attachment, throw an Exception here, so that
                    // AttachmentDownloadService knows that we failed
                    if (storePart.getBody() == null) {
                        throw new MessagingException("Attachment not loaded.");
                    }

                    // 5. Save the downloaded file and update the attachment as necessary
                    LegacyConversions.saveAttachmentBody(mContext, storePart, attachment,
                            accountId);

                    // 6. Report success
                    mListeners.loadAttachmentFinished(accountId, messageId, attachmentId);
                }
                catch (MessagingException me) {
                    if (Logging.LOGD) Log.v(Logging.LOG_TAG, "", me);
                    mListeners.loadAttachmentFailed(
                            accountId, messageId, attachmentId, me, background);
                } catch (IOException ioe) {
                    Log.e(Logging.LOG_TAG, "Error while storing attachment." + ioe.toString());
                }
            }});
    }

    /**
     * Attempt to send any messages that are sitting in the Outbox.
     * @param account
     * @param listener
     */
    public void sendPendingMessages(final Account account, final long sentFolderId,
            MessagingListener listener) {
        put("sendPendingMessages", listener, new Runnable() {
            public void run() {
                sendPendingMessagesSynchronous(account, sentFolderId);
            }
        });
    }

    /**
     * Attempt to send all messages sitting in the given account's outbox. Optionally,
     * if the server requires it, the message will be moved to the given sent folder.
     */
    public void sendPendingMessagesSynchronous(final Account account,
            long sentFolderId) {
        TrafficStats.setThreadStatsTag(TrafficFlags.getSmtpFlags(mContext, account));
        NotificationController nc = NotificationController.getInstance(mContext);
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

            Sender sender = Sender.getInstance(mContext, account);
            Store remoteStore = Store.getInstance(account, mContext);
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
                    // Don't send messages with unloaded attachments
                    if (Utility.hasUnloadedAttachments(mContext, messageId)) {
                        if (Email.DEBUG) {
                            Log.d(Logging.LOG_TAG, "Can't send #" + messageId +
                                    "; unloaded attachments");
                        }
                        continue;
                    }
                    sender.sendMessage(messageId);
                } catch (MessagingException me) {
                    // report error for this message, but keep trying others
                    if (me instanceof AuthenticationFailedException) {
                        nc.showLoginFailedNotification(account.mId);
                    }
                    mListeners.sendPendingMessagesFailed(account.mId, messageId, me);
                    if (me.getExceptionType() == MessagingException.GENERAL_SECURITY) {
                        final Object exceptionData = me.getExceptionData();
                        if (exceptionData != null && exceptionData instanceof Attachment) {
                            nc.showDownloadForwardFailedNotification((Attachment) exceptionData);
                        }
                    }
                    continue;
                }
                // 5. move to sent, or delete
                Uri syncedUri =
                    ContentUris.withAppendedId(EmailContent.Message.SYNCED_CONTENT_URI, messageId);
                if (requireMoveMessageToSentFolder) {
                    // If this is a forwarded message and it has attachments, delete them, as they
                    // duplicate information found elsewhere (on the server).  This saves storage.
                    EmailContent.Message msg =
                        EmailContent.Message.restoreMessageWithId(mContext, messageId);
                    if (msg != null &&
                            ((msg.mFlags & EmailContent.Message.FLAG_TYPE_FORWARD) != 0)) {
                        AttachmentUtilities.deleteAllAttachmentFiles(mContext, account.mId,
                                messageId);
                    }
                    resolver.update(syncedUri, moveToSentValues, null, null);
                } else {
                    AttachmentUtilities.deleteAllAttachmentFiles(mContext, account.mId,
                            messageId);
                    Uri uri =
                        ContentUris.withAppendedId(EmailContent.Message.CONTENT_URI, messageId);
                    resolver.delete(uri, null, null);
                    resolver.delete(syncedUri, null, null);
                }
            }
            // 6. report completion/success
            mListeners.sendPendingMessagesCompleted(account.mId);
            nc.cancelLoginFailedNotification(account.mId);
        } catch (MessagingException me) {
            if (me instanceof AuthenticationFailedException) {
                nc.showLoginFailedNotification(account.mId);
            }
            mListeners.sendPendingMessagesFailed(account.mId, -1, me);
        } finally {
            c.close();
        }
    }

    /**
     * Checks mail for an account.
     * This entry point is for use by the mail checking service only, because it
     * gives slightly different callbacks (so the service doesn't get confused by callbacks
     * triggered by/for the foreground UI.
     *
     * TODO clean up the execution model which is unnecessarily threaded due to legacy code
     *
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
                Account account = Account.restoreAccountWithId(mContext, accountId);
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
                        Mailbox mailbox =
                            Mailbox.restoreMailboxWithId(mContext, inboxId);
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

    /** Results of the latest synchronization. */
    private static class SyncResults {
        /** The total # of messages in the folder */
        public final int mTotalMessages;
        /** A list of new message IDs; must not be {@code null} */
        public final ArrayList<Long> mAddedMessages;

        public SyncResults(int totalMessages, ArrayList<Long> addedMessages) {
            if (addedMessages == null) {
                throw new IllegalArgumentException("addedMessages must not be null");
            }
            mTotalMessages = totalMessages;
            mAddedMessages = addedMessages;
        }
    }
}
