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
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.EditText;
import android.widget.Spinner;

import com.android.email.Email;
import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.provider.AccountBackupRestore;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.Utility;

/**
 * Provides UI for SMTP account settings (for IMAP/POP accounts).
 *
 * This fragment is used by AccountSetupOutgoing (for creating accounts) and by AccountSettingsXL
 * (for editing existing accounts).
 */
public class AccountSetupOutgoingFragment extends AccountServerBaseFragment
        implements OnCheckedChangeListener {

    private final static String STATE_KEY_LOADED = "AccountSetupOutgoingFragment.loaded";

    private static final int SMTP_PORT_NORMAL = 587;
    private static final int SMTP_PORT_SSL    = 465;

    private EditText mUsernameView;
    private EditText mPasswordView;
    private EditText mServerView;
    private EditText mPortView;
    private CheckBox mRequireLoginView;
    private Spinner mSecurityTypeView;

    // Support for lifecycle
    private boolean mStarted;
    private boolean mLoaded;

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupOutgoingFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mLoaded = savedInstanceState.getBoolean(STATE_KEY_LOADED, false);
        }
        mBaseScheme = HostAuth.SCHEME_SMTP;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupOutgoingFragment onCreateView");
        }
        int layoutId = mSettingsMode
                ? R.layout.account_settings_outgoing_fragment
                : R.layout.account_setup_outgoing_fragment;

        View view = inflater.inflate(layoutId, container, false);
        Context context = getActivity();

        mUsernameView = (EditText) UiUtilities.getView(view, R.id.account_username);
        mPasswordView = (EditText) UiUtilities.getView(view, R.id.account_password);
        mServerView = (EditText) UiUtilities.getView(view, R.id.account_server);
        mPortView = (EditText) UiUtilities.getView(view, R.id.account_port);
        mRequireLoginView = (CheckBox) UiUtilities.getView(view, R.id.account_require_login);
        mSecurityTypeView = (Spinner) UiUtilities.getView(view, R.id.account_security_type);
        mRequireLoginView.setOnCheckedChangeListener(this);

        // Note:  Strings are shared with AccountSetupIncomingFragment
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

        ArrayAdapter<SpinnerOption> securityTypesAdapter = new ArrayAdapter<SpinnerOption>(context,
                android.R.layout.simple_spinner_item, securityTypes);
        securityTypesAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSecurityTypeView.setAdapter(securityTypesAdapter);

        // Updates the port when the user changes the security type. This allows
        // us to show a reasonable default which the user can change.
        mSecurityTypeView.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> arg0, View arg1, int arg2, long arg3) {
                updatePortFromSecurityType();
            }

            public void onNothingSelected(AdapterView<?> arg0) { }
        });

        // Calls validateFields() which enables or disables the Next button
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
            Log.d(Logging.LOG_TAG, "AccountSetupOutgoingFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupOutgoingFragment onStart");
        }
        super.onStart();
        mStarted = true;
        loadSettings();
    }

    /**
     * Called when the fragment is visible to the user and actively running.
     */
    @Override
    public void onResume() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupOutgoingFragment onResume");
        }
        super.onResume();
        validateFields();
    }

    @Override
    public void onPause() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupOutgoingFragment onPause");
        }
        super.onPause();
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupOutgoingFragment onStop");
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
            Log.d(Logging.LOG_TAG, "AccountSetupOutgoingFragment onDestroy");
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Logging.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSetupOutgoingFragment onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);

        outState.putBoolean(STATE_KEY_LOADED, mLoaded);
    }

    /**
     * Activity provides callbacks here.  This also triggers loading and setting up the UX
     */
    @Override
    public void setCallback(Callback callback) {
        super.setCallback(callback);
        if (mStarted) {
            loadSettings();
        }
    }

    /**
     * Load the current settings into the UI
     */
    private void loadSettings() {
        if (mLoaded) return;

        HostAuth sendAuth = SetupData.getAccount().getOrCreateHostAuthSend(mContext);
        if ((sendAuth.mFlags & HostAuth.FLAG_AUTHENTICATE) != 0) {
            String username = sendAuth.mLogin;
            if (username != null) {
                mUsernameView.setText(username);
                mRequireLoginView.setChecked(true);
            }

            String password = sendAuth.mPassword;
            if (password != null) {
                mPasswordView.setText(password);
            }
        }

        int flags = sendAuth.mFlags & ~HostAuth.FLAG_AUTHENTICATE;
        SpinnerOption.setSpinnerOptionValue(mSecurityTypeView, flags);

        String hostname = sendAuth.mAddress;
        if (hostname != null) {
            mServerView.setText(hostname);
        }

        int port = sendAuth.mPort;
        if (port != -1) {
            mPortView.setText(Integer.toString(port));
        } else {
            updatePortFromSecurityType();
        }

        mLoadedSendAuth = sendAuth;
        mLoaded = true;
        validateFields();
    }

    /**
     * Preflight the values in the fields and decide if it makes sense to enable the "next" button
     */
    private void validateFields() {
        if (!mLoaded) return;
        boolean enabled =
            Utility.isServerNameValid(mServerView) && Utility.isPortFieldValid(mPortView);

        if (enabled && mRequireLoginView.isChecked()) {
            enabled = (Utility.isTextViewNotEmpty(mUsernameView)
                    && Utility.isTextViewNotEmpty(mPasswordView));
        }
        enableNextButton(enabled);
        // Warn (but don't prevent) if password has leading/trailing spaces
        AccountSettingsUtils.checkPasswordSpaces(mContext, mPasswordView);
   }

    /**
     * implements OnCheckedChangeListener
     */
    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        final int visibility = isChecked ? View.VISIBLE : View.GONE;
        UiUtilities.setVisibilitySafe(getView(), R.id.account_require_login_settings, visibility);
        UiUtilities.setVisibilitySafe(getView(), R.id.account_require_login_settings_2, visibility);
        validateFields();
    }

    private int getPortFromSecurityType() {
        int securityType = (Integer)((SpinnerOption)mSecurityTypeView.getSelectedItem()).value;
        int port = (securityType & HostAuth.FLAG_SSL) != 0 ? SMTP_PORT_SSL : SMTP_PORT_NORMAL;
        return port;
    }

    private void updatePortFromSecurityType() {
        int port = getPortFromSecurityType();
        mPortView.setText(Integer.toString(port));
    }

    /**
     * Entry point from Activity after editing settings and verifying them.  Must be FLOW_MODE_EDIT.
     * Blocking - do not call from UI Thread.
     */
    @Override
    public void saveSettingsAfterEdit() {
        Account account = SetupData.getAccount();
        account.mHostAuthSend.update(mContext, account.mHostAuthSend.toContentValues());
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
     * Entry point from Activity, when "next" button is clicked
     */
    @Override
    public void onNext() {
        Account account = SetupData.getAccount();
        HostAuth sendAuth = account.getOrCreateHostAuthSend(mContext);

        if (mRequireLoginView.isChecked()) {
            String userName = mUsernameView.getText().toString().trim();
            String userPassword = mPasswordView.getText().toString();
            sendAuth.setLogin(userName, userPassword);
        } else {
            sendAuth.setLogin(null, null);
        }

        String serverAddress = mServerView.getText().toString().trim();
        int serverPort;
        try {
            serverPort = Integer.parseInt(mPortView.getText().toString().trim());
        } catch (NumberFormatException e) {
            serverPort = getPortFromSecurityType();
            Log.d(Logging.LOG_TAG, "Non-integer server port; using '" + serverPort + "'");
        }
        int securityType = (Integer)((SpinnerOption)mSecurityTypeView.getSelectedItem()).value;
        sendAuth.setConnection(mBaseScheme, serverAddress, serverPort, securityType);
        sendAuth.mDomain = null;

        mCallback.onProceedNext(SetupData.CHECK_OUTGOING, this);
    }
}
