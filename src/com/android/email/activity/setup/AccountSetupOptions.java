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
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.RemoteException;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.Spinner;

import com.android.email.R;
import com.android.email.activity.ActivityHelper;
import com.android.email.activity.UiUtilities;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.SyncWindow;
import com.android.emailcommon.utility.Utility;
import com.android.mail.preferences.AccountPreferences;
import com.android.mail.utils.LogUtils;

import java.io.IOException;

public class AccountSetupOptions extends AccountSetupActivity implements OnClickListener {
    private static final String EXTRA_IS_PROCESSING_KEY = "com.android.email.is_processing";

    private Spinner mCheckFrequencyView;
    private Spinner mSyncWindowView;
    private CheckBox mNotifyView;
    private CheckBox mSyncContactsView;
    private CheckBox mSyncCalendarView;
    private CheckBox mSyncEmailView;
    private CheckBox mBackgroundAttachmentsView;
    private View mAccountSyncWindowRow;
    private boolean mDonePressed = false;
    private EmailServiceInfo mServiceInfo;
    private boolean mIsProcessing = false;

    private ProgressDialog mCreateAccountDialog;

    public static final int REQUEST_CODE_ACCEPT_POLICIES = 1;

    /** Default sync window for new EAS accounts */
    private static final int SYNC_WINDOW_EAS_DEFAULT = SyncWindow.SYNC_WINDOW_1_WEEK;

    public static void actionOptions(Activity fromActivity, SetupData setupData) {
        final Intent intent = new ForwardingIntent(fromActivity, AccountSetupOptions.class);
        intent.putExtra(SetupData.EXTRA_SETUP_DATA, setupData);
        fromActivity.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);
        setContentView(R.layout.account_setup_options);

        mCheckFrequencyView = UiUtilities.getView(this, R.id.account_check_frequency);
        mSyncWindowView = UiUtilities.getView(this, R.id.account_sync_window);
        mNotifyView = UiUtilities.getView(this, R.id.account_notify);
        mSyncContactsView = UiUtilities.getView(this, R.id.account_sync_contacts);
        mSyncCalendarView = UiUtilities.getView(this, R.id.account_sync_calendar);
        mSyncEmailView = UiUtilities.getView(this, R.id.account_sync_email);
        mSyncEmailView.setChecked(true);
        mBackgroundAttachmentsView = UiUtilities.getView(this, R.id.account_background_attachments);
        mBackgroundAttachmentsView.setChecked(true);
        UiUtilities.getView(this, R.id.previous).setOnClickListener(this);
        UiUtilities.getView(this, R.id.next).setOnClickListener(this);
        mAccountSyncWindowRow = UiUtilities.getView(this, R.id.account_sync_window_row);

        final Account account = mSetupData.getAccount();
        mServiceInfo = EmailServiceUtils.getServiceInfo(getApplicationContext(),
                account.mHostAuthRecv.mProtocol);
        final CharSequence[] frequencyValues = mServiceInfo.syncIntervals;
        final CharSequence[] frequencyEntries = mServiceInfo.syncIntervalStrings;

        // Now create the array used by the sync interval Spinner
        final SpinnerOption[] checkFrequencies = new SpinnerOption[frequencyEntries.length];
        for (int i = 0; i < frequencyEntries.length; i++) {
            checkFrequencies[i] = new SpinnerOption(
                    Integer.valueOf(frequencyValues[i].toString()), frequencyEntries[i].toString());
        }
        final ArrayAdapter<SpinnerOption> checkFrequenciesAdapter =
                new ArrayAdapter<SpinnerOption>(this, android.R.layout.simple_spinner_item,
                        checkFrequencies);
        checkFrequenciesAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCheckFrequencyView.setAdapter(checkFrequenciesAdapter);

        if (mServiceInfo.offerLookback) {
            enableLookbackSpinner();
        }

        mNotifyView.setChecked(true); // By default, we want notifications on
        SpinnerOption.setSpinnerOptionValue(mCheckFrequencyView, account.getSyncInterval());

        if (mServiceInfo.syncContacts) {
            mSyncContactsView.setVisibility(View.VISIBLE);
            mSyncContactsView.setChecked(true);
            UiUtilities.setVisibilitySafe(this, R.id.account_sync_contacts_divider, View.VISIBLE);
        }
        if (mServiceInfo.syncCalendar) {
            mSyncCalendarView.setVisibility(View.VISIBLE);
            mSyncCalendarView.setChecked(true);
            UiUtilities.setVisibilitySafe(this, R.id.account_sync_calendar_divider, View.VISIBLE);
        }

