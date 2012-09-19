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

package com.android.email.activity.setup;

import android.app.Activity;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

import com.android.email.R;
import com.android.email.activity.ActivityHelper;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;

/**
 * Provides setup flow for IMAP/POP accounts.
 *
 * Uses AccountSetupIncomingFragment for primary UI.  Uses AccountCheckSettingsFragment to validate
 * the settings as entered.  If the account is OK, proceeds to AccountSetupOutgoing.
 */
public class AccountSetupIncoming extends AccountSetupActivity
        implements AccountSetupIncomingFragment.Callback, OnClickListener {

    /* package */ AccountServerBaseFragment mFragment;
    private Button mNextButton;
    /* package */ boolean mNextButtonEnabled;
    private boolean mStartedAutoDiscovery;
    private EmailServiceInfo mServiceInfo;

    // Keys for savedInstanceState
    private final static String STATE_STARTED_AUTODISCOVERY =
            "AccountSetupExchange.StartedAutoDiscovery";

    // Extras for AccountSetupIncoming intent
    private final static String FLOW_MODE_EXTRA = "flow-mode-extra";
    private final static String ACCOUNT_EXTRA = "account-extra";

    public static void actionIncomingSettings(Activity fromActivity, int mode, Account account) {
        SetupData.setFlowMode(mode);
        SetupData.setAccount(account);

        final Intent intent = new Intent(fromActivity, AccountSetupIncoming.class);
        // Add the additional information to the intent, in case the Email process is killed.
        intent.putExtra(FLOW_MODE_EXTRA, mode);
        intent.putExtra(ACCOUNT_EXTRA, account);

        fromActivity.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);

        final Account dataAccount = SetupData.getAccount();
        if (dataAccount == null) {
            // The account is not set in the SetupData.  This probably means that the Email
            // process was killed, and we are in the process of restoring the activity
            final Bundle extras = getIntent().getExtras();
            SetupData.setFlowMode(extras.getInt(FLOW_MODE_EXTRA));
            SetupData.setAccount((Account)extras.getParcelable(ACCOUNT_EXTRA));
        }

        HostAuth hostAuth = SetupData.getAccount().mHostAuthRecv;
        mServiceInfo = EmailServiceUtils.getServiceInfo(this, hostAuth.mProtocol);

        setContentView(R.layout.account_setup_incoming);
        mFragment = (AccountServerBaseFragment)
                getFragmentManager().findFragmentById(R.id.setup_fragment);

        // Configure fragment
        mFragment.setCallback(this);

        mNextButton = (Button) UiUtilities.getView(this, R.id.next);
        mNextButton.setOnClickListener(this);
        UiUtilities.getView(this, R.id.previous).setOnClickListener(this);

        // One-shot to launch autodiscovery at the entry to this activity (but not if it restarts)
        if (mServiceInfo.usesAutodiscover) {
            mStartedAutoDiscovery = false;
            if (savedInstanceState != null) {
                mStartedAutoDiscovery = savedInstanceState.getBoolean(STATE_STARTED_AUTODISCOVERY);
            }
            if (!mStartedAutoDiscovery) {
                startAutoDiscover();
            }
        }

        // If we've got a default prefix for this protocol, use it
        String prefix = mServiceInfo.inferPrefix;
        if (prefix != null && !hostAuth.mAddress.startsWith(prefix + ".")) {
            hostAuth.mAddress = prefix + "." + hostAuth.mAddress;
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
     *
     * Launches the account checker.  Positive results are reported to onCheckSettingsOk().
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
     * If the checked settings are OK, proceed to outgoing settings screen
     */
    public void onCheckSettingsComplete(int result, int setupMode) {
        if (result == AccountCheckSettingsFragment.CHECK_SETTINGS_OK) {
            if (mServiceInfo.usesSmtp) {
                AccountSetupOutgoing.actionOutgoingSettings(this, SetupData.getFlowMode(),
                        SetupData.getAccount());
            } else {
                AccountSetupOptions.actionOptions(this);
                finish();
            }
        }
    }
}
