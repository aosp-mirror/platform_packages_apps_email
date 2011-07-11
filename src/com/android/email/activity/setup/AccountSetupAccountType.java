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
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

import com.android.email.R;
import com.android.email.VendorPolicyLoader;
import com.android.email.activity.ActivityHelper;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;

/**
 * Prompts the user to select an account type. The account type, along with the
 * passed in email address, password and makeDefault are then passed on to the
 * AccountSetupIncoming activity.
 */
public class AccountSetupAccountType extends AccountSetupActivity implements OnClickListener {

    public static void actionSelectAccountType(Activity fromActivity) {
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupAccountType.class));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        int flowMode = SetupData.getFlowMode();

        // If we're in account setup flow mode, for EAS, skip this screen and "click" EAS
        if (flowMode == SetupData.FLOW_MODE_ACCOUNT_MANAGER_EAS) {
            onExchange();
            return;
        }

        // Otherwise proceed into this screen
        setContentView(R.layout.account_setup_account_type);
        UiUtilities.getView(this, R.id.pop).setOnClickListener(this);
        UiUtilities.getView(this, R.id.imap).setOnClickListener(this);
        final Button exchangeButton = (Button) UiUtilities.getView(this, R.id.exchange);
        exchangeButton.setVisibility(View.INVISIBLE);
        final Button previousButton = (Button) findViewById(R.id.previous); // xlarge only
        if (previousButton != null) previousButton.setOnClickListener(this);

        // TODO If we decide to exclude the Exchange option in POP_IMAP mode, use the following line
        // instead of the line that follows it
        //if (ExchangeUtils.isExchangeAvailable(this) && flowMode != SetupData.FLOW_MODE_POP_IMAP) {
        if (EmailServiceUtils.isExchangeAvailable(this)) {
            exchangeButton.setOnClickListener(this);
            exchangeButton.setVisibility(View.VISIBLE);
            if (VendorPolicyLoader.getInstance(this).useAlternateExchangeStrings()) {
                exchangeButton.setText(
                        R.string.account_setup_account_type_exchange_action_alternate);
            }
        }
        // TODO: Dynamic creation of buttons, instead of just hiding things we don't need
    }

    /**
     * For POP accounts, we rewrite the username to the full user@domain, and we set the
     * default server name to pop3.domain
     */
    private void onPop() {
        Account account = SetupData.getAccount();
        HostAuth hostAuth = account.mHostAuthRecv;
        hostAuth.mProtocol = HostAuth.SCHEME_POP3;
        hostAuth.mLogin = hostAuth.mLogin + "@" + hostAuth.mAddress;
        hostAuth.mAddress = AccountSettingsUtils.inferServerName(hostAuth.mAddress,
                HostAuth.SCHEME_POP3, null);
        AccountSetupBasics.setFlagsForProtocol(account, HostAuth.SCHEME_POP3);
        SetupData.setCheckSettingsMode(SetupData.CHECK_INCOMING | SetupData.CHECK_OUTGOING);
        AccountSetupIncoming.actionIncomingSettings(this, SetupData.getFlowMode(), account);
        finish();
    }

    /**
     * The user has selected an IMAP account type.  Try to put together a URI using the entered
     * email address.  Also set the mail delete policy here, because there is no UI (for IMAP).
     */
    private void onImap() {
        Account account = SetupData.getAccount();
        HostAuth hostAuth = account.mHostAuthRecv;
        hostAuth.mProtocol = HostAuth.SCHEME_IMAP;
        hostAuth.mLogin = hostAuth.mLogin + "@" + hostAuth.mAddress;
        hostAuth.mAddress = AccountSettingsUtils.inferServerName(hostAuth.mAddress,
                HostAuth.SCHEME_IMAP, null);
        AccountSetupBasics.setFlagsForProtocol(account, HostAuth.SCHEME_IMAP);
        SetupData.setCheckSettingsMode(SetupData.CHECK_INCOMING | SetupData.CHECK_OUTGOING);
        AccountSetupIncoming.actionIncomingSettings(this, SetupData.getFlowMode(), account);
        finish();
    }

    /**
     * The user has selected an exchange account type. Set the mail delete policy here, because
     * there is no UI (for exchange), and switch the default sync interval to "push".
     */
    private void onExchange() {
        Account account = SetupData.getAccount();
        HostAuth recvAuth = account.getOrCreateHostAuthRecv(this);
        recvAuth.setConnection(HostAuth.SCHEME_EAS, recvAuth.mAddress, recvAuth.mPort,
                recvAuth.mFlags | HostAuth.FLAG_SSL);
        HostAuth sendAuth = account.getOrCreateHostAuthSend(this);
        sendAuth.setConnection(HostAuth.SCHEME_EAS, sendAuth.mAddress, sendAuth.mPort,
                sendAuth.mFlags | HostAuth.FLAG_SSL);
        AccountSetupBasics.setFlagsForProtocol(account, HostAuth.SCHEME_EAS);
        SetupData.setCheckSettingsMode(SetupData.CHECK_AUTODISCOVER);
        AccountSetupExchange.actionIncomingSettings(this, SetupData.getFlowMode(), account);
        finish();
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.pop:
                onPop();
                break;
            case R.id.imap:
                onImap();
                break;
            case R.id.exchange:
                onExchange();
                break;
            case R.id.previous:
                finish();
                break;
        }
    }
}
