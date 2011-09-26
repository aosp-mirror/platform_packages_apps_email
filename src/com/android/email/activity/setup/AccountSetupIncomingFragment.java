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
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.android.email.Email;
import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.provider.AccountBackupRestore;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.Utility;

/**
 * Provides UI for IMAP/POP account settings.
 *
 * This fragment is used by AccountSetupIncoming (for creating accounts) and by AccountSettingsXL
 * (for editing existing accounts).
 */
public class AccountSetupIncomingFragment extends AccountServerBaseFragment {

    private final static String STATE_KEY_CREDENTIAL = "AccountSetupIncomingFragment.credential";
    private final static String STATE_KEY_LOADED = "AccountSetupIncomingFragment.loaded";

    private static final int POP3_PORT_NORMAL = 110;
    private static final int POP3_PORT_SSL = 995;

    private static final int IMAP_PORT_NORMAL = 143;
    private static final int IMAP_PORT_SSL = 993;

    private EditText mUsernameView;
    private EditText mPasswordView;
    private TextView mServerLabelView;
    private EditText mServerView;
    private EditText mPortView;
    private Spinner mSecurityTypeView;
    private TextView mDeletePolicyLabelView;
    private Spinner mDeletePolicyView;
    private View mImapPathPrefixSectionView;
    private EditText mImapPathPrefixView;
    // Delete policy as loaded from the device
    private int mLoadedDeletePolicy;

