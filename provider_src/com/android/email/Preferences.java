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

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.mail.utils.LogUtils;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Preferences {

    // Preferences file
    public static final String PREFERENCES_FILE = "AndroidMail.Main";

    // Preferences field names
    @Deprecated
    private static final String ACCOUNT_UUIDS = "accountUuids";
    private static final String ENABLE_DEBUG_LOGGING = "enableDebugLogging";
    private static final String ENABLE_EXCHANGE_LOGGING = "enableExchangeLogging";
    private static final String ENABLE_EXCHANGE_FILE_LOGGING = "enableExchangeFileLogging";
    private static final String ENABLE_STRICT_MODE = "enableStrictMode";
    private static final String DEVICE_UID = "deviceUID";
    private static final String ONE_TIME_INITIALIZATION_PROGRESS = "oneTimeInitializationProgress";
    private static final String LAST_ACCOUNT_USED = "lastAccountUsed";
    // The following are only used for migration
    @Deprecated
    private static final String AUTO_ADVANCE_DIRECTION = "autoAdvance";
    @Deprecated
    private static final String TRUSTED_SENDERS = "trustedSenders";
    @Deprecated
    private static final String CONFIRM_DELETE = "confirm_delete";
    @Deprecated
    private static final String CONFIRM_SEND = "confirm_send";
    @Deprecated
    private static final String SWIPE_DELETE = "swipe_delete";
    @Deprecated
    private static final String CONV_LIST_ICON = "conversation_list_icons";
    @Deprecated
    private static final String REPLY_ALL = "reply_all";

    @Deprecated
    public static final int AUTO_ADVANCE_NEWER = 0;
    @Deprecated
    public static final int AUTO_ADVANCE_OLDER = 1;
    @Deprecated
    public static final int AUTO_ADVANCE_MESSAGE_LIST = 2;
    // "move to older" was the behavior on older versions.
    @Deprecated
    private static final int AUTO_ADVANCE_DEFAULT = AUTO_ADVANCE_OLDER;
    @Deprecated
    private static final boolean CONFIRM_DELETE_DEFAULT = false;
    @Deprecated
    private static final boolean CONFIRM_SEND_DEFAULT = false;

    @Deprecated
    public static final String CONV_LIST_ICON_SENDER_IMAGE = "senderimage";
    @Deprecated
    public static final String CONV_LIST_ICON_NONE = "none";
    @Deprecated
    public static final String CONV_LIST_ICON_DEFAULT = CONV_LIST_ICON_SENDER_IMAGE;

    private static Preferences sPreferences;

    private final SharedPreferences mSharedPreferences;

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

    public static SharedPreferences getSharedPreferences(Context context) {
        return getPreferences(context).mSharedPreferences;
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

    /** @deprecated Only used for migration */
    @Deprecated
    public int getAutoAdvanceDirection() {
        return mSharedPreferences.getInt(AUTO_ADVANCE_DIRECTION, AUTO_ADVANCE_DEFAULT);
    }

    /** @deprecated Only used for migration */
    @Deprecated
    public String getConversationListIcon() {
        return mSharedPreferences.getString(CONV_LIST_ICON, CONV_LIST_ICON_SENDER_IMAGE);
    }

    /** @deprecated Only used for migration */
    @Deprecated
    public boolean getConfirmDelete() {
        return mSharedPreferences.getBoolean(CONFIRM_DELETE, CONFIRM_DELETE_DEFAULT);
    }

    /** @deprecated Only used for migration */
    @Deprecated
    public boolean getConfirmSend() {
        return mSharedPreferences.getBoolean(CONFIRM_SEND, CONFIRM_SEND_DEFAULT);
    }

    /** @deprecated Only used for migration */
    @Deprecated
    public boolean hasSwipeDelete() {
        return mSharedPreferences.contains(SWIPE_DELETE);
    }

    /** @deprecated Only used for migration */
    @Deprecated
    public boolean getSwipeDelete() {
        return mSharedPreferences.getBoolean(SWIPE_DELETE, false);
    }

    /** @deprecated Only used for migration */
    @Deprecated
    public boolean hasReplyAll() {
        return mSharedPreferences.contains(REPLY_ALL);
    }

    /** @deprecated Only used for migration */
    @Deprecated
    public boolean getReplyAll() {
        return mSharedPreferences.getBoolean(REPLY_ALL, false);
    }

    /**
     * @deprecated This has been moved to {@link com.android.mail.preferences.MailPrefs}, and is
     * only here for migration.
     */
    @Deprecated
    public Set<String> getWhitelistedSenderAddresses() {
        try {
            return parseEmailSet(mSharedPreferences.getString(TRUSTED_SENDERS, ""));
        } catch (JSONException e) {
            return Collections.emptySet();
        }
    }

    HashSet<String> parseEmailSet(String serialized) throws JSONException {
        HashSet<String> result = new HashSet<String>();
        if (!TextUtils.isEmpty(serialized)) {
            JSONArray arr = new JSONArray(serialized);
            for (int i = 0, len = arr.length(); i < len; i++) {
                result.add((String) arr.get(i));
            }
        }
        return result;
    }

    /**
     * Returns the last used account ID as set by {@link #setLastUsedAccountId}.
     * The system makes no attempt to automatically track what is considered a "use" - clients
     * are expected to call {@link #setLastUsedAccountId} manually.
     *
     * Note that the last used account may have been deleted in the background so there is also
     * no guarantee that the account exists.
     */
    public long getLastUsedAccountId() {
        return mSharedPreferences.getLong(LAST_ACCOUNT_USED, Account.NO_ACCOUNT);
    }

    /**
     * Sets the specified ID of the last account used. Treated as an opaque ID and does not
     * validate the value. Value is saved asynchronously.
     */
    public void setLastUsedAccountId(long accountId) {
        mSharedPreferences
                .edit()
                .putLong(LAST_ACCOUNT_USED, accountId)
                .apply();
    }

    public void clear() {
        mSharedPreferences.edit().clear().apply();
    }

    public void dump() {
        if (Logging.LOGD) {
            for (String key : mSharedPreferences.getAll().keySet()) {
                LogUtils.v(Logging.LOG_TAG, key + " = " + mSharedPreferences.getAll().get(key));
            }
        }
    }
}
