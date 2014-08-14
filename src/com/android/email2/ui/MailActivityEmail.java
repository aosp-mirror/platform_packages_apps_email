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

package com.android.email2.ui;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import com.android.email.NotificationController;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.provider.EmailProvider;
import com.android.email.service.AttachmentService;
import com.android.email.service.EmailServiceUtils;
import com.android.emailcommon.Logging;
import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.IntentUtilities;
import com.android.emailcommon.utility.Utility;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogTag;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;

public class MailActivityEmail extends com.android.mail.ui.MailActivity {
    /**
     * If this is enabled there will be additional logging information sent to
     * LogUtils.d, including protocol dumps.
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

    public static final String LOG_TAG = LogTag.getLogTag();

    // Exchange debugging flags (passed to Exchange, when available, via EmailServiceProxy)
    public static boolean DEBUG_EXCHANGE;
    public static boolean DEBUG_VERBOSE;
    public static boolean DEBUG_FILE;

    private static final int MATCH_LEGACY_SHORTCUT_INTENT = 1;
    /**
     * A matcher for data URI's that specify conversation list info.
     */
    private static final UriMatcher sUrlMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    static {
        sUrlMatcher.addURI(
                EmailProvider.LEGACY_AUTHORITY, "view/mailbox", MATCH_LEGACY_SHORTCUT_INTENT);
    }


    /**
     * Asynchronous version of {@link #setServicesEnabledSync(Context)}.  Use when calling from
     * UI thread (or lifecycle entry points.)
     */
    public static void setServicesEnabledAsync(final Context context) {
        if (context.getResources().getBoolean(R.bool.enable_services)) {
            EmailAsyncTask.runAsyncParallel(new Runnable() {
                @Override
                public void run() {
                    setServicesEnabledSync(context);
                }
            });
        }
    }

    /**
     * Called throughout the application when the number of accounts has changed. This method
     * enables or disables the Compose activity, the boot receiver and the service based on
     * whether any accounts are configured.
     *
     * Blocking call - do not call from UI/lifecycle threads.
     *
     * @return true if there are any accounts configured.
     */
    public static boolean setServicesEnabledSync(Context context) {
        // Make sure we're initialized
        EmailContent.init(context);
        Cursor c = null;
        try {
            c = context.getContentResolver().query(
                    Account.CONTENT_URI,
                    Account.ID_PROJECTION,
                    null, null, null);
            boolean enable = c != null && c.getCount() > 0;
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
        pm.setComponentEnabledSetting(
                new ComponentName(context, AttachmentService.class),
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED :
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);

        // Start/stop the various services depending on whether there are any accounts
        // TODO: Make sure that the AttachmentService responds to this request as it
        // expects a particular set of data in the intents that it receives or it ignores.
        startOrStopService(enabled, context, new Intent(context, AttachmentService.class));
        NotificationController.getInstance(context).watchForMessages();
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
    public void onCreate(Bundle bundle) {
        final Intent intent = getIntent();
        final Uri data = intent != null ? intent.getData() : null;
        if (data != null) {
            final int match = sUrlMatcher.match(data);
            switch (match) {
                case MATCH_LEGACY_SHORTCUT_INTENT: {
                    final long mailboxId = IntentUtilities.getMailboxIdFromIntent(intent);
                    final Mailbox mailbox = Mailbox.restoreMailboxWithId(this, mailboxId);
                    if (mailbox == null) {
                        LogUtils.e(LOG_TAG, "unable to restore mailbox");
                        break;
                    }

                    final Intent viewIntent = getViewIntent(mailbox.mAccountKey, mailboxId);
                    if (viewIntent != null) {
                        setIntent(viewIntent);
                    }
                    break;
                }
            }
        }

        super.onCreate(bundle);
        final Preferences prefs = Preferences.getPreferences(this);
        DEBUG = prefs.getEnableDebugLogging();
        enableStrictMode(prefs.getEnableStrictMode());
        TempDirectory.setTempDirectory(this);

        // Enable logging in the EAS service, so it starts up as early as possible.
        updateLoggingFlags(this);

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
        EmailServiceUtils.setRemoteServicesLogging(context, debugBits);
     }

    /**
     * Internal, utility method for logging.
     * The calls to log() must be guarded with "if (Email.LOGD)" for performance reasons.
     */
    public static void log(String message) {
        LogUtils.d(Logging.LOG_TAG, message);
    }

    public static void enableStrictMode(boolean enabled) {
        Utility.enableStrictMode(enabled);
    }

    private Intent getViewIntent(long accountId, long mailboxId) {
        final ContentResolver contentResolver = getContentResolver();

        final Cursor accountCursor = contentResolver.query(
                EmailProvider.uiUri("uiaccount", accountId),
                UIProvider.ACCOUNTS_PROJECTION_NO_CAPABILITIES,
                null, null, null);

        if (accountCursor == null) {
            LogUtils.e(LOG_TAG, "Null account cursor for mAccountId %d", accountId);
            return null;
        }

        com.android.mail.providers.Account account = null;
        try {
            if (accountCursor.moveToFirst()) {
                account = com.android.mail.providers.Account.builder().buildFrom(accountCursor);
            }
        } finally {
            accountCursor.close();
        }


        final Cursor folderCursor = contentResolver.query(
                EmailProvider.uiUri("uifolder", mailboxId),
                UIProvider.FOLDERS_PROJECTION, null, null, null);

        if (folderCursor == null) {
            LogUtils.e(LOG_TAG, "Null folder cursor for account %d, mailbox %d",
                    accountId, mailboxId);
            return null;
        }

        Folder folder = null;
        try {
            if (folderCursor.moveToFirst()) {
                folder = new Folder(folderCursor);
            } else {
                LogUtils.e(LOG_TAG, "Empty folder cursor for account %d, mailbox %d",
                        accountId, mailboxId);
                return null;
            }
        } finally {
            folderCursor.close();
        }

        return Utils.createViewFolderIntent(this, folder.folderUri.fullUri, account);
    }
}
