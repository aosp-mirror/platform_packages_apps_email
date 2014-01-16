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
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.LoaderManager;
import android.app.ProgressDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.email.provider.AccountBackupRestore;
import com.android.email.service.EmailServiceUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.mail.ui.MailAsyncTaskLoader;
import com.android.mail.utils.LogUtils;

public class AccountSetupFinal extends AccountSetupActivity implements View.OnClickListener {
    private static final String SAVESTATE_IS_PROCESSING_KEY =
            "com.android.email.AccountSetupFinal.is_processing";
    private static final String SAVESTATE_STATE = "com.android.email.AccountSetupFinal.state";

    private static final String ACCOUNT_CREATION_FRAGMENT_TAG = "AccountCreationFragment";
    private static final String ACCOUNT_FINALIZE_FRAGMENT_TAG = "AccountFinalizeFragment";

    private static final String CREATE_ACCOUNT_DIALOG_TAG = "CreateAccountDialog";

    private static final String CONTENT_FRAGMENT_TAG = "AccountSetupContentFragment";

    private static final int STATE_OPTIONS = 0;
    private static final int STATE_NAMES = 1;
    private int mState = STATE_OPTIONS;

    private boolean mIsProcessing = false;

    private Button mNextButton;

    public static void actionFinal(Activity fromActivity, SetupDataFragment setupData) {
        final Intent intent = new ForwardingIntent(fromActivity, AccountSetupFinal.class);
        intent.putExtra(SetupDataFragment.EXTRA_SETUP_DATA, setupData);
        fromActivity.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.account_setup);

        if (savedInstanceState != null) {
            mIsProcessing = savedInstanceState.getBoolean(SAVESTATE_IS_PROCESSING_KEY, false);
            mState = savedInstanceState.getInt(SAVESTATE_STATE, STATE_OPTIONS);
        } else {
            // If we're not restoring from a previous state, we want to configure the initial screen
            mState = STATE_OPTIONS;
            updateHeadline();
            updateContentFragment();
        }

        UiUtilities.getView(this, R.id.previous).setOnClickListener(this);
        mNextButton = UiUtilities.getView(this, R.id.next);
        mNextButton.setOnClickListener(this);

