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

import com.android.email.Clock;

import android.content.Context;
import android.content.CursorLoader;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

import java.security.InvalidParameterException;
import java.util.Timer;
import java.util.TimerTask;

/**
 * A {@link CursorLoader} variant that throttle auto-requery on content changes.
 *
 * This class overrides {@link android.content.Loader#onContentChanged}, and instead of immediately
 * requerying, it waits until the specified timeout before doing so.
 *
 * There are two timeout settings: {@link #mMinTimeout} and {@link #mMaxTimeout}.
 * We normally use {@link #mMinTimeout}, but if we detect more than one change in
 * the {@link #TIMEOUT_EXTEND_INTERVAL} period, we double it, until it reaches {@link #mMaxTimeout}.
 */
public class ThrottlingCursorLoader extends CursorLoader {
    private static final boolean DEBUG = false; // Don't submit with true

    /* package */ static final int TIMEOUT_EXTEND_INTERVAL = 500;

    private static final int DEFAULT_MIN_TIMEOUT = 150;
    private static final int DEFAULT_MAX_TIMEOUT = 2500;

    private static Timer sTimer = new Timer();

    private final Clock mClock;

    /** Handler for the UI thread. */
    private final Handler mHandler = new Handler();

    /** Minimum (default) timeout */
    private final int mMinTimeout;

    /** Max timeout */
    private final int mMaxTimeout;

    /** Content change auto-requery timeout, in milliseconds. */
    private int mTimeout;

    /** When onChanged() was last called. */
    private long mLastOnChangedTime;

    private ForceLoadTimerTask mRunningForceLoadTimerTask;

    /** Constructor with default timeout */
    public ThrottlingCursorLoader(Context context, Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        this(context, uri, projection, selection, selectionArgs, sortOrder, DEFAULT_MIN_TIMEOUT,
                DEFAULT_MAX_TIMEOUT);
    }

    /** Constructor that takes custom timeout */
    public ThrottlingCursorLoader(Context context, Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder, int minTimeout, int maxTimeout) {
        this(context, uri, projection, selection, selectionArgs, sortOrder, minTimeout, maxTimeout,
                Clock.INSTANCE);
    }

    /** Constructor for tests.  Clock is injectable. */
    /* package */ ThrottlingCursorLoader(Context context, Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder,
            int minTimeout, int maxTimeout, Clock clock) {
        super(context, uri, projection, selection, selectionArgs, sortOrder);
        mClock = clock;
        if (maxTimeout < minTimeout) {
            throw new InvalidParameterException();
        }
        mMinTimeout = minTimeout;
        mMaxTimeout = maxTimeout;
        mTimeout = mMinTimeout;
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

    /* package */ void updateTimeout() {
        final long now = mClock.getTime();
        if ((now - mLastOnChangedTime) <= TIMEOUT_EXTEND_INTERVAL) {
            if (DEBUG) debugLog("Extending timeout: " + mTimeout);
            mTimeout *= 2;
            if (mTimeout >= mMaxTimeout) {
                mTimeout = mMaxTimeout;
            }
        } else {
            if (DEBUG) debugLog("Resetting timeout.");
            mTimeout = mMinTimeout;
        }

        mLastOnChangedTime = now;
    }

    @Override
    public void onContentChanged() {
        if (DEBUG) debugLog("onContentChanged");

        updateTimeout();

        if (isForceLoadScheduled()) {
            if (DEBUG) debugLog("    forceLoad already scheduled.");
        } else {
            if (DEBUG) debugLog("    scheduling forceLoad.");
            mRunningForceLoadTimerTask = new ForceLoadTimerTask();
            sTimer.schedule(mRunningForceLoadTimerTask, mTimeout);
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

    /* package */ int getTimeoutForTest() {
        return mTimeout;
    }

    /* package */ long getLastOnChangedTimeForTest() {
        return mLastOnChangedTime;
    }
}
