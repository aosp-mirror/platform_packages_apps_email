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

import android.app.Activity;
import android.app.LoaderManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

import com.android.email.R;
import com.android.email.activity.ActivityHelper;
import com.android.email.activity.UiUtilities;
import com.android.email.provider.AccountBackupRestore;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.mail.ui.MailAsyncTaskLoader;

/**
 * Final screen of setup process.  Collect account nickname and/or username.
 */
public class AccountSetupNames extends AccountSetupActivity {
    private static final int REQUEST_SECURITY = 0;

    private Button mNextButton;
    private static final String SAVESTATE_ISCOMPLETING_TAG = "isCompleting";
    private boolean mIsCompleting = false;
    private static final int FINAL_ACCOUNT_TASK_LOADER_ID = 0;
    private static final String ACCOUNT_TAG = "account";
    private Bundle mFinalAccountTaskLoaderArgs;
    private LoaderManager.LoaderCallbacks mFinalAccountTaskLoaderCallbacks;

    public static void actionSetNames(Activity fromActivity, SetupDataFragment setupData) {
        ForwardingIntent intent = new ForwardingIntent(fromActivity, AccountSetupNames.class);
        intent.putExtra(SetupDataFragment.EXTRA_SETUP_DATA, setupData);
        fromActivity.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mIsCompleting = savedInstanceState.getBoolean(SAVESTATE_ISCOMPLETING_TAG);
        }

        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.account_setup_names);

        mNextButton = UiUtilities.getView(this, R.id.next);
        mNextButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                onNext();
            }
        });

        // Proceed immediately if in account creation mode
        if (mSetupData.getFlowMode() == SetupDataFragment.FLOW_MODE_FORCE_CREATE) {
            onNext();
        }

        if (mIsCompleting) {
            startFinalSetupTaskLoader(getSetupData().getAccount());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SAVESTATE_ISCOMPLETING_TAG, mIsCompleting);
    }

    /**
     * Block the back key if we are currently processing the "next" key"
     */
    @Override
    public void onBackPressed() {
        if (!mIsCompleting) {
            finishActivity();
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

    public void setNextButtonEnabled(boolean enabled) {
        mNextButton.setEnabled(enabled);
    }

    /**
     * After clicking the next button, we'll start an async task to commit the data
     * and other steps to finish the creation of the account.
     */
    private void onNext() {
        mNextButton.setEnabled(false); // Protect against double-tap.
        mIsCompleting = true;

        AccountSetupNamesFragment fragment = (AccountSetupNamesFragment)
                getFragmentManager().findFragmentById(R.id.names_fragment);
        // Update account object from UI
        final Account account = mSetupData.getAccount();
        final String description = fragment.getDescription();
        if (!TextUtils.isEmpty(description)) {
            account.setDisplayName(description);
        }
        account.setSenderName(fragment.getSenderName());

        startFinalSetupTaskLoader(account);
    }

    private void startFinalSetupTaskLoader(Account account) {
        if (mFinalAccountTaskLoaderArgs == null) {
            mFinalAccountTaskLoaderArgs = new Bundle(1);
            mFinalAccountTaskLoaderArgs.putParcelable(ACCOUNT_TAG, account);

            final Context appContext = getApplicationContext();
            mFinalAccountTaskLoaderCallbacks = new LoaderManager.LoaderCallbacks<Boolean>() {
                @Override
                public Loader<Boolean> onCreateLoader(int id, Bundle args) {
                    final Account accountArg = args.getParcelable(ACCOUNT_TAG);
                    return new FinalSetupTaskLoader(appContext, accountArg);
                }

                @Override
                public void onLoadFinished(Loader<Boolean> loader, Boolean isSecurityHold) {
                    if (isSecurityHold) {
                        final FinalSetupTaskLoader finalSetupTaskLoader =
                                (FinalSetupTaskLoader)loader;
                        final Intent i = AccountSecurity.actionUpdateSecurityIntent(
                                appContext, finalSetupTaskLoader.getAccount().mId, false);
                        startActivityForResult(i, REQUEST_SECURITY);
                    } else {
                        finishActivity();
                    }
                }

                @Override
                public void onLoaderReset(Loader<Boolean> loader) {}
            };
        }
        getLoaderManager().initLoader(FINAL_ACCOUNT_TASK_LOADER_ID, mFinalAccountTaskLoaderArgs,
                mFinalAccountTaskLoaderCallbacks);
    }

    /**
     * Final account setup work is handled in this AsyncTask:
     *   Commit final values to provider
     *   Trigger account backup
     *   Check for security hold
     *
     * When this completes, we return to UI thread for the following steps:
     *   If security hold, dispatch to AccountSecurity activity
     *   Otherwise, return to AccountSetupBasics for conclusion.
     *
     * TODO: If there was *any* indication that security might be required, we could at least
     * force the DeviceAdmin activation step, without waiting for the initial sync/handshake
     * to fail.
     * TODO: If the user doesn't update the security, don't go to the MessageList.
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
            cv.put(AccountColumns.DISPLAY_NAME, mAccount.getDisplayName());
            cv.put(AccountColumns.SENDER_NAME, mAccount.getSenderName());
            mAccount.update(getContext(), cv);

            // Update the backup (side copy) of the accounts
            AccountBackupRestore.backup(getContext());

            return Account.isSecurityHold(getContext(), mAccount.mId);
        }

        @Override
        protected void onDiscardResult(Boolean result) {}
    }

    /**
     * Handle the eventual result from the security update activity
     *
     * TODO: If the user doesn't update the security, don't go to the MessageList.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_SECURITY:
                finishActivity();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

}