        if (!mIsProcessing
                && mSetupData.getFlowMode() == SetupDataFragment.FLOW_MODE_FORCE_CREATE) {
            // If we are just visiting here to fill in details, exit immediately
            getFragmentManager().executePendingTransactions();
            initiateAccountCreation();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SAVESTATE_IS_PROCESSING_KEY, mIsProcessing);
    }

    /**
     * Set the headline text according to mState.
     */
    private void updateHeadline() {
        TextView headlineView = UiUtilities.getView(this, R.id.headline);
        switch (mState) {
            case STATE_OPTIONS:
                headlineView.setText(R.string.account_setup_options_headline);
                break;
            case STATE_NAMES:
                headlineView.setText(R.string.account_setup_names_headline);
                break;
        }
    }

    /**
     * Swap in the new fragment according to mState. This pushes the current fragment onto the back
     * stack, so only call it once per transition.
     */
    private void updateContentFragment() {
        final Fragment f;
        switch (mState) {
            case STATE_OPTIONS:
                f = new AccountSetupOptionsFragment();
                break;
            case STATE_NAMES:
                f = new AccountSetupNamesFragment();
                break;
            default:
                throw new IllegalStateException("Unknown state " + mState);
        }
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.replace(R.id.setup_fragment_container, f, CONTENT_FRAGMENT_TAG);
        ft.addToBackStack(null);
        ft.commit();
    }

    /**
     * Retrieve the current content fragment
     * @return The content fragment or null if it wasn't found for some reason
     */
    private Fragment getContentFragment() {
        return getFragmentManager().findFragmentByTag(CONTENT_FRAGMENT_TAG);
    }

    /**
     * Main choreography function to handle moving forward through scenes. Moving back should be
     * generally handled for us by the back stack
     */
    protected void proceed() {
        mIsProcessing = false;
        setNextButtonEnabled(true);

        switch (mState) {
            case STATE_OPTIONS:
                mState = STATE_NAMES;
                updateHeadline();
                updateContentFragment();
                if (mSetupData.getFlowMode() == SetupDataFragment.FLOW_MODE_FORCE_CREATE) {
                    getFragmentManager().executePendingTransactions();
                    initiateAccountFinalize();
                }
                break;
            case STATE_NAMES:
                finishActivity();
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
            finishActivity();
        } else {
            super.onBackPressed();
        }
    }

    private void finishActivity() {
        if (mSetupData.getFlowMode() == SetupDataFragment.FLOW_MODE_NO_ACCOUNTS) {
            AccountSetupBasics.actionAccountCreateFinishedWithResult(this);
        } else if (mSetupData.getFlowMode() != SetupDataFragment.FLOW_MODE_NORMAL) {
            AccountSetupBasics.actionAccountCreateFinishedAccountFlow(this);
        } else {
            final Account account = mSetupData.getAccount();
            if (account != null) {
                AccountSetupBasics.actionAccountCreateFinished(this, account);
            }
        }
        finish();
    }

    /**
     * Respond to clicks in the "Next" or "Previous" buttons
     */
    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.next:
                // Debounce touches
                if (!mIsProcessing) {
                    switch (mState) {
                        case STATE_OPTIONS:
                            initiateAccountCreation();
                            break;
                        case STATE_NAMES:
                            initiateAccountFinalize();
                            break;
                    }
                    setNextButtonEnabled(false);
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

    /**
     * Ths is called when the user clicks the "done" button.
     * It collects the data from the UI, updates the setup account record, and launches a fragment
     * which handles creating the account in the system and database.
     */
    private void initiateAccountCreation() {
        mIsProcessing = true;

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
        ft.add(f, ACCOUNT_CREATION_FRAGMENT_TAG);
        ft.commit();

        showCreateAccountDialog();
    }

    /**
     * Called by the account creation fragment after it has completed.
     * We do a small amount of work here before moving on to the next state.
     */
    public void proceedFromAccountCreationFragment() {
        destroyAccountCreationFragment();
        // If the account manager initiated the creation, and success was not reported,
        // then we assume that we're giving up (for any reason) - report failure.
        final AccountAuthenticatorResponse authenticatorResponse =
                mSetupData.getAccountAuthenticatorResponse();
        if (authenticatorResponse != null) {
            authenticatorResponse.onError(AccountManager.ERROR_CODE_CANCELED, "canceled");
            mSetupData.setAccountAuthenticatorResponse(null);
        }
        proceed();
    }

    public void destroyAccountCreationFragment() {
        dismissCreateAccountDialog();

        final Fragment f = getFragmentManager().findFragmentByTag(ACCOUNT_CREATION_FRAGMENT_TAG);
        if (f == null) {
            LogUtils.wtf(LogUtils.TAG, "Couldn't find AccountCreationFragment to destroy");
        }
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.remove(f);
        ft.commit();
    }


    public static class CreateAccountDialogFragment extends DialogFragment {
        CreateAccountDialogFragment() {}

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
        new CreateAccountDialogFragment().show(getFragmentManager(), CREATE_ACCOUNT_DIALOG_TAG);
    }

    protected void dismissCreateAccountDialog() {
        final DialogFragment f = (DialogFragment)
                getFragmentManager().findFragmentByTag(CREATE_ACCOUNT_DIALOG_TAG);
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
    protected void showCreateAccountErrorDialog() {
        new CreateAccountErrorDialogFragment().show(getFragmentManager(), null);
    }

    private void initiateAccountFinalize() {
        mIsProcessing = true;

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
        ft.add(f, ACCOUNT_FINALIZE_FRAGMENT_TAG);
        ft.commit();
    }


    private static class AccountFinalizeFragment extends Fragment {
        private static final String ACCOUNT_TAG = "account";

        private static final int FINAL_ACCOUNT_TASK_LOADER_ID = 0;

        private Context mAppContext;

        public static AccountFinalizeFragment newInstance(Account account) {
            final AccountFinalizeFragment f = new AccountFinalizeFragment();
            final Bundle args = new Bundle(1);
            args.putParcelable(ACCOUNT_TAG, account);
            f.setArguments(args);
            return f;
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            mAppContext = getActivity().getApplicationContext();

            setRetainInstance(true);
        }

        @Override
        public void onResume() {
            super.onResume();

            getLoaderManager().initLoader(FINAL_ACCOUNT_TASK_LOADER_ID, getArguments(),
                    new LoaderManager.LoaderCallbacks<Boolean>() {
                        @Override
                        public Loader<Boolean> onCreateLoader(int id, Bundle args) {
                            final Account accountArg = args.getParcelable(ACCOUNT_TAG);
                            return new FinalSetupTaskLoader(mAppContext, accountArg);
                        }

                        @Override
                        public void onLoadFinished(Loader<Boolean> loader, Boolean success) {
                            if (success && isResumed()) {
                                AccountSetupFinal activity = (AccountSetupFinal) getActivity();
                                activity.finishActivity();
                            }
                        }

                        @Override
                        public void onLoaderReset(Loader<Boolean> loader) {
                        }
                    });
        }

        /**
         * Final account setup work is handled in this Loader:
         *   Commit final values to provider
         *   Trigger account backup
         */
        private static class FinalSetupTaskLoader extends MailAsyncTaskLoader<Boolean> {

            private final Account mAccount;

            public FinalSetupTaskLoader(Context context, Account account) {
                super(context);
                mAccount = account;
            }

            Account getAccount() {
                return mAccount;
            }

            @Override
            public Boolean loadInBackground() {
                // Update the account in the database
                final ContentValues cv = new ContentValues();
                cv.put(EmailContent.AccountColumns.DISPLAY_NAME, mAccount.getDisplayName());
                cv.put(EmailContent.AccountColumns.SENDER_NAME, mAccount.getSenderName());
                mAccount.update(getContext(), cv);

                // Update the backup (side copy) of the accounts
                AccountBackupRestore.backup(getContext());

                return true;
            }

            @Override
            protected void onDiscardResult(Boolean result) {}
        }
    }
}
