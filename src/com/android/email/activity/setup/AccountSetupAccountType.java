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
import com.android.email.VendorPolicyLoader;
import com.android.email.mail.Store;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

import java.net.URI;
import java.net.URISyntaxException;

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
        int flowMode = SetupData.getFlowMode();

        // If we're in account setup flow mode, for EAS, skip this screen and "click" EAS
        if (flowMode == SetupData.FLOW_MODE_ACCOUNT_MANAGER_EAS) {
            onExchange();
            return;
        }

        // Otherwise proceed into this screen
        setContentView(R.layout.account_setup_account_type);
        ((Button)findViewById(R.id.pop)).setOnClickListener(this);
        ((Button)findViewById(R.id.imap)).setOnClickListener(this);
        final Button exchangeButton = ((Button)findViewById(R.id.exchange));
        exchangeButton.setOnClickListener(this);

        // TODO If we decide to exclude the Exchange option in POP_IMAP mode, use the following line
        // instead of the line that follows it
        //if (isExchangeAvailable() && flowMode != SetupData.FLOW_MODE_POP_IMAP) {
        if (isExchangeAvailable()) {
            exchangeButton.setVisibility(View.VISIBLE);
            if (VendorPolicyLoader.getInstance(this).useAlternateExchangeStrings()) {
                exchangeButton.setText(
                        R.string.account_setup_account_type_exchange_action_alternate);
            }
        }
        // TODO: Dynamic creation of buttons, instead of just hiding things we don't need
    }

    private void onPop() {
        Account account = SetupData.getAccount();
        try {
            URI uri = new URI(account.getStoreUri(this));
            uri = new URI("pop3", uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null);
            account.setStoreUri(this, uri.toString());
        } catch (URISyntaxException use) {
            /*
             * This should not happen.
             */
            throw new Error(use);
        }
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
        try {
            URI uri = new URI(account.getStoreUri(this));
            uri = new URI("imap", uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null);
            account.setStoreUri(this, uri.toString());
        } catch (URISyntaxException use) {
            /*
             * This should not happen.
             */
            throw new Error(use);
        }
        // Delete policy must be set explicitly, because IMAP does not provide a UI selection
        // for it. This logic needs to be followed in the auto setup flow as well.
        account.setDeletePolicy(Account.DELETE_POLICY_ON_DELETE);
        SetupData.setCheckSettingsMode(SetupData.CHECK_INCOMING | SetupData.CHECK_OUTGOING);
        AccountSetupIncoming.actionIncomingSettings(this, SetupData.getFlowMode(), account);
        finish();
    }

    /**
     * The user has selected an exchange account type.  Try to put together a URI using the entered
     * email address.  Also set the mail delete policy here, because there is no UI (for exchange),
     * and switch the default sync interval to "push".
     */
    private void onExchange() {
        Account account = SetupData.getAccount();
        try {
            URI uri = new URI(account.getStoreUri(this));
            uri = new URI("eas+ssl+", uri.getUserInfo(), uri.getHost(), uri.getPort(),
                    null, null, null);
            account.setStoreUri(this, uri.toString());
            account.setSenderUri(this, uri.toString());
        } catch (URISyntaxException use) {
            /*
             * This should not happen.
             */
            throw new Error(use);
        }
        // TODO: Confirm correct delete policy for exchange
        account.setDeletePolicy(Account.DELETE_POLICY_ON_DELETE);
        account.setSyncInterval(Account.CHECK_INTERVAL_PUSH);
        account.setSyncLookback(1);
        SetupData.setCheckSettingsMode(SetupData.CHECK_AUTODISCOVER);
        AccountSetupExchange.actionIncomingSettings(this, SetupData.getFlowMode(), account);
        finish();
    }

    /**
     * Determine if we can show the "exchange" option
     *
     * TODO: This should be dynamic and data-driven for all account types, not just hardcoded
     * like this.
     */
    private boolean isExchangeAvailable() {
        //EXCHANGE-REMOVE-SECTION-START
        try {
            URI uri = new URI(SetupData.getAccount().getStoreUri(this));
            uri = new URI("eas", uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null);
            Store.StoreInfo storeInfo = Store.StoreInfo.getStoreInfo(uri.toString(), this);
            return (storeInfo != null && checkAccountInstanceLimit(storeInfo));
        } catch (URISyntaxException e) {
        }
        //EXCHANGE-REMOVE-SECTION-END
        return false;
    }

    /**
     * If the optional store specifies a limit on the number of accounts, make sure that we
     * don't violate that limit.
     * @return true if OK to create another account, false if not OK (limit reached)
     */
    /* package */ boolean checkAccountInstanceLimit(Store.StoreInfo storeInfo) {
        // return immediately if account defines no limit
        if (storeInfo.mAccountInstanceLimit < 0) {
            return true;
        }

        // count existing accounts
        int currentAccountsCount = 0;
        Cursor c = null;
        try {
            c = this.getContentResolver().query(
                    Account.CONTENT_URI,
                    Account.CONTENT_PROJECTION,
                    null, null, null);
            while (c.moveToNext()) {
                Account account = EmailContent.getContent(c, Account.class);
                String storeUri = account.getStoreUri(this);
                if (storeUri != null && storeUri.startsWith(storeInfo.mScheme)) {
                    currentAccountsCount++;
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }

        // return true if we can accept another account
        return (currentAccountsCount < storeInfo.mAccountInstanceLimit);
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
        }
    }
}
