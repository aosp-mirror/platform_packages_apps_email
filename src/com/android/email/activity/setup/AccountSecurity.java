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

package com.android.email.activity.setup;

import com.android.email.R;
import com.android.email.SecurityPolicy;
import com.android.email.Utility;
import com.android.email.activity.ActivityHelper;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.HostAuth;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;

/**
 * Psuedo-activity (no UI) to bootstrap the user up to a higher desired security level.  This
 * bootstrap requires the following steps.
 *
 * 1.  Confirm the account of interest has any security policies defined - exit early if not
 * 2.  If not actively administrating the device, ask Device Policy Manager to start that
 * 3.  When we are actively administrating, check current policies and see if they're sufficient
 * 4.  If not, set policies
 * 5.  If necessary, request for user to update device password
 * 6.  If necessary, request for user to activate device encryption
 */
public class AccountSecurity extends Activity {

    private static final String EXTRA_ACCOUNT_ID = "com.android.email.activity.setup.ACCOUNT_ID";

    private static final int REQUEST_ENABLE = 1;
    private static final int REQUEST_PASSWORD = 2;
    private static final int REQUEST_ENCRYPTION = 3;

    private boolean mTriedAddAdministrator = false;
    private boolean mTriedSetPassword = false;
    private boolean mTriedSetEncryption = false;
    private Account mAccount;

    /**
     * Used for generating intent for this activity (which is intended to be launched
     * from a notification.)
     *
     * @param context Calling context for building the intent
     * @param accountId The account of interest
     * @return an Intent which can be used to view that account
     */
    public static Intent actionUpdateSecurityIntent(Context context, long accountId) {
        Intent intent = new Intent(context, AccountSecurity.class);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        return intent;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);

        Intent i = getIntent();
        final long accountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        SecurityPolicy security = SecurityPolicy.getInstance(this);
        security.clearNotification(accountId);
        if (accountId == -1) {
            finish();
            return;
        }

        // Let onCreate exit, while background thread retrieves account.
        // Then start the security check/bootstrap process.
        new AsyncTask<Void, Void, Account>() {
            @Override
            protected Account doInBackground(Void... params) {
                return Account.restoreAccountWithId(AccountSecurity.this, accountId);
            }

            @Override
            protected void onPostExecute(Account result) {
                mAccount = result;
                if (mAccount != null && mAccount.mSecurityFlags != 0) {
                    // This account wants to control security
                    tryAdvanceSecurity(mAccount);
                    return;
                }
                finish();
            }
        }.execute();
    }

    /**
     * After any of the activities return, try to advance to the "next step"
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        tryAdvanceSecurity(mAccount);
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Walk the user through the required steps to become an active administrator and with
     * the requisite security settings for the given account.
     *
     * These steps will be repeated each time we return from a given attempt (e.g. asking the
     * user to choose a device pin/password).  In a typical activation, we may repeat these
     * steps a few times.  It may go as far as step 5 (password) or step 6 (encryption), but it
     * will terminate when step 2 (isActive()) succeeds.
     *
     * If at any point we do not advance beyond a given user step, (e.g. the user cancels
     * instead of setting a password) we simply repost the security notification, and exit.
     * We never want to loop here.
     */
    private void tryAdvanceSecurity(Account account) {
        SecurityPolicy security = SecurityPolicy.getInstance(this);

        // Step 1.  Check if we are an active device administrator, and stop here to activate
        if (!security.isActiveAdmin()) {
            if (mTriedAddAdministrator) {
                repostNotification(account, security);
                finish();
            } else {
                mTriedAddAdministrator = true;
                // retrieve name of server for the format string
                HostAuth hostAuth = HostAuth.restoreHostAuthWithId(this, account.mHostAuthKeyRecv);
                if (hostAuth == null) {
                    repostNotification(account, security);
                    finish();
                } else {
                    // try to become active - must happen here in activity, to get result
                    Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            security.getAdminComponent());
                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            this.getString(R.string.account_security_policy_explanation_fmt,
                                    hostAuth.mAddress));
                    startActivityForResult(intent, REQUEST_ENABLE);
                }
            }
            return;
        }

        // Step 2.  Check if the current aggregate security policy is being satisfied by the
        // DevicePolicyManager (the current system security level).
        if (security.isActive(null)) {
            Account.clearSecurityHoldOnAllAccounts(this);
            finish();
            return;
        }

        // Step 3.  Try to assert the current aggregate security requirements with the system.
        security.setActivePolicies();

        // Step 4.  Recheck the security policy, and determine what changes are needed (if any)
        // to satisfy the requirements.
        int inactiveReasons = security.getInactiveReasons(null);

        // Step 5.  If password is needed, try to have the user set it
        if ((inactiveReasons & SecurityPolicy.INACTIVE_NEED_PASSWORD) != 0) {
            if (mTriedSetPassword) {
                repostNotification(account, security);
                finish();
            } else {
                mTriedSetPassword = true;
                // launch the activity to have the user set a new password.
                Intent intent = new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD);
                startActivityForResult(intent, REQUEST_PASSWORD);
            }
            return;
        }

        // Step 6.  If encryption is needed, try to have the user set it
        if ((inactiveReasons & SecurityPolicy.INACTIVE_NEED_ENCRYPTION) != 0) {
            if (mTriedSetEncryption) {
                repostNotification(account, security);
                finish();
            } else {
                mTriedSetEncryption = true;
                // launch the activity to start up encryption.
                Intent intent = new Intent(DevicePolicyManager.ACTION_START_ENCRYPTION);
                startActivityForResult(intent, REQUEST_ENCRYPTION);
            }
            return;
        }

        // Step 7.  No problems were found, so clear holds and exit
        Account.clearSecurityHoldOnAllAccounts(this);
        finish();
    }

    /**
     * Mark an account as not-ready-for-sync and post a notification to bring the user back here
     * eventually.
     */
    private void repostNotification(final Account account, final SecurityPolicy security) {
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                security.policiesRequired(account.mId);
            }
        });
    }
}
