/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.emailcommon.utility;

import android.os.AsyncTask;

import java.util.LinkedList;
import java.util.concurrent.ExecutionException;

/**
 * {@link AsyncTask} substitution for the email app.
 *
 * Modeled after {@link AsyncTask}; the basic usage is the same, with extra features:
 * - Bulk cancellation of multiple tasks.  This is mainly used by UI to cancell pending tasks
 *   in onDestroy() or similar places.
 * - More features to come...
 *
 * Note this class isn't 100% compatible to the regular {@link AsyncTask}, e.g. it lacks
 * {@link AsyncTask#onProgressUpdate}.  Add these when necessary.
 */
public abstract class EmailAsyncTask<Params, Progress, Result> {
    /**
     * Tracks {@link EmailAsyncTask}.
     *
     * Call {@link #cancellAllInterrupt()} to cancel all tasks registered.
     */
    public static class Tracker {
        private final LinkedList<EmailAsyncTask<?, ?, ?>> mTasks =
                new LinkedList<EmailAsyncTask<?, ?, ?>>();

        private void add(EmailAsyncTask<?, ?, ?> task) {
            synchronized (mTasks) {
                mTasks.add(task);
            }
        }

        private void remove(EmailAsyncTask<?, ?, ?> task) {
            synchronized (mTasks) {
                mTasks.remove(task);
            }
        }

        /**
         * Cancel all registered tasks.
         */
        public void cancellAllInterrupt() {
            synchronized (mTasks) {
                for (EmailAsyncTask<?, ?, ?> task : mTasks) {
                    task.cancel(true);
                }
                mTasks.clear();
            }
        }

        /* package */ int getTaskCountForTest() {
            return mTasks.size();
        }
    }

    private final Tracker mTracker;

    private static class InnerTask<Params2, Progress2, Result2>
            extends AsyncTask<Params2, Progress2, Result2> {
        private final EmailAsyncTask<Params2, Progress2, Result2> mOwner;

        public InnerTask(EmailAsyncTask<Params2, Progress2, Result2> owner) {
            mOwner = owner;
        }

        @Override
        protected Result2 doInBackground(Params2... params) {
            return mOwner.doInBackground(params);
        }

        @Override
        public void onCancelled(Result2 result) {
            mOwner.unregisterSelf();
            mOwner.onCancelled(result);
        }

        @Override
        public void onPostExecute(Result2 result) {
            mOwner.unregisterSelf();
            mOwner.onPostExecute(result);
        }
    }

    private final InnerTask<Params, Progress, Result> mInnerTask;

    public EmailAsyncTask(Tracker tracker) {
        mTracker = tracker;
        if (mTracker != null) {
            mTracker.add(this);
        }
        mInnerTask = new InnerTask<Params, Progress, Result>(this);
    }

    /* package */ void unregisterSelf() {
        if (mTracker != null) {
            mTracker.remove(this);
        }
    }

    protected abstract Result doInBackground(Params... params);

    public final boolean cancel(boolean mayInterruptIfRunning) {
        return mInnerTask.cancel(mayInterruptIfRunning);
    }

    protected void onCancelled(Result result) {
    }

    protected void onPostExecute(Result result) {
    }

    public final EmailAsyncTask<Params, Progress, Result> execute(Params... params) {
        mInnerTask.execute(params);
        return this;
    }

    public final Result get() throws InterruptedException, ExecutionException {
        return mInnerTask.get();
    }

    public final boolean isCancelled() {
        return mInnerTask.isCancelled();
    }

    /* package */ Result callDoInBackgroundForTest(Params... params) {
        return mInnerTask.doInBackground(params);
    }

    /* package */ void callOnCancelledForTest(Result result) {
        mInnerTask.onCancelled(result);
    }

    /* package */ void callOnPostExecuteForTest(Result result) {
        mInnerTask.onPostExecute(result);
    }
}
