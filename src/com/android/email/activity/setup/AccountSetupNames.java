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
import com.android.email.Utility;
import com.android.email.activity.ActivityHelper;
import com.android.email.activity.Welcome;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;
import com.android.email.provider.EmailContent.HostAuth;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.TextKeyListener;
import android.text.method.TextKeyListener.Capitalize;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;

public class AccountSetupNames extends AccountSetupActivity {
    private static final int REQUEST_SECURITY = 0;

    private EditText mDescription;
    private EditText mName;
    private boolean mEasAccount = false;
    private boolean mNextButtonEnabled;

    private CheckAccountStateTask mCheckAccountStateTask;

    public static void actionSetNames(Activity fromActivity) {
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupNames.class));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.account_setup_names);
        mDescription = (EditText)findViewById(R.id.account_description);
        mName = (EditText)findViewById(R.id.account_name);

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
        // Shouldn't happen, but it could
        if (account == null) {
            onBackPressed();
            return;
        }
        // Get the hostAuth for receiving
        HostAuth hostAuth = HostAuth.restoreHostAuthWithId(this, account.mHostAuthKeyRecv);
        if (hostAuth == null) {
            onBackPressed();
            return;
        }

        // Remember whether we're an EAS account, since it doesn't require the user name field
        mEasAccount = hostAuth.mProtocol.equals("eas");
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
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (mCheckAccountStateTask != null &&
                mCheckAccountStateTask.getStatus() != CheckAccountStateTask.Status.FINISHED) {
            mCheckAccountStateTask.cancel(true);
            mCheckAccountStateTask = null;
        }
    }

    /**
     * Add "Next" button when this activity is displayed
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.account_setup_next_option, menu);
        return super.onCreateOptionsMenu(menu);
    }

    /**
     * Enable/disable "Next" button
     */
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.next).setEnabled(mNextButtonEnabled);
        return super.onPrepareOptionsMenu(menu);
    }

    /**
     * Respond to clicks in the "Next" button
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.next:
                onNext();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * TODO: Validator should also trim the name string before checking it.
     */
    private void validateFields() {
        boolean newEnabled = mEasAccount || Utility.isTextViewNotEmpty(mName);
        if (newEnabled != mNextButtonEnabled) {
            mNextButtonEnabled = newEnabled;
            invalidateOptionsMenu();
        }
    }

    @Override
    public void onBackPressed() {
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
     * After having a chance to input the display names, we normally jump directly to the
     * inbox for the new account.  However if we're in EAS flow mode (externally-launched
     * account creation) we simply "pop" here which should return us to the Accounts activities.
     *
     * TODO: Validator should also trim the description string before checking it.
     */
    private void onNext() {
        Account account = SetupData.getAccount();
        if (Utility.isTextViewNotEmpty(mDescription)) {
            account.setDisplayName(mDescription.getText().toString());
        }
        String name = mName.getText().toString();
        account.setSenderName(name);
        ContentValues cv = new ContentValues();
        cv.put(AccountColumns.DISPLAY_NAME, account.getDisplayName());
        cv.put(AccountColumns.SENDER_NAME, name);
        account.update(this, cv);
        // Update the backup (side copy) of the accounts
        AccountBackupRestore.backupAccounts(this);

        // Before proceeding, launch an AsyncTask to test the account for any syncing problems,
        // and if there's a problem, bring up the UI to update the security level.
        mCheckAccountStateTask = new CheckAccountStateTask(account.mId);
        mCheckAccountStateTask.execute();
    }

    /**
     * This async task is launched just before exiting.  It's a last chance test, before leaving
     * this activity, for the account being in a "hold" state, and gives the user a chance to
     * update security, enter a device PIN, etc. for a more seamless account setup experience.
     *
     * TODO: If there was *any* indication that security might be required, we could at least
     * force the DeviceAdmin activation step, without waiting for the initial sync/handshake
     * to fail.
     * TODO: If the user doesn't update the security, don't go to the MessageList.
     */
    private class CheckAccountStateTask extends AsyncTask<Void, Void, Boolean> {

        private long mAccountId;

        public CheckAccountStateTask(long accountId) {
            mAccountId = accountId;
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            return Account.isSecurityHold(AccountSetupNames.this, mAccountId);
        }

        @Override
        protected void onPostExecute(Boolean isSecurityHold) {
            if (!isCancelled()) {
                if (isSecurityHold) {
                    Intent i = AccountSecurity.actionUpdateSecurityIntent(
                            AccountSetupNames.this, mAccountId);
                    AccountSetupNames.this.startActivityForResult(i, REQUEST_SECURITY);
                } else {
                    onBackPressed();
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
                onBackPressed();
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

}
