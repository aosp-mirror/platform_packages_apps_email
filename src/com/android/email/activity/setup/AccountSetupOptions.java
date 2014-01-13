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

import android.accounts.AccountAuthenticatorResponse;
import android.accounts.AccountManager;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.app.LoaderManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.view.View;
import android.view.View.OnClickListener;

import com.android.email.R;
import com.android.email.activity.ActivityHelper;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.mail.preferences.AccountPreferences;
import com.android.mail.ui.MailAsyncTaskLoader;
import com.android.mail.utils.LogUtils;

import java.io.IOException;

public class AccountSetupOptions extends AccountSetupActivity implements OnClickListener {
    private static final String EXTRA_IS_PROCESSING_KEY = "com.android.email.is_processing";
    private static final String ACCOUNT_FINALIZE_FRAGMENT_TAG = "AccountFinalizeFragment";

    private boolean mDonePressed = false;
    private boolean mIsProcessing = false;

    public static final int REQUEST_CODE_ACCEPT_POLICIES = 1;


    public static void actionOptions(Activity fromActivity, SetupDataFragment setupData) {
        final Intent intent = new ForwardingIntent(fromActivity, AccountSetupOptions.class);
        intent.putExtra(SetupDataFragment.EXTRA_SETUP_DATA, setupData);
        fromActivity.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.account_setup_options);

        UiUtilities.getView(this, R.id.previous).setOnClickListener(this);
        UiUtilities.getView(this, R.id.next).setOnClickListener(this);



        mIsProcessing = savedInstanceState != null &&
                savedInstanceState.getBoolean(EXTRA_IS_PROCESSING_KEY, false);
        if (mIsProcessing) {
            // We are already processing, so just show the dialog until we finish
            showCreateAccountDialog();
        } else if (mSetupData.getFlowMode() == SetupDataFragment.FLOW_MODE_FORCE_CREATE) {
            // If we are just visiting here to fill in details, exit immediately
            onDone();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(EXTRA_IS_PROCESSING_KEY, mIsProcessing);
    }

    @Override
    public void finish() {
        // If the account manager initiated the creation, and success was not reported,
        // then we assume that we're giving up (for any reason) - report failure.
        final AccountAuthenticatorResponse authenticatorResponse =
                mSetupData.getAccountAuthenticatorResponse();
        if (authenticatorResponse != null) {
            authenticatorResponse.onError(AccountManager.ERROR_CODE_CANCELED, "canceled");
            mSetupData.setAccountAuthenticatorResponse(null);
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
                // Don't allow this more than once (Exchange accounts call an async method
                // before finish()'ing the Activity, which allows this code to potentially be
                // executed multiple times
                if (!mDonePressed) {
                    onDone();
                    mDonePressed = true;
                }
                break;
            case R.id.previous:
                onBackPressed();
                break;
        }
    }

