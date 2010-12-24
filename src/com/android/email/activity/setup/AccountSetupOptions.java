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
import com.android.email.ExchangeUtils;
import com.android.email.R;
import com.android.email.SecurityPolicy.PolicySet;
import com.android.email.Utility;
import com.android.email.activity.ActivityHelper;
import com.android.email.mail.Store;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.service.MailService;

import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;

import java.io.IOException;

/**
 * TODO: Cleanup the manipulation of Account.FLAGS_INCOMPLETE and make sure it's never left set. 
 */
public class AccountSetupOptions extends AccountSetupActivity implements OnClickListener {

    private Spinner mCheckFrequencyView;
    private Spinner mSyncWindowView;
    private CheckBox mDefaultView;
    private CheckBox mNotifyView;
    private CheckBox mSyncContactsView;
    private CheckBox mSyncCalendarView;
    private CheckBox mSyncEmailView;
    private boolean mDonePressed = false;

    public static final int REQUEST_CODE_ACCEPT_POLICIES = 1;

    /** Default sync window for new EAS accounts */
    private static final int SYNC_WINDOW_EAS_DEFAULT = com.android.email.Account.SYNC_WINDOW_3_DAYS;

    public static void actionOptions(Activity fromActivity) {
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupOptions.class));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.account_setup_options);

        mCheckFrequencyView = (Spinner)findViewById(R.id.account_check_frequency);
        mSyncWindowView = (Spinner) findViewById(R.id.account_sync_window);
        mDefaultView = (CheckBox)findViewById(R.id.account_default);
        mNotifyView = (CheckBox)findViewById(R.id.account_notify);
        mSyncContactsView = (CheckBox) findViewById(R.id.account_sync_contacts);
        mSyncCalendarView = (CheckBox) findViewById(R.id.account_sync_calendar);
        mSyncEmailView = (CheckBox) findViewById(R.id.account_sync_email);
        mSyncEmailView.setChecked(true);
        findViewById(R.id.previous).setOnClickListener(this);
        findViewById(R.id.next).setOnClickListener(this);

        // Generate spinner entries using XML arrays used by the preferences
        int frequencyValuesId;
        int frequencyEntriesId;
        Account account = SetupData.getAccount();
        Store.StoreInfo info = Store.StoreInfo.getStoreInfo(account.getStoreUri(this), this);
        if (info.mPushSupported) {
            frequencyValuesId = R.array.account_settings_check_frequency_values_push;
            frequencyEntriesId = R.array.account_settings_check_frequency_entries_push;
        } else {
            frequencyValuesId = R.array.account_settings_check_frequency_values;
            frequencyEntriesId = R.array.account_settings_check_frequency_entries;
        }
        CharSequence[] frequencyValues = getResources().getTextArray(frequencyValuesId);
        CharSequence[] frequencyEntries = getResources().getTextArray(frequencyEntriesId);

        // Now create the array used by the Spinner
        SpinnerOption[] checkFrequencies = new SpinnerOption[frequencyEntries.length];
        for (int i = 0; i < frequencyEntries.length; i++) {
            checkFrequencies[i] = new SpinnerOption(
                    Integer.valueOf(frequencyValues[i].toString()), frequencyEntries[i].toString());
        }

        ArrayAdapter<SpinnerOption> checkFrequenciesAdapter = new ArrayAdapter<SpinnerOption>(this,
                android.R.layout.simple_spinner_item, checkFrequencies);
        checkFrequenciesAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCheckFrequencyView.setAdapter(checkFrequenciesAdapter);

        if (info.mVisibleLimitDefault == -1) {
            enableEASSyncWindowSpinner();
        }

        // Note:  It is OK to use mAccount.mIsDefault here *only* because the account
        // has not been written to the DB yet.  Ordinarily, call Account.getDefaultAccountId().
        if (account.mIsDefault || SetupData.isDefault()) {
            mDefaultView.setChecked(true);
        }
        mNotifyView.setChecked(
                (account.getFlags() & EmailContent.Account.FLAGS_NOTIFY_NEW_MAIL) != 0);
        SpinnerOption.setSpinnerOptionValue(mCheckFrequencyView, account.getSyncInterval());

        // Setup any additional items to support EAS & EAS flow mode
        if ("eas".equals(info.mScheme)) {
            // "also sync contacts" == "true"
            mSyncContactsView.setVisibility(View.VISIBLE);
            mSyncContactsView.setChecked(true);
            mSyncCalendarView.setVisibility(View.VISIBLE);
            mSyncCalendarView.setChecked(true);
            // Show the associated dividers
            findViewById(R.id.account_sync_contacts_divider).setVisibility(View.VISIBLE);
            findViewById(R.id.account_sync_calendar_divider).setVisibility(View.VISIBLE);
        }

        // If we are just visiting here to fill in details, exit immediately
        if (SetupData.isAutoSetup() ||
                SetupData.getFlowMode() == SetupData.FLOW_MODE_FORCE_CREATE) {
            onDone();
        }
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
        final Account account = SetupData.getAccount();
        account.setDisplayName(account.getEmailAddress());
        int newFlags = account.getFlags() & ~(EmailContent.Account.FLAGS_NOTIFY_NEW_MAIL);
        if (mNotifyView.isChecked()) {
            newFlags |= EmailContent.Account.FLAGS_NOTIFY_NEW_MAIL;
        }
        account.setFlags(newFlags);
        account.setSyncInterval((Integer)((SpinnerOption)mCheckFrequencyView
                .getSelectedItem()).value);
        if (findViewById(R.id.account_sync_window_row).getVisibility() == View.VISIBLE) {
            int window = (Integer)((SpinnerOption)mSyncWindowView.getSelectedItem()).value;
            account.setSyncLookback(window);
        }
        account.setDefaultAccount(mDefaultView.isChecked());

        if (account.isSaved()) {
            throw new IllegalStateException("in AccountSetupOptions with already-saved account");
        }
        if (account.mHostAuthRecv == null) {
            throw new IllegalStateException("in AccountSetupOptions with null mHostAuthRecv");
        }

        // Finish setting up the account, and commit it to the database
        // Set the incomplete flag here to avoid reconciliation issues in ExchangeService
        account.mFlags |= Account.FLAGS_INCOMPLETE;
        boolean calendar = false;
        boolean contacts = false;
        boolean email = mSyncEmailView.isChecked();
        if (account.mHostAuthRecv.mProtocol.equals("eas")) {
            // Set security hold if necessary to prevent sync until policies are accepted
            PolicySet policySet = SetupData.getPolicySet();
            if (policySet != null && policySet.getSecurityCode() != 0) {
                account.mSecurityFlags = policySet.getSecurityCode();
                account.mFlags |= Account.FLAGS_SECURITY_HOLD;
            }
            // Get flags for contacts/calendar sync
            contacts = mSyncContactsView.isChecked();
            calendar = mSyncCalendarView.isChecked();
        }

        // Finally, write the completed account (for the first time) and then
        // install it into the Account manager as well.  These are done off-thread.
        // The account manager will report back via the callback, which will take us to
        // the next operations.
        final boolean email2 = email;
        final boolean calendar2 = calendar;
        final boolean contacts2 = contacts;
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                Context context = AccountSetupOptions.this;
                AccountSettingsUtils.commitSettings(context, account);
                MailService.setupAccountManagerAccount(context, account,
                        email2, calendar2, contacts2, mAccountManagerCallback);
            }
        });
    }

    /**
     * This is called at the completion of MailService.setupAccountManagerAccount()
     */
    AccountManagerCallback<Bundle> mAccountManagerCallback = new AccountManagerCallback<Bundle>() {
        public void run(AccountManagerFuture<Bundle> future) {
            try {
                Bundle bundle = future.getResult();
                bundle.keySet();
                AccountSetupOptions.this.runOnUiThread(new Runnable() {
                    public void run() {
                        optionsComplete();
                    }
                });
                return;
            } catch (OperationCanceledException e) {
                Log.d(Email.LOG_TAG, "addAccount was canceled");
            } catch (IOException e) {
                Log.d(Email.LOG_TAG, "addAccount failed: " + e);
            } catch (AuthenticatorException e) {
                Log.d(Email.LOG_TAG, "addAccount failed: " + e);
            }
            showErrorDialog(R.string.account_setup_failed_dlg_auth_message,
                    R.string.system_account_create_failed);
        }
    };

    /**
     * This is called if MailService.setupAccountManagerAccount() fails for some reason
     */
    private void showErrorDialog(final int msgResId, final Object... args) {
        runOnUiThread(new Runnable() {
            public void run() {
                new AlertDialog.Builder(AccountSetupOptions.this)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(getString(R.string.account_setup_failed_dlg_title))
                        .setMessage(getString(msgResId, args))
                        .setCancelable(true)
                        .setPositiveButton(
                                getString(R.string.account_setup_failed_dlg_edit_details_action),
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                       finish();
                                    }
                                })
                        .show();
            }
        });
    }

    /**
     * This is called after the account manager creates the new account.
     */
    private void optionsComplete() {
        // If we've got policies for this account, ask the user to accept.
        Account account = SetupData.getAccount();
        if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
            Intent intent = AccountSecurity.actionUpdateSecurityIntent(this, account.mId);
            startActivityForResult(intent, AccountSetupOptions.REQUEST_CODE_ACCEPT_POLICIES);
            return;
        }
        saveAccountAndFinish();
    }

    /**
     * This is called after the AccountSecurity activity completes.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        saveAccountAndFinish();
    }

    /**
     * These are the final cleanup steps when creating an account:
     *  Clear incomplete & security hold flags
     *  Update account in DB
     *  Enable email services
     *  Enable exchange services
     *  Move to final setup screen
     */
    private void saveAccountAndFinish() {
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                AccountSetupOptions context = AccountSetupOptions.this;
                // Clear the incomplete/security hold flag now
                Account account = SetupData.getAccount();
                account.mFlags &= ~(Account.FLAGS_INCOMPLETE | Account.FLAGS_SECURITY_HOLD);
                AccountSettingsUtils.commitSettings(context, account);
                // Start up services based on new account(s)
                Email.setServicesEnabledSync(context);
                ExchangeUtils.startExchangeService(context);
                // Move to final setup screen
                AccountSetupNames.actionSetNames(context);
                finish();
            }
        });
    }

    /**
     * Enable an additional spinner using the arrays normally handled by preferences
     */
    private void enableEASSyncWindowSpinner() {
        // Show everything
        findViewById(R.id.account_sync_window_row).setVisibility(View.VISIBLE);

        // Generate spinner entries using XML arrays used by the preferences
        CharSequence[] windowValues = getResources().getTextArray(
                R.array.account_settings_mail_window_values);
        CharSequence[] windowEntries = getResources().getTextArray(
                R.array.account_settings_mail_window_entries);

        // Now create the array used by the Spinner
        SpinnerOption[] windowOptions = new SpinnerOption[windowEntries.length];
        int defaultIndex = -1;
        for (int i = 0; i < windowEntries.length; i++) {
            final int value = Integer.valueOf(windowValues[i].toString());
            windowOptions[i] = new SpinnerOption(value, windowEntries[i].toString());
            if (value == SYNC_WINDOW_EAS_DEFAULT) {
                defaultIndex = i;
            }
        }

        ArrayAdapter<SpinnerOption> windowOptionsAdapter = new ArrayAdapter<SpinnerOption>(this,
                android.R.layout.simple_spinner_item, windowOptions);
        windowOptionsAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSyncWindowView.setAdapter(windowOptionsAdapter);

        SpinnerOption.setSpinnerOptionValue(mSyncWindowView,
                SetupData.getAccount().getSyncLookback());
        if (defaultIndex >= 0) {
            mSyncWindowView.setSelection(defaultIndex);
        }
    }
}
