/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.email.Controller;
import com.android.email.Email;
import com.android.email.NotificationController;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.activity.ActivityHelper;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;

import java.util.List;

/**
 * Handles account preferences, using multi-pane arrangement when possible.
 *
 * This activity uses the following fragments:
 *   AccountSettingsFragment
 *   Account{Incoming/Outgoing/Exchange}Fragment
 *   AccountCheckSettingsFragment
 *   GeneralPreferences
 *   DebugFragment
 *
 * TODO: In Account settings in Phone UI, change title
 * TODO: Rework all remaining calls to DB from UI thread
 * TODO: Delete account - on single-pane view (phone UX) the account list doesn't update properly
 * TODO: Handle dynamic changes to the account list (exit if necessary).  It probably makes
 *       sense to use a loader for the accounts list, because it would provide better support for
 *       dealing with accounts being added/deleted and triggering the header reload.
 */
public class AccountSettingsXL extends PreferenceActivity implements OnClickListener {

    // Intent extras for our internal activity launch
    /* package */ static final String EXTRA_ACCOUNT_ID = "AccountSettingsXL.account_id";
    private static final String EXTRA_ENABLE_DEBUG = "AccountSettingsXL.enable_debug";

    // Intent extras for launch directly from system account manager
    // NOTE: This string must match the one in res/xml/account_preferences.xml
    private static final String ACTION_ACCOUNT_MANAGER_ENTRY =
        "com.android.email.activity.setup.ACCOUNT_MANAGER_ENTRY";
    // NOTE: This constant should eventually be defined in android.accounts.Constants
    private static final String EXTRA_ACCOUNT_MANAGER_ACCOUNT = "account";

    // Key codes used to open a debug settings fragment.
    private static final int[] SECRET_KEY_CODES = {
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_U,
            KeyEvent.KEYCODE_G
            };
    private int mSecretKeyCodeIndex = 0;

    // Support for account-by-name lookup
    private static final String SELECTION_ACCOUNT_EMAIL_ADDRESS =
        AccountColumns.EMAIL_ADDRESS + "=?";

    // When the user taps "Email Preferences" 10 times in a row, we'll enable the debug settings.
    private int mNumGeneralHeaderClicked = 0;

    private long mRequestedAccountId;
    private Header mRequestedAccountHeader;
    private Header[] mAccountListHeaders;
    private Header mAppPreferencesHeader;
    private int mCurrentHeaderPosition;
    /* package */ Fragment mCurrentFragment;
    private long mDeletingAccountId = -1;
    private boolean mShowDebugMenu;
    private Button mAddAccountButton;
    private List<Header> mGeneratedHeaders;

    // Async Tasks
    private LoadAccountListTask mLoadAccountListTask;
    private GetAccountIdFromAccountTask mGetAccountIdFromAccountTask;

    // Specific callbacks used by settings fragments
    private final AccountSettingsFragmentCallback mAccountSettingsFragmentCallback
            = new AccountSettingsFragmentCallback();
    private final AccountServerSettingsFragmentCallback mAccountServerSettingsFragmentCallback
            = new AccountServerSettingsFragmentCallback();

    /**
     * Display (and edit) settings for a specific account, or -1 for any/all accounts
     */
    public static void actionSettings(Activity fromActivity, long accountId) {
        Intent i = new Intent(fromActivity, AccountSettingsXL.class);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        fromActivity.startActivity(i);
    }

    /**
     * Create and return an intent to display (and edit) settings for a specific account, or -1
     * for any/all accounts
     */
    public static Intent createAccountSettingsIntent(Context context, long accountId) {
        Intent i = new Intent(context, AccountSettingsXL.class);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        return i;
    }

