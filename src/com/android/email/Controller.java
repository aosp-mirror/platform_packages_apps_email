/*
 * Copyright (C) 2009 The Android Open Source Project
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

import com.android.email.mail.AuthenticationFailedException;
import com.android.email.mail.Folder.MessageRetrievalListener;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Store;
import com.android.email.mail.store.Pop3Store.Pop3Message;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Body;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;
import com.android.email.service.EmailServiceStatus;
import com.android.email.service.IEmailService;
import com.android.email.service.IEmailServiceCallback;

import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidParameterException;
import java.util.Collection;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * New central controller/dispatcher for Email activities that may require remote operations.
 * Handles disambiguating between legacy MessagingController operations and newer provider/sync
 * based code.  We implement Service to allow loadAttachment calls to be sent in a consistent manner
 * to IMAP, POP3, and EAS by AttachmentDownloadService
 */
public class Controller {
    private static final String TAG = "Controller";
    private static Controller sInstance;
    private final Context mContext;
    private Context mProviderContext;
    private final MessagingController mLegacyController;
    private final LegacyListener mLegacyListener = new LegacyListener();
    private final ServiceCallback mServiceCallback = new ServiceCallback();
    private final HashSet<Result> mListeners = new HashSet<Result>();
    /*package*/ final ConcurrentHashMap<Long, Boolean> mLegacyControllerMap =
        new ConcurrentHashMap<Long, Boolean>();


    // Note that 0 is a syntactically valid account key; however there can never be an account
    // with id = 0, so attempts to restore the account will return null.  Null values are
    // handled properly within the code, so this won't cause any issues.
    private static final long ATTACHMENT_MAILBOX_ACCOUNT_KEY = 0;
    /*package*/ static final String ATTACHMENT_MAILBOX_SERVER_ID = "__attachment_mailbox__";
    /*package*/ static final String ATTACHMENT_MESSAGE_UID_PREFIX = "__attachment_message__";
    private static final String WHERE_TYPE_ATTACHMENT =
        MailboxColumns.TYPE + "=" + Mailbox.TYPE_ATTACHMENT;
    private static final String WHERE_MAILBOX_KEY = MessageColumns.MAILBOX_KEY + "=?";

    private static final String[] MESSAGEID_TO_ACCOUNTID_PROJECTION = new String[] {
        EmailContent.RECORD_ID,
        EmailContent.MessageColumns.ACCOUNT_KEY
    };
    private static final int MESSAGEID_TO_ACCOUNTID_COLUMN_ACCOUNTID = 1;

    private static final String[] BODY_SOURCE_KEY_PROJECTION =
        new String[] {Body.SOURCE_MESSAGE_KEY};
    private static final int BODY_SOURCE_KEY_COLUMN = 0;
    private static final String WHERE_MESSAGE_KEY = Body.MESSAGE_KEY + "=?";

    private static final String[] MESSAGEID_TO_MAILBOXID_PROJECTION = new String[] {
        EmailContent.RECORD_ID,
        EmailContent.MessageColumns.MAILBOX_KEY
    };
    private static final int MESSAGEID_TO_MAILBOXID_COLUMN_MAILBOXID = 1;

    // Service callbacks as set up via setCallback
    private static RemoteCallbackList<IEmailServiceCallback> sCallbackList =
        new RemoteCallbackList<IEmailServiceCallback>();

    protected Controller(Context _context) {
        mContext = _context.getApplicationContext();
        mProviderContext = _context;
        mLegacyController = MessagingController.getInstance(mProviderContext, this);
        mLegacyController.addListener(mLegacyListener);
    }

    /**
     * Cleanup for test.  Mustn't be called for the regular {@link Controller}, as it's a
     * singleton and lives till the process finishes.
     *
     * <p>However, this method MUST be called for mock instances.
     */
    public void cleanupForTest() {
        mLegacyController.removeListener(mLegacyListener);
    }

    /**
     * Gets or creates the singleton instance of Controller.
     */
    public synchronized static Controller getInstance(Context _context) {
        if (sInstance == null) {
            sInstance = new Controller(_context);
        }
        return sInstance;
    }

    /**
     * Inject a mock controller.  Used only for testing.  Affects future calls to getInstance().
     *
     * Tests that use this method MUST clean it up by calling this method again with null.
     */
    public synchronized static void injectMockControllerForTest(Controller mockController) {
        sInstance = mockController;
    }

    /**
     * For testing only:  Inject a different context for provider access.  This will be
     * used internally for access the underlying provider (e.g. getContentResolver().query()).
     * @param providerContext the provider context to be used by this instance
     */
    public void setProviderContext(Context providerContext) {
        mProviderContext = providerContext;
    }

    /**
     * Any UI code that wishes for callback results (on async ops) should register their callback
     * here (typically from onResume()).  Unregistered callbacks will never be called, to prevent
     * problems when the command completes and the activity has already paused or finished.
     * @param listener The callback that may be used in action methods
     */
    public void addResultCallback(Result listener) {
        synchronized (mListeners) {
            listener.setRegistered(true);
            mListeners.add(listener);
        }
    }

    /**
     * Any UI code that no longer wishes for callback results (on async ops) should unregister
     * their callback here (typically from onPause()).  Unregistered callbacks will never be called,
     * to prevent problems when the command completes and the activity has already paused or
     * finished.
     * @param listener The callback that may no longer be used
     */
    public void removeResultCallback(Result listener) {
        synchronized (mListeners) {
            listener.setRegistered(false);
            mListeners.remove(listener);
        }
    }

    public Collection<Result> getResultCallbacksForTest() {
        return mListeners;
    }