    // Support for lifecycle
    private boolean mStarted;
    private boolean mConfigured;
    private boolean mLoaded;
    private String mCacheLoginCredential;

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onCreate");
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
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onCreateView");
        }
        int layoutId = mSettingsMode
                ? R.layout.account_settings_incoming_fragment
                : R.layout.account_setup_incoming_fragment;

        View view = inflater.inflate(layoutId, container, false);
        Context context = getActivity();

        mUsernameView = (EditText) UiUtilities.getView(view, R.id.account_username);
        mPasswordView = (EditText) UiUtilities.getView(view, R.id.account_password);
        mServerLabelView = (TextView) UiUtilities.getView(view, R.id.account_server_label);
        mServerView = (EditText) UiUtilities.getView(view, R.id.account_server);
        mPortView = (EditText) UiUtilities.getView(view, R.id.account_port);
        mSecurityTypeView = (Spinner) UiUtilities.getView(view, R.id.account_security_type);
        mDeletePolicyLabelView = (TextView) UiUtilities.getView(view,
                R.id.account_delete_policy_label);
        mDeletePolicyView = (Spinner) UiUtilities.getView(view, R.id.account_delete_policy);
        mImapPathPrefixSectionView = UiUtilities.getView(view, R.id.imap_path_prefix_section);
        mImapPathPrefixView = (EditText) UiUtilities.getView(view, R.id.imap_path_prefix);

        // Set up spinners
        SpinnerOption securityTypes[] = {
            new SpinnerOption(HostAuth.FLAG_NONE, context.getString(
                    R.string.account_setup_incoming_security_none_label)),
            new SpinnerOption(HostAuth.FLAG_SSL, context.getString(
                    R.string.account_setup_incoming_security_ssl_label)),
            new SpinnerOption(HostAuth.FLAG_SSL | HostAuth.FLAG_TRUST_ALL, context.getString(
                    R.string.account_setup_incoming_security_ssl_trust_certificates_label)),
            new SpinnerOption(HostAuth.FLAG_TLS, context.getString(
                    R.string.account_setup_incoming_security_tls_label)),
            new SpinnerOption(HostAuth.FLAG_TLS | HostAuth.FLAG_TRUST_ALL, context.getString(
                    R.string.account_setup_incoming_security_tls_trust_certificates_label)),
        };

        SpinnerOption deletePolicies[] = {
            new SpinnerOption(Account.DELETE_POLICY_NEVER,
                    context.getString(R.string.account_setup_incoming_delete_policy_never_label)),
            new SpinnerOption(Account.DELETE_POLICY_ON_DELETE,
                    context.getString(R.string.account_setup_incoming_delete_policy_delete_label)),
        };

        ArrayAdapter<SpinnerOption> securityTypesAdapter = new ArrayAdapter<SpinnerOption>(context,
                android.R.layout.simple_spinner_item, securityTypes);
        securityTypesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSecurityTypeView.setAdapter(securityTypesAdapter);

        ArrayAdapter<SpinnerOption> deletePoliciesAdapter = new ArrayAdapter<SpinnerOption>(context,
                android.R.layout.simple_spinner_item, deletePolicies);
        deletePoliciesAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        mDeletePolicyView.setAdapter(deletePoliciesAdapter);

        // Updates the port when the user changes the security type. This allows
        // us to show a reasonable default which the user can change.
        mSecurityTypeView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
                updatePortFromSecurityType();
            }

            public void onNothingSelected(AdapterView<?> arg0) { }
        });

        // After any text edits, call validateFields() which enables or disables the Next button
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
        mPortView.addTextChangedListener(validationTextWatcher);

        // Only allow digits in the port field.
        mPortView.setKeyListener(DigitsKeyListener.getInstance("0123456789"));

        // Additional setup only used while in "settings" mode
        onCreateViewSettingsMode(view);

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onStart");
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
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onResume");
        }
        super.onResume();
        validateFields();
    }

    @Override
    public void onPause() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onPause");
        }
        super.onPause();
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onStop");
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
            Log.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onDestroy");
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupIncomingFragment onSaveInstanceState");
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
        if (mConfigured) return;
        Account account = SetupData.getAccount();
        TextView lastView = mImapPathPrefixView;
        mBaseScheme = account.mHostAuthRecv.mProtocol;
        if (HostAuth.SCHEME_POP3.equals(mBaseScheme)) {
            mServerLabelView.setText(R.string.account_setup_incoming_pop_server_label);
            mServerView.setContentDescription(
                    getResources().getString(R.string.account_setup_incoming_pop_server_label));
            mImapPathPrefixSectionView.setVisibility(View.GONE);
            lastView = mPortView;
        } else if (HostAuth.SCHEME_IMAP.equals(mBaseScheme)) {
            mServerLabelView.setText(R.string.account_setup_incoming_imap_server_label);
            mServerView.setContentDescription(
                    getResources().getString(R.string.account_setup_incoming_imap_server_label));
            mDeletePolicyLabelView.setVisibility(View.GONE);
            mDeletePolicyView.setVisibility(View.GONE);
            mPortView.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        } else {
            throw new Error("Unknown account type: " + account);
        }
        lastView.setOnEditorActionListener(mDismissImeOnDoneListener);
        mConfigured = true;
    }

    /**
     * Load the current settings into the UI
     */
    private void loadSettings() {
        if (mLoaded) return;

        Account account = SetupData.getAccount();
        HostAuth recvAuth = account.getOrCreateHostAuthRecv(mContext);

        String username = recvAuth.mLogin;
        if (username != null) {
            mUsernameView.setText(username);
        }
        String password = recvAuth.mPassword;
        if (password != null) {
            mPasswordView.setText(password);
            // Since username is uneditable, focus on the next editable field
            if (mSettingsMode) {
                mPasswordView.requestFocus();
            }
        }

        if (HostAuth.SCHEME_IMAP.equals(recvAuth.mProtocol)) {
            String prefix = recvAuth.mDomain;
            if (prefix != null && prefix.length() > 0) {
                mImapPathPrefixView.setText(prefix.substring(1));
            }
        } else if (!HostAuth.SCHEME_POP3.equals(recvAuth.mProtocol)) {
            // Account must either be IMAP or POP3
            throw new Error("Unknown account type: " + recvAuth.mProtocol);
        }

        // The delete policy is set for all legacy accounts. For POP3 accounts, the user sets
        // the policy explicitly. For IMAP accounts, the policy is set when the Account object
        // is created. @see AccountSetupBasics#populateSetupData
        mLoadedDeletePolicy = account.getDeletePolicy();
        SpinnerOption.setSpinnerOptionValue(mDeletePolicyView, mLoadedDeletePolicy);

        int flags = recvAuth.mFlags;
        flags &= ~HostAuth.FLAG_AUTHENTICATE;
        SpinnerOption.setSpinnerOptionValue(mSecurityTypeView, flags);

        String hostname = recvAuth.mAddress;
        if (hostname != null) {
            mServerView.setText(hostname);
        }

        int port = recvAuth.mPort;
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
        if (!mConfigured || !mLoaded) return;
        boolean enabled = Utility.isTextViewNotEmpty(mUsernameView)
                && Utility.isTextViewNotEmpty(mPasswordView)
                && Utility.isServerNameValid(mServerView)
                && Utility.isPortFieldValid(mPortView);
        enableNextButton(enabled);

        String userName = mUsernameView.getText().toString().trim();
        mCacheLoginCredential = userName;

        // Warn (but don't prevent) if password has leading/trailing spaces
        AccountSettingsUtils.checkPasswordSpaces(mContext, mPasswordView);
    }

    private int getPortFromSecurityType() {
        int securityType = (Integer)((SpinnerOption)mSecurityTypeView.getSelectedItem()).value;
        boolean useSsl = ((securityType & HostAuth.FLAG_SSL) != 0);
        int port = useSsl ? IMAP_PORT_SSL : IMAP_PORT_NORMAL;     // default to IMAP
        if (HostAuth.SCHEME_POP3.equals(mBaseScheme)) {
            port = useSsl ? POP3_PORT_SSL : POP3_PORT_NORMAL;
        }
        return port;
    }

    private void updatePortFromSecurityType() {
        int port = getPortFromSecurityType();
        mPortView.setText(Integer.toString(port));
    }

    /**
     * Entry point from Activity after editing settings and verifying them.  Must be FLOW_MODE_EDIT.
     * Note, we update account here (as well as the account.mHostAuthRecv) because we edit
     * account's delete policy here.
     * Blocking - do not call from UI Thread.
     */
    @Override
    public void saveSettingsAfterEdit() {
        Account account = SetupData.getAccount();
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
        Account account = SetupData.getAccount();
        HostAuth recvAuth = account.getOrCreateHostAuthRecv(mContext);
        HostAuth sendAuth = account.getOrCreateHostAuthSend(mContext);

        // Set the username and password for the outgoing settings to the username and
        // password the user just set for incoming.  Use the verified host address to try and
        // pick a smarter outgoing address.
        String hostName = AccountSettingsUtils.inferServerName(recvAuth.mAddress, null, "smtp");
        sendAuth.setLogin(recvAuth.mLogin, recvAuth.mPassword);
        sendAuth.setConnection(sendAuth.mProtocol, hostName, sendAuth.mPort, sendAuth.mFlags);
    }

    /**
     * Entry point from Activity, when "next" button is clicked
     */
    @Override
    public void onNext() {
        Account account = SetupData.getAccount();

        // Make sure delete policy is an valid option before using it; otherwise, the results are
        // indeterminate, I suspect...
        if (mDeletePolicyView.getVisibility() == View.VISIBLE) {
            account.setDeletePolicy(
                    (Integer) ((SpinnerOption) mDeletePolicyView.getSelectedItem()).value);
        }

        HostAuth recvAuth = account.getOrCreateHostAuthRecv(mContext);
        String userName = mUsernameView.getText().toString().trim();
        String userPassword = mPasswordView.getText().toString();
        recvAuth.setLogin(userName, userPassword);

        String serverAddress = mServerView.getText().toString().trim();
        int serverPort;
        try {
            serverPort = Integer.parseInt(mPortView.getText().toString().trim());
        } catch (NumberFormatException e) {
            serverPort = getPortFromSecurityType();
            Log.d(Logging.LOG_TAG, "Non-integer server port; using '" + serverPort + "'");
        }
        int securityType = (Integer) ((SpinnerOption) mSecurityTypeView.getSelectedItem()).value;
        recvAuth.setConnection(mBaseScheme, serverAddress, serverPort, securityType);
        if (HostAuth.SCHEME_IMAP.equals(recvAuth.mProtocol)) {
            String prefix = mImapPathPrefixView.getText().toString().trim();
            recvAuth.mDomain = TextUtils.isEmpty(prefix) ? null : ("/" + prefix);
        } else {
            recvAuth.mDomain = null;
        }

        // Check for a duplicate account (requires async DB work) and if OK,
        // proceed with check
        startDuplicateTaskCheck(
                account.mId, serverAddress, mCacheLoginCredential, SetupData.CHECK_INCOMING);
    }

    @Override
    public boolean haveSettingsChanged() {
        boolean deletePolicyChanged = false;

        // Only verify the delete policy if the control is visible (i.e. is a pop3 account)
        if (mDeletePolicyView.getVisibility() == View.VISIBLE) {
            int newDeletePolicy =
                (Integer)((SpinnerOption)mDeletePolicyView.getSelectedItem()).value;
            deletePolicyChanged = mLoadedDeletePolicy != newDeletePolicy;
        }

        return deletePolicyChanged || super.haveSettingsChanged();
    }
}
