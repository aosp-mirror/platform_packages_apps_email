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
import com.android.email.mail.MessagingException;
import com.android.email.mail.Store;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.MailboxColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;
import com.android.email.service.EmailServiceStatus;
import com.android.email.service.IEmailService;
import com.android.email.service.IEmailServiceCallback;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.util.HashSet;

/**
 * New central controller/dispatcher for Email activities that may require remote operations.
 * Handles disambiguating between legacy MessagingController operations and newer provider/sync
 * based code.
 */
public class Controller {

    private static Controller sInstance;
    private final Context mContext;
    private Context mProviderContext;
    private final MessagingController mLegacyController;
    private final LegacyListener mLegacyListener = new LegacyListener();
    private final ServiceCallback mServiceCallback = new ServiceCallback();
    private final HashSet<Result> mListeners = new HashSet<Result>();

    private static String[] MESSAGEID_TO_ACCOUNTID_PROJECTION = new String[] {
        EmailContent.RECORD_ID,
        EmailContent.MessageColumns.ACCOUNT_KEY
    };
    private static int MESSAGEID_TO_ACCOUNTID_COLUMN_ACCOUNTID = 1;

    private static String[] MESSAGEID_TO_MAILBOXID_PROJECTION = new String[] {
        EmailContent.RECORD_ID,
        EmailContent.MessageColumns.MAILBOX_KEY
    };
    private static int MESSAGEID_TO_MAILBOXID_COLUMN_MAILBOXID = 1;

    protected Controller(Context _context) {
        mContext = _context;
        mProviderContext = _context;
        mLegacyController = MessagingController.getInstance(mContext);
        mLegacyController.addListener(mLegacyListener);
    }

