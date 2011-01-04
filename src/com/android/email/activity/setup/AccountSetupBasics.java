/*
 * Copyright (C) 2008 The Android Open Source Project
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
import com.android.email.VendorPolicyLoader;
import com.android.email.activity.ActivityHelper;
import com.android.email.activity.Welcome;
import com.android.email.activity.setup.AccountSettingsUtils.Provider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.HostAuth;

import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;

/**
 * Prompts the user for the email address and password. Also prompts for "Use this account as
 * default" if this is the 2nd+ account being set up.
 *
 * If the domain is well-known, the account is configured fully and checked immediately
 * using AccountCheckSettingsFragment.  If this succeeds we proceed directly to AccountSetupOptions.
 *
 * If the domain is not known, or the user selects Manual setup, we invoke the
 * AccountSetupAccountType activity where the user can begin to manually configure the account.
 *
 * === Support for automated testing ==
 * This activity can also be launched directly via ACTION_CREATE_ACCOUNT.  This is intended
 * only for use by continuous test systems, and is currently only available when "ro.monkey"
 * is set.  To use this mode, you must construct an intent which contains all necessary information
 * to create the account.  No connection checking is done, so the account may or may not actually
 * work.  Here is a sample command, for a gmail account "test_account" with a password of
 * "test_password".
 *
 *      $ adb shell am start -a com.android.email.CREATE_ACCOUNT \
 *          -e EMAIL test_account@gmail.com \
 *          -e USER "Test Account Name" \
 *          -e INCOMING imap+ssl+://test_account:test_password@imap.gmail.com \
 *          -e OUTGOING smtp+ssl+://test_account:test_password@smtp.gmail.com
 *
 * Note: For accounts that require the full email address in the login, encode the @ as %40.
 * Note: Exchange accounts that require device security policies cannot be created automatically.
 */
