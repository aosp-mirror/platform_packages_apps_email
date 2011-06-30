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

import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.DelayedOperations;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;

/**
 * Used by {@link MessageView} to determine the message-id of the previous/next messages.
 *
 * All public methods must be called on the main thread.
 *
 * Call {@link #moveTo} to set the current message id.  As a result,
 * either {@link Callback#onMessagesChanged} or {@link Callback#onMessageNotFound} is called.
 *
 * Use {@link #canMoveToNewer()} and {@link #canMoveToOlder()} to see if there is a newer/older
 * message, and {@link #moveToNewer()} and {@link #moveToOlder()} to update the current position.
 *
 * If the message list changes (e.g. message removed, new message arrived, etc), {@link Callback}
 * gets called again.
 *
 * When an instance is no longer needed, call {@link #close()}, which closes an underlying cursor
 * and shuts down an async task.
 *
 * TODO: Is there better words than "newer"/"older" that works even if we support other sort orders
 * than timestamp?
 */
public class MessageOrderManager {
    private final Context mContext;
    private final ContentResolver mContentResolver;

    private final long mMailboxId;
    private final ContentObserver mObserver;
    private final Callback mCallback;
    private final DelayedOperations mDelayedOperations;

    private LoadMessageListTask mLoadMessageListTask;
    private Cursor mCursor;

    private long mCurrentMessageId = -1;

    private int mTotalMessageCount;

    private int mCurrentPosition;

    private boolean mClosed = false;

    public interface Callback {
        /**
         * Called when the message set by {@link MessageOrderManager#moveTo(long)} is found in the
         * mailbox.  {@link #canMoveToOlder}, {@link #canMoveToNewer}, {@link #moveToOlder} and
         * {@link #moveToNewer} are ready to be called.
         */
        public void onMessagesChanged();
        /**
         * Called when the message set by {@link MessageOrderManager#moveTo(long)} is not found.
         */
        public void onMessageNotFound();
    }

    /**
     * Wrapper for {@link Callback}, which uses {@link DelayedOperations#post(Runnable)} to
     * kick callbacks rather than calling them directly.  This is used to avoid the "nested fragment
     * transaction" exception.  e.g. {@link #moveTo} is often called during a fragment transaction,
     * and if the message no longer exists we call {@link #onMessageNotFound}, which most probably
     * triggers another fragment transaction.
     */
    private class PostingCallback implements Callback {
        private final Callback mOriginal;

        private PostingCallback(Callback original) {
            mOriginal = original;
        }

        private final Runnable mOnMessagesChangedRunnable = new Runnable() {
            @Override public void run() {
                mOriginal.onMessagesChanged();
            }
        };

        @Override
        public void onMessagesChanged() {
            mDelayedOperations.post(mOnMessagesChangedRunnable);
        }

        private final Runnable mOnMessageNotFoundRunnable = new Runnable() {
            @Override public void run() {
                mOriginal.onMessageNotFound();
            }
        };

        @Override
        public void onMessageNotFound() {
            mDelayedOperations.post(mOnMessageNotFoundRunnable);
        }
    }

    public MessageOrderManager(Context context, long mailboxId, Callback callback) {
        this(context, mailboxId, callback, new DelayedOperations(Utility.getMainThreadHandler()));
    }

    @VisibleForTesting
    MessageOrderManager(Context context, long mailboxId, Callback callback,
            DelayedOperations delayedOperations) {
        Preconditions.checkArgument(mailboxId != Mailbox.NO_MAILBOX);
        mContext = context.getApplicationContext();
        mContentResolver = mContext.getContentResolver();
        mDelayedOperations = delayedOperations;
        mMailboxId = mailboxId;
        mCallback = new PostingCallback(callback);
        mObserver = new ContentObserver(getHandlerForContentObserver()) {
                @Override public void onChange(boolean selfChange) {
                    if (mClosed) {
                        return;
                    }
                    onContentChanged();
                }
        };
        startTask();
    }

    public long getMailboxId() {
        return mMailboxId;
    }

    /**
     * @return the total number of messages.
     */
    public int getTotalMessageCount() {
        return mTotalMessageCount;
    }

    /**
     * @return current cursor position, starting from 0.
     */
    public int getCurrentPosition() {
        return mCurrentPosition;
    }

    /**
     * @return a {@link Handler} for {@link ContentObserver}.
     *
     * Unit tests override this and return null, so that {@link ContentObserver#onChange} is
     * called synchronously.
     */
    /* package */ Handler getHandlerForContentObserver() {
        return new Handler();
    }

    private boolean isTaskRunning() {
        return mLoadMessageListTask != null;
    }

    private void startTask() {
        cancelTask();
        startQuery();
    }

    /**
     * Start {@link LoadMessageListTask} to query DB.
     * Unit tests override this to make tests synchronous and to inject a mock query.
     */
    /* package */ void startQuery() {
        mLoadMessageListTask = new LoadMessageListTask();
        mLoadMessageListTask.executeParallel();
    }

