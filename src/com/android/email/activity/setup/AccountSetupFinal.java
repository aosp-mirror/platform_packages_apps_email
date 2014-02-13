/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.LoaderManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.emailcommon.VendorPolicyLoader;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.mail.utils.LogUtils;
import com.google.common.annotations.VisibleForTesting;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

public class AccountSetupFinal extends AccountSetupActivity
        implements View.OnClickListener, AccountFinalizeFragment.Callback,
        AccountSetupNoteDialogFragment.Callback, AccountCreationFragment.Callback,
        AccountCheckSettingsFragment.Callback, SecurityRequiredDialogFragment.Callback,
        CheckSettingsErrorDialogFragment.Callback, CheckSettingsProgressDialogFragment.Callback,
        AccountSetupTypeFragment.Callback, AccountSetupNamesFragment.Callback,
        AccountSetupOptionsFragment.Callback, AccountSetupBasicsFragment.Callback,
        AccountServerBaseFragment.Callback, AccountSetupCredentialsFragment.Callback {

    // STOPSHIP: Set to false before shipping, logs PII
    private final static boolean ENTER_DEBUG_SCREEN = true;

    /**
     * Direct access for forcing account creation
     * For use by continuous automated test system (e.g. in conjunction with monkey tests)
     *
     * === Support for automated testing ==
     * This activity can also be launched directly via INTENT_CREATE_ACCOUNT.  This is intended
     * only for use by continuous test systems, and is currently only available when
     * {@link ActivityManager#isRunningInTestHarness()} is set.  To use this mode, you must
     * construct an intent which contains all necessary information to create the account.  No
     * connection checking is done, so the account may or may not actually work.  Here is a sample
     * command, for a gmail account "test_account" with a password of "test_password".
     *
     *      $ adb shell am start -a com.android.email.CREATE_ACCOUNT \
     *          -e EMAIL test_account@gmail.com \
     *          -e USER "Test Account Name" \
     *          -e INCOMING imap+ssl+://test_account:test_password@imap.gmail.com \
     *          -e OUTGOING smtp+ssl+://test_account:test_password@smtp.gmail.com
     *
     * Note: For accounts that require the full email address in the login, encode the @ as %40.
     * Note: Exchange accounts that require device security policies cannot be created
     * automatically.
     */
    private static String INTENT_CREATE_ACCOUNT;
    private static final String EXTRA_FLOW_MODE = "FLOW_MODE";
    private static final String EXTRA_FLOW_ACCOUNT_TYPE = "FLOW_ACCOUNT_TYPE";
    private static final String EXTRA_CREATE_ACCOUNT_EMAIL = "EMAIL";
    private static final String EXTRA_CREATE_ACCOUNT_USER = "USER";
    private static final String EXTRA_CREATE_ACCOUNT_INCOMING = "INCOMING";
    private static final String EXTRA_CREATE_ACCOUNT_OUTGOING = "OUTGOING";
    private static final Boolean DEBUG_ALLOW_NON_TEST_HARNESS_CREATION = false;

    protected static final String ACTION_JUMP_TO_INCOMING = "jumpToIncoming";
    protected static final String ACTION_JUMP_TO_OUTGOING = "jumpToOutgoing";
    protected static final String ACTION_JUMP_TO_OPTIONS = "jumpToOptions";

    private static final String SAVESTATE_KEY_IS_PROCESSING = "AccountSetupFinal.is_processing";
    private static final String SAVESTATE_KEY_STATE = "AccountSetupFinal.state";
    private static final String SAVESTATE_KEY_PROVIDER = "AccountSetupFinal.provider";
    private static final String SAVESTATE_KEY_AUTHENTICATOR_RESPONSE = "AccountSetupFinal.authResp";
    private static final String SAVESTATE_KEY_REPORT_AUTHENTICATOR_ERROR =
            "AccountSetupFinal.authErr";
    private static final String SAVESTATE_KEY_IS_PRE_CONFIGURED = "AccountSetupFinal.preconfig";
    private static final String SAVESTATE_KEY_SKIP_AUTO_DISCOVER = "AccountSetupFinal.noAuto";

    private static final String CONTENT_FRAGMENT_TAG = "AccountSetupContentFragment";

    // Collecting initial email and password
    private static final int STATE_BASICS = 0;
    // Collect initial password or oauth token
    private static final int STATE_CREDENTIALS = 1;
    // Show the user some interstitial message after credential entry
    private static final int STATE_CREDENTIALS_POST = 2;
    // User chose the "Manual Setup" button
    private static final int STATE_DIVERT_TO_MANUAL = 3;
    // Account is a pre-configured account, run the checker
    private static final int STATE_CHECKING_PRECONFIGURED = 4;
    // Account is not pre-configured, query user for account type
    private static final int STATE_TYPE = 5;
    // Auto-discovering exchange account info, possibly other protocols later
    private static final int STATE_AUTO_DISCOVER = 6;
    // User is entering incoming settings
    private static final int STATE_MANUAL_INCOMING = 7;
    // We're checking incoming settings
    private static final int STATE_CHECKING_INCOMING = 8;
    // User is entering outgoing settings
    private static final int STATE_MANUAL_OUTGOING = 9;
    // We're checking outgoing settings
    private static final int STATE_CHECKING_OUTGOING = 10;
    // User is entering sync options
    private static final int STATE_OPTIONS = 11;
    // We're creating the account
    private static final int STATE_CREATING = 12;
    // User is entering account name and real name
    private static final int STATE_NAMES = 13;
    // we're finalizing the account
    private static final int STATE_FINALIZE = 14;

    private int mState = STATE_BASICS;

    private boolean mIsProcessing = false;
    private boolean mForceCreate = false;
    private boolean mReportAccountAuthenticatorError;
    private AccountAuthenticatorResponse mAccountAuthenticatorResponse;
    // True if this provider is found in our providers.xml, set after Basics
    private boolean mIsPreConfiguredProvider = false;
    // True if the user selected manual setup
    private boolean mSkipAutoDiscover = false;
    // True if validating the pre-configured provider failed and we want manual setup
    private boolean mPreConfiguredFailed = false;

    private VendorPolicyLoader.Provider mProvider;

    // UI elements
    @VisibleForTesting
    protected ImageButton mNextButton;

    private static final int OWNER_NAME_LOADER_ID = 0;
    private String mOwnerName;

    private static final int EXISTING_ACCOUNTS_LOADER_ID = 1;
    private Map<String, String> mExistingAccountsMap;

    public static Intent actionNewAccountIntent(final Context context) {
        final Intent i = new Intent(context, AccountSetupFinal.class);
        i.putExtra(EXTRA_FLOW_MODE, SetupDataFragment.FLOW_MODE_NORMAL);
        return i;
    }

    public static Intent actionNewAccountWithResultIntent(final Context context) {
        final Intent i = new ForwardingIntent(context, AccountSetupFinal.class);
        i.putExtra(EXTRA_FLOW_MODE, SetupDataFragment.FLOW_MODE_NO_ACCOUNTS);
        return i;
    }

    public static Intent actionGetCreateAccountIntent(final Context context,
            final String accountManagerType) {
        final Intent i = new Intent(context, AccountSetupFinal.class);
        i.putExtra(EXTRA_FLOW_MODE, SetupDataFragment.FLOW_MODE_ACCOUNT_MANAGER);
        i.putExtra(EXTRA_FLOW_ACCOUNT_TYPE, accountManagerType);
        return i;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent intent = getIntent();
        final String action = intent.getAction();

        if (INTENT_CREATE_ACCOUNT == null) {
            INTENT_CREATE_ACCOUNT = getString(R.string.intent_create_account);
        }

        setContentView(R.layout.account_setup);

        if (savedInstanceState != null) {
            mIsProcessing = savedInstanceState.getBoolean(SAVESTATE_KEY_IS_PROCESSING, false);
            mState = savedInstanceState.getInt(SAVESTATE_KEY_STATE, STATE_OPTIONS);
            mProvider = (VendorPolicyLoader.Provider)
                    savedInstanceState.getSerializable(SAVESTATE_KEY_PROVIDER);
            mAccountAuthenticatorResponse =
                    savedInstanceState.getParcelable(SAVESTATE_KEY_AUTHENTICATOR_RESPONSE);
            mReportAccountAuthenticatorError =
                    savedInstanceState.getBoolean(SAVESTATE_KEY_REPORT_AUTHENTICATOR_ERROR);
            mIsPreConfiguredProvider =
                    savedInstanceState.getBoolean(SAVESTATE_KEY_IS_PRE_CONFIGURED);
            mSkipAutoDiscover = savedInstanceState.getBoolean(SAVESTATE_KEY_SKIP_AUTO_DISCOVER);
        } else {
            // If we're not restoring from a previous state, we want to configure the initial screen

            // Set aside incoming AccountAuthenticatorResponse, if there was any
            mAccountAuthenticatorResponse = getIntent()
                    .getParcelableExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE);
            if (mAccountAuthenticatorResponse != null) {
                // When this Activity is called as part of account authentification flow,
                // we are responsible for eventually reporting the result (success or failure) to
                // the account manager.  Most exit paths represent an failed or abandoned setup,
                // so the default is to report the error.  Success will be reported by the code in
                // AccountSetupOptions that commits the finally created account.
                mReportAccountAuthenticatorError = true;
            }

            // Initialize the SetupDataFragment
            if (INTENT_CREATE_ACCOUNT.equals(action)) {
                mSetupData.setFlowMode(SetupDataFragment.FLOW_MODE_FORCE_CREATE);
            } else {
                final int intentFlowMode = intent.getIntExtra(EXTRA_FLOW_MODE,
                        SetupDataFragment.FLOW_MODE_UNSPECIFIED);
                // TODO: do something with this
                final String flowAccountType = intent.getStringExtra(EXTRA_FLOW_ACCOUNT_TYPE);
                mSetupData.setFlowMode(intentFlowMode);
            }

            mState = STATE_BASICS;
            // Support unit testing individual screens
            if (TextUtils.equals(ACTION_JUMP_TO_INCOMING, action)) {
                mState = STATE_MANUAL_INCOMING;
            } else if (TextUtils.equals(ACTION_JUMP_TO_OUTGOING, action)) {
                mState = STATE_MANUAL_OUTGOING;
            } else if (TextUtils.equals(ACTION_JUMP_TO_OPTIONS, action)) {
                mState = STATE_OPTIONS;
            }
            updateHeadline();
            updateContentFragment(false /* addToBackstack */);
        }

        UiUtilities.getView(this, R.id.previous).setOnClickListener(this);
        mNextButton = UiUtilities.getView(this, R.id.next);
        mNextButton.setOnClickListener(this);

        if (!mIsProcessing
                && mSetupData.getFlowMode() == SetupDataFragment.FLOW_MODE_FORCE_CREATE) {
            getFragmentManager().executePendingTransactions();

            if (!DEBUG_ALLOW_NON_TEST_HARNESS_CREATION &&
                    !ActivityManager.isRunningInTestHarness()) {
                LogUtils.e(LogUtils.TAG,
                        "ERROR: Force account create only allowed while in test harness");
                finish();
                return;
            }

            mForceCreate = true;
        }

        // Launch a loader to look up the owner name.  It should be ready well in advance of
        // the time the user clicks next or manual.
        getLoaderManager().initLoader(OWNER_NAME_LOADER_ID, null,
                new LoaderManager.LoaderCallbacks<Cursor>() {
                    @Override
                    public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
                        return new CursorLoader(AccountSetupFinal.this,
                                ContactsContract.Profile.CONTENT_URI,
                                new String[] {ContactsContract.Profile.DISPLAY_NAME_PRIMARY},
                                null, null, null);
                    }

                    @Override
                    public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
                        if (data != null && data.moveToFirst()) {
                            mOwnerName = data.getString(data.getColumnIndex(
                                    ContactsContract.Profile.DISPLAY_NAME_PRIMARY));
                        }
                    }

                    @Override
                    public void onLoaderReset(final Loader<Cursor> loader) {}
                });

        // Launch a loader to cache some info about existing accounts so we can dupe-check against
        // them.
        getLoaderManager().initLoader(EXISTING_ACCOUNTS_LOADER_ID, null,
                new LoaderManager.LoaderCallbacks<Cursor> () {
                    @Override
                    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
                        return new CursorLoader(AccountSetupFinal.this, Account.CONTENT_URI,
                                new String[] {Account.EMAIL_ADDRESS, Account.DISPLAY_NAME},
                                null, null, null);
                    }

                    @Override
                    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
                        if (data == null) {
                            mExistingAccountsMap = null;
                            return;
                        }

                        mExistingAccountsMap = new HashMap<String, String>();

                        final int emailColumnIndex = data.getColumnIndex(Account.EMAIL_ADDRESS);
                        final int displayNameColumnIndex =
                                data.getColumnIndex(Account.DISPLAY_NAME);

                        while (data.moveToNext()) {
                            final String email = data.getString(emailColumnIndex);
                            final String displayName = data.getString(displayNameColumnIndex);
                            mExistingAccountsMap.put(email,
                                    TextUtils.isEmpty(displayName) ? email : displayName);
                        }
                    }

                    @Override
                    public void onLoaderReset(Loader<Cursor> loader) {
                        mExistingAccountsMap = null;
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mForceCreate) {
            try {
                mForceCreate = false;

                /**
                 * To support continuous testing, we allow the forced creation of accounts.
                 * This works in a manner fairly similar to automatic setup, in which the complete
                 * server Uri's are available, except that we will also skip checking (as if both
                 * checks were true) and all other UI.
                 *
                 * email: The email address for the new account
                 * user: The user name for the new account
                 * incoming: The URI-style string defining the incoming account
                 * outgoing: The URI-style string defining the outgoing account
                 */
                final Intent intent = getIntent();
                final String email = intent.getStringExtra(EXTRA_CREATE_ACCOUNT_EMAIL);
                final String user = intent.getStringExtra(EXTRA_CREATE_ACCOUNT_USER);
                final String incoming = intent.getStringExtra(EXTRA_CREATE_ACCOUNT_INCOMING);
                final String outgoing = intent.getStringExtra(EXTRA_CREATE_ACCOUNT_OUTGOING);
                if (TextUtils.isEmpty(email) || TextUtils.isEmpty(user) ||
                        TextUtils.isEmpty(incoming) || TextUtils.isEmpty(outgoing)) {
                    LogUtils.e(LogUtils.TAG, "ERROR: Force account create requires extras EMAIL, " +
                            "USER, INCOMING, OUTGOING");
                    finish();
                    return;
                }

                final Account account = mSetupData.getAccount();
                final HostAuth recvAuth = account.getOrCreateHostAuthRecv(this);
                recvAuth.setHostAuthFromString(incoming);

                final HostAuth sendAuth = account.getOrCreateHostAuthSend(this);
                sendAuth.setHostAuthFromString(outgoing);

                populateSetupData(user, email);

                // We need to do this after onCreate so that we can ensure that the fragment is
                // fully created before querying it.
                initiateAccountCreation();
            } catch (URISyntaxException e) {
                // If we can't set up the URL, don't continue
                Toast.makeText(this,
                        R.string.account_setup_username_password_toast, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SAVESTATE_KEY_IS_PROCESSING, mIsProcessing);
        outState.putInt(SAVESTATE_KEY_STATE, mState);
        outState.putSerializable(SAVESTATE_KEY_PROVIDER, mProvider);
        outState.putParcelable(SAVESTATE_KEY_AUTHENTICATOR_RESPONSE, mAccountAuthenticatorResponse);
        outState.putBoolean(SAVESTATE_KEY_REPORT_AUTHENTICATOR_ERROR,
                mReportAccountAuthenticatorError);
        outState.putBoolean(SAVESTATE_KEY_IS_PRE_CONFIGURED, mIsPreConfiguredProvider);
    }

    /**
     * Set the headline text according to mState.
     */
    private void updateHeadline() {
        TextView headlineView = UiUtilities.getView(this, R.id.headline);
        switch (mState) {
            case STATE_BASICS:
                headlineView.setText(R.string.account_setup_basics_headline);
                break;
            case STATE_CREDENTIALS:
                // TODO: real string
                headlineView.setText(R.string.sign_in_title);
                break;
            case STATE_TYPE:
                headlineView.setText(R.string.account_setup_account_type_headline);
                break;
            case STATE_MANUAL_INCOMING:
                headlineView.setText(R.string.account_setup_incoming_headline);
                break;
            case STATE_MANUAL_OUTGOING:
                headlineView.setText(R.string.account_setup_outgoing_headline);
                break;
            case STATE_OPTIONS:
                headlineView.setText(R.string.account_setup_options_headline);
                break;
            case STATE_NAMES:
                headlineView.setText(R.string.account_setup_names_headline);
                break;
            default:
                throw new IllegalStateException("Incorrect state" + mState);
        }
    }

    /**
     * Swap in the new fragment according to mState. This pushes the current fragment onto the back
     * stack, so only call it once per transition.
     */
    private void updateContentFragment(boolean addToBackstack) {
        final AccountSetupFragment f;
        switch (mState) {
            case STATE_BASICS:
                f = AccountSetupBasicsFragment.newInstance();
                break;
            case STATE_CREDENTIALS:
                f = AccountSetupCredentialsFragment.newInstance(mSetupData.getEmail(),
                        mSetupData.getAccount().getOrCreateHostAuthRecv(this).mProtocol);
                break;
            case STATE_TYPE:
                f = AccountSetupTypeFragment.newInstance();
                break;
            case STATE_MANUAL_INCOMING:
                f = AccountSetupIncomingFragment.newInstance(false);
                break;
            case STATE_MANUAL_OUTGOING:
                f = AccountSetupOutgoingFragment.newInstance(false);
                break;
            case STATE_OPTIONS:
                f = AccountSetupOptionsFragment.newInstance();
                break;
            case STATE_NAMES:
                f = AccountSetupNamesFragment.newInstance();
                break;
            default:
                throw new IllegalStateException("Incorrect state " + mState);
        }
        f.setState(mState);
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.replace(R.id.setup_fragment_container, f, CONTENT_FRAGMENT_TAG);
        if (addToBackstack) {
            ft.addToBackStack(null);
        }
        ft.commit();
    }

    /**
     * Retrieve the current content fragment
     * @return The content fragment or null if it wasn't found for some reason
     */
    private AccountSetupFragment getContentFragment() {
        return (AccountSetupFragment) getFragmentManager().findFragmentByTag(CONTENT_FRAGMENT_TAG);
    }

    /**
     * Main choreography function to handle moving forward through scenes. Moving back should be
     * generally handled for us by the back stack
     */
    protected void proceed() {
        mIsProcessing = false;
        setNextButtonEnabled(true);

        getFragmentManager().executePendingTransactions();

        switch (mState) {
            case STATE_BASICS:
                onBasicsComplete();
                mState = STATE_CREDENTIALS;
                updateHeadline();
                updateContentFragment(true /* addToBackstack */);
                break;
            case STATE_DIVERT_TO_MANUAL:
                mState = STATE_TYPE;
                updateHeadline();
                updateContentFragment(true /* addToBackstack */);
                break;
            case STATE_CREDENTIALS:
                final boolean advance = collectCredentials();
                if (!advance) {
                    mState = STATE_CREDENTIALS_POST;
                    break;
                } // else fall through
            case STATE_CREDENTIALS_POST:
                if (mIsPreConfiguredProvider) {
                    mState = STATE_CHECKING_PRECONFIGURED;
                    initiateCheckSettingsFragment(SetupDataFragment.CHECK_INCOMING
                            | SetupDataFragment.CHECK_OUTGOING);
                } else {
                    mState = STATE_TYPE;
                    updateHeadline();
                    updateContentFragment(true /* addToBackstack */);
                }
                break;
            case STATE_CHECKING_PRECONFIGURED:
                if (mPreConfiguredFailed) {
                    mState = STATE_MANUAL_INCOMING;
                    updateHeadline();
                    updateContentFragment(true /* addToBackstack */);
                } else {
                    mState = STATE_OPTIONS;
                    updateHeadline();
                    updateContentFragment(true /* addToBackstack */);
                }
                break;
            case STATE_TYPE:
                // We either got here through "Manual Setup" or because we didn't find the provider
                // In the former case, we skip auto-discover
                if (mSkipAutoDiscover) {
                    mState = STATE_MANUAL_INCOMING;
                    updateHeadline();
                    updateContentFragment(true /* addToBackstack */);
                } else {
                    mState = STATE_AUTO_DISCOVER;
                    initiateCheckSettingsFragment(SetupDataFragment.CHECK_AUTODISCOVER);
                }
                break;
            case STATE_AUTO_DISCOVER:
                // TODO: figure out if we can skip past manual setup
                mState = STATE_MANUAL_INCOMING;
                updateHeadline();
                updateContentFragment(true);
                break;
            case STATE_MANUAL_INCOMING:
                onIncomingComplete();
                mState = STATE_CHECKING_INCOMING;
                initiateCheckSettingsFragment(SetupDataFragment.CHECK_INCOMING);
                break;
            case STATE_CHECKING_INCOMING:
                final EmailServiceUtils.EmailServiceInfo serviceInfo =
                        EmailServiceUtils.getServiceInfo(this,
                                getSetupData().getAccount().getProtocol(this));
                if (serviceInfo.usesSmtp) {
                    mState = STATE_MANUAL_OUTGOING;
                } else {
                    mState = STATE_OPTIONS;
                }
                updateHeadline();
                updateContentFragment(true /* addToBackstack */);
                break;
            case STATE_MANUAL_OUTGOING:
                onOutgoingComplete();
                mState = STATE_CHECKING_OUTGOING;
                initiateCheckSettingsFragment(SetupDataFragment.CHECK_OUTGOING);
                break;
            case STATE_CHECKING_OUTGOING:
                mState = STATE_OPTIONS;
                updateHeadline();
                updateContentFragment(true /* addToBackstack */);
                break;
            case STATE_OPTIONS:
                mState = STATE_CREATING;
                initiateAccountCreation();
                break;
            case STATE_CREATING:
                mState = STATE_NAMES;
                updateHeadline();
                updateContentFragment(true /* addToBackstack */);
                if (mSetupData.getFlowMode() == SetupDataFragment.FLOW_MODE_FORCE_CREATE) {
                    getFragmentManager().executePendingTransactions();
                    initiateAccountFinalize();
                }
                break;
            case STATE_NAMES:
                initiateAccountFinalize();
                break;
            case STATE_FINALIZE:
                finish();
                break;
            default:
                LogUtils.wtf(LogUtils.TAG, "Unknown state %d", mState);
                break;
        }
    }

    /**
     * Block the back key if we are currently processing the "next" key"
     */
    @Override
    public void onBackPressed() {
        if (mIsProcessing) {
            return;
        }
        if (mState == STATE_NAMES) {
            finish();
        } else {
            super.onBackPressed();
        }
        // After super.onBackPressed() our fragment should be in place, so query the state we
        // installed it for
        AccountSetupFragment f = getContentFragment();
        mState = f.getState();
        updateHeadline();
    }

    @Override
    public void setAccount(Account account) {
        mSetupData.setAccount(account);
    }

    @Override
    public void finish() {
        // If the account manager initiated the creation, and success was not reported,
        // then we assume that we're giving up (for any reason) - report failure.
        if (mReportAccountAuthenticatorError) {
            if (mAccountAuthenticatorResponse != null) {
                mAccountAuthenticatorResponse
                        .onError(AccountManager.ERROR_CODE_CANCELED, "canceled");
                mAccountAuthenticatorResponse = null;
            }
        }
        super.finish();
    }

    /**
     * Respond to clicks in the "Next" or "Previous" buttons
     */
    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.next:
                // Some states are handled without UI, block double-presses here
                if (!mIsProcessing) {
                    proceed();
                }
                break;
            case R.id.previous:
                onBackPressed();
                break;
        }
    }

    public void setNextButtonEnabled(final boolean enabled) {
        mNextButton.setEnabled(enabled);
    }

    @Override
    public void onBasicsManualSetupButton() {
        final AccountSetupBasicsFragment basicsFragment =
                (AccountSetupBasicsFragment) getContentFragment();
        final String email = basicsFragment.getEmail();
        mSetupData.setEmail(email);

        // Alternate entry to the debug options screen (for devices without a physical keyboard:
        //  Username: d@d.d
        if (ENTER_DEBUG_SCREEN) {
            if ("d@d.d".equals(mSetupData.getEmail())) {
                AccountSettings.actionSettingsWithDebug(this);
                return;
            }
        }
        mState = STATE_DIVERT_TO_MANUAL;
        mSkipAutoDiscover = true;
        proceed();
    }

    private void onBasicsComplete() {
        final AccountSetupBasicsFragment f = (AccountSetupBasicsFragment) getContentFragment();
        final String email = f.getEmail();
        mSetupData.setEmail(email);
    }

    @Override
    public void onCredentialsComplete(Bundle results) {
        proceed();
    }

    /**
     *
     * @return true to proceed, false to remain on the current screen
     */
    private boolean collectCredentials() {
        final AccountSetupCredentialsFragment f = (AccountSetupCredentialsFragment)
                getContentFragment();
        final Bundle results = f.getCredentialResults();
        mSetupData.setCredentialResults(results);
        final String email = mSetupData.getEmail();
        final String[] emailParts = email.split("@");
        final String domain = emailParts[1].trim();
        mProvider = AccountSettingsUtils.findProviderForDomain(this, domain);
        if (mProvider != null) {
            mProvider.expandTemplates(email);
            mIsPreConfiguredProvider = true;
            if (mProvider.note != null) {
                final AccountSetupNoteDialogFragment dialogFragment =
                        AccountSetupNoteDialogFragment.newInstance(mProvider.note);
                dialogFragment.show(getFragmentManager(), AccountSetupNoteDialogFragment.TAG);
                return false;
            } else {
                return finishAutoSetup();
            }
        } else {
            // Can't use auto setup (although EAS accounts may still be able to AutoDiscover)
            mIsPreConfiguredProvider = false;
            final String existingAccountName = mExistingAccountsMap.get(email);
            if (!TextUtils.isEmpty(existingAccountName)) {
                final DuplicateAccountDialogFragment dialogFragment =
                        DuplicateAccountDialogFragment.newInstance(existingAccountName);
                dialogFragment.show(getFragmentManager(), DuplicateAccountDialogFragment.TAG);
                return false;
            } else {
                populateSetupData(mOwnerName, email);
                mSkipAutoDiscover = false;
                return true;
            }
        }
    }

    @Override
    public void onNoteDialogComplete() {
        finishAutoSetup();
    }

    /**
     * Finish the auto setup process, in some cases after showing a warning dialog.
     * Happens after onBasicsComplete
     * @return true to proceed, false to remain on the current screen
     */
    private boolean finishAutoSetup() {
        final String email = mSetupData.getEmail();

        try {
            mProvider.expandTemplates(email);

            final Account account = mSetupData.getAccount();
            final HostAuth recvAuth = account.getOrCreateHostAuthRecv(this);
            recvAuth.setHostAuthFromString(mProvider.incomingUri);
            recvAuth.setUserName(mProvider.incomingUsername);
            AccountSetupCredentialsFragment.populateHostAuthWithResults(this, recvAuth,
                    mSetupData.getCredentialResults());

            final EmailServiceUtils.EmailServiceInfo info = EmailServiceUtils.getServiceInfo(this,
                    recvAuth.mProtocol);
            recvAuth.mPort =
                    ((recvAuth.mFlags & HostAuth.FLAG_SSL) != 0) ? info.portSsl : info.port;

            final HostAuth sendAuth = account.getOrCreateHostAuthSend(this);
            sendAuth.setHostAuthFromString(mProvider.outgoingUri);
            sendAuth.setUserName(mProvider.outgoingUsername);
            AccountSetupCredentialsFragment.populateHostAuthWithResults(this, sendAuth,
                    mSetupData.getCredentialResults());

            // Populate the setup data, assuming that the duplicate account check will succeed
            populateSetupData(mOwnerName, email);

            final String duplicateAccountName =
                    mExistingAccountsMap != null ? mExistingAccountsMap.get(email) : null;
            if (duplicateAccountName != null) {
                final DuplicateAccountDialogFragment dialogFragment =
                        DuplicateAccountDialogFragment.newInstance(duplicateAccountName);
                dialogFragment.show(getFragmentManager(), DuplicateAccountDialogFragment.TAG);
                return false;
            }
        } catch (URISyntaxException e) {
            mSkipAutoDiscover = false;
            mPreConfiguredFailed = true;
        }
        return true;
    }


    /**
     * Helper method to fill in some per-protocol defaults
     * @param account Account object to fill in
     */
    public void setDefaultsForProtocol(Account account) {
        final String protocol = account.getOrCreateHostAuthRecv(this).mProtocol;
        if (protocol == null) return;
        final EmailServiceUtils.EmailServiceInfo info =
                EmailServiceUtils.getServiceInfo(this, protocol);
        account.mSyncInterval = info.defaultSyncInterval;
        account.mSyncLookback = info.defaultLookback;
        if (info.offerLocalDeletes) {
            account.setDeletePolicy(info.defaultLocalDeletes);
        }
    }

    /**
     * Populate SetupData's account with complete setup info, assumes that the receive auth is
     * created and its protocol is set
     */
    private void populateSetupData(String senderName, String senderEmail) {
        final Account account = mSetupData.getAccount();
        account.setSenderName(senderName);
        account.setEmailAddress(senderEmail);
        account.setDisplayName(senderEmail);
        setDefaultsForProtocol(account);
    }

    private void onIncomingComplete() {
        AccountSetupIncomingFragment f = (AccountSetupIncomingFragment) getContentFragment();
        f.collectUserInput();
    }

    private void onOutgoingComplete() {
        AccountSetupOutgoingFragment f = (AccountSetupOutgoingFragment) getContentFragment();
        f.collectUserInput();
    }

    // This callback method is only applicable to using Incoming/Outgoing fragments in settings mode
    @Override
    public void onAccountServerUIComplete(int checkMode) {}

    // This callback method is only applicable to using Incoming/Outgoing fragments in settings mode
    @Override
    public void onAccountServerSaveComplete() {}

    private void initiateCheckSettingsFragment(int checkMode) {
        final Fragment f = AccountCheckSettingsFragment.newInstance(checkMode);
        final Fragment d = CheckSettingsProgressDialogFragment.newInstance(checkMode);
        getFragmentManager().beginTransaction()
                .add(f, AccountCheckSettingsFragment.TAG)
                .add(d, CheckSettingsProgressDialogFragment.TAG)
                .commit();
    }

    @Override
    public void onCheckSettingsProgressDialogCancel() {
        dismissCheckSettingsFragment();
    }

    private void dismissCheckSettingsFragment() {
        final Fragment f = getFragmentManager().findFragmentByTag(AccountCheckSettingsFragment.TAG);
        final Fragment d =
                getFragmentManager().findFragmentByTag(CheckSettingsProgressDialogFragment.TAG);
        getFragmentManager().beginTransaction()
                .remove(f)
                .remove(d)
                .commit();
    }

    @Override
    public void onCheckSettingsError(int reason, String message) {
        dismissCheckSettingsFragment();
        final DialogFragment f =
                CheckSettingsErrorDialogFragment.newInstance(reason, message);
        f.show(getFragmentManager(), CheckSettingsErrorDialogFragment.TAG);
    }

    @Override
    public void onCheckSettingsErrorDialogEditCertificate() {
        if (mState == STATE_CHECKING_PRECONFIGURED) {
            mPreConfiguredFailed = true;
            proceed();
        }
        final AccountSetupIncomingFragment f = (AccountSetupIncomingFragment) getContentFragment();
        f.onCertificateRequested();
    }

    @Override
    public void onCheckSettingsErrorDialogEditSettings() {
        // If we're checking pre-configured, set a flag that we failed and navigate fowards to
        // incoming settings
        if (mState == STATE_CHECKING_PRECONFIGURED || mState == STATE_AUTO_DISCOVER) {
            mPreConfiguredFailed = true;
            proceed();
        }
    }

    @Override
    public void onCheckSettingsComplete() {
        dismissCheckSettingsFragment();
        proceed();
    }

    @Override
    public void onCheckSettingsAutoDiscoverComplete(int result) {
        dismissCheckSettingsFragment();
        proceed();
    }

    @Override
    public void onCheckSettingsSecurityRequired(String hostName) {
        dismissCheckSettingsFragment();
        final DialogFragment f = SecurityRequiredDialogFragment.newInstance(hostName);
        f.show(getFragmentManager(), SecurityRequiredDialogFragment.TAG);
    }

    @Override
    public void onSecurityRequiredDialogResult(boolean ok) {
        if (ok) {
            proceed();
        }
    }

    @Override
    public void onChooseProtocol(String protocol) {
        final Account account = mSetupData.getAccount();
        final HostAuth recvAuth = account.getOrCreateHostAuthRecv(this);
        recvAuth.setConnection(protocol, recvAuth.mAddress, recvAuth.mPort, recvAuth.mFlags);

        recvAuth.mLogin = recvAuth.mLogin + "@" + recvAuth.mAddress;
        setDefaultsForProtocol(account);
        proceed();
    }

    /**
     * Ths is called when the user clicks the "done" button.
     * It collects the data from the UI, updates the setup account record, and launches a fragment
     * which handles creating the account in the system and database.
     */
    private void initiateAccountCreation() {
        mIsProcessing = true;
        setNextButtonEnabled(false);

        final Account account = mSetupData.getAccount();
        if (account.mHostAuthRecv == null) {
            throw new IllegalStateException("in AccountSetupOptions with null mHostAuthRecv");
        }

        final AccountSetupOptionsFragment fragment = (AccountSetupOptionsFragment)
                getContentFragment();
        if (fragment == null) {
            throw new IllegalStateException("Fragment missing!");
        }

        account.setDisplayName(account.getEmailAddress());
        int newFlags = account.getFlags() & ~(Account.FLAGS_BACKGROUND_ATTACHMENTS);
        final EmailServiceUtils.EmailServiceInfo serviceInfo =
                EmailServiceUtils.getServiceInfo(getApplicationContext(),
                        account.mHostAuthRecv.mProtocol);
        if (serviceInfo.offerAttachmentPreload && fragment.getBackgroundAttachmentsValue()) {
            newFlags |= Account.FLAGS_BACKGROUND_ATTACHMENTS;
        }
        account.setFlags(newFlags);
        account.setSyncInterval(fragment.getCheckFrequencyValue());
        final Integer syncWindowValue = fragment.getAccountSyncWindowValue();
        if (syncWindowValue != null) {
            account.setSyncLookback(syncWindowValue);
        }

        // Finish setting up the account, and commit it to the database
        if (mSetupData.getPolicy() != null) {
            account.mFlags |= Account.FLAGS_SECURITY_HOLD;
            account.mPolicy = mSetupData.getPolicy();
        }

        // Finally, write the completed account (for the first time) and then
        // install it into the Account manager as well.  These are done off-thread.
        // The account manager will report back via the callback, which will take us to
        // the next operations.
        final boolean syncEmail = fragment.getSyncEmailValue();
        final boolean syncCalendar = serviceInfo.syncCalendar && fragment.getSyncCalendarValue();
        final boolean syncContacts = serviceInfo.syncContacts && fragment.getSyncContactsValue();
        final boolean enableNotifications = fragment.getNotifyValue();

        final Fragment f = AccountCreationFragment.newInstance(account, syncEmail, syncCalendar,
                syncContacts, enableNotifications);
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.add(f, AccountCreationFragment.TAG);
        ft.commit();

        showCreateAccountDialog();
    }

    /**
     * Called by the account creation fragment after it has completed.
     * We do a small amount of work here before moving on to the next state.
     */
    public void onAccountCreationFragmentComplete() {
        destroyAccountCreationFragment();
        // If the account manager initiated the creation, and success was not reported,
        // then we assume that we're giving up (for any reason) - report failure.
        if (mAccountAuthenticatorResponse != null) {
            final Account account = getSetupData().getAccount();
            final EmailServiceUtils.EmailServiceInfo info =
                    EmailServiceUtils.getServiceInfo(this, account.getProtocol(this));
            final Bundle b = new Bundle(2);
            b.putString(AccountManager.KEY_ACCOUNT_NAME, account.getEmailAddress());
            b.putString(AccountManager.KEY_ACCOUNT_TYPE, info.accountType);
            mAccountAuthenticatorResponse.onResult(b);
            mAccountAuthenticatorResponse = null;
            mReportAccountAuthenticatorError = false;
        }
        proceed();
    }

    public void destroyAccountCreationFragment() {
        dismissCreateAccountDialog();

        final Fragment f = getFragmentManager().findFragmentByTag(AccountCreationFragment.TAG);
        if (f == null) {
            LogUtils.wtf(LogUtils.TAG, "Couldn't find AccountCreationFragment to destroy");
        }
        getFragmentManager().beginTransaction()
                .remove(f)
                .commit();
    }


    public static class CreateAccountDialogFragment extends DialogFragment {
        public static final String TAG = "CreateAccountDialogFragment";
        public CreateAccountDialogFragment() {}

        public static CreateAccountDialogFragment newInstance() {
            return new CreateAccountDialogFragment();
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            /// Show "Creating account..." dialog
            final ProgressDialog d = new ProgressDialog(getActivity());
            d.setIndeterminate(true);
            d.setMessage(getString(R.string.account_setup_creating_account_msg));
            return d;
        }
    }

    protected void showCreateAccountDialog() {
        CreateAccountDialogFragment.newInstance()
                .show(getFragmentManager(), CreateAccountDialogFragment.TAG);
    }

    protected void dismissCreateAccountDialog() {
        final DialogFragment f = (DialogFragment)
                getFragmentManager().findFragmentByTag(CreateAccountDialogFragment.TAG);
        if (f != null) {
            f.dismiss();
        }
    }

    public static class CreateAccountErrorDialogFragment extends DialogFragment
            implements DialogInterface.OnClickListener {
        public CreateAccountErrorDialogFragment() {}

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final String message = getString(R.string.account_setup_failed_dlg_auth_message,
                    R.string.system_account_create_failed);

            return new AlertDialog.Builder(getActivity())
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setTitle(getString(R.string.account_setup_failed_dlg_title))
                    .setMessage(message)
                    .setCancelable(true)
                    .setPositiveButton(
                            getString(R.string.account_setup_failed_dlg_edit_details_action), this)
                    .create();
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            getActivity().finish();
        }
    }

    /**
     * This is called if MailService.setupAccountManagerAccount() fails for some reason
     */
    public void showCreateAccountErrorDialog() {
        new CreateAccountErrorDialogFragment().show(getFragmentManager(), null);
    }

    /**
     * Collect the data from AccountSetupNames and finish up account creation
     */
    private void initiateAccountFinalize() {
        mIsProcessing = true;
        setNextButtonEnabled(false);

        AccountSetupNamesFragment fragment = (AccountSetupNamesFragment) getContentFragment();
        // Update account object from UI
        final Account account = mSetupData.getAccount();
        final String description = fragment.getDescription();
        if (!TextUtils.isEmpty(description)) {
            account.setDisplayName(description);
        }
        account.setSenderName(fragment.getSenderName());

        final Fragment f = AccountFinalizeFragment.newInstance(account);
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.add(f, AccountFinalizeFragment.TAG);
        ft.commit();
    }

    /**
     * Called when the AccountFinalizeFragment has finished its tasks
     */
    @Override
    public void onAccountFinalizeFragmentComplete() {
        finish();
    }
}
