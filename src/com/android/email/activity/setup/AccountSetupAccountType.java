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

import com.android.email.Account;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.mail.Store;

import android.app.Activity;
import android.content.Intent;
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
public class AccountSetupAccountType extends Activity implements OnClickListener {
    private static final String EXTRA_ACCOUNT = "account";

    private static final String EXTRA_MAKE_DEFAULT = "makeDefault";

    private Account mAccount;

    private boolean mMakeDefault;

    public static void actionSelectAccountType(Activity fromActivity, Account account, 
            boolean makeDefault) {
        Intent i = new Intent(fromActivity, AccountSetupAccountType.class);
        i.putExtra(EXTRA_ACCOUNT, account);
        i.putExtra(EXTRA_MAKE_DEFAULT, makeDefault);
        fromActivity.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_account_type);
        ((Button)findViewById(R.id.pop)).setOnClickListener(this);
        ((Button)findViewById(R.id.imap)).setOnClickListener(this);
        ((Button)findViewById(R.id.exchange)).setOnClickListener(this);
        
        mAccount = (Account)getIntent().getSerializableExtra(EXTRA_ACCOUNT);
        mMakeDefault = (boolean)getIntent().getBooleanExtra(EXTRA_MAKE_DEFAULT, false);

        if (isExchangeAvailable()) {
            findViewById(R.id.exchange).setVisibility(View.VISIBLE);
        }
        // TODO: Dynamic creation of buttons, instead of just hiding things we don't need
    }

    private void onPop() {
        try {
            URI uri = new URI(mAccount.getStoreUri());
            uri = new URI("pop3", uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null);
            mAccount.setStoreUri(uri.toString());
        } catch (URISyntaxException use) {
            /*
             * This should not happen.
             */
            throw new Error(use);
        }
        AccountSetupIncoming.actionIncomingSettings(this, mAccount, mMakeDefault);
        finish();
    }

    /**
     * The user has selected an IMAP account type.  Try to put together a URI using the entered
     * email address.  Also set the mail delete policy here, because there is no UI (for IMAP).
     */
    private void onImap() {
        try {
            URI uri = new URI(mAccount.getStoreUri());
            uri = new URI("imap", uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null);
            mAccount.setStoreUri(uri.toString());
        } catch (URISyntaxException use) {
            /*
             * This should not happen.
             */
            throw new Error(use);
        }
        // Delete policy must be set explicitly, because IMAP does not provide a UI selection
        // for it. This logic needs to be followed in the auto setup flow as well.
        mAccount.setDeletePolicy(Account.DELETE_POLICY_ON_DELETE);
        AccountSetupIncoming.actionIncomingSettings(this, mAccount, mMakeDefault);
        finish();
    }
    
    /**
     * The user has selected an exchange account type.  Try to put together a URI using the entered
     * email address.  Also set the mail delete policy here, because there is no UI (for exchange),
     * and switch the default sync interval to "push".
     */
    private void onExchange() {
        try {
            URI uri = new URI(mAccount.getStoreUri());
            uri = new URI("eas", uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null);
            mAccount.setStoreUri(uri.toString());
            mAccount.setSenderUri(uri.toString());
        } catch (URISyntaxException use) {
            /*
             * This should not happen.
             */
            throw new Error(use);
        }
        // TODO: Confirm correct delete policy for exchange
        mAccount.setDeletePolicy(Account.DELETE_POLICY_ON_DELETE);
        mAccount.setAutomaticCheckIntervalMinutes(Account.CHECK_INTERVAL_PUSH);
        mAccount.setSyncWindow(Account.SYNC_WINDOW_1_DAY);
        AccountSetupExchange.actionIncomingSettings(this, mAccount, mMakeDefault);
        finish();
    }
    
    /**
     * Determine if we can show the "exchange" option
     * 
     * TODO: This should be dynamic and data-driven for all account types, not just hardcoded
     * like this.
     */
    private boolean isExchangeAvailable() {
        try {
            URI uri = new URI(mAccount.getStoreUri());
            uri = new URI("eas", uri.getUserInfo(), uri.getHost(), uri.getPort(), null, null, null);
            Store.StoreInfo storeInfo = Store.StoreInfo.getStoreInfo(uri.toString(), this);
            return (storeInfo != null && checkAccountInstanceLimit(storeInfo));
        } catch (URISyntaxException e) {
            return false;
        }
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
        Account[] accounts = Preferences.getPreferences(this).getAccounts();
        for (Account account : accounts) {
            String storeUri = account.getStoreUri();
            if (storeUri != null && storeUri.startsWith(storeInfo.mScheme)) {
                currentAccountsCount++;
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
