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
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;

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

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Provides UI for SMTP account settings (for IMAP/POP accounts).
 *
 * This fragment is used by AccountSetupOutgoing (for creating accounts) and by AccountSettingsXL
 * (for editing existing accounts).
 */
public class AccountSetupOutgoingFragment extends AccountServerBaseFragment
        implements OnCheckedChangeListener {

    private final static String STATE_KEY_LOADED = "AccountSetupOutgoingFragment.loaded";

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
    private View mRequireLoginSettingsView;
    private View mRequireLoginSettingsView2;
    private Spinner mSecurityTypeView;

    // Support for lifecycle
    private boolean mStarted;
    private boolean mLoaded;

    /**
     * Create the fragment with parameters - used mainly to force into settings mode (with buttons)
     * @param settingsMode if true, alters appearance for use in settings (default is "setup")
     */
    public static AccountSetupOutgoingFragment newInstance(boolean settingsMode) {
        AccountSetupOutgoingFragment f = new AccountSetupOutgoingFragment();
        f.setSetupArguments(settingsMode);
        return f;
    }

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupOutgoingFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mLoaded = savedInstanceState.getBoolean(STATE_KEY_LOADED, false);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupOutgoingFragment onCreateView");
        }
        int layoutId = mSettingsMode
                ? R.layout.account_settings_outgoing_fragment
                : R.layout.account_setup_outgoing_fragment;

        View view = inflater.inflate(layoutId, container, false);
        Context context = getActivity();

        mUsernameView = (EditText) view.findViewById(R.id.account_username);
        mPasswordView = (EditText) view.findViewById(R.id.account_password);
        mServerView = (EditText) view.findViewById(R.id.account_server);
        mPortView = (EditText) view.findViewById(R.id.account_port);
        mRequireLoginView = (CheckBox) view.findViewById(R.id.account_require_login);
        mRequireLoginSettingsView = view.findViewById(R.id.account_require_login_settings);
        mRequireLoginSettingsView2 = view.findViewById(R.id.account_require_login_settings_2);
        mSecurityTypeView = (Spinner) view.findViewById(R.id.account_security_type);
        mRequireLoginView.setOnCheckedChangeListener(this);

        // Note:  Strings are shared with AccountSetupIncomingFragment
        SpinnerOption securityTypes[] = {
            new SpinnerOption(0, context.getString(
                    R.string.account_setup_incoming_security_none_label)),
            new SpinnerOption(1, context.getString(
                    R.string.account_setup_incoming_security_ssl_label)),
            new SpinnerOption(2, context.getString(
                    R.string.account_setup_incoming_security_ssl_trust_certificates_label)),
            new SpinnerOption(3, context.getString(
                    R.string.account_setup_incoming_security_tls_label)),
            new SpinnerOption(4, context.getString(
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
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupOutgoingFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupOutgoingFragment onStart");
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
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupOutgoingFragment onResume");
        }
        super.onResume();
        validateFields();
    }

    @Override
    public void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupOutgoingFragment onPause");
        }
        super.onPause();
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupOutgoingFragment onStop");
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
            Log.d(Email.LOG_TAG, "AccountSetupOutgoingFragment onDestroy");
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupOutgoingFragment onSaveInstanceState");
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
        try {
            // TODO this should be accessed directly via the HostAuth structure
            URI uri = new URI(SetupData.getAccount().getSenderUri(mContext));
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
        mLoaded = true;
        validateFields();
    }

    /**
     * Preflight the values in the fields and decide if it makes sense to enable the "next" button
     */
    private void validateFields() {
        if (!mLoaded) return;
        boolean enabled =
            Utility.isTextViewNotEmpty(mServerView) && Utility.isPortFieldValid(mPortView);

        if (enabled && mRequireLoginView.isChecked()) {
            enabled = (Utility.isTextViewNotEmpty(mUsernameView)
                    && Utility.isTextViewNotEmpty(mPasswordView));
        }

        if (enabled) {
            try {
                URI uri = getUri();
            } catch (URISyntaxException use) {
                enabled = false;
            }
        }
        enableNextButton(enabled);
   }

    /**
     * implements OnCheckedChangeListener
     */
    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        mRequireLoginSettingsView.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        if (mRequireLoginSettingsView2 != null) {
            mRequireLoginSettingsView2.setVisibility(isChecked ? View.VISIBLE : View.GONE);
        }
        validateFields();
    }

    private void updatePortFromSecurityType() {
        int securityType = (Integer)((SpinnerOption)mSecurityTypeView.getSelectedItem()).value;
        mPortView.setText(Integer.toString(SMTP_PORTS[securityType]));
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
        AccountBackupRestore.backupAccounts(mContext);
    }

    /**
     * Entry point from Activity after entering new settings and verifying them.  For setup mode.
     */
    @Override
    public void saveSettingsAfterSetup() {
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

    /**
     * Entry point from Activity, when "next" button is clicked
     */
    @Override
    public void onNext() {
        EmailContent.Account account = SetupData.getAccount();
        try {
            // TODO this should be accessed directly via the HostAuth structure
            URI uri = getUri();
            account.setSenderUri(mContext, uri.toString());
        } catch (URISyntaxException use) {
            /*
             * It's unrecoverable if we cannot create a URI from components that
             * we validated to be safe.
             */
            throw new Error(use);
        }

        mCallback.onProceedNext(SetupData.CHECK_OUTGOING, this);
    }
}