        if (!mServiceInfo.offerAttachmentPreload) {
            mBackgroundAttachmentsView.setVisibility(View.GONE);
            UiUtilities.setVisibilitySafe(this, R.id.account_background_attachments_divider,
                    View.GONE);
        }

        mIsProcessing = savedInstanceState != null &&
                savedInstanceState.getBoolean(EXTRA_IS_PROCESSING_KEY, false);
        if (mIsProcessing) {
            // We are already processing, so just show the dialog until we finish
            showCreateAccountDialog();
        } else if (mSetupData.getFlowMode() == SetupData.FLOW_MODE_FORCE_CREATE) {
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
    @SuppressWarnings("deprecation")
    private void onDone() {
        final Account account = mSetupData.getAccount();
        if (account.isSaved()) {
            // Disrupting the normal flow could get us here, but if the account is already
            // saved, we've done this work
            return;
        } else if (account.mHostAuthRecv == null) {
            throw new IllegalStateException("in AccountSetupOptions with null mHostAuthRecv");
        }

        mIsProcessing = true;
        account.setDisplayName(account.getEmailAddress());
        int newFlags = account.getFlags() & ~(Account.FLAGS_BACKGROUND_ATTACHMENTS);
        if (mServiceInfo.offerAttachmentPreload && mBackgroundAttachmentsView.isChecked()) {
            newFlags |= Account.FLAGS_BACKGROUND_ATTACHMENTS;
        }
        account.setFlags(newFlags);
        account.setSyncInterval((Integer)((SpinnerOption)mCheckFrequencyView
                .getSelectedItem()).value);
        if (mAccountSyncWindowRow.getVisibility() == View.VISIBLE) {
            account.setSyncLookback(
                    (Integer)((SpinnerOption)mSyncWindowView.getSelectedItem()).value);
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
        final boolean email = mSyncEmailView.isChecked();
        final boolean calendar = mServiceInfo.syncCalendar && mSyncCalendarView.isChecked();
        final boolean contacts = mServiceInfo.syncContacts && mSyncContactsView.isChecked();

        showCreateAccountDialog();
        Utility.runAsync(new Runnable() {
            @Override
            public void run() {
                final Context context = AccountSetupOptions.this;
                AccountSettingsUtils.commitSettings(context, account);
                EmailServiceUtils.setupAccountManagerAccount(context, account,
                        email, calendar, contacts, mAccountManagerCallback);

                // We can move the notification setting to the inbox FolderPreferences later, once
                // we know what the inbox is
                final AccountPreferences accountPreferences =
                        new AccountPreferences(context, account.getEmailAddress());
                accountPreferences.setDefaultInboxNotificationsEnabled(mNotifyView.isChecked());
            }
        });
    }

    private void showCreateAccountDialog() {
        /// Show "Creating account..." dialog
        mCreateAccountDialog = new ProgressDialog(this);
        mCreateAccountDialog.setIndeterminate(true);
        mCreateAccountDialog.setMessage(getString(R.string.account_setup_creating_account_msg));
        mCreateAccountDialog.show();
    }

    /**
     * This is called at the completion of MailService.setupAccountManagerAccount()
     */
    AccountManagerCallback<Bundle> mAccountManagerCallback = new AccountManagerCallback<Bundle>() {
        @Override
        public void run(AccountManagerFuture<Bundle> future) {
            try {
                // Block until the operation completes
                future.getResult();
                AccountSetupOptions.this.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        optionsComplete();
                    }
                });
                return;
            } catch (OperationCanceledException e) {
                LogUtils.d(Logging.LOG_TAG, "addAccount was canceled");
            } catch (IOException e) {
                LogUtils.d(Logging.LOG_TAG, "addAccount failed: " + e);
            } catch (AuthenticatorException e) {
                LogUtils.d(Logging.LOG_TAG, "addAccount failed: " + e);
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
            @Override
            public void run() {
                new AlertDialog.Builder(AccountSetupOptions.this)
                        .setIconAttribute(android.R.attr.alertDialogIcon)
                        .setTitle(getString(R.string.account_setup_failed_dlg_title))
                        .setMessage(getString(msgResId, args))
                        .setCancelable(true)
                        .setPositiveButton(
                                getString(R.string.account_setup_failed_dlg_edit_details_action),
                                new DialogInterface.OnClickListener() {
                                    @Override
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
        final AccountAuthenticatorResponse authenticatorResponse =
            mSetupData.getAccountAuthenticatorResponse();
        if (authenticatorResponse != null) {
            authenticatorResponse.onResult(null);
            mSetupData.setAccountAuthenticatorResponse(null);
        }

        // Now that AccountManager account creation is complete, clear the INCOMPLETE flag
        final Account account = mSetupData.getAccount();
        account.mFlags &= ~Account.FLAGS_INCOMPLETE;
        AccountSettingsUtils.commitSettings(AccountSetupOptions.this, account);

        // If we've got policies for this account, ask the user to accept.
        if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
            final Intent intent =
                    AccountSecurity.actionUpdateSecurityIntent(this, account.mId, false);
            startActivityForResult(intent, AccountSetupOptions.REQUEST_CODE_ACCEPT_POLICIES);
            return;
        }
        saveAccountAndFinish();

        // Update the folder list (to get our starting folders, e.g. Inbox)
        final EmailServiceProxy proxy = EmailServiceUtils.getServiceForAccount(this, account.mId);
        try {
            proxy.updateFolderList(account.mId);
        } catch (RemoteException e) {
            // It's all good
        }
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
        AsyncTask<Void, Void, Void> asyncTask = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                final AccountSetupOptions context = AccountSetupOptions.this;
                // Clear the security hold flag now
                final Account account = mSetupData.getAccount();
                account.mFlags &= ~Account.FLAGS_SECURITY_HOLD;
                AccountSettingsUtils.commitSettings(context, account);
                // Start up services based on new account(s)
                MailActivityEmail.setServicesEnabledSync(context);
                EmailServiceUtils.startService(context, account.mHostAuthRecv.mProtocol);
                // Move to final setup screen
                AccountSetupNames.actionSetNames(context, mSetupData);
                finish();
                return null;
            }
        };
        asyncTask.execute();
    }

    /**
     * Enable an additional spinner using the arrays normally handled by preferences
     */
    private void enableLookbackSpinner() {
        // Show everything
        mAccountSyncWindowRow.setVisibility(View.VISIBLE);

        // Generate spinner entries using XML arrays used by the preferences
        final CharSequence[] windowValues = getResources().getTextArray(
                R.array.account_settings_mail_window_values);
        final CharSequence[] windowEntries = getResources().getTextArray(
                R.array.account_settings_mail_window_entries);

        // Find a proper maximum for email lookback, based on policy (if we have one)
        int maxEntry = windowEntries.length;
        final Policy policy = mSetupData.getAccount().mPolicy;
        if (policy != null) {
            final int maxLookback = policy.mMaxEmailLookback;
            if (maxLookback != 0) {
                // Offset/Code   0      1      2      3      4        5
                // Entries      auto, 1 day, 3 day, 1 week, 2 week, 1 month
                // Lookback     N/A   1 day, 3 day, 1 week, 2 week, 1 month
                // Since our test below is i < maxEntry, we must set maxEntry to maxLookback + 1
                maxEntry = maxLookback + 1;
            }
        }

        // Now create the array used by the Spinner
        final SpinnerOption[] windowOptions = new SpinnerOption[maxEntry];
        int defaultIndex = -1;
        for (int i = 0; i < maxEntry; i++) {
            final int value = Integer.valueOf(windowValues[i].toString());
            windowOptions[i] = new SpinnerOption(value, windowEntries[i].toString());
            if (value == SYNC_WINDOW_EAS_DEFAULT) {
                defaultIndex = i;
            }
        }

        final ArrayAdapter<SpinnerOption> windowOptionsAdapter =
                new ArrayAdapter<SpinnerOption>(this, android.R.layout.simple_spinner_item,
                        windowOptions);
        windowOptionsAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSyncWindowView.setAdapter(windowOptionsAdapter);

        SpinnerOption.setSpinnerOptionValue(mSyncWindowView,
                mSetupData.getAccount().getSyncLookback());
        if (defaultIndex >= 0) {
            mSyncWindowView.setSelection(defaultIndex);
        }
    }
}
