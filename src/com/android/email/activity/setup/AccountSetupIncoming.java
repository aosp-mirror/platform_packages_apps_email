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
import com.android.email.provider.EmailContent;

import android.app.Activity;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.os.Bundle;

/**
 * Provides setup flow for IMAP/POP accounts.
 *
 * Uses AccountSetupIncomingFragment for primary UI.  Uses AccountCheckSettingsFragment to validate
 * the settings as entered.  If the account is OK, proceeds to AccountSetupOutgoing.
 */
public class AccountSetupIncoming extends AccountSetupActivity
        implements AccountSetupIncomingFragment.Callback {

    private AccountSetupIncomingFragment mFragment;
    /* package */ boolean mNextButtonEnabled;

    public static void actionIncomingSettings(Activity fromActivity, int mode,
            EmailContent.Account account) {
        SetupData.init(mode, account);
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupIncoming.class));
    }

    public static void actionEditIncomingSettings(Activity fromActivity, int mode,
            EmailContent.Account account) {
        actionIncomingSettings(fromActivity, mode, account);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_incoming);

        mFragment = (AccountSetupIncomingFragment)
                getFragmentManager().findFragmentById(R.id.setup_fragment);

        // Configure fragment
        mFragment.setCallback(this);
   }

    /**
     * Implements AccountServerBaseFragment.Callback
     *
     * Launches the account checker.  Positive results are reported to onCheckSettingsOk().
     */
    public void onProceedNext(int checkMode, AccountServerBaseFragment target) {
        AccountCheckSettingsFragment checkerFragment =
            AccountCheckSettingsFragment.newInstance(checkMode, target);
        FragmentTransaction transaction = getFragmentManager().openTransaction();
        transaction.replace(R.id.setup_fragment, checkerFragment);
        transaction.addToBackStack("back");
        transaction.commit();
    }

    /**
     * Implements AccountServerBaseFragment.Callback
     */
    public void onEnableProceedButtons(boolean enable) {
        boolean wasEnabled = mNextButtonEnabled;
        mNextButtonEnabled = enable;

        if (enable != wasEnabled) {
            invalidateOptionsMenu();
        }
    }

    /**
     * Implements AccountServerBaseFragment.Callback
     *
     * If the checked settings are OK, proceed to outgoing settings screen
     */
    public void onCheckSettingsOk(int setupMode) {
        if (SetupData.getFlowMode() != SetupData.FLOW_MODE_EDIT) {
            AccountSetupOutgoing.actionOutgoingSettings(this, SetupData.getFlowMode(),
                    SetupData.getAccount());
            finish();
        }
    }
}