    /**
     * Ths is called when the user clicks the "done" button.
     * It collects the data from the UI, updates the setup account record, and commits
     * the account to the database (making it real for the first time.)
     * Finally, we call setupAccountManagerAccount(), which will eventually complete via callback.
     */
    private void onDone() {
        final Account account = mSetupData.getAccount();
        if (account.isSaved()) {
            // Disrupting the normal flow could get us here, but if the account is already
            // saved, we've done this work
            return;
        } else if (account.mHostAuthRecv == null) {
            throw new IllegalStateException("in AccountSetupOptions with null mHostAuthRecv");
        }

        final AccountSetupOptionsFragment fragment = (AccountSetupOptionsFragment)
                getFragmentManager().findFragmentById(R.id.options_fragment);
        if (fragment == null) {
            throw new IllegalStateException("Fragment missing!");
        }

        mIsProcessing = true;
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
        // Set the incomplete flag here to avoid reconciliation issues in ExchangeService
        account.mFlags |= Account.FLAGS_INCOMPLETE;
        if (mSetupData.getPolicy() != null) {
            account.mFlags |= Account.FLAGS_SECURITY_HOLD;
            account.mPolicy = mSetupData.getPolicy();
        }

        // Finally, write the completed account (for the first time) and then
        // install it into the Account manager as well.  These are done off-thread.
        // The account manager will report back via the callback, which will take us to
        // the next operations.
        final Bundle args = new Bundle(5);
        args.putParcelable(AccountFinalizeFragment.ACCOUNT_TAG, account);
        args.putBoolean(AccountFinalizeFragment.SYNC_EMAIL_TAG, fragment.getSyncEmailValue());
        final boolean calendar = serviceInfo.syncCalendar && fragment.getSyncCalendarValue();
        args.putBoolean(AccountFinalizeFragment.SYNC_CALENDAR_TAG, calendar);
        final boolean contacts = serviceInfo.syncContacts && fragment.getSyncContactsValue();
        args.putBoolean(AccountFinalizeFragment.SYNC_CONTACTS_TAG, contacts);
        args.putBoolean(AccountFinalizeFragment.NOTIFICATIONS_TAG, fragment.getNotifyValue());

        final Fragment f = new AccountFinalizeFragment();
        f.setArguments(args);
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.add(f, ACCOUNT_FINALIZE_FRAGMENT_TAG);
        ft.commit();

        showCreateAccountDialog();
    }

    public void destroyAccountFinalizeFragment() {
        final Fragment f = getFragmentManager().findFragmentByTag(ACCOUNT_FINALIZE_FRAGMENT_TAG);
        if (f == null) {
            LogUtils.wtf(LogUtils.TAG, "Couldn't find AccountFinalizeFragment to destroy");
        }
        final FragmentTransaction ft = getFragmentManager().beginTransaction();
        ft.remove(f);
        ft.commit();
    }

    /**
     * This retained headless fragment acts as a container for the multi-step task of creating the
     * AccountManager account and saving our account object to the database, as well as some misc
     * related background tasks.
     *
     * TODO: move this to a separate file, probably
     */
    public static class AccountFinalizeFragment extends Fragment {
        public static final String ACCOUNT_TAG = "account";
        public static final String SYNC_EMAIL_TAG = "email";
        public static final String SYNC_CALENDAR_TAG = "calendar";
        public static final String SYNC_CONTACTS_TAG = "contacts";
        public static final String NOTIFICATIONS_TAG = "notifications";

        private static final String SAVESTATE_STAGE = "AccountFinalizeFragment.stage";
        private static final int STAGE_BEFORE_ACCOUNT_SECURITY = 0;
        private static final int STAGE_REFRESHING_ACCOUNT = 1;
        private static final int STAGE_WAITING_FOR_ACCOUNT_SECURITY = 2;
        private static final int STAGE_AFTER_ACCOUNT_SECURITY = 3;
        private int mStage = 0;

        private Context mAppContext;
        private final Handler mHandler;

        AccountFinalizeFragment() {
            mHandler = new Handler();
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setRetainInstance(true);
            mAppContext = getActivity().getApplicationContext();
            if (savedInstanceState != null) {
                mStage = savedInstanceState.getInt(SAVESTATE_STAGE);
            }
        }

        @Override
        public void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putInt(SAVESTATE_STAGE, mStage);
        }

        @Override
        public void onResume() {
            super.onResume();

            switch (mStage) {
                case STAGE_BEFORE_ACCOUNT_SECURITY:
                    kickBeforeAccountSecurityLoader();
                    break;
                case STAGE_REFRESHING_ACCOUNT:
                    kickRefreshingAccountLoader();
                    break;
                case STAGE_WAITING_FOR_ACCOUNT_SECURITY:
                    // TODO: figure out when we might get here and what to do if we do
                    break;
                case STAGE_AFTER_ACCOUNT_SECURITY:
                    kickAfterAccountSecurityLoader();
                    break;
            }
        }

