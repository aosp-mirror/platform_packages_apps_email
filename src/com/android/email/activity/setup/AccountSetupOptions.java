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
import com.android.email.mail.Store;
import com.android.email.mail.store.ExchangeStore;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;

import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;

import java.io.IOException;

public class AccountSetupOptions extends Activity implements OnClickListener {

    private static final String EXTRA_ACCOUNT = "account";
    private static final String EXTRA_MAKE_DEFAULT = "makeDefault";
    private static final String EXTRA_EAS_FLOW = "easFlow";

    private Spinner mCheckFrequencyView;
    private Spinner mSyncWindowView;
    private CheckBox mDefaultView;
    private CheckBox mNotifyView;
    private CheckBox mSyncContactsView;
    private CheckBox mSyncCalendarView;
    private EmailContent.Account mAccount;
    private boolean mEasFlowMode;
    private Handler mHandler = new Handler();
    private boolean mDonePressed = false;

    /** Default sync window for new EAS accounts */
    private static final int SYNC_WINDOW_EAS_DEFAULT = com.android.email.Account.SYNC_WINDOW_3_DAYS;

    public static void actionOptions(Activity fromActivity, EmailContent.Account account,
            boolean makeDefault, boolean easFlowMode) {
        Intent i = new Intent(fromActivity, AccountSetupOptions.class);
        i.putExtra(EXTRA_ACCOUNT, account);
        i.putExtra(EXTRA_MAKE_DEFAULT, makeDefault);
        i.putExtra(EXTRA_EAS_FLOW, easFlowMode);
        fromActivity.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_options);

        mCheckFrequencyView = (Spinner)findViewById(R.id.account_check_frequency);
        mSyncWindowView = (Spinner) findViewById(R.id.account_sync_window);
        mDefaultView = (CheckBox)findViewById(R.id.account_default);
        mNotifyView = (CheckBox)findViewById(R.id.account_notify);
        mSyncContactsView = (CheckBox) findViewById(R.id.account_sync_contacts);
        mSyncCalendarView = (CheckBox) findViewById(R.id.account_sync_calendar);

        findViewById(R.id.next).setOnClickListener(this);

        mAccount = (EmailContent.Account) getIntent().getParcelableExtra(EXTRA_ACCOUNT);
        boolean makeDefault = getIntent().getBooleanExtra(EXTRA_MAKE_DEFAULT, false);

        // Generate spinner entries using XML arrays used by the preferences
        int frequencyValuesId;
        int frequencyEntriesId;
        Store.StoreInfo info = Store.StoreInfo.getStoreInfo(mAccount.getStoreUri(this), this);
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
        if (mAccount.mIsDefault || makeDefault) {
            mDefaultView.setChecked(true);
        }
        mNotifyView.setChecked(
                (mAccount.getFlags() & EmailContent.Account.FLAGS_NOTIFY_NEW_MAIL) != 0);
        SpinnerOption.setSpinnerOptionValue(mCheckFrequencyView, mAccount
                .getSyncInterval());

        // Setup any additional items to support EAS & EAS flow mode
        mEasFlowMode = getIntent().getBooleanExtra(EXTRA_EAS_FLOW, false);
        if ("eas".equals(info.mScheme)) {
            // "also sync contacts" == "true"
            mSyncContactsView.setVisibility(View.VISIBLE);
            mSyncContactsView.setChecked(true);
            mSyncCalendarView.setVisibility(View.VISIBLE);
            mSyncCalendarView.setChecked(true);
        }
    }

    AccountManagerCallback<Bundle> mAccountManagerCallback = new AccountManagerCallback<Bundle>() {
        public void run(AccountManagerFuture<Bundle> future) {
            try {
                Bundle bundle = future.getResult();
                bundle.keySet();
                mHandler.post(new Runnable() {
                    public void run() {
                        finishOnDone();
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

    private void showErrorDialog(final int msgResId, final Object... args) {
        mHandler.post(new Runnable() {
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

    private void finishOnDone() {
        // Clear the incomplete flag now
        mAccount.mFlags &= ~Account.FLAGS_INCOMPLETE;
        AccountSettingsUtils.commitSettings(this, mAccount);
        Email.setServicesEnabled(this);
        AccountSetupNames.actionSetNames(this, mAccount.mId, mEasFlowMode);
        // Start up SyncManager (if it isn't already running)
        ExchangeUtils.startExchangeService(this);
        finish();
    }

    private void onDone() {
        mAccount.setDisplayName(mAccount.getEmailAddress());
        int newFlags = mAccount.getFlags() & ~(EmailContent.Account.FLAGS_NOTIFY_NEW_MAIL);
        if (mNotifyView.isChecked()) {
            newFlags |= EmailContent.Account.FLAGS_NOTIFY_NEW_MAIL;
        }
        mAccount.setFlags(newFlags);
        mAccount.setSyncInterval((Integer)((SpinnerOption)mCheckFrequencyView
                .getSelectedItem()).value);
        if (mSyncWindowView.getVisibility() == View.VISIBLE) {
            int window = (Integer)((SpinnerOption)mSyncWindowView.getSelectedItem()).value;
            mAccount.setSyncLookback(window);
        }
        mAccount.setDefaultAccount(mDefaultView.isChecked());

        // Call EAS to store account information for use by AccountManager
        if (!mAccount.isSaved()
                && mAccount.mHostAuthRecv != null
                && mAccount.mHostAuthRecv.mProtocol.equals("eas")) {
            boolean alsoSyncContacts = mSyncContactsView.isChecked();
            boolean alsoSyncCalendar = mSyncCalendarView.isChecked();
            // Set the incomplete flag here to avoid reconciliation issues in SyncManager (EAS)
            mAccount.mFlags |= Account.FLAGS_INCOMPLETE;
            AccountSettingsUtils.commitSettings(this, mAccount);
            ExchangeStore.addSystemAccount(getApplication(), mAccount,
                    alsoSyncContacts, alsoSyncCalendar, mAccountManagerCallback);
        } else {
            finishOnDone();
       }
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.next:
                // Don't allow this more than once (Exchange accounts call an async method
                // before finish()'ing the Activity, which allows this code to potentially be
                // executed multiple times
                if (!mDonePressed) {
                    onDone();
                    mDonePressed = true;
                }
                break;
        }
    }

    /**
     * Enable an additional spinner using the arrays normally handled by preferences
     */
    private void enableEASSyncWindowSpinner() {
        // Show everything
        findViewById(R.id.account_sync_window_label).setVisibility(View.VISIBLE);
        mSyncWindowView.setVisibility(View.VISIBLE);

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

        SpinnerOption.setSpinnerOptionValue(mSyncWindowView, mAccount.getSyncLookback());
        if (defaultIndex >= 0) {
            mSyncWindowView.setSelection(defaultIndex);
        }
    }
}
