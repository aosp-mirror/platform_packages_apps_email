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
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

public class AccountSetupOutgoing extends Activity
        implements OnClickListener, AccountSetupOutgoingFragment.Callback {

    private AccountSetupOutgoingFragment mFragment;
    private Button mNextButton;

    public static void actionOutgoingSettings(Activity fromActivity, int mode, Account acct) {
        SetupData.init(mode, acct);
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupOutgoing.class));
    }

    public static void actionEditOutgoingSettings(Activity fromActivity, int mode, Account acct) {
        actionOutgoingSettings(fromActivity, mode, acct);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_outgoing);

        mFragment = (AccountSetupOutgoingFragment) findFragmentById(R.id.setup_outgoing_fragment);
        mNextButton = (Button)findViewById(R.id.next);
        mNextButton.setOnClickListener(this);

        // Configure fragment
        mFragment.setCallback(this);
    }

    /**
     * After verifying a new server configuration, we return here and continue.  If editing an
     * existing account, we simply finish().  If creating a new account, we move to the next phase.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        EmailContent.Account account = SetupData.getAccount();
        if (resultCode == RESULT_OK) {
            if (SetupData.getFlowMode() == SetupData.FLOW_MODE_EDIT) {
                mFragment.saveSettingsAfterEdit();
            } else {
                AccountSetupOptions.actionOptions(this);
            }
            finish();
        }
    }

    /**
     * When the user clicks "next", notify the fragment that it's time to prepare the new
     * settings to be tested.  This will call back via onProceedNext() to actually launch the test.
     */
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.next:
                mFragment.onNext();
                break;
        }
    }

    /**
     * Implements AccountSetupIncomingFragment.Listener
     */
    public void onProceedNext() {
        AccountSetupCheckSettings.actionCheckSettings(this, SetupData.CHECK_OUTGOING);
    }

    /**
     * Implements AccountSetupIncomingFragment.Listener
     */
    public void onEnableProceedButtons(boolean enabled) {
        mNextButton.setEnabled(enabled);
        // Dim the next button's icon to 50% if the button is disabled.
        // TODO this can probably be done with a stateful drawable. (check android:state_enabled)
        Utility.setCompoundDrawablesAlpha(mNextButton, enabled ? 255 : 128);
    }
}
