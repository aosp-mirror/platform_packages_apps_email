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

import com.android.email.AccountBackupRestore;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.provider.EmailContent;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.CompoundButton.OnCheckedChangeListener;

import java.net.URI;
import java.net.URISyntaxException;

public class AccountSetupOutgoing extends Activity implements OnClickListener,
        OnCheckedChangeListener {
    private static final String EXTRA_ACCOUNT = "account";

    private static final String EXTRA_MAKE_DEFAULT = "makeDefault";

    private static final int SMTP_PORTS[] = {
            587, 465, 465, 587, 587
    };

    private static final String SMTP_SCHEMES[] = {
            "smtp", "smtp+ssl+", "smtp+ssl+trustallcerts", "smtp+tls+", "smtp+tls+trustallcerts"
    };

    private EditText mUsernameView;
    private EditText mPasswordView;
    private EditText mServerView;
    private EditText mPortView;
    private CheckBox mRequireLoginView;
    private ViewGroup mRequireLoginSettingsView;
    private Spinner mSecurityTypeView;
    private Button mNextButton;
    private EmailContent.Account mAccount;
    private boolean mMakeDefault;

    public static void actionOutgoingSettings(Activity fromActivity, EmailContent.Account account, 
            boolean makeDefault) {
        Intent i = new Intent(fromActivity, AccountSetupOutgoing.class);
        i.putExtra(EXTRA_ACCOUNT, account);
        i.putExtra(EXTRA_MAKE_DEFAULT, makeDefault);
        fromActivity.startActivity(i);
    }

    public static void actionEditOutgoingSettings(Activity fromActivity, EmailContent.Account account)
            {
        Intent i = new Intent(fromActivity, AccountSetupOutgoing.class);
        i.setAction(Intent.ACTION_EDIT);
        i.putExtra(EXTRA_ACCOUNT, account);
        fromActivity.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_outgoing);

        mUsernameView = (EditText)findViewById(R.id.account_username);
        mPasswordView = (EditText)findViewById(R.id.account_password);
        mServerView = (EditText)findViewById(R.id.account_server);
        mPortView = (EditText)findViewById(R.id.account_port);
        mRequireLoginView = (CheckBox)findViewById(R.id.account_require_login);
        mRequireLoginSettingsView = (ViewGroup)findViewById(R.id.account_require_login_settings);
        mSecurityTypeView = (Spinner)findViewById(R.id.account_security_type);
        mNextButton = (Button)findViewById(R.id.next);

        mNextButton.setOnClickListener(this);
        mRequireLoginView.setOnCheckedChangeListener(this);

        SpinnerOption securityTypes[] = {
            new SpinnerOption(0, getString(R.string.account_setup_incoming_security_none_label)),
            new SpinnerOption(1, getString(R.string.account_setup_incoming_security_ssl_label)),
            new SpinnerOption(2, getString(
                    R.string.account_setup_incoming_security_ssl_trust_certificates_label)),
            new SpinnerOption(3, getString(R.string.account_setup_incoming_security_tls_label)),
            new SpinnerOption(4, getString(
                    R.string.account_setup_incoming_security_tls_trust_certificates_label)),
        };

        ArrayAdapter<SpinnerOption> securityTypesAdapter = new ArrayAdapter<SpinnerOption>(this,
                android.R.layout.simple_spinner_item, securityTypes);
        securityTypesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSecurityTypeView.setAdapter(securityTypesAdapter);

        /*
         * Updates the port when the user changes the security type. This allows
         * us to show a reasonable default which the user can change.
         */
        mSecurityTypeView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView arg0, View arg1, int arg2, long arg3) {
                updatePortFromSecurityType();
            }

            public void onNothingSelected(AdapterView<?> arg0) {
            }
        });

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
        mPortView.addTextChangedListener(validationTextWatcher);

        /*
         * Only allow digits in the port field.
         */
        mPortView.setKeyListener(DigitsKeyListener.getInstance("0123456789"));

        mAccount = (EmailContent.Account)getIntent().getParcelableExtra(EXTRA_ACCOUNT);
        mMakeDefault = getIntent().getBooleanExtra(EXTRA_MAKE_DEFAULT, false);

        /*
         * If we're being reloaded we override the original account with the one
         * we saved
         */
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_ACCOUNT)) {
            mAccount = (EmailContent.Account)savedInstanceState.getParcelable(EXTRA_ACCOUNT);
        }

        try {
            // TODO this should be accessed directly via the HostAuth structure
            URI uri = new URI(mAccount.getSenderUri(this));
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
                mRequireLoginView.setChecked(true);
            }

            if (password != null) {
                mPasswordView.setText(password);
            }

            for (int i = 0; i < SMTP_SCHEMES.length; i++) {
                if (SMTP_SCHEMES[i].equals(uri.getScheme())) {
                    SpinnerOption.setSpinnerOptionValue(mSecurityTypeView, i);
                }
            }

            if (uri.getHost() != null) {
                mServerView.setText(uri.getHost());
            }

            if (uri.getPort() != -1) {
                mPortView.setText(Integer.toString(uri.getPort()));
            } else {
                updatePortFromSecurityType();
            }
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
        outState.putParcelable(EXTRA_ACCOUNT, mAccount);
    }

    /**
     * Preflight the values in the fields and decide if it makes sense to enable the "next" button
     * NOTE:  Does it make sense to extract & combine with similar code in AccountSetupIncoming? 
     */
    private void validateFields() {
        boolean enabled = 
            Utility.requiredFieldValid(mServerView) 
                && Utility.isPortFieldValid(mPortView);

        if (enabled && mRequireLoginView.isChecked()) {
            enabled = (Utility.requiredFieldValid(mUsernameView)
                    && Utility.requiredFieldValid(mPasswordView));
        }

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

    private void updatePortFromSecurityType() {
        int securityType = (Integer)((SpinnerOption)mSecurityTypeView.getSelectedItem()).value;
        mPortView.setText(Integer.toString(SMTP_PORTS[securityType]));
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            if (Intent.ACTION_EDIT.equals(getIntent().getAction())) {
                if (mAccount.isSaved()) {
                    mAccount.update(this, mAccount.toContentValues());
                    mAccount.mHostAuthSend.update(this, mAccount.mHostAuthSend.toContentValues());
                } else {
                    mAccount.save(this);
                }
                // Update the backup (side copy) of the accounts
                AccountBackupRestore.backupAccounts(this);
                finish();
            } else {
                AccountSetupOptions.actionOptions(this, mAccount, mMakeDefault, false);
                finish();
            }
        }
    }
    
    /**
     * Attempt to create a URI from the fields provided.  Throws URISyntaxException if there's 
     * a problem with the user input.
     * @return a URI built from the account setup fields
     */
    /* package */ URI getUri() throws URISyntaxException {
        int securityType = (Integer)((SpinnerOption)mSecurityTypeView.getSelectedItem()).value;
        String userInfo = null;
        if (mRequireLoginView.isChecked()) {
            userInfo = mUsernameView.getText().toString().trim() + ":" + mPasswordView.getText();
        }
        URI uri = new URI(
                SMTP_SCHEMES[securityType],
                userInfo,
                mServerView.getText().toString().trim(),
                Integer.parseInt(mPortView.getText().toString().trim()),
                null, null, null);
        
        return uri;
    }

    private void onNext() {       
        try {
            // TODO this should be accessed directly via the HostAuth structure
            URI uri = getUri();
            mAccount.setSenderUri(this, uri.toString());
        } catch (URISyntaxException use) {
            /*
             * It's unrecoverable if we cannot create a URI from components that
             * we validated to be safe.
             */
            throw new Error(use);
        }
        AccountSetupCheckSettings.actionValidateSettings(this, mAccount, false, true);
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.next:
                onNext();
                break;
        }
    }

    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        mRequireLoginSettingsView.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        validateFields();
    }
}
