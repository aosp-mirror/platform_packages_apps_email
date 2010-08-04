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

package com.android.email.data;

import android.content.Context;
import android.content.CursorLoader;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import java.util.Timer;
import java.util.TimerTask;

/**
 * A {@link CursorLoader} variant that throttle auto-requery on content changes.
 *
 * This class overrides {@link android.content.Loader#onContentChanged}, and instead of immediately
 * requerying, it waits until the specified timeout before doing so.
 */
public class ThrottlingCursorLoader extends CursorLoader {
    private static final boolean DEBUG = false; // Don't submit with true

    private static Timer sTimer = new Timer();

    /** Handler for the UI thread. */
    private final Handler mHandler = new Handler();

    /** Content change auto-requery timeout, in milliseconds. */
    private final int mTimeout;

    private ForceLoadTimerTask mRunningForceLoadTimerTask;

    /**
     * Constructor.  Same as the one of {@link CursorLoader}, but takes {@code timeoutSecond}.
     *
     * @param timeout Content change auto-requery timeout in milliseconds.
     */
    public ThrottlingCursorLoader(Context context, Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder, int timeout) {
        super(context, uri, projection, selection, selectionArgs, sortOrder);
        mTimeout = timeout;
    }

    private void debugLog(String message) {
        Log.d("ThrottlingCursorLoader", "[" + getUri() + "] " + message);
    }

    /**
     * @return true if forceLoad() is scheduled.
     */
    private boolean isForceLoadScheduled() {
        return mRunningForceLoadTimerTask != null;
    }

    /**
     * Cancel the scheduled forceLoad(), if exists.
     */
    private void cancelScheduledForceLoad() {
        if (DEBUG) debugLog("cancelScheduledForceLoad");
        if (mRunningForceLoadTimerTask != null) {
            mRunningForceLoadTimerTask.cancel();
            mRunningForceLoadTimerTask = null;
        }
    }

    @Override
    public void startLoading() {
        if (DEBUG) debugLog("startLoading");
        cancelScheduledForceLoad();
        super.startLoading();
    }

    @Override
    public void forceLoad() {
        if (DEBUG) debugLog("forceLoad");
        cancelScheduledForceLoad();
        super.forceLoad();
    }

    @Override
    public void stopLoading() {
        if (DEBUG) debugLog("stopLoading");
        cancelScheduledForceLoad();
        super.stopLoading();
    }

    @Override
    public void onContentChanged() {
        if (DEBUG) debugLog("onContentChanged");
        if (mTimeout <= 0) {
            forceLoad();
        } else {
            if (isForceLoadScheduled()) {
                if (DEBUG) debugLog("    forceLoad already scheduled.");
            } else {
                if (DEBUG) debugLog("    scheduling forceLoad.");
                mRunningForceLoadTimerTask = new ForceLoadTimerTask();
                sTimer.schedule(mRunningForceLoadTimerTask, mTimeout);
            }
        }
    }

    /**
     * A {@link TimerTask} to call {@link #forceLoad} on the UI thread.
     */
    private class ForceLoadTimerTask extends TimerTask {
        private boolean mCanceled;

        @Override
        public void run() {
            mHandler.post(new ForceLoadRunnable());
        }

        @Override
        public boolean cancel() {
            mCanceled = true;
            return super.cancel();
        }

        private class ForceLoadRunnable implements Runnable {
            @Override
            public void run() {
                if (!mCanceled) { // This check has to be done on the UI thread.
                    forceLoad();
                }
            }
        }
    }
}
