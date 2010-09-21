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

import com.android.email.activity.AccountShortcutPicker;
import com.android.email.activity.Debug;
import com.android.email.activity.MessageCompose;
import com.android.email.provider.EmailContent;
import com.android.email.service.MailService;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.text.format.DateUtils;
import android.util.Log;

import java.io.File;
import java.util.HashMap;

public class Email extends Application {
    public static final String LOG_TAG = "Email";

    /**
     * If this is enabled there will be additional logging information sent to
     * Log.d, including protocol dumps.
     *
     * This should only be used for logs that are useful for debbuging user problems,
     * not for internal/development logs.
     *
     * This can be enabled by typing "debug" in the AccountFolderList activity.
     * Changing the value to 'true' here will likely have no effect at all!
     *
     * TODO: rename this to sUserDebug, and rename LOGD below to DEBUG.
     */
    public static boolean DEBUG = false;

    /**
     * If this is enabled than logging that normally hides sensitive information
     * like passwords will show that information.
     */
    public static boolean DEBUG_SENSITIVE = false;

    /**
     * Set this to 'true' to enable as much Email logging as possible.
     * Do not check-in with it set to 'true'!
     */
    public static final boolean LOGD = false;

    /**
     * The MIME type(s) of attachments we're willing to send via attachments.
     *
     * Any attachments may be added via Intents with Intent.ACTION_SEND or ACTION_SEND_MULTIPLE.
     */
    public static final String[] ACCEPTABLE_ATTACHMENT_SEND_INTENT_TYPES = new String[] {
        "*/*",
    };

    /**
     * The MIME type(s) of attachments we're willing to send from the internal UI.
     *
     * NOTE:  At the moment it is not possible to open a chooser with a list of filter types, so
     * the chooser is only opened with the first item in the list.
     */
    public static final String[] ACCEPTABLE_ATTACHMENT_SEND_UI_TYPES = new String[] {
        "image/*",
        "video/*",
    };

    /**
     * The MIME type(s) of attachments we're willing to view.
     */
    public static final String[] ACCEPTABLE_ATTACHMENT_VIEW_TYPES = new String[] {
        "*/*",
    };

    /**
     * The MIME type(s) of attachments we're not willing to view.
     */
    public static final String[] UNACCEPTABLE_ATTACHMENT_VIEW_TYPES = new String[] {
    };

    /**
     * The MIME type(s) of attachments we're willing to download to SD.
     */
    public static final String[] ACCEPTABLE_ATTACHMENT_DOWNLOAD_TYPES = new String[] {
        "image/*",
    };

    /**
     * The MIME type(s) of attachments we're not willing to download to SD.
     */
    public static final String[] UNACCEPTABLE_ATTACHMENT_DOWNLOAD_TYPES = new String[] {
    };

    /**
     * Specifies how many messages will be shown in a folder by default. This number is set
     * on each new folder and can be incremented with "Load more messages..." by the
     * VISIBLE_LIMIT_INCREMENT
     */
    public static final int VISIBLE_LIMIT_DEFAULT = 25;

    /**
     * Number of additional messages to load when a user selects "Load more messages..."
     */
    public static final int VISIBLE_LIMIT_INCREMENT = 25;

    /**
     * The maximum size of an attachment we're willing to download (either View or Save)
     * Attachments that are base64 encoded (most) will be about 1.375x their actual size
     * so we should probably factor that in. A 5MB attachment will generally be around
     * 6.8MB downloaded but only 5MB saved.
     */
    public static final int MAX_ATTACHMENT_DOWNLOAD_SIZE = (5 * 1024 * 1024);

    /**
     * The maximum size of an attachment we're willing to upload (measured as stored on disk).
     * Attachments that are base64 encoded (most) will be about 1.375x their actual size
     * so we should probably factor that in. A 5MB attachment will generally be around
     * 6.8MB uploaded.
     */
    public static final int MAX_ATTACHMENT_UPLOAD_SIZE = (5 * 1024 * 1024);

    private static HashMap<Long, Long> sMailboxSyncTimes = new HashMap<Long, Long>();
    private static final long UPDATE_INTERVAL = 5 * DateUtils.MINUTE_IN_MILLIS;

    /**
     * This is used to force stacked UI to return to the "welcome" screen any time we change
     * the accounts list (e.g. deleting accounts in the Account Manager preferences.)
     */
    private static boolean sAccountsChangedNotification = false;

    public static final String EXCHANGE_ACCOUNT_MANAGER_TYPE = "com.android.exchange";

    // The color chip resources and the RGB color values in the array below must be kept in sync
    private static final int[] ACCOUNT_COLOR_CHIP_RES_IDS = new int[] {
        R.drawable.appointment_indicator_leftside_1,
        R.drawable.appointment_indicator_leftside_2,
        R.drawable.appointment_indicator_leftside_3,
        R.drawable.appointment_indicator_leftside_4,
        R.drawable.appointment_indicator_leftside_5,
        R.drawable.appointment_indicator_leftside_6,
        R.drawable.appointment_indicator_leftside_7,
        R.drawable.appointment_indicator_leftside_8,
        R.drawable.appointment_indicator_leftside_9,
    };

