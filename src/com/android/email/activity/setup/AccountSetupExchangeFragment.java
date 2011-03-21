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

import com.android.email.AccountBackupRestore;
import com.android.email.Email;
import com.android.email.ExchangeUtils;
import com.android.email.R;
import com.android.email.mail.Store;
import com.android.emailcommon.Device;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.HostAuth;
import com.android.emailcommon.utility.Utility;

import android.app.Activity;
import android.content.Context;
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

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * Provides generic setup for Exchange accounts.
 *
 * This fragment is used by AccountSetupExchange (for creating accounts) and by AccountSettingsXL
 * (for editing existing accounts).
 */
public class AccountSetupExchangeFragment extends AccountServerBaseFragment
        implements OnCheckedChangeListener {

    private final static String STATE_KEY_CREDENTIAL = "AccountSetupExchangeFragment.credential";
    private final static String STATE_KEY_LOADED = "AccountSetupExchangeFragment.loaded";

    private EditText mUsernameView;
    private EditText mPasswordView;
    private EditText mServerView;
    private CheckBox mSslSecurityView;
    private CheckBox mTrustCertificatesView;
    private View mTrustCertificatesDivider;

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
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mCacheLoginCredential = savedInstanceState.getString(STATE_KEY_CREDENTIAL);
            mLoaded = savedInstanceState.getBoolean(STATE_KEY_LOADED, false);
        }
        mBaseScheme = Store.STORE_SCHEME_EAS;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onCreateView");
        }
        int layoutId = mSettingsMode
                ? R.layout.account_settings_exchange_fragment
                : R.layout.account_setup_exchange_fragment;

        View view = inflater.inflate(layoutId, container, false);
        Context context = getActivity();

        mUsernameView = (EditText) view.findViewById(R.id.account_username);
        mPasswordView = (EditText) view.findViewById(R.id.account_password);
        mServerView = (EditText) view.findViewById(R.id.account_server);
        mSslSecurityView = (CheckBox) view.findViewById(R.id.account_ssl);
        mSslSecurityView.setOnCheckedChangeListener(this);
        mTrustCertificatesView = (CheckBox) view.findViewById(R.id.account_trust_certificates);
        mTrustCertificatesDivider = view.findViewById(R.id.account_trust_certificates_divider);

        // Calls validateFields() which enables or disables the Next button
        // based on the fields' validity.
        TextWatcher validationTextWatcher = new TextWatcher() {
            public void afterTextChanged(Editable s) {
                validateFields();
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
        };
        mUsernameView.addTextChangedListener(validationTextWatcher);
        mPasswordView.addTextChangedListener(validationTextWatcher);
        mServerView.addTextChangedListener(validationTextWatcher);

        try {
            String deviceId = Device.getDeviceId(context);
            ((TextView) view.findViewById(R.id.device_id)).setText(deviceId);
        } catch (IOException e) {
            // Not required
        }

        // Additional setup only used while in "settings" mode
        onCreateViewSettingsMode(view);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
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
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onResume");
        }
        super.onResume();
        validateFields();
    }

    @Override
    public void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onPause");
        }
        super.onPause();
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
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
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupExchangeFragment onDestroy");
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
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
        }

        String protocol = hostAuth.mProtocol;
        if (protocol == null || !protocol.startsWith("eas")) {
            throw new Error("Unknown account type: " + account.getStoreUri(mContext));
        }

        if (hostAuth.mAddress != null) {
            mServerView.setText(hostAuth.mAddress);
        }

        boolean ssl = 0 != (hostAuth.mFlags & HostAuth.FLAG_SSL);
        boolean trustCertificates = 0 != (hostAuth.mFlags & HostAuth.FLAG_TRUST_ALL);
        mSslSecurityView.setChecked(ssl);
        mTrustCertificatesView.setChecked(trustCertificates);
        showTrustCertificates(ssl);

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
                && Utility.isTextViewNotEmpty(mServerView);
        if (enabled) {
            try {
                URI uri = getUri();
            } catch (URISyntaxException use) {
                enabled = false;
            }
        }
        enableNextButton(enabled);

        // Warn (but don't prevent) if password has leading/trailing spaces
        AccountSettingsUtils.checkPasswordSpaces(mContext, mPasswordView);

        return enabled;
    }

    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView.getId() == R.id.account_ssl) {
            showTrustCertificates(isChecked);
        }
    }

    public void showTrustCertificates(boolean visible) {
        int mode = visible ? View.VISIBLE : View.GONE;
        mTrustCertificatesView.setVisibility(mode);
        // Divider is optional (only on XL layouts)
        if (mTrustCertificatesDivider != null) {
            mTrustCertificatesDivider.setVisibility(mode);
        }
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
            ExchangeUtils.getExchangeService(mContext, null).hostChanged(account.mId);
        } catch (RemoteException e) {
            // Nothing to be done if this fails
        }
        // Update the backup (side copy) of the accounts
        AccountBackupRestore.backupAccounts(mContext);
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
     * Attempt to create a URI from the fields provided.  Throws URISyntaxException if there's
     * a problem with the user input.
     * @return a URI built from the account setup fields
     */
    @Override
    protected URI getUri() throws URISyntaxException {
        Account account = SetupData.getAccount();
        boolean sslRequired = mSslSecurityView.isChecked();
        boolean trustCertificates = mTrustCertificatesView.isChecked();
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
        int port = mSslSecurityView.isChecked() ? 443 : 80;
        // Ensure TLS is not set
        int flags = account.getOrCreateHostAuthRecv(mContext).mFlags & ~HostAuth.FLAG_TLS;

        URI uri = new URI(
                HostAuth.getSchemeString(mBaseScheme, flags),
                userInfo,
                host,
                port,
                path,
                null,
                null);
        return uri;
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
        EmailContent.Account account = SetupData.getAccount();

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
        String serverAddress = mServerView.getText().toString().trim();

        int port = mSslSecurityView.isChecked() ? 443 : 80;
        HostAuth sendAuth = account.getOrCreateHostAuthSend(mContext);
        sendAuth.setLogin(userName, userPassword);
        sendAuth.setConnection(mBaseScheme, serverAddress, port, flags);
        sendAuth.mDomain = null;

        HostAuth recvAuth = account.getOrCreateHostAuthRecv(mContext);
        recvAuth.setLogin(userName, userPassword);
        recvAuth.setConnection(mBaseScheme, serverAddress, port, flags);
        recvAuth.mDomain = null;

        // Check for a duplicate account (requires async DB work) and if OK, proceed with check
        startDuplicateTaskCheck(account.mId, serverAddress, mCacheLoginCredential,
                SetupData.CHECK_INCOMING);
    }

}
