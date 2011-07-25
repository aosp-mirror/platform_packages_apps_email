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

import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

import com.android.email.mail.store.Pop3Store.Pop3Message;
import com.android.email.provider.AccountBackupRestore;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.MailService;
import com.android.emailcommon.Api;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.Folder.MessageRetrievalListener;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceStatus;
import com.android.emailcommon.service.IEmailService;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.google.common.annotations.VisibleForTesting;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
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
    private static final long GLOBAL_MAILBOX_ACCOUNT_KEY = 0;
    /*package*/ static final String ATTACHMENT_MAILBOX_SERVER_ID = "__attachment_mailbox__";
    /*package*/ static final String ATTACHMENT_MESSAGE_UID_PREFIX = "__attachment_message__";
    /*package*/ static final String SEARCH_MAILBOX_SERVER_ID = "__search_mailbox__";
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

    private static final String MAILBOXES_FOR_ACCOUNT_SELECTION = MailboxColumns.ACCOUNT_KEY + "=?";
    private static final String MAILBOXES_FOR_ACCOUNT_EXCEPT_ACCOUNT_MAILBOX_SELECTION =
        MAILBOXES_FOR_ACCOUNT_SELECTION + " AND " + MailboxColumns.TYPE + "!=" +
        Mailbox.TYPE_EAS_ACCOUNT_MAILBOX;
    private static final String MESSAGES_FOR_ACCOUNT_SELECTION = MessageColumns.ACCOUNT_KEY + "=?";

    // Service callbacks as set up via setCallback
    private static RemoteCallbackList<IEmailServiceCallback> sCallbackList =
        new RemoteCallbackList<IEmailServiceCallback>();

    private volatile boolean mInUnitTests = false;

    protected Controller(Context _context) {
        mContext = _context.getApplicationContext();
        mProviderContext = _context;
        mLegacyController = MessagingController.getInstance(mProviderContext, this);
        mLegacyController.addListener(mLegacyListener);
    }

    /**
     * Mark this controller as being in use in a unit test.
     * This is a kludge vs having proper mocks and dependency injection; since the Controller is a
     * global singleton there isn't much else we can do.
     */
    public void markForTest(boolean inUnitTests) {
        mInUnitTests = inUnitTests;
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
                AttachmentUtilities.deleteAllMailboxAttachmentFiles(mProviderContext, 0,
                        mailboxId);
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
     * Get a mailbox based on a sqlite WHERE clause
     */
    private Mailbox getGlobalMailboxWhere(String where) {
        Cursor c = mProviderContext.getContentResolver().query(Mailbox.CONTENT_URI,
                Mailbox.CONTENT_PROJECTION, where, null, null);
        try {
            if (c.moveToFirst()) {
                Mailbox m = new Mailbox();
                m.restore(c);
                return m;
            }
        } finally {
            c.close();
        }
        return null;
    }

    /**
     * Returns the attachment mailbox (where we store eml attachment Emails), creating one
     * if necessary
     * @return the global attachment mailbox
     */
    public Mailbox getAttachmentMailbox() {
        Mailbox m = getGlobalMailboxWhere(WHERE_TYPE_ATTACHMENT);
        if (m == null) {
            m = new Mailbox();
            m.mAccountKey = GLOBAL_MAILBOX_ACCOUNT_KEY;
            m.mServerId = ATTACHMENT_MAILBOX_SERVER_ID;
            m.mFlagVisible = false;
            m.mDisplayName = ATTACHMENT_MAILBOX_SERVER_ID;
            m.mSyncInterval = Mailbox.CHECK_INTERVAL_NEVER;
            m.mType = Mailbox.TYPE_ATTACHMENT;
            m.save(mProviderContext);
        }
        return m;
    }

    /**
     * Returns the search mailbox for the specified account, creating one if necessary
     * @return the search mailbox for the passed in account
     */
    public Mailbox getSearchMailbox(long accountId) {
        Mailbox m = Mailbox.restoreMailboxOfType(mContext, accountId, Mailbox.TYPE_SEARCH);
        if (m == null) {
            m = new Mailbox();
            m.mAccountKey = accountId;
            m.mServerId = SEARCH_MAILBOX_SERVER_ID;
            m.mFlagVisible = false;
            m.mDisplayName = SEARCH_MAILBOX_SERVER_ID;
            m.mSyncInterval = Mailbox.CHECK_INTERVAL_NEVER;
            m.mType = Mailbox.TYPE_SEARCH;
            m.mFlags = Mailbox.FLAG_HOLDS_MAIL;
            m.mParentKey = Mailbox.NO_MAILBOX;
            m.save(mProviderContext);
        }
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
     * Set logging flags for external sync services
     *
     * Generally this should be called by anybody who changes Email.DEBUG
     */
    public void serviceLogging(int debugFlags) {
        IEmailService service = EmailServiceUtils.getExchangeService(mContext, mServiceCallback);
        try {
            service.setLogging(debugFlags);
        } catch (RemoteException e) {
            // TODO Change exception handling to be consistent with however this method
            // is implemented for other protocols
            Log.d("setLogging", "RemoteException" + e);
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
    public void updateMailbox(final long accountId, final long mailboxId, boolean userRequest) {

        IEmailService service = getServiceForAccount(accountId);
        if (service != null) {
           try {
                service.startSync(mailboxId, userRequest);
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
                        Account.restoreAccountWithId(mProviderContext, accountId);
                    Mailbox mailbox =
                        Mailbox.restoreMailboxWithId(mProviderContext, mailboxId);
                    if (account == null || mailbox == null ||
                            mailbox.mType == Mailbox.TYPE_SEARCH) {
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
            Log.d(Logging.LOG_TAG, "Unexpected loadMessageForView() for service-based message.");
            final long accountId = Account.getAccountIdForMessageId(mProviderContext, messageId);
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadMessageForViewCallback(null, accountId, messageId, 100);
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
     * Look for a specific system mailbox, creating it if necessary, and return the mailbox id.
     * This is a blocking operation and should not be called from the UI thread.
     *
     * Synchronized so multiple threads can call it (and not risk creating duplicate boxes).
     *
     * @param accountId the account id
     * @param mailboxType the mailbox type (e.g.  EmailContent.Mailbox.TYPE_TRASH)
     * @return the id of the mailbox. The mailbox is created if not existing.
     * Returns Mailbox.NO_MAILBOX if the accountId or mailboxType are negative.
     * Does not validate the input in other ways (e.g. does not verify the existence of account).
     */
    public synchronized long findOrCreateMailboxOfType(long accountId, int mailboxType) {
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
     * @return the resource string corresponding to the mailbox type, empty if not found.
     */
    public static String getMailboxServerName(Context context, int mailboxType) {
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
        return resId != -1 ? context.getString(resId) : "";
    }

    /**
     * Create a mailbox given the account and mailboxType.
     * TODO: Does this need to be signaled explicitly to the sync engines?
     */
    @VisibleForTesting
    long createMailbox(long accountId, int mailboxType) {
        if (accountId < 0 || mailboxType < 0) {
            String mes = "Invalid arguments " + accountId + ' ' + mailboxType;
            Log.e(Logging.LOG_TAG, mes);
            throw new RuntimeException(mes);
        }
        Mailbox box = Mailbox.newSystemMailbox(
                accountId, mailboxType, getMailboxServerName(mContext, mailboxType));
        box.save(mProviderContext);
        return box.mId;
    }

    /**
     * Send a message:
     * - move the message to Outbox (the message is assumed to be in Drafts).
     * - EAS service will take it from there
     * - mark reply/forward state in source message (if any)
     * - trigger send for POP/IMAP
     * @param message the fully populated Message (usually retrieved from the Draft box). Note that
     *     all transient fields (e.g. Body related fields) are also expected to be fully loaded
     */
    public void sendMessage(Message message) {
        ContentResolver resolver = mProviderContext.getContentResolver();
        long accountId = message.mAccountKey;
        long messageId = message.mId;
        if (accountId == Account.NO_ACCOUNT) {
            accountId = lookupAccountForMessage(messageId);
        }
        if (accountId == Account.NO_ACCOUNT) {
            // probably the message was not found
            if (Logging.LOGD) {
                Email.log("no account found for message " + messageId);
            }
            return;
        }

        // Move to Outbox
        long outboxId = findOrCreateMailboxOfType(accountId, Mailbox.TYPE_OUTBOX);
        ContentValues cv = new ContentValues();
        cv.put(EmailContent.MessageColumns.MAILBOX_KEY, outboxId);

        // does this need to be SYNCED_CONTENT_URI instead?
        Uri uri = ContentUris.withAppendedId(Message.CONTENT_URI, messageId);
        resolver.update(uri, cv, null, null);

        // If this is a reply/forward, indicate it as such on the source.
        long sourceKey = message.mSourceKey;
        if (sourceKey != Message.NO_MESSAGE) {
            boolean isReply = (message.mFlags & Message.FLAG_TYPE_REPLY) != 0;
            int flagUpdate = isReply ? Message.FLAG_REPLIED_TO : Message.FLAG_FORWARDED;
            setMessageAnsweredOrForwarded(sourceKey, flagUpdate);
        }

        sendPendingMessages(accountId);
    }

    private void sendPendingMessagesSmtp(long accountId) {
        // for IMAP & POP only, (attempt to) send the message now
        final Account account =
                Account.restoreAccountWithId(mProviderContext, accountId);
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
                service.startSync(outboxId, false);
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
                        String protocol = Account.getProtocol(mProviderContext, accountId);
                        if (!HostAuth.SCHEME_EAS.equals(protocol)) {
                            ContentValues cv = new ContentValues();
                            cv.put(MailboxColumns.VISIBLE_LIMIT, Email.VISIBLE_LIMIT_DEFAULT);
                            resolver.update(Mailbox.CONTENT_URI, cv,
                                    MailboxColumns.ACCOUNT_KEY + "=?",
                                    new String[] { Long.toString(accountId) });
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
     * IMAP and POP mailboxes, with the exception of the EAS search mailbox.
     *
     * @param mailboxId the mailbox
     */
    public void loadMoreMessages(final long mailboxId) {
        EmailAsyncTask.runAsyncParallel(new Runnable() {
            public void run() {
                Mailbox mailbox = Mailbox.restoreMailboxWithId(mProviderContext, mailboxId);
                if (mailbox == null) {
                    return;
                }
                if (mailbox.mType == Mailbox.TYPE_SEARCH) {
                    try {
                        searchMore(mailbox.mAccountKey);
                    } catch (MessagingException e) {
                        // Nothing to be done
                    }
                    return;
                }
                Account account = Account.restoreAccountWithId(mProviderContext,
                        mailbox.mAccountKey);
                if (account == null) {
                    return;
                }
                // Use provider math to increment the field
                ContentValues cv = new ContentValues();;
                cv.put(EmailContent.FIELD_COLUMN_NAME, MailboxColumns.VISIBLE_LIMIT);
                cv.put(EmailContent.ADD_COLUMN_NAME, Email.VISIBLE_LIMIT_INCREMENT);
                Uri uri = ContentUris.withAppendedId(Mailbox.ADD_TO_FIELD_URI, mailboxId);
                mProviderContext.getContentResolver().update(uri, cv, null, null);
                // Trigger a refresh using the new, longer limit
                mailbox.mVisibleLimit += Email.VISIBLE_LIMIT_INCREMENT;
                mLegacyController.synchronizeMailbox(account, mailbox, mLegacyListener);
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
     * Async version of {@link #deleteMessageSync}.
     */
    public void deleteMessage(final long messageId) {
        EmailAsyncTask.runAsyncParallel(new Runnable() {
            public void run() {
                deleteMessageSync(messageId);
            }
        });
    }

    /**
     * Batch & async version of {@link #deleteMessageSync}.
     */
    public void deleteMessages(final long[] messageIds) {
        if (messageIds == null || messageIds.length == 0) {
            throw new IllegalArgumentException();
        }
        EmailAsyncTask.runAsyncParallel(new Runnable() {
            public void run() {
                for (long messageId: messageIds) {
                    deleteMessageSync(messageId);
                }
            }
        });
    }

    /**
     * Delete a single message by moving it to the trash, or really delete it if it's already in
     * trash or a draft message.
     *
     * This function has no callback, no result reporting, because the desired outcome
     * is reflected entirely by changes to one or more cursors.
     *
     * @param messageId The id of the message to "delete".
     */
    /* package */ void deleteMessageSync(long messageId) {
        // 1. Get the message's account
        Account account = Account.getAccountForMessageId(mProviderContext, messageId);

        if (account == null) return;

        // 2. Confirm that there is a trash mailbox available.  If not, create one
        long trashMailboxId = findOrCreateMailboxOfType(account.mId, Mailbox.TYPE_TRASH);

        // 3. Get the message's original mailbox
        Mailbox mailbox = Mailbox.getMailboxForMessageId(mProviderContext, messageId);

        if (mailbox == null) return;

        // 4.  Drop non-essential data for the message (e.g. attachment files)
        AttachmentUtilities.deleteAllAttachmentFiles(mProviderContext, account.mId,
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
     * Moves messages to a new mailbox.
     *
     * This function has no callback, no result reporting, because the desired outcome
     * is reflected entirely by changes to one or more cursors.
     *
     * Note this method assumes all of the given message and mailbox IDs belong to the same
     * account.
     *
     * @param messageIds IDs of the messages that are to be moved
     * @param newMailboxId ID of the new mailbox that the messages will be moved to
     * @return an asynchronous task that executes the move (for testing only)
     */
    public EmailAsyncTask<Void, Void, Void> moveMessages(final long[] messageIds,
            final long newMailboxId) {
        if (messageIds == null || messageIds.length == 0) {
            throw new IllegalArgumentException();
        }
        return EmailAsyncTask.runAsyncParallel(new Runnable() {
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
     * @param messageId the message to update
     * @param isRead the new value for the isRead flag
     */
    public void setMessageReadSync(long messageId, boolean isRead) {
        setMessageBooleanSync(messageId, EmailContent.MessageColumns.FLAG_READ, isRead);
    }

    /**
     * Set/clear the unread status of a message from UI thread
     *
     * @param messageId the message to update
     * @param isRead the new value for the isRead flag
     * @return the EmailAsyncTask created
     */
    public EmailAsyncTask<Void, Void, Void> setMessageRead(final long messageId,
            final boolean isRead) {
        return EmailAsyncTask.runAsyncParallel(new Runnable() {
            @Override
            public void run() {
                setMessageBooleanSync(messageId, EmailContent.MessageColumns.FLAG_READ, isRead);
            }});
    }

    /**
     * Update a message record and ping MessagingController, if necessary
     *
     * @param messageId the message to update
     * @param cv the ContentValues used in the update
     */
    private void updateMessageSync(long messageId, ContentValues cv) {
        Uri uri = ContentUris.withAppendedId(EmailContent.Message.SYNCED_CONTENT_URI, messageId);
        mProviderContext.getContentResolver().update(uri, cv, null, null);

        // Service runs automatically, MessagingController needs a kick
        long accountId = Account.getAccountIdForMessageId(mProviderContext, messageId);
        if (accountId == Account.NO_ACCOUNT) return;
        if (isMessagingController(accountId)) {
            mLegacyController.processPendingActions(accountId);
        }
    }

    /**
     * Set the answered status of a message
     *
     * @param messageId the message to update
     * @return the AsyncTask that will execute the changes (for testing only)
     */
    public void setMessageAnsweredOrForwarded(final long messageId,
            final int flag) {
        EmailAsyncTask.runAsyncParallel(new Runnable() {
            public void run() {
                Message msg = Message.restoreMessageWithId(mProviderContext, messageId);
                if (msg == null) {
                    Log.w(Logging.LOG_TAG, "Unable to find source message for a reply/forward");
                    return;
                }
                ContentValues cv = new ContentValues();
                cv.put(MessageColumns.FLAGS, msg.mFlags | flag);
                updateMessageSync(messageId, cv);
            }
        });
    }

    /**
     * Set/clear the favorite status of a message from UI thread
     *
     * @param messageId the message to update
     * @param isFavorite the new value for the isFavorite flag
     * @return the EmailAsyncTask created
     */
    public EmailAsyncTask<Void, Void, Void> setMessageFavorite(final long messageId,
            final boolean isFavorite) {
        return EmailAsyncTask.runAsyncParallel(new Runnable() {
            @Override
            public void run() {
                setMessageBooleanSync(messageId, EmailContent.MessageColumns.FLAG_FAVORITE,
                        isFavorite);
            }});
    }
    /**
     * Set/clear the favorite status of a message
     *
     * @param messageId the message to update
     * @param isFavorite the new value for the isFavorite flag
     */
    public void setMessageFavoriteSync(long messageId, boolean isFavorite) {
        setMessageBooleanSync(messageId, EmailContent.MessageColumns.FLAG_FAVORITE, isFavorite);
    }

    /**
     * Set/clear boolean columns of a message
     *
     * @param messageId the message to update
     * @param columnName the column to update
     * @param columnValue the new value for the column
     */
    private void setMessageBooleanSync(long messageId, String columnName, boolean columnValue) {
        ContentValues cv = new ContentValues();
        cv.put(columnName, columnValue);
        updateMessageSync(messageId, cv);
    }


    private static final HashMap<Long, SearchParams> sSearchParamsMap =
        new HashMap<Long, SearchParams>();

    public void searchMore(long accountId) throws MessagingException {
        SearchParams params = sSearchParamsMap.get(accountId);
        if (params == null) return;
        params.mOffset += params.mLimit;
        searchMessages(accountId, params);
    }

    /**
     * Search for messages on the (IMAP) server; do not call this on the UI thread!
     * @param accountId the id of the account to be searched
     * @param searchParams the parameters for this search
     * @throws MessagingException
     */
    public int searchMessages(final long accountId, final SearchParams searchParams)
            throws MessagingException {
        // Find/create our search mailbox
        Mailbox searchMailbox = getSearchMailbox(accountId);
        if (searchMailbox == null) return 0;
        final long searchMailboxId = searchMailbox.mId;
        // Save this away (per account)
        sSearchParamsMap.put(accountId, searchParams);

        if (searchParams.mOffset == 0) {
            // Delete existing contents of search mailbox
            ContentResolver resolver = mContext.getContentResolver();
            resolver.delete(Message.CONTENT_URI, Message.MAILBOX_KEY + "=" + searchMailboxId,
                    null);
            ContentValues cv = new ContentValues();
            // For now, use the actual query as the name of the mailbox
            cv.put(Mailbox.DISPLAY_NAME, searchParams.mFilter);
            resolver.update(ContentUris.withAppendedId(Mailbox.CONTENT_URI, searchMailboxId),
                    cv, null, null);
        }

        IEmailService service = getServiceForAccount(accountId);
        if (service != null) {
            // Service implementation
            try {
                return service.searchMessages(accountId, searchParams, searchMailboxId);
            } catch (RemoteException e) {
                // TODO Change exception handling to be consistent with however this method
                // is implemented for other protocols
                Log.e("searchMessages", "RemoteException", e);
                return 0;
            }
        } else {
            // This is the actual mailbox we'll be searching
            Mailbox actualMailbox = Mailbox.restoreMailboxWithId(mContext, searchParams.mMailboxId);
            if (actualMailbox == null) {
                Log.e(Logging.LOG_TAG, "Unable to find mailbox " + searchParams.mMailboxId
                        + " to search in with " + searchParams);
                return 0;
            }
            // Do the search
            if (Email.DEBUG) {
                Log.d(Logging.LOG_TAG, "Search: " + searchParams.mFilter);
            }
            return mLegacyController.searchMailbox(accountId, searchParams, searchMailboxId);
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
        if (attachInfo == null) {
            return;
        }

        if (Utility.attachmentExists(mProviderContext, attachInfo)) {
            // The attachment has already been downloaded, so we will just "pretend" to download it
            // This presumably is for POP3 messages
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(null, accountId, messageId, attachmentId, 0);
                }
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(null, accountId, messageId, attachmentId, 100);
                }
            }
            return;
        }

        // Flag the attachment as needing download at the user's request
        ContentValues cv = new ContentValues();
        cv.put(Attachment.FLAGS, attachInfo.mFlags | Attachment.FLAG_DOWNLOAD_USER_REQUEST);
        attachInfo.update(mProviderContext, cv);
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
        return EmailServiceUtils.getExchangeService(mContext, mServiceCallback);
    }

    /**
     * Simple helper to determine if legacy MessagingController should be used
     */
    public boolean isMessagingController(Account account) {
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
        EmailAsyncTask.runAsyncParallel(new Runnable() {
            @Override
            public void run() {
                deleteAccountSync(accountId, mProviderContext);
            }
        });
    }

    /**
     * Delete an account synchronously.
     */
    public void deleteAccountSync(long accountId, Context context) {
        try {
            mLegacyControllerMap.remove(accountId);
            // Get the account URI.
            final Account account = Account.restoreAccountWithId(context, accountId);
            if (account == null) {
                return; // Already deleted?
            }

            // Delete account data, attachments, PIM data, etc.
            deleteSyncedDataSync(accountId);

            // Now delete the account itself
            Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, accountId);
            context.getContentResolver().delete(uri, null, null);

            // For unit tests, don't run backup, security, and ui pieces.
            if (mInUnitTests) {
                return;
            }

            // Clean up
            AccountBackupRestore.backup(context);
            SecurityPolicy.getInstance(context).reducePolicies();
            Email.setServicesEnabledSync(context);
            Email.setNotifyUiAccountsChanged(true);
            MailService.actionReschedule(context);
        } catch (Exception e) {
            Log.w(Logging.LOG_TAG, "Exception while deleting account", e);
        }
    }

    /**
     * Delete all synced data, but don't delete the actual account.  This is used when security
     * policy requirements are not met, and we don't want to reveal any synced data, but we do
     * wish to keep the account configured (e.g. to accept remote wipe commands).
     *
     * The only mailbox not deleted is the account mailbox (if any)
     * Also, clear the sync keys on the remaining account, since the data is gone.
     *
     * SYNCHRONOUS - do not call from UI thread.
     *
     * @param accountId The account to wipe.
     */
    public void deleteSyncedDataSync(long accountId) {
        try {
            // Delete synced attachments
            AttachmentUtilities.deleteAllAccountAttachmentFiles(mProviderContext,
                    accountId);

            // Delete synced email, leaving only an empty inbox.  We do this in two phases:
            // 1. Delete all non-inbox mailboxes (which will delete all of their messages)
            // 2. Delete all remaining messages (which will be the inbox messages)
            ContentResolver resolver = mProviderContext.getContentResolver();
            String[] accountIdArgs = new String[] { Long.toString(accountId) };
            resolver.delete(Mailbox.CONTENT_URI,
                    MAILBOXES_FOR_ACCOUNT_EXCEPT_ACCOUNT_MAILBOX_SELECTION,
                    accountIdArgs);
            resolver.delete(Message.CONTENT_URI, MESSAGES_FOR_ACCOUNT_SELECTION, accountIdArgs);

            // Delete sync keys on remaining items
            ContentValues cv = new ContentValues();
            cv.putNull(Account.SYNC_KEY);
            resolver.update(Account.CONTENT_URI, cv, Account.ID_SELECTION, accountIdArgs);
            cv.clear();
            cv.putNull(Mailbox.SYNC_KEY);
            resolver.update(Mailbox.CONTENT_URI, cv,
                    MAILBOXES_FOR_ACCOUNT_SELECTION, accountIdArgs);

            // Delete PIM data (contacts, calendar), stop syncs, etc. if applicable
            IEmailService service = getServiceForAccount(accountId);
            if (service != null) {
                service.deleteAccountPIMData(accountId);
            }
        } catch (Exception e) {
            Log.w(Logging.LOG_TAG, "Exception while deleting account synced data", e);
        }
    }

    /**
     * Simple callback for synchronous commands.  For many commands, this can be largely ignored
     * and the result is observed via provider cursors.  The callback will *not* necessarily be
     * made from the UI thread, so you may need further handlers to safely make UI updates.
     */
    public static abstract class Result {
        private volatile boolean mRegistered;

        protected void setRegistered(boolean registered) {
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
                long mailboxId, int progress, int numNewMessages, ArrayList<Long> addedMessages) {
        }

        /**
         * Callback for loadMessageForView
         *
         * @param result if null, the attachment completed - if non-null, terminating with failure
         * @param messageId the message which contains the attachment
         * @param progress 0 for "starting", 1..99 for updates (if needed in UI), 100 for complete
         */
        public void loadMessageForViewCallback(MessagingException result, long accountId,
                long messageId, int progress) {
        }

        /**
         * Callback for loadAttachment
         *
         * @param result if null, the attachment completed - if non-null, terminating with failure
         * @param messageId the message which contains the attachment
         * @param attachmentId the attachment being loaded
         * @param progress 0 for "starting", 1..99 for updates (if needed in UI), 100 for complete
         */
        public void loadAttachmentCallback(MessagingException result, long accountId,
                long messageId, long attachmentId, int progress) {
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
    }

    /**
     * Bridge to intercept {@link MessageRetrievalListener#loadAttachmentProgress} and
     * pass down to {@link Result}.
     */
    public class MessageRetrievalListenerBridge implements MessageRetrievalListener {
        private final long mMessageId;
        private final long mAttachmentId;
        private final long mAccountId;

        public MessageRetrievalListenerBridge(long messageId, long attachmentId) {
            mMessageId = messageId;
            mAttachmentId = attachmentId;
            mAccountId = Account.getAccountIdForMessageId(mProviderContext, mMessageId);
        }

        @Override
        public void loadAttachmentProgress(int progress) {
              synchronized (mListeners) {
                  for (Result listener : mListeners) {
                      listener.loadAttachmentCallback(null, mAccountId, mMessageId, mAttachmentId,
                              progress);
                 }
              }
        }

        @Override
        public void messageRetrieved(com.android.emailcommon.mail.Message message) {
        }
    }

    /**
     * Support for receiving callbacks from MessagingController and dealing with UI going
     * out of scope.
     */
    public class LegacyListener extends MessagingListener {
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
                    l.updateMailboxCallback(null, accountId, mailboxId, 0, 0, null);
                }
            }
        }

        @Override
        public void synchronizeMailboxFinished(long accountId, long mailboxId,
                int totalMessagesInMailbox, int numNewMessages, ArrayList<Long> addedMessages) {
            synchronized (mListeners) {
                for (Result l : mListeners) {
                    l.updateMailboxCallback(null, accountId, mailboxId, 100, numNewMessages,
                            addedMessages);
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
                    l.updateMailboxCallback(me, accountId, mailboxId, 0, 0, null);
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
            final long accountId = Account.getAccountIdForMessageId(mProviderContext, messageId);
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadMessageForViewCallback(null, accountId, messageId, 0);
                }
            }
        }

        @Override
        public void loadMessageForViewFinished(long messageId) {
            final long accountId = Account.getAccountIdForMessageId(mProviderContext, messageId);
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadMessageForViewCallback(null, accountId, messageId, 100);
                }
            }
        }

        @Override
        public void loadMessageForViewFailed(long messageId, String message) {
            final long accountId = Account.getAccountIdForMessageId(mProviderContext, messageId);
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadMessageForViewCallback(new MessagingException(message),
                            accountId, messageId, 0);
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
                    listener.loadAttachmentCallback(null, accountId, messageId, attachmentId, 0);
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
                    listener.loadAttachmentCallback(null, accountId, messageId, attachmentId, 100);
                }
            }
        }

        @Override
        public void loadAttachmentFailed(long accountId, long messageId, long attachmentId,
                MessagingException me, boolean background) {
            try {
                // If the cause of the MessagingException is an IOException, we send a status of
                // CONNECTION_ERROR; in this case, AttachmentDownloadService will try again to
                // download the attachment.  Otherwise, the error is considered non-recoverable.
                int status = EmailServiceStatus.ATTACHMENT_NOT_FOUND;
                if (me != null && me.getCause() instanceof IOException) {
                    status = EmailServiceStatus.CONNECTION_ERROR;
                }
                mCallbackProxy.loadAttachmentStatus(messageId, attachmentId, status, 0);
            } catch (RemoteException e) {
            }
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    // TODO We are overloading the exception here. The UI listens for this
                    // callback and displays a toast if the exception is not null. Since we
                    // want to avoid displaying toast for background operations, we force
                    // the exception to be null. This needs to be re-worked so the UI will
                    // only receive (or at least pays attention to) responses for requests
                    // it explicitly cares about. Then we would not need to overload the
                    // exception parameter.
                    listener.loadAttachmentCallback(background ? null : me, accountId, messageId,
                            attachmentId, 0);
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
            final long accountId = Account.getAccountIdForMessageId(mProviderContext, messageId);
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(result, accountId, messageId, attachmentId,
                            progress);
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
                    listener.updateMailboxCallback(result, accountId, mailboxId, progress, 0, null);
                }
            }
        }

        private MessagingException mapStatusToException(int statusCode) {
            switch (statusCode) {
                case EmailServiceStatus.SUCCESS:
                case EmailServiceStatus.IN_PROGRESS:
                // Don't generate error if the account is uninitialized
                case EmailServiceStatus.ACCOUNT_UNINITIALIZED:
                    return null;

                case EmailServiceStatus.LOGIN_FAILED:
                    return new AuthenticationFailedException("");

                case EmailServiceStatus.CONNECTION_ERROR:
                    return new MessagingException(MessagingException.IOERROR);

                case EmailServiceStatus.SECURITY_FAILURE:
                    return new MessagingException(MessagingException.SECURITY_POLICIES_REQUIRED);

                case EmailServiceStatus.ACCESS_DENIED:
                    return new MessagingException(MessagingException.ACCESS_DENIED);

                case EmailServiceStatus.ATTACHMENT_NOT_FOUND:
                    return new MessagingException(MessagingException.ATTACHMENT_NOT_FOUND);

                case EmailServiceStatus.CLIENT_CERTIFICATE_ERROR:
                    return new MessagingException(MessagingException.CLIENT_CERTIFICATE_ERROR);

                case EmailServiceStatus.MESSAGE_NOT_FOUND:
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
        public void sendMessageStatus(long messageId, String subject, int statusCode, int progress){
        }

        @Override
        public void syncMailboxListStatus(long accountId, int statusCode, int progress) {
        }

        @Override
        public void syncMailboxStatus(long mailboxId, int statusCode, int progress) {
        }
    };

    public static class ControllerService extends Service {
        /**
         * Create our EmailService implementation here.  For now, only loadAttachment is supported;
         * the intention, however, is to move more functionality to the service interface
         */
        private final IEmailService.Stub mBinder = new IEmailService.Stub() {

            public Bundle validate(HostAuth hostAuth) {
                return null;
            }

            public Bundle autoDiscover(String userName, String password) {
                return null;
            }

            public void startSync(long mailboxId, boolean userRequest) {
            }

            public void stopSync(long mailboxId) {
            }

            public void loadAttachment(long attachmentId, boolean background)
                    throws RemoteException {
                Attachment att = Attachment.restoreAttachmentWithId(ControllerService.this,
                        attachmentId);
                if (att != null) {
                    if (Email.DEBUG) {
                        Log.d(TAG, "loadAttachment " + attachmentId + ": " + att.mFileName);
                    }
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
                                    // TODO: We can try restoring from the deleted table here...
                                    return;
                                }
                            }
                        }
                        MessagingController legacyController = sInstance.mLegacyController;
                        LegacyListener legacyListener = sInstance.mLegacyListener;
                        legacyController.loadAttachment(msg.mAccountKey, msg.mId, msg.mMailboxKey,
                                attachmentId, legacyListener, background);
                    } else {
                        // Send back the specific error status for this case
                        sInstance.mCallbackProxy.loadAttachmentStatus(att.mMessageKey, attachmentId,
                                EmailServiceStatus.MESSAGE_NOT_FOUND, 0);
                    }
                }
            }

            public void updateFolderList(long accountId) {
            }

            public void hostChanged(long accountId) {
            }

            public void setLogging(int flags) {
            }

            public void sendMeetingResponse(long messageId, int response) {
            }

            public void loadMore(long messageId) {
            }

            // The following three methods are not implemented in this version
            public boolean createFolder(long accountId, String name) {
                return false;
            }

            public boolean deleteFolder(long accountId, String name) {
                return false;
            }

            public boolean renameFolder(long accountId, String oldName, String newName) {
                return false;
            }

            public void setCallback(IEmailServiceCallback cb) {
                sCallbackList.register(cb);
            }

            public void deleteAccountPIMData(long accountId) {
            }

            public int searchMessages(long accountId, SearchParams searchParams,
                    long destMailboxId) {
                return 0;
            }

            @Override
            public int getApiLevel() {
                return Api.LEVEL;
            }
        };

        @Override
        public IBinder onBind(Intent intent) {
            return mBinder;
        }
    }
}
