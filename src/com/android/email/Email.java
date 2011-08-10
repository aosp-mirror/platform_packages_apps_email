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

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.util.Log;

import com.android.email.activity.MessageCompose;
import com.android.email.activity.ShortcutPicker;
import com.android.email.service.AttachmentDownloadService;
import com.android.email.service.MailService;
import com.android.email.widget.WidgetConfiguration;
import com.android.emailcommon.Logging;
import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;

public class Email extends Application {
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
    public static boolean DEBUG;

    // Exchange debugging flags (passed to Exchange, when available, via EmailServiceProxy)
    public static boolean DEBUG_EXCHANGE;
    public static boolean DEBUG_EXCHANGE_VERBOSE;
    public static boolean DEBUG_EXCHANGE_FILE;

    /**
     * If true, inhibit hardware graphics acceleration in UI (for a/b testing)
     */
    public static boolean sDebugInhibitGraphicsAcceleration = false;

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
     * This is used to force stacked UI to return to the "welcome" screen any time we change
     * the accounts list (e.g. deleting accounts in the Account Manager preferences.)
     */
    private static boolean sAccountsChangedNotification = false;

    private static String sMessageDecodeErrorString;

    private static Thread sUiThread;

    /**
     * Asynchronous version of {@link #setServicesEnabledSync(Context)}.  Use when calling from
     * UI thread (or lifecycle entry points.)
     *
     * @param context
     */
    public static void setServicesEnabledAsync(final Context context) {
        EmailAsyncTask.runAsyncParallel(new Runnable() {
            @Override
            public void run() {
                setServicesEnabledSync(context);
            }
        });
    }

    /**
     * Called throughout the application when the number of accounts has changed. This method
     * enables or disables the Compose activity, the boot receiver and the service based on
     * whether any accounts are configured.
     *
     * Blocking call - do not call from UI/lifecycle threads.
     *
     * @param context
     * @return true if there are any accounts configured.
     */
    public static boolean setServicesEnabledSync(Context context) {
        Cursor c = null;
        try {
            c = context.getContentResolver().query(
                    Account.CONTENT_URI,
                    Account.ID_PROJECTION,
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

    private static void setServicesEnabled(Context context, boolean enabled) {
        PackageManager pm = context.getPackageManager();
        if (!enabled && pm.getComponentEnabledSetting(
                new ComponentName(context, MailService.class)) ==
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
                new ComponentName(context, ShortcutPicker.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(
                new ComponentName(context, MailService.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        pm.setComponentEnabledSetting(
                new ComponentName(context, AttachmentDownloadService.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
        if (enabled && pm.getComponentEnabledSetting(
                new ComponentName(context, MailService.class)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            /*
             * And now if accounts do exist then we've just enabled the service and we want to
             * schedule alarms for the new accounts.
             */
            MailService.actionReschedule(context);
        }

        // Note - the Email widget is always enabled as it will show a warning if no accounts are
        // configured. In previous releases, this was disabled if no accounts were set, so we
        // need to unconditionally enable it here.
        pm.setComponentEnabledSetting(
                new ComponentName(context, WidgetConfiguration.class),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

        // Start/stop the various services depending on whether there are any accounts
        startOrStopService(enabled, context, new Intent(context, AttachmentDownloadService.class));
        NotificationController.getInstance(context).watchForMessages(enabled);
    }

    /**
     * Starts or stops the service as necessary.
     * @param enabled If {@code true}, the service will be started. Otherwise, it will be stopped.
     * @param context The context to manage the service with.
     * @param intent The intent of the service to be managed.
     */
    private static void startOrStopService(boolean enabled, Context context, Intent intent) {
        if (enabled) {
            context.startService(intent);
        } else {
            context.stopService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sUiThread = Thread.currentThread();
        Preferences prefs = Preferences.getPreferences(this);
        DEBUG = prefs.getEnableDebugLogging();
        sDebugInhibitGraphicsAcceleration = prefs.getInhibitGraphicsAcceleration();
        enableStrictMode(prefs.getEnableStrictMode());
        TempDirectory.setTempDirectory(this);

        // Tie MailRefreshManager to the Controller.
        RefreshManager.getInstance(this);
        // Reset all accounts to default visible window
        Controller.getInstance(this).resetVisibleLimits();

        // Enable logging in the EAS service, so it starts up as early as possible.
        updateLoggingFlags(this);

        // Get a helper string used deep inside message decoders (which don't have context)
        sMessageDecodeErrorString = getString(R.string.message_decode_error);

        // Make sure all required services are running when the app is started (can prevent
        // issues after an adb sync/install)
        setServicesEnabledAsync(this);
    }

    /**
     * Load enabled debug flags from the preferences and update the EAS debug flag.
     */
    public static void updateLoggingFlags(Context context) {
        Preferences prefs = Preferences.getPreferences(context);
        int debugLogging = prefs.getEnableDebugLogging() ? EmailServiceProxy.DEBUG_BIT : 0;
        int verboseLogging =
            prefs.getEnableExchangeLogging() ? EmailServiceProxy.DEBUG_VERBOSE_BIT : 0;
        int fileLogging =
            prefs.getEnableExchangeFileLogging() ? EmailServiceProxy.DEBUG_FILE_BIT : 0;
        int enableStrictMode =
            prefs.getEnableStrictMode() ? EmailServiceProxy.DEBUG_ENABLE_STRICT_MODE : 0;
        int debugBits = debugLogging | verboseLogging | fileLogging | enableStrictMode;
        Controller.getInstance(context).serviceLogging(debugBits);
    }

    /**
     * Internal, utility method for logging.
     * The calls to log() must be guarded with "if (Email.LOGD)" for performance reasons.
     */
    public static void log(String message) {
        Log.d(Logging.LOG_TAG, message);
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

    public static void warnIfUiThread() {
        if (Thread.currentThread().equals(sUiThread)) {
            Log.w(Logging.LOG_TAG, "Method called on the UI thread", new Exception("STACK TRACE"));
        }
    }

    /**
     * Retrieve a simple string that can be used when message decoders encounter bad data.
     * This is provided here because the protocol decoders typically don't have mContext.
     */
    public static String getMessageDecodeErrorString() {
        return sMessageDecodeErrorString != null ? sMessageDecodeErrorString : "";
    }

    public static void enableStrictMode(boolean enabled) {
        Utility.enableStrictMode(enabled);
    }
}
