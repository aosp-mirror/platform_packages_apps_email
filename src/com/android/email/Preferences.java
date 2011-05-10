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

import java.util.HashMap;
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
    private static final String MESSAGE_NOTIFICATION_TABLE = "messageNotificationTable";

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

    public HashMap<Long, long[]> getMessageNotificationTable() {
        HashMap<Long, long[]> table = new HashMap<Long, long[]>();
        // The table is encoded as a string with the following format:
        //   K:V1,V2;K:V1,V2;...
        // Where 'K' is the table key and 'V1' and 'V2' are the array values associated with the key
        // Multiple key/value pairs are separated from one another by a ';'.
        String preference = mSharedPreferences.getString(MESSAGE_NOTIFICATION_TABLE, "");
        String[] entries = preference.split(";");
        for (String entry : entries) {
            try {
                String hash[] = entry.split(":");
                if (hash.length != 2) continue;
                String stringValues[] = hash[1].split(",");
                if (stringValues.length != 2) continue;
                long key = Long.parseLong(hash[0]);
                long[] value = new long[2];
                value[0] = Long.parseLong(stringValues[0]);
                value[1] = Long.parseLong(stringValues[1]);
                table.put(key, value);
            } catch (NumberFormatException e) {
                Log.w(Logging.LOG_TAG, "notification table preference corrupt");
                continue;
            }
        }
        return table;
    }

    /**
     * Sets the message notification table.
     * @throws IllegalArgumentException if the given table is null or any of the value arrays do
     *      not have exactly 2 elements.
     */
    public void setMessageNotificationTable(HashMap<Long, long[]> notificationTable) {
        StringBuffer sb = new StringBuffer();
        boolean first = true;
        if (notificationTable == null) throw new IllegalArgumentException("table cannot be null");
        for (Long key : notificationTable.keySet()) {
            if (!first) {
                sb.append(';');
            }
            long[] value = notificationTable.get(key);
            if (value == null || value.length != 2) {
                throw new IllegalArgumentException("value array must contain 2 elements");
            }
            sb.append(key).append(':').append(value[0]).append(',').append(value[1]);
            first = false;
        }
        mSharedPreferences.edit().putString(MESSAGE_NOTIFICATION_TABLE, sb.toString()).apply();
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