    private void cancelTask() {
        Utility.cancelTaskInterrupt(mLoadMessageListTask);
        mLoadMessageListTask = null;
    }

    private void closeCursor() {
        if (mCursor != null) {
            mCursor.close();
            mCursor = null;
        }
    }

    private void setCurrentMessageIdFromCursor() {
        if (mCursor != null) {
            mCurrentMessageId = mCursor.getLong(EmailContent.ID_PROJECTION_COLUMN);
        }
    }

    private void onContentChanged() {
        if (!isTaskRunning()) { // Start only if not running already.
            startTask();
        }
    }

    /**
     * Shutdown itself and release resources.
     */
    public void close() {
        mClosed = true;
        mDelayedOperations.removeCallbacks();
        cancelTask();
        closeCursor();
    }

    public long getCurrentMessageId() {
        return mCurrentMessageId;
    }

    /**
     * Set the current message id.  As a result, either {@link Callback#onMessagesChanged} or
     * {@link Callback#onMessageNotFound} is called.
     */
    public void moveTo(long messageId) {
        if (mCurrentMessageId != messageId) {
            mCurrentMessageId = messageId;
            adjustCursorPosition();
        }
    }

    private void adjustCursorPosition() {
        mCurrentPosition = 0;
        if (mCurrentMessageId == -1) {
            return; // Current ID not specified yet.
        }
        if (mCursor == null) {
            // Task not finished yet.
            // We call adjustCursorPosition() again when we've opened a cursor.
            return;
        }
        mCursor.moveToPosition(-1);
        while (mCursor.moveToNext()
                && mCursor.getLong(EmailContent.ID_PROJECTION_COLUMN) != mCurrentMessageId) {
            mCurrentPosition++;
        }
        if (mCursor.isAfterLast()) {
            mCurrentPosition = 0;
            mCallback.onMessageNotFound(); // Message not found... Already deleted?
        } else {
            mCallback.onMessagesChanged();
        }
    }

    /**
     * @return true if the message set to {@link #moveTo} has an older message in the mailbox.
     * false otherwise, or unknown yet.
     */
    public boolean canMoveToOlder() {
        return (mCursor != null) && !mCursor.isLast();
    }


    /**
     * @return true if the message set to {@link #moveTo} has an newer message in the mailbox.
     * false otherwise, or unknown yet.
     */
    public boolean canMoveToNewer() {
        return (mCursor != null) && !mCursor.isFirst();
    }

    /**
     * Move to the older message.
     *
     * @return true iif succeed, and {@link Callback#onMessagesChanged} is called.
     */
    public boolean moveToOlder() {
        if (canMoveToOlder() && mCursor.moveToNext()) {
            mCurrentPosition++;
            setCurrentMessageIdFromCursor();
            mCallback.onMessagesChanged();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Move to the newer message.
     *
     * @return true iif succeed, and {@link Callback#onMessagesChanged} is called.
     */
    public boolean moveToNewer() {
        if (canMoveToNewer() && mCursor.moveToPrevious()) {
            mCurrentPosition--;
            setCurrentMessageIdFromCursor();
            mCallback.onMessagesChanged();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Task to open a Cursor on a worker thread.
     */
    private class LoadMessageListTask extends EmailAsyncTask<Void, Void, Cursor> {
        public LoadMessageListTask() {
            super(null);
        }

        @Override
        protected Cursor doInBackground(Void... params) {
            return openNewCursor();
        }

        @Override
        protected void onCancelled(Cursor cursor) {
            if (cursor != null) {
                cursor.close();
            }
            onCursorOpenDone(null);
        }

        @Override
        protected void onSuccess(Cursor cursor) {
            onCursorOpenDone(cursor);
        }
    }

    /**
     * Open a new cursor for a message list.
     *
     * This method is called on a worker thread by LoadMessageListTask.
     */
    private Cursor openNewCursor() {
        final Cursor cursor = mContentResolver.query(EmailContent.Message.CONTENT_URI,
                EmailContent.ID_PROJECTION, Message.buildMessageListSelection(mContext, mMailboxId),
                null, EmailContent.MessageColumns.TIMESTAMP + " DESC");
        return cursor;
    }

    /**
     * Called when {@link #openNewCursor()} is finished.
     *
     * Unit tests call this directly to inject a mock cursor.
     */
    /* package */ void onCursorOpenDone(Cursor cursor) {
        try {
            closeCursor();
            if (cursor == null || cursor.isClosed()) {
                mTotalMessageCount = 0;
                mCurrentPosition = 0;
                return; // Task canceled
            }
            mCursor = cursor;
            mTotalMessageCount = mCursor.getCount();
            mCursor.registerContentObserver(mObserver);
            adjustCursorPosition();
        } finally {
            mLoadMessageListTask = null; // isTaskRunning() becomes false.
        }
    }
}
