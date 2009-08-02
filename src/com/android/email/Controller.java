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

import com.android.email.mail.MessagingException;
import com.android.email.mail.Store;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.service.EmailServiceProxy;
import com.android.exchange.EmailServiceStatus;
import com.android.exchange.IEmailService;
import com.android.exchange.IEmailServiceCallback;
import com.android.exchange.SyncManager;

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

    static Controller sInstance;
    private Context mContext;
    private Context mProviderContext;
    private MessagingController mLegacyController;
    private ServiceCallback mServiceCallback = new ServiceCallback();
    private HashSet<Result> mListeners = new HashSet<Result>();

    private static String[] MESSAGEID_TO_ACCOUNTID_PROJECTION = new String[] {
        EmailContent.RECORD_ID,
        EmailContent.MessageColumns.ACCOUNT_KEY
    };
    private static int MESSAGEID_TO_ACCOUNTID_COLUMN_ACCOUNTID = 1;

    protected Controller(Context _context) {
        mContext = _context;
        mProviderContext = _context;
        mLegacyController = MessagingController.getInstance(mContext);
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
    public void serviceLogging(boolean debugEnabled) {
        IEmailService service =
            new EmailServiceProxy(mContext, SyncManager.class, mServiceCallback);
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
                    Account account =
                        EmailContent.Account.restoreAccountWithId(mProviderContext, accountId);
                    MessagingListener listener = new LegacyListener(callback);
                    mLegacyController.addListener(listener);
                    mLegacyController.listFolders(account, listener);
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
    public void updateMailbox(final long accountId,
            final EmailContent.Mailbox mailbox, final Result callback) {

        IEmailService service = getServiceForAccount(accountId);
        if (service != null) {
            // Service implementation
            try {
                service.startSync(mailbox.mId);
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
                    Account account =
                        EmailContent.Account.restoreAccountWithId(mProviderContext, accountId);
                    MessagingListener listener = new LegacyListener(callback);
                    mLegacyController.addListener(listener);
                    mLegacyController.synchronizeMailbox(account, mailbox, listener);
                }
            }.start();
        }
    }

    /**
     * Saves the message to a mailbox of given type.
     * @param message the message (must have the mAccountId set).
     * @param mailboxType the mailbox type (e.g. Mailbox.TYPE_DRAFTS).
     * TODO: UI feedback.
     * TODO: use AsyncTask instead of Thread
     */
    public void saveToMailbox(final EmailContent.Message message, final int mailboxType) {
        new Thread() {
            @Override
            public void run() {
                long accountId = message.mAccountKey;
                long mailboxId = findOrCreateMailboxOfType(accountId, mailboxType);
                message.mMailboxKey = mailboxId;
                message.save(mContext);
            }
        }.start();
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
     * @param mailboxType the mailbox type
     * @return the resource string corresponding to the mailbox type, empty if not found.
     */
    /* package */ String getSpecialMailboxDisplayName(int mailboxType) {
        int resId = -1;
        switch (mailboxType) {
            case Mailbox.TYPE_INBOX:
                // TODO: there is no special_mailbox_display_name_inbox; why?
                resId = R.string.special_mailbox_name_inbox;
                break;
            case Mailbox.TYPE_OUTBOX:
                resId = R.string.special_mailbox_display_name_outbox;
                break;
            case Mailbox.TYPE_DRAFTS:
                resId = R.string.special_mailbox_display_name_drafts;
                break;
            case Mailbox.TYPE_TRASH:
                resId = R.string.special_mailbox_display_name_trash;
                break;
            case Mailbox.TYPE_SENT:
                resId = R.string.special_mailbox_display_name_sent;
                break;
        }
        return resId != -1 ? mContext.getString(resId) : "";
    }

    /**
     * Create a mailbox given the account and mailboxType.
     * TODO: Does this need to be signaled explicitly to the sync engines?
     * As this method is only used internally ('private'), it does not
     * validate its inputs (accountId and mailboxType).
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
        box.mDisplayName = getSpecialMailboxDisplayName(mailboxType);
        box.save(mProviderContext);
        return box.mId;
    }

    /**
     * Send a message:
     * - move the message to Outbox (the message is assumed to be in Drafts).
     * - perform any necessary notification
     * - do the work in a separate (non-UI) thread
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

        // TODO: notifications
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
     * Delete a single message by moving it to the trash.
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
        Cursor c = null;
        if (accountId == -1) {
            accountId = lookupAccountForMessage(messageId);
        }
        if (accountId == -1) {
            return;
        }

        // 2. Confirm that there is a trash mailbox available
        // 3.  If there's no trash mailbox, create one
        // TODO: Does this need to be signaled explicitly to the sync engines?
        long trashMailboxId = findOrCreateMailboxOfType(accountId, Mailbox.TYPE_TRASH);

        // 4.  Change the mailbox key for the message we're "deleting"
        ContentValues cv = new ContentValues();
        cv.put(EmailContent.MessageColumns.MAILBOX_KEY, trashMailboxId);
        Uri uri = ContentUris.withAppendedId(EmailContent.Message.SYNCED_CONTENT_URI, messageId);
        resolver.update(uri, cv, null, null);

        // 5.  Drop non-essential data for the message (e.g. attachments)
        // TODO: find the actual files (if any, if loaded) & delete them
        c = null;
        try {
            c = resolver.query(EmailContent.Attachment.CONTENT_URI,
                    EmailContent.Attachment.CONTENT_PROJECTION,
                    EmailContent.AttachmentColumns.MESSAGE_KEY + "=?",
                    new String[] { Long.toString(messageId) }, null);
            while (c.moveToNext()) {
                // delete any associated storage
                // delete row?
            }
        } finally {
            if (c != null) c.close();
        }

        // 6.  For IMAP/POP3 we may need to kick off an immediate delete (depends on acct settings)
        // TODO write this
    }

    /**
     * Set/clear the unread status of a message
     *
     * @param messageId the message to update
     * @param isRead the new value for the isRead flag
     */
    public void setMessageRead(long messageId, boolean isRead) {
        // TODO this should not be in this thread. queue it up.
        // TODO Also, it needs to update the read/unread count in the mailbox
        // TODO kick off service/messagingcontroller actions

        ContentValues cv = new ContentValues();
        cv.put(EmailContent.MessageColumns.FLAG_READ, isRead);
        Uri uri = ContentUris.withAppendedId(
                EmailContent.Message.SYNCED_CONTENT_URI, messageId);
        mProviderContext.getContentResolver().update(uri, cv, null, null);
    }

    /**
     * Set/clear the favorite status of a message
     *
     * @param messageId the message to update
     * @param isFavorite the new value for the isFavorite flag
     */
    public void setMessageFavorite(long messageId, boolean isFavorite) {
        // TODO this should not be in this thread. queue it up.
        // TODO kick off service/messagingcontroller actions

        ContentValues cv = new ContentValues();
        cv.put(EmailContent.MessageColumns.FLAG_FAVORITE, isFavorite);
        Uri uri = ContentUris.withAppendedId(
                EmailContent.Message.SYNCED_CONTENT_URI, messageId);
        mProviderContext.getContentResolver().update(uri, cv, null, null);
    }

    /**
     * Request that an attachment be loaded.  It will be stored at a location controlled
     * by the AttachmentProvider.
     *
     * @param attachmentId the attachment to load
     * @param messageId the owner message
     * @param accountId the owner account
     * @param callback the Controller callback by which results will be reported
     */
    public void loadAttachment(long attachmentId, long messageId, long accountId,
            final Result callback) {

        Attachment attachInfo = Attachment.restoreAttachmentWithId(mProviderContext, attachmentId);

        File saveToFile = AttachmentProvider.getAttachmentFilename(mContext,
                accountId, attachmentId);

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
        return getServiceForAccount(message.mAccountKey);
    }

    /**
     * For a given account id, return a service proxy if applicable, or null.
     *
     * @param accountId the message of interest
     * @result service proxy, or null if n/a
     */
    private IEmailService getServiceForAccount(long accountId) {
        // TODO make this more efficient, caching the account, MUCH smaller lookup here, etc.
        Account account = EmailContent.Account.restoreAccountWithId(mProviderContext, accountId);
        if (isMessagingController(account)) {
            return null;
        } else {
            return new EmailServiceProxy(mContext, SyncManager.class, mServiceCallback);
        }
    }

    /**
     * Simple helper to determine if legacy MessagingController should be used
     *
     * TODO this should not require a full account, just an accountId
     * TODO this should use a cache because we'll be doing this a lot
     */
    private boolean isMessagingController(EmailContent.Account account) {
        Store.StoreInfo info =
            Store.StoreInfo.getStoreInfo(account.getStoreUri(mContext), mContext);
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
         * Callback for updateMailbox
         *
         * @param result If null, the operation completed without error
         * @param accountId The account being operated on
         * @param mailboxId The mailbox being operated on
         * @param progress 0 for "starting", 1..99 for updates (if needed in UI), 100 for complete
         */
        public void updateMailboxCallback(MessagingException result, long accountId,
                long mailboxId, int progress, int totalMessagesInMailbox, int numNewMessages);

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
    }

    /**
     * Support for receiving callbacks from MessagingController and dealing with UI going
     * out of scope.
     */
    private class LegacyListener extends MessagingListener {
        Result mResultCallback;

        public LegacyListener(Result callback) {
            mResultCallback = callback;
        }

        @Override
        public void listFoldersStarted(EmailContent.Account account) {
            if (mResultCallback != null && isActiveResultCallback(mResultCallback)) {
                mResultCallback.updateMailboxListCallback(null, account.mId, 0);
            }
        }

        @Override
        public void listFoldersFailed(EmailContent.Account account, String message) {
            if (mResultCallback != null && isActiveResultCallback(mResultCallback)) {
                mResultCallback.updateMailboxListCallback(new MessagingException(message),
                        account.mId, 0);
            }
            mLegacyController.removeListener(this);
        }

        @Override
        public void listFoldersFinished(EmailContent.Account account) {
            if (mResultCallback != null && isActiveResultCallback(mResultCallback)) {
                mResultCallback.updateMailboxListCallback(null, account.mId, 100);
            }
            mLegacyController.removeListener(this);
        }

        @Override
        public void synchronizeMailboxStarted(EmailContent.Account account,
                EmailContent.Mailbox folder) {
            if (mResultCallback != null && isActiveResultCallback(mResultCallback)) {
                mResultCallback.updateMailboxCallback(null, account.mId, folder.mId, 0, -1, -1);
            }
        }

        @Override
        public void synchronizeMailboxFinished(EmailContent.Account account,
                EmailContent.Mailbox folder, int totalMessagesInMailbox, int numNewMessages) {
            if (mResultCallback != null && isActiveResultCallback(mResultCallback)) {
                mResultCallback.updateMailboxCallback(null, account.mId, folder.mId, 100,
                        totalMessagesInMailbox, numNewMessages);
            }
            mLegacyController.removeListener(this);
        }

        @Override
        public void synchronizeMailboxFailed(EmailContent.Account account,
                EmailContent.Mailbox folder, Exception e) {
            if (mResultCallback != null && isActiveResultCallback(mResultCallback)) {
                MessagingException me;
                if (e instanceof MessagingException) {
                    me = (MessagingException) e;
                } else {
                    me = new MessagingException(e.toString());
                }
                mResultCallback.updateMailboxCallback(me, account.mId, folder.mId, 0, -1, -1);
            }
            mLegacyController.removeListener(this);
        }


    }

    /**
     * Service callback for service operations
     */
    private class ServiceCallback extends IEmailServiceCallback.Stub {

        private final static boolean DEBUG_FAIL_DOWNLOADS = false;       // do not check in "true"

        public void loadAttachmentStatus(long messageId, long attachmentId, int statusCode,
                int progress) {
            MessagingException result = null;
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
                default:
                    result = new MessagingException(String.valueOf(statusCode));
                break;
            }
            synchronized (mListeners) {
                for (Result listener : mListeners) {
                    listener.loadAttachmentCallback(result, messageId, attachmentId, progress);
                }
            }
        }

        public void sendMessageStatus(long messageId, int statusCode, int progress) {
            // TODO Auto-generated method stub

        }

        public void syncMailboxListStatus(long accountId, int statusCode, int progress) {
            MessagingException result= null;
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
                default:
                    result = new MessagingException(String.valueOf(statusCode));
                break;
            }
            synchronized(mListeners) {
                for (Result listener : mListeners) {
                    listener.updateMailboxListCallback(result, accountId, progress);
                }
            }
        }

        public void syncMailboxStatus(long mailboxId, int statusCode, int progress) {
            MessagingException result= null;
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
                default:
                    result = new MessagingException(String.valueOf(statusCode));
                break;
            }
            // TODO can we get "number of new messages" back as well?
            // TODO remove "total num messages" which can be looked up if needed
            // TODO should pass this back instead of looking it up here
            // TODO smaller projection
            Mailbox mbx = Mailbox.restoreMailboxWithId(mContext, mailboxId);
            // The mailbox could have disappeared if the server commanded it
            if (mbx == null) return;
            long accountId = mbx.mAccountKey;
            synchronized(mListeners) {
                for (Result listener : mListeners) {
                    listener.updateMailboxCallback(result, accountId, mailboxId, progress, 0, 0);
                }
            }
        }
    }
}