    /**
     * Gets or creates the singleton instance of Controller.
     * @param _context The context that will be used for all underlying system access
     */
    public synchronized static Controller getInstance(Context _context) {
        if (sInstance == null) {
            sInstance = new Controller(_context);
        }
        return sInstance;
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
            mListeners.remove(listener);
        }
    }

    private boolean isActiveResultCallback(Result listener) {
        synchronized (mListeners) {
            return mListeners.contains(listener);
        }
    }

    /**
     * Enable/disable logging for external sync services
     *
     * Generally this should be called by anybody who changes Email.DEBUG
     */
    public void serviceLogging(int debugEnabled) {
        IEmailService service = ExchangeUtils.getExchangeEmailService(mContext, mServiceCallback);
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
     *
     * TODO: Clean up threading in MessagingController cases (or perhaps here in Controller)
     */
    public void updateMailboxList(final long accountId, final Result callback) {

        IEmailService service = getServiceForAccount(accountId);
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
            new Thread() {
                @Override
                public void run() {
                    mLegacyController.listFolders(accountId, mLegacyListener);
                }
            }.start();
        }
    }

    /**
     * Request a remote update of a mailbox.  For use by the timed service.
     *
     * Functionally this is quite similar to updateMailbox(), but it's a separate API and
     * separate callback in order to keep UI callbacks from affecting the service loop.
     */
    public void serviceCheckMail(final long accountId, final long mailboxId, final long tag,
            final Result callback) {
        IEmailService service = getServiceForAccount(accountId);
        if (service != null) {
            // Service implementation
//            try {
                // TODO this isn't quite going to work, because we're going to get the
                // generic (UI) callbacks and not the ones we need to restart the ol' service.
                // service.startSync(mailboxId, tag);
                callback.serviceCheckMailCallback(null, accountId, mailboxId, 100, tag);
//            } catch (RemoteException e) {
                // TODO Change exception handling to be consistent with however this method
                // is implemented for other protocols
//                Log.d("updateMailbox", "RemoteException" + e);
//            }
        } else {
            // MessagingController implementation
            new Thread() {
                @Override
                public void run() {
                    mLegacyController.checkMail(accountId, tag, mLegacyListener);
                }
            }.start();
        }
    }

    /**
     * Request a remote update of a mailbox.
     *
     * The contract here should be to try and update the headers ASAP, in order to populate
     * a simple message list.  We should also at this point queue up a background task of
     * downloading some/all of the messages in this mailbox, but that should be interruptable.
     */
    public void updateMailbox(final long accountId, final long mailboxId, final Result callback) {

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
            new Thread() {
                @Override
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
            }.start();
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
    public void loadMessageForView(final long messageId, final Result callback) {

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
            new Thread() {
                @Override
                public void run() {
                    mLegacyController.loadMessageForView(messageId, mLegacyListener);
                }
            }.start();
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

        // Split here for target type (Service or MessagingController)
        IEmailService service = getServiceForMessage(messageId);
        if (service != null) {
            // We just need to be sure the callback is installed, if this is the first call
            // to the service.
            try {
                service.setCallback(mServiceCallback);
            } catch (RemoteException re) {
                // OK - not a critical callback here
            }
        } else {
            // for IMAP & POP only, (attempt to) send the message now
            final EmailContent.Account account =
                    EmailContent.Account.restoreAccountWithId(mProviderContext, accountId);
            if (account == null) {
                return;
            }
            final long sentboxId = findOrCreateMailboxOfType(accountId, Mailbox.TYPE_SENT);
            new Thread() {
                @Override
                public void run() {
                    mLegacyController.sendPendingMessages(account, sentboxId, mLegacyListener);
                }
            }.start();
        }
    }

    /**
     * Try to send all pending messages for a given account
     *
     * @param accountId the account for which to send messages (-1 for all accounts)
     * @param callback
     */
    public void sendPendingMessages(long accountId, Result callback) {
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
            final EmailContent.Account account =
                EmailContent.Account.restoreAccountWithId(mProviderContext, accountId);
            if (account == null) {
                return;
            }
            final long sentboxId = findOrCreateMailboxOfType(accountId, Mailbox.TYPE_SENT);
            new Thread() {
                @Override
                public void run() {
                    mLegacyController.sendPendingMessages(account, sentboxId, mLegacyListener);
                }
            }.start();
        }
    }

    /**
     * Reset visible limits for all accounts.
     * For each account:
     *   look up limit
     *   write limit into all mailboxes for that account
     */
    public void resetVisibleLimits() {
        new Thread() {
            @Override
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
        }.start();
    }

    /**
     * Increase the load count for a given mailbox, and trigger a refresh.  Applies only to
     * IMAP and POP.
     *
     * @param mailboxId the mailbox
     * @param callback
     */
    public void loadMoreMessages(final long mailboxId, Result callback) {
        new Thread() {
            @Override
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
        }.start();
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
     * Delete a single message by moving it to the trash, or deleting it from the trash
     *
     * This function has no callback, no result reporting, because the desired outcome
     * is reflected entirely by changes to one or more cursors.
     *
     * @param messageId The id of the message to "delete".
     * @param accountId The id of the message's account, or -1 if not known by caller
     *
     * TODO: Move out of UI thread
     * TODO: "get account a for message m" should be a utility
     * TODO: "get mailbox of type n for account a" should be a utility
     */
    public void deleteMessage(long messageId, long accountId) {
        ContentResolver resolver = mProviderContext.getContentResolver();

        // 1.  Look up acct# for message we're deleting
        if (accountId == -1) {
            accountId = lookupAccountForMessage(messageId);
        }
        if (accountId == -1) {
            return;
        }

        // 2. Confirm that there is a trash mailbox available.  If not, create one
        long trashMailboxId = findOrCreateMailboxOfType(accountId, Mailbox.TYPE_TRASH);

        // 3.  Are we moving to trash or deleting?  It depends on where the message currently sits.
        long sourceMailboxId = -1;
        Cursor c = resolver.query(EmailContent.Message.CONTENT_URI,
                MESSAGEID_TO_MAILBOXID_PROJECTION, EmailContent.RECORD_ID + "=?",
                new String[] { Long.toString(messageId) }, null);
        try {
            sourceMailboxId = c.moveToFirst()
                ? c.getLong(MESSAGEID_TO_MAILBOXID_COLUMN_MAILBOXID)
                : -1;
        } finally {
            c.close();
        }

        // 4.  Drop non-essential data for the message (e.g. attachment files)
        AttachmentProvider.deleteAllAttachmentFiles(mProviderContext, accountId, messageId);

        Uri uri = ContentUris.withAppendedId(EmailContent.Message.SYNCED_CONTENT_URI, messageId);

        // 5. Perform "delete" as appropriate
        if (sourceMailboxId == trashMailboxId) {
            // 5a. Delete from trash
            resolver.delete(uri, null, null);
        } else {
            // 5b. Move to trash
            ContentValues cv = new ContentValues();
            cv.put(EmailContent.MessageColumns.MAILBOX_KEY, trashMailboxId);
            resolver.update(uri, cv, null, null);
        }

        // 6.  Service runs automatically, MessagingController needs a kick
        Account account = Account.restoreAccountWithId(mProviderContext, accountId);
        if (account == null) {
            return; // isMessagingController returns false for null, but let's make it clear.
        }
        if (isMessagingController(account)) {
            final long syncAccountId = accountId;
            new Thread() {
                @Override
                public void run() {
                    mLegacyController.processPendingActions(syncAccountId);
                }
            }.start();
        }
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
            new Thread() {
                @Override
                public void run() {
                    mLegacyController.processPendingActions(message.mAccountKey);
                }
            }.start();
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
            new Thread() {
                @Override
                public void run() {
                    mLegacyController.processPendingActions(message.mAccountKey);
                }
            }.start();
        }
    }

    /**
     * Respond to a meeting invitation.
     *
     * @param messageId the id of the invitation being responded to
     * @param response the code representing the response to the invitation
     * @callback the Controller callback by which results will be reported (currently not defined)
     */
    public void sendMeetingResponse(final long messageId, final int response,
            final Result callback) {
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
     * @param mailboxId the owner mailbox
     * @param accountId the owner account
     * @param callback the Controller callback by which results will be reported
     */
    public void loadAttachment(final long attachmentId, final long messageId, final long mailboxId,
            final long accountId, final Result callback) {

        File saveToFile = AttachmentProvider.getAttachmentFilename(mProviderContext,
                accountId, attachmentId);
        Attachment attachInfo = Attachment.restoreAttachmentWithId(mProviderContext, attachmentId);
        if (attachInfo == null) {
            return;
        }

        if (saveToFile.exists() && attachInfo.mContentUri != null) {
            // The attachment has already been downloaded, so we will just "pretend" to download it
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

        // Split here for target type (Service or MessagingController)
        IEmailService service = getServiceForMessage(messageId);
        if (service != null) {
            // Service implementation
            try {
                service.loadAttachment(attachInfo.mId, saveToFile.getAbsolutePath(),
                        AttachmentProvider.getAttachmentUri(accountId, attachmentId).toString());
            } catch (RemoteException e) {
                // TODO Change exception handling to be consistent with however this method
                // is implemented for other protocols
                Log.e("onDownloadAttachment", "RemoteException", e);
            }
        } else {
            // MessagingController implementation
            new Thread() {
                @Override
                public void run() {
                    mLegacyController.loadAttachment(accountId, messageId, mailboxId, attachmentId,
                            mLegacyListener);
                }
            }.start();
        }
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
     * TODO this should use a cache because we'll be doing this a lot
     *
     * @param accountId the message of interest
     * @result service proxy, or null if n/a
     */
    private IEmailService getServiceForAccount(long accountId) {
        // TODO make this more efficient, caching the account, MUCH smaller lookup here, etc.
        Account account = EmailContent.Account.restoreAccountWithId(mProviderContext, accountId);
        if (account == null || isMessagingController(account)) {
            return null;
        } else {
            return ExchangeUtils.getExchangeEmailService(mContext, mServiceCallback);
        }
    }

    /**
     * Simple helper to determine if legacy MessagingController should be used
     *
     * TODO this should not require a full account, just an accountId
     * TODO this should use a cache because we'll be doing this a lot
     */
    public boolean isMessagingController(EmailContent.Account account) {
        if (account == null) return false;
        Store.StoreInfo info =
            Store.StoreInfo.getStoreInfo(account.getStoreUri(mProviderContext), mContext);
        // This null happens in testing.
        if (info == null) {
            return false;
        }
        String scheme = info.mScheme;

        return ("pop3".equals(scheme) || "imap".equals(scheme));
    }

    /**
     * Simple callback for synchronous commands.  For many commands, this can be largely ignored
     * and the result is observed via provider cursors.  The callback will *not* necessarily be
     * made from the UI thread, so you may need further handlers to safely make UI updates.
     */
    public interface Result {
        /**
         * Callback for updateMailboxList
         *
         * @param result If null, the operation completed without error
         * @param accountId The account being operated on
         * @param progress 0 for "starting", 1..99 for updates (if needed in UI), 100 for complete
         */
        public void updateMailboxListCallback(MessagingException result, long accountId,
                int progress);

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
                long mailboxId, int progress, int numNewMessages);

        /**
         * Callback for loadMessageForView
         *
         * @param result if null, the attachment completed - if non-null, terminating with failure
         * @param messageId the message which contains the attachment
         * @param progress 0 for "starting", 1..99 for updates (if needed in UI), 100 for complete
         */
        public void loadMessageForViewCallback(MessagingException result, long messageId,
                int progress);

        /**
         * Callback for loadAttachment
         *
         * @param result if null, the attachment completed - if non-null, terminating with failure
         * @param messageId the message which contains the attachment
         * @param attachmentId the attachment being loaded
         * @param progress 0 for "starting", 1..99 for updates (if needed in UI), 100 for complete
         */
        public void loadAttachmentCallback(MessagingException result, long messageId,
                long attachmentId, int progress);

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
                long mailboxId, int progress, long tag);

        /**
         * Callback for sending pending messages.  This will be called once to start the
         * group, multiple times for messages, and once to complete the group.
         *
         * @param result If null, the operation completed without error
         * @param accountId The account being operated on
         * @param messageId The being sent (may be unknown at start)
         * @param progress 0 for "starting", 100 for complete
         */
        public void sendMailCallback(MessagingException result, long accountId,
                long messageId, int progress);
    }

    /**
     * Support for receiving callbacks from MessagingController and dealing with UI going
     * out of scope.
     */
    private class LegacyListener extends MessagingListener {

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
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(null, messageId, attachmentId, 0);
                }
            }
        }

        @Override
        public void loadAttachmentFinished(long accountId, long messageId, long attachmentId) {
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(null, messageId, attachmentId, 100);
                }
            }
        }

        @Override
        public void loadAttachmentFailed(long accountId, long messageId, long attachmentId,
                String reason) {
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(new MessagingException(reason),
                            messageId, attachmentId, 0);
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
//            Log.d(Email.LOG_TAG, "sendMessageStatus: messageId=" + messageId
//                    + " statusCode=" + statusCode + " progress=" + progress);
//            Log.d(Email.LOG_TAG, "sendMessageStatus: subject=" + subject);
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
//            Log.d(Email.LOG_TAG, "result=" + result + " messageId=" + messageId
//                    + " progress=" + progress);
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
            // TODO where do we get "number of new messages" as well?
            // TODO should pass this back instead of looking it up here
            // TODO smaller projection
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
}
