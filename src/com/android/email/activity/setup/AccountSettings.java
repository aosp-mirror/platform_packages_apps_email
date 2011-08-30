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

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;

import com.android.email.Controller;
import com.android.email.R;
import com.android.email.activity.ActivityHelper;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.utility.IntentUtilities;
import com.android.emailcommon.utility.Utility;

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
 * TODO: Delete account - on single-pane view (phone UX) the account list doesn't update properly
 * TODO: Handle dynamic changes to the account list (exit if necessary).  It probably makes
 *       sense to use a loader for the accounts list, because it would provide better support for
 *       dealing with accounts being added/deleted and triggering the header reload.
 */
public class AccountSettings extends PreferenceActivity {
    /*
     * Intent to open account settings for account=1
        adb shell am start -a android.intent.action.EDIT \
            -d '"content://ui.email.android.com/settings?ACCOUNT_ID=1"'
     */

    // Intent extras for our internal activity launch
    private static final String EXTRA_ENABLE_DEBUG = "AccountSettings.enable_debug";
    private static final String EXTRA_LOGIN_WARNING_FOR_ACCOUNT = "AccountSettings.for_account";
    private static final String EXTRA_TITLE = "AccountSettings.title";

    // Intent extras for launch directly from system account manager
    // NOTE: This string must match the one in res/xml/account_preferences.xml
    private static final String ACTION_ACCOUNT_MANAGER_ENTRY =
        "com.android.email.activity.setup.ACCOUNT_MANAGER_ENTRY";
    // NOTE: This constant should eventually be defined in android.accounts.Constants
    private static final String EXTRA_ACCOUNT_MANAGER_ACCOUNT = "account";

    // Key for arguments bundle for QuickResponse editing
    private static final String QUICK_RESPONSE_ACCOUNT_KEY = "account";

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
    /* package */ Fragment mCurrentFragment;
    private long mDeletingAccountId = -1;
    private boolean mShowDebugMenu;
    private List<Header> mGeneratedHeaders;

    // Async Tasks
    private LoadAccountListTask mLoadAccountListTask;
    private GetAccountIdFromAccountTask mGetAccountIdFromAccountTask;
    private ContentObserver mAccountObserver;

    // Specific callbacks used by settings fragments
    private final AccountSettingsFragmentCallback mAccountSettingsFragmentCallback
            = new AccountSettingsFragmentCallback();
    private final AccountServerSettingsFragmentCallback mAccountServerSettingsFragmentCallback
            = new AccountServerSettingsFragmentCallback();

    /**
     * Display (and edit) settings for a specific account, or -1 for any/all accounts
     */
    public static void actionSettings(Activity fromActivity, long accountId) {
        fromActivity.startActivity(createAccountSettingsIntent(fromActivity, accountId, null));
    }

    /**
     * Create and return an intent to display (and edit) settings for a specific account, or -1
     * for any/all accounts.  If an account name string is provided, a warning dialog will be
     * displayed as well.
     */
    public static Intent createAccountSettingsIntent(Context context, long accountId,
            String loginWarningAccountName) {
        final Uri.Builder b = IntentUtilities.createActivityIntentUrlBuilder("settings");
        IntentUtilities.setAccountId(b, accountId);
        Intent i = new Intent(Intent.ACTION_EDIT, b.build());
        if (loginWarningAccountName != null) {
            i.putExtra(EXTRA_LOGIN_WARNING_FOR_ACCOUNT, loginWarningAccountName);
        }
        return i;
    }

