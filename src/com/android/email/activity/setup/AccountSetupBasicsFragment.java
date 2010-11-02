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

import com.android.email.Email;
import com.android.email.EmailAddressValidator;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.activity.setup.AccountSettingsUtils.Provider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Prompts the user for the email address and password. Also prompts for
 * "Use this account as default" if this is the 2nd+ account being set up.
 * Attempts to lookup default settings for the domain the user specified. If the
 * domain is known the settings are handed off to the AccountSetupCheckSettings
 * activity. If no settings are found the settings are handed off to the
 * AccountSetupAccountType activity.
 *
 * TODO: Move provider lookups to AsyncTask(s)
 */
public class AccountSetupBasicsFragment extends Fragment implements TextWatcher {
    private final static boolean ENTER_DEBUG_SCREEN = true;

    private final static String STATE_KEY_PROVIDER =
        "com.android.email.AccountSetupBasics.provider";

    // NOTE: If you change this value, confirm that the new interval exists in arrays.xml
    private final static int DEFAULT_ACCOUNT_CHECK_INTERVAL = 15;

    // Support for UI
    private TextView mWelcomeView;
    private EditText mEmailView;
    private EditText mPasswordView;
    private CheckBox mDefaultView;
    private EmailAddressValidator mEmailValidator = new EmailAddressValidator();
    private Provider mProvider;

    // Support for lifecycle
    private Context mContext;
    private Callback mCallback = EmptyCallback.INSTANCE;
    private boolean mStarted;
    private boolean mLoaded;
    private boolean mUseAlternateStrings;

    /**
     * Callback interface that owning activities must implement
     */
    public interface Callback {
        public void onEnableProceedButtons(boolean enable);
        public void onProceedAutomatic();
        public void onProceedManual(boolean allowAutoDiscover);
        public void onProceedDebugSettings();
    }

