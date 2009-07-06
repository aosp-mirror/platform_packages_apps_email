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
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Mailbox;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

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
    private HashSet<Result> mListeners = new HashSet<Result>();

    private static String[] MESSAGEID_TO_ACCOUNTID_PROJECTION = new String[] {
        EmailContent.RECORD_ID,
        EmailContent.MessageColumns.ACCOUNT_KEY
    };
    private static int MESSAGEID_TO_ACCOUNTID_COLUMN_ACCOUNTID = 1;

    private static String[] ACCOUNTID_TO_MAILBOXTYPE_PROJECTION = new String[] {
        EmailContent.RECORD_ID,
        EmailContent.MailboxColumns.ACCOUNT_KEY,
        EmailContent.MailboxColumns.TYPE
    };
    private static int ACCOUNTID_TO_MAILBOXTYPE_COLUMN_ID = 0;

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
     * Request a remote update of mailboxes for an account.
     *
     * TODO: Implement (if any) for non-MessagingController
     * TODO: Probably the right way is to create a fake "service" for MessagingController ops
     */
    public void updateMailboxList(final EmailContent.Account account, final Result callback) {

        // 1. determine if we can use MessagingController for this
        boolean legacyController = isMessagingController(account);

        // 2. if not...?
        // TODO: for now, just pretend "it worked"
        if (!legacyController) {
            if (callback != null) {
                callback.updateMailboxListCallback(null, account.mId);
            }
            return;
        }

        // 3. if so, make the call
        new Thread() {
            @Override
            public void run() {
                MessagingListener listener = new LegacyListener(callback);
                mLegacyController.addListener(listener);
                mLegacyController.listFolders(account, listener);
            }
        }.start();
    }

    /**
     * Request a remote update of a mailbox.
     *
     * The contract here should be to try and update the headers ASAP, in order to populate
     * a simple message list.  We should also at this point queue up a background task of
     * downloading some/all of the messages in this mailbox, but that should be interruptable.
     */
    public void updateMailbox(final EmailContent.Account account,
            final EmailContent.Mailbox mailbox, final Result callback) {

        // 1. determine if we can use MessagingController for this
        boolean legacyController = isMessagingController(account);

        // 2. if not...?
        // TODO: for now, just pretend "it worked"
        if (!legacyController) {
            if (callback != null) {
                callback.updateMailboxCallback(null, account.mId, mailbox.mId, -1, -1);
            }
            return;
        }

        // 3. if so, make the call
        new Thread() {
            @Override
            public void run() {
                MessagingListener listener = new LegacyListener(callback);
                mLegacyController.addListener(listener);
                mLegacyController.synchronizeMailbox(account, mailbox, listener);
            }
        }.start();
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
            try {
                c = resolver.query(EmailContent.Message.CONTENT_URI,
                        MESSAGEID_TO_ACCOUNTID_PROJECTION, EmailContent.RECORD_ID + "=?",
                        new String[] { Long.toString(messageId) }, null);
                if (c.moveToFirst()) {
                    accountId = c.getLong(MESSAGEID_TO_ACCOUNTID_COLUMN_ACCOUNTID);
                } else {
                    return;
                }
            } finally {
                if (c != null) c.close();
            }
        }

        // 2. Confirm that there is a trash mailbox available
        long trashMailboxId = -1;
        c = null;
        try {
            c = resolver.query(EmailContent.Mailbox.CONTENT_URI,
                    ACCOUNTID_TO_MAILBOXTYPE_PROJECTION,
                    EmailContent.MailboxColumns.ACCOUNT_KEY + "=? AND " +
                    EmailContent.MailboxColumns.TYPE + "=" + EmailContent.Mailbox.TYPE_TRASH,
                    new String[] { Long.toString(accountId) }, null);
            if (c.moveToFirst()) {
                trashMailboxId = c.getLong(ACCOUNTID_TO_MAILBOXTYPE_COLUMN_ID);
            }
        } finally {
            if (c != null) c.close();
        }

        // 3.  If there's no trash mailbox, create one
        // TODO: Does this need to be signaled explicitly to the sync engines?
        if (trashMailboxId == -1) {
            Mailbox box = new Mailbox();

            box.mDisplayName = mContext.getString(R.string.special_mailbox_name_trash);
            box.mAccountKey = accountId;
            box.mType = Mailbox.TYPE_TRASH;
            box.mSyncFrequency = EmailContent.Account.CHECK_INTERVAL_NEVER;
            box.mFlagVisible = true;
            box.saveOrUpdate(mProviderContext);
            trashMailboxId = box.mId;
        }

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
     * Simple helper to determine if legacy MessagingController should be used
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
         * @param accountKey The account being operated on
         */
        public void updateMailboxListCallback(MessagingException result, long accountKey);

        /**
         * Callback for updateMailbox
         *
         * @param result If null, the operation completed without error
         * @param accountKey The account being operated on
         * @param mailboxKey The mailbox being operated on
         */
        public void updateMailboxCallback(MessagingException result, long accountKey,
                long mailboxKey, int totalMessagesInMailbox, int numNewMessages);
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
        public void listFoldersFailed(EmailContent.Account account, String message) {
            if (mResultCallback != null && isActiveResultCallback(mResultCallback)) {
                mResultCallback.updateMailboxListCallback(new MessagingException(message),
                        account.mId);
            }
            mLegacyController.removeListener(this);
        }

        @Override
        public void listFoldersFinished(EmailContent.Account account) {
            if (mResultCallback != null && isActiveResultCallback(mResultCallback)) {
                mResultCallback.updateMailboxListCallback(null, account.mId);
            }
            mLegacyController.removeListener(this);
        }

        @Override
        public void synchronizeMailboxFinished(EmailContent.Account account,
                EmailContent.Mailbox folder, int totalMessagesInMailbox, int numNewMessages) {
            if (mResultCallback != null && isActiveResultCallback(mResultCallback)) {
                mResultCallback.updateMailboxCallback(null, account.mId, folder.mId,
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
                mResultCallback.updateMailboxCallback(me, account.mId, folder.mId, -1, -1);
            }
            mLegacyController.removeListener(this);
        }


    }


}