        private void kickBeforeAccountSecurityLoader() {
            final LoaderManager loaderManager = getLoaderManager();

            loaderManager.destroyLoader(STAGE_REFRESHING_ACCOUNT);
            loaderManager.destroyLoader(STAGE_AFTER_ACCOUNT_SECURITY);
            loaderManager.initLoader(STAGE_BEFORE_ACCOUNT_SECURITY, getArguments(),
                    new BeforeAccountSecurityCallbacks());
        }

        private void kickRefreshingAccountLoader() {
            final LoaderManager loaderManager = getLoaderManager();

            loaderManager.destroyLoader(STAGE_BEFORE_ACCOUNT_SECURITY);
            loaderManager.destroyLoader(STAGE_AFTER_ACCOUNT_SECURITY);
            loaderManager.initLoader(STAGE_REFRESHING_ACCOUNT, getArguments(),
                    new RefreshAccountCallbacks());
        }

        private void kickAfterAccountSecurityLoader() {
            final LoaderManager loaderManager = getLoaderManager();

            loaderManager.destroyLoader(STAGE_BEFORE_ACCOUNT_SECURITY);
            loaderManager.destroyLoader(STAGE_REFRESHING_ACCOUNT);
            loaderManager.initLoader(STAGE_AFTER_ACCOUNT_SECURITY, getArguments(),
                    new AfterAccountSecurityCallbacks());
        }