    private static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onEnableProceedButtons(boolean enable) { }
        @Override public void onProceedAutomatic() { }
        @Override public void onProceedManual(boolean allowAutoDiscover) { }
        @Override public void onProceedDebugSettings() { }
    }

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupBasicsFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_KEY_PROVIDER)) {
            mProvider = (Provider) savedInstanceState.getSerializable(STATE_KEY_PROVIDER);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupBasicsFragment onCreateView");
        }
        View view = inflater.inflate(R.layout.account_setup_basics_fragment, container, false);

        mWelcomeView = (TextView) view.findViewById(R.id.instructions);
        mEmailView = (EditText) view.findViewById(R.id.account_email);
        mPasswordView = (EditText) view.findViewById(R.id.account_password);
        mDefaultView = (CheckBox) view.findViewById(R.id.account_default);

        mEmailView.addTextChangedListener(this);
        mPasswordView.addTextChangedListener(this);

        // TODO move this to an AsyncTask
        // Find out how many accounts we have, and if there one or more, then we have a choice
        // about being default or not.
        int numAccounts = EmailContent.count(getActivity(), EmailContent.Account.CONTENT_URI);
        if (numAccounts > 0) {
            mDefaultView.setVisibility(View.VISIBLE);
        }

        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupBasicsFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupBasicsFragment onStart");
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
            Log.d(Email.LOG_TAG, "AccountSetupBasicsFragment onResume");
        }
        super.onResume();
        validateFields();
    }

    @Override
    public void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupBasicsFragment onPause");
        }
        super.onPause();
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupBasicsFragment onStop");
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
            Log.d(Email.LOG_TAG, "AccountSetupBasicsFragment onDestroy");
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSetupBasicsFragment onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
        if (mProvider != null) {
            outState.putSerializable(STATE_KEY_PROVIDER, mProvider);
        }
    }

    /**
     * Activity provides callbacks here.  This also triggers loading and setting up the UX
     */
    public void setCallback(Callback callback, boolean useAlternateStrings) {
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
        mUseAlternateStrings = useAlternateStrings;
        mContext = getActivity();
        if (mStarted && !mLoaded) {
            loadSettings();
        }
    }

    /**
     * Load account data into preference UI
     */
    private void loadSettings() {
        // We can only do this once, so prevent repeat
        mLoaded = true;

        int flowMode = SetupData.getFlowMode();
        if (flowMode == SetupData.FLOW_MODE_ACCOUNT_MANAGER_EAS) {
            // Swap welcome text for EAS-specific text
            mWelcomeView.setText(mUseAlternateStrings
                    ? R.string.accounts_welcome_exchange_alternate
                            : R.string.accounts_welcome_exchange);
        }

        if (SetupData.getUsername() != null) {
            mEmailView.setText(SetupData.getUsername());
        }
        if (SetupData.getPassword() != null) {
            mPasswordView.setText(SetupData.getPassword());
        }
    }

    public void afterTextChanged(Editable s) {
        validateFields();
    }

    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    private void validateFields() {
        boolean valid = Utility.isTextViewNotEmpty(mEmailView)
                && Utility.isTextViewNotEmpty(mPasswordView)
                && mEmailValidator.isValid(mEmailView.getText().toString().trim());
        mCallback.onEnableProceedButtons(valid);
    }

    // TODO this should also be in AsyncTask
    private String getOwnerName() {
        String name = null;
/* TODO figure out another way to get the owner name
        String projection[] = {
            ContactMethods.NAME
        };
        Cursor c = getContentResolver().query(
                Uri.withAppendedPath(Contacts.People.CONTENT_URI, "owner"), projection, null, null,
                null);
        if (c != null) {
            if (c.moveToFirst()) {
                name = c.getString(0);
            }
            c.close();
        }
*/

        if (name == null || name.length() == 0) {
            long defaultId = Account.getDefaultAccountId(mContext);
            if (defaultId != -1) {
                Account account = Account.restoreAccountWithId(mContext, defaultId);
                if (account != null) {
                    name = account.getSenderName();
                }
            }
        }
        return name;
    }

    /**
     * Finish the auto setup process, in some cases after showing a warning dialog.
     */
    private void finishAutoSetup() {
        String email = mEmailView.getText().toString().trim();
        String password = mPasswordView.getText().toString().trim();
        String[] emailParts = email.split("@");
        String user = emailParts[0];
        String domain = emailParts[1];
        URI incomingUri = null;
        URI outgoingUri = null;
        try {
            String incomingUsername = mProvider.incomingUsernameTemplate;
            incomingUsername = incomingUsername.replaceAll("\\$email", email);
            incomingUsername = incomingUsername.replaceAll("\\$user", user);
            incomingUsername = incomingUsername.replaceAll("\\$domain", domain);

            URI incomingUriTemplate = mProvider.incomingUriTemplate;
            incomingUri = new URI(incomingUriTemplate.getScheme(), incomingUsername + ":"
                    + password, incomingUriTemplate.getHost(), incomingUriTemplate.getPort(),
                    incomingUriTemplate.getPath(), null, null);

            String outgoingUsername = mProvider.outgoingUsernameTemplate;
            outgoingUsername = outgoingUsername.replaceAll("\\$email", email);
            outgoingUsername = outgoingUsername.replaceAll("\\$user", user);
            outgoingUsername = outgoingUsername.replaceAll("\\$domain", domain);

            URI outgoingUriTemplate = mProvider.outgoingUriTemplate;
            outgoingUri = new URI(outgoingUriTemplate.getScheme(), outgoingUsername + ":"
                    + password, outgoingUriTemplate.getHost(), outgoingUriTemplate.getPort(),
                    outgoingUriTemplate.getPath(), null, null);

            // Stop here if the login credentials duplicate an existing account
            // TODO this shouldn't be in UI thread
            Account account = Utility.findExistingAccount(mContext, -1,
                    incomingUri.getHost(), incomingUsername);
            if (account != null) {
                DuplicateAccountDialogFragment dialogFragment =
                    DuplicateAccountDialogFragment.newInstance(account.mDisplayName);
                dialogFragment.show(getActivity(), DuplicateAccountDialogFragment.TAG);
                return;
            }

        } catch (URISyntaxException use) {
            /*
             * If there is some problem with the URI we give up and go on to
             * manual setup.  Technically speaking, AutoDiscover is OK here, since user clicked
             * "Next" to get here.  This would never happen in practice because we don't expect
             * to find any EAS accounts in the providers list.
             */
            onManualSetup(true);
            return;
        }

        Account account = SetupData.getAccount();
        account.setSenderName(getOwnerName());
        account.setEmailAddress(email);
        account.setDisplayName(email);
        boolean isDefault = mDefaultView.isChecked();
        account.setDefaultAccount(isDefault);
        SetupData.setDefault(isDefault);        // TODO - why duplicated, if already set in account
        account.setStoreUri(mContext, incomingUri.toString());
        account.setSenderUri(mContext, outgoingUri.toString());
        String incomingUriString = incomingUri.toString();
        if (incomingUriString.startsWith("imap")) {
            // Delete policy must be set explicitly, because IMAP does not provide a UI selection
            // for it. This logic needs to be followed in the auto setup flow as well.
            account.setDeletePolicy(EmailContent.Account.DELETE_POLICY_ON_DELETE);
        }
        if (incomingUriString.startsWith("eas")) {
            account.setSyncInterval(Account.CHECK_INTERVAL_PUSH);
        } else {
            account.setSyncInterval(DEFAULT_ACCOUNT_CHECK_INTERVAL);
        }
        mCallback.onProceedAutomatic();
    }

    /**
     * Entry point from Activity, when "next" button is clicked
     */
    public void onNext() {
        // Try auto-configuration from XML providers (unless in EAS mode, we can skip it)
        if (SetupData.getFlowMode() != SetupData.FLOW_MODE_ACCOUNT_MANAGER_EAS) {
            String email = mEmailView.getText().toString().trim();
            String[] emailParts = email.split("@");
            String domain = emailParts[1].trim();
            mProvider = AccountSettingsUtils.findProviderForDomain(mContext, domain);
            if (mProvider != null) {
                if (mProvider.note != null) {
                    NoteDialogFragment dialogFragment =
                        NoteDialogFragment.newInstance(mProvider.note, this);
                    dialogFragment.show(getActivity(), NoteDialogFragment.TAG);
                } else {
                    finishAutoSetup();
                }
                return;
            }
        }
        // Can't use auto setup (although EAS accounts may still be able to AutoDiscover)
        onManualSetup(true);
    }

    /**
     * Entry point from Activity, when "manual setup" button is clicked
     *
     * @param allowAutoDiscover - true if the user clicked 'next' and (if the account is EAS)
     * it's OK to use autodiscover.  false to prevent autodiscover and go straight to manual setup.
     * Ignored for IMAP & POP accounts.
     */
    public void onManualSetup(boolean allowAutoDiscover) {
        String email = mEmailView.getText().toString().trim();
        String password = mPasswordView.getText().toString().trim();
        String[] emailParts = email.split("@");
        String user = emailParts[0].trim();
        String domain = emailParts[1].trim();

        // Alternate entry to the debug options screen (for devices without a physical keyboard:
        //  Username: d@d.d
        //  Password: debug
        if (ENTER_DEBUG_SCREEN && "d@d.d".equals(email) && "debug".equals(password)) {
            mEmailView.setText("");
            mPasswordView.setText("");
            mCallback.onProceedDebugSettings();
            return;
        }

        Account account = SetupData.getAccount();
        account.setSenderName(getOwnerName());
        account.setEmailAddress(email);
        account.setDisplayName(email);
        boolean isDefault = mDefaultView.isChecked();
        account.setDefaultAccount(isDefault);
        SetupData.setDefault(isDefault);        // TODO - why duplicated, if already set in account
        try {
            URI uri = new URI("placeholder", user + ":" + password, domain, -1, null, null, null);
            account.setStoreUri(mContext, uri.toString());
            account.setSenderUri(mContext, uri.toString());
        } catch (URISyntaxException use) {
            // If we can't set up the URL, don't continue - account setup pages will fail too
            Toast.makeText(mContext, R.string.account_setup_username_password_toast,
                    Toast.LENGTH_LONG).show();
            account = null;
            return;
        }
        account.setSyncInterval(DEFAULT_ACCOUNT_CHECK_INTERVAL);

        mCallback.onProceedManual(allowAutoDiscover);
    }

    /**
     * Dialog fragment to show "setup note" dialog
     */
    public static class NoteDialogFragment extends DialogFragment {
        private final static String TAG = "NoteDialogFragment";

        // Argument bundle keys
        private final static String BUNDLE_KEY_NOTE = "NoteDialogFragment.Note";

        /**
         * Create the dialog with parameters
         */
        public static NoteDialogFragment newInstance(String note, Fragment parentFragment) {
            NoteDialogFragment f = new NoteDialogFragment();
            Bundle b = new Bundle();
            b.putString(BUNDLE_KEY_NOTE, note);
            f.setArguments(b);
            f.setTargetFragment(parentFragment, 0);
            return f;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Context context = getActivity();
            final String note = getArguments().getString(BUNDLE_KEY_NOTE);

            return new AlertDialog.Builder(context)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(android.R.string.dialog_alert_title)
                .setMessage(note)
                .setPositiveButton(
                        R.string.okay_action,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                Fragment f = getTargetFragment();
                                if (f instanceof AccountSetupBasicsFragment) {
                                    ((AccountSetupBasicsFragment)f).finishAutoSetup();
                                }
                                dismiss();
                            }
                        })
                .setNegativeButton(
                        context.getString(R.string.cancel_action),
                        null)
                .create();
        }
    }

}
