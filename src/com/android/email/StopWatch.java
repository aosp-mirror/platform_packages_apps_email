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
import com.android.mail.utils.LogUtils;

import android.os.SystemClock;

/**
 * A simple class to measure elapsed time.
 *
 * <code>
 *   StopWatch s = StopWatch.start();
 *   // Do your stuff
 *   s.split();
 *   // More stuff
 *   s.split();
 *   // More stuff
 *   s.stop();
 * </code>
 */
public class StopWatch {
    private final String mName;
    private final long mStart;
    private long mLastSplit;

    private StopWatch(String name) {
        mName = name;
        mStart = getCurrentTime();
        mLastSplit = mStart;
        LogUtils.w(Logging.LOG_TAG, "StopWatch(" + mName + ") start");
    }

    public static StopWatch start(String name) {
        return new StopWatch(name);
    }

    public void split(String label) {
        long now = getCurrentTime() ;
        long elapse = now - mLastSplit;
        LogUtils.w(Logging.LOG_TAG, "StopWatch(" + mName + ") split(" + label + ") " + elapse);
        mLastSplit = now;
    }

    public void stop() {
        long now = getCurrentTime();
        LogUtils.w(Logging.LOG_TAG, "StopWatch(" + mName + ") stop: "
                + (now - mLastSplit)
                + "  (total " + (now - mStart) + ")");
    }

    private static long getCurrentTime() {
        // We might want to use other counters, such as currentThreadTimeMillis().
        // TODO add option for that?
        return SystemClock.elapsedRealtime();
    }
}
