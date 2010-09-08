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
import android.preference.PreferenceActivity;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.DigitsKeyListener;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.net.URI;
import java.net.URISyntaxException;

public class AccountSetupIncomingFragment extends AccountServerBaseFragment {

    private final static String STATE_KEY_CREDENTIAL =
            "AccountSetupIncomingFragment.loginCredential";

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

    private int mAccountPorts[];
    private String mAccountSchemes[];
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

    // Support for lifecycle
    private boolean mStarted;
    private boolean mLoaded;
    private String mCacheLoginCredential;

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupIncomingFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            mCacheLoginCredential = savedInstanceState.getString(STATE_KEY_CREDENTIAL);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupIncomingFragment onCreateView");
        }
        View view = inflater.inflate(R.layout.account_setup_incoming_fragment, container, false);
        Context context = getActivity();

        mUsernameView = (EditText) view.findViewById(R.id.account_username);
        mPasswordView = (EditText) view.findViewById(R.id.account_password);
        mServerLabelView = (TextView) view.findViewById(R.id.account_server_label);
        mServerView = (EditText) view.findViewById(R.id.account_server);
        mPortView = (EditText) view.findViewById(R.id.account_port);
        mSecurityTypeView = (Spinner) view.findViewById(R.id.account_security_type);
        mDeletePolicyLabelView = (TextView) view.findViewById(R.id.account_delete_policy_label);
        mDeletePolicyView = (Spinner) view.findViewById(R.id.account_delete_policy);
        mImapPathPrefixSectionView = view.findViewById(R.id.imap_path_prefix_section);
        mImapPathPrefixView = (EditText) view.findViewById(R.id.imap_path_prefix);

        // Set up spinners
        SpinnerOption securityTypes[] = {
            new SpinnerOption(0,
                    context.getString(R.string.account_setup_incoming_security_none_label)),
            new SpinnerOption(1,
                    context.getString(R.string.account_setup_incoming_security_ssl_label)),
            new SpinnerOption(2,
                    context.getString(
                            R.string.account_setup_incoming_security_ssl_trust_certificates_label)),
            new SpinnerOption(3,
                    context.getString(R.string.account_setup_incoming_security_tls_label)),
            new SpinnerOption(4,
                    context.getString(
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
        mUsernameView.addTextChangedListener(validationTextWatcher);
        mPasswordView.addTextChangedListener(validationTextWatcher);
        mServerView.addTextChangedListener(validationTextWatcher);
        mPortView.addTextChangedListener(validationTextWatcher);

        // Only allow digits in the port field.
        mPortView.setKeyListener(DigitsKeyListener.getInstance("0123456789"));

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupIncomingFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupIncomingFragment onStart");
        }
        super.onStart();
        mStarted = true;
        if (!mLoaded) {
            loadSettings();
        }
    }

    /**
     * Called when the fragment is visible to the user and actively running.
     */
    @Override
    public void onResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupIncomingFragment onResume");
        }
        super.onResume();
        validateFields();
    }

    @Override
    public void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupIncomingFragment onPause");
        }
        super.onPause();
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupIncomingFragment onStop");
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
            Log.d(Email.LOG_TAG, "AccountSetupIncomingFragment onDestroy");
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupIncomingFragment onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);

        outState.putString(STATE_KEY_CREDENTIAL, mCacheLoginCredential);
    }

    /**
     * Activity provides callbacks here.  This also triggers loading and setting up the UX
     */
    @Override
    public void setCallback(Callback callback) {
        super.setCallback(callback);
        if (mStarted && !mLoaded) {
            loadSettings();
        }
    }

    /**
     * Load the current settings into the UI
     */
    private void loadSettings() {
        try {
            // TODO this should be accessed directly via the HostAuth structure
            EmailContent.Account account = SetupData.getAccount();
            URI uri = new URI(account.getStoreUri(mContext));
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
                mServerLabelView.setText(R.string.account_setup_incoming_pop_server_label);
                mAccountPorts = POP_PORTS;
                mAccountSchemes = POP_SCHEMES;

                mImapPathPrefixSectionView.setVisibility(View.GONE);
            } else if (uri.getScheme().startsWith("imap")) {
                mServerLabelView.setText(R.string.account_setup_incoming_imap_server_label);
                mAccountPorts = IMAP_PORTS;
                mAccountSchemes = IMAP_SCHEMES;

                mDeletePolicyLabelView.setVisibility(View.GONE);
                mDeletePolicyView.setVisibility(View.GONE);
                if (uri.getPath() != null && uri.getPath().length() > 0) {
                    mImapPathPrefixView.setText(uri.getPath().substring(1));
                }
            } else {
                throw new Error("Unknown account type: " + account.getStoreUri(mContext));
            }

            for (int i = 0; i < mAccountSchemes.length; i++) {
                if (mAccountSchemes[i].equals(uri.getScheme())) {
                    SpinnerOption.setSpinnerOptionValue(mSecurityTypeView, i);
                }
            }

            SpinnerOption.setSpinnerOptionValue(mDeletePolicyView, account.getDeletePolicy());

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

    /**
     * Check the values in the fields and decide if it makes sense to enable the "next" button
     */
    private void validateFields() {
        boolean enabled = Utility.isTextViewNotEmpty(mUsernameView)
                && Utility.isTextViewNotEmpty(mPasswordView)
                && Utility.isTextViewNotEmpty(mServerView)
                && Utility.isPortFieldValid(mPortView);
        if (enabled) {
            try {
                URI uri = getUri();
            } catch (URISyntaxException use) {
                enabled = false;
            }
        }
        enableNextButton(enabled);
    }

    private void updatePortFromSecurityType() {
        int securityType = (Integer)((SpinnerOption)mSecurityTypeView.getSelectedItem()).value;
        mPortView.setText(Integer.toString(mAccountPorts[securityType]));
    }

    /**
     * Entry point from Activity after editing settings and verifying them.  Must be FLOW_MODE_EDIT.
     */
    @Override
    public void saveSettingsAfterEdit() {
        EmailContent.Account account = SetupData.getAccount();
        if (account.isSaved()) {
            account.update(mContext, account.toContentValues());
            account.mHostAuthRecv.update(mContext, account.mHostAuthRecv.toContentValues());
        } else {
            account.save(mContext);
        }
        // Update the backup (side copy) of the accounts
        AccountBackupRestore.backupAccounts(mContext);
    }

    /**
     * Entry point from Activity after entering new settings and verifying them.  For setup mode.
     */
    public void saveSettingsAfterSetup() {
        EmailContent.Account account = SetupData.getAccount();

        // Set the username and password for the outgoing settings to the username and
        // password the user just set for incoming.
        try {
            URI oldUri = new URI(account.getSenderUri(mContext));
            URI uri = new URI(
                    oldUri.getScheme(),
                    mUsernameView.getText().toString().trim() + ":"
                            + mPasswordView.getText().toString().trim(),
                    oldUri.getHost(),
                    oldUri.getPort(),
                    null,
                    null,
                    null);
            account.setSenderUri(mContext, uri.toString());
        } catch (URISyntaxException use) {
            // If we can't set up the URL we just continue. It's only for convenience.
        }
    }

    /**
     * Attempt to create a URI from the fields provided.  Throws URISyntaxException if there's
     * a problem with the user input.
     * @return a URI built from the account setup fields
     */
    private URI getUri() throws URISyntaxException {
        int securityType = (Integer)((SpinnerOption)mSecurityTypeView.getSelectedItem()).value;
        String path = null;
        if (mAccountSchemes[securityType].startsWith("imap")) {
            path = "/" + mImapPathPrefixView.getText().toString().trim();
        }
        String userName = mUsernameView.getText().toString().trim();
        mCacheLoginCredential = userName;
        URI uri = new URI(
                mAccountSchemes[securityType],
                userName + ":" + mPasswordView.getText().toString().trim(),
                mServerView.getText().toString().trim(),
                Integer.parseInt(mPortView.getText().toString().trim()),
                path, // path
                null, // query
                null);

        return uri;
    }

    /**
     * Entry point from Activity, when "next" button is clicked
     */
    @Override
    public void onNext() {
        EmailContent.Account setupAccount = SetupData.getAccount();
        try {
            URI uri = getUri();
            setupAccount.setStoreUri(mContext, uri.toString());

            // Stop here if the login credentials duplicate an existing account
            // (unless they duplicate the existing account, as they of course will)
            EmailContent.Account account = Utility.findExistingAccount(mContext, setupAccount.mId,
                    uri.getHost(), mCacheLoginCredential);
            if (account != null) {
                DuplicateAccountDialogFragment dialogFragment =
                    DuplicateAccountDialogFragment.newInstance(account.mDisplayName);
                dialogFragment.show(getActivity(), DuplicateAccountDialogFragment.TAG);
                return;
            }
        } catch (URISyntaxException use) {
            /*
             * It's unrecoverable if we cannot create a URI from components that
             * we validated to be safe.
             */
            throw new Error(use);
        }

        setupAccount.setDeletePolicy(
                (Integer)((SpinnerOption)mDeletePolicyView.getSelectedItem()).value);

        // STOPSHIP - use new checker fragment only during account settings (TODO: account setup)
        Activity activity = getActivity();
        if (activity instanceof PreferenceActivity) {
            AccountCheckSettingsFragment checkerFragment =
                AccountCheckSettingsFragment.newInstance(SetupData.CHECK_INCOMING, this);
            ((PreferenceActivity)activity).startPreferenceFragment(checkerFragment, true);
        } else {
            // STOPSHIP remove this old code
            mCallback.onProceedNext(SetupData.CHECK_INCOMING);
        }
    }
}
