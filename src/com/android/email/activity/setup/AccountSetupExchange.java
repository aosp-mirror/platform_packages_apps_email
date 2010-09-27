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

import com.android.email.AccountBackupRestore;
import com.android.email.ExchangeUtils;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.exchange.SyncManager;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.RemoteException;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.CompoundButton.OnCheckedChangeListener;

import java.io.IOException;
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
 *
 * There are two primary paths through this activity:
 *   Edit existing:
 *     Load existing values from account into fields
 *     When user clicks 'next':
 *       Confirm not a duplicate account
 *       Try new values (check settings)
 *       If new values are OK:
 *         Write new values (save to provider)
 *         finish() (pop to previous)
 *
 *   Creating New:
 *     Try Auto-discover to get details from server
 *     If Auto-discover reports an authentication failure:
 *       finish() (pop to previous, to re-enter username & password)
 *     If Auto-discover succeeds:
 *       write server's account details into account
 *     Load values from account into fields
 *     Confirm not a duplicate account
 *     Try new values (check settings)
 *     If new values are OK:
 *       Write new values (save to provider)
 *       Proceed to options screen
 *       finish() (removes self from back stack)
 *
 * NOTE: The manifest for this activity has it ignore config changes, because
 * we don't want to restart on every orientation - this would launch autodiscover again.
 * Do not attempt to define orientation-specific resources, they won't be loaded.
 */
