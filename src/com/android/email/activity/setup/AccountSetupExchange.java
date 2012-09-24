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
import com.android.email.activity.ActivityHelper;
import com.android.email.activity.UiUtilities;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;

import android.app.Activity;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

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
 */
public class AccountSetupExchange extends AccountSetupActivity
        implements AccountSetupExchangeFragment.Callback, OnClickListener {

    // Keys for savedInstanceState
    private final static String STATE_STARTED_AUTODISCOVERY =
            "AccountSetupExchange.StartedAutoDiscovery";

    boolean mStartedAutoDiscovery;
    /* package */ AccountSetupExchangeFragment mFragment;
    private Button mNextButton;
    /* package */ boolean mNextButtonEnabled;

    public static void actionIncomingSettings(Activity fromActivity, int mode, Account account) {
        SetupData.setFlowMode(mode);
        SetupData.setAccount(account);
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupExchange.class));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.account_setup_exchange);

        mFragment = (AccountSetupExchangeFragment)
                getFragmentManager().findFragmentById(R.id.setup_fragment);
        mFragment.setCallback(this);

        mNextButton = (Button) UiUtilities.getView(this, R.id.next);
        mNextButton.setOnClickListener(this);
        UiUtilities.getView(this, R.id.previous).setOnClickListener(this);

        // One-shot to launch autodiscovery at the entry to this activity (but not if it restarts)
        mStartedAutoDiscovery = false;
        if (savedInstanceState != null) {
            mStartedAutoDiscovery = savedInstanceState.getBoolean(STATE_STARTED_AUTODISCOVERY);
        }
        if (!mStartedAutoDiscovery) {
            startAutoDiscover();
        }
    }

    /**
     * Implements View.OnClickListener
     */
    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.next:
                mFragment.onNext();
                break;
            case R.id.previous:
                onBackPressed();
                break;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_STARTED_AUTODISCOVERY, mStartedAutoDiscovery);
    }

    /**
     * If the conditions are right, launch the autodiscover fragment.  If it succeeds (even
     * partially) it will prefill the setup fields and we can proceed as if the user entered them.
     *
     * Conditions for skipping:
     *  Editing existing account
     *  AutoDiscover blocked (used for unit testing only)
     *  Username or password not entered yet
     */
    private void startAutoDiscover() {
        // Note that we've started autodiscovery - even if we decide not to do it,
        // this prevents repeating.
        mStartedAutoDiscovery = true;

        if (!SetupData.isAllowAutodiscover()) {
            return;
        }

        Account account = SetupData.getAccount();
        // If we've got a username and password and we're NOT editing, try autodiscover
        String username = account.mHostAuthRecv.mLogin;
        String password = account.mHostAuthRecv.mPassword;
        if (username != null && password != null) {
            onProceedNext(SetupData.CHECK_AUTODISCOVER, mFragment);
        }
    }

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     *
     * @param result configuration data returned by AD server, or null if no data available
     */
    public void onAutoDiscoverComplete(int result, HostAuth hostAuth) {
        // If authentication failed, exit immediately (to re-enter credentials)
        if (result == AccountCheckSettingsFragment.AUTODISCOVER_AUTHENTICATION) {
            finish();
            return;
        }

        // If data was returned, populate the account & populate the UI fields and validate it
        if (result == AccountCheckSettingsFragment.AUTODISCOVER_OK) {
            boolean valid = mFragment.setHostAuthFromAutodiscover(hostAuth);
            if (valid) {
                // "click" next to launch server verification
                mFragment.onNext();
            }
        }
        // Otherwise, proceed into this activity for manual setup
    }

    /**
     * Implements AccountServerBaseFragment.Callback
     */
    public void onProceedNext(int checkMode, AccountServerBaseFragment target) {
        AccountCheckSettingsFragment checkerFragment =
            AccountCheckSettingsFragment.newInstance(checkMode, target);
        FragmentTransaction transaction = getFragmentManager().beginTransaction();
        transaction.add(checkerFragment, AccountCheckSettingsFragment.TAG);
        transaction.addToBackStack("back");
        transaction.commit();
    }

    /**
     * Implements AccountServerBaseFragment.Callback
     */
    public void onEnableProceedButtons(boolean enable) {
        mNextButtonEnabled = enable;
        mNextButton.setEnabled(enable);
    }

    /**
     * Implements AccountServerBaseFragment.Callback
     *
     * If the checked settings are OK, proceed to options screen.  If the user rejects security,
     * exit this screen.  For all other errors, remain here for editing.
     */
    public void onCheckSettingsComplete(int result, int setupMode) {
        switch (result) {
            case AccountCheckSettingsFragment.CHECK_SETTINGS_OK:
                AccountSetupOptions.actionOptions(this);
                finish();
                break;
            case AccountCheckSettingsFragment.CHECK_SETTINGS_SECURITY_USER_DENY:
                finish();
                break;
            default:
            case AccountCheckSettingsFragment.CHECK_SETTINGS_SERVER_ERROR:
                // Do nothing - remain in this screen
                break;
        }
    }
}