    /**
     * Launch generic settings and pre-enable the debug preferences
     */
    public static void actionSettingsWithDebug(Context fromContext) {
        Intent i = new Intent(fromContext, AccountSettingsXL.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(EXTRA_ENABLE_DEBUG, true);
        fromContext.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);

        Intent i = getIntent();
        if (savedInstanceState == null) {
            // If we are not restarting from a previous instance, we need to
            // figure out the initial prefs to show.  (Otherwise, we want to
            // continue showing whatever the user last selected.)
            if (ACTION_ACCOUNT_MANAGER_ENTRY.equals(i.getAction())) {
                // This case occurs if we're changing account settings from Settings -> Accounts
                mGetAccountIdFromAccountTask =
                    (GetAccountIdFromAccountTask) new GetAccountIdFromAccountTask().execute(i);
            } else {
                // Otherwise, we're called from within the Email app and look for our extra
                mRequestedAccountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
            }
        }
        mShowDebugMenu = i.getBooleanExtra(EXTRA_ENABLE_DEBUG, false);

        // Add Account as header list footer
        // TODO: This probably should be some sort of themed layout with a button in it
        if (hasHeaders()) {
            mAddAccountButton = new Button(this);
            mAddAccountButton.setText(R.string.add_account_action);
            mAddAccountButton.setOnClickListener(this);
            mAddAccountButton.setEnabled(false);
            setListFooter(mAddAccountButton);
        }

    }

