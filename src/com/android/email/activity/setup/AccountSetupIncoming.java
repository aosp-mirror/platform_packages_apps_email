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
import com.android.email.AccountBackupRestore;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.provider.EmailContent;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.net.URI;
import java.net.URISyntaxException;

public class AccountSetupIncoming extends Activity implements OnClickListener {
    private static final String EXTRA_ACCOUNT = "account";
    private static final String EXTRA_MAKE_DEFAULT = "makeDefault";

    private static final int POP_PORTS[] = {
            110, 995, 995, 110, 110
    };
    private static final String POP_SCHEMES[] = {
            "pop3", "pop3+ssl+", "pop3+ssl+trustallcerts", "pop3+tls+", "pop3+tls+trustallcerts"
    };
    private static final int IMAP_PORTS[] = {
            143, 993, 993, 143, 143
    };
    private static final String IMAP_SCHEMES[] = {
            "imap", "imap+ssl+", "imap+ssl+trustallcerts", "imap+tls+", "imap+tls+trustallcerts"
    };

    private final static int DIALOG_DUPLICATE_ACCOUNT = 1;

    private int mAccountPorts[];
    private String mAccountSchemes[];
    private EditText mUsernameView;
    private EditText mPasswordView;
    private EditText mServerView;
    private EditText mPortView;
    private Spinner mSecurityTypeView;
    private Spinner mDeletePolicyView;
    private EditText mImapPathPrefixView;
    private Button mNextButton;
    private EmailContent.Account mAccount;
    private boolean mMakeDefault;
    private String mCacheLoginCredential;
    private String mDuplicateAccountName;

    public static void actionIncomingSettings(Activity fromActivity, EmailContent.Account account,
            boolean makeDefault) {
        Intent i = new Intent(fromActivity, AccountSetupIncoming.class);
        i.putExtra(EXTRA_ACCOUNT, account);
        i.putExtra(EXTRA_MAKE_DEFAULT, makeDefault);
        fromActivity.startActivity(i);
    }

    public static void actionEditIncomingSettings(Activity fromActivity, EmailContent.Account account)
            {
        Intent i = new Intent(fromActivity, AccountSetupIncoming.class);
        i.setAction(Intent.ACTION_EDIT);
        i.putExtra(EXTRA_ACCOUNT, account);
        fromActivity.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_incoming);

        mUsernameView = (EditText)findViewById(R.id.account_username);
        mPasswordView = (EditText)findViewById(R.id.account_password);
        TextView serverLabelView = (TextView) findViewById(R.id.account_server_label);
        mServerView = (EditText)findViewById(R.id.account_server);
        mPortView = (EditText)findViewById(R.id.account_port);
        mSecurityTypeView = (Spinner)findViewById(R.id.account_security_type);
        mDeletePolicyView = (Spinner)findViewById(R.id.account_delete_policy);
        mImapPathPrefixView = (EditText)findViewById(R.id.imap_path_prefix);
        mNextButton = (Button)findViewById(R.id.next);

        mNextButton.setOnClickListener(this);

        SpinnerOption securityTypes[] = {
            new SpinnerOption(0, getString(R.string.account_setup_incoming_security_none_label)),
            new SpinnerOption(1, getString(R.string.account_setup_incoming_security_ssl_label)),
            new SpinnerOption(2, getString(
                    R.string.account_setup_incoming_security_ssl_trust_certificates_label)),
            new SpinnerOption(3, getString(R.string.account_setup_incoming_security_tls_label)),
            new SpinnerOption(4, getString(
                    R.string.account_setup_incoming_security_tls_trust_certificates_label)),
        };

        SpinnerOption deletePolicies[] = {
                new SpinnerOption(Account.DELETE_POLICY_NEVER,
                        getString(R.string.account_setup_incoming_delete_policy_never_label)),
                new SpinnerOption(Account.DELETE_POLICY_ON_DELETE,
                        getString(R.string.account_setup_incoming_delete_policy_delete_label)),
        };

        ArrayAdapter<SpinnerOption> securityTypesAdapter = new ArrayAdapter<SpinnerOption>(this,
                android.R.layout.simple_spinner_item, securityTypes);
        securityTypesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSecurityTypeView.setAdapter(securityTypesAdapter);

        ArrayAdapter<SpinnerOption> deletePoliciesAdapter = new ArrayAdapter<SpinnerOption>(this,
                android.R.layout.simple_spinner_item, deletePolicies);
        deletePoliciesAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mDeletePolicyView.setAdapter(deletePoliciesAdapter);

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
            URI uri = new URI(mAccount.getStoreUri(this));
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

            if (uri.getScheme().startsWith("pop3")) {
                serverLabelView.setText(R.string.account_setup_incoming_pop_server_label);
                mAccountPorts = POP_PORTS;
                mAccountSchemes = POP_SCHEMES;

                findViewById(R.id.imap_path_prefix_section).setVisibility(View.GONE);
            } else if (uri.getScheme().startsWith("imap")) {
                serverLabelView.setText(R.string.account_setup_incoming_imap_server_label);
                mAccountPorts = IMAP_PORTS;
                mAccountSchemes = IMAP_SCHEMES;

                findViewById(R.id.account_delete_policy_label).setVisibility(View.GONE);
                mDeletePolicyView.setVisibility(View.GONE);
                if (uri.getPath() != null && uri.getPath().length() > 0) {
                    mImapPathPrefixView.setText(uri.getPath().substring(1));
                }
            } else {
                throw new Error("Unknown account type: " + mAccount.getStoreUri(this));
            }

