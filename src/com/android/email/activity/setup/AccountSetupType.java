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
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.RelativeLayout.LayoutParams;

import com.android.email.R;
import com.android.email.activity.ActivityHelper;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.Utility;
import com.android.mail.utils.LogUtils;

/**
 * Prompts the user to select an account type. The account type, along with the
 * passed in email address, password and makeDefault are then passed on to the
 * AccountSetupIncoming activity.
 */
public class AccountSetupType extends AccountSetupActivity implements OnClickListener {

    private boolean mButtonPressed;

    public static void actionSelectAccountType(Activity fromActivity, SetupData setupData) {
        final Intent i = new ForwardingIntent(fromActivity, AccountSetupType.class);
        i.putExtra(SetupData.EXTRA_SETUP_DATA, setupData);
        fromActivity.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);

        final String accountType = mSetupData.getFlowAccountType();
        // If we're in account setup flow mode, see if there's just one protocol that matches
        if (mSetupData.getFlowMode() == SetupData.FLOW_MODE_ACCOUNT_MANAGER) {
            int matches = 0;
            String protocol = null;
            for (EmailServiceInfo info: EmailServiceUtils.getServiceInfoList(this)) {
                if (info.accountType.equals(accountType)) {
                    protocol = info.protocol;
                    matches++;
                }
            }
            // If so, select it...
            if (matches == 1) {
                onSelect(protocol);
                return;
            }
        }

        // Otherwise proceed into this screen
        setContentView(R.layout.account_setup_account_type);
        final ViewGroup parent = UiUtilities.getView(this, R.id.accountTypes);
        View lastView = parent.getChildAt(0);
        int i = 1;
        for (EmailServiceInfo info: EmailServiceUtils.getServiceInfoList(this)) {
            if (EmailServiceUtils.isServiceAvailable(this, info.protocol)) {
                // If we're looking for a specific account type, reject others
                // Don't show types with "hide" set
                if (info.hide || (accountType != null && !accountType.equals(info.accountType))) {
                    continue;
                }
                LayoutInflater.from(this).inflate(R.layout.account_type, parent);
                final Button button = (Button)parent.getChildAt(i);
                if (parent instanceof RelativeLayout) {
                    final LayoutParams params = (LayoutParams)button.getLayoutParams();
                    params.addRule(RelativeLayout.BELOW, lastView.getId());
                 }
                button.setId(i);
                button.setTag(info.protocol);
                button.setText(info.name);
                button.setOnClickListener(this);
                lastView = button;
                i++;
                // TODO: Remember vendor overlay for exchange name
            }
       }
        final Button previousButton = (Button) findViewById(R.id.previous); // xlarge only
        if (previousButton != null) previousButton.setOnClickListener(this);
    }

    /**
     * The user has selected an exchange account type. Set the mail delete policy here, because
     * there is no UI (for exchange), and switch the default sync interval to "push".
     */
    private void onSelect(String protocol) {
        final Account account = mSetupData.getAccount();
        final HostAuth recvAuth = account.getOrCreateHostAuthRecv(this);
        recvAuth.setConnection(protocol, recvAuth.mAddress, recvAuth.mPort, recvAuth.mFlags);
        final EmailServiceInfo info = EmailServiceUtils.getServiceInfo(this, protocol);

        new DuplicateCheckTask(account.mEmailAddress, info.accountType)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void onProceedNext() {
        final Account account = mSetupData.getAccount();
        final HostAuth recvAuth = account.getOrCreateHostAuthRecv(this);
        final EmailServiceInfo info = EmailServiceUtils.getServiceInfo(this, recvAuth.mProtocol);
        if (info.usesAutodiscover) {
            mSetupData.setCheckSettingsMode(SetupData.CHECK_AUTODISCOVER);
        } else {
            mSetupData.setCheckSettingsMode(
                    SetupData.CHECK_INCOMING | (info.usesSmtp ? SetupData.CHECK_OUTGOING : 0));
        }
        recvAuth.mLogin = recvAuth.mLogin + "@" + recvAuth.mAddress;
        AccountSetupBasics.setDefaultsForProtocol(this, account);
        AccountSetupIncoming.actionIncomingSettings(this, mSetupData);
        // Back from the incoming screen returns to AccountSetupBasics
        finish();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.previous:
                finish();
                break;
            default:
                if (!mButtonPressed) {
                    mButtonPressed = true;
                    onSelect((String)v.getTag());
                }
                break;
        }
    }

    private class DuplicateCheckTask extends AsyncTask<Void, Void, String> {
        private final String mAddress;
        private final String mAuthority;

        public DuplicateCheckTask(String address, String authority) {
            mAddress = address;
            mAuthority = authority;
        }

        @Override
        protected String doInBackground(Void... params) {
            return Utility.findExistingAccount(AccountSetupType.this, mAuthority, mAddress);
        }

        @Override
        protected void onPostExecute(String duplicateAccountName) {
            mButtonPressed = false;
            if (duplicateAccountName != null) {
                // Show duplicate account warning
                final DuplicateAccountDialogFragment dialogFragment =
                        DuplicateAccountDialogFragment.newInstance(duplicateAccountName);
                dialogFragment.show(AccountSetupType.this.getFragmentManager(),
                        DuplicateAccountDialogFragment.TAG);
            } else {
                // Otherwise, proceed with the save/check
                onProceedNext();
            }
        }

        @Override
        protected void onCancelled(String s) {
            mButtonPressed = false;
            LogUtils.d(LogUtils.TAG, "Duplicate account check cancelled (AccountSetupType)");
        }
    }
}