    @Override
    public void onResume() {
        super.onResume();
        updateAccounts();

        // When we're resuming, enable/disable the add account button
        if (mAddAccountButton != null && hasHeaders()) {
            mAddAccountButton.setEnabled(shouldShowNewAccount());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Utility.cancelTaskInterrupt(mLoadAccountListTask);
        mLoadAccountListTask = null;
        Utility.cancelTaskInterrupt(mGetAccountIdFromAccountTask);
        mGetAccountIdFromAccountTask = null;
    }

    /**
     * Listen for secret sequence and, if heard, enable debug menu
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (event.getKeyCode() == SECRET_KEY_CODES[mSecretKeyCodeIndex]) {
            mSecretKeyCodeIndex++;
            if (mSecretKeyCodeIndex == SECRET_KEY_CODES.length) {
                mSecretKeyCodeIndex = 0;
                enableDebugMenu();
            }
        } else {
            mSecretKeyCodeIndex = 0;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onClick(View v) {
        if (v == mAddAccountButton) {
            onAddNewAccount();
        }
    }

    /**
     * If the caller requested a specific account to be edited, switch to it.  This is a one-shot,
     * so the user is free to edit another account as well.
     */
    @Override
    public Header onGetNewHeader() {
        Header result = mRequestedAccountHeader;
        mRequestedAccountHeader = null;
        return result;
    }

    private void enableDebugMenu() {
        mShowDebugMenu = true;
        invalidateHeaders();
    }

    /**
     * Decide if "add account" should be shown
     */
    private boolean shouldShowNewAccount() {
        // If in single pane mode, only add accounts at top level
        if (!isMultiPane()) {
            if (!hasHeaders()) return false;
        } else {
            // If in multi pane mode, only add accounts when showing a top level fragment
            // Note: null is OK; This is the case when we first launch the activity
            if ((mCurrentFragment != null)
                && !(mCurrentFragment instanceof GeneralPreferences)
                && !(mCurrentFragment instanceof DebugFragment)
                && !(mCurrentFragment instanceof AccountSettingsFragment)) return false;
        }
        return true;
    }

    private void onAddNewAccount() {
        AccountSetupBasics.actionNewAccount(this);
    }

    /**
     * Start the async reload of the accounts list (if the headers are being displayed)
     */
    private void updateAccounts() {
        if (hasHeaders()) {
            Utility.cancelTaskInterrupt(mLoadAccountListTask);
            mLoadAccountListTask = (LoadAccountListTask)
                    new LoadAccountListTask().execute(mDeletingAccountId);
        }
    }

    /**
     * Write the current header (accounts) array into the one provided by the PreferenceActivity.
     * Skip any headers that match mDeletingAccountId (this is a quick-hide algorithm while a
     * background thread works on deleting the account).  Also sets mRequestedAccountHeader if
     * we find the requested account (by id).
     */
    @Override
    public void onBuildHeaders(List<Header> target) {
        // Assume the account is unspecified
        mRequestedAccountHeader = null;

        // Always add app preferences as first header
        target.clear();
        target.add(getAppPreferencesHeader());

        // Then add zero or more account headers as necessary
        if (mAccountListHeaders != null) {
            int headerCount = mAccountListHeaders.length;
            for (int index = 0; index < headerCount; index++) {
                Header header = mAccountListHeaders[index];
                if (header.id != HEADER_ID_UNDEFINED) {
                    if (header.id != mDeletingAccountId) {
                        target.add(header);
                        if (header.id == mRequestedAccountId) {
                            mRequestedAccountHeader = header;
                            mRequestedAccountId = -1;
                        }
                    }
                }
            }
        }

        // finally, if debug header is enabled, show it
        if (mShowDebugMenu) {
            // setup lightweight header for debugging
            Header debugHeader = new Header();
            debugHeader.title = getText(R.string.debug_title);
            debugHeader.summary = null;
            debugHeader.iconRes = 0;
            debugHeader.fragment = DebugFragment.class.getCanonicalName();
            debugHeader.fragmentArguments = null;
            target.add(debugHeader);
        }

        // Save for later use (see forceSwitch)
        mGeneratedHeaders = target;
    }

    /**
     * Generate and return the first header, for app preferences
     */
    private Header getAppPreferencesHeader() {
        // Set up fixed header for general settings
        if (mAppPreferencesHeader == null) {
            mAppPreferencesHeader = new Header();
            mAppPreferencesHeader.title = getText(R.string.header_label_general_preferences);
            mAppPreferencesHeader.summary = null;
            mAppPreferencesHeader.iconRes = 0;
            mAppPreferencesHeader.fragment = GeneralPreferences.class.getCanonicalName();
            mAppPreferencesHeader.fragmentArguments = null;
        }
        return mAppPreferencesHeader;
    }

    /**
     * This AsyncTask reads the accounts list and generates the headers.  When the headers are
     * ready, we'll trigger PreferenceActivity to refresh the account list with them.
     *
     * TODO: Smaller projection
     * TODO: Convert to Loader
     * TODO: Write a test, including operation of deletingAccountId param
     */
    private class LoadAccountListTask extends AsyncTask<Long, Void, Object[]> {

        @Override
        protected Object[] doInBackground(Long... params) {
            Header[] result = null;
            Boolean deletingAccountFound = false;
            long deletingAccountId = params[0];

            Cursor c = getContentResolver().query(
                    EmailContent.Account.CONTENT_URI,
                    EmailContent.Account.CONTENT_PROJECTION, null, null, null);
            try {
                int index = 0;
                int headerCount = c.getCount();
                result = new Header[headerCount];

                while (c.moveToNext()) {
                    long accountId = c.getLong(Account.CONTENT_ID_COLUMN);
                    if (accountId == deletingAccountId) {
                        deletingAccountFound = true;
                        continue;
                    }
                    String title = c.getString(Account.CONTENT_DISPLAY_NAME_COLUMN);
                    String summary = c.getString(Account.CONTENT_EMAIL_ADDRESS_COLUMN);
                    Header newHeader = new Header();
                    newHeader.id = accountId;
                    newHeader.title = title;
                    newHeader.summary = summary;
                    newHeader.fragment = AccountSettingsFragment.class.getCanonicalName();
                    newHeader.fragmentArguments = AccountSettingsFragment.buildArguments(accountId);

                    result[index++] = newHeader;
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
            return new Object[] { result, deletingAccountFound };
        }

        @Override
        protected void onPostExecute(Object[] result) {
            if (this.isCancelled() || result == null) return;
            // Extract the results
            Header[] headers = (Header[]) result[0];
            boolean deletingAccountFound = (Boolean) result[1];
            // report the settings
            mAccountListHeaders = headers;
            AccountSettingsXL.this.invalidateHeaders();
            if (!deletingAccountFound) {
                mDeletingAccountId = -1;
            }
        }
    }

    /**
     * Called when the user selects an item in the header list.  Handles save-data cases as needed
     *
     * @param header The header that was selected.
     * @param position The header's position in the list.
     */
    @Override
    public void onHeaderClick(Header header, int position) {
        // special case when exiting the server settings fragments
        if (mCurrentFragment instanceof AccountServerBaseFragment) {
            if (position != mCurrentHeaderPosition) {
                UnsavedChangesDialogFragment dialogFragment =
                    UnsavedChangesDialogFragment.newInstance(position);
                dialogFragment.show(getFragmentManager(), UnsavedChangesDialogFragment.TAG);
            }
            return;
        }

        // Secret keys:  Click 10x to enable debug settings
        if (position == 0) {
            mNumGeneralHeaderClicked++;
            if (mNumGeneralHeaderClicked == 10) {
                enableDebugMenu();
            }
        } else {
            mNumGeneralHeaderClicked = 0;
        }

        // Process header click normally
        mCurrentHeaderPosition = position;
        super.onHeaderClick(header, position);
    }

    /**
     * Switch to a specific header without checking for server settings fragments as done
     * in {@link #onHeaderClick(Header, int)}.  Called after we interrupted a header switch
     * with a dialog, and the user OK'd it.
     */
    private void forceSwitchHeader(int newPosition) {
        mCurrentHeaderPosition = newPosition;
        Header header = mGeneratedHeaders.get(newPosition);
        switchToHeader(header.fragment, header.fragmentArguments);
    }

    /**
     * Called by fragments at onAttach time
     */
    public void onAttach(Fragment f) {
        mCurrentFragment = f;
        // dispatch per-fragment setup
        if (f instanceof AccountSettingsFragment) {
            AccountSettingsFragment asf = (AccountSettingsFragment) f;
            asf.setCallback(mAccountSettingsFragmentCallback);
        } else if (mCurrentFragment instanceof AccountServerBaseFragment) {
            AccountServerBaseFragment asbf = (AccountServerBaseFragment) mCurrentFragment;
            asbf.setCallback(mAccountServerSettingsFragmentCallback);
        }

        // When we're changing fragments, enable/disable the add account button
        if (mAddAccountButton != null && hasHeaders()) {
            mAddAccountButton.setEnabled(shouldShowNewAccount());
        }
    }

    /**
     * Callbacks for AccountSettingsFragment
     */
    private class AccountSettingsFragmentCallback implements AccountSettingsFragment.Callback {
        public void onIncomingSettings(Account account) {
            AccountSettingsXL.this.onIncomingSettings(account);
        }
        public void onOutgoingSettings(Account account) {
            AccountSettingsXL.this.onOutgoingSettings(account);
        }
        public void abandonEdit() {
            finish();
        }
        public void deleteAccount(Account account) {
            AccountSettingsXL.this.deleteAccount(account);
        }
    }

    /**
     * Callbacks for AccountServerSettingsFragmentCallback
     */
    private class AccountServerSettingsFragmentCallback
            implements AccountServerBaseFragment.Callback {
        @Override
        public void onEnableProceedButtons(boolean enable) {
            // This is not used - it's a callback for the legacy activities
        }

        @Override
        public void onProceedNext(int checkMode, AccountServerBaseFragment target) {
            AccountCheckSettingsFragment checkerFragment =
                AccountCheckSettingsFragment.newInstance(checkMode, target);
            startPreferenceFragment(checkerFragment, true);
        }

        /**
         * After verifying a new server configuration as OK, we return here and continue.  This
         * simply does a "back" to exit the settings screen.
         */
        public void onCheckSettingsComplete(int result, int setupMode) {
            if (result == AccountCheckSettingsFragment.CHECK_SETTINGS_OK) {
                onBackPressed();
            }
        }
    }

    /**
     * Dispatch to edit incoming settings.
     *
     * TODO: Cache the store lookup earlier, in an AsyncTask, to avoid this DB access
     * TODO: Make things less hardwired
     */
    public void onIncomingSettings(Account account) {
        try {
            Store store = Store.getInstance(account.getStoreUri(this), getApplication(), null);
            if (store != null) {
                Class<? extends android.app.Activity> setting = store.getSettingActivityClass();
                if (setting != null) {
//                    java.lang.reflect.Method m = setting.getMethod("actionEditIncomingSettings",
//                            Activity.class, int.class, Account.class);
//                    m.invoke(null, this, SetupData.FLOW_MODE_EDIT, account);
                    SetupData.init(SetupData.FLOW_MODE_EDIT, account);
                    Fragment f = null;
                    if (setting.equals(AccountSetupIncoming.class)) {
                        f = new AccountSetupIncomingFragment();
                    } else if (setting.equals(AccountSetupExchange.class)) {
                        f = new AccountSetupExchangeFragment();
                    }
                    startPreferenceFragment(f, true);
                }
            }
        } catch (Exception e) {
            Log.d(Email.LOG_TAG, "Error while trying to invoke store settings.", e);
        }
    }

    /**
     * Dispatch to edit outgoing settings.
     *
     * TODO: Cache the store lookup earlier, in an AsyncTask, to avoid this DB access
     * TODO: Make things less hardwired
     */
    public void onOutgoingSettings(Account account) {
        try {
            Sender sender = Sender.getInstance(getApplication(), account.getSenderUri(this));
            if (sender != null) {
                Class<? extends android.app.Activity> setting = sender.getSettingActivityClass();
                if (setting != null) {
//                    java.lang.reflect.Method m = setting.getMethod("actionEditOutgoingSettings",
//                            Activity.class, int.class, Account.class);
//                    m.invoke(null, this, SetupData.FLOW_MODE_EDIT, account);
                    SetupData.init(SetupData.FLOW_MODE_EDIT, account);
                    Fragment f = null;
                    if (setting.equals(AccountSetupOutgoing.class)) {
                        f = new AccountSetupOutgoingFragment();
                    }
                    startPreferenceFragment(f, true);
                }
            }
        } catch (Exception e) {
            Log.d(Email.LOG_TAG, "Error while trying to invoke sender settings.", e);
        }
    }

    /**
     * Delete the selected account
     */
    public void deleteAccount(Account account) {
        // Kick off the work to actually delete the account

        // Clear notifications for the account.
        NotificationController.getInstance(this).cancelNewMessageNotification(account.mId);

        // Delete the account (note, this is async.  Would be nice to get a callback.
        Controller.getInstance(this).deleteAccount(account.mId);

        // Then update the UI as appropriate:
        // If single pane, return to the header list.  If multi, rebuild header list
        if (isMultiPane()) {
            Header prefsHeader = getAppPreferencesHeader();
            this.switchToHeader(prefsHeader.fragment, prefsHeader.fragmentArguments);
            mDeletingAccountId = account.mId;
            invalidateHeaders();
        } else {
            // We should only be calling this while showing AccountSettingsFragment,
            // so a finish() should bring us back to headers.  No point hiding the deleted account.
            finish();
        }
    }

    /**
     * This AsyncTask looks up an account based on its email address (which is what we get from
     * the Account Manager).  When the account id is determined, we refresh the header list,
     * which will select the preferences for that account.
     */
    private class GetAccountIdFromAccountTask extends AsyncTask<Intent, Void, Long> {

        @Override
        protected Long doInBackground(Intent... params) {
            Intent intent = params[0];
            android.accounts.Account acct =
                (android.accounts.Account) intent.getParcelableExtra(EXTRA_ACCOUNT_MANAGER_ACCOUNT);
            return Utility.getFirstRowLong(AccountSettingsXL.this, Account.CONTENT_URI,
                    Account.ID_PROJECTION, SELECTION_ACCOUNT_EMAIL_ADDRESS, new String[] {acct.name},
                    null, Account.ID_PROJECTION_COLUMN, -1L);
        }

        @Override
        protected void onPostExecute(Long accountId) {
            if (accountId != -1 && !isCancelled()) {
                mRequestedAccountId = accountId;
                AccountSettingsXL.this.invalidateHeaders();
            }
        }
    }

    /**
     * Dialog fragment to show "exit with unsaved changes?" dialog
     */
    public static class UnsavedChangesDialogFragment extends DialogFragment {
        private final static String TAG = "UnsavedChangesDialogFragment";

        // Argument bundle keys
        private final static String BUNDLE_KEY_NEW_HEADER = "UnsavedChangesDialogFragment.Header";

        /**
         * Create the dialog with parameters
         */
        public static UnsavedChangesDialogFragment newInstance(int newPosition) {
            UnsavedChangesDialogFragment f = new UnsavedChangesDialogFragment();
            Bundle b = new Bundle();
            b.putInt(BUNDLE_KEY_NEW_HEADER, newPosition);
            f.setArguments(b);
            return f;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final AccountSettingsXL activity = (AccountSettingsXL) getActivity();
            final int newPosition = getArguments().getInt(BUNDLE_KEY_NEW_HEADER);

            return new AlertDialog.Builder(activity)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(android.R.string.dialog_alert_title)
                .setMessage(R.string.account_settings_exit_server_settings)
                .setPositiveButton(
                        R.string.okay_action,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                activity.forceSwitchHeader(newPosition);
                                dismiss();
                            }
                        })
                .setNegativeButton(
                        activity.getString(R.string.cancel_action),
                        null)
                .create();
        }
    }

}