public class AccountSetupBasics extends AccountSetupActivity
        implements OnClickListener, TextWatcher, AccountCheckSettingsFragment.Callbacks {

    private final static boolean ENTER_DEBUG_SCREEN = true;

    /**
     * Direct access for forcing account creation
     * For use by continuous automated test system (e.g. in conjunction with monkey tests)
     */
    private final String ACTION_CREATE_ACCOUNT = "com.android.email.CREATE_ACCOUNT";
    private final String EXTRA_CREATE_ACCOUNT_EMAIL = "EMAIL";
    private final String EXTRA_CREATE_ACCOUNT_USER = "USER";
    private final String EXTRA_CREATE_ACCOUNT_INCOMING = "INCOMING";
    private final String EXTRA_CREATE_ACCOUNT_OUTGOING = "OUTGOING";
    private final Boolean DEBUG_ALLOW_NON_MONKEY_CREATION = true;  // STOPSHIP - must be FALSE

    private final static String STATE_KEY_PROVIDER = "AccountSetupBasics.provider";

    // NOTE: If you change this value, confirm that the new interval exists in arrays.xml
    private final static int DEFAULT_ACCOUNT_CHECK_INTERVAL = 15;

    // Support for UI
    private TextView mWelcomeView;
    private EditText mEmailView;
    private EditText mPasswordView;
    private CheckBox mDefaultView;
    private EmailAddressValidator mEmailValidator = new EmailAddressValidator();
    private Provider mProvider;
    private Button mManualButton;
    private Button mNextButton;
    private boolean mNextButtonInhibit;

    // Used when this Activity is called as part of account authentification flow,
    // which requires to do extra work before and after the account creation.
    // See also AccountAuthenticatorActivity.
    private AccountAuthenticatorResponse mAccountAuthenticatorResponse = null;
    private Bundle mResultBundle = null;

    // FutureTask to look up the owner
    FutureTask<String> mOwnerLookupTask;

    public static void actionNewAccount(Activity fromActivity) {
        SetupData.init(SetupData.FLOW_MODE_NORMAL);
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupBasics.class));
    }

    /**
     * This generates setup data that can be used to start a self-contained account creation flow
     * for exchange accounts.
     */
    public static Intent actionSetupExchangeIntent(Context context) {
        SetupData.init(SetupData.FLOW_MODE_ACCOUNT_MANAGER_EAS);
        return new Intent(context, AccountSetupBasics.class);
    }

    /**
     * This generates setup data that can be used to start a self-contained account creation flow
     * for pop/imap accounts.
     */
    public static Intent actionSetupPopImapIntent(Context context) {
        SetupData.init(SetupData.FLOW_MODE_ACCOUNT_MANAGER_POP_IMAP);
        return new Intent(context, AccountSetupBasics.class);
    }

    public static void actionAccountCreateFinishedAccountFlow(Activity fromActivity) {
        Intent i= new Intent(fromActivity, AccountSetupBasics.class);
        // If we're in the "account flow" (from AccountManager), we want to return to the caller
        // (in the settings app)
        SetupData.init(SetupData.FLOW_MODE_RETURN_TO_CALLER);
        i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        fromActivity.startActivity(i);
    }

    public static void actionAccountCreateFinished(final Activity fromActivity,
            final long accountId) {
        Utility.runAsync(new Runnable() {
           public void run() {
               Intent i = new Intent(fromActivity, AccountSetupBasics.class);
               // If we're not in the "account flow" (from AccountManager), we want to show the
               // message list for the new inbox
               Account account = Account.restoreAccountWithId(fromActivity, accountId);
               SetupData.init(SetupData.FLOW_MODE_RETURN_TO_MESSAGE_LIST, account);
               i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
               fromActivity.startActivity(i);
            }});
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);

        // Check for forced account creation first, as it comes from an externally-generated
        // intent and won't have any SetupData prepared.
        String action = getIntent().getAction();
        if (ACTION_CREATE_ACCOUNT.equals(action)) {
            SetupData.init(SetupData.FLOW_MODE_FORCE_CREATE);
        }

        int flowMode = SetupData.getFlowMode();
        if (flowMode == SetupData.FLOW_MODE_RETURN_TO_CALLER) {
            // Return to the caller who initiated account creation
            finish();
            return;
        } else if (flowMode == SetupData.FLOW_MODE_RETURN_TO_MESSAGE_LIST) {
            Account account = SetupData.getAccount();
            if (account != null && account.mId >= 0) {
                // Show the message list for the new account
                Welcome.actionOpenAccountInbox(this, account.mId);
                finish();
                return;
            }
        }

        setContentView(R.layout.account_setup_basics);

        mWelcomeView = (TextView) findViewById(R.id.instructions);
        mEmailView = (EditText) findViewById(R.id.account_email);
        mPasswordView = (EditText) findViewById(R.id.account_password);
        mDefaultView = (CheckBox) findViewById(R.id.account_default);

        mEmailView.addTextChangedListener(this);
        mPasswordView.addTextChangedListener(this);

        // If there are one or more accounts already in existence, then display
        // the "use as default" checkbox (it defaults to hidden).
        new DisplayCheckboxTask().execute();

        boolean manualButtonDisplayed = true;
        boolean alternateStrings = false;
        if (flowMode == SetupData.FLOW_MODE_ACCOUNT_MANAGER_EAS) {
            // No need for manual button -> next is appropriate
            manualButtonDisplayed = false;
            // Swap welcome text for EAS-specific text
            alternateStrings = VendorPolicyLoader.getInstance(this).useAlternateExchangeStrings();
            setTitle(alternateStrings
                    ? R.string.account_setup_basics_exchange_title_alternate
                    : R.string.account_setup_basics_exchange_title);
            mWelcomeView.setText(alternateStrings
                    ? R.string.accounts_welcome_exchange_alternate
                    : R.string.accounts_welcome_exchange);
        }

        // Configure buttons
        mManualButton = (Button) findViewById(R.id.manual_setup);
        mNextButton = (Button) findViewById(R.id.next);
        mManualButton.setVisibility(manualButtonDisplayed ? View.VISIBLE : View.INVISIBLE);
        mManualButton.setOnClickListener(this);
        mNextButton.setOnClickListener(this);
        // Force disabled until validator notifies otherwise
        onEnableProceedButtons(false);
        // Lightweight debounce while Async tasks underway
        mNextButtonInhibit = false;

        mAccountAuthenticatorResponse =
            getIntent().getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);

        if (mAccountAuthenticatorResponse != null) {
            mAccountAuthenticatorResponse.onRequestContinued();
        }

        // Load fields, but only once
        String userName = SetupData.getUsername();
        if (userName != null) {
            mEmailView.setText(userName);
            SetupData.setUsername(null);
        }
        String password = SetupData.getPassword();
        if (userName != null) {
            mPasswordView.setText(password);
            SetupData.setPassword(null);
        }

        // Handle force account creation immediately (now that fragment is set up)
        // This is never allowed in a normal user build and will exit immediately.
        if (SetupData.getFlowMode() == SetupData.FLOW_MODE_FORCE_CREATE) {
            if (!DEBUG_ALLOW_NON_MONKEY_CREATION && !ActivityManager.isUserAMonkey()) {
                Log.e(Email.LOG_TAG, "ERROR: Force account create only allowed for monkeys");
                finish();
                return;
            }
            Intent intent = getIntent();
            String email = intent.getStringExtra(EXTRA_CREATE_ACCOUNT_EMAIL);
            String user = intent.getStringExtra(EXTRA_CREATE_ACCOUNT_USER);
            String incoming = intent.getStringExtra(EXTRA_CREATE_ACCOUNT_INCOMING);
            String outgoing = intent.getStringExtra(EXTRA_CREATE_ACCOUNT_OUTGOING);
            if (TextUtils.isEmpty(email) || TextUtils.isEmpty(user) ||
                    TextUtils.isEmpty(incoming) || TextUtils.isEmpty(outgoing)) {
                Log.e(Email.LOG_TAG, "ERROR: Force account create requires extras EMAIL, USER, " +
                        "INCOMING, OUTGOING");
                finish();
                return;
            }
            forceCreateAccount(email, user, incoming, outgoing);
            onCheckSettingsComplete(AccountCheckSettingsFragment.CHECK_SETTINGS_OK); // calls finish
            return;
        }

        if (savedInstanceState != null && savedInstanceState.containsKey(STATE_KEY_PROVIDER)) {
            mProvider = (Provider) savedInstanceState.getSerializable(STATE_KEY_PROVIDER);
        }

        // Launch a worker to look up the owner name.  It should be ready well in advance of
        // the time the user clicks next or manual.
        mOwnerLookupTask = new FutureTask<String>(mOwnerLookupCallable);
        Utility.runAsync(mOwnerLookupTask);
    }

    @Override
    public void finish() {
        if (mAccountAuthenticatorResponse != null) {
            // send the result bundle back if set, otherwise send an error.
            if (mResultBundle != null) {
                mAccountAuthenticatorResponse.onResult(mResultBundle);
            } else {
                mAccountAuthenticatorResponse.onError(AccountManager.ERROR_CODE_CANCELED,
                        "canceled");
            }
            mAccountAuthenticatorResponse = null;
        }
        super.finish();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mProvider != null) {
            outState.putSerializable(STATE_KEY_PROVIDER, mProvider);
        }
    }

    /**
     * Implements OnClickListener
     */
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.next:
                // Simple debounce - just ignore while async checks are underway
                if (mNextButtonInhibit) {
                    return;
                }
                onNext();
                break;
            case R.id.manual_setup:
                onManualSetup(false);
                break;
        }
    }

    /**
     * Implements TextWatcher
     */
    public void afterTextChanged(Editable s) {
        validateFields();
    }

    /**
     * Implements TextWatcher
     */
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }

    /**
     * Implements TextWatcher
     */
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }

    private void validateFields() {
        boolean valid = Utility.isTextViewNotEmpty(mEmailView)
                && Utility.isTextViewNotEmpty(mPasswordView)
                && mEmailValidator.isValid(mEmailView.getText().toString().trim());
        onEnableProceedButtons(valid);
    }

    /**
     * Return an existing username if found, or null.  This is the result of the Callable (below).
     */
    private String getOwnerName() {
        String result = null;
        try {
            result = mOwnerLookupTask.get();
        } catch (InterruptedException e) {
        } catch (ExecutionException e) {
        }
        return result;
    }

    /**
     * Callable that returns the username (based on other accounts) or null.
     */
    private Callable<String> mOwnerLookupCallable = new Callable<String>() {
        public String call() {
            Context context = AccountSetupBasics.this;
            String name = null;
            long defaultId = Account.getDefaultAccountId(context);
            if (defaultId != -1) {
                Account account = Account.restoreAccountWithId(context, defaultId);
                if (account != null) {
                    name = account.getSenderName();
                }
            }
            return name;
        }
    };

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
        String incomingUsername = mProvider.incomingUsernameTemplate;
        try {
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

        // Populate the setup data, assuming that the duplicate account check will succeed
        populateSetupData(getOwnerName(), email, mDefaultView.isChecked(),
                incomingUri.toString(), outgoingUri.toString());

        // Stop here if the login credentials duplicate an existing account
        // Launch an Async task to do the work
        new DuplicateCheckTask(this, incomingUri.getHost(), incomingUsername).execute();
    }

    /**
     * Async task that continues the work of finishAutoSetup().  Checks for a duplicate
     * account and then either alerts the user, or continues.
     */
    private class DuplicateCheckTask extends AsyncTask<Void, Void, Account> {
        private final Context mContext;
        private final String mCheckHost;
        private final String mCheckLogin;

        public DuplicateCheckTask(Context context, String checkHost, String checkLogin) {
            mContext = context;
            mCheckHost = checkHost;
            mCheckLogin = checkLogin;
            // Prevent additional clicks on the next button during Async lookup
            mNextButtonInhibit = true;
        }

        @Override
        protected Account doInBackground(Void... params) {
            EmailContent.Account account = Utility.findExistingAccount(mContext, -1,
                    mCheckHost, mCheckLogin);
            return account;
        }

        @Override
        protected void onPostExecute(Account duplicateAccount) {
            mNextButtonInhibit = false;
            // Show duplicate account warning, or proceed
            if (duplicateAccount != null) {
                DuplicateAccountDialogFragment dialogFragment =
                    DuplicateAccountDialogFragment.newInstance(duplicateAccount.mDisplayName);
                dialogFragment.show(getFragmentManager(), DuplicateAccountDialogFragment.TAG);
                return;
            } else {
                AccountCheckSettingsFragment checkerFragment =
                    AccountCheckSettingsFragment.newInstance(
                        SetupData.CHECK_INCOMING | SetupData.CHECK_OUTGOING, null);
                FragmentTransaction transaction = getFragmentManager().openTransaction();
                transaction.add(checkerFragment, AccountCheckSettingsFragment.TAG);
                transaction.addToBackStack("back");
                transaction.commit();
            }
        }
    }


    /**
     * When "next" button is clicked
     */
    private void onNext() {
        // Try auto-configuration from XML providers (unless in EAS mode, we can skip it)
        if (SetupData.getFlowMode() != SetupData.FLOW_MODE_ACCOUNT_MANAGER_EAS) {
            String email = mEmailView.getText().toString().trim();
            String[] emailParts = email.split("@");
            String domain = emailParts[1].trim();
            mProvider = AccountSettingsUtils.findProviderForDomain(this, domain);
            if (mProvider != null) {
                if (mProvider.note != null) {
                    NoteDialogFragment dialogFragment =
                            NoteDialogFragment.newInstance(mProvider.note);
                    dialogFragment.show(getFragmentManager(), NoteDialogFragment.TAG);
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
     * When "manual setup" button is clicked
     *
     * @param allowAutoDiscover - true if the user clicked 'next' and (if the account is EAS)
     * it's OK to use autodiscover.  false to prevent autodiscover and go straight to manual setup.
     * Ignored for IMAP & POP accounts.
     */
    private void onManualSetup(boolean allowAutoDiscover) {
        String email = mEmailView.getText().toString().trim();
        String password = mPasswordView.getText().toString();
        String[] emailParts = email.split("@");
        String user = emailParts[0].trim();
        String domain = emailParts[1].trim();

        // Alternate entry to the debug options screen (for devices without a physical keyboard:
        //  Username: d@d.d
        //  Password: debug
        if (ENTER_DEBUG_SCREEN && "d@d.d".equals(email) && "debug".equals(password)) {
            mEmailView.setText("");
            mPasswordView.setText("");
            AccountSettingsXL.actionSettingsWithDebug(this);
            return;
        }

        String uriString = null;
        try {
            URI uri = new URI("placeholder", user + ":" + password, domain, -1, null, null, null);
            uriString = uri.toString();
        } catch (URISyntaxException use) {
            // If we can't set up the URL, don't continue - account setup pages will fail too
            Toast.makeText(this, R.string.account_setup_username_password_toast,
                    Toast.LENGTH_LONG).show();
            return;
        }

        populateSetupData(getOwnerName(), email, mDefaultView.isChecked(), uriString, uriString);

        SetupData.setAllowAutodiscover(allowAutoDiscover);
        AccountSetupAccountType.actionSelectAccountType(this);
    }

    /**
     * To support continuous testing, we allow the forced creation of accounts.
     * This works in a manner fairly similar to automatic setup, in which the complete server
     * Uri's are available, except that we will also skip checking (as if both checks were true)
     * and all other UI.
     *
     * @param email The email address for the new account
     * @param user The user name for the new account
     * @param incoming The URI-style string defining the incoming account
     * @param outgoing The URI-style string defining the outgoing account
     */
    private void forceCreateAccount(String email, String user, String incoming, String outgoing) {
        populateSetupData(user, email, false, incoming, outgoing);
    }

    /**
     * Populate SetupData's account with complete setup info.
     */
    private void populateSetupData(String senderName, String senderEmail, boolean isDefault,
            String incoming, String outgoing) {
        Account account = SetupData.getAccount();
        account.setSenderName(senderName);
        account.setEmailAddress(senderEmail);
        account.setDisplayName(senderEmail);
        account.setDefaultAccount(isDefault);
        SetupData.setDefault(isDefault);        // TODO - why duplicated, if already set in account
        account.setStoreUri(this, incoming);
        account.setSenderUri(this, outgoing);

        // Set sync and delete policies for specific account types
        if (incoming.startsWith("imap")) {
            // Delete policy must be set explicitly, because IMAP does not provide a UI selection
            // for it. This logic needs to be followed in the auto setup flow as well.
            account.setDeletePolicy(EmailContent.Account.DELETE_POLICY_ON_DELETE);
        }

        if (incoming.startsWith("eas")) {
            account.setSyncInterval(Account.CHECK_INTERVAL_PUSH);
        } else {
            account.setSyncInterval(DEFAULT_ACCOUNT_CHECK_INTERVAL);
        }
    }

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     *
     * This is used in automatic setup mode to jump directly down to the options screen.
     */
    @Override
    public void onCheckSettingsComplete(int result) {
        if (result == AccountCheckSettingsFragment.CHECK_SETTINGS_OK) {
            AccountSetupOptions.actionOptions(this);
            finish();
        }
    }

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     * This is overridden only by AccountSetupExchange
     */
    @Override
    public void onAutoDiscoverComplete(int result, HostAuth hostAuth) {
        throw new IllegalStateException();
    }

    /**
     * AsyncTask checks count of accounts and displays "use this account as default" checkbox
     * if there are more than one.
     */
    private class DisplayCheckboxTask extends AsyncTask<Void, Void, Integer> {

        @Override
        protected Integer doInBackground(Void... params) {
            return EmailContent.count(AccountSetupBasics.this, EmailContent.Account.CONTENT_URI);
        }

        @Override
        protected void onPostExecute(Integer numAccounts) {
            if (numAccounts > 0) {
                Activity activity = AccountSetupBasics.this;
                activity.findViewById(R.id.account_default_divider_1).setVisibility(View.VISIBLE);
                mDefaultView.setVisibility(View.VISIBLE);
                activity.findViewById(R.id.account_default_divider_2).setVisibility(View.VISIBLE);
            }
        }
    }

    private void onEnableProceedButtons(boolean enabled) {
        mManualButton.setEnabled(enabled);
        mNextButton.setEnabled(enabled);
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
        public static NoteDialogFragment newInstance(String note) {
            NoteDialogFragment f = new NoteDialogFragment();
            Bundle b = new Bundle();
            b.putString(BUNDLE_KEY_NOTE, note);
            f.setArguments(b);
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
                                Activity a = getActivity();
                                if (a instanceof AccountSetupBasics) {
                                    ((AccountSetupBasics)a).finishAutoSetup();
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
