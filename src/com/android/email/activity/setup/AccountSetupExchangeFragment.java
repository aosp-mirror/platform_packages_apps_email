/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.TextView;

import com.android.email.Email;
import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.provider.AccountBackupRestore;
import com.android.email.service.EmailServiceUtils;
import com.android.email.view.CertificateSelector;
import com.android.email.view.CertificateSelector.HostCallback;
import com.android.emailcommon.Device;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.CertificateRequestor;
import com.android.emailcommon.utility.Utility;

import java.io.IOException;

/**
 * Provides generic setup for Exchange accounts.
 *
 * This fragment is used by AccountSetupExchange (for creating accounts) and by AccountSettingsXL
 * (for editing existing accounts).
 */
public class AccountSetupExchangeFragment extends AccountServerBaseFragment
        implements OnCheckedChangeListener, HostCallback {

    private static final int CERTIFICATE_REQUEST = 0;
    private final static String STATE_KEY_CREDENTIAL = "AccountSetupExchangeFragment.credential";
    private final static String STATE_KEY_LOADED = "AccountSetupExchangeFragment.loaded";

    private EditText mUsernameView;
    private EditText mPasswordView;
    private EditText mServerView;
    private CheckBox mSslSecurityView;
    private CheckBox mTrustCertificatesView;
    private CertificateSelector mClientCertificateSelector;

    // Support for lifecycle
    private boolean mStarted;
    /* package */ boolean mLoaded;
    private String mCacheLoginCredential;

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mCacheLoginCredential = savedInstanceState.getString(STATE_KEY_CREDENTIAL);
            mLoaded = savedInstanceState.getBoolean(STATE_KEY_LOADED, false);
        }
        mBaseScheme = HostAuth.SCHEME_EAS;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onCreateView");
        }
        int layoutId = mSettingsMode
                ? R.layout.account_settings_exchange_fragment
                : R.layout.account_setup_exchange_fragment;

        View view = inflater.inflate(layoutId, container, false);
        final Context context = getActivity();

        mUsernameView = UiUtilities.getView(view, R.id.account_username);
        mPasswordView = UiUtilities.getView(view, R.id.account_password);
        mServerView = UiUtilities.getView(view, R.id.account_server);
        mSslSecurityView = UiUtilities.getView(view, R.id.account_ssl);
        mSslSecurityView.setOnCheckedChangeListener(this);
        mTrustCertificatesView = UiUtilities.getView(view, R.id.account_trust_certificates);
        mClientCertificateSelector = UiUtilities.getView(view, R.id.client_certificate_selector);

        // Calls validateFields() which enables or disables the Next button
        // based on the fields' validity.
        TextWatcher validationTextWatcher = new TextWatcher() {
            public void afterTextChanged(Editable s) {
                validateFields();
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
        };
        // We're editing an existing account; don't allow modification of the user name
        if (mSettingsMode) {
            makeTextViewUneditable(mUsernameView,
                    getString(R.string.account_setup_username_uneditable_error));
        }
        mUsernameView.addTextChangedListener(validationTextWatcher);
        mPasswordView.addTextChangedListener(validationTextWatcher);
        mServerView.addTextChangedListener(validationTextWatcher);

        EditText lastView = mServerView;
        lastView.setOnEditorActionListener(mDismissImeOnDoneListener);

        String deviceId = "";
        try {
            deviceId = Device.getDeviceId(context);
        } catch (IOException e) {
            // Not required
        }
        ((TextView) UiUtilities.getView(view, R.id.device_id)).setText(deviceId);

        // Additional setup only used while in "settings" mode
        onCreateViewSettingsMode(view);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);
        mClientCertificateSelector.setHostActivity(this);
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onStart");
        }
        super.onStart();
        mStarted = true;
        loadSettings(SetupData.getAccount());
    }

    /**
     * Called when the fragment is visible to the user and actively running.
     */
    @Override
    public void onResume() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onResume");
        }
        super.onResume();
        validateFields();
    }

    @Override
    public void onPause() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onPause");
        }
        super.onPause();
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onStop");
        }
        super.onStop();
        mStarted = false;
    }

    /**
     * Called when the fragment is no longer in use.
     */
    @Override
    public void onDestroy() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onDestroy");
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);

        outState.putString(STATE_KEY_CREDENTIAL, mCacheLoginCredential);
        outState.putBoolean(STATE_KEY_LOADED, mLoaded);
    }

    /**
     * Activity provides callbacks here.  This also triggers loading and setting up the UX
     */
    @Override
    public void setCallback(Callback callback) {
        super.setCallback(callback);
        if (mStarted) {
            loadSettings(SetupData.getAccount());
        }
    }

    /**
     * Force the given account settings to be loaded using {@link #loadSettings(Account)}.
     *
     * @return true if the loaded values pass validation
     */
    private boolean forceLoadSettings(Account account) {
        mLoaded = false;
        return loadSettings(account);
    }

    /**
     * Load the given account settings into the UI and then ensure the settings are valid.
     * As an optimization, if the settings have already been loaded, the UI will not be
     * updated, but, the account fields will still be validated.
     *
     * @return true if the loaded values pass validation
     */
    /*package*/ boolean loadSettings(Account account) {
        if (mLoaded) return validateFields();

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
            // Since username is uneditable, focus on the next editable field
            if (mSettingsMode) {
                mPasswordView.requestFocus();
            }
        }

        String protocol = hostAuth.mProtocol;
        if (protocol == null || !protocol.startsWith("eas")) {
            throw new Error("Unknown account type: " + protocol);
        }

        if (hostAuth.mAddress != null) {
            mServerView.setText(hostAuth.mAddress);
        }

        boolean ssl = 0 != (hostAuth.mFlags & HostAuth.FLAG_SSL);
        boolean trustCertificates = 0 != (hostAuth.mFlags & HostAuth.FLAG_TRUST_ALL);
        mSslSecurityView.setChecked(ssl);
        mTrustCertificatesView.setChecked(trustCertificates);
        if (hostAuth.mClientCertAlias != null) {
            mClientCertificateSelector.setCertificate(hostAuth.mClientCertAlias);
        }
        onUseSslChanged(ssl);

        mLoadedRecvAuth = hostAuth;
        mLoaded = true;
        return validateFields();
    }

    private boolean usernameFieldValid(EditText usernameView) {
        return Utility.isTextViewNotEmpty(usernameView) &&
            !usernameView.getText().toString().equals("\\");
    }

    /**
     * Check the values in the fields and decide if it makes sense to enable the "next" button
     * @return true if all fields are valid, false if any fields are incomplete
     */
    private boolean validateFields() {
        if (!mLoaded) return false;
        boolean enabled = usernameFieldValid(mUsernameView)
                && Utility.isTextViewNotEmpty(mPasswordView)
                && Utility.isServerNameValid(mServerView);
        enableNextButton(enabled);

        // Warn (but don't prevent) if password has leading/trailing spaces
        AccountSettingsUtils.checkPasswordSpaces(mContext, mPasswordView);

        return enabled;
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView.getId() == R.id.account_ssl) {
            onUseSslChanged(isChecked);
        }
    }

    public void onUseSslChanged(boolean useSsl) {
        int mode = useSsl ? View.VISIBLE : View.GONE;
        mTrustCertificatesView.setVisibility(mode);
        UiUtilities.setVisibilitySafe(getView(), R.id.account_trust_certificates_divider, mode);
        mClientCertificateSelector.setVisibility(mode);
        UiUtilities.setVisibilitySafe(getView(), R.id.client_certificate_divider, mode);
    }

    @Override
    public void onCheckSettingsComplete(final int result) {
        if (result == AccountCheckSettingsFragment.CHECK_SETTINGS_CLIENT_CERTIFICATE_NEEDED) {
            mSslSecurityView.setChecked(true);
            onCertificateRequested();
            return;
        }
        super.onCheckSettingsComplete(result);
    }


    /**
     * Entry point from Activity after editing settings and verifying them.  Must be FLOW_MODE_EDIT.
     * Blocking - do not call from UI Thread.
     */
    @Override
    public void saveSettingsAfterEdit() {
        Account account = SetupData.getAccount();
        account.mHostAuthRecv.update(mContext, account.mHostAuthRecv.toContentValues());
        account.mHostAuthSend.update(mContext, account.mHostAuthSend.toContentValues());
        // For EAS, notify ExchangeService that the password has changed
        try {
            EmailServiceUtils.getExchangeService(mContext, null).hostChanged(account.mId);
        } catch (RemoteException e) {
            // Nothing to be done if this fails
        }
        // Update the backup (side copy) of the accounts
        AccountBackupRestore.backup(mContext);
    }

    /**
     * Entry point from Activity after entering new settings and verifying them.  For setup mode.
     */
    @Override
    public void saveSettingsAfterSetup() {
    }

    /**
     * Entry point from Activity after entering new settings and verifying them.  For setup mode.
     */
    public boolean setHostAuthFromAutodiscover(HostAuth newHostAuth) {
        Account account = SetupData.getAccount();
        account.mHostAuthSend = newHostAuth;
        account.mHostAuthRecv = newHostAuth;
        // Auto discovery may have changed the auth settings; force load them
        return forceLoadSettings(account);
    }

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     */
    @Override
    public void onAutoDiscoverComplete(int result, HostAuth hostAuth) {
        AccountSetupExchange activity = (AccountSetupExchange) getActivity();
        activity.onAutoDiscoverComplete(result, hostAuth);
    }

    /**
     * Entry point from Activity, when "next" button is clicked
     */
    @Override
    public void onNext() {
        Account account = SetupData.getAccount();

        String userName = mUsernameView.getText().toString().trim();
        if (userName.startsWith("\\")) {
            userName = userName.substring(1);
        }
        mCacheLoginCredential = userName;
        String userPassword = mPasswordView.getText().toString();

        int flags = 0;
        if (mSslSecurityView.isChecked()) {
            flags |= HostAuth.FLAG_SSL;
        }
        if (mTrustCertificatesView.isChecked()) {
            flags |= HostAuth.FLAG_TRUST_ALL;
        }
        String certAlias = mClientCertificateSelector.getCertificate();
        String serverAddress = mServerView.getText().toString().trim();

        int port = mSslSecurityView.isChecked() ? 443 : 80;
        HostAuth sendAuth = account.getOrCreateHostAuthSend(mContext);
        sendAuth.setLogin(userName, userPassword);
        sendAuth.setConnection(mBaseScheme, serverAddress, port, flags, certAlias);
        sendAuth.mDomain = null;

        HostAuth recvAuth = account.getOrCreateHostAuthRecv(mContext);
        recvAuth.setLogin(userName, userPassword);
        recvAuth.setConnection(mBaseScheme, serverAddress, port, flags, certAlias);
        recvAuth.mDomain = null;

        // Check for a duplicate account (requires async DB work) and if OK, proceed with check
        startDuplicateTaskCheck(account.mId, serverAddress, mCacheLoginCredential,
                SetupData.CHECK_INCOMING);
    }

    @Override
    public void onCertificateRequested() {
        Intent intent = new Intent(CertificateRequestor.ACTION_REQUEST_CERT);
        intent.setData(Uri.parse("eas://com.android.emailcommon/certrequest"));
        startActivityForResult(intent, CERTIFICATE_REQUEST);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CERTIFICATE_REQUEST && resultCode == Activity.RESULT_OK) {
            String certAlias = data.getStringExtra(CertificateRequestor.RESULT_ALIAS);
            if (certAlias != null) {
                mClientCertificateSelector.setCertificate(certAlias);
            }
        }
    }

}
