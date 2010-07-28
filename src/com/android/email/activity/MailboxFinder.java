/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.email.activity;

import com.android.email.Controller;
import com.android.email.ControllerResultUiThreadWrapper;
import com.android.email.Email;
import com.android.email.Utility;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.Mailbox;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.Log;

/**
 * A class that finds a mailbox ID by account ID and mailbox type.
 *
 * If an account doesn't have a mailbox of a specified type, it refreshes the mailbox list and
 * try looking for again.
 */
public class MailboxFinder {
    private final Context mContext;
    private final Controller mController;
    private final Controller.Result mControllerResults;

    private final long mAccountId;
    private final int mMailboxType;
    private final Callback mCallback;

    private FindMailboxTask mTask;

    /**
     * Callback for results.
     */
    public interface Callback {
        public void onAccountNotFound();
        public void onMailboxNotFound(long accountId);
        public void onAccountSecurityHold(long accountId);
        public void onMailboxFound(long accountId, long mailboxId);
    }

    /**
     * Creates an instance for {@code accountId} and {@code mailboxType}.  (But won't start yet)
     *
     * Must be called on the UI thread.
     */
    public MailboxFinder(Context context, long accountId, int mailboxType, Callback callback) {
        if (accountId == -1) {
            throw new UnsupportedOperationException();
        }
        mContext = context.getApplicationContext();
        mController = Controller.getInstance(context);
        mAccountId = accountId;
        mMailboxType = mailboxType;
        mCallback = callback;
        mControllerResults = new ControllerResultUiThreadWrapper<ControllerResults>(
                new Handler(), new ControllerResults());
        mController.addResultCallback(mControllerResults);
    }

    /**
     * Start looking up.
     *
     * Must be called on the UI thread.
     */
    public void startLookup() {
        stop();
        mTask = new FindMailboxTask(true);
        mTask.execute();
    }

    /**
     * Stop the running worker task, if exists.
     */
    public void stop() {
        Utility.cancelTaskInterrupt(mTask);
        mTask = null;
    }

    /**
     * Stop the running task, if exists, and clean up internal resources.  (MUST be called.)
     */
    public void close() {
        mController.removeResultCallback(mControllerResults);
        stop();
    }

    private class ControllerResults extends Controller.Result {
        @Override
        public void updateMailboxListCallback(MessagingException result, long accountId,
                int progress) {
            if (result != null) {
                // Error while updating the mailbox list.  Notify the UI...
                mCallback.onMailboxNotFound(mAccountId);
            } else {
                // Messagebox list updated, look for mailbox again...
                if (progress == 100 && accountId == mAccountId) {
                    mTask = new FindMailboxTask(false);
                    mTask.execute();
                }
            }
        }
    }

    /**
     * Async task for finding a single mailbox by type.  If a mailbox of a type is not found,
     * and {@code okToRecurse} is true, we update the mailbox list and try looking again.
     */
    private class FindMailboxTask extends AsyncTask<Void, Void, Long> {
        private final boolean mOkToRecurse;

        private static final int RESULT_MAILBOX_FOUND = 0;
        private static final int RESULT_ACCOUNT_SECURITY_HOLD = 1;
        private static final int RESULT_ACCOUNT_NOT_FOUND = 2;
        private static final int RESULT_MAILBOX_NOT_FOUND = 3;
        private static final int RESULT_START_NETWORK_LOOK_UP = 4;

        private int mResult = -1;

        /**
         * Special constructor to cache some local info
         */
        public FindMailboxTask(boolean okToRecurse) {
            mOkToRecurse = okToRecurse;
        }

        @Override
        protected Long doInBackground(Void... params) {
            // Quick check that account is not in security hold
            if (Account.isSecurityHold(mContext, mAccountId)) {
                mResult = RESULT_ACCOUNT_SECURITY_HOLD;
                return Mailbox.NO_MAILBOX;
            }

            // See if we can find the requested mailbox in the DB.
            long mailboxId = Mailbox.findMailboxOfType(mContext, mAccountId, mMailboxType);
            if (mailboxId != Mailbox.NO_MAILBOX) {
                mResult = RESULT_MAILBOX_FOUND;
                return mailboxId; // Found
            }

            // Mailbox not found.  Does the account really exists?
            final boolean accountExists = Account.isValidId(mContext, mAccountId);
            if (accountExists) {
                if (mOkToRecurse) {
                    // launch network lookup
                    mResult = RESULT_START_NETWORK_LOOK_UP;
                } else {
                    mResult = RESULT_MAILBOX_NOT_FOUND;
                }
            } else {
                mResult = RESULT_ACCOUNT_NOT_FOUND;
            }
            return Mailbox.NO_MAILBOX;
        }

        @Override
        protected void onPostExecute(Long mailboxId) {
            Log.w(Email.LOG_TAG, "" + isCancelled() + " " + mResult);
            if (isCancelled()) {
                return;
            }
            switch (mResult) {
                case RESULT_ACCOUNT_SECURITY_HOLD:
                    Log.w(Email.LOG_TAG, "Account security hold.");
                    mCallback.onAccountSecurityHold(mAccountId);
                    return;
                case RESULT_ACCOUNT_NOT_FOUND:
                    Log.w(Email.LOG_TAG, "Account not found.");
                    mCallback.onAccountNotFound();
                    return;
                case RESULT_MAILBOX_NOT_FOUND:
                    Log.w(Email.LOG_TAG, "Mailbox not found.");
                    mCallback.onMailboxNotFound(mAccountId);
                    return;
                case RESULT_START_NETWORK_LOOK_UP:
                    // Not found locally.  Let's sync the mailbox list...
                    Log.i(Email.LOG_TAG, "Mailbox not found locally. Starting network lookup.");
                    mController.updateMailboxList(mAccountId);
                    return;
                case RESULT_MAILBOX_FOUND:
                    mCallback.onMailboxFound(mAccountId, mailboxId);
                    return;
                default:
                    throw new RuntimeException();
            }
        }
    }

    /* package */ Controller.Result getControllerResultsForTest() {
        return mControllerResults;
    }
}
