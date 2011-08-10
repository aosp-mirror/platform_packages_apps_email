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

import com.android.email.Email;
import com.android.email.R;
import com.android.email.activity.ActivityHelper;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.MailService;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.service.SyncWindow;
import com.android.emailcommon.utility.Utility;

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
    private CheckBox mBackgroundAttachmentsView;
    private View mAccountSyncWindowRow;
    private boolean mDonePressed = false;

    public static final int REQUEST_CODE_ACCEPT_POLICIES = 1;

    /** Default sync window for new EAS accounts */
    private static final int SYNC_WINDOW_EAS_DEFAULT = SyncWindow.SYNC_WINDOW_AUTO;

    public static void actionOptions(Activity fromActivity) {
        fromActivity.startActivity(new Intent(fromActivity, AccountSetupOptions.class));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.account_setup_options);

        mCheckFrequencyView = (Spinner) UiUtilities.getView(this, R.id.account_check_frequency);
        mSyncWindowView = (Spinner) UiUtilities.getView(this, R.id.account_sync_window);
        mDefaultView = (CheckBox) UiUtilities.getView(this, R.id.account_default);
        mNotifyView = (CheckBox) UiUtilities.getView(this, R.id.account_notify);
        mSyncContactsView = (CheckBox) UiUtilities.getView(this, R.id.account_sync_contacts);
        mSyncCalendarView = (CheckBox) UiUtilities.getView(this, R.id.account_sync_calendar);
        mSyncEmailView = (CheckBox) UiUtilities.getView(this, R.id.account_sync_email);
        mSyncEmailView.setChecked(true);
        mBackgroundAttachmentsView = (CheckBox) UiUtilities.getView(this,
                R.id.account_background_attachments);
        mBackgroundAttachmentsView.setChecked(true);
        UiUtilities.getView(this, R.id.previous).setOnClickListener(this);
        UiUtilities.getView(this, R.id.next).setOnClickListener(this);
        mAccountSyncWindowRow = UiUtilities.getView(this, R.id.account_sync_window_row);

        // Generate spinner entries using XML arrays used by the preferences
        int frequencyValuesId;
        int frequencyEntriesId;
        Account account = SetupData.getAccount();
        String protocol = account.mHostAuthRecv.mProtocol;
        boolean eas = HostAuth.SCHEME_EAS.equals(protocol);
        if (eas) {
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

        if (eas) {
            enableEASSyncWindowSpinner();
        }

        // Note:  It is OK to use mAccount.mIsDefault here *only* because the account
        // has not been written to the DB yet.  Ordinarily, call Account.getDefaultAccountId().
        if (account.mIsDefault || SetupData.isDefault()) {
            mDefaultView.setChecked(true);
        }
        mNotifyView.setChecked(
                (account.getFlags() & Account.FLAGS_NOTIFY_NEW_MAIL) != 0);
        SpinnerOption.setSpinnerOptionValue(mCheckFrequencyView, account.getSyncInterval());

        // Setup any additional items to support EAS & EAS flow mode
        if (eas) {
            // "also sync contacts" == "true"
            mSyncContactsView.setVisibility(View.VISIBLE);
            mSyncContactsView.setChecked(true);
            mSyncCalendarView.setVisibility(View.VISIBLE);
            mSyncCalendarView.setChecked(true);
            // Show the associated dividers
            UiUtilities.setVisibilitySafe(this, R.id.account_sync_contacts_divider, View.VISIBLE);
            UiUtilities.setVisibilitySafe(this, R.id.account_sync_calendar_divider, View.VISIBLE);
        }

        // If we are in POP3, hide the "Background Attachments" mode
        if (HostAuth.SCHEME_POP3.equals(protocol)) {
            mBackgroundAttachmentsView.setVisibility(View.GONE);
            UiUtilities.setVisibilitySafe(this, R.id.account_background_attachments_divider,
                    View.GONE);
        }

        // If we are just visiting here to fill in details, exit immediately
        if (SetupData.isAutoSetup() ||
                SetupData.getFlowMode() == SetupData.FLOW_MODE_FORCE_CREATE) {
            onDone();
        }
    }

    @Override
    public void finish() {
        // If the account manager initiated the creation, and success was not reported,
        // then we assume that we're giving up (for any reason) - report failure.
        AccountAuthenticatorResponse authenticatorResponse =
            SetupData.getAccountAuthenticatorResponse();
        if (authenticatorResponse != null) {
            authenticatorResponse.onError(AccountManager.ERROR_CODE_CANCELED, "canceled");
            SetupData.setAccountAuthenticatorResponse(null);
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
        final Account account = SetupData.getAccount();
        if (account.isSaved()) {
            // Disrupting the normal flow could get us here, but if the account is already
            // saved, we've done this work
            return;
        }
        account.setDisplayName(account.getEmailAddress());
        int newFlags = account.getFlags() &
            ~(Account.FLAGS_NOTIFY_NEW_MAIL | Account.FLAGS_BACKGROUND_ATTACHMENTS);
        if (mNotifyView.isChecked()) {
            newFlags |= Account.FLAGS_NOTIFY_NEW_MAIL;
        }
        if (mBackgroundAttachmentsView.isChecked()) {
            newFlags |= Account.FLAGS_BACKGROUND_ATTACHMENTS;
        }
        account.setFlags(newFlags);
        account.setSyncInterval((Integer)((SpinnerOption)mCheckFrequencyView
                .getSelectedItem()).value);
        if (mAccountSyncWindowRow.getVisibility() == View.VISIBLE) {
            int window = (Integer)((SpinnerOption)mSyncWindowView.getSelectedItem()).value;
            account.setSyncLookback(window);
        }
        account.setDefaultAccount(mDefaultView.isChecked());

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
            if (SetupData.getPolicy() != null) {
                account.mFlags |= Account.FLAGS_SECURITY_HOLD;
                account.mPolicy = SetupData.getPolicy();
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
                Log.d(Logging.LOG_TAG, "addAccount was canceled");
            } catch (IOException e) {
                Log.d(Logging.LOG_TAG, "addAccount failed: " + e);
            } catch (AuthenticatorException e) {
                Log.d(Logging.LOG_TAG, "addAccount failed: " + e);
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
                        .setIconAttribute(android.R.attr.alertDialogIcon)
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
        // If the account manager initiated the creation, report success at this point
        AccountAuthenticatorResponse authenticatorResponse =
            SetupData.getAccountAuthenticatorResponse();
        if (authenticatorResponse != null) {
            authenticatorResponse.onResult(null);
            SetupData.setAccountAuthenticatorResponse(null);
        }

        // Now that AccountManager account creation is complete, clear the INCOMPLETE flag
        Account account = SetupData.getAccount();
        account.mFlags &= ~Account.FLAGS_INCOMPLETE;
        AccountSettingsUtils.commitSettings(AccountSetupOptions.this, account);

        // If we've got policies for this account, ask the user to accept.
        if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
            Intent intent = AccountSecurity.actionUpdateSecurityIntent(this, account.mId, false);
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
                // Clear the security hold flag now
                Account account = SetupData.getAccount();
                account.mFlags &= ~Account.FLAGS_SECURITY_HOLD;
                AccountSettingsUtils.commitSettings(context, account);
                // Start up services based on new account(s)
                Email.setServicesEnabledSync(context);
                EmailServiceUtils.startExchangeService(context);
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
        mAccountSyncWindowRow.setVisibility(View.VISIBLE);

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
