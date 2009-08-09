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
import com.android.email.activity.MessageCompose;
import com.android.email.mail.internet.BinaryTempFileBody;
import com.android.email.provider.EmailContent;
import com.android.email.service.BootReceiver;
import com.android.email.service.MailService;
import com.android.exchange.Eas;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.util.Log;

import java.io.File;

public class Email extends Application {
    public static final String LOG_TAG = "Email";

    public static File tempDirectory;

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
     * The MIME type(s) of attachments we're willing to send. At the moment it is not possible
     * to open a chooser with a list of filter types, so the chooser is only opened with the first
     * item in the list. The entire list will be used to filter down attachments that are added
     * with Intent.ACTION_SEND.
     *
     * TODO: It should be legal to send anything requested by another app.  This would provide
     * parity with Gmail's behavior.
     */
    public static final String[] ACCEPTABLE_ATTACHMENT_SEND_TYPES = new String[] {
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
     * The special name "INBOX" is used throughout the application to mean "Whatever folder
     * the server refers to as the user's Inbox. Placed here to ease use.
     */
    public static final String INBOX = "INBOX";

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

    /**
     * Called throughout the application when the number of accounts has changed. This method
     * enables or disables the Compose activity, the boot receiver and the service based on
     * whether any accounts are configured.
     */
    public static void setServicesEnabled(Context context) {
        Cursor c = null;
        try {
            c = context.getContentResolver().query(
                    EmailContent.Account.CONTENT_URI,
                    EmailContent.Account.ID_PROJECTION,
                    null, null, null);
            boolean enable = c.getCount() > 0;
            setServicesEnabled(context, c.getCount() > 0);
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
                new ComponentName(context, BootReceiver.class),
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
        DEBUG = prefs.geteEnableDebugLogging();
        DEBUG_SENSITIVE = prefs.getEnableSensitiveLogging();

        // Reset all accounts to default visible window
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    EmailContent.Account.CONTENT_URI,
                    EmailContent.Account.CONTENT_PROJECTION,
                    null, null, null);
            while (c.moveToNext()) {
                EmailContent.Account account = EmailContent.getContent(c, EmailContent.Account.class);
                MessagingController.getInstance(this).resetVisibleLimits(account);
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }

        /*
         * We have to give MimeMessage a temp directory because File.createTempFile(String, String)
         * doesn't work in Android and MimeMessage does not have access to a Context.
         */
        BinaryTempFileBody.setTempDirectory(getCacheDir());

        // Enable logging in the EAS service, so it starts up as early as possible.
        int debugLogging = prefs.geteEnableDebugLogging() ? Eas.DEBUG_BIT : 0;
        int exchangeLogging = prefs.getEnableExchangeLogging() ? Eas.DEBUG_EXCHANGE_BIT : 0;
        int fileLogging = prefs.getEnableExchangeFileLogging() ? Eas.DEBUG_FILE_BIT : 0;
        int debugBits = debugLogging + exchangeLogging + fileLogging;
        Controller.getInstance(this).serviceLogging(debugBits);
    }

    /**
     * Internal, utility method for logging.
     * The calls to log() must be guarded with "if (Email.LOGD)" for performance reasons.
     */
    public static void log(String message) {
        Log.d(LOG_TAG, message);
    }
}
