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

import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.VendorPolicyLoader;
import com.android.email.activity.ActivityHelper;
import com.android.email.activity.Welcome;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.HostAuth;

import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

/**
 * Prompts the user for the email address and password. Also prompts for "Use this account as
 * default" if this is the 2nd+ account being set up.
 *
 * If the domain is well-known, the account is configured fully and checked immediately
 * using AccountCheckSettingsFragment.  If this succeeds we proceed directly to AccountSetupOptions.
 *
 * If the domain is not known, or the user selects Manual setup, we invoke the
 * AccountSetupAccountType activity where the user can begin to manually configure the account.
 *
 * === Support for automated testing ==
 * This activity can also be launched directly via ACTION_CREATE_ACCOUNT.  This is intended
 * only for use by continuous test systems, and is currently only available when "ro.monkey"
 * is set.  To use this mode, you must construct an intent which contains all necessary information
 * to create the account.  No connection checking is done, so the account may or may not actually
 * work.  Here is a sample command, for a gmail account "test_account" with a password of
 * "test_password".
 *
 *      $ adb shell am start -a com.android.email.CREATE_ACCOUNT \
 *          -e EMAIL test_account@gmail.com \
 *          -e USER "Test Account Name" \
 *          -e INCOMING imap+ssl+://test_account:test_password@imap.gmail.com \
 *          -e OUTGOING smtp+ssl+://test_account:test_password@smtp.gmail.com
 *
 * Note: For accounts that require the full email address in the login, encode the @ as %40.
 * Note: Exchange accounts that require device security policies cannot be created automatically.
 */
