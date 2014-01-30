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


import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

public final class IntentUtilities {

    public static final String PATH_SETTINGS = "settings";

    // Format for activity URIs: content://ui.email.android.com/...
    private static final String ACTIVITY_INTENT_SCHEME = "content";
    private static final String ACTIVITY_INTENT_HOST = "ui.email.android.com";

    private static final String ACCOUNT_ID_PARAM = "ACCOUNT_ID";
    private static final String ACCOUNT_NAME_PARAM = "ACCOUNT_NAME";
    private static final String MAILBOX_ID_PARAM = "MAILBOX_ID";
    private static final String MESSAGE_ID_PARAM = "MESSAGE_ID";
    private static final String ACCOUNT_UUID_PARAM = "ACCOUNT_UUID";

    private IntentUtilities() {
    }

    /**
     * @return a URI builder for "content://ui.email.android.com/..."
     */
    public static Uri.Builder createActivityIntentUrlBuilder(String path) {
        final Uri.Builder b = new Uri.Builder();
        b.scheme(ACTIVITY_INTENT_SCHEME);
        b.authority(ACTIVITY_INTENT_HOST);
        b.path(path);
        return b;
    }

    /**
     * Add the account ID parameter.
     */
    public static void setAccountId(Uri.Builder b, long accountId) {
        if (accountId != -1) {
            b.appendQueryParameter(ACCOUNT_ID_PARAM, Long.toString(accountId));
        }
    }

    /**
     * Add the account name parameter.
     */
    public static void setAccountName(Uri.Builder b, String accountName) {
        if (accountName != null) {
            b.appendQueryParameter(ACCOUNT_NAME_PARAM, accountName);
        }
    }

    /**
     * Add the mailbox ID parameter.
     */
    public static void setMailboxId(Uri.Builder b, long mailboxId) {
        if (mailboxId != -1) {
            b.appendQueryParameter(MAILBOX_ID_PARAM, Long.toString(mailboxId));
        }
    }

    /**
     * Add the message ID parameter.
     */
    public static void setMessageId(Uri.Builder b, long messageId) {
        if (messageId != -1) {
            b.appendQueryParameter(MESSAGE_ID_PARAM, Long.toString(messageId));
        }
    }

    /**
     * Add the account UUID parameter.
     */
    public static void setAccountUuid(Uri.Builder b, String mUuid) {
        if (TextUtils.isEmpty(mUuid)) {
            throw new IllegalArgumentException();
        }
        b.appendQueryParameter(ACCOUNT_UUID_PARAM, mUuid);
    }

    /**
     * Retrieve the account ID from the underlying URI.
     */
    public static long getAccountIdFromIntent(Intent intent) {
        return getLongFromIntent(intent, ACCOUNT_ID_PARAM);
    }

    /**
     * Retrieve the account name.
     */
    public static String getAccountNameFromIntent(Intent intent) {
        return getStringFromIntent(intent, ACCOUNT_NAME_PARAM);
    }

    /**
     * Retrieve the mailbox ID.
     */
    public static long getMailboxIdFromIntent(Intent intent) {
        return getLongFromIntent(intent, MAILBOX_ID_PARAM);
    }

    /**
     * Retrieve the message ID.
     */
    public static long getMessageIdFromIntent(Intent intent) {
        return getLongFromIntent(intent, MESSAGE_ID_PARAM);
    }

    /**
     * Retrieve the account UUID, or null if the UUID param is not found.
     */
    public static String getAccountUuidFromIntent(Intent intent) {
        final Uri uri = intent.getData();
        if (uri == null) {
            return null;
        }
        String uuid = uri.getQueryParameter(ACCOUNT_UUID_PARAM);
        return TextUtils.isEmpty(uuid) ? null : uuid;
    }

    private static long getLongFromIntent(Intent intent, String paramName) {
        long value = -1;
        if (intent.getData() != null) {
            value = getLongParamFromUri(intent.getData(), paramName, -1);
        }
        return value;
    }

    private static String getStringFromIntent(Intent intent, String paramName) {
        String value = null;
        if (intent.getData() != null) {
            value = getStringParamFromUri(intent.getData(), paramName, null);
        }
        return value;
    }

    private static long getLongParamFromUri(Uri uri, String paramName, long defaultValue) {
        final String value = uri.getQueryParameter(paramName);
        if (!TextUtils.isEmpty(value)) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                // return default
            }
        }
        return defaultValue;
    }

    private static String getStringParamFromUri(Uri uri, String paramName, String defaultValue) {
        final String value = uri.getQueryParameter(paramName);
        if (value == null) {
            return defaultValue;
        }
        return value;
    }

    /**
     * Create an {@link Intent} to launch an activity as the main entry point.  Existing activities
     * will all be closed.
     */
    public static Intent createRestartAppIntent(Context context, Class<? extends Activity> clazz) {
        Intent i = new Intent(context, clazz);
        prepareRestartAppIntent(i);
        return i;
    }

    /**
     * Create an {@link Intent} to launch an activity as the main entry point.  Existing activities
     * will all be closed.
     */
    public static Intent createRestartAppIntent(Uri data) {
        Intent i = new Intent(Intent.ACTION_MAIN, data);
        prepareRestartAppIntent(i);
        return i;
    }

    private static void prepareRestartAppIntent(Intent i) {
        i.setAction(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
