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

import com.android.email.R;
import com.android.email.Utility;
import com.android.email.VendorPolicyLoader;
import com.android.email.activity.Welcome;
import com.android.email.provider.EmailContent.Account;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

/**
 * Prompts the user for the email address and password. Also prompts for
 * "Use this account as default" if this is the 2nd+ account being set up.
 * Attempts to lookup default settings for the domain the user specified. If the
 * domain is known the settings are handed off to the AccountSetupCheckSettings
 * activity. If no settings are found the settings are handed off to the
 * AccountSetupAccountType activity.
 */
public class AccountSetupBasics extends AccountSetupActivity
        implements AccountSetupBasicsFragment.Callback {

    private AccountSetupBasicsFragment mFragment;
    private boolean mManualButtonDisplayed;
    private boolean mNextButtonEnabled;

    public static void actionNewAccount(Activity fromActivity) {
        SetupData.init(SetupData.FLOW_MODE_NORMAL);
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupBasics.class));
    }

    public static void actionNewAccountWithCredentials(Activity fromActivity,
            String username, String password, int accountFlowMode) {
        SetupData.init(accountFlowMode, username, password);
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
        SetupData.init(SetupData.FLOW_MODE_ACCOUNT_MAANGER_POP_IMAP);
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
    }

     /**
     * This is used in automatic setup mode to jump directly down to the names screen.
     *
     * NOTE:  With this organization, it is *not* possible to auto-create an exchange account,
     * because certain necessary actions happen during AccountSetupOptions (which we are
     * skipping here).
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            AccountSetupOptions.actionOptions(this);
            finish();
        }
    }

    /**
     * Add "Next" & "Manual" buttons when this activity is displayed
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        int menuId = mManualButtonDisplayed
        ? R.menu.account_setup_manual_next_option
                : R.menu.account_setup_next_option;
        getMenuInflater().inflate(menuId, menu);
        return super.onCreateOptionsMenu(menu);
    }

    /**
     * Enable/disable "Next" & "Manual" buttons
     */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.next).setEnabled(mNextButtonEnabled);
        if (mManualButtonDisplayed) {
            menu.findItem(R.id.manual_setup).setEnabled(mNextButtonEnabled);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    /**
     * Respond to clicks in the "Next" button
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.next:
                mFragment.onNext();
                return true;
            case R.id.manual_setup:
                // no AutoDiscover - user clicked "manual"
                mFragment.onManualSetup(false);
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Implements AccountSetupBasicsFragment.Callback
     */
    @Override
    public void onEnableProceedButtons(boolean enabled) {
        boolean wasEnabled = mNextButtonEnabled;
        mNextButtonEnabled = enabled;

        if (enabled != wasEnabled) {
            invalidateOptionsMenu();
        }
    }

    /**
     * Implements AccountSetupBasicsFragment.Callback
     */
    @Override
    public void onProceedAutomatic() {
        AccountSetupCheckSettings.actionCheckSettings(this,
                SetupData.CHECK_INCOMING | SetupData.CHECK_OUTGOING);
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
    public void onProceedManual() {
        AccountSetupAccountType.actionSelectAccountType(this);
    }
}