public class AccountSetupBasics extends AccountSetupActivity
        implements AccountSetupBasicsFragment.Callback, AccountCheckSettingsFragment.Callbacks,
        OnClickListener {

    /**
     * Direct access for forcing account creation
     * For use by continuous automated test system (e.g. in conjunction with monkey tests)
     */
    private final String ACTION_CREATE_ACCOUNT = "com.android.email.CREATE_ACCOUNT";
    private final String EXTRA_CREATE_ACCOUNT_EMAIL = "EMAIL";
    private final String EXTRA_CREATE_ACCOUNT_USER = "USER";
    private final String EXTRA_CREATE_ACCOUNT_INCOMING = "INCOMING";
    private final String EXTRA_CREATE_ACCOUNT_OUTGOING = "OUTGOING";
    private final Boolean DEBUG_ALLOW_NON_MONKEY_CREATION = true;  // STOPSHIP - must be FALSE

    private AccountSetupBasicsFragment mFragment;
    private boolean mManualButtonDisplayed;
    private boolean mNextButtonEnabled;
    private Button mManualButton;
    private Button mNextButton;

    // Used when this Activity is called as part of account authentification flow,
    // which requires to do extra work before and after the account creation.
    // See also AccountAuthenticatorActivity.
    private AccountAuthenticatorResponse mAccountAuthenticatorResponse = null;
    private Bundle mResultBundle = null;

    public static void actionNewAccount(Activity fromActivity) {
        SetupData.init(SetupData.FLOW_MODE_NORMAL);
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupBasics.class));
    }

    /**
     * This generates setup data that can be used to start a self-contained account creation flow
     * for exchange accounts.
     */
    public static Intent actionSetupExchangeIntent(Context context) {
        SetupData.init(SetupData.FLOW_MODE_ACCOUNT_MANAGER_EAS);
        return new Intent(context, AccountSetupBasics.class);
    }

    /**
     * This generates setup data that can be used to start a self-contained account creation flow
     * for pop/imap accounts.
     */
    public static Intent actionSetupPopImapIntent(Context context) {
        SetupData.init(SetupData.FLOW_MODE_ACCOUNT_MANAGER_POP_IMAP);
        return new Intent(context, AccountSetupBasics.class);
    }

    public static void actionAccountCreateFinishedAccountFlow(Activity fromActivity) {
        Intent i= new Intent(fromActivity, AccountSetupBasics.class);
        // If we're in the "account flow" (from AccountManager), we want to return to the caller
        // (in the settings app)
        SetupData.init(SetupData.FLOW_MODE_RETURN_TO_CALLER);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        fromActivity.startActivity(i);
    }

    public static void actionAccountCreateFinished(final Activity fromActivity,
            final long accountId) {
        Utility.runAsync(new Runnable() {
           public void run() {
               Intent i = new Intent(fromActivity, AccountSetupBasics.class);
               // If we're not in the "account flow" (from AccountManager), we want to show the
               // message list for the new inbox
               Account account = Account.restoreAccountWithId(fromActivity, accountId);
               SetupData.init(SetupData.FLOW_MODE_RETURN_TO_MESSAGE_LIST, account);
               i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
               fromActivity.startActivity(i);
            }});
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);

        // Check for forced account creation first, as it comes from an externally-generated
        // intent and won't have any SetupData prepared.
        String action = getIntent().getAction();
        if (ACTION_CREATE_ACCOUNT.equals(action)) {
            SetupData.init(SetupData.FLOW_MODE_FORCE_CREATE);
        }

        int flowMode = SetupData.getFlowMode();
        if (flowMode == SetupData.FLOW_MODE_RETURN_TO_CALLER) {
            // Return to the caller who initiated account creation
            finish();
            return;
        } else if (flowMode == SetupData.FLOW_MODE_RETURN_TO_MESSAGE_LIST) {
            Account account = SetupData.getAccount();
            if (account != null && account.mId >= 0) {
                // Show the message list for the new account
                Welcome.actionOpenAccountInbox(this, account.mId);
                finish();
                return;
            }
        }

        setContentView(R.layout.account_setup_basics);

        mFragment = (AccountSetupBasicsFragment)
                getFragmentManager().findFragmentById(R.id.setup_basics_fragment);
        mManualButtonDisplayed = true;
        boolean alternateStrings = false;
        if (flowMode == SetupData.FLOW_MODE_ACCOUNT_MANAGER_EAS) {
            // No need for manual button -> next is appropriate
            mManualButtonDisplayed = false;
            // Swap welcome text for EAS-specific text
            alternateStrings = VendorPolicyLoader.getInstance(this).useAlternateExchangeStrings();
            setTitle(alternateStrings
                    ? R.string.account_setup_basics_exchange_title_alternate
                            : R.string.account_setup_basics_exchange_title);
        }

        // Configure fragment
        mFragment.setCallback(this, alternateStrings);

        // Configure buttons
        mManualButton = (Button) findViewById(R.id.manual_setup);
        mNextButton = (Button) findViewById(R.id.next);
        mManualButton.setVisibility(mManualButtonDisplayed ? View.VISIBLE : View.INVISIBLE);
        mManualButton.setOnClickListener(this);
        mNextButton.setOnClickListener(this);
        // Force disabled until fragment notifies otherwise
        mNextButtonEnabled = true;
        this.onEnableProceedButtons(false);

        mAccountAuthenticatorResponse =
            getIntent().getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);

        if (mAccountAuthenticatorResponse != null) {
            mAccountAuthenticatorResponse.onRequestContinued();
        }

        // Handle force account creation immediately (now that fragment is set up)
        // This is never allowed in a normal user build and will exit immediately.
        if (SetupData.getFlowMode() == SetupData.FLOW_MODE_FORCE_CREATE) {
            if (!DEBUG_ALLOW_NON_MONKEY_CREATION && !ActivityManager.isUserAMonkey()) {
                Log.e(Email.LOG_TAG, "ERROR: Force account create only allowed for monkeys");
                finish();
                return;
            }
            Intent intent = getIntent();
            String email = intent.getStringExtra(EXTRA_CREATE_ACCOUNT_EMAIL);
            String user = intent.getStringExtra(EXTRA_CREATE_ACCOUNT_USER);
            String incoming = intent.getStringExtra(EXTRA_CREATE_ACCOUNT_INCOMING);
            String outgoing = intent.getStringExtra(EXTRA_CREATE_ACCOUNT_OUTGOING);
            if (TextUtils.isEmpty(email) || TextUtils.isEmpty(user) ||
                    TextUtils.isEmpty(incoming) || TextUtils.isEmpty(outgoing)) {
                Log.e(Email.LOG_TAG, "ERROR: Force account create requires extras EMAIL, USER, " +
                        "INCOMING, OUTGOING");
                finish();
                return;
            }
            mFragment.forceCreateAccount(email, user, incoming, outgoing);
            onCheckSettingsComplete(AccountCheckSettingsFragment.CHECK_SETTINGS_OK); // calls finish
            return;
        }
    }

    @Override
    public void finish() {
        if (mAccountAuthenticatorResponse != null) {
            // send the result bundle back if set, otherwise send an error.
            if (mResultBundle != null) {
                mAccountAuthenticatorResponse.onResult(mResultBundle);
            } else {
                mAccountAuthenticatorResponse.onError(AccountManager.ERROR_CODE_CANCELED,
                        "canceled");
            }
            mAccountAuthenticatorResponse = null;
        }
        super.finish();
    }

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     *
     * This is used in automatic setup mode to jump directly down to the options screen.
     */
    @Override
    public void onCheckSettingsComplete(int result) {
        if (result == AccountCheckSettingsFragment.CHECK_SETTINGS_OK) {
            AccountSetupOptions.actionOptions(this);
            finish();
        }
    }

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     * This is overridden only by AccountSetupExchange
     */
    @Override
    public void onAutoDiscoverComplete(int result, HostAuth hostAuth) {
        throw new IllegalStateException();
    }

    /**
     * Implements OnClickListener
     */
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.next:
                mFragment.onNext();
                break;
            case R.id.manual_setup:
                // no AutoDiscover - user clicked "manual"
                mFragment.onManualSetup(false);
                break;
        }
    }

    /**
     * Implements AccountSetupBasicsFragment.Callback
     */
    @Override
    public void onEnableProceedButtons(boolean enabled) {
        boolean wasEnabled = mNextButtonEnabled;
        mNextButtonEnabled = enabled;

        if (enabled != wasEnabled) {
            mManualButton.setEnabled(enabled);
            mNextButton.setEnabled(enabled);
        }
    }

    /**
     * Implements AccountSetupBasicsFragment.Callback
     *
     * This is called when auto-setup (from hardcoded server info) is attempted.
     * Replace the name/password fragment with the account checker, which will begin to
     * check incoming/outgoing.
     */
    @Override
    public void onProceedAutomatic() {
        AccountCheckSettingsFragment checkerFragment =
            AccountCheckSettingsFragment.newInstance(
                    SetupData.CHECK_INCOMING | SetupData.CHECK_OUTGOING, null);
        FragmentTransaction transaction = getFragmentManager().openTransaction();
        transaction.replace(R.id.setup_basics_fragment, checkerFragment);
        transaction.addToBackStack("back");
        transaction.commit();
    }

    /**
     * Implements AccountSetupBasicsFragment.Callback
     */
    @Override
    public void onProceedDebugSettings() {
        AccountSettingsXL.actionSettingsWithDebug(this);
    }

    /**
     * Implements AccountSetupBasicsFragment.Callback
     */
    @Override
    public void onProceedManual(boolean allowAutoDiscover) {
        SetupData.setAllowAutodiscover(allowAutoDiscover);
        AccountSetupAccountType.actionSelectAccountType(this);
    }
}