    private static final int[] ACCOUNT_COLOR_CHIP_RGBS = new int[] {
        0x71aea7,
        0x621919,
        0x18462f,
        0xbf8e52,
        0x001f79,
        0xa8afc2,
        0x6b64c4,
        0x738359,
        0x9d50a4,
    };

    private static File sTempDirectory;

    /* package for testing */ static int getColorIndexFromAccountId(long accountId) {
        // Account id is 1-based, so - 1.
        // Use abs so that it won't possibly return negative.
        return Math.abs((int) (accountId - 1) % ACCOUNT_COLOR_CHIP_RES_IDS.length);
    }

    public static int getAccountColorResourceId(long accountId) {
        return ACCOUNT_COLOR_CHIP_RES_IDS[getColorIndexFromAccountId(accountId)];
    }

    public static int getAccountColor(long accountId) {
        return ACCOUNT_COLOR_CHIP_RGBS[getColorIndexFromAccountId(accountId)];
    }

    public static void setTempDirectory(Context context) {
        sTempDirectory = context.getCacheDir();
    }

    public static File getTempDirectory() {
        if (sTempDirectory == null) {
            throw new RuntimeException(
                    "TempDirectory not set.  " +
                    "If in a unit test, call Email.setTempDirectory(context) in setUp().");
        }
        return sTempDirectory;
    }

    /**
     * Called throughout the application when the number of accounts has changed. This method
     * enables or disables the Compose activity, the boot receiver and the service based on
     * whether any accounts are configured.   Returns true if there are any accounts configured.
     */
    public static boolean setServicesEnabled(Context context) {
        Cursor c = null;
        try {
            c = context.getContentResolver().query(
                    EmailContent.Account.CONTENT_URI,
                    EmailContent.Account.ID_PROJECTION,
                    null, null, null);
            boolean enable = c.getCount() > 0;
            setServicesEnabled(context, enable);
            return enable;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    public static void setServicesEnabled(Context context, boolean enabled) {
        PackageManager pm = context.getPackageManager();
        if (!enabled && pm.getComponentEnabledSetting(new ComponentName(context, MailService.class)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            /*
             * If no accounts now exist but the service is still enabled we're about to disable it
             * so we'll reschedule to kill off any existing alarms.
             */
            MailService.actionReschedule(context);
        }
        pm.setComponentEnabledSetting(
                new ComponentName(context, MessageCompose.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(
                new ComponentName(context, AccountShortcutPicker.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(
                new ComponentName(context, MailService.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        if (enabled && pm.getComponentEnabledSetting(new ComponentName(context, MailService.class)) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            /*
             * And now if accounts do exist then we've just enabled the service and we want to
             * schedule alarms for the new accounts.
             */
            MailService.actionReschedule(context);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Preferences prefs = Preferences.getPreferences(this);
        DEBUG = prefs.getEnableDebugLogging();
        DEBUG_SENSITIVE = prefs.getEnableSensitiveLogging();
        setTempDirectory(this);

        // Reset all accounts to default visible window
        Controller.getInstance(this).resetVisibleLimits();

        // Enable logging in the EAS service, so it starts up as early as possible.
        Debug.updateLoggingFlags(this);
    }

    /**
     * Internal, utility method for logging.
     * The calls to log() must be guarded with "if (Email.LOGD)" for performance reasons.
     */
    public static void log(String message) {
        Log.d(LOG_TAG, message);
    }

    /**
     * Update the time when the mailbox is refreshed
     * @param mailboxId mailbox which need to be updated
     */
    public static void updateMailboxRefreshTime(long mailboxId) {
        synchronized (sMailboxSyncTimes) {
            sMailboxSyncTimes.put(mailboxId, System.currentTimeMillis());
        }
    }

    /**
     * Check if the mailbox is need to be refreshed
     * @param mailboxId mailbox checked the need of refreshing
     * @return the need of refreshing
     */
    public static boolean mailboxRequiresRefresh(long mailboxId) {
        synchronized (sMailboxSyncTimes) {
            return
                !sMailboxSyncTimes.containsKey(mailboxId)
                || (System.currentTimeMillis() - sMailboxSyncTimes.get(mailboxId)
                        > UPDATE_INTERVAL);
        }
    }

    /**
     * Called by the accounts reconciler to notify that accounts have changed, or by  "Welcome"
     * to clear the flag.
     * @param setFlag true to set the notification flag, false to clear it
     */
    public static synchronized void setNotifyUiAccountsChanged(boolean setFlag) {
        sAccountsChangedNotification = setFlag;
    }

    /**
     * Called from activity onResume() functions to check for an accounts-changed condition, at
     * which point they should finish() and jump to the Welcome activity.
     */
    public static synchronized boolean getNotifyUiAccountsChanged() {
        return sAccountsChangedNotification;
    }
}
