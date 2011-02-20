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

import com.android.email.AccountBackupRestore;
import com.android.email.R;
import com.android.email.activity.ActivityHelper;
import com.android.email.activity.Welcome;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.text.method.TextKeyListener.Capitalize;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;

/**
 * Final screen of setup process.  Collect account nickname and/or username.
 */
public class AccountSetupNames extends AccountSetupActivity implements OnClickListener {
    private static final int REQUEST_SECURITY = 0;

    private EditText mDescription;
    private EditText mName;
    private Button mNextButton;
    private boolean mNextPressed = false;
    private boolean mEasAccount = false;

    public static void actionSetNames(Activity fromActivity) {
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupNames.class));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.account_setup_names);
        mDescription = (EditText) findViewById(R.id.account_description);
        mName = (EditText) findViewById(R.id.account_name);
        mNextButton = (Button) findViewById(R.id.next);
        mNextButton.setOnClickListener(this);

        TextWatcher validationTextWatcher = new TextWatcher() {
            public void afterTextChanged(Editable s) {
                validateFields();
            }

            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }
        };
        mName.addTextChangedListener(validationTextWatcher);
        mName.setKeyListener(TextKeyListener.getInstance(false, Capitalize.WORDS));

        Account account = SetupData.getAccount();
        if (account == null) {
            throw new IllegalStateException("unexpected null account");
        }
        if (account.mHostAuthRecv == null) {
            throw new IllegalStateException("unexpected null hostauth");
        }

        // Remember whether we're an EAS account, since it doesn't require the user name field
        mEasAccount = "eas".equals(account.mHostAuthRecv.mProtocol);
        if (mEasAccount) {
            mName.setVisibility(View.GONE);
            findViewById(R.id.account_name_label).setVisibility(View.GONE);
        }
        /*
         * Since this field is considered optional, we don't set this here. If
         * the user fills in a value we'll reset the current value, otherwise we
         * just leave the saved value alone.
         */
        // mDescription.setText(mAccount.getDescription());
        if (account != null && account.getSenderName() != null) {
            mName.setText(account.getSenderName());
        }

        // Make sure the "done" button is in the proper state
        validateFields();

        // Proceed immediately if in account creation mode
        if (SetupData.getFlowMode() == SetupData.FLOW_MODE_FORCE_CREATE) {
            onNext();
        }
    }

    /**
     * Implements OnClickListener
     */
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.next:
                // Don't allow this more than once (we do some work in an async thread before
                // finish()'ing the Activity, which allows this code to potentially be
                // executed multiple times.
                if (!mNextPressed) {
                    onNext();
                }
                mNextPressed = true;
                break;
        }
    }

    /**
     * Check input fields for legal values and enable/disable next button
     */
    private void validateFields() {
        boolean newEnabled = true;
        // Validation is based only on the "user name" field, not shown for EAS accounts
        if (!mEasAccount) {
            String userName = mName.getText().toString().trim();
            newEnabled = !TextUtils.isEmpty(userName);
            if (!newEnabled) {
                mName.setError(getString(R.string.account_setup_names_user_name_empty_error));
            }
        }
        mNextButton.setEnabled(newEnabled);
    }

    /**
     * Block the back key if we are currently processing the "next" key"
     */
    @Override
    public void onBackPressed() {
        if (!mNextPressed) {
            finishActivity();
        }
    }

    private void finishActivity() {
        if (SetupData.getFlowMode() != SetupData.FLOW_MODE_NORMAL) {
            AccountSetupBasics.actionAccountCreateFinishedAccountFlow(this);
        } else {
            Account account = SetupData.getAccount();
            if (account != null) {
                AccountSetupBasics.actionAccountCreateFinished(this, account.mId);
            } else {
                // Safety check here;  If mAccount is null (due to external issues or bugs)
                // just rewind back to Welcome, which can handle any configuration of accounts
                Welcome.actionStart(this);
            }
        }
        finish();
    }

    /**
     * After clicking the next button, we'll start an async task to commit the data
     * and other steps to finish the creation of the account.
     */
    private void onNext() {
        // Update account object from UI
        Account account = SetupData.getAccount();
        String description = mDescription.getText().toString().trim();
        if (!TextUtils.isEmpty(description)) {
            account.setDisplayName(description);
        }
        account.setSenderName(mName.getText().toString().trim());

        // Launch async task for final commit work
        new FinalSetupTask(account).execute();
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
    private class FinalSetupTask extends AsyncTask<Void, Void, Boolean> {

        private Account mAccount;
        private Context mContext;

        public FinalSetupTask(Account account) {
            mAccount = account;
            mContext = AccountSetupNames.this;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            // Update the account in the database
            ContentValues cv = new ContentValues();
            cv.put(AccountColumns.DISPLAY_NAME, mAccount.getDisplayName());
            cv.put(AccountColumns.SENDER_NAME, mAccount.getSenderName());
            mAccount.update(mContext, cv);

            // Update the backup (side copy) of the accounts
            AccountBackupRestore.backupAccounts(AccountSetupNames.this);

            return Account.isSecurityHold(mContext, mAccount.mId);
        }

        @Override
        protected void onPostExecute(Boolean isSecurityHold) {
            if (!isCancelled()) {
                if (isSecurityHold) {
                    Intent i = AccountSecurity.actionUpdateSecurityIntent(
                            AccountSetupNames.this, mAccount.mId, false);
                    AccountSetupNames.this.startActivityForResult(i, REQUEST_SECURITY);
                } else {
                    finishActivity();
                }
            }
        }
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
