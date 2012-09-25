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
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

/**
 * A class that finds a mailbox ID by account ID and mailbox type.
 *
 * If an account doesn't have a mailbox of a specified type, it refreshes the mailbox list and
 * try looking for again.
 *
 * This is a "one-shot" class.  You create an instance, call {@link #startLookup}, get a result
 * or call {@link #cancel}, and that's it.  The instance can't be re-used.
 */
public class MailboxFinder {
    private final Context mContext;
    private final Controller mController;

    // Actual Controller.Result that will wrapped by ControllerResultUiThreadWrapper.
    // Unit tests directly use it to avoid asynchronicity caused by ControllerResultUiThreadWrapper.
    private final ControllerResults mInnerControllerResults;
    private Controller.Result mControllerResults; // Not final, we null it out when done.

    private final long mAccountId;
    private final int mMailboxType;
    private final Callback mCallback;

    private FindMailboxTask mTask;
    private boolean mStarted;
    private boolean mClosed;

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
        mInnerControllerResults = new ControllerResults();
        mControllerResults = new ControllerResultUiThreadWrapper<ControllerResults>(
                new Handler(), mInnerControllerResults);
        mController.addResultCallback(mControllerResults);
    }

    /**
     * Start looking up.
     *
     * Must be called on the UI thread.
     */
    public void startLookup() {
        if (mStarted) {
            throw new IllegalStateException(); // Can't start twice.
        }
        mStarted = true;
        mTask = new FindMailboxTask(true);
        mTask.executeParallel();
    }

    /**
     * Cancel the operation.  It's safe to call it multiple times, or even if the operation is
     * already finished.
     */
    public void cancel() {
        if (!mClosed) {
            close();
        }
    }

    /**
     * Stop the running task, if exists, and clean up internal resources.
     */
    private void close() {
        mClosed = true;
        if (mControllerResults != null) {
            mController.removeResultCallback(mControllerResults);
            mControllerResults = null;
        }
        Utility.cancelTaskInterrupt(mTask);
        mTask = null;
    }

    private class ControllerResults extends Controller.Result {
        @Override
        public void updateMailboxListCallback(MessagingException result, long accountId,
                int progress) {
            if (mClosed || (accountId != mAccountId)) {
                return; // Already closed, or non-target account.
            }
            Log.i(Logging.LOG_TAG, "MailboxFinder: updateMailboxListCallback");
            if (result != null) {
                // Error while updating the mailbox list.  Notify the UI...
                try {
                    mCallback.onMailboxNotFound(mAccountId);
                } finally {
                    close();
                }
            } else if (progress == 100) {
                // Mailbox list updated, look for mailbox again...
                mTask = new FindMailboxTask(false);
                mTask.executeParallel();
            }
        }
    }

    /**
     * Async task for finding a single mailbox by type.  If a mailbox of a type is not found,
     * and {@code okToRecurse} is true, we update the mailbox list and try looking again.
     */
    private class FindMailboxTask extends EmailAsyncTask<Void, Void, Long> {
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
            super(null);
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
        protected void onSuccess(Long mailboxId) {
            switch (mResult) {
                case RESULT_ACCOUNT_SECURITY_HOLD:
                    Log.w(Logging.LOG_TAG, "MailboxFinder: Account security hold.");
                    try {
                        mCallback.onAccountSecurityHold(mAccountId);
                    } finally {
                        close();
                    }
                    return;
                case RESULT_ACCOUNT_NOT_FOUND:
                    Log.w(Logging.LOG_TAG, "MailboxFinder: Account not found.");
                    try {
                        mCallback.onAccountNotFound();
                    } finally {
                        close();
                    }
                    return;
                case RESULT_MAILBOX_NOT_FOUND:
                    Log.w(Logging.LOG_TAG, "MailboxFinder: Mailbox not found.");
                    try {
                        mCallback.onMailboxNotFound(mAccountId);
                    } finally {
                        close();
                    }
                    return;
                case RESULT_MAILBOX_FOUND:
                    if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
                        Log.d(Logging.LOG_TAG, "MailboxFinder: mailbox found: id=" + mailboxId);
                    }
                    try {
                        mCallback.onMailboxFound(mAccountId, mailboxId);
                    } finally {
                        close();
                    }
                    return;
                case RESULT_START_NETWORK_LOOK_UP:
                    // Not found locally.  Let's sync the mailbox list...
                    Log.i(Logging.LOG_TAG, "MailboxFinder: Starting network lookup.");
                    mController.updateMailboxList(mAccountId);
                    return;
                default:
                    throw new RuntimeException();
            }
        }
    }

    /* package */ boolean isStartedForTest() {
        return mStarted;
    }

    /**
     * Called by unit test.  Return true if all the internal resources are really released.
     */
    /* package */ boolean isReallyClosedForTest() {
        return mClosed && (mTask == null) && (mControllerResults == null);
    }

    /* package */ Controller.Result getControllerResultsForTest() {
        return mInnerControllerResults;
    }
}
