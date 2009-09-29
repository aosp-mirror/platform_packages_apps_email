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

import com.android.email.R;
import com.android.email.Utility;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.service.EmailServiceProxy;
import com.android.exchange.SyncManager;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.CompoundButton.OnCheckedChangeListener;

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
public class AccountSetupExchange extends Activity implements OnClickListener,
        OnCheckedChangeListener {
    private static final String EXTRA_ACCOUNT = "account";
    private static final String EXTRA_MAKE_DEFAULT = "makeDefault";
    private static final String EXTRA_EAS_FLOW = "easFlow";

    private final static int DIALOG_DUPLICATE_ACCOUNT = 1;

    private EditText mUsernameView;
    private EditText mPasswordView;
    private EditText mServerView;
    private CheckBox mSslSecurityView;
    private CheckBox mTrustCertificatesView;

    private Button mNextButton;
    private Account mAccount;
    private boolean mMakeDefault;
    private String mCacheLoginCredential;
    private String mDuplicateAccountName;

    public static void actionIncomingSettings(Activity fromActivity, Account account,
            boolean makeDefault, boolean easFlowMode) {
        Intent i = new Intent(fromActivity, AccountSetupExchange.class);
        i.putExtra(EXTRA_ACCOUNT, account);
        i.putExtra(EXTRA_MAKE_DEFAULT, makeDefault);
        i.putExtra(EXTRA_EAS_FLOW, easFlowMode);
        fromActivity.startActivity(i);
    }

    public static void actionEditIncomingSettings(Activity fromActivity, Account account)
            {
        Intent i = new Intent(fromActivity, AccountSetupExchange.class);
        i.setAction(Intent.ACTION_EDIT);
        i.putExtra(EXTRA_ACCOUNT, account);
        fromActivity.startActivity(i);
    }

    /**
     * For now, we'll simply replicate outgoing, for the purpose of satisfying the
     * account settings flow.
     */
    public static void actionEditOutgoingSettings(Activity fromActivity, Account account)
            {
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
        mSslSecurityView = (CheckBox) findViewById(R.id.account_ssl);
        mSslSecurityView.setOnCheckedChangeListener(this);
        mTrustCertificatesView = (CheckBox) findViewById(R.id.account_trust_certificates);

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

        mAccount = (EmailContent.Account) getIntent().getParcelableExtra(EXTRA_ACCOUNT);
        mMakeDefault = getIntent().getBooleanExtra(EXTRA_MAKE_DEFAULT, false);

        /*
         * If we're being reloaded we override the original account with the one
         * we saved
         */
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_ACCOUNT)) {
            mAccount = (EmailContent.Account) savedInstanceState.getParcelable(EXTRA_ACCOUNT);
        }

        try {
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
                // Add a backslash to the start of the username, but only if the username has no
                // backslash in it.
                if (username.indexOf('\\') < 0) {
                    username = "\\" + username;
                }
                mUsernameView.setText(username);
            }

            if (password != null) {
                mPasswordView.setText(password);
            }

            if (uri.getScheme().startsWith("eas")) {
                // any other setup from mAccount can go here
            } else {
                throw new Error("Unknown account type: " + mAccount.getStoreUri(this));
            }

            if (uri.getHost() != null) {
                mServerView.setText(uri.getHost());
            }

            boolean ssl = uri.getScheme().contains("ssl");
            mSslSecurityView.setChecked(ssl);
            mTrustCertificatesView.setChecked(uri.getScheme().contains("trustallcerts"));
            mTrustCertificatesView.setVisibility(ssl ? View.VISIBLE : View.GONE);

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

    private boolean usernameFieldValid(EditText usernameView) {
        return Utility.requiredFieldValid(usernameView) &&
            !usernameView.getText().toString().equals("\\");
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
        boolean enabled = usernameFieldValid(mUsernameView)
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
                if (mAccount.isSaved()) {
                    // Account.update will NOT save the HostAuth's
                    mAccount.update(this, mAccount.toContentValues());
                    mAccount.mHostAuthRecv.update(this, mAccount.mHostAuthRecv.toContentValues());
                    mAccount.mHostAuthSend.update(this, mAccount.mHostAuthSend.toContentValues());
                    if (mAccount.mHostAuthRecv.mProtocol.equals("eas")) {
                        // For EAS, notify SyncManager that the password has changed
                        try {
                            new EmailServiceProxy(this, SyncManager.class)
                                .hostChanged(mAccount.mId);
                        } catch (RemoteException e) {
                            // Nothing to be done if this fails
                        }
                    }
                } else {
                    // Account.save will save the HostAuth's
                    mAccount.save(this);
                }
                finish();
            } else {
                // Go directly to end - there is no 2nd screen for incoming settings
                boolean easFlowMode = getIntent().getBooleanExtra(EXTRA_EAS_FLOW, false);
                AccountSetupOptions.actionOptions(this, mAccount, mMakeDefault, easFlowMode);
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
        boolean trustCertificates = mTrustCertificatesView.isChecked();
        String scheme = (sslRequired)
                        ? (trustCertificates ? "eas+ssl+trustallcerts" : "eas+ssl+")
                        : "eas";
        String userName = mUsernameView.getText().toString().trim();
        // Remove a leading backslash, if there is one, since we now automatically put one at
        // the start of the username field
        if (userName.startsWith("\\")) {
            userName = userName.substring(1);
        }
        mCacheLoginCredential = userName;
        String userInfo = userName + ":" + mPasswordView.getText().toString().trim();
        String host = mServerView.getText().toString().trim();
        String path = null;

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
            mAccount.setStoreUri(this, uri.toString());
            mAccount.setSenderUri(this, uri.toString());

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

        AccountSetupCheckSettings.actionCheckSettings(this, mAccount, true, false);
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.next:
                onNext();
                break;
        }
    }

    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView.getId() == R.id.account_ssl) {
            mTrustCertificatesView.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        }
    }
}
