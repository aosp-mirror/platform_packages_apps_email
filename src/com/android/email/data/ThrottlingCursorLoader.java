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

import com.android.email.Throttle;
import com.android.emailcommon.Logging;

import android.content.Context;
import android.content.CursorLoader;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;

/**
 * A {@link CursorLoader} variant that throttle auto-requery on content changes using
 * {@link Throttle}.
 */
public class ThrottlingCursorLoader extends CursorLoader {
    private final Throttle mThrottle;

    /** Constructor with default timeout */
    public ThrottlingCursorLoader(Context context, Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        this(context, uri, projection, selection, selectionArgs, sortOrder,
                Throttle.DEFAULT_MIN_TIMEOUT, Throttle.DEFAULT_MAX_TIMEOUT);
    }

    /** Constructor that takes custom timeout */
    public ThrottlingCursorLoader(Context context, Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder, int minTimeout, int maxTimeout) {
        super(context, uri, projection, selection, selectionArgs, sortOrder);

        Runnable forceLoadRunnable = new Runnable() {
            @Override
            public void run() {
                callSuperOnContentChanged();
            }
        };
        mThrottle = new Throttle(uri.toString(), forceLoadRunnable, new Handler(),
                minTimeout, maxTimeout);
    }

    private void debugLog(String message) {
        Log.d(Logging.LOG_TAG, "ThrottlingCursorLoader: [" + getUri() + "] " + message);
    }

    @Override
    protected void onStartLoading() {
        if (Throttle.DEBUG) debugLog("startLoading");
        mThrottle.cancelScheduledCallback();
        super.onStartLoading();
    }

    @Override
    protected void onForceLoad() {
        if (Throttle.DEBUG) debugLog("forceLoad");
        mThrottle.cancelScheduledCallback();
        super.onForceLoad();
    }

    @Override
    protected void onStopLoading() {
        if (Throttle.DEBUG) debugLog("stopLoading");
        mThrottle.cancelScheduledCallback();
        super.onStopLoading();
    }

    @Override
    public void onCanceled(Cursor cursor) {
        if (Throttle.DEBUG) debugLog("onCancelled");
        mThrottle.cancelScheduledCallback();
        super.onCanceled(cursor);
    }

    @Override
    protected void onReset() {
        if (Throttle.DEBUG) debugLog("onReset");
        mThrottle.cancelScheduledCallback();
        super.onReset();
    }

    @Override
    public void onContentChanged() {
        if (Throttle.DEBUG) debugLog("onContentChanged");

        mThrottle.onEvent();
    }

    private void callSuperOnContentChanged() {
        if (Throttle.DEBUG) debugLog("callSuperOnContentChanged");
        super.onContentChanged();
    }
}
