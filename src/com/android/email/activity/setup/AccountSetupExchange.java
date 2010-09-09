/*
 * Copyright (C) 2009 The Android Open Source Project
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
import com.android.email.SecurityPolicy.PolicySet;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.email.service.EmailServiceProxy;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;

/**
 * Provides generic setup for Exchange accounts.  The following fields are supported:
 *
 *  Email Address   (from previous setup screen)
 *  Server
 *  Domain
 *  Requires SSL?
 *  User (login)
 *  Password
 *
 * There are two primary paths through this activity:
 *   Edit existing:
 *     Load existing values from account into fields
 *     When user clicks 'next':
 *       Confirm not a duplicate account
 *       Try new values (check settings)
 *       If new values are OK:
 *         Write new values (save to provider)
 *         finish() (pop to previous)
 *
 *   Creating New:
 *     Try Auto-discover to get details from server
 *     If Auto-discover reports an authentication failure:
 *       finish() (pop to previous, to re-enter username & password)
 *     If Auto-discover succeeds:
 *       write server's account details into account
 *     Load values from account into fields
 *     Confirm not a duplicate account
 *     Try new values (check settings)
 *     If new values are OK:
 *       Write new values (save to provider)
 *       Proceed to options screen
 *       finish() (removes self from back stack)
 *
 * TODO: The manifest for this activity has it ignore config changes, because
 * we don't want to restart on every orientation - this would launch autodiscover again.
 * Do not attempt to define orientation-specific resources, they won't be loaded.
 * What we really need here is a more "sticky" way to prevent that problem.
 */
public class AccountSetupExchange extends AccountSetupActivity
        implements AccountSetupExchangeFragment.Callback {

    /* package */ AccountSetupExchangeFragment mFragment;
    /* package */ boolean mNextButtonEnabled;

    public static void actionIncomingSettings(Activity fromActivity, int mode, Account acct) {
        SetupData.init(mode, acct);
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupExchange.class));
    }

    public static void actionEditIncomingSettings(Activity fromActivity, int mode, Account acct) {
        actionIncomingSettings(fromActivity, mode, acct);
    }

    /**
     * For now, we'll simply replicate outgoing, for the purpose of satisfying the
     * account settings flow.
     */
    public static void actionEditOutgoingSettings(Activity fromActivity, int mode, Account acct) {
        actionIncomingSettings(fromActivity, mode, acct);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_exchange);

        mFragment = (AccountSetupExchangeFragment)
                getFragmentManager().findFragmentById(R.id.setup_fragment);
        mFragment.setCallback(this);

        startAutoDiscover();
    }

    /**
     * If the conditions are right, launch the autodiscover activity.  If it succeeds (even
     * partially) it will prefill the setup fields and we can proceed as if the user entered them.
     *
     * Conditions for skipping:
     *  Editing existing account
     *  AutoDiscover blocked (used for unit testing only)
     *  Username or password not entered yet
     */
    private void startAutoDiscover() {
        if (SetupData.getFlowMode() == SetupData.FLOW_MODE_EDIT
                || !SetupData.isAllowAutodiscover()) {
            return;
        }

        Account account = SetupData.getAccount();
        // If we've got a username and password and we're NOT editing, try autodiscover
        String username = account.mHostAuthRecv.mLogin;
        String password = account.mHostAuthRecv.mPassword;
        if (username != null && password != null) {
            AccountSetupCheckSettings.actionAutoDiscover(this, account.mEmailAddress, password);
        }
    }

    /**
     * There are three cases handled here, so we split out into separate sections.
     * 1.  Validate existing account (edit)
     * 2.  Validate new account
     * 3.  Autodiscover for new account
     *
     * For each case, there are two or more paths for success or failure.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == AccountSetupCheckSettings.REQUEST_CODE_VALIDATE) {
            if (SetupData.getFlowMode() == SetupData.FLOW_MODE_EDIT) {
                doActivityResultValidateExistingAccount(resultCode, data);
            } else {
                doActivityResultValidateNewAccount(resultCode, data);
            }
        } else if (requestCode == AccountSetupCheckSettings.REQUEST_CODE_AUTO_DISCOVER) {
            doActivityResultAutoDiscoverNewAccount(resultCode, data);
        }
    }

    /**
     * Process activity result when validating existing account.  If OK, save and finish;
     * otherwise simply remain in activity for further editing.
     */
    private void doActivityResultValidateExistingAccount(int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            mFragment.saveSettingsAfterEdit();
            finish();
        }
    }

    /**
     * Process activity result when validating new account
     */
    private void doActivityResultValidateNewAccount(int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            // Go directly to next screen
            PolicySet ps = null;
            if ((data != null) && data.hasExtra(EmailServiceProxy.VALIDATE_BUNDLE_POLICY_SET)) {
                ps = (PolicySet)data.getParcelableExtra(
                        EmailServiceProxy.VALIDATE_BUNDLE_POLICY_SET);
            }
            AccountSetupOptions.actionOptions(this);
            finish();
        } else if (resultCode == AccountSetupCheckSettings.RESULT_SECURITY_REQUIRED_USER_CANCEL) {
            finish();
        }
        // else (resultCode not OK) - just return into this activity for further editing
    }

    /**
     * Process activity result when provisioning new account via autodiscovery
     */
    private void doActivityResultAutoDiscoverNewAccount(int resultCode, Intent data) {
        // If authentication failed, exit immediately (to re-enter credentials)
        if (resultCode == AccountSetupCheckSettings.RESULT_AUTO_DISCOVER_AUTH_FAILED) {
            finish();
            return;
        }

        // If data was returned, populate the account & populate the UI fields and validate it
        if (data != null) {
            Parcelable p = data.getParcelableExtra("HostAuth");
            if (p != null) {
                HostAuth hostAuth = (HostAuth)p;
                boolean valid = mFragment.setHostAuthFromAutodiscover(hostAuth);
                if (valid) {
                    // "click" next to launch server verification
                    mFragment.onNext();
                }
            }
        }
        // Otherwise, proceed into this activity for manual setup
    }

    /**
     * Implements AccountServerBaseFragment.Callback
     */
    public void onEnableProceedButtons(boolean enabled) {
        boolean wasEnabled = mNextButtonEnabled;
        mNextButtonEnabled = enabled;

        if (enabled != wasEnabled) {
            invalidateOptionsMenu();
        }
    }

    /**
     * Implements AccountServerBaseFragment.Callback
     */
    public void onProceedNext(int checkMode) {
        AccountSetupCheckSettings.actionCheckSettings(this, checkMode);
    }
}
