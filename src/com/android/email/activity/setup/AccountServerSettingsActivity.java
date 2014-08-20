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

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;

import com.android.email.R;
import com.android.emailcommon.provider.Account;
import com.android.mail.utils.LogUtils;

public class AccountServerSettingsActivity extends AccountSetupActivity implements
        SecurityRequiredDialogFragment.Callback, CheckSettingsErrorDialogFragment.Callback,
        AccountCheckSettingsFragment.Callback, AccountServerBaseFragment.Callback,
        CheckSettingsProgressDialogFragment.Callback {

    /**
     * {@link com.android.emailcommon.provider.Account}
     */
    private static final String EXTRA_ACCOUNT = "account";
    /**
     * Incoming or Outgoing settings?
     */
    private static final String EXTRA_WHICH_SETTINGS = "whichSettings";
    private static final String INCOMING_SETTINGS = "incoming";
    private static final String OUTGOING_SETTINGS = "outgoing";

    private AccountServerBaseFragment mAccountServerFragment;

    public static Intent getIntentForIncoming(final Context context, final Account account) {
        final Intent intent = new Intent(context, AccountServerSettingsActivity.class);
        intent.putExtra(EXTRA_ACCOUNT, account);
        intent.putExtra(EXTRA_WHICH_SETTINGS, INCOMING_SETTINGS);
        return intent;
    }

    public static Intent getIntentForOutgoing(final Context context, final Account account) {
        final Intent intent = new Intent(context, AccountServerSettingsActivity.class);
        intent.putExtra(EXTRA_ACCOUNT, account);
        intent.putExtra(EXTRA_WHICH_SETTINGS, OUTGOING_SETTINGS);
        return intent;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mSetupData.setFlowMode(SetupDataFragment.FLOW_MODE_EDIT);

        setContentView(R.layout.account_server_settings);
        setFinishOnTouchOutside(false);

        if (savedInstanceState == null) {
            final Account account = getIntent().getParcelableExtra(EXTRA_ACCOUNT);
            if (account == null) {
                throw new IllegalArgumentException("No account present in intent");
            }
            mSetupData.setAccount(account);
            final String whichSettings = getIntent().getStringExtra(EXTRA_WHICH_SETTINGS);
            final AccountServerBaseFragment f;
            if (OUTGOING_SETTINGS.equals(whichSettings)) {
                f = AccountSetupOutgoingFragment.newInstance(true);
            } else {
                f = AccountSetupIncomingFragment.newInstance(true);
            }
            getFragmentManager().beginTransaction()
                    .add(R.id.account_server_settings_container, f)
                    .commit();
        }
    }

    @Override
    public void onAttachFragment(Fragment fragment) {
        super.onAttachFragment(fragment);
        if (fragment instanceof AccountServerBaseFragment) {
            mAccountServerFragment = (AccountServerBaseFragment) fragment;
        }
    }

    public AccountServerBaseFragment getAccountServerFragment() {
        return mAccountServerFragment;
    }

    private void forceBack() {
        super.onBackPressed();
    }

    /**
     * Any time we exit via this pathway we put up the exit-save-changes dialog.
     */
    @Override
    public void onBackPressed() {
        final AccountServerBaseFragment accountServerFragment = getAccountServerFragment();
        if (accountServerFragment != null) {
            if (accountServerFragment.haveSettingsChanged()) {
                UnsavedChangesDialogFragment dialogFragment =
                        UnsavedChangesDialogFragment.newInstanceForBack();
                dialogFragment.show(getFragmentManager(), UnsavedChangesDialogFragment.TAG);
                return; // Prevent "back" from being handled
            }
        }
        super.onBackPressed();
    }

    @Override
    public void onNextButton() {}

    /**
     * Save process is done, dismiss the fragment.
     */
    @Override
    public void onAccountServerSaveComplete() {
        super.onBackPressed();
    }

    @Override
    public void onAccountServerUIComplete(int checkMode) {
        final Fragment checkerDialog = CheckSettingsProgressDialogFragment.newInstance(checkMode);
        final Fragment checkerFragment = AccountCheckSettingsFragment.newInstance(checkMode);
        getFragmentManager().beginTransaction()
                .add(checkerDialog, CheckSettingsProgressDialogFragment.TAG)
                .add(checkerFragment, AccountCheckSettingsFragment.TAG)
                .commit();
    }

    @Override
    public void onCheckSettingsProgressDialogCancel() {
        dismissCheckSettingsFragment();
    }

    /**
     * After verifying a new server configuration as OK, we return here and continue. This kicks
     * off the save process.
     */
    @Override
    public void onCheckSettingsComplete() {
        dismissCheckSettingsFragment();
        final AccountServerBaseFragment f = getAccountServerFragment();
        if (f != null) {
            f.saveSettings();
        }
    }

    @Override
    public void onCheckSettingsSecurityRequired(String hostName) {
        dismissCheckSettingsFragment();
        SecurityRequiredDialogFragment.newInstance(hostName)
                .show(getFragmentManager(), SecurityRequiredDialogFragment.TAG);
    }

    @Override
    public void onCheckSettingsError(int reason, String message) {
        dismissCheckSettingsFragment();
        CheckSettingsErrorDialogFragment.newInstance(reason, message)
                .show(getFragmentManager(), CheckSettingsErrorDialogFragment.TAG);
    }

    @Override
    public void onCheckSettingsAutoDiscoverComplete(int result) {
        throw new IllegalStateException();
    }

    private void dismissCheckSettingsFragment() {
        final Fragment f =
                getFragmentManager().findFragmentByTag(AccountCheckSettingsFragment.TAG);
        final Fragment d =
                getFragmentManager().findFragmentByTag(CheckSettingsProgressDialogFragment.TAG);
        getFragmentManager().beginTransaction()
                .remove(f)
                .remove(d)
                .commit();
    }

    @Override
    public void onSecurityRequiredDialogResult(boolean ok) {
        if (ok) {
            final AccountServerBaseFragment f = getAccountServerFragment();
            if (f != null) {
                f.saveSettings();
            }
        }
        // else just stay here
    }

    @Override
    public void onCheckSettingsErrorDialogEditSettings() {
        // Just stay here
    }

    @Override
    public void onCheckSettingsErrorDialogEditCertificate() {
        final AccountServerBaseFragment f = getAccountServerFragment();
        if (f instanceof AccountSetupIncomingFragment) {
            AccountSetupIncomingFragment asif = (AccountSetupIncomingFragment) f;
            asif.onCertificateRequested();
        } else {
            LogUtils.wtf(LogUtils.TAG, "Tried to change cert on non-incoming screen?");
        }
    }

    /**
     * Dialog fragment to show "exit with unsaved changes?" dialog
     */
    public static class UnsavedChangesDialogFragment extends DialogFragment {
        final static String TAG = "UnsavedChangesDialogFragment";

        /**
         * Creates a save changes dialog when the user navigates "back".
         * {@link #onBackPressed()} defines in which case this may be triggered.
         */
        public static UnsavedChangesDialogFragment newInstanceForBack() {
            return new UnsavedChangesDialogFragment();
        }

        // Force usage of newInstance()
        public UnsavedChangesDialogFragment() {}

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final AccountServerSettingsActivity activity =
                    (AccountServerSettingsActivity) getActivity();

            return new AlertDialog.Builder(activity)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setTitle(android.R.string.dialog_alert_title)
                    .setMessage(R.string.account_settings_exit_server_settings)
                    .setPositiveButton(
                            android.R.string.ok,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    activity.forceBack();
                                    dismiss();
                                }
                            })
                    .setNegativeButton(
                            activity.getString(android.R.string.cancel), null)
                    .create();
        }
    }
}
