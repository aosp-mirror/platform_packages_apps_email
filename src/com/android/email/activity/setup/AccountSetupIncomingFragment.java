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
import android.content.Loader;
import android.os.Bundle;
import android.os.Parcel;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.activity.setup.AuthenticationView.AuthenticationCallback;
import com.android.email.provider.AccountBackupRestore;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.email.view.CertificateSelector;
import com.android.email.view.CertificateSelector.HostCallback;
import com.android.emailcommon.Device;
import com.android.emailcommon.VendorPolicyLoader;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Credential;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.CertificateRequestor;
import com.android.emailcommon.utility.Utility;
import com.android.mail.ui.MailAsyncTaskLoader;
import com.android.mail.utils.LogUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Provides UI for IMAP/POP account settings.
 *
 * This fragment is used by AccountSetupIncoming (for creating accounts) and by AccountSettingsXL
 * (for editing existing accounts).
 */
public class AccountSetupIncomingFragment extends AccountServerBaseFragment
        implements HostCallback, AuthenticationCallback {

    private static final int CERTIFICATE_REQUEST = 0;
    private static final int SIGN_IN_REQUEST = 1;

    private final static String STATE_KEY_CREDENTIAL = "AccountSetupIncomingFragment.credential";
    private final static String STATE_KEY_LOADED = "AccountSetupIncomingFragment.loaded";

    private EditText mUsernameView;
    private AuthenticationView mAuthenticationView;
    private TextView mAuthenticationLabel;
    private TextView mServerLabelView;
    private EditText mServerView;
    private EditText mPortView;
    private Spinner mSecurityTypeView;
    private TextView mDeletePolicyLabelView;
    private Spinner mDeletePolicyView;
    private CertificateSelector mClientCertificateSelector;
    private View mDeviceIdSection;
    private View mImapPathPrefixSectionView;
    private EditText mImapPathPrefixView;
    private boolean mOAuthProviderPresent;
    // Delete policy as loaded from the device
    private int mLoadedDeletePolicy;

    private TextWatcher mValidationTextWatcher;

    // Support for lifecycle
    private boolean mLoaded;
    private String mCacheLoginCredential;
    private EmailServiceInfo mServiceInfo;

    public static AccountSetupIncomingFragment newInstance(boolean settingsMode) {
        final AccountSetupIncomingFragment f = new AccountSetupIncomingFragment();
        f.setArguments(getArgs(settingsMode));
        return f;
    }

    // Public no-args constructor needed for fragment re-instantiation
    public AccountSetupIncomingFragment() {}

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mCacheLoginCredential = savedInstanceState.getString(STATE_KEY_CREDENTIAL);
            mLoaded = savedInstanceState.getBoolean(STATE_KEY_LOADED, false);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        final View view;
        if (mSettingsMode) {
            view = inflater.inflate(R.layout.account_settings_incoming_fragment, container, false);
        } else {
            view = inflateTemplatedView(inflater, container,
                    R.layout.account_setup_incoming_fragment,
                    R.string.account_setup_incoming_headline);
        }

        mUsernameView = UiUtilities.getView(view, R.id.account_username);
        mServerLabelView = UiUtilities.getView(view, R.id.account_server_label);
        mServerView = UiUtilities.getView(view, R.id.account_server);
        mPortView = UiUtilities.getView(view, R.id.account_port);
        mSecurityTypeView = UiUtilities.getView(view, R.id.account_security_type);
        mDeletePolicyLabelView = UiUtilities.getView(view, R.id.account_delete_policy_label);
        mDeletePolicyView = UiUtilities.getView(view, R.id.account_delete_policy);
        mImapPathPrefixSectionView = UiUtilities.getView(view, R.id.imap_path_prefix_section);
        mImapPathPrefixView = UiUtilities.getView(view, R.id.imap_path_prefix);
        mAuthenticationView = UiUtilities.getView(view, R.id.authentication_view);
        mClientCertificateSelector = UiUtilities.getView(view, R.id.client_certificate_selector);
        mDeviceIdSection = UiUtilities.getView(view, R.id.device_id_section);
        // Don't use UiUtilities here. In some configurations this view does not exist, and
        // UiUtilities throws an exception in this case.
        mAuthenticationLabel = (TextView)view.findViewById(R.id.authentication_label);

        // Updates the port when the user changes the security type. This allows
        // us to show a reasonable default which the user can change.
        mSecurityTypeView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
                updatePortFromSecurityType();
            }

            @Override
            public void onNothingSelected(AdapterView<?> arg0) { }
        });

        // After any text edits, call validateFields() which enables or disables the Next button
        mValidationTextWatcher = new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                validateFields();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
        };

        mUsernameView.addTextChangedListener(mValidationTextWatcher);
        mServerView.addTextChangedListener(mValidationTextWatcher);
        mPortView.addTextChangedListener(mValidationTextWatcher);

        // Only allow digits in the port field.
        mPortView.setKeyListener(DigitsKeyListener.getInstance("0123456789"));

        // Additional setup only used while in "settings" mode
        onCreateViewSettingsMode(view);

        mAuthenticationView.setAuthenticationCallback(this);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mClientCertificateSelector.setHostCallback(this);

        final Context context = getActivity();
        final SetupDataFragment.SetupDataContainer container =
                (SetupDataFragment.SetupDataContainer) context;
        mSetupData = container.getSetupData();
        final Account account = mSetupData.getAccount();
        final HostAuth recvAuth = account.getOrCreateHostAuthRecv(mAppContext);

        // Pre-fill info as appropriate
        if (!mSetupData.isIncomingCredLoaded()) {
            recvAuth.mLogin = mSetupData.getEmail();
            AccountSetupCredentialsFragment.populateHostAuthWithResults(context, recvAuth,
                    mSetupData.getCredentialResults());
            final String[] emailParts = mSetupData.getEmail().split("@");
            final String domain = emailParts[1];
            recvAuth.setConnection(recvAuth.mProtocol, domain, HostAuth.PORT_UNKNOWN,
                    HostAuth.FLAG_NONE);
            mSetupData.setIncomingCredLoaded(true);
        }

        mServiceInfo = mSetupData.getIncomingServiceInfo(context);

        if (mServiceInfo.offerLocalDeletes) {
            SpinnerOption deletePolicies[] = {
                    new SpinnerOption(Account.DELETE_POLICY_NEVER,
                            context.getString(
                                    R.string.account_setup_incoming_delete_policy_never_label)),
                    new SpinnerOption(Account.DELETE_POLICY_ON_DELETE,
                            context.getString(
                                    R.string.account_setup_incoming_delete_policy_delete_label)),
            };
            ArrayAdapter<SpinnerOption> deletePoliciesAdapter =
                    new ArrayAdapter<SpinnerOption>(context,
                            android.R.layout.simple_spinner_item, deletePolicies);
            deletePoliciesAdapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
            mDeletePolicyView.setAdapter(deletePoliciesAdapter);
        }

        // Set up security type spinner
        ArrayList<SpinnerOption> securityTypes = new ArrayList<SpinnerOption>();
        securityTypes.add(
                new SpinnerOption(HostAuth.FLAG_NONE, context.getString(
                        R.string.account_setup_incoming_security_none_label)));
        securityTypes.add(
                new SpinnerOption(HostAuth.FLAG_SSL, context.getString(
                        R.string.account_setup_incoming_security_ssl_label)));
        securityTypes.add(
                new SpinnerOption(HostAuth.FLAG_SSL | HostAuth.FLAG_TRUST_ALL, context.getString(
                        R.string.account_setup_incoming_security_ssl_trust_certificates_label)));
        if (mServiceInfo.offerTls) {
            securityTypes.add(
                    new SpinnerOption(HostAuth.FLAG_TLS, context.getString(
                            R.string.account_setup_incoming_security_tls_label)));
            securityTypes.add(new SpinnerOption(HostAuth.FLAG_TLS | HostAuth.FLAG_TRUST_ALL,
                    context.getString(R.string
                            .account_setup_incoming_security_tls_trust_certificates_label)));
        }
        ArrayAdapter<SpinnerOption> securityTypesAdapter = new ArrayAdapter<SpinnerOption>(
                context, android.R.layout.simple_spinner_item, securityTypes);
        securityTypesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSecurityTypeView.setAdapter(securityTypesAdapter);

        configureEditor();
        loadSettings();
    }

    /**
     * Called when the fragment is visible to the user and actively running.
     */
    @Override
    public void onResume() {
        super.onResume();
        validateFields();
    }

    @Override
    public void onDestroyView() {
        // Make sure we don't get callbacks after the views are supposed to be destroyed
        // and also don't hold onto them longer than we need
        if (mUsernameView != null) {
            mUsernameView.removeTextChangedListener(mValidationTextWatcher);
        }
        mUsernameView = null;
        mServerLabelView = null;
        if (mServerView != null) {
            mServerView.removeTextChangedListener(mValidationTextWatcher);
        }
        mServerView = null;
        if (mPortView != null) {
            mPortView.removeTextChangedListener(mValidationTextWatcher);
        }
        mPortView = null;
        if (mSecurityTypeView != null) {
            mSecurityTypeView.setOnItemSelectedListener(null);
        }
        mSecurityTypeView = null;
        mDeletePolicyLabelView = null;
        mDeletePolicyView = null;
        mImapPathPrefixSectionView = null;
        mImapPathPrefixView = null;
        mDeviceIdSection = null;
        mClientCertificateSelector = null;

        super.onDestroyView();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putString(STATE_KEY_CREDENTIAL, mCacheLoginCredential);
        outState.putBoolean(STATE_KEY_LOADED, mLoaded);
    }

    /**
     * Configure the editor for the account type
     */
    private void configureEditor() {
        final Account account = mSetupData.getAccount();
        if (account == null || account.mHostAuthRecv == null) {
            LogUtils.e(LogUtils.TAG,
                    "null account or host auth. account null: %b host auth null: %b",
                    account == null, account == null || account.mHostAuthRecv == null);
            return;
        }
        mBaseScheme = account.mHostAuthRecv.mProtocol;
        mServerLabelView.setText(R.string.account_setup_incoming_server_label);
        mServerView.setContentDescription(getResources().getText(
                R.string.account_setup_incoming_server_label));
        if (!mServiceInfo.offerPrefix) {
            mImapPathPrefixSectionView.setVisibility(View.GONE);
        }
        if (!mServiceInfo.offerLocalDeletes) {
            mDeletePolicyLabelView.setVisibility(View.GONE);
            mDeletePolicyView.setVisibility(View.GONE);
            mPortView.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        }
    }

    /**
     * Load the current settings into the UI
     */
    private void loadSettings() {
        if (mLoaded) return;

        final Account account = mSetupData.getAccount();
        final HostAuth recvAuth = account.getOrCreateHostAuthRecv(mAppContext);
        mServiceInfo = mSetupData.getIncomingServiceInfo(getActivity());
        final List<VendorPolicyLoader.OAuthProvider> oauthProviders =
                AccountSettingsUtils.getAllOAuthProviders(getActivity());
        final boolean offerOAuth = (mServiceInfo.offerOAuth && oauthProviders.size() > 0);

        mAuthenticationView.setAuthInfo(offerOAuth, recvAuth);
        if (mAuthenticationLabel != null) {
            if (offerOAuth) {
                mAuthenticationLabel.setText(R.string.authentication_label);
            } else {
                mAuthenticationLabel.setText(R.string.account_setup_basics_password_label);
            }
        }

        final String username = recvAuth.mLogin;
        if (username != null) {
            //*** For eas?
            // Add a backslash to the start of the username, but only if the username has no
            // backslash in it.
            //if (userName.indexOf('\\') < 0) {
            //    userName = "\\" + userName;
            //}
            mUsernameView.setText(username);
        }

        if (mServiceInfo.offerPrefix) {
            final String prefix = recvAuth.mDomain;
            if (prefix != null && prefix.length() > 0) {
                mImapPathPrefixView.setText(prefix.substring(1));
            }
        }

        // The delete policy is set for all legacy accounts. For POP3 accounts, the user sets
        // the policy explicitly. For IMAP accounts, the policy is set when the Account object
        // is created. @see AccountSetupBasics#populateSetupData
        mLoadedDeletePolicy = account.getDeletePolicy();
        SpinnerOption.setSpinnerOptionValue(mDeletePolicyView, mLoadedDeletePolicy);

        int flags = recvAuth.mFlags;
        if (mServiceInfo.defaultSsl) {
            flags |= HostAuth.FLAG_SSL;
        }
        // Strip out any flags that are not related to security type.
        int securityTypeFlags = (flags & HostAuth.FLAG_TRANSPORTSECURITY_MASK);
        SpinnerOption.setSpinnerOptionValue(mSecurityTypeView, securityTypeFlags);

        final String hostname = recvAuth.mAddress;
        if (hostname != null) {
            mServerView.setText(hostname);
        }

        final int port = recvAuth.mPort;
        if (port != HostAuth.PORT_UNKNOWN) {
            mPortView.setText(Integer.toString(port));
        } else {
            updatePortFromSecurityType();
        }

        if (!TextUtils.isEmpty(recvAuth.mClientCertAlias)) {
            mClientCertificateSelector.setCertificate(recvAuth.mClientCertAlias);
        }

        // Make a deep copy of the HostAuth to compare with later
        final Parcel parcel = Parcel.obtain();
        parcel.writeParcelable(recvAuth, recvAuth.describeContents());
        parcel.setDataPosition(0);
        mLoadedRecvAuth = parcel.readParcelable(HostAuth.class.getClassLoader());
        parcel.recycle();

        mLoaded = true;
        validateFields();
    }

    /**
     * Check the values in the fields and decide if it makes sense to enable the "next" button
     */
    private void validateFields() {
        if (!mLoaded) return;
        enableNextButton(!TextUtils.isEmpty(mUsernameView.getText())
                && mAuthenticationView.getAuthValid()
                && Utility.isServerNameValid(mServerView)
                && Utility.isPortFieldValid(mPortView));

        mCacheLoginCredential = mUsernameView.getText().toString().trim();
    }

    private int getPortFromSecurityType(boolean useSsl) {
        return useSsl ? mServiceInfo.portSsl : mServiceInfo.port;
    }

    private boolean getSslSelected() {
        final int securityType =
                (Integer)((SpinnerOption)mSecurityTypeView.getSelectedItem()).value;
        return ((securityType & HostAuth.FLAG_SSL) != 0);
    }

    public void onUseSslChanged(boolean useSsl) {
        if (mServiceInfo.offerCerts) {
            final int mode = useSsl ? View.VISIBLE : View.GONE;
            mClientCertificateSelector.setVisibility(mode);
            String deviceId = "";
            try {
                deviceId = Device.getDeviceId(mAppContext);
            } catch (IOException e) {
                // Not required
            }
            ((TextView) UiUtilities.getView(getView(), R.id.device_id)).setText(deviceId);

            mDeviceIdSection.setVisibility(mode);
        }
    }

    private void updatePortFromSecurityType() {
        final boolean sslSelected = getSslSelected();
        final int port = getPortFromSecurityType(sslSelected);
        mPortView.setText(Integer.toString(port));
        onUseSslChanged(sslSelected);
    }

    @Override
    public void saveSettings() {
        // Reset this here so we don't get stuck on this screen
        mLoadedDeletePolicy = mSetupData.getAccount().getDeletePolicy();
        super.saveSettings();
    }

    private static class SaveSettingsLoader extends MailAsyncTaskLoader<Boolean> {
        private final SetupDataFragment mSetupData;
        private final boolean mSettingsMode;

        private SaveSettingsLoader(Context context, SetupDataFragment setupData,
                boolean settingsMode) {
            super(context);
            mSetupData = setupData;
            mSettingsMode = settingsMode;
        }

        @Override
        public Boolean loadInBackground() {
            if (mSettingsMode) {
                saveSettingsAfterEdit(getContext(), mSetupData);
            } else {
                saveSettingsAfterSetup(getContext(), mSetupData);
            }
            return true;
        }

        @Override
        protected void onDiscardResult(Boolean result) {}
    }

    @Override
    public Loader<Boolean> getSaveSettingsLoader() {
        return new SaveSettingsLoader(mAppContext, mSetupData, mSettingsMode);
    }

    /**
     * Entry point from Activity after editing settings and verifying them.  Must be FLOW_MODE_EDIT.
     * Note, we update account here (as well as the account.mHostAuthRecv) because we edit
     * account's delete policy here.
     * Blocking - do not call from UI Thread.
     */
    public static void saveSettingsAfterEdit(Context context, SetupDataFragment setupData) {
        final Account account = setupData.getAccount();
        account.update(context, account.toContentValues());
        final Credential cred = account.mHostAuthRecv.mCredential;
        if (cred != null) {
            if (cred.isSaved()) {
                cred.update(context, cred.toContentValues());
            } else {
                cred.save(context);
                account.mHostAuthRecv.mCredentialKey = cred.mId;
            }
        }
        account.mHostAuthRecv.update(context, account.mHostAuthRecv.toContentValues());
        // Update the backup (side copy) of the accounts
        AccountBackupRestore.backup(context);
    }

    /**
     * Entry point from Activity after entering new settings and verifying them.  For setup mode.
     */
    public static void saveSettingsAfterSetup(Context context, SetupDataFragment setupData) {
        final Account account = setupData.getAccount();
        final HostAuth recvAuth = account.getOrCreateHostAuthRecv(context);
        final HostAuth sendAuth = account.getOrCreateHostAuthSend(context);

        // Set the username and password for the outgoing settings to the username and
        // password the user just set for incoming.  Use the verified host address to try and
        // pick a smarter outgoing address.
        final String hostName =
                AccountSettingsUtils.inferServerName(context, recvAuth.mAddress, null, "smtp");
        sendAuth.setLogin(recvAuth.mLogin, recvAuth.mPassword);
        sendAuth.setConnection(sendAuth.mProtocol, hostName, sendAuth.mPort, sendAuth.mFlags);
    }

    /**
     * Entry point from Activity, when "next" button is clicked
     */
    @Override
    public int collectUserInputInternal() {
        final Account account = mSetupData.getAccount();

        // Make sure delete policy is an valid option before using it; otherwise, the results are
        // indeterminate, I suspect...
        if (mDeletePolicyView.getVisibility() == View.VISIBLE) {
            account.setDeletePolicy(
                    (Integer) ((SpinnerOption) mDeletePolicyView.getSelectedItem()).value);
        }

        final HostAuth recvAuth = account.getOrCreateHostAuthRecv(mAppContext);
        final String userName = mUsernameView.getText().toString().trim();
        final String userPassword = mAuthenticationView.getPassword();
        recvAuth.setLogin(userName, userPassword);
        if (!TextUtils.isEmpty(mAuthenticationView.getOAuthProvider())) {
            Credential cred = recvAuth.getOrCreateCredential(getActivity());
            cred.mProviderId = mAuthenticationView.getOAuthProvider();
        }

        final String serverAddress = mServerView.getText().toString().trim();
        int serverPort;
        try {
            serverPort = Integer.parseInt(mPortView.getText().toString().trim());
        } catch (NumberFormatException e) {
            serverPort = getPortFromSecurityType(getSslSelected());
            LogUtils.d(LogUtils.TAG, "Non-integer server port; using '" + serverPort + "'");
        }
        final int securityType =
                (Integer) ((SpinnerOption) mSecurityTypeView.getSelectedItem()).value;
        recvAuth.setConnection(mBaseScheme, serverAddress, serverPort, securityType);
        if (mServiceInfo.offerPrefix) {
            final String prefix = mImapPathPrefixView.getText().toString().trim();
            recvAuth.mDomain = TextUtils.isEmpty(prefix) ? null : ("/" + prefix);
        } else {
            recvAuth.mDomain = null;
        }
        recvAuth.mClientCertAlias = mClientCertificateSelector.getCertificate();

        return SetupDataFragment.CHECK_INCOMING;
    }

    @Override
    public boolean haveSettingsChanged() {
        final boolean deletePolicyChanged;

        // Only verify the delete policy if the control is visible (i.e. is a pop3 account)
        if (mDeletePolicyView != null && mDeletePolicyView.getVisibility() == View.VISIBLE) {
            int newDeletePolicy =
                (Integer)((SpinnerOption)mDeletePolicyView.getSelectedItem()).value;
            deletePolicyChanged = mLoadedDeletePolicy != newDeletePolicy;
        } else {
            deletePolicyChanged = false;
        }

        return deletePolicyChanged || super.haveSettingsChanged();
    }

    @Override
    public void onValidateStateChanged() {
        validateFields();
    }

    @Override
    public void onRequestSignIn() {
        // Launch the credentials activity.
        final String protocol =
                mSetupData.getAccount().getOrCreateHostAuthRecv(mAppContext).mProtocol;
        final Intent intent = AccountCredentials.getAccountCredentialsIntent(getActivity(),
                mUsernameView.getText().toString(), protocol);
        startActivityForResult(intent, SIGN_IN_REQUEST);
    }

    @Override
    public void onCertificateRequested() {
        final Intent intent = new Intent(getString(R.string.intent_exchange_cert_action));
        intent.setData(CertificateRequestor.CERTIFICATE_REQUEST_URI);
        intent.putExtra(CertificateRequestor.EXTRA_HOST, mServerView.getText().toString().trim());
        try {
            intent.putExtra(CertificateRequestor.EXTRA_PORT,
                    Integer.parseInt(mPortView.getText().toString().trim()));
        } catch (final NumberFormatException e) {
            LogUtils.d(LogUtils.TAG, "Couldn't parse port %s", mPortView.getText());
        }
        startActivityForResult(intent, CERTIFICATE_REQUEST);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CERTIFICATE_REQUEST && resultCode == Activity.RESULT_OK) {
            final String certAlias = data.getStringExtra(CertificateRequestor.RESULT_ALIAS);
            if (certAlias != null) {
                mClientCertificateSelector.setCertificate(certAlias);
            }
        } else if (requestCode == SIGN_IN_REQUEST && resultCode == Activity.RESULT_OK) {
            final Account account = mSetupData.getAccount();
            final HostAuth recvAuth = account.getOrCreateHostAuthRecv(getActivity());
            AccountSetupCredentialsFragment.populateHostAuthWithResults(mAppContext, recvAuth,
                    data.getExtras());
            mAuthenticationView.setAuthInfo(mServiceInfo.offerOAuth, recvAuth);
        }
    }
}