        private class BeforeAccountSecurityCallbacks
                implements LoaderManager.LoaderCallbacks<Boolean> {
            public BeforeAccountSecurityCallbacks() {}

            @Override
            public Loader<Boolean> onCreateLoader(int id, Bundle args) {
                final Account account = args.getParcelable(ACCOUNT_TAG);
                final boolean email = args.getBoolean(SYNC_EMAIL_TAG);
                final boolean calendar = args.getBoolean(SYNC_CALENDAR_TAG);
                final boolean contacts = args.getBoolean(SYNC_CONTACTS_TAG);
                final boolean notificationsEnabled = args.getBoolean(NOTIFICATIONS_TAG);

                /**
                 * Task loader returns true if we created the account, false if we bailed out.
                 */
                return new MailAsyncTaskLoader<Boolean>(mAppContext) {
                    @Override
                    protected void onDiscardResult(Boolean result) {}

                    @Override
                    public Boolean loadInBackground() {
                        AccountSettingsUtils.commitSettings(mAppContext, account);
                        final AccountManagerFuture<Bundle> future =
                                EmailServiceUtils.setupAccountManagerAccount(mAppContext, account,
                                email, calendar, contacts, null);

                        boolean createSuccess = false;
                        try {
                            future.getResult();
                            createSuccess = true;
                        } catch (OperationCanceledException e) {
                            LogUtils.d(Logging.LOG_TAG, "addAccount was canceled");
                        } catch (IOException e) {
                            LogUtils.d(Logging.LOG_TAG, "addAccount failed: " + e);
                        } catch (AuthenticatorException e) {
                            LogUtils.d(Logging.LOG_TAG, "addAccount failed: " + e);
                        }
                        if (!createSuccess) {
                            return false;
                        }
                        // We can move the notification setting to the inbox FolderPreferences
                        // later, once we know what the inbox is
                        new AccountPreferences(mAppContext, account.getEmailAddress())
                                .setDefaultInboxNotificationsEnabled(notificationsEnabled);

                        // Now that AccountManager account creation is complete, clear the
                        // INCOMPLETE flag
                        account.mFlags &= ~Account.FLAGS_INCOMPLETE;
                        AccountSettingsUtils.commitSettings(mAppContext, account);

                        return true;
                    }
                };
            }

            @Override
            public void onLoadFinished(Loader<Boolean> loader, Boolean success) {
                if (success == null || !isResumed()) {
                    return;
                }
                if (success) {
                    mStage = STAGE_REFRESHING_ACCOUNT;
                    kickRefreshingAccountLoader();
                } else {
                    final AccountSetupOptions activity = (AccountSetupOptions)getActivity();
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isResumed()) {
                                return;
                            }
                            // Can't do this from within onLoadFinished
                            activity.destroyAccountFinalizeFragment();
                            activity.showCreateAccountErrorDialog();
                        }
                    });
                }
            }

            @Override
            public void onLoaderReset(Loader<Boolean> loader) {}
        }

        private class RefreshAccountCallbacks implements LoaderManager.LoaderCallbacks<Account> {

            @Override
            public Loader<Account> onCreateLoader(int id, Bundle args) {
                final Account account = args.getParcelable(ACCOUNT_TAG);
                return new MailAsyncTaskLoader<Account>(mAppContext) {
                    @Override
                    protected void onDiscardResult(Account result) {}

                    @Override
                    public Account loadInBackground() {
                        account.refresh(mAppContext);
                        return account;
                    }
                };
            }

            @Override
            public void onLoadFinished(Loader<Account> loader, Account account) {
                if (account == null || !isResumed()) {
                    return;
                }

                getArguments().putParcelable(ACCOUNT_TAG, account);

                if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
                    final Intent intent = AccountSecurity
                            .actionUpdateSecurityIntent(getActivity(), account.mId, false);
                    startActivityForResult(intent,
                            AccountSetupOptions.REQUEST_CODE_ACCEPT_POLICIES);
                    mStage = STAGE_WAITING_FOR_ACCOUNT_SECURITY;
                } else {
                    mStage = STAGE_AFTER_ACCOUNT_SECURITY;
                    kickAfterAccountSecurityLoader();
                }
            }

            @Override
            public void onLoaderReset(Loader<Account> loader) {}
        }

        private class AfterAccountSecurityCallbacks
                implements LoaderManager.LoaderCallbacks<Account> {
            @Override
            public Loader<Account> onCreateLoader(int id, Bundle args) {
                final Account account = args.getParcelable(ACCOUNT_TAG);
                return new MailAsyncTaskLoader<Account>(mAppContext) {
                    @Override
                    protected void onDiscardResult(Account result) {}

                    @Override
                    public Account loadInBackground() {
                        // Clear the security hold flag now
                        account.mFlags &= ~Account.FLAGS_SECURITY_HOLD;
                        AccountSettingsUtils.commitSettings(mAppContext, account);
                        // Start up services based on new account(s)
                        MailActivityEmail.setServicesEnabledSync(mAppContext);
                        EmailServiceUtils
                                .startService(mAppContext, account.mHostAuthRecv.mProtocol);
                        return account;
                    }
                };
            }

            @Override
            public void onLoadFinished(Loader<Account> loader, Account account) {
                if (account == null || !isResumed()) {
                    return;
                }

                // Move to final setup screen
                AccountSetupOptions activity = (AccountSetupOptions) getActivity();
                activity.getSetupData().setAccount(account);
                activity.proceed();

                // Update the folder list (to get our starting folders, e.g. Inbox)
                final EmailServiceProxy proxy = EmailServiceUtils.getServiceForAccount(activity,
                        account.mId);
                try {
                    proxy.updateFolderList(account.mId);
                } catch (RemoteException e) {
                    // It's all good
                }
            }

            @Override
            public void onLoaderReset(Loader<Account> loader) {}
        }

        /**
         * This is called after the AccountSecurity activity completes.
         */
        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            mStage = STAGE_AFTER_ACCOUNT_SECURITY;
            // onResume() will be called immediately after this to kick the next loader
        }
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

    private void showCreateAccountDialog() {
        new CreateAccountDialogFragment().show(getFragmentManager(), null);
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
    private void showCreateAccountErrorDialog() {
        new CreateAccountErrorDialogFragment().show(getFragmentManager(), null);
    }

    /**
     * Background account creation has completed, so proceed to the next screen.
     */
    private void proceed() {
        AccountSetupNames.actionSetNames(this, mSetupData);
        finish();
    }
}