public class AccountSetupExchange extends Activity implements OnClickListener,
        OnCheckedChangeListener {
    /*package*/ static final String EXTRA_ACCOUNT = "account";
    private static final String EXTRA_MAKE_DEFAULT = "makeDefault";
    private static final String EXTRA_EAS_FLOW = "easFlow";
    /*package*/ static final String EXTRA_DISABLE_AUTO_DISCOVER = "disableAutoDiscover";

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
            boolean makeDefault, boolean easFlowMode, boolean allowAutoDiscover) {
        Intent i = new Intent(fromActivity, AccountSetupExchange.class);
        i.putExtra(EXTRA_ACCOUNT, account);
        i.putExtra(EXTRA_MAKE_DEFAULT, makeDefault);
        i.putExtra(EXTRA_EAS_FLOW, easFlowMode);
        if (!allowAutoDiscover) {
            i.putExtra(EXTRA_DISABLE_AUTO_DISCOVER, true);
        }
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

        Intent intent = getIntent();
        mAccount = (EmailContent.Account) intent.getParcelableExtra(EXTRA_ACCOUNT);
        mMakeDefault = intent.getBooleanExtra(EXTRA_MAKE_DEFAULT, false);

        /*
         * If we're being reloaded we override the original account with the one
         * we saved
         */
        if (savedInstanceState != null && savedInstanceState.containsKey(EXTRA_ACCOUNT)) {
            mAccount = (EmailContent.Account) savedInstanceState.getParcelable(EXTRA_ACCOUNT);
        }

        loadFields(mAccount);
        validateFields();

        // If we've got a username and password and we're NOT editing, try autodiscover
        String username = mAccount.mHostAuthRecv.mLogin;
        String password = mAccount.mHostAuthRecv.mPassword;
        if (username != null && password != null &&
                !Intent.ACTION_EDIT.equals(intent.getAction())) {
            // NOTE: Disabling AutoDiscover is only used in unit tests
            boolean disableAutoDiscover =
                intent.getBooleanExtra(EXTRA_DISABLE_AUTO_DISCOVER, false);
            if (!disableAutoDiscover) {
                AccountSetupCheckSettings
                    .actionAutoDiscover(this, mAccount, mAccount.mEmailAddress, password);
            }
        }

        //EXCHANGE-REMOVE-SECTION-START
        // Show device ID
        try {
            ((TextView) findViewById(R.id.device_id)).setText(SyncManager.getDeviceId(this));
        } catch (IOException ignore) {
            // There's nothing we can do here...
        }
        //EXCHANGE-REMOVE-SECTION-END
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
     * Copy mAccount's values into UI fields
     */
    /* package */ void loadFields(Account account) {
        HostAuth hostAuth = account.mHostAuthRecv;

        String userName = hostAuth.mLogin;
        if (userName != null) {
            // Add a backslash to the start of the username, but only if the username has no
            // backslash in it.
            if (userName.indexOf('\\') < 0) {
                userName = "\\" + userName;
            }
            mUsernameView.setText(userName);
        }

        if (hostAuth.mPassword != null) {
            mPasswordView.setText(hostAuth.mPassword);
        }

        String protocol = hostAuth.mProtocol;
        if (protocol == null || !protocol.startsWith("eas")) {
            throw new Error("Unknown account type: " + account.getStoreUri(this));
        }

        if (hostAuth.mAddress != null) {
            mServerView.setText(hostAuth.mAddress);
        }

        boolean ssl = 0 != (hostAuth.mFlags & HostAuth.FLAG_SSL);
        boolean trustCertificates = 0 != (hostAuth.mFlags & HostAuth.FLAG_TRUST_ALL_CERTIFICATES);
        mSslSecurityView.setChecked(ssl);
        mTrustCertificatesView.setChecked(trustCertificates);
        mTrustCertificatesView.setVisibility(ssl ? View.VISIBLE : View.GONE);
    }

    /**
     * Check the values in the fields and decide if it makes sense to enable the "next" button
     * NOTE:  Does it make sense to extract & combine with similar code in AccountSetupIncoming?
     * @return true if all fields are valid, false if fields are incomplete
     */
    private boolean validateFields() {
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
        return enabled;
    }

    private void doOptions() {
        boolean easFlowMode = getIntent().getBooleanExtra(EXTRA_EAS_FLOW, false);
        AccountSetupOptions.actionOptions(this, mAccount, mMakeDefault, easFlowMode);
        finish();
    }

    /**
     * There are three cases handled here, so we split out into separate sections.
     * 1.  Validate existing account (edit)
     * 2.  Validate new account
     * 3.  Autodiscover for new account
     *
     * For each case, there are two or more paths for success or failure.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == AccountSetupCheckSettings.REQUEST_CODE_VALIDATE) {
            if (Intent.ACTION_EDIT.equals(getIntent().getAction())) {
                doActivityResultValidateExistingAccount(resultCode, data);
            } else {
                doActivityResultValidateNewAccount(resultCode, data);
            }
        } else if (requestCode == AccountSetupCheckSettings.REQUEST_CODE_AUTO_DISCOVER) {
            doActivityResultAutoDiscoverNewAccount(resultCode, data);
        }
    }

    /**
     * Process activity result when validating existing account
     */
    private void doActivityResultValidateExistingAccount(int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            if (mAccount.isSaved()) {
                // Account.update will NOT save the HostAuth's
                mAccount.update(this, mAccount.toContentValues());
                mAccount.mHostAuthRecv.update(this,
                        mAccount.mHostAuthRecv.toContentValues());
                mAccount.mHostAuthSend.update(this,
                        mAccount.mHostAuthSend.toContentValues());
                if (mAccount.mHostAuthRecv.mProtocol.equals("eas")) {
                    // For EAS, notify SyncManager that the password has changed
                    try {
                        ExchangeUtils.getExchangeEmailService(this, null)
                        .hostChanged(mAccount.mId);
                    } catch (RemoteException e) {
                        // Nothing to be done if this fails
                    }
                }
            } else {
                // Account.save will save the HostAuth's
                mAccount.save(this);
            }
            // Update the backup (side copy) of the accounts
            AccountBackupRestore.backupAccounts(this);
            finish();
        }
        // else (resultCode not OK) - just return into this activity for further editing
    }

    /**
     * Process activity result when validating new account
     */
    private void doActivityResultValidateNewAccount(int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            // Go directly to next screen
            doOptions();
        } else if (resultCode == AccountSetupCheckSettings.RESULT_SECURITY_REQUIRED_USER_CANCEL) {
            finish();
        }
        // else (resultCode not OK) - just return into this activity for further editing
    }

    /**
     * Process activity result when validating new account
     */
    private void doActivityResultAutoDiscoverNewAccount(int resultCode, Intent data) {
        // If authentication failed, exit immediately (to re-enter credentials)
        if (resultCode == AccountSetupCheckSettings.RESULT_AUTO_DISCOVER_AUTH_FAILED) {
            finish();
            return;
        }

        // If data was returned, populate the account & populate the UI fields and validate it
        if (data != null) {
            Parcelable p = data.getParcelableExtra("HostAuth");
            if (p != null) {
                HostAuth hostAuth = (HostAuth)p;
                mAccount.mHostAuthSend = hostAuth;
                mAccount.mHostAuthRecv = hostAuth;
                loadFields(mAccount);
                if (validateFields()) {
                    // "click" next to launch server verification
                    onNext();
                }
            }
        }
        // Otherwise, proceed into this activity for manual setup
    }

    /**
     * Attempt to create a URI from the fields provided.  Throws URISyntaxException if there's
     * a problem with the user input.
     * @return a URI built from the account setup fields
     */
    /* package */ URI getUri() throws URISyntaxException {
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
        String userInfo = userName + ":" + mPasswordView.getText();
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

        AccountSetupCheckSettings.actionValidateSettings(this, mAccount, true, false);
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
