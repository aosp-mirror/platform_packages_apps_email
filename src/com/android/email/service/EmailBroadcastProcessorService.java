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

package com.android.email.service;

import android.accounts.AccountManager;
import android.app.IntentService;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.SecurityPolicy;
import com.android.email.VendorPolicyLoader;
import com.android.email.activity.setup.AccountSettings;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.HostAuth;

/**
 * The service that really handles broadcast intents on a worker thread.
 *
 * We make it a service, because:
 * <ul>
 *   <li>So that it's less likely for the process to get killed.
 *   <li>Even if it does, the Intent that have started it will be re-delivered by the system,
 *   and we can start the process again.  (Using {@link #setIntentRedelivery}).
 * </ul>
 *
 * This also handles the DeviceAdminReceiver in SecurityPolicy, because it is also
 * a BroadcastReceiver and requires the same processing semantics.
 */
public class EmailBroadcastProcessorService extends IntentService {
    // Action used for BroadcastReceiver entry point
    private static final String ACTION_BROADCAST = "broadcast_receiver";

    // Dialing "*#*#36245#*#*" to open the debug screen.   "36245" = "email"
    private static final String ACTION_SECRET_CODE = "android.provider.Telephony.SECRET_CODE";
    private static final String SECRET_CODE_HOST_DEBUG_SCREEN = "36245";

    // This is a helper used to process DeviceAdminReceiver messages
    private static final String ACTION_DEVICE_POLICY_ADMIN = "com.android.email.devicepolicy";
    private static final String EXTRA_DEVICE_POLICY_ADMIN = "message_code";

    public EmailBroadcastProcessorService() {
        // Class name will be the thread name.
        super(EmailBroadcastProcessorService.class.getName());

        // Intent should be redelivered if the process gets killed before completing the job.
        setIntentRedelivery(true);
    }

    /**
     * Entry point for {@link EmailBroadcastReceiver}.
     */
    public static void processBroadcastIntent(Context context, Intent broadcastIntent) {
        Intent i = new Intent(context, EmailBroadcastProcessorService.class);
        i.setAction(ACTION_BROADCAST);
        i.putExtra(Intent.EXTRA_INTENT, broadcastIntent);
        context.startService(i);
    }

