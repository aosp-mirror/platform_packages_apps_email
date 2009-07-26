/*
 * Copyright (C) 2009 The Android Open Source Project
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
import com.android.email.Utility;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Provides generic setup for Exchange accounts.  The following fields are supported:
 *
 *  Email Address   (from previous setup screen)
 *  Server
 *  Domain
 *  Requires SSL?
 *  User (login)
 *  Password
 */
public class AccountSetupExchange extends Activity implements OnClickListener {
    private static final String EXTRA_ACCOUNT = "account";
    private static final String EXTRA_MAKE_DEFAULT = "makeDefault";

    private EditText mUsernameView;
    private EditText mPasswordView;
    private EditText mServerView;
    private EditText mDomainView;
    private CheckBox mSslSecurityView;

    private Button mNextButton;
    private Account mAccount;
    private boolean mMakeDefault;

    public static void actionIncomingSettings(Activity fromActivity, Account account, 
            boolean makeDefault) {
        Intent i = new Intent(fromActivity, AccountSetupExchange.class);
        i.putExtra(EXTRA_ACCOUNT, account);
        i.putExtra(EXTRA_MAKE_DEFAULT, makeDefault);
        fromActivity.startActivity(i);
    }

    public static void actionEditIncomingSettings(Activity fromActivity, Account account) {
        Intent i = new Intent(fromActivity, AccountSetupExchange.class);
        i.setAction(Intent.ACTION_EDIT);
        i.putExtra(EXTRA_ACCOUNT, account);
        fromActivity.startActivity(i);
    }

    /**
     * For now, we'll simply replicate outgoing, for the purpose of satisfying the 
     * account settings flow.
     */
    public static void actionEditOutgoingSettings(Activity fromActivity, Account account) {
        Intent i = new Intent(fromActivity, AccountSetupExchange.class);
        i.setAction(Intent.ACTION_EDIT);
        i.putExtra(EXTRA_ACCOUNT, account);
        fromActivity.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_exchange);

        mUsernameView = (EditText) findViewById(R.id.account_username);
        mPasswordView = (EditText) findViewById(R.id.account_password);
        mServerView = (EditText) findViewById(R.id.account_server);
        mDomainView = (EditText) findViewById(R.id.account_domain);
        mSslSecurityView = (CheckBox) findViewById(R.id.account_ssl);

        mNextButton = (Button)findViewById(R.id.next);
        mNextButton.setOnClickListener(this);

        /*
         * Calls validateFields() which enables or disables the Next button
         * based on the fields' validity.
         */
        TextWatcher validationTextWatcher = new TextWatcher() {
            public void afterTextChanged(Editable s) {
                validateFields();
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        };
        mUsernameView.addTextChangedListener(validationTextWatcher);
        mPasswordView.addTextChangedListener(validationTextWatcher);
        mServerView.addTextChangedListener(validationTextWatcher);
        mDomainView.addTextChangedListener(validationTextWatcher);

        mAccount = (Account)getIntent().getSerializableExtra(EXTRA_ACCOUNT);
        mMakeDefault = getIntent().getBooleanExtra(EXTRA_MAKE_DEFAULT, false);

        /*
         * If we're being reloaded we override the original account with the one
         * we saved
         */
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_ACCOUNT)) {
            mAccount = (Account)savedInstanceState.getSerializable(EXTRA_ACCOUNT);
        }

        try {
            URI uri = new URI(mAccount.getStoreUri());
            String username = null;
            String password = null;
            if (uri.getUserInfo() != null) {
                String[] userInfoParts = uri.getUserInfo().split(":", 2);
                username = userInfoParts[0];
                if (userInfoParts.length > 1) {
                    password = userInfoParts[1];
                }
            }

            if (username != null) {
                mUsernameView.setText(username);
            }

            if (password != null) {
                mPasswordView.setText(password);
            }

            if (uri.getScheme().startsWith("eas")) {
                // any other setup from mAccount can go here
            } else {
                throw new Error("Unknown account type: " + mAccount.getStoreUri());
            }

            if (uri.getHost() != null) {
                mServerView.setText(uri.getHost());
            }
            
            String domain = uri.getPath();
            if (!TextUtils.isEmpty(domain)) {
                mDomainView.setText(domain.substring(1));
            }
            
            mSslSecurityView.setChecked(uri.getScheme().contains("ssl"));

        } catch (URISyntaxException use) {
            /*
             * We should always be able to parse our own settings.
             */
            throw new Error(use);
        }

        validateFields();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putSerializable(EXTRA_ACCOUNT, mAccount);
    }

    /**
     * Check the values in the fields and decide if it makes sense to enable the "next" button
     * NOTE:  Does it make sense to extract & combine with similar code in AccountSetupIncoming? 
     */
    private void validateFields() {
        boolean enabled = Utility.requiredFieldValid(mUsernameView)
                && Utility.requiredFieldValid(mPasswordView)
                && Utility.requiredFieldValid(mServerView);
        if (enabled) {
            try {
                URI uri = getUri();
            } catch (URISyntaxException use) {
                enabled = false;
            }
        }
        mNextButton.setEnabled(enabled);
        Utility.setCompoundDrawablesAlpha(mNextButton, enabled ? 255 : 128);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            if (Intent.ACTION_EDIT.equals(getIntent().getAction())) {
                mAccount.save(Preferences.getPreferences(this));
                finish();
            } else {
                // Go directly to end - there is no 2nd screen for incoming settings
                AccountSetupOptions.actionOptions(this, mAccount, mMakeDefault);
                finish();
            }
        }
    }
    
    /**
     * Attempt to create a URI from the fields provided.  Throws URISyntaxException if there's 
     * a problem with the user input.
     * @return a URI built from the account setup fields
     */
    private URI getUri() throws URISyntaxException {
        boolean sslRequired = mSslSecurityView.isChecked();
        String scheme = sslRequired ? "eas+ssl+" : "eas";
        String userInfo = mUsernameView.getText().toString().trim() + ":" + 
                mPasswordView.getText().toString().trim();
        String host = mServerView.getText().toString().trim();
        String domain = mDomainView.getText().toString().trim();
        String path = null;
        if (domain.length() > 0) {
            path = "/" + domain;
        }

        URI uri = new URI(
                scheme,
                userInfo,
                host,
                0,
                path,
                null,
                null);

        return uri;
    }

    /**
     * Note, in EAS, store & sender are the same, so we always populate them together
     */
    private void onNext() {
        try {
            URI uri = getUri();
            mAccount.setStoreUri(uri.toString());
            mAccount.setSenderUri(uri.toString());
        } catch (URISyntaxException use) {
            /*
             * It's unrecoverable if we cannot create a URI from components that
             * we validated to be safe.
             */
            throw new Error(use);
        }

        AccountSetupCheckSettings.actionCheckSettings(this, mAccount, true, false);
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.next:
                onNext();
                break;
        }
    }
}
