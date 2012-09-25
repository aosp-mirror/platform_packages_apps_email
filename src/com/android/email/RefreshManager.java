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

package com.android.email;

import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.utility.Utility;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;

/**
 * Class that handles "refresh" (and "send pending messages" for outboxes) related functionalities.
 *
 * <p>This class is responsible for two things:
 * <ul>
 *   <li>Taking refresh requests of mailbox-lists and message-lists and the "send outgoing
 *       messages" requests from UI, and calls appropriate methods of {@link Controller}.
 *       Note at this point the timer-based refresh
 *       (by {@link com.android.email.service.MailService}) uses {@link Controller} directly.
 *   <li>Keeping track of which mailbox list/message list is actually being refreshed.
 * </ul>
 * Refresh requests will be ignored if a request to the same target is already requested, or is
 * already being refreshed.
 *
 * <p>Conceptually it can be a part of {@link Controller}, but extracted for easy testing.
 *
 * (All public methods must be called on the UI thread.  All callbacks will be called on the UI
 * thread.)
 */
public class RefreshManager {
    private static final boolean LOG_ENABLED = false; // DONT SUBMIT WITH TRUE
    private static final long MAILBOX_AUTO_REFRESH_INTERVAL = 5 * 60 * 1000; // in milliseconds
    private static final long MAILBOX_LIST_AUTO_REFRESH_INTERVAL = 5 * 60 * 1000; // in milliseconds

    private static RefreshManager sInstance;

    private final Clock mClock;
    private final Context mContext;
    private final Controller mController;
    private final Controller.Result mControllerResult;

    /** Last error message */
    private String mErrorMessage;

    public interface Listener {
        /**
         * Refresh status of a mailbox list or a message list has changed.
         *
         * @param accountId ID of the account.
         * @param mailboxId -1 if it's about the mailbox list, or the ID of the mailbox list in
         * question.
         */
        public void onRefreshStatusChanged(long accountId, long mailboxId);

        /**
         * Error callback.
         *
         * @param accountId ID of the account, or -1 if unknown.
         * @param mailboxId ID of the mailbox, or -1 if unknown.
         * @param message error message which can be shown to the user.
         */
        public void onMessagingError(long accountId, long mailboxId, String message);
    }

    private final ArrayList<Listener> mListeners = new ArrayList<Listener>();

    /**
     * Status of a mailbox list/message list.
     */
    /* package */ static class Status {
        /**
         * True if a refresh of the mailbox is requested, and not finished yet.
         */
        private boolean mIsRefreshRequested;

        /**
         * True if the mailbox is being refreshed.
         *
         * Set true when {@link #onRefreshRequested} is called, i.e. refresh is requested by UI.
         * Note refresh can occur without a request from UI as well (e.g. timer based refresh).
         * In which case, {@link #mIsRefreshing} will be true with {@link #mIsRefreshRequested}
         * being false.
         */
        private boolean mIsRefreshing;

        private long mLastRefreshTime;

        public boolean isRefreshing() {
            return mIsRefreshRequested || mIsRefreshing;
        }

        public boolean canRefresh() {
            return !isRefreshing();
        }

        public void onRefreshRequested() {
            mIsRefreshRequested = true;
        }

        public long getLastRefreshTime() {
            return mLastRefreshTime;
        }

        public void onCallback(MessagingException exception, int progress, Clock clock) {
            if (exception == null && progress == 0) {
                // Refresh started
                mIsRefreshing = true;
            } else if (exception != null || progress == 100) {
                // Refresh finished
                mIsRefreshing = false;
                mIsRefreshRequested = false;
                mLastRefreshTime = clock.getTime();
            }
        }
    }

    /**
     * Map of accounts/mailboxes to {@link Status}.
     */
    private static class RefreshStatusMap {
        private final HashMap<Long, Status> mMap = new HashMap<Long, Status>();

        public Status get(long id) {
            Status s = mMap.get(id);
            if (s == null) {
                s = new Status();
                mMap.put(id, s);
            }
            return s;
        }

        public boolean isRefreshingAny() {
            for (Status s : mMap.values()) {
                if (s.isRefreshing()) {
                    return true;
                }
            }
            return false;
        }
    }

    private final RefreshStatusMap mMailboxListStatus = new RefreshStatusMap();
    private final RefreshStatusMap mMessageListStatus = new RefreshStatusMap();

