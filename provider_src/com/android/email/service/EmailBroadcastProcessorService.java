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
import android.content.PeriodicSync;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.text.format.DateUtils;

import com.android.email.EmailIntentService;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.SecurityPolicy;
import com.android.email.provider.AccountReconciler;
import com.android.emailcommon.Logging;
import com.android.emailcommon.VendorPolicyLoader;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Maps;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    // This is a helper used to process DeviceAdminReceiver messages
    private static final String ACTION_DEVICE_POLICY_ADMIN = "com.android.email.devicepolicy";
    private static final String EXTRA_DEVICE_POLICY_ADMIN = "message_code";

    // Action used for EmailUpgradeBroadcastReceiver.
    private static final String ACTION_UPGRADE_BROADCAST = "upgrade_broadcast_receiver";

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

    public static void processUpgradeBroadcastIntent(final Context context) {
        final Intent i = new Intent(context, EmailBroadcastProcessorService.class);
        i.setAction(ACTION_UPGRADE_BROADCAST);
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
            } else if (AccountManager.LOGIN_ACCOUNTS_CHANGED_ACTION.equals(broadcastAction)) {
                onSystemAccountChanged();
            } else if (Intent.ACTION_LOCALE_CHANGED.equals(broadcastAction) ||
                    UIProvider.ACTION_UPDATE_NOTIFICATION.equals((broadcastAction))) {
                broadcastIntent.setClass(this, EmailIntentService.class);
                startService(broadcastIntent);
            }
        } else if (ACTION_DEVICE_POLICY_ADMIN.equals(action)) {
            int message = intent.getIntExtra(EXTRA_DEVICE_POLICY_ADMIN, -1);
            SecurityPolicy.onDeviceAdminReceiverMessage(this, message);
        } else if (ACTION_UPGRADE_BROADCAST.equals(action)) {
            onAppUpgrade();
        }
    }

    private void disableComponent(final Class<?> klass) {
        getPackageManager().setComponentEnabledSetting(new ComponentName(this, klass),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
    }

    private boolean isComponentDisabled(final Class<?> klass) {
        return getPackageManager().getComponentEnabledSetting(new ComponentName(this, klass))
                == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    private void updateAccountManagerAccountsOfType(final String amAccountType,
            final Map<String, String> protocolMap) {
        final android.accounts.Account[] amAccounts =
                AccountManager.get(this).getAccountsByType(amAccountType);

        for (android.accounts.Account amAccount: amAccounts) {
            EmailServiceUtils.updateAccountManagerType(this, amAccount, protocolMap);
        }
    }

    /**
     * Delete all periodic syncs for an account.
     * @param amAccount The account for which to disable syncs.
     * @param authority The authority for which to disable syncs.
     */
    private static void removePeriodicSyncs(final android.accounts.Account amAccount,
            final String authority) {
        final List<PeriodicSync> syncs =
                ContentResolver.getPeriodicSyncs(amAccount, authority);
        for (final PeriodicSync sync : syncs) {
            ContentResolver.removePeriodicSync(amAccount, authority, sync.extras);
        }
    }

    /**
     * Remove all existing periodic syncs for an account type, and add the necessary syncs.
     * @param amAccountType The account type to handle.
     * @param syncIntervals The map of all account addresses to sync intervals in the DB.
     */
    private void fixPeriodicSyncs(final String amAccountType,
            final Map<String, Integer> syncIntervals) {
        final android.accounts.Account[] amAccounts =
                AccountManager.get(this).getAccountsByType(amAccountType);
        for (android.accounts.Account amAccount : amAccounts) {
            // First delete existing periodic syncs.
            removePeriodicSyncs(amAccount, EmailContent.AUTHORITY);
            removePeriodicSyncs(amAccount, CalendarContract.AUTHORITY);
            removePeriodicSyncs(amAccount, ContactsContract.AUTHORITY);

            // Add back a sync for this account if necessary (i.e. the account has a positive
            // sync interval in the DB). This assumes that the email app requires unique email
            // addresses for each account, which is currently the case.
            final Integer syncInterval = syncIntervals.get(amAccount.name);
            if (syncInterval != null && syncInterval > 0) {
                // Sync interval is stored in minutes in DB, but we want the value in seconds.
                ContentResolver.addPeriodicSync(amAccount, EmailContent.AUTHORITY, Bundle.EMPTY,
                        syncInterval * DateUtils.MINUTE_IN_MILLIS / DateUtils.SECOND_IN_MILLIS);
            }
        }
    }

    /** Projection used for getting sync intervals for all accounts. */
    private static final String[] ACCOUNT_SYNC_INTERVAL_PROJECTION =
            { AccountColumns.EMAIL_ADDRESS, AccountColumns.SYNC_INTERVAL };
    private static final int ACCOUNT_SYNC_INTERVAL_ADDRESS_COLUMN = 0;
    private static final int ACCOUNT_SYNC_INTERVAL_INTERVAL_COLUMN = 1;

    /**
     * Get the sync interval for all accounts, as stored in the DB.
     * @return The map of all sync intervals by account email address.
     */
    private Map<String, Integer> getSyncIntervals() {
        final Cursor c = getContentResolver().query(Account.CONTENT_URI,
                ACCOUNT_SYNC_INTERVAL_PROJECTION, null, null, null);
        if (c != null) {
            final Map<String, Integer> periodicSyncs =
                    Maps.newHashMapWithExpectedSize(c.getCount());
            try {
                while (c.moveToNext()) {
                    periodicSyncs.put(c.getString(ACCOUNT_SYNC_INTERVAL_ADDRESS_COLUMN),
                            c.getInt(ACCOUNT_SYNC_INTERVAL_INTERVAL_COLUMN));
                }
            } finally {
                c.close();
            }
            return periodicSyncs;
        }
        return Collections.emptyMap();
    }

    @VisibleForTesting
    protected static void removeNoopUpgrades(final Map<String, String> protocolMap) {
        final Set<String> keySet = new HashSet<String>(protocolMap.keySet());
        for (final String key : keySet) {
            if (TextUtils.equals(key, protocolMap.get(key))) {
                protocolMap.remove(key);
            }
        }
    }

    private void onAppUpgrade() {
        if (isComponentDisabled(EmailUpgradeBroadcastReceiver.class)) {
            return;
        }
        // When upgrading to a version that changes the protocol strings, we need to essentially
        // rename the account manager type for all existing accounts, so we add new ones and delete
        // the old.
        // We specify the translations in this map. We map from old protocol name to new protocol
        // name, and from protocol name + "_type" to new account manager type name. (Email1 did
        // not use distinct account manager types for POP and IMAP, but Email2 does, hence this
        // weird mapping.)
        final Map<String, String> protocolMap = Maps.newHashMapWithExpectedSize(4);
        protocolMap.put("imap", getString(R.string.protocol_legacy_imap));
        protocolMap.put("pop3", getString(R.string.protocol_pop3));
        removeNoopUpgrades(protocolMap);
        if (!protocolMap.isEmpty()) {
            protocolMap.put("imap_type", getString(R.string.account_manager_type_legacy_imap));
            protocolMap.put("pop3_type", getString(R.string.account_manager_type_pop3));
            updateAccountManagerAccountsOfType("com.android.email", protocolMap);
        }

        protocolMap.clear();
        protocolMap.put("eas", getString(R.string.protocol_eas));
        removeNoopUpgrades(protocolMap);
        if (!protocolMap.isEmpty()) {
            protocolMap.put("eas_type", getString(R.string.account_manager_type_exchange));
            updateAccountManagerAccountsOfType("com.android.exchange", protocolMap);
        }

        // Disable the old authenticators.
        disableComponent(LegacyEmailAuthenticatorService.class);
        disableComponent(LegacyEasAuthenticatorService.class);

        // Fix periodic syncs.
        final Map<String, Integer> syncIntervals = getSyncIntervals();
        for (final EmailServiceUtils.EmailServiceInfo service
                : EmailServiceUtils.getServiceInfoList(this)) {
            fixPeriodicSyncs(service.accountType, syncIntervals);
        }

        // Disable the upgrade broadcast receiver now that we're fully upgraded.
        disableComponent(EmailUpgradeBroadcastReceiver.class);
    }

    /**
     * Handles {@link Intent#ACTION_BOOT_COMPLETED}.  Called on a worker thread.
     */
    private void onBootCompleted() {
        performOneTimeInitialization();
        reconcileAndStartServices();
    }

    private void reconcileAndStartServices() {
        /**
         *  We can get here before the ACTION_UPGRADE_BROADCAST is received, so make sure the
         *  accounts are converted otherwise terrible, horrible things will happen.
         */
        onAppUpgrade();
        // Reconcile accounts
        AccountReconciler.reconcileAccounts(this);
        // Starts remote services, if any
        EmailServiceUtils.startRemoteServices(this);
    }

    private void performOneTimeInitialization() {
        final Preferences pref = Preferences.getPreferences(this);
        int progress = pref.getOneTimeInitializationProgress();
        final int initialProgress = progress;

        if (progress < 1) {
            LogUtils.i(Logging.LOG_TAG, "Onetime initialization: 1");
            progress = 1;
            EmailServiceUtils.enableExchangeComponent(this);
        }

        if (progress < 2) {
            LogUtils.i(Logging.LOG_TAG, "Onetime initialization: 2");
            progress = 2;
            setImapDeletePolicy(this);
        }

        // Add your initialization steps here.
        // Use "progress" to skip the initializations that's already done before.
        // Using this preference also makes it safe when a user skips an upgrade.  (i.e. upgrading
        // version N to version N+2)

        if (progress != initialProgress) {
            pref.setOneTimeInitializationProgress(progress);
            LogUtils.i(Logging.LOG_TAG, "Onetime initialization: completed.");
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
                String legacyImapProtocol = context.getString(R.string.protocol_legacy_imap);
                if (legacyImapProtocol.equals(recvAuth.mProtocol)) {
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

    private void onSystemAccountChanged() {
        LogUtils.i(Logging.LOG_TAG, "System accounts updated.");
        reconcileAndStartServices();
    }
}
