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

package org.apache.james.mime4j;

import com.android.mail.utils.LogUtils;

/**
 * Empty stub for the apache logging library.
 */
public class Log {
    private static final String LOG_TAG = new LogUtils().getLogTag();

    public Log(Class mClazz) {
    }

    public boolean isDebugEnabled() {
        return false;
    }

    public boolean isErrorEnabled() {
        return true;
    }

    public boolean isFatalEnabled() {
        return true;
    }

    public boolean isInfoEnabled() {
        return false;
    }

    public boolean isTraceEnabled() {
        return false;
    }

    public boolean isWarnEnabled() {
        return true;
    }

    public void trace(Object message) {
        if (!isTraceEnabled()) return;
        android.util.Log.v(LOG_TAG, toString(message, null));
    }

    public void trace(Object message, Throwable t) {
        if (!isTraceEnabled()) return;
        android.util.Log.v(LOG_TAG, toString(message, t));
    }

    public void debug(Object message) {
        if (!isDebugEnabled()) return;
        android.util.Log.d(LOG_TAG, toString(message, null));
    }

    public void debug(Object message, Throwable t) {
        if (!isDebugEnabled()) return;
        android.util.Log.d(LOG_TAG, toString(message, t));
    }

    public void info(Object message) {
        if (!isInfoEnabled()) return;
        android.util.Log.i(LOG_TAG, toString(message, null));
    }

    public void info(Object message, Throwable t) {
        if (!isInfoEnabled()) return;
        android.util.Log.i(LOG_TAG, toString(message, t));
    }

    public void warn(Object message) {
        android.util.Log.w(LOG_TAG, toString(message, null));
    }

    public void warn(Object message, Throwable t) {
        android.util.Log.w(LOG_TAG, toString(message, t));
    }

    public void error(Object message) {
        android.util.Log.e(LOG_TAG, toString(message, null));
    }

    public void error(Object message, Throwable t) {
        android.util.Log.e(LOG_TAG, toString(message, t));
    }

    public void fatal(Object message) {
        android.util.Log.e(LOG_TAG, toString(message, null));
    }

    public void fatal(Object message, Throwable t) {
        android.util.Log.e(LOG_TAG, toString(message, t));
    }

    private static String toString(Object o, Throwable t) {
        String m = (o == null) ? "(null)" : o.toString();
        if (t == null) {
            return m;
        } else {
            return m + " " + t.getMessage();
        }
    }
}