    /**
     * Delete all Messages that live in the attachment mailbox
     */
    public void deleteAttachmentMessages() {
        // Note: There should only be one attachment mailbox at present
        ContentResolver resolver = mProviderContext.getContentResolver();
        Cursor c = null;
        try {
            c = resolver.query(Mailbox.CONTENT_URI, EmailContent.ID_PROJECTION,
                    WHERE_TYPE_ATTACHMENT, null, null);
            while (c.moveToNext()) {
                long mailboxId = c.getLong(EmailContent.ID_PROJECTION_COLUMN);
                // Must delete attachments BEFORE messages
                AttachmentProvider.deleteAllMailboxAttachmentFiles(mProviderContext, 0, mailboxId);
                resolver.delete(Message.CONTENT_URI, WHERE_MAILBOX_KEY,
                        new String[] {Long.toString(mailboxId)});
           }
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * Returns the attachment Mailbox (where we store eml attachment Emails), creating one
     * if necessary
     * @return the account's temporary Mailbox
     */
    public Mailbox getAttachmentMailbox() {
        Cursor c = mProviderContext.getContentResolver().query(Mailbox.CONTENT_URI,
                Mailbox.CONTENT_PROJECTION, WHERE_TYPE_ATTACHMENT, null, null);
        try {
            if (c.moveToFirst()) {
                return new Mailbox().restore(c);
            }
        } finally {
            c.close();
        }
        Mailbox m = new Mailbox();
        m.mAccountKey = ATTACHMENT_MAILBOX_ACCOUNT_KEY;
        m.mServerId = ATTACHMENT_MAILBOX_SERVER_ID;
        m.mFlagVisible = false;
        m.mDisplayName = ATTACHMENT_MAILBOX_SERVER_ID;
        m.mSyncInterval = Mailbox.CHECK_INTERVAL_NEVER;
        m.mType = Mailbox.TYPE_ATTACHMENT;
        m.save(mProviderContext);
        return m;
    }

    /**
     * Create a Message from the Uri and store it in the attachment mailbox
     * @param uri the uri containing message content
     * @return the Message or null
     */
    public Message loadMessageFromUri(Uri uri) {
        Mailbox mailbox = getAttachmentMailbox();
        if (mailbox == null) return null;
        try {
            InputStream is = mProviderContext.getContentResolver().openInputStream(uri);
            try {
                // First, create a Pop3Message from the attachment and then parse it
                Pop3Message pop3Message = new Pop3Message(
                        ATTACHMENT_MESSAGE_UID_PREFIX + System.currentTimeMillis(), null);
                pop3Message.parse(is);
                // Now, pull out the header fields
                Message msg = new Message();
                LegacyConversions.updateMessageFields(msg, pop3Message, 0, mailbox.mId);
                // Commit the message to the local store
                msg.save(mProviderContext);
                // Setup the rest of the message and mark it completely loaded
                mLegacyController.copyOneMessageToProvider(pop3Message, msg,
                        Message.FLAG_LOADED_COMPLETE, mProviderContext);
                // Restore the complete message and return it
                return Message.restoreMessageWithId(mProviderContext, msg.mId);
            } catch (MessagingException e) {
            } catch (IOException e) {
            }
        } catch (FileNotFoundException e) {
        }
        return null;
    }

    /**
     * Enable/disable logging for external sync services
     *
     * Generally this should be called by anybody who changes Email.DEBUG
     */
    public void serviceLogging(int debugEnabled) {
        IEmailService service = ExchangeUtils.getExchangeService(mContext, mServiceCallback);
        try {
            service.setLogging(debugEnabled);
        } catch (RemoteException e) {
            // TODO Change exception handling to be consistent with however this method
            // is implemented for other protocols
            Log.d("updateMailboxList", "RemoteException" + e);
        }
    }

    /**
     * Request a remote update of mailboxes for an account.
     */
    public void updateMailboxList(final long accountId) {
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                final IEmailService service = getServiceForAccount(accountId);
                if (service != null) {
                    // Service implementation
                    try {
                        service.updateFolderList(accountId);
                    } catch (RemoteException e) {
                        // TODO Change exception handling to be consistent with however this method
                        // is implemented for other protocols
                        Log.d("updateMailboxList", "RemoteException" + e);
                    }
                } else {
                    // MessagingController implementation
                    mLegacyController.listFolders(accountId, mLegacyListener);
                }
            }
        });
    }

    /**
     * Request a remote update of a mailbox.  For use by the timed service.
     *
     * Functionally this is quite similar to updateMailbox(), but it's a separate API and
     * separate callback in order to keep UI callbacks from affecting the service loop.
     */
    public void serviceCheckMail(final long accountId, final long mailboxId, final long tag) {
        IEmailService service = getServiceForAccount(accountId);
        if (service != null) {
            // Service implementation
//            try {
                // TODO this isn't quite going to work, because we're going to get the
                // generic (UI) callbacks and not the ones we need to restart the ol' service.
                // service.startSync(mailboxId, tag);
            mLegacyListener.checkMailFinished(mContext, accountId, mailboxId, tag);
//            } catch (RemoteException e) {
                // TODO Change exception handling to be consistent with however this method
                // is implemented for other protocols
//                Log.d("updateMailbox", "RemoteException" + e);
//            }
        } else {
            // MessagingController implementation
            Utility.runAsync(new Runnable() {
                public void run() {
                    mLegacyController.checkMail(accountId, tag, mLegacyListener);
                }
            });
        }
    }

    /**
     * Request a remote update of a mailbox.
     *
     * The contract here should be to try and update the headers ASAP, in order to populate
     * a simple message list.  We should also at this point queue up a background task of
     * downloading some/all of the messages in this mailbox, but that should be interruptable.
     */
    public void updateMailbox(final long accountId, final long mailboxId) {

        IEmailService service = getServiceForAccount(accountId);
        if (service != null) {
            // Service implementation
            try {
                service.startSync(mailboxId);
            } catch (RemoteException e) {
                // TODO Change exception handling to be consistent with however this method
                // is implemented for other protocols
                Log.d("updateMailbox", "RemoteException" + e);
            }
        } else {
            // MessagingController implementation
            Utility.runAsync(new Runnable() {
                public void run() {
                    // TODO shouldn't be passing fully-build accounts & mailboxes into APIs
                    Account account =
                        EmailContent.Account.restoreAccountWithId(mProviderContext, accountId);
                    Mailbox mailbox =
                        EmailContent.Mailbox.restoreMailboxWithId(mProviderContext, mailboxId);
                    if (account == null || mailbox == null) {
                        return;
                    }
                    mLegacyController.synchronizeMailbox(account, mailbox, mLegacyListener);
                }
            });
        }
    }

    /**
     * Request that any final work necessary be done, to load a message.
     *
     * Note, this assumes that the caller has already checked message.mFlagLoaded and that
     * additional work is needed.  There is no optimization here for a message which is already
     * loaded.
     *
     * @param messageId the message to load
     * @param callback the Controller callback by which results will be reported
     */
    public void loadMessageForView(final long messageId) {

        // Split here for target type (Service or MessagingController)
        IEmailService service = getServiceForMessage(messageId);
        if (service != null) {
            // There is no service implementation, so we'll just jam the value, log the error,
            // and get out of here.
            Uri uri = ContentUris.withAppendedId(Message.CONTENT_URI, messageId);
            ContentValues cv = new ContentValues();
            cv.put(MessageColumns.FLAG_LOADED, Message.FLAG_LOADED_COMPLETE);
            mProviderContext.getContentResolver().update(uri, cv, null, null);
            Log.d(Email.LOG_TAG, "Unexpected loadMessageForView() for service-based message.");
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadMessageForViewCallback(null, messageId, 100);
                }
            }
        } else {
            // MessagingController implementation
            Utility.runAsync(new Runnable() {
                public void run() {
                    mLegacyController.loadMessageForView(messageId, mLegacyListener);
                }
            });
        }
    }


    /**
     * Saves the message to a mailbox of given type.
     * This is a synchronous operation taking place in the same thread as the caller.
     * Upon return the message.mId is set.
     * @param message the message (must have the mAccountId set).
     * @param mailboxType the mailbox type (e.g. Mailbox.TYPE_DRAFTS).
     */
    public void saveToMailbox(final EmailContent.Message message, final int mailboxType) {
        long accountId = message.mAccountKey;
        long mailboxId = findOrCreateMailboxOfType(accountId, mailboxType);
        message.mMailboxKey = mailboxId;
        message.save(mProviderContext);
    }

    /**
     * @param accountId the account id
     * @param mailboxType the mailbox type (e.g.  EmailContent.Mailbox.TYPE_TRASH)
     * @return the id of the mailbox. The mailbox is created if not existing.
     * Returns Mailbox.NO_MAILBOX if the accountId or mailboxType are negative.
     * Does not validate the input in other ways (e.g. does not verify the existence of account).
     */
    public long findOrCreateMailboxOfType(long accountId, int mailboxType) {
        if (accountId < 0 || mailboxType < 0) {
            return Mailbox.NO_MAILBOX;
        }
        long mailboxId =
            Mailbox.findMailboxOfType(mProviderContext, accountId, mailboxType);
        return mailboxId == Mailbox.NO_MAILBOX ? createMailbox(accountId, mailboxType) : mailboxId;
    }

    /**
     * Returns the server-side name for a specific mailbox.
     *
     * @param mailboxType the mailbox type
     * @return the resource string corresponding to the mailbox type, empty if not found.
     */
    /* package */ String getMailboxServerName(int mailboxType) {
        int resId = -1;
        switch (mailboxType) {
            case Mailbox.TYPE_INBOX:
                resId = R.string.mailbox_name_server_inbox;
                break;
            case Mailbox.TYPE_OUTBOX:
                resId = R.string.mailbox_name_server_outbox;
                break;
            case Mailbox.TYPE_DRAFTS:
                resId = R.string.mailbox_name_server_drafts;
                break;
            case Mailbox.TYPE_TRASH:
                resId = R.string.mailbox_name_server_trash;
                break;
            case Mailbox.TYPE_SENT:
                resId = R.string.mailbox_name_server_sent;
                break;
            case Mailbox.TYPE_JUNK:
                resId = R.string.mailbox_name_server_junk;
                break;
        }
        return resId != -1 ? mContext.getString(resId) : "";
    }

    /**
     * Create a mailbox given the account and mailboxType.
     * TODO: Does this need to be signaled explicitly to the sync engines?
     */
    /* package */ long createMailbox(long accountId, int mailboxType) {
        if (accountId < 0 || mailboxType < 0) {
            String mes = "Invalid arguments " + accountId + ' ' + mailboxType;
            Log.e(Email.LOG_TAG, mes);
            throw new RuntimeException(mes);
        }
        Mailbox box = new Mailbox();
        box.mAccountKey = accountId;
        box.mType = mailboxType;
        box.mSyncInterval = EmailContent.Account.CHECK_INTERVAL_NEVER;
        box.mFlagVisible = true;
        box.mDisplayName = getMailboxServerName(mailboxType);
        box.save(mProviderContext);
        return box.mId;
    }

    /**
     * Send a message:
     * - move the message to Outbox (the message is assumed to be in Drafts).
     * - EAS service will take it from there
     * - trigger send for POP/IMAP
     * @param messageId the id of the message to send
     */
    public void sendMessage(long messageId, long accountId) {
        ContentResolver resolver = mProviderContext.getContentResolver();
        if (accountId == -1) {
            accountId = lookupAccountForMessage(messageId);
        }
        if (accountId == -1) {
            // probably the message was not found
            if (Email.LOGD) {
                Email.log("no account found for message " + messageId);
            }
            return;
        }

        // Move to Outbox
        long outboxId = findOrCreateMailboxOfType(accountId, Mailbox.TYPE_OUTBOX);
        ContentValues cv = new ContentValues();
        cv.put(EmailContent.MessageColumns.MAILBOX_KEY, outboxId);

        // does this need to be SYNCED_CONTENT_URI instead?
        Uri uri = ContentUris.withAppendedId(EmailContent.Message.CONTENT_URI, messageId);
        resolver.update(uri, cv, null, null);

        sendPendingMessages(accountId);
    }

    private void sendPendingMessagesSmtp(long accountId) {
        // for IMAP & POP only, (attempt to) send the message now
        final EmailContent.Account account =
                EmailContent.Account.restoreAccountWithId(mProviderContext, accountId);
        if (account == null) {
            return;
        }
        final long sentboxId = findOrCreateMailboxOfType(accountId, Mailbox.TYPE_SENT);
        Utility.runAsync(new Runnable() {
            public void run() {
                mLegacyController.sendPendingMessages(account, sentboxId, mLegacyListener);
            }
        });
    }

    /**
     * Try to send all pending messages for a given account
     *
     * @param accountId the account for which to send messages
     */
    public void sendPendingMessages(long accountId) {
        // 1. make sure we even have an outbox, exit early if not
        final long outboxId =
            Mailbox.findMailboxOfType(mProviderContext, accountId, Mailbox.TYPE_OUTBOX);
        if (outboxId == Mailbox.NO_MAILBOX) {
            return;
        }

        // 2. dispatch as necessary
        IEmailService service = getServiceForAccount(accountId);
        if (service != null) {
            // Service implementation
            try {
                service.startSync(outboxId);
            } catch (RemoteException e) {
                // TODO Change exception handling to be consistent with however this method
                // is implemented for other protocols
                Log.d("updateMailbox", "RemoteException" + e);
            }
        } else {
            // MessagingController implementation
            sendPendingMessagesSmtp(accountId);
        }
    }

    /**
     * Reset visible limits for all accounts.
     * For each account:
     *   look up limit
     *   write limit into all mailboxes for that account
     */
    public void resetVisibleLimits() {
        Utility.runAsync(new Runnable() {
            public void run() {
                ContentResolver resolver = mProviderContext.getContentResolver();
                Cursor c = null;
                try {
                    c = resolver.query(
                            Account.CONTENT_URI,
                            Account.ID_PROJECTION,
                            null, null, null);
                    while (c.moveToNext()) {
                        long accountId = c.getLong(Account.ID_PROJECTION_COLUMN);
                        Account account = Account.restoreAccountWithId(mProviderContext, accountId);
                        if (account != null) {
                            Store.StoreInfo info = Store.StoreInfo.getStoreInfo(
                                    account.getStoreUri(mProviderContext), mContext);
                            if (info != null && info.mVisibleLimitDefault > 0) {
                                int limit = info.mVisibleLimitDefault;
                                ContentValues cv = new ContentValues();
                                cv.put(MailboxColumns.VISIBLE_LIMIT, limit);
                                resolver.update(Mailbox.CONTENT_URI, cv,
                                        MailboxColumns.ACCOUNT_KEY + "=?",
                                        new String[] { Long.toString(accountId) });
                            }
                        }
                    }
                } finally {
                    if (c != null) {
                        c.close();
                    }
                }
            }
        });
    }

    /**
     * Increase the load count for a given mailbox, and trigger a refresh.  Applies only to
     * IMAP and POP.
     *
     * @param mailboxId the mailbox
     * @param callback
     */
    public void loadMoreMessages(final long mailboxId) {
        Utility.runAsync(new Runnable() {
            public void run() {
                Mailbox mailbox = Mailbox.restoreMailboxWithId(mProviderContext, mailboxId);
                if (mailbox == null) {
                    return;
                }
                Account account = Account.restoreAccountWithId(mProviderContext,
                        mailbox.mAccountKey);
                if (account == null) {
                    return;
                }
                Store.StoreInfo info = Store.StoreInfo.getStoreInfo(
                        account.getStoreUri(mProviderContext), mContext);
                if (info != null && info.mVisibleLimitIncrement > 0) {
                    // Use provider math to increment the field
                    ContentValues cv = new ContentValues();;
                    cv.put(EmailContent.FIELD_COLUMN_NAME, MailboxColumns.VISIBLE_LIMIT);
                    cv.put(EmailContent.ADD_COLUMN_NAME, info.mVisibleLimitIncrement);
                    Uri uri = ContentUris.withAppendedId(Mailbox.ADD_TO_FIELD_URI, mailboxId);
                    mProviderContext.getContentResolver().update(uri, cv, null, null);
                    // Trigger a refresh using the new, longer limit
                    mailbox.mVisibleLimit += info.mVisibleLimitIncrement;
                    mLegacyController.synchronizeMailbox(account, mailbox, mLegacyListener);
                }
            }
        });
    }

    /**
     * @param messageId the id of message
     * @return the accountId corresponding to the given messageId, or -1 if not found.
     */
    private long lookupAccountForMessage(long messageId) {
        ContentResolver resolver = mProviderContext.getContentResolver();
        Cursor c = resolver.query(EmailContent.Message.CONTENT_URI,
                                  MESSAGEID_TO_ACCOUNTID_PROJECTION, EmailContent.RECORD_ID + "=?",
                                  new String[] { Long.toString(messageId) }, null);
        try {
            return c.moveToFirst()
                ? c.getLong(MESSAGEID_TO_ACCOUNTID_COLUMN_ACCOUNTID)
                : -1;
        } finally {
            c.close();
        }
    }

    /**
     * Delete a single attachment entry from the DB given its id.
     * Does not delete any eventual associated files.
     */
    public void deleteAttachment(long attachmentId) {
        ContentResolver resolver = mProviderContext.getContentResolver();
        Uri uri = ContentUris.withAppendedId(Attachment.CONTENT_URI, attachmentId);
        resolver.delete(uri, null, null);
    }

    /**
     * Delete a single message by moving it to the trash, or really delete it if it's already in
     * trash or a draft message.
     *
     * This function has no callback, no result reporting, because the desired outcome
     * is reflected entirely by changes to one or more cursors.
     *
     * @param messageId The id of the message to "delete".
     * @param accountId The id of the message's account, or -1 if not known by caller
     */
    public void deleteMessage(final long messageId, final long accountId) {
        Utility.runAsync(new Runnable() {
            public void run() {
                deleteMessageSync(messageId, accountId);
            }
        });
    }

    /**
     * Synchronous version of {@link #deleteMessage} for tests.
     */
    /* package */ void deleteMessageSync(long messageId, long accountId) {
        // 1. Get the message's account
        Account account = Account.getAccountForMessageId(mProviderContext, messageId);

        // 2. Confirm that there is a trash mailbox available.  If not, create one
        long trashMailboxId = findOrCreateMailboxOfType(account.mId, Mailbox.TYPE_TRASH);

        // 3. Get the message's original mailbox
        Mailbox mailbox = Mailbox.getMailboxForMessageId(mProviderContext, messageId);

        // 4.  Drop non-essential data for the message (e.g. attachment files)
        AttachmentProvider.deleteAllAttachmentFiles(mProviderContext, account.mId,
                messageId);

        Uri uri = ContentUris.withAppendedId(EmailContent.Message.SYNCED_CONTENT_URI,
                messageId);
        ContentResolver resolver = mProviderContext.getContentResolver();

        // 5. Perform "delete" as appropriate
        if ((mailbox.mId == trashMailboxId) || (mailbox.mType == Mailbox.TYPE_DRAFTS)) {
            // 5a. Really delete it
            resolver.delete(uri, null, null);
        } else {
            // 5b. Move to trash
            ContentValues cv = new ContentValues();
            cv.put(EmailContent.MessageColumns.MAILBOX_KEY, trashMailboxId);
            resolver.update(uri, cv, null, null);
        }

        if (isMessagingController(account)) {
            mLegacyController.processPendingActions(account.mId);
        }
    }

    /**
     * Moving messages to another folder
     *
     * This function has no callback, no result reporting, because the desired outcome
     * is reflected entirely by changes to one or more cursors.
     *
     * Note this method assumes all the messages, and the destination mailbox belong to the same
     * account.
     *
     * @param messageIds The IDs of the messages to move
     * @param newMailboxId The id of the folder we're supposed to move the folder to
     * @return the AsyncTask that will execute the move
     */
    public AsyncTask<Void, Void, Void> moveMessage(final long[] messageIds,
            final long newMailboxId) {
        if (messageIds == null || messageIds.length == 0) {
            throw new InvalidParameterException();
        }
        return Utility.runAsync(new Runnable() {
            public void run() {
                Account account = Account.getAccountForMessageId(mProviderContext, messageIds[0]);
                if (account != null) {
                    ContentValues cv = new ContentValues();
                    cv.put(EmailContent.MessageColumns.MAILBOX_KEY, newMailboxId);
                    ContentResolver resolver = mProviderContext.getContentResolver();
                    for (long messageId : messageIds) {
                        Uri uri = ContentUris.withAppendedId(
                                EmailContent.Message.SYNCED_CONTENT_URI, messageId);
                        resolver.update(uri, cv, null, null);
                    }
                    if (isMessagingController(account)) {
                        mLegacyController.processPendingActions(account.mId);
                    }
                }
            }
        });
    }

    /**
     * Set/clear the unread status of a message
     *
     * TODO db ops should not be in this thread. queue it up.
     *
     * @param messageId the message to update
     * @param isRead the new value for the isRead flag
     */
    public void setMessageRead(final long messageId, boolean isRead) {
        ContentValues cv = new ContentValues();
        cv.put(EmailContent.MessageColumns.FLAG_READ, isRead);
        Uri uri = ContentUris.withAppendedId(
                EmailContent.Message.SYNCED_CONTENT_URI, messageId);
        mProviderContext.getContentResolver().update(uri, cv, null, null);

        // Service runs automatically, MessagingController needs a kick
        final Message message = Message.restoreMessageWithId(mProviderContext, messageId);
        if (message == null) {
            return;
        }
        Account account = Account.restoreAccountWithId(mProviderContext, message.mAccountKey);
        if (account == null) {
            return; // isMessagingController returns false for null, but let's make it clear.
        }
        if (isMessagingController(account)) {
            Utility.runAsync(new Runnable() {
                public void run() {
                    mLegacyController.processPendingActions(message.mAccountKey);
                }
            });
        }
    }

    /**
     * Set/clear the favorite status of a message
     *
     * TODO db ops should not be in this thread. queue it up.
     *
     * @param messageId the message to update
     * @param isFavorite the new value for the isFavorite flag
     */
    public void setMessageFavorite(final long messageId, boolean isFavorite) {
        ContentValues cv = new ContentValues();
        cv.put(EmailContent.MessageColumns.FLAG_FAVORITE, isFavorite);
        Uri uri = ContentUris.withAppendedId(
                EmailContent.Message.SYNCED_CONTENT_URI, messageId);
        mProviderContext.getContentResolver().update(uri, cv, null, null);

        // Service runs automatically, MessagingController needs a kick
        final Message message = Message.restoreMessageWithId(mProviderContext, messageId);
        if (message == null) {
            return;
        }
        Account account = Account.restoreAccountWithId(mProviderContext, message.mAccountKey);
        if (account == null) {
            return; // isMessagingController returns false for null, but let's make it clear.
        }
        if (isMessagingController(account)) {
            Utility.runAsync(new Runnable() {
                public void run() {
                    mLegacyController.processPendingActions(message.mAccountKey);
                }
            });
        }
    }

    /**
     * Respond to a meeting invitation.
     *
     * @param messageId the id of the invitation being responded to
     * @param response the code representing the response to the invitation
     */
    public void sendMeetingResponse(final long messageId, final int response) {
         // Split here for target type (Service or MessagingController)
        IEmailService service = getServiceForMessage(messageId);
        if (service != null) {
            // Service implementation
            try {
                service.sendMeetingResponse(messageId, response);
            } catch (RemoteException e) {
                // TODO Change exception handling to be consistent with however this method
                // is implemented for other protocols
                Log.e("onDownloadAttachment", "RemoteException", e);
            }
        }
    }

    /**
     * Request that an attachment be loaded.  It will be stored at a location controlled
     * by the AttachmentProvider.
     *
     * @param attachmentId the attachment to load
     * @param messageId the owner message
     * @param accountId the owner account
     */
    public void loadAttachment(final long attachmentId, final long messageId,
            final long accountId) {

        Attachment attachInfo = Attachment.restoreAttachmentWithId(mProviderContext, attachmentId);
        if (Utility.attachmentExists(mProviderContext, attachInfo)) {
            // The attachment has already been downloaded, so we will just "pretend" to download it
            // This presumably is for POP3 messages
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(null, messageId, attachmentId, 0);
                }
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(null, messageId, attachmentId, 100);
                }
            }
            return;
        }

        // Flag the attachment as needing download at the user's request
        ContentValues cv = new ContentValues();
        cv.put(Attachment.FLAGS, attachInfo.mFlags | Attachment.FLAG_DOWNLOAD_USER_REQUEST);
        attachInfo.update(mContext, cv);
    }

    /**
     * For a given message id, return a service proxy if applicable, or null.
     *
     * @param messageId the message of interest
     * @result service proxy, or null if n/a
     */
    private IEmailService getServiceForMessage(long messageId) {
        // TODO make this more efficient, caching the account, smaller lookup here, etc.
        Message message = Message.restoreMessageWithId(mProviderContext, messageId);
        if (message == null) {
            return null;
        }
        return getServiceForAccount(message.mAccountKey);
    }

    /**
     * For a given account id, return a service proxy if applicable, or null.
     *
     * @param accountId the message of interest
     * @result service proxy, or null if n/a
     */
    private IEmailService getServiceForAccount(long accountId) {
        if (isMessagingController(accountId)) return null;
        return getExchangeEmailService();
    }

    private IEmailService getExchangeEmailService() {
        return ExchangeUtils.getExchangeService(mContext, mServiceCallback);
    }

    /**
     * Simple helper to determine if legacy MessagingController should be used
     */
    public boolean isMessagingController(EmailContent.Account account) {
        if (account == null) return false;
        return isMessagingController(account.mId);
    }

    public boolean isMessagingController(long accountId) {
        Boolean isLegacyController = mLegacyControllerMap.get(accountId);
        if (isLegacyController == null) {
            String protocol = Account.getProtocol(mProviderContext, accountId);
            isLegacyController = ("pop3".equals(protocol) || "imap".equals(protocol));
            mLegacyControllerMap.put(accountId, isLegacyController);
        }
        return isLegacyController;
    }

    /**
     * Delete an account.
     */
    public void deleteAccount(final long accountId) {
        Utility.runAsync(new Runnable() {
            public void run() {
                deleteAccountSync(accountId, mContext);
            }
        });
    }

    /**
     * Delete an account synchronously.  Intended to be used only by unit tests.
     */
    public void deleteAccountSync(long accountId, Context context) {
        try {
            mLegacyControllerMap.remove(accountId);
            // Get the account URI.
            final Account account = Account.restoreAccountWithId(context, accountId);
            if (account == null) {
                return; // Already deleted?
            }

            final String accountUri = account.getStoreUri(context);
            // Delete Remote store at first.
            if (!TextUtils.isEmpty(accountUri)) {
                Store.getInstance(accountUri, context, null).delete();
                // Remove the Store instance from cache.
                Store.removeInstance(accountUri);
            }

            Uri uri = ContentUris.withAppendedId(
                    EmailContent.Account.CONTENT_URI, accountId);
            mContext.getContentResolver().delete(uri, null, null);

            // Update the backup (side copy) of the accounts
            AccountBackupRestore.backupAccounts(context);

            // Release or relax device administration, if relevant
            SecurityPolicy.getInstance(context).reducePolicies();

            Email.setServicesEnabled(context);
        } catch (Exception e) {
            Log.w(Email.LOG_TAG, "Exception while deleting account", e);
        } finally {
            synchronized (mListeners) {
                for (Result l : mListeners) {
                    l.deleteAccountCallback(accountId);
                }
            }
        }
    }

    /**
     * Simple callback for synchronous commands.  For many commands, this can be largely ignored
     * and the result is observed via provider cursors.  The callback will *not* necessarily be
     * made from the UI thread, so you may need further handlers to safely make UI updates.
     */
    public static abstract class Result {
        private volatile boolean mRegistered;

        private void setRegistered(boolean registered) {
            mRegistered = registered;
        }

        protected final boolean isRegistered() {
            return mRegistered;
        }

        /**
         * Callback for updateMailboxList
         *
         * @param result If null, the operation completed without error
         * @param accountId The account being operated on
         * @param progress 0 for "starting", 1..99 for updates (if needed in UI), 100 for complete
         */
        public void updateMailboxListCallback(MessagingException result, long accountId,
                int progress) {
        }

        /**
         * Callback for updateMailbox.  Note:  This looks a lot like checkMailCallback, but
         * it's a separate call used only by UI's, so we can keep things separate.
         *
         * @param result If null, the operation completed without error
         * @param accountId The account being operated on
         * @param mailboxId The mailbox being operated on
         * @param progress 0 for "starting", 1..99 for updates (if needed in UI), 100 for complete
         * @param numNewMessages the number of new messages delivered
         */
        public void updateMailboxCallback(MessagingException result, long accountId,
                long mailboxId, int progress, int numNewMessages) {
        }

        /**
         * Callback for loadMessageForView
         *
         * @param result if null, the attachment completed - if non-null, terminating with failure
         * @param messageId the message which contains the attachment
         * @param progress 0 for "starting", 1..99 for updates (if needed in UI), 100 for complete
         */
        public void loadMessageForViewCallback(MessagingException result, long messageId,
                int progress) {
        }

        /**
         * Callback for loadAttachment
         *
         * @param result if null, the attachment completed - if non-null, terminating with failure
         * @param messageId the message which contains the attachment
         * @param attachmentId the attachment being loaded
         * @param progress 0 for "starting", 1..99 for updates (if needed in UI), 100 for complete
         */
        public void loadAttachmentCallback(MessagingException result, long messageId,
                long attachmentId, int progress) {
        }

        /**
         * Callback for checkmail.  Note:  This looks a lot like updateMailboxCallback, but
         * it's a separate call used only by the automatic checker service, so we can keep
         * things separate.
         *
         * @param result If null, the operation completed without error
         * @param accountId The account being operated on
         * @param mailboxId The mailbox being operated on (may be unknown at start)
         * @param progress 0 for "starting", no updates, 100 for complete
         * @param tag the same tag that was passed to serviceCheckMail()
         */
        public void serviceCheckMailCallback(MessagingException result, long accountId,
                long mailboxId, int progress, long tag) {
        }

        /**
         * Callback for sending pending messages.  This will be called once to start the
         * group, multiple times for messages, and once to complete the group.
         *
         * Unfortunately this callback works differently on SMTP and EAS.
         *
         * On SMTP:
         *
         * First, we get this.
         *  result == null, messageId == -1, progress == 0:     start batch send
         *
         * Then we get these callbacks per message.
         * (Exchange backend may skip "start sending one message".)
         *  result == null, messageId == xx, progress == 0:     start sending one message
         *  result == xxxx, messageId == xx, progress == 0;     failed sending one message
         *
         * Finally we get this.
         *  result == null, messageId == -1, progres == 100;    finish sending batch
         *
         * On EAS: Almost same as above, except:
         *
         * - There's no first ("start batch send") callback.
         * - accountId is always -1.
         *
         * @param result If null, the operation completed without error
         * @param accountId The account being operated on
         * @param messageId The being sent (may be unknown at start)
         * @param progress 0 for "starting", 100 for complete
         */
        public void sendMailCallback(MessagingException result, long accountId,
                long messageId, int progress) {
        }

        /**
         * Callback from {@link Controller#deleteAccount}.
         */
        public void deleteAccountCallback(long accountId) {
        }
    }

    /**
     * Support for receiving callbacks from MessagingController and dealing with UI going
     * out of scope.
     */
    public class LegacyListener extends MessagingListener implements MessageRetrievalListener {
        public LegacyListener(long messageId, long attachmentId) {
            super(messageId, attachmentId);
        }

        public LegacyListener() {
        }

        @Override
        public void listFoldersStarted(long accountId) {
            synchronized (mListeners) {
                for (Result l : mListeners) {
                    l.updateMailboxListCallback(null, accountId, 0);
                }
            }
        }

        @Override
        public void listFoldersFailed(long accountId, String message) {
            synchronized (mListeners) {
                for (Result l : mListeners) {
                    l.updateMailboxListCallback(new MessagingException(message), accountId, 0);
                }
            }
        }

        @Override
        public void listFoldersFinished(long accountId) {
            synchronized (mListeners) {
                for (Result l : mListeners) {
                    l.updateMailboxListCallback(null, accountId, 100);
                }
            }
        }

        @Override
        public void synchronizeMailboxStarted(long accountId, long mailboxId) {
            synchronized (mListeners) {
                for (Result l : mListeners) {
                    l.updateMailboxCallback(null, accountId, mailboxId, 0, 0);
                }
            }
        }

        @Override
        public void synchronizeMailboxFinished(long accountId, long mailboxId,
                int totalMessagesInMailbox, int numNewMessages) {
            synchronized (mListeners) {
                for (Result l : mListeners) {
                    l.updateMailboxCallback(null, accountId, mailboxId, 100, numNewMessages);
                }
            }
        }

        @Override
        public void synchronizeMailboxFailed(long accountId, long mailboxId, Exception e) {
            MessagingException me;
            if (e instanceof MessagingException) {
                me = (MessagingException) e;
            } else {
                me = new MessagingException(e.toString());
            }
            synchronized (mListeners) {
                for (Result l : mListeners) {
                    l.updateMailboxCallback(me, accountId, mailboxId, 0, 0);
                }
            }
        }

        @Override
        public void checkMailStarted(Context context, long accountId, long tag) {
            synchronized (mListeners) {
                for (Result l : mListeners) {
                    l.serviceCheckMailCallback(null, accountId, -1, 0, tag);
                }
            }
        }

        @Override
        public void checkMailFinished(Context context, long accountId, long folderId, long tag) {
            synchronized (mListeners) {
                for (Result l : mListeners) {
                    l.serviceCheckMailCallback(null, accountId, folderId, 100, tag);
                }
            }
        }

        @Override
        public void loadMessageForViewStarted(long messageId) {
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadMessageForViewCallback(null, messageId, 0);
                }
            }
        }

        @Override
        public void loadMessageForViewFinished(long messageId) {
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadMessageForViewCallback(null, messageId, 100);
                }
            }
        }

        @Override
        public void loadMessageForViewFailed(long messageId, String message) {
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadMessageForViewCallback(new MessagingException(message),
                            messageId, 0);
                }
            }
        }

        @Override
        public void loadAttachmentStarted(long accountId, long messageId, long attachmentId,
                boolean requiresDownload) {
            try {
                mCallbackProxy.loadAttachmentStatus(messageId, attachmentId,
                        EmailServiceStatus.IN_PROGRESS, 0);
            } catch (RemoteException e) {
            }
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(null, messageId, attachmentId, 0);
                }
            }
        }

        @Override
        public void loadAttachmentFinished(long accountId, long messageId, long attachmentId) {
            try {
                mCallbackProxy.loadAttachmentStatus(messageId, attachmentId,
                        EmailServiceStatus.SUCCESS, 100);
            } catch (RemoteException e) {
            }
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(null, messageId, attachmentId, 100);
                }
            }
        }

        @Override
        public void loadAttachmentProgress(int progress) {
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(null, messageId, attachmentId, progress);
               }
            }
        }

        @Override
        public void loadAttachmentFailed(long accountId, long messageId, long attachmentId,
                MessagingException me) {
            try {
                // If the cause of the MessagingException is an IOException, we send a status of
                // CONNECTION_ERROR; in this case, AttachmentDownloadService will try again to
                // download the attachment.  Otherwise, the error is considered non-recoverable.
                int status = EmailServiceStatus.ATTACHMENT_NOT_FOUND;
                if (me.getCause() instanceof IOException) {
                    status = EmailServiceStatus.CONNECTION_ERROR;
                }
                mCallbackProxy.loadAttachmentStatus(messageId, attachmentId, status, 0);
            } catch (RemoteException e) {
            }
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(me, messageId, attachmentId, 0);
                }
            }
        }

        @Override
        synchronized public void sendPendingMessagesStarted(long accountId, long messageId) {
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.sendMailCallback(null, accountId, messageId, 0);
                }
            }
        }

        @Override
        synchronized public void sendPendingMessagesCompleted(long accountId) {
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.sendMailCallback(null, accountId, -1, 100);
                }
            }
        }

        @Override
        synchronized public void sendPendingMessagesFailed(long accountId, long messageId,
                Exception reason) {
            MessagingException me;
            if (reason instanceof MessagingException) {
                me = (MessagingException) reason;
            } else {
                me = new MessagingException(reason.toString());
            }
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.sendMailCallback(me, accountId, messageId, 0);
                }
            }
        }

        @Override
        public void messageRetrieved(com.android.email.mail.Message message) {
        }
    }

    /**
     * Service callback for service operations
     */
    private class ServiceCallback extends IEmailServiceCallback.Stub {

        private final static boolean DEBUG_FAIL_DOWNLOADS = false;       // do not check in "true"

        public void loadAttachmentStatus(long messageId, long attachmentId, int statusCode,
                int progress) {
            MessagingException result = mapStatusToException(statusCode);
            switch (statusCode) {
                case EmailServiceStatus.SUCCESS:
                    progress = 100;
                    break;
                case EmailServiceStatus.IN_PROGRESS:
                    if (DEBUG_FAIL_DOWNLOADS && progress > 75) {
                        result = new MessagingException(
                                String.valueOf(EmailServiceStatus.CONNECTION_ERROR));
                    }
                    // discard progress reports that look like sentinels
                    if (progress < 0 || progress >= 100) {
                        return;
                    }
                    break;
            }
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(result, messageId, attachmentId, progress);
                }
            }
        }

        /**
         * Note, this is an incomplete implementation of this callback, because we are
         * not getting things back from Service in quite the same way as from MessagingController.
         * However, this is sufficient for basic "progress=100" notification that message send
         * has just completed.
         */
        public void sendMessageStatus(long messageId, String subject, int statusCode,
                int progress) {
            long accountId = -1;        // This should be in the callback
            MessagingException result = mapStatusToException(statusCode);
            switch (statusCode) {
                case EmailServiceStatus.SUCCESS:
                    progress = 100;
                    break;
                case EmailServiceStatus.IN_PROGRESS:
                    // discard progress reports that look like sentinels
                    if (progress < 0 || progress >= 100) {
                        return;
                    }
                    break;
            }
            synchronized(mListeners) {
                for (Result listener : mListeners) {
                    listener.sendMailCallback(result, accountId, messageId, progress);
                }
            }
        }

        public void syncMailboxListStatus(long accountId, int statusCode, int progress) {
            MessagingException result = mapStatusToException(statusCode);
            switch (statusCode) {
                case EmailServiceStatus.SUCCESS:
                    progress = 100;
                    break;
                case EmailServiceStatus.IN_PROGRESS:
                    // discard progress reports that look like sentinels
                    if (progress < 0 || progress >= 100) {
                        return;
                    }
                    break;
            }
            synchronized(mListeners) {
                for (Result listener : mListeners) {
                    listener.updateMailboxListCallback(result, accountId, progress);
                }
            }
        }

        public void syncMailboxStatus(long mailboxId, int statusCode, int progress) {
            MessagingException result = mapStatusToException(statusCode);
            switch (statusCode) {
                case EmailServiceStatus.SUCCESS:
                    progress = 100;
                    break;
                case EmailServiceStatus.IN_PROGRESS:
                    // discard progress reports that look like sentinels
                    if (progress < 0 || progress >= 100) {
                        return;
                    }
                    break;
            }
            // TODO should pass this back instead of looking it up here
            Mailbox mbx = Mailbox.restoreMailboxWithId(mProviderContext, mailboxId);
            // The mailbox could have disappeared if the server commanded it
            if (mbx == null) return;
            long accountId = mbx.mAccountKey;
            synchronized(mListeners) {
                for (Result listener : mListeners) {
                    listener.updateMailboxCallback(result, accountId, mailboxId, progress, 0);
                }
            }
        }

        private MessagingException mapStatusToException(int statusCode) {
            switch (statusCode) {
                case EmailServiceStatus.SUCCESS:
                case EmailServiceStatus.IN_PROGRESS:
                    return null;

                case EmailServiceStatus.LOGIN_FAILED:
                    return new AuthenticationFailedException("");

                case EmailServiceStatus.CONNECTION_ERROR:
                    return new MessagingException(MessagingException.IOERROR);

                case EmailServiceStatus.SECURITY_FAILURE:
                    return new MessagingException(MessagingException.SECURITY_POLICIES_REQUIRED);

                case EmailServiceStatus.MESSAGE_NOT_FOUND:
                case EmailServiceStatus.ATTACHMENT_NOT_FOUND:
                case EmailServiceStatus.FOLDER_NOT_DELETED:
                case EmailServiceStatus.FOLDER_NOT_RENAMED:
                case EmailServiceStatus.FOLDER_NOT_CREATED:
                case EmailServiceStatus.REMOTE_EXCEPTION:
                    // TODO: define exception code(s) & UI string(s) for server-side errors
                default:
                    return new MessagingException(String.valueOf(statusCode));
            }
        }
    }

    private interface ServiceCallbackWrapper {
        public void call(IEmailServiceCallback cb) throws RemoteException;
    }

    /**
     * Proxy that can be used to broadcast service callbacks; we currently use this only for
     * loadAttachment callbacks
     */
    private final IEmailServiceCallback.Stub mCallbackProxy =
        new IEmailServiceCallback.Stub() {

        /**
         * Broadcast a callback to the everyone that's registered
         *
         * @param wrapper the ServiceCallbackWrapper used in the broadcast
         */
        private synchronized void broadcastCallback(ServiceCallbackWrapper wrapper) {
            if (sCallbackList != null) {
                // Call everyone on our callback list
                // Exceptions can be safely ignored
                int count = sCallbackList.beginBroadcast();
                for (int i = 0; i < count; i++) {
                    try {
                        wrapper.call(sCallbackList.getBroadcastItem(i));
                    } catch (RemoteException e) {
                    }
                }
                sCallbackList.finishBroadcast();
            }
        }

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
        public void sendMessageStatus(long messageId, String subject, int statusCode, int progress)
                throws RemoteException {
        }

        @Override
        public void syncMailboxListStatus(long accountId, int statusCode, int progress)
                throws RemoteException {
        }

        @Override
        public void syncMailboxStatus(long mailboxId, int statusCode, int progress)
                throws RemoteException {
        }
    };

    public static class ControllerService extends Service {
        /**
         * Create our EmailService implementation here.  For now, only loadAttachment is supported; the
         * intention, however, is to move more functionality to the service interface
         */
        private final IEmailService.Stub mBinder = new IEmailService.Stub() {

            public Bundle validate(String protocol, String host, String userName, String password,
                    int port, boolean ssl, boolean trustCertificates) throws RemoteException {
                return null;
            }

            public Bundle autoDiscover(String userName, String password) throws RemoteException {
                return null;
            }

            public void startSync(long mailboxId) throws RemoteException {
            }

            public void stopSync(long mailboxId) throws RemoteException {
            }

            public void loadAttachment(long attachmentId, String destinationFile,
                    String contentUriString) throws RemoteException {
                if (Email.DEBUG) {
                    Log.d(TAG, "loadAttachment: " + attachmentId + " to " + destinationFile);
                }
                Attachment att = Attachment.restoreAttachmentWithId(ControllerService.this,
                        attachmentId);
                if (att != null) {
                    Message msg = Message.restoreMessageWithId(ControllerService.this,
                            att.mMessageKey);
                    if (msg != null) {
                        // If the message is a forward and the attachment needs downloading, we need
                        // to retrieve the message from the source, rather than from the message
                        // itself
                        if ((msg.mFlags & Message.FLAG_TYPE_FORWARD) != 0) {
                            String[] cols = Utility.getRowColumns(ControllerService.this,
                                    Body.CONTENT_URI, BODY_SOURCE_KEY_PROJECTION, WHERE_MESSAGE_KEY,
                                    new String[] {Long.toString(msg.mId)});
                            if (cols != null) {
                                msg = Message.restoreMessageWithId(ControllerService.this,
                                        Long.parseLong(cols[BODY_SOURCE_KEY_COLUMN]));
                                if (msg == null) {
                                    // TODO: We can try restoring from the deleted table at this point...
                                    return;
                                }
                            }
                        }
                        MessagingController legacyController = sInstance.mLegacyController;
                        LegacyListener legacyListener = sInstance.mLegacyListener;
                        legacyController.loadAttachment(msg.mAccountKey, msg.mId, msg.mMailboxKey,
                                attachmentId, legacyListener);
                    }
                }
            }

            public void updateFolderList(long accountId) throws RemoteException {
            }

            public void hostChanged(long accountId) throws RemoteException {
            }

            public void setLogging(int on) throws RemoteException {
            }

            public void sendMeetingResponse(long messageId, int response) throws RemoteException {
            }

            public void loadMore(long messageId) throws RemoteException {
            }

            // The following three methods are not implemented in this version
            public boolean createFolder(long accountId, String name) throws RemoteException {
                return false;
            }

            public boolean deleteFolder(long accountId, String name) throws RemoteException {
                return false;
            }

            public boolean renameFolder(long accountId, String oldName, String newName)
                    throws RemoteException {
                return false;
            }

            public void setCallback(IEmailServiceCallback cb) throws RemoteException {
                sCallbackList.register(cb);
            }

            public void moveMessage(long messageId, long mailboxId) throws RemoteException {
            }

            public void deleteAccountPIMData(long accountId) throws RemoteException {
            }
        };

        @Override
        public IBinder onBind(Intent intent) {
            return mBinder;
        }
    }
}