    /**
     * @return the singleton instance.
     */
    public static synchronized RefreshManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new RefreshManager(context, Controller.getInstance(context),
                    Clock.INSTANCE, new Handler());
        }
        return sInstance;
    }

    protected RefreshManager(Context context, Controller controller, Clock clock,
            Handler handler) {
        mClock = clock;
        mContext = context.getApplicationContext();
        mController = controller;
        mControllerResult = new ControllerResultUiThreadWrapper<ControllerResult>(
                handler, new ControllerResult());
        mController.addResultCallback(mControllerResult);
    }

    /**
     * MUST be called for mock instances.  (The actual instance is a singleton, so no cleanup
     * is necessary.)
     */
    public void cleanUpForTest() {
        mController.removeResultCallback(mControllerResult);
    }

    public void registerListener(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException();
        }
        mListeners.add(listener);
    }

    public void unregisterListener(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException();
        }
        mListeners.remove(listener);
    }

    /**
     * Refresh the mailbox list of an account.
     */
    public boolean refreshMailboxList(long accountId) {
        final Status status = mMailboxListStatus.get(accountId);
        if (!status.canRefresh()) return false;

        if (LOG_ENABLED) {
            Log.d(Logging.LOG_TAG, "refreshMailboxList " + accountId);
        }
        status.onRefreshRequested();
        notifyRefreshStatusChanged(accountId, -1);
        mController.updateMailboxList(accountId);
        return true;
    }

    public boolean isMailboxStale(long mailboxId) {
        return mClock.getTime() >= (mMessageListStatus.get(mailboxId).getLastRefreshTime()
                + MAILBOX_AUTO_REFRESH_INTERVAL);
    }

    public boolean isMailboxListStale(long accountId) {
        return mClock.getTime() >= (mMailboxListStatus.get(accountId).getLastRefreshTime()
                + MAILBOX_LIST_AUTO_REFRESH_INTERVAL);
    }

    /**
     * Refresh messages in a mailbox.
     */
    public boolean refreshMessageList(long accountId, long mailboxId, boolean userRequest) {
        return refreshMessageList(accountId, mailboxId, false, userRequest);
    }

    /**
     * "load more messages" in a mailbox.
     */
    public boolean loadMoreMessages(long accountId, long mailboxId) {
        return refreshMessageList(accountId, mailboxId, true, true);
    }

    private boolean refreshMessageList(long accountId, long mailboxId, boolean loadMoreMessages,
            boolean userRequest) {
        final Status status = mMessageListStatus.get(mailboxId);
        if (!status.canRefresh()) return false;

        if (LOG_ENABLED) {
            Log.d(Logging.LOG_TAG, "refreshMessageList " + accountId + ", " + mailboxId + ", "
                    + loadMoreMessages);
        }
        status.onRefreshRequested();
        notifyRefreshStatusChanged(accountId, mailboxId);
        if (loadMoreMessages) {
            mController.loadMoreMessages(mailboxId);
        } else {
            mController.updateMailbox(accountId, mailboxId, userRequest);
        }
        return true;
    }

    /**
     * Send pending messages.
     */
    public boolean sendPendingMessages(long accountId) {
        if (LOG_ENABLED) {
            Log.d(Logging.LOG_TAG, "sendPendingMessages " + accountId);
        }
        notifyRefreshStatusChanged(accountId, -1);
        mController.sendPendingMessages(accountId);
        return true;
    }

    /**
     * Call {@link #sendPendingMessages} for all accounts.
     */
    public void sendPendingMessagesForAllAccounts() {
        if (LOG_ENABLED) {
            Log.d(Logging.LOG_TAG, "sendPendingMessagesForAllAccounts");
        }
        new SendPendingMessagesForAllAccountsImpl()
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private class SendPendingMessagesForAllAccountsImpl extends Utility.ForEachAccount {
        public SendPendingMessagesForAllAccountsImpl() {
            super(mContext);
        }

        @Override
        protected void performAction(long accountId) {
            sendPendingMessages(accountId);
        }
    }

    public long getLastMailboxListRefreshTime(long accountId) {
        return mMailboxListStatus.get(accountId).getLastRefreshTime();
    }

    public long getLastMessageListRefreshTime(long mailboxId) {
        return mMessageListStatus.get(mailboxId).getLastRefreshTime();
    }

    public boolean isMailboxListRefreshing(long accountId) {
        return mMailboxListStatus.get(accountId).isRefreshing();
    }

    public boolean isMessageListRefreshing(long mailboxId) {
        return mMessageListStatus.get(mailboxId).isRefreshing();
    }

    public boolean isRefreshingAnyMailboxListForTest() {
        return mMailboxListStatus.isRefreshingAny();
    }

    public boolean isRefreshingAnyMessageListForTest() {
        return mMessageListStatus.isRefreshingAny();
    }

    public String getErrorMessage() {
        return mErrorMessage;
    }

    private void notifyRefreshStatusChanged(long accountId, long mailboxId) {
        for (Listener l : mListeners) {
            l.onRefreshStatusChanged(accountId, mailboxId);
        }
    }

    private void reportError(long accountId, long mailboxId, String errorMessage) {
        mErrorMessage = errorMessage;
        for (Listener l : mListeners) {
            l.onMessagingError(accountId, mailboxId, mErrorMessage);
        }
    }

    /* package */ Collection<Listener> getListenersForTest() {
        return mListeners;
    }

    /* package */ Status getMailboxListStatusForTest(long accountId) {
        return mMailboxListStatus.get(accountId);
    }

    /* package */ Status getMessageListStatusForTest(long mailboxId) {
        return mMessageListStatus.get(mailboxId);
    }

    private class ControllerResult extends Controller.Result {
        private boolean mSendMailExceptionReported = false;

        private String exceptionToString(MessagingException exception) {
            if (exception == null) {
                return "(no exception)";
            } else {
                return MessagingExceptionStrings.getErrorString(mContext, exception);
            }
        }

        /**
         * Callback for mailbox list refresh.
         */
        @Override
        public void updateMailboxListCallback(MessagingException exception, long accountId,
                int progress) {
            if (LOG_ENABLED) {
                Log.d(Logging.LOG_TAG, "updateMailboxListCallback " + accountId + ", " + progress
                        + ", " + exceptionToString(exception));
            }
            mMailboxListStatus.get(accountId).onCallback(exception, progress, mClock);
            if (exception != null) {
                reportError(accountId, -1,
                        MessagingExceptionStrings.getErrorString(mContext, exception));
            }
            notifyRefreshStatusChanged(accountId, -1);
        }

        /**
         * Callback for explicit (user-driven) mailbox refresh.
         */
        @Override
        public void updateMailboxCallback(MessagingException exception, long accountId,
                long mailboxId, int progress, int dontUseNumNewMessages,
                ArrayList<Long> addedMessages) {
            if (LOG_ENABLED) {
                Log.d(Logging.LOG_TAG, "updateMailboxCallback " + accountId + ", "
                        + mailboxId + ", " + progress + ", " + exceptionToString(exception));
            }
            updateMailboxCallbackInternal(exception, accountId, mailboxId, progress, 0);
        }

        /**
         * Callback for implicit (timer-based) mailbox refresh.
         *
         * Do the same as {@link #updateMailboxCallback}.
         * TODO: Figure out if it's really okay to do the same as updateMailboxCallback.
         * If both the explicit refresh and the implicit refresh can run at the same time,
         * we need to keep track of their status separately.
         */
        @Override
        public void serviceCheckMailCallback(
                MessagingException exception, long accountId, long mailboxId, int progress,
                long tag) {
            if (LOG_ENABLED) {
                Log.d(Logging.LOG_TAG, "serviceCheckMailCallback " + accountId + ", "
                        + mailboxId + ", " + progress + ", " + exceptionToString(exception));
            }
            updateMailboxCallbackInternal(exception, accountId, mailboxId, progress, 0);
        }

        private void updateMailboxCallbackInternal(MessagingException exception, long accountId,
                long mailboxId, int progress, int dontUseNumNewMessages) {
            // Don't use dontUseNumNewMessages.  serviceCheckMailCallback() don't set it.
            mMessageListStatus.get(mailboxId).onCallback(exception, progress, mClock);
            if (exception != null) {
                reportError(accountId, mailboxId,
                        MessagingExceptionStrings.getErrorString(mContext, exception));
            }
            notifyRefreshStatusChanged(accountId, mailboxId);
        }


        /**
         * Send message progress callback.
         *
         * We don't keep track of the status of outboxes, but we monitor this to catch
         * errors.
         */
        @Override
        public void sendMailCallback(MessagingException exception, long accountId, long messageId,
                int progress) {
            if (LOG_ENABLED) {
                Log.d(Logging.LOG_TAG, "sendMailCallback " + accountId + ", "
                        + messageId + ", " + progress + ", " + exceptionToString(exception));
            }
            if (progress == 0 && messageId == -1) {
                mSendMailExceptionReported = false;
            }
            if (exception != null && !mSendMailExceptionReported) {
                // Only the first error in a batch will be reported.
                mSendMailExceptionReported = true;
                reportError(accountId, messageId,
                        MessagingExceptionStrings.getErrorString(mContext, exception));
            }
            if (progress == 100) {
                mSendMailExceptionReported = false;
            }
        }
    }
}
