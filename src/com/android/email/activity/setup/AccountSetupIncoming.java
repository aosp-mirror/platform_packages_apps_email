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
import android.content.Intent;
import android.os.Bundle;

public class AccountSetupIncoming extends AccountSetupActivity
        implements AccountSetupIncomingFragment.Callback {

    private AccountSetupIncomingFragment mFragment;
    private boolean mNextButtonEnabled;

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
     * After verifying a new server configuration, we return here and continue.  If editing an
     * existing account, we simply finish().  If creating a new account, we move to the next phase.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            if (SetupData.getFlowMode() == SetupData.FLOW_MODE_EDIT) {
                mFragment.saveSettingsAfterEdit();
            } else {
                mFragment.saveSettingsAfterSetup();
                AccountSetupOutgoing.actionOutgoingSettings(this, SetupData.getFlowMode(),
                        SetupData.getAccount());
            }
            finish();
        }
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
