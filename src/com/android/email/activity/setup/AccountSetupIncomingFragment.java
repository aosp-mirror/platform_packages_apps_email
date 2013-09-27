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
import com.android.email.provider.AccountBackupRestore;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.email.view.CertificateSelector;
import com.android.email.view.CertificateSelector.HostCallback;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Device;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.CertificateRequestor;
import com.android.emailcommon.utility.Utility;
import com.android.mail.utils.LogUtils;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Provides UI for IMAP/POP account settings.
 *
 * This fragment is used by AccountSetupIncoming (for creating accounts) and by AccountSettingsXL
 * (for editing existing accounts).
 */
public class AccountSetupIncomingFragment extends AccountServerBaseFragment
        implements HostCallback {

    private static final int CERTIFICATE_REQUEST = 0;
    private final static String STATE_KEY_CREDENTIAL = "AccountSetupIncomingFragment.credential";
    private final static String STATE_KEY_LOADED = "AccountSetupIncomingFragment.loaded";

    private EditText mUsernameView;
    private EditText mPasswordView;
    private TextView mServerLabelView;
    private EditText mServerView;
    private EditText mPortView;
    private Spinner mSecurityTypeView;
    private TextView mDeletePolicyLabelView;
    private Spinner mDeletePolicyView;
    private View mImapPathPrefixSectionView;
    private View mDeviceIdSectionView;
    private EditText mImapPathPrefixView;
    private CertificateSelector mClientCertificateSelector;
    // Delete policy as loaded from the device
    private int mLoadedDeletePolicy;

    private TextWatcher mValidationTextWatcher;

    // Support for lifecycle
    private boolean mStarted;
    private boolean mLoaded;
    private String mCacheLoginCredential;
    private EmailServiceInfo mServiceInfo;

    // Public no-args constructor needed for fragment re-instantiation
    public AccountSetupIncomingFragment() {}

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mCacheLoginCredential = savedInstanceState.getString(STATE_KEY_CREDENTIAL);
            mLoaded = savedInstanceState.getBoolean(STATE_KEY_LOADED, false);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onCreateView");
        }
        final int layoutId = mSettingsMode
                ? R.layout.account_settings_incoming_fragment
                : R.layout.account_setup_incoming_fragment;

        final View view = inflater.inflate(layoutId, container, false);

        mUsernameView = UiUtilities.getView(view, R.id.account_username);
        mPasswordView = UiUtilities.getView(view, R.id.account_password);
        mServerLabelView = UiUtilities.getView(view, R.id.account_server_label);
        mServerView = UiUtilities.getView(view, R.id.account_server);
        mPortView = UiUtilities.getView(view, R.id.account_port);
        mSecurityTypeView = UiUtilities.getView(view, R.id.account_security_type);
        mDeletePolicyLabelView = UiUtilities.getView(view, R.id.account_delete_policy_label);
        mDeletePolicyView = UiUtilities.getView(view, R.id.account_delete_policy);
        mImapPathPrefixSectionView = UiUtilities.getView(view, R.id.imap_path_prefix_section);
        mDeviceIdSectionView = UiUtilities.getView(view, R.id.device_id_section);
        mImapPathPrefixView = UiUtilities.getView(view, R.id.imap_path_prefix);
        mClientCertificateSelector = UiUtilities.getView(view, R.id.client_certificate_selector);

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
        // We're editing an existing account; don't allow modification of the user name
        if (mSettingsMode) {
            makeTextViewUneditable(mUsernameView,
                    getString(R.string.account_setup_username_uneditable_error));
        }
        mUsernameView.addTextChangedListener(mValidationTextWatcher);
        mPasswordView.addTextChangedListener(mValidationTextWatcher);
        mServerView.addTextChangedListener(mValidationTextWatcher);
        mPortView.addTextChangedListener(mValidationTextWatcher);

        // Only allow digits in the port field.
        mPortView.setKeyListener(DigitsKeyListener.getInstance("0123456789"));

        // Additional setup only used while in "settings" mode
        onCreateViewSettingsMode(view);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);
        mClientCertificateSelector.setHostActivity(this);

        final Context context = getActivity();
        final SetupData.SetupDataContainer container = (SetupData.SetupDataContainer) context;
        mSetupData = container.getSetupData();

        final HostAuth recvAuth = mSetupData.getAccount().mHostAuthRecv;
        mServiceInfo = EmailServiceUtils.getServiceInfo(mContext, recvAuth.mProtocol);

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
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onStart");
        }
        super.onStart();
        mStarted = true;
        configureEditor();
        loadSettings();
    }

    /**
     * Called when the fragment is visible to the user and actively running.
     */
    @Override
    public void onResume() {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onResume");
        }
        super.onResume();
        validateFields();
    }

    @Override
    public void onPause() {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onPause");
        }
        super.onPause();
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onStop");
        }
        super.onStop();
        mStarted = false;
    }

    @Override
    public void onDestroyView() {
        // Make sure we don't get callbacks after the views are supposed to be destroyed
        // and also don't hold onto them longer than we need
        if (mUsernameView != null) {
            mUsernameView.removeTextChangedListener(mValidationTextWatcher);
        }
        mUsernameView = null;
        if (mPasswordView != null) {
            mPasswordView.removeTextChangedListener(mValidationTextWatcher);
        }
        mPasswordView = null;
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
        mDeviceIdSectionView = null;
        mImapPathPrefixView = null;
        mClientCertificateSelector = null;

        super.onDestroyView();
    }

    /**
     * Called when the fragment is no longer in use.
     */
    @Override
    public void onDestroy() {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onDestroy");
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onSaveInstanceState");
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
            configureEditor();
            loadSettings();
        }
    }

    /**
     * Configure the editor for the account type
     */
    private void configureEditor() {
        final Account account = mSetupData.getAccount();
        if (account == null || account.mHostAuthRecv == null) {
            LogUtils.e(Logging.LOG_TAG,
                    "null account or host auth. account null: %b host auth null: %b",
                    account == null, account == null || account.mHostAuthRecv == null);
            return;
        }
        TextView lastView = mImapPathPrefixView;
        mBaseScheme = account.mHostAuthRecv.mProtocol;
        mServerLabelView.setText(R.string.account_setup_incoming_server_label);
        mServerView.setContentDescription(getResources().getText(
                R.string.account_setup_incoming_server_label));
        if (!mServiceInfo.offerPrefix) {
            mImapPathPrefixSectionView.setVisibility(View.GONE);
            lastView = mPortView;
        }
        if (!mServiceInfo.offerLocalDeletes) {
            mDeletePolicyLabelView.setVisibility(View.GONE);
            mDeletePolicyView.setVisibility(View.GONE);
            mPortView.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        }
        lastView.setOnEditorActionListener(mDismissImeOnDoneListener);
    }

    /**
     * Load the current settings into the UI
     */
    private void loadSettings() {
        if (mLoaded) return;

        final Account account = mSetupData.getAccount();
        final HostAuth recvAuth = account.getOrCreateHostAuthRecv(mContext);

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
        final String password = recvAuth.mPassword;
        if (password != null) {
            mPasswordView.setText(password);
            // Since username is uneditable, focus on the next editable field
            if (mSettingsMode) {
                mPasswordView.requestFocus();
            }
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
        flags &= ~HostAuth.FLAG_AUTHENTICATE;
        if (mServiceInfo.defaultSsl) {
            flags |= HostAuth.FLAG_SSL;
        }
        SpinnerOption.setSpinnerOptionValue(mSecurityTypeView, flags);

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

        mLoadedRecvAuth = recvAuth;
        mLoaded = true;
        validateFields();
    }

    /**
     * Check the values in the fields and decide if it makes sense to enable the "next" button
     */
    private void validateFields() {
        if (!mLoaded) return;
        enableNextButton(!TextUtils.isEmpty(mUsernameView.getText())
                && !TextUtils.isEmpty(mPasswordView.getText())
                && Utility.isServerNameValid(mServerView)
                && Utility.isPortFieldValid(mPortView));

        mCacheLoginCredential = mUsernameView.getText().toString().trim();

        // Warn (but don't prevent) if password has leading/trailing spaces
        AccountSettingsUtils.checkPasswordSpaces(mContext, mPasswordView);
    }

    private int getPortFromSecurityType(boolean useSsl) {
        final EmailServiceInfo info = EmailServiceUtils.getServiceInfo(mContext,
                mSetupData.getAccount().mHostAuthRecv.mProtocol);
        return useSsl ? info.portSsl : info.port;
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
                deviceId = Device.getDeviceId(mContext);
            } catch (IOException e) {
                // Not required
            }
            ((TextView) UiUtilities.getView(getView(), R.id.device_id)).setText(deviceId);

            mDeviceIdSectionView.setVisibility(mode);
            //UiUtilities.setVisibilitySafe(getView(), R.id.client_certificate_divider, mode);
        }
    }

    private void updatePortFromSecurityType() {
        final boolean sslSelected = getSslSelected();
        final int port = getPortFromSecurityType(sslSelected);
        mPortView.setText(Integer.toString(port));
        onUseSslChanged(sslSelected);
    }

    /**
     * Entry point from Activity after editing settings and verifying them.  Must be FLOW_MODE_EDIT.
     * Note, we update account here (as well as the account.mHostAuthRecv) because we edit
     * account's delete policy here.
     * Blocking - do not call from UI Thread.
     */
    @Override
    public void saveSettingsAfterEdit() {
        final Account account = mSetupData.getAccount();
        account.update(mContext, account.toContentValues());
        account.mHostAuthRecv.update(mContext, account.mHostAuthRecv.toContentValues());
        // Update the backup (side copy) of the accounts
        AccountBackupRestore.backup(mContext);
    }

    /**
     * Entry point from Activity after entering new settings and verifying them.  For setup mode.
     */
    @Override
    public void saveSettingsAfterSetup() {
        final Account account = mSetupData.getAccount();
        final HostAuth recvAuth = account.getOrCreateHostAuthRecv(mContext);
        final HostAuth sendAuth = account.getOrCreateHostAuthSend(mContext);

        // Set the username and password for the outgoing settings to the username and
        // password the user just set for incoming.  Use the verified host address to try and
        // pick a smarter outgoing address.
        final String hostName =
                AccountSettingsUtils.inferServerName(mContext, recvAuth.mAddress, null, "smtp");
        sendAuth.setLogin(recvAuth.mLogin, recvAuth.mPassword);
        sendAuth.setConnection(sendAuth.mProtocol, hostName, sendAuth.mPort, sendAuth.mFlags);
    }

    /**
     * Entry point from Activity, when "next" button is clicked
     */
    @Override
    public void onNext() {
        final Account account = mSetupData.getAccount();

        // Make sure delete policy is an valid option before using it; otherwise, the results are
        // indeterminate, I suspect...
        if (mDeletePolicyView.getVisibility() == View.VISIBLE) {
            account.setDeletePolicy(
                    (Integer) ((SpinnerOption) mDeletePolicyView.getSelectedItem()).value);
        }

        final HostAuth recvAuth = account.getOrCreateHostAuthRecv(mContext);
        final String userName = mUsernameView.getText().toString().trim();
        final String userPassword = mPasswordView.getText().toString();
        recvAuth.setLogin(userName, userPassword);

        final String serverAddress = mServerView.getText().toString().trim();
        int serverPort;
        try {
            serverPort = Integer.parseInt(mPortView.getText().toString().trim());
        } catch (NumberFormatException e) {
            serverPort = getPortFromSecurityType(getSslSelected());
            LogUtils.d(Logging.LOG_TAG, "Non-integer server port; using '" + serverPort + "'");
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

        mCallback.onProceedNext(SetupData.CHECK_INCOMING, this);
        clearButtonBounce();
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

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     */
    @Override
    public void onAutoDiscoverComplete(int result, SetupData setupData) {
        mSetupData = setupData;
        final AccountSetupIncoming activity = (AccountSetupIncoming) getActivity();
        activity.onAutoDiscoverComplete(result, setupData);
    }

    @Override
    public void onCertificateRequested() {
        final Intent intent = new Intent(CertificateRequestor.ACTION_REQUEST_CERT);
        intent.setData(Uri.parse("eas://com.android.emailcommon/certrequest"));
        startActivityForResult(intent, CERTIFICATE_REQUEST);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == CERTIFICATE_REQUEST && resultCode == Activity.RESULT_OK) {
            final String certAlias = data.getStringExtra(CertificateRequestor.RESULT_ALIAS);
            if (certAlias != null) {
                mClientCertificateSelector.setCertificate(certAlias);
            }
        }
    }
}