            for (int i = 0; i < mAccountSchemes.length; i++) {
                if (mAccountSchemes[i].equals(uri.getScheme())) {
                    SpinnerOption.setSpinnerOptionValue(mSecurityTypeView, i);
                }
            }

            SpinnerOption.setSpinnerOptionValue(mDeletePolicyView, mAccount.getDeletePolicy());

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
     * Prepare a cached dialog with current values (e.g. account name)
     */
    @Override
    public Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_DUPLICATE_ACCOUNT:
                return new AlertDialog.Builder(this)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle(R.string.account_duplicate_dlg_title)
                    .setMessage(getString(R.string.account_duplicate_dlg_message_fmt,
                            mDuplicateAccountName))
                    .setPositiveButton(R.string.okay_action,
                            new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dismissDialog(DIALOG_DUPLICATE_ACCOUNT);
                        }
                    })
                    .create();
        }
        return null;
    }

    /**
     * Update a cached dialog with current values (e.g. account name)
     */
    @Override
    public void onPrepareDialog(int id, Dialog dialog) {
        switch (id) {
            case DIALOG_DUPLICATE_ACCOUNT:
                if (mDuplicateAccountName != null) {
                    AlertDialog alert = (AlertDialog) dialog;
                    alert.setMessage(getString(R.string.account_duplicate_dlg_message_fmt,
                            mDuplicateAccountName));
                }
                break;
        }
    }

    /**
     * Check the values in the fields and decide if it makes sense to enable the "next" button
     * NOTE:  Does it make sense to extract & combine with similar code in AccountSetupIncoming? 
     */
    private void validateFields() {
        boolean enabled = Utility.requiredFieldValid(mUsernameView)
                && Utility.requiredFieldValid(mPasswordView)
                && Utility.requiredFieldValid(mServerView)
                && Utility.isPortFieldValid(mPortView);
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
        mPortView.setText(Integer.toString(mAccountPorts[securityType]));
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            if (Intent.ACTION_EDIT.equals(getIntent().getAction())) {
                if (mAccount.isSaved()) {
                    mAccount.update(this, mAccount.toContentValues());
                    mAccount.mHostAuthRecv.update(this, mAccount.mHostAuthRecv.toContentValues());
                } else {
                    mAccount.save(this);
                }
                // Update the backup (side copy) of the accounts
                AccountBackupRestore.backupAccounts(this);
                finish();
            } else {
                /*
                 * Set the username and password for the outgoing settings to the username and
                 * password the user just set for incoming.
                 */
                try {
                    URI oldUri = new URI(mAccount.getSenderUri(this));
                    URI uri = new URI(
                            oldUri.getScheme(),
                            mUsernameView.getText().toString().trim() + ":" 
                                    + mPasswordView.getText().toString(),
                            oldUri.getHost(),
                            oldUri.getPort(),
                            null,
                            null,
                            null);
                    mAccount.setSenderUri(this, uri.toString());
                } catch (URISyntaxException use) {
                    /*
                     * If we can't set up the URL we just continue. It's only for
                     * convenience.
                     */
                }

                AccountSetupOutgoing.actionOutgoingSettings(this, mAccount, mMakeDefault);
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
        String path = null;
        if (mAccountSchemes[securityType].startsWith("imap")) {
            path = "/" + mImapPathPrefixView.getText().toString().trim();
        }
        String userName = mUsernameView.getText().toString().trim();
        mCacheLoginCredential = userName;
        URI uri = new URI(
                mAccountSchemes[securityType],
                userName + ":" + mPasswordView.getText(),
                mServerView.getText().toString().trim(),
                Integer.parseInt(mPortView.getText().toString().trim()),
                path, // path
                null, // query
                null);

        return uri;
    }

    private void onNext() {
        try {
            URI uri = getUri();
            mAccount.setStoreUri(this, uri.toString());

            // Stop here if the login credentials duplicate an existing account
            // (unless they duplicate the existing account, as they of course will)
            mDuplicateAccountName = Utility.findDuplicateAccount(this, mAccount.mId,
                    uri.getHost(), mCacheLoginCredential);
            if (mDuplicateAccountName != null) {
                this.showDialog(DIALOG_DUPLICATE_ACCOUNT);
                return;
            }
        } catch (URISyntaxException use) {
            /*
             * It's unrecoverable if we cannot create a URI from components that
             * we validated to be safe.
             */
            throw new Error(use);
        }

        mAccount.setDeletePolicy((Integer)((SpinnerOption)mDeletePolicyView.getSelectedItem()).value);
        AccountSetupCheckSettings.actionValidateSettings(this, mAccount, true, false);
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.next:
                onNext();
                break;
        }
    }
}
