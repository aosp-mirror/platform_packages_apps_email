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
import com.android.email.activity.ActivityHelper;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.HostAuth;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
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
        long accountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        SecurityPolicy security = SecurityPolicy.getInstance(this);
        security.clearNotification(accountId);
        if (accountId != -1) {
            // TODO: spin up a thread to do this in the background, because of DB ops
            Account account = Account.restoreAccountWithId(this, accountId);
            if (account != null) {
                if (account.mSecurityFlags != 0) {
                    // This account wants to control security
                    if (!security.isActiveAdmin()) {
                        // retrieve name of server for the format string
                        HostAuth hostAuth =
                                HostAuth.restoreHostAuthWithId(this, account.mHostAuthKeyRecv);
                        if (hostAuth != null) {
                            // try to become active - must happen here in activity, to get result
                            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                    security.getAdminComponent());
                            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                this.getString(R.string.account_security_policy_explanation_fmt,
                                        hostAuth.mAddress));
                            startActivityForResult(intent, REQUEST_ENABLE);
                            // keep this activity on stack to process result
                            return;
                        }
                    } else {
                        // already active - try to set actual policies, finish, and return
                        boolean startedActivity = setActivePolicies();
                        if (startedActivity) {
                            // keep this activity on stack to process result
                            return;
                        }
                    }
                }
            }
        }
        finish();
    }

    /**
     * Handle the eventual result of the user allowing us to become an active device admin
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        boolean startedActivity = false;
        switch (requestCode) {
            case REQUEST_PASSWORD:
            case REQUEST_ENCRYPTION:
                // Force the result code and just check the DPM to check for actual success
                resultCode = Activity.RESULT_OK;
              //$FALL-THROUGH$
            case REQUEST_ENABLE:
                if (resultCode == Activity.RESULT_OK) {
                    // now active - try to set actual policies
                    startedActivity = setActivePolicies();
                } else {
                    // failed - repost notification, and exit
                    final long accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, -1);
                    if (accountId != -1) {
                        new Thread() {
                            @Override
                            public void run() {
                                SecurityPolicy.getInstance(AccountSecurity.this)
                                        .policiesRequired(accountId);
                            }
                        }.start();
                    }
                }
        }
        if (!startedActivity) {
            finish();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Now that we are connected as an active device admin, try to set the device to the
     * correct security level, and ask for a password if necessary.
     * @return true if we started another activity (and should not finish(), as we're waiting for
     * their result.)
     */
    private boolean setActivePolicies() {
        SecurityPolicy sp = SecurityPolicy.getInstance(this);
        // check current security level - if sufficient, we're done!
        if (sp.isActive(null)) {
            Account.clearSecurityHoldOnAllAccounts(this);
            return false;
        }
        // set current security level
        sp.setActivePolicies();
        // check current security level - if sufficient, we're done!
        int inactiveReasons = sp.getInactiveReasons(null);
        if (inactiveReasons == 0) {
            Account.clearSecurityHoldOnAllAccounts(this);
            return false;
        }
        // If password or encryption required, launch relevant intent
        if ((inactiveReasons & SecurityPolicy.INACTIVE_NEED_PASSWORD) != 0) {
            // launch the activity to have the user set a new password.
            Intent intent = new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD);
            startActivityForResult(intent, REQUEST_PASSWORD);
            return true;
        } else if ((inactiveReasons & SecurityPolicy.INACTIVE_NEED_ENCRYPTION) != 0) {
            // launch the activity to start up encryption.
            Intent intent = new Intent(DevicePolicyManager.ACTION_START_ENCRYPTION);
            startActivityForResult(intent, REQUEST_ENCRYPTION);
            return true;
        }
        return false;
    }
}
