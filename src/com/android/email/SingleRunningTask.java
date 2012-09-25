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

package com.android.email;

import com.android.emailcommon.Logging;

import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for a task that runs at most one instance at any given moment.
 *
 * Call {@link #run} to start the task.  If the task is already running on another thread, it'll do
 * nothing.
 */
public abstract class SingleRunningTask<Param> {
    private final AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private final String mLogTaskName;

    public SingleRunningTask(String logTaskName) {
        mLogTaskName = logTaskName;
    }

    /**
     * Calls {@link #runInternal} if it's not running already.
     */
    public final void run(Param param) {
        if (mIsRunning.compareAndSet(false, true)) {
            Log.i(Logging.LOG_TAG,  mLogTaskName + ": start");
            try {
                runInternal(param);
            } finally {
                Log.i(Logging.LOG_TAG, mLogTaskName + ": done");
                mIsRunning.set(false);
            }
        } else {
            // Already running -- do nothing.
            Log.i(Logging.LOG_TAG, mLogTaskName + ": already running");
        }
    }

    /**
     * The actual task must be implemented by subclasses.
     */
    protected abstract void runInternal(Param param);

    /* package */ boolean isRunningForTest() {
        return mIsRunning.get();
    }
}
