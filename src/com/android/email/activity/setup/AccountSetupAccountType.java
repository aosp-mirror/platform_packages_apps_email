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

import java.net.URI;
import java.net.URISyntaxException;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

import com.android.email.Account;
import com.android.email.R;

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

    public static void actionSelectAccountType(Context context, Account account, boolean makeDefault) {
        Intent i = new Intent(context, AccountSetupAccountType.class);
        i.putExtra(EXTRA_ACCOUNT, account);
        i.putExtra(EXTRA_MAKE_DEFAULT, makeDefault);
        context.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_account_type);
        ((Button)findViewById(R.id.pop)).setOnClickListener(this);
        ((Button)findViewById(R.id.imap)).setOnClickListener(this);

        mAccount = (Account)getIntent().getSerializableExtra(EXTRA_ACCOUNT);
        mMakeDefault = (boolean)getIntent().getBooleanExtra(EXTRA_MAKE_DEFAULT, false);
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

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.pop:
                onPop();
                break;
            case R.id.imap:
                onImap();
                break;
        }
    }
}