    /**
     * Entry point for {@link com.android.email.SecurityPolicy.PolicyAdmin}.  These will
     * simply callback to {@link
     * com.android.email.SecurityPolicy#onDeviceAdminReceiverMessage(Context, int)}.
     */
    public static void processDevicePolicyMessage(Context context, int message) {
        Intent i = new Intent(context, EmailBroadcastProcessorService.class);
        i.setAction(ACTION_DEVICE_POLICY_ADMIN);
        i.putExtra(EXTRA_DEVICE_POLICY_ADMIN, message);
        context.startService(i);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        // This method is called on a worker thread.

        // Dispatch from entry point
        final String action = intent.getAction();
        if (ACTION_BROADCAST.equals(action)) {
            final Intent broadcastIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            final String broadcastAction = broadcastIntent.getAction();

            if (Intent.ACTION_BOOT_COMPLETED.equals(broadcastAction)) {
                onBootCompleted();

            // TODO: Do a better job when we get ACTION_DEVICE_STORAGE_LOW.
            //       The code below came from very old code....
            } else if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(broadcastAction)) {
                // Stop IMAP/POP3 poll.
                MailService.actionCancel(this);
            } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(broadcastAction)) {
                enableComponentsIfNecessary();
            } else if (ACTION_SECRET_CODE.equals(broadcastAction)
                    && SECRET_CODE_HOST_DEBUG_SCREEN.equals(broadcastIntent.getData().getHost())) {
                AccountSettings.actionSettingsWithDebug(this);
            } else if (AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION.equals(broadcastAction)) {
                onSystemAccountChanged();
            }
        } else if (ACTION_DEVICE_POLICY_ADMIN.equals(action)) {
            int message = intent.getIntExtra(EXTRA_DEVICE_POLICY_ADMIN, -1);
            SecurityPolicy.onDeviceAdminReceiverMessage(this, message);
        }
    }

    private void enableComponentsIfNecessary() {
        if (Email.setServicesEnabledSync(this)) {
            // At least one account exists.
            // TODO probably we should check if it's a POP/IMAP account.
            MailService.actionReschedule(this);
        }
    }

    /**
     * Handles {@link Intent#ACTION_BOOT_COMPLETED}.  Called on a worker thread.
     */
    private void onBootCompleted() {
        performOneTimeInitialization();

        enableComponentsIfNecessary();

        // Starts the service for Exchange, if supported.
        EmailServiceUtils.startExchangeService(this);
    }

    private void performOneTimeInitialization() {
        final Preferences pref = Preferences.getPreferences(this);
        int progress = pref.getOneTimeInitializationProgress();
        final int initialProgress = progress;

        if (progress < 1) {
            Log.i(Logging.LOG_TAG, "Onetime initialization: 1");
            progress = 1;
            if (VendorPolicyLoader.getInstance(this).useAlternateExchangeStrings()) {
                setComponentEnabled(EasAuthenticatorServiceAlternate.class, true);
                setComponentEnabled(EasAuthenticatorService.class, false);
            }
        }

        if (progress < 2) {
            Log.i(Logging.LOG_TAG, "Onetime initialization: 2");
            progress = 2;
            setImapDeletePolicy(this);
        }

        // Add your initialization steps here.
        // Use "progress" to skip the initializations that's already done before.
        // Using this preference also makes it safe when a user skips an upgrade.  (i.e. upgrading
        // version N to version N+2)

        if (progress != initialProgress) {
            pref.setOneTimeInitializationProgress(progress);
            Log.i(Logging.LOG_TAG, "Onetime initialization: completed.");
        }
    }

    /**
     * Sets the delete policy to the correct value for all IMAP accounts. This will have no
     * effect on either EAS or POP3 accounts.
     */
    /*package*/ static void setImapDeletePolicy(Context context) {
        ContentResolver resolver = context.getContentResolver();
        Cursor c = resolver.query(Account.CONTENT_URI, Account.CONTENT_PROJECTION,
                null, null, null);
        try {
            while (c.moveToNext()) {
                long recvAuthKey = c.getLong(Account.CONTENT_HOST_AUTH_KEY_RECV_COLUMN);
                HostAuth recvAuth = HostAuth.restoreHostAuthWithId(context, recvAuthKey);
                if (HostAuth.SCHEME_IMAP.equals(recvAuth.mProtocol)) {
                    int flags = c.getInt(Account.CONTENT_FLAGS_COLUMN);
                    flags &= ~Account.FLAGS_DELETE_POLICY_MASK;
                    flags |= Account.DELETE_POLICY_ON_DELETE << Account.FLAGS_DELETE_POLICY_SHIFT;
                    ContentValues cv = new ContentValues();
                    cv.put(AccountColumns.FLAGS, flags);
                    long accountId = c.getLong(Account.CONTENT_ID_COLUMN);
                    Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, accountId);
                    resolver.update(uri, cv, null, null);
                }
            }
        } finally {
            c.close();
        }
    }

    private void setComponentEnabled(Class<?> clazz, boolean enabled) {
        final ComponentName c = new ComponentName(this, clazz.getName());
        getPackageManager().setComponentEnabledSetting(c,
                enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void onSystemAccountChanged() {
        Log.i(Logging.LOG_TAG, "System accounts updated.");
        MailService.reconcilePopImapAccountsSync(this);

        // If the exchange service wasn't already running, starting it will cause exchange account
        // reconciliation to be performed.  The service stops itself it there are no EAS accounts.
        EmailServiceUtils.startExchangeService(this);
    }
}
