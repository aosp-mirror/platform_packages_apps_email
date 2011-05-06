/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.UUID;

public class Preferences {

    // Preferences file
    private static final String PREFERENCES_FILE = "AndroidMail.Main";

    // Preferences field names
    private static final String ACCOUNT_UUIDS = "accountUuids";
    private static final String ENABLE_DEBUG_LOGGING = "enableDebugLogging";
    private static final String ENABLE_EXCHANGE_LOGGING = "enableExchangeLogging";
    private static final String ENABLE_EXCHANGE_FILE_LOGGING = "enableExchangeFileLogging";
    private static final String INHIBIT_GRAPHICS_ACCELERATION = "inhibitGraphicsAcceleration";
    private static final String FORCE_ONE_MINUTE_REFRESH = "forceOneMinuteRefresh";
    private static final String ENABLE_STRICT_MODE = "enableStrictMode";
    private static final String DEVICE_UID = "deviceUID";
    private static final String ONE_TIME_INITIALIZATION_PROGRESS = "oneTimeInitializationProgress";
    private static final String AUTO_ADVANCE_DIRECTION = "autoAdvance";
    private static final String TEXT_ZOOM = "textZoom";
    private static final String BACKGROUND_ATTACHMENTS = "backgroundAttachments";

    public static final int AUTO_ADVANCE_NEWER = 0;
    public static final int AUTO_ADVANCE_OLDER = 1;
    public static final int AUTO_ADVANCE_MESSAGE_LIST = 2;
    // "move to older" was the behavior on older versions.
    private static final int AUTO_ADVANCE_DEFAULT = AUTO_ADVANCE_OLDER;

    // The following constants are used as offsets into TEXT_ZOOM_ARRAY (below)
    public static final int TEXT_ZOOM_TINY = 0;
    public static final int TEXT_ZOOM_SMALL = 1;
    public static final int TEXT_ZOOM_NORMAL = 2;
    public static final int TEXT_ZOOM_LARGE = 3;
    public static final int TEXT_ZOOM_HUGE = 4;
    // "normal" will be the default
    public static final int TEXT_ZOOM_DEFAULT = TEXT_ZOOM_NORMAL;

    private static Preferences sPreferences;

    final SharedPreferences mSharedPreferences;

    private Preferences(Context context) {
        mSharedPreferences = context.getSharedPreferences(PREFERENCES_FILE, Context.MODE_PRIVATE);
    }

    /**
     * TODO need to think about what happens if this gets GCed along with the
     * Activity that initialized it. Do we lose ability to read Preferences in
     * further Activities? Maybe this should be stored in the Application
     * context.
     */
    public static synchronized Preferences getPreferences(Context context) {
        if (sPreferences == null) {
            sPreferences = new Preferences(context);
        }
        return sPreferences;
    }

    public static String getLegacyBackupPreference(Context context) {
        return getPreferences(context).mSharedPreferences.getString(ACCOUNT_UUIDS, null);
    }

    public static void clearLegacyBackupPreference(Context context) {
        getPreferences(context).mSharedPreferences.edit().remove(ACCOUNT_UUIDS).apply();
    }

    public void setEnableDebugLogging(boolean value) {
        mSharedPreferences.edit().putBoolean(ENABLE_DEBUG_LOGGING, value).apply();
    }

    public boolean getEnableDebugLogging() {
        return mSharedPreferences.getBoolean(ENABLE_DEBUG_LOGGING, false);
    }

    public void setEnableExchangeLogging(boolean value) {
        mSharedPreferences.edit().putBoolean(ENABLE_EXCHANGE_LOGGING, value).apply();
    }

    public boolean getEnableExchangeLogging() {
        return mSharedPreferences.getBoolean(ENABLE_EXCHANGE_LOGGING, false);
    }

    public void setEnableExchangeFileLogging(boolean value) {
        mSharedPreferences.edit().putBoolean(ENABLE_EXCHANGE_FILE_LOGGING, value).apply();
    }

    public boolean getEnableExchangeFileLogging() {
        return mSharedPreferences.getBoolean(ENABLE_EXCHANGE_FILE_LOGGING, false);
    }

    public void setInhibitGraphicsAcceleration(boolean value) {
        mSharedPreferences.edit().putBoolean(INHIBIT_GRAPHICS_ACCELERATION, value).apply();
    }

    public boolean getInhibitGraphicsAcceleration() {
        return mSharedPreferences.getBoolean(INHIBIT_GRAPHICS_ACCELERATION, false);
    }

    public void setForceOneMinuteRefresh(boolean value) {
        mSharedPreferences.edit().putBoolean(FORCE_ONE_MINUTE_REFRESH, value).apply();
    }

    public boolean getForceOneMinuteRefresh() {
        return mSharedPreferences.getBoolean(FORCE_ONE_MINUTE_REFRESH, false);
    }

    public void setEnableStrictMode(boolean value) {
        mSharedPreferences.edit().putBoolean(ENABLE_STRICT_MODE, value).apply();
    }

    public boolean getEnableStrictMode() {
        return mSharedPreferences.getBoolean(ENABLE_STRICT_MODE, false);
    }

    /**
     * Generate a new "device UID".  This is local to Email app only, to prevent possibility
     * of correlation with any other user activities in any other apps.
     * @return a persistent, unique ID
     */
    public synchronized String getDeviceUID() {
         String result = mSharedPreferences.getString(DEVICE_UID, null);
         if (result == null) {
             result = UUID.randomUUID().toString();
             mSharedPreferences.edit().putString(DEVICE_UID, result).apply();
         }
         return result;
    }

    public int getOneTimeInitializationProgress() {
        return mSharedPreferences.getInt(ONE_TIME_INITIALIZATION_PROGRESS, 0);
    }

    public void setOneTimeInitializationProgress(int progress) {
        mSharedPreferences.edit().putInt(ONE_TIME_INITIALIZATION_PROGRESS, progress).apply();
    }

    public int getAutoAdvanceDirection() {
        return mSharedPreferences.getInt(AUTO_ADVANCE_DIRECTION, AUTO_ADVANCE_DEFAULT);
    }

    public void setAutoAdvanceDirection(int direction) {
        mSharedPreferences.edit().putInt(AUTO_ADVANCE_DIRECTION, direction).apply();
    }

    public int getTextZoom() {
        return mSharedPreferences.getInt(TEXT_ZOOM, TEXT_ZOOM_DEFAULT);
    }

    public void setTextZoom(int zoom) {
        mSharedPreferences.edit().putInt(TEXT_ZOOM, zoom).apply();
    }

    public boolean getBackgroundAttachments() {
        return mSharedPreferences.getBoolean(BACKGROUND_ATTACHMENTS, false);
    }

    public void setBackgroundAttachments(boolean allowed) {
        mSharedPreferences.edit().putBoolean(BACKGROUND_ATTACHMENTS, allowed).apply();
    }

    public void save() {
    }

    public void clear() {
        mSharedPreferences.edit().clear().apply();
    }

    public void dump() {
        if (Email.LOGD) {
            for (String key : mSharedPreferences.getAll().keySet()) {
                Log.v(Logging.LOG_TAG, key + " = " + mSharedPreferences.getAll().get(key));
            }
        }
    }
}