    /**
     * Launch generic settings and pre-enable the debug preferences
     */
    public static void actionSettingsWithDebug(Context fromContext) {
        Intent i = new Intent(fromContext, AccountSettings.class);
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
                        (GetAccountIdFromAccountTask) new GetAccountIdFromAccountTask()
                        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, i);
            } else {
                // Otherwise, we're called from within the Email app and look for our extras
                mRequestedAccountId = IntentUtilities.getAccountIdFromIntent(i);
                String loginWarningAccount = i.getStringExtra(EXTRA_LOGIN_WARNING_FOR_ACCOUNT);
                if (loginWarningAccount != null) {
                    // Show dialog (first time only - don't re-show on a rotation)
                    LoginWarningDialog dialog = LoginWarningDialog.newInstance(loginWarningAccount);
                    dialog.show(getFragmentManager(), "loginwarning");
                }
            }
        }
        mShowDebugMenu = i.getBooleanExtra(EXTRA_ENABLE_DEBUG, false);

        String title = i.getStringExtra(EXTRA_TITLE);
        if (title != null) {
            setTitle(title);
        }

        getActionBar().setDisplayOptions(
                ActionBar.DISPLAY_HOME_AS_UP, ActionBar.DISPLAY_HOME_AS_UP);

        mAccountObserver = new ContentObserver(Utility.getMainThreadHandler()) {
            @Override
            public void onChange(boolean selfChange) {
                updateAccounts();
            }
        };
    }

    @Override
    public void onResume() {
        super.onResume();
        getContentResolver().registerContentObserver(Account.NOTIFIER_URI, true, mAccountObserver);
        updateAccounts();
    }

    @Override
    public void onPause() {
        super.onPause();
        getContentResolver().unregisterContentObserver(mAccountObserver);
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
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.account_settings_add_account_option, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        return shouldShowNewAccount();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                // The app icon on the action bar is pressed.  Just emulate a back press.
                // TODO: this should navigate to the main screen, even if a sub-setting is open.
                // But we shouldn't just finish(), as we want to show "discard changes?" dialog
                // when necessary.
                onBackPressed();
                break;
            case R.id.add_new_account:
                onAddNewAccount();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public Intent onBuildStartFragmentIntent(String fragmentName, Bundle args,
            int titleRes, int shortTitleRes) {
        Intent result = super.onBuildStartFragmentIntent(
                fragmentName, args, titleRes, shortTitleRes);

        // When opening a sub-settings page (e.g. account specific page), see if we want to modify
        // the activity title.
        String title = AccountSettingsFragment.getTitleFromArgs(args);
        if ((titleRes == 0) && (title != null)) {
            result.putExtra(EXTRA_TITLE, title);
        }
        return result;
    }

    /**
     * Any time we exit via this pathway, and we are showing a server settings fragment,
     * we put up the exit-save-changes dialog.  This will work for the following cases:
     *   Cancel button
     *   Back button
     *   Up arrow in application icon
     * It will *not* apply in the following cases:
     *   Click the parent breadcrumb - need to find a hook for this
     *   Click in the header list (e.g. another account) - handled elsewhere
     */
    @Override
    public void onBackPressed() {
        if (mCurrentFragment instanceof AccountServerBaseFragment) {
            boolean changed = ((AccountServerBaseFragment) mCurrentFragment).haveSettingsChanged();
            if (changed) {
                UnsavedChangesDialogFragment dialogFragment =
                        UnsavedChangesDialogFragment.newInstanceForBack();
                dialogFragment.show(getFragmentManager(), UnsavedChangesDialogFragment.TAG);
                return; // Prevent "back" from being handled
            }
        }
        super.onBackPressed();
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
        if (!onIsMultiPane()) {
            return hasHeaders();
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
                    new LoadAccountListTask().executeOnExecutor(
                            AsyncTask.THREAD_POOL_EXECUTOR, mDeletingAccountId);
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
                if (header != null && header.id != HEADER_ID_UNDEFINED) {
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
     * The array generated and stored in mAccountListHeaders may be sparse so any readers should
     * check for and skip over null entries, and should not assume array length is # of accounts.
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
                    Account.CONTENT_URI,
                    Account.CONTENT_PROJECTION, null, null, null);
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
                    String name = c.getString(Account.CONTENT_DISPLAY_NAME_COLUMN);
                    String email = c.getString(Account.CONTENT_EMAIL_ADDRESS_COLUMN);
                    Header newHeader = new Header();
                    newHeader.id = accountId;
                    newHeader.title = name;
                    newHeader.summary = email;
                    newHeader.fragment = AccountSettingsFragment.class.getCanonicalName();
                    newHeader.fragmentArguments =
                            AccountSettingsFragment.buildArguments(accountId, email);

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
            if (isCancelled() || result == null) return;
            // Extract the results
            Header[] headers = (Header[]) result[0];
            boolean deletingAccountFound = (Boolean) result[1];
            // report the settings
            mAccountListHeaders = headers;
            invalidateHeaders();
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
            boolean changed = ((AccountServerBaseFragment)mCurrentFragment).haveSettingsChanged();
            if (changed) {
                UnsavedChangesDialogFragment dialogFragment =
                    UnsavedChangesDialogFragment.newInstanceForHeader(position);
                dialogFragment.show(getFragmentManager(), UnsavedChangesDialogFragment.TAG);
                return;
            }
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
        super.onHeaderClick(header, position);
    }

    /**
     * Switch to a specific header without checking for server settings fragments as done
     * in {@link #onHeaderClick(Header, int)}.  Called after we interrupted a header switch
     * with a dialog, and the user OK'd it.
     */
    private void forceSwitchHeader(int position) {
        // Clear the current fragment; we're navigating away
        mCurrentFragment = null;
        // Ensure the UI visually shows the correct header selected
        setSelection(position);
        Header header = mGeneratedHeaders.get(position);
        switchToHeader(header);
    }

    /**
     * Forcefully go backward in the stack. This may potentially discard unsaved settings.
     */
    private void forceBack() {
        // Clear the current fragment; we're navigating away
        mCurrentFragment = null;
        onBackPressed();
    }

    @Override
    public void onAttachFragment(Fragment f) {
        super.onAttachFragment(f);

        if (f instanceof AccountSettingsFragment) {
            AccountSettingsFragment asf = (AccountSettingsFragment) f;
            asf.setCallback(mAccountSettingsFragmentCallback);
        } else if (f instanceof AccountServerBaseFragment) {
            AccountServerBaseFragment asbf = (AccountServerBaseFragment) f;
            asbf.setCallback(mAccountServerSettingsFragmentCallback);
        } else {
            // Possibly uninteresting fragment, such as a dialog.
            return;
        }
        mCurrentFragment = f;

        // When we're changing fragments, enable/disable the add account button
        invalidateOptionsMenu();
    }

    /**
     * Callbacks for AccountSettingsFragment
     */
    private class AccountSettingsFragmentCallback implements AccountSettingsFragment.Callback {
        @Override
        public void onSettingsChanged(Account account, String preference, Object value) {
            AccountSettings.this.onSettingsChanged(account, preference, value);
        }
        @Override
        public void onEditQuickResponses(Account account) {
            AccountSettings.this.onEditQuickResponses(account);
        }
        @Override
        public void onIncomingSettings(Account account) {
            AccountSettings.this.onIncomingSettings(account);
        }
        @Override
        public void onOutgoingSettings(Account account) {
            AccountSettings.this.onOutgoingSettings(account);
        }
        @Override
        public void abandonEdit() {
            finish();
        }
        @Override
        public void deleteAccount(Account account) {
            AccountSettings.this.deleteAccount(account);
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
        @Override
        public void onCheckSettingsComplete(int result, int setupMode) {
            if (result == AccountCheckSettingsFragment.CHECK_SETTINGS_OK) {
                // Settings checked & saved; clear current fragment
                mCurrentFragment = null;
                onBackPressed();
            }
        }
    }

    /**
     * Some of the settings have changed. Update internal state as necessary.
     */
    public void onSettingsChanged(Account account, String preference, Object value) {
        if (AccountSettingsFragment.PREFERENCE_DESCRIPTION.equals(preference)) {
            for (Header header : mAccountListHeaders) {
                if (header.id == account.mId) {
                    // Manually tweak the header title. We cannot rebuild the header list from
                    // an account cursor as the account database has not been saved yet.
                    header.title = value.toString();
                    invalidateHeaders();
                    break;
                }
            }
        }
    }

    /**
     * Dispatch to edit quick responses.
     */
    public void onEditQuickResponses(Account account) {
        try {
            Bundle args = new Bundle();
            args.putParcelable(QUICK_RESPONSE_ACCOUNT_KEY, account);
            startPreferencePanel(AccountSettingsEditQuickResponsesFragment.class.getName(), args,
                    R.string.account_settings_edit_quick_responses_label, null, null, 0);
        } catch (Exception e) {
            Log.d(Logging.LOG_TAG, "Error while trying to invoke edit quick responses.", e);
        }
    }

    /**
     * Dispatch to edit incoming settings.
     *
     * TODO: Make things less hardwired
     */
    public void onIncomingSettings(Account account) {
        try {
            Store store = Store.getInstance(account, getApplication());
            if (store != null) {
                Class<? extends android.app.Activity> setting = store.getSettingActivityClass();
                if (setting != null) {
                    SetupData.init(SetupData.FLOW_MODE_EDIT, account);
                    if (setting.equals(AccountSetupIncoming.class)) {
                        startPreferencePanel(AccountSetupIncomingFragment.class.getName(),
                                AccountSetupIncomingFragment.getSettingsModeArgs(),
                                R.string.account_settings_incoming_label, null, null, 0);
                    } else if (setting.equals(AccountSetupExchange.class)) {
                        startPreferencePanel(AccountSetupExchangeFragment.class.getName(),
                                AccountSetupExchangeFragment.getSettingsModeArgs(),
                                R.string.account_settings_incoming_label, null, null, 0);
                    }
                }
            }
        } catch (Exception e) {
            Log.d(Logging.LOG_TAG, "Error while trying to invoke store settings.", e);
        }
    }

    /**
     * Dispatch to edit outgoing settings.
     *
     * TODO: Make things less hardwired
     */
    public void onOutgoingSettings(Account account) {
        try {
            Sender sender = Sender.getInstance(getApplication(), account);
            if (sender != null) {
                Class<? extends android.app.Activity> setting = sender.getSettingActivityClass();
                if (setting != null) {
                    SetupData.init(SetupData.FLOW_MODE_EDIT, account);
                    if (setting.equals(AccountSetupOutgoing.class)) {
                        startPreferencePanel(AccountSetupOutgoingFragment.class.getName(),
                                AccountSetupOutgoingFragment.getSettingsModeArgs(),
                                R.string.account_settings_outgoing_label, null, null, 0);
                    }
                }
            }
        } catch (Exception e) {
            Log.d(Logging.LOG_TAG, "Error while trying to invoke sender settings.", e);
        }
    }

    /**
     * Delete the selected account
     */
    public void deleteAccount(Account account) {
        // Kick off the work to actually delete the account
        // Delete the account (note, this is async.  Would be nice to get a callback.
        Controller.getInstance(this).deleteAccount(account.mId);

        // Then update the UI as appropriate:
        // If single pane, return to the header list.  If multi, rebuild header list
        if (onIsMultiPane()) {
            Header prefsHeader = getAppPreferencesHeader();
            this.switchToHeader(prefsHeader.fragment, prefsHeader.fragmentArguments);
            mDeletingAccountId = account.mId;
            updateAccounts();
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
            return Utility.getFirstRowLong(AccountSettings.this, Account.CONTENT_URI,
                    Account.ID_PROJECTION, SELECTION_ACCOUNT_EMAIL_ADDRESS,
                    new String[] {acct.name}, null, Account.ID_PROJECTION_COLUMN, -1L);
        }

        @Override
        protected void onPostExecute(Long accountId) {
            if (accountId != -1 && !isCancelled()) {
                mRequestedAccountId = accountId;
                invalidateHeaders();
            }
        }
    }

    /**
     * Dialog fragment to show "exit with unsaved changes?" dialog
     */
    /* package */ static class UnsavedChangesDialogFragment extends DialogFragment {
        private final static String TAG = "UnsavedChangesDialogFragment";

        // Argument bundle keys
        private final static String BUNDLE_KEY_HEADER = "UnsavedChangesDialogFragment.Header";
        private final static String BUNDLE_KEY_BACK = "UnsavedChangesDialogFragment.Back";

        /**
         * Creates a save changes dialog when the user selects a new header
         * @param position The new header index to make active if the user accepts the dialog. This
         * must be a valid header index although there is no error checking.
         */
        public static UnsavedChangesDialogFragment newInstanceForHeader(int position) {
            UnsavedChangesDialogFragment f = new UnsavedChangesDialogFragment();
            Bundle b = new Bundle();
            b.putInt(BUNDLE_KEY_HEADER, position);
            f.setArguments(b);
            return f;
        }

        /**
         * Creates a save changes dialog when the user navigates "back".
         * {@link #onBackPressed()} defines in which case this may be triggered.
         */
        public static UnsavedChangesDialogFragment newInstanceForBack() {
            UnsavedChangesDialogFragment f = new UnsavedChangesDialogFragment();
            Bundle b = new Bundle();
            b.putBoolean(BUNDLE_KEY_BACK, true);
            f.setArguments(b);
            return f;
        }

        // Force usage of newInstance()
        private UnsavedChangesDialogFragment() {
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final AccountSettings activity = (AccountSettings) getActivity();
            final int position = getArguments().getInt(BUNDLE_KEY_HEADER);
            final boolean isBack = getArguments().getBoolean(BUNDLE_KEY_BACK);

            return new AlertDialog.Builder(activity)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(android.R.string.dialog_alert_title)
                .setMessage(R.string.account_settings_exit_server_settings)
                .setPositiveButton(
                        R.string.okay_action,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                if (isBack) {
                                    activity.forceBack();
                                } else {
                                    activity.forceSwitchHeader(position);
                                }
                                dismiss();
                            }
                        })
                .setNegativeButton(
                        activity.getString(R.string.cancel_action), null)
                .create();
        }
    }

    /**
     * Dialog briefly shown in some cases, to indicate the user that login failed.  If the user
     * clicks OK, we simply dismiss the dialog, leaving the user in the account settings for
     * that account;  If the user clicks "cancel", we exit account settings.
     */
    public static class LoginWarningDialog extends DialogFragment
            implements DialogInterface.OnClickListener {
        private static final String BUNDLE_KEY_ACCOUNT_NAME = "account_name";

        /**
         * Create a new dialog.
         */
        public static LoginWarningDialog newInstance(String accountName) {
            final LoginWarningDialog dialog = new LoginWarningDialog();
            Bundle b = new Bundle();
            b.putString(BUNDLE_KEY_ACCOUNT_NAME, accountName);
            dialog.setArguments(b);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final String accountName = getArguments().getString(BUNDLE_KEY_ACCOUNT_NAME);

            final Context context = getActivity();
            final Resources res = context.getResources();
            final AlertDialog.Builder b = new AlertDialog.Builder(context);
            b.setTitle(R.string.account_settings_login_dialog_title);
            b.setIconAttribute(android.R.attr.alertDialogIcon);
            b.setMessage(res.getString(R.string.account_settings_login_dialog_content_fmt,
                    accountName));
            b.setPositiveButton(R.string.okay_action, this);
            b.setNegativeButton(R.string.cancel_action, this);
            return b.create();
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            dismiss();
            if (which == DialogInterface.BUTTON_NEGATIVE) {
                getActivity().finish();
            }
        }
    }
}
