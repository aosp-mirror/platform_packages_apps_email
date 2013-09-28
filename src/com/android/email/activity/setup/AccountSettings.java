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
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.util.Linkify;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import com.android.email.R;
import com.android.email.activity.ActivityHelper;
import com.android.email.provider.EmailProvider;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.service.ServiceProxy;
import com.android.emailcommon.utility.IntentUtilities;
import com.android.emailcommon.utility.Utility;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider.EditSettingsExtras;
import com.android.mail.ui.FeedbackEnabledActivity;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;

import java.util.List;

/**
 * Handles account preferences, using multi-pane arrangement when possible.
 *
 * This activity uses the following fragments:
 *   AccountSettingsFragment
 *   Account{Incoming/Outgoing}Fragment
 *   AccountCheckSettingsFragment
 *   GeneralPreferences
 *   DebugFragment
 *
 * TODO: Delete account - on single-pane view (phone UX) the account list doesn't update properly
 * TODO: Handle dynamic changes to the account list (exit if necessary).  It probably makes
 *       sense to use a loader for the accounts list, because it would provide better support for
 *       dealing with accounts being added/deleted and triggering the header reload.
 */
public class AccountSettings extends PreferenceActivity implements FeedbackEnabledActivity,
        SetupData.SetupDataContainer {
    /*
     * Intent to open account settings for account=1
        adb shell am start -a android.intent.action.EDIT \
            -d '"content://ui.email.android.com/settings?ACCOUNT_ID=1"'
     */

    // Intent extras for our internal activity launch
    private static final String EXTRA_ENABLE_DEBUG = "AccountSettings.enable_debug";
    private static final String EXTRA_LOGIN_WARNING_FOR_ACCOUNT = "AccountSettings.for_account";
    private static final String EXTRA_LOGIN_WARNING_REASON_FOR_ACCOUNT =
            "AccountSettings.for_account_reason";
    private static final String EXTRA_TITLE = "AccountSettings.title";
    public static final String EXTRA_NO_ACCOUNTS = "AccountSettings.no_account";

    // Intent extras for launch directly from system account manager
    // NOTE: This string must match the one in res/xml/account_preferences.xml
    private static String ACTION_ACCOUNT_MANAGER_ENTRY;
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
    private Header[] mAccountListHeaders;
    private Header mAppPreferencesHeader;
    /* package */ Fragment mCurrentFragment;
    private long mDeletingAccountId = -1;
    private boolean mShowDebugMenu;
    private List<Header> mGeneratedHeaders;
    private Uri mFeedbackUri;
    private MenuItem mFeedbackMenuItem;

    private SetupData mSetupData;

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
        fromActivity.startActivity(createAccountSettingsIntent(accountId, null, null));
    }

    /**
     * Create and return an intent to display (and edit) settings for a specific account, or -1
     * for any/all accounts.  If an account name string is provided, a warning dialog will be
     * displayed as well.
     */
    public static Intent createAccountSettingsIntent(long accountId,
            String loginWarningAccountName, String loginWarningReason) {
        final Uri.Builder b = IntentUtilities.createActivityIntentUrlBuilder(
                IntentUtilities.PATH_SETTINGS);
        IntentUtilities.setAccountId(b, accountId);
        final Intent i = new Intent(Intent.ACTION_EDIT, b.build());
        if (loginWarningAccountName != null) {
            i.putExtra(EXTRA_LOGIN_WARNING_FOR_ACCOUNT, loginWarningAccountName);
        }
        if (loginWarningReason != null) {
            i.putExtra(EXTRA_LOGIN_WARNING_REASON_FOR_ACCOUNT, loginWarningReason);
        }
        return i;
    }

    @Override
    public Intent getIntent() {
        final Intent intent = super.getIntent();
        final long accountId = IntentUtilities.getAccountIdFromIntent(intent);
        if (accountId < 0) {
            return intent;
        }
        Intent modIntent = new Intent(intent);
        modIntent.putExtra(EXTRA_SHOW_FRAGMENT, AccountSettingsFragment.class.getCanonicalName());
        modIntent.putExtra(
                EXTRA_SHOW_FRAGMENT_ARGUMENTS,
                AccountSettingsFragment.buildArguments(
                        accountId, IntentUtilities.getAccountNameFromIntent(intent)));
        modIntent.putExtra(EXTRA_NO_HEADERS, true);
        return modIntent;
    }


    /**
     * Launch generic settings and pre-enable the debug preferences
     */
    public static void actionSettingsWithDebug(Context fromContext) {
        final Intent i = new Intent(fromContext, AccountSettings.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra(EXTRA_ENABLE_DEBUG, true);
        fromContext.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityHelper.debugSetWindowFlags(this);

        final Intent i = getIntent();
        if (savedInstanceState == null) {
            // If we are not restarting from a previous instance, we need to
            // figure out the initial prefs to show.  (Otherwise, we want to
            // continue showing whatever the user last selected.)
            if (ACTION_ACCOUNT_MANAGER_ENTRY == null) {
                ACTION_ACCOUNT_MANAGER_ENTRY =
                        ServiceProxy.getIntentStringForEmailPackage(this,
                                getString(R.string.intent_account_manager_entry));
            }
            if (ACTION_ACCOUNT_MANAGER_ENTRY.equals(i.getAction())) {
                // This case occurs if we're changing account settings from Settings -> Accounts
                mGetAccountIdFromAccountTask =
                        (GetAccountIdFromAccountTask) new GetAccountIdFromAccountTask()
                        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, i);
            } else if (i.hasExtra(EditSettingsExtras.EXTRA_FOLDER)) {
                launchMailboxSettings(i);
                return;
            } else if (i.hasExtra(EXTRA_NO_ACCOUNTS)) {
                AccountSetupBasics.actionNewAccountWithResult(this);
                finish();
                return;
            } else {
                // Otherwise, we're called from within the Email app and look for our extras
                mRequestedAccountId = IntentUtilities.getAccountIdFromIntent(i);
                String loginWarningAccount = i.getStringExtra(EXTRA_LOGIN_WARNING_FOR_ACCOUNT);
                String loginWarningReason =
                        i.getStringExtra(EXTRA_LOGIN_WARNING_REASON_FOR_ACCOUNT);
                if (loginWarningAccount != null) {
                    // Show dialog (first time only - don't re-show on a rotation)
                    LoginWarningDialog dialog =
                            LoginWarningDialog.newInstance(loginWarningAccount, loginWarningReason);
                    dialog.show(getFragmentManager(), "loginwarning");
                }
            }
        } else {
            mSetupData = savedInstanceState.getParcelable(SetupData.EXTRA_SETUP_DATA);
        }
        mShowDebugMenu = i.getBooleanExtra(EXTRA_ENABLE_DEBUG, false);

        final String title = i.getStringExtra(EXTRA_TITLE);
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

        mFeedbackUri = Utils.getValidUri(getString(R.string.email_feedback_uri));
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(
                outState);
        outState.putParcelable(SetupData.EXTRA_SETUP_DATA, mSetupData);
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
        getMenuInflater().inflate(R.menu.settings_menu, menu);

        mFeedbackMenuItem = menu.findItem(R.id.feedback_menu_item);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        if (mFeedbackMenuItem != null) {
            // We only want to enable the feedback menu item, if there is a valid feedback uri
            mFeedbackMenuItem.setVisible(!Uri.EMPTY.equals(mFeedbackUri));
        }
        return true;
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
            case R.id.feedback_menu_item:
                Utils.sendFeedback(this, mFeedbackUri, false /* reportingProblem */);
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public Intent onBuildStartFragmentIntent(String fragmentName, Bundle args,
            int titleRes, int shortTitleRes) {
        final Intent intent = super.onBuildStartFragmentIntent(
                fragmentName, args, titleRes, shortTitleRes);

        // When opening a sub-settings page (e.g. account specific page), see if we want to modify
        // the activity title.
        String title = AccountSettingsFragment.getTitleFromArgs(args);
        if ((titleRes == 0) && (title != null)) {
            intent.putExtra(EXTRA_TITLE, title);
        }
        return intent;
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
            if (((AccountServerBaseFragment) mCurrentFragment).haveSettingsChanged()) {
                UnsavedChangesDialogFragment dialogFragment =
                        UnsavedChangesDialogFragment.newInstanceForBack();
                dialogFragment.show(getFragmentManager(), UnsavedChangesDialogFragment.TAG);
                return; // Prevent "back" from being handled
            }
        }
        super.onBackPressed();
    }

    private void launchMailboxSettings(Intent intent) {
        final Folder folder = intent.getParcelableExtra(EditSettingsExtras.EXTRA_FOLDER);

        // TODO: determine from the account if we should navigate to the mailbox settings.
        // See bug 6242668

        // Get the mailbox id from the folder
        final long mailboxId =
                Long.parseLong(folder.folderUri.fullUri.getPathSegments().get(1));

        MailboxSettings.start(this, mailboxId);
        finish();
    }


    private void enableDebugMenu() {
        mShowDebugMenu = true;
        invalidateHeaders();
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
        // Always add app preferences as first header
        target.clear();
        target.add(getAppPreferencesHeader());

        // Then add zero or more account headers as necessary
        if (mAccountListHeaders != null) {
            final int headerCount = mAccountListHeaders.length;
            for (int index = 0; index < headerCount; index++) {
                Header header = mAccountListHeaders[index];
                if (header != null && header.id != HEADER_ID_UNDEFINED) {
                    if (header.id != mDeletingAccountId) {
                        target.add(header);
                        if (header.id == mRequestedAccountId) {
                            mRequestedAccountId = -1;
                        }
                    }
                }
            }
        }

        // finally, if debug header is enabled, show it
        if (mShowDebugMenu) {
            // setup lightweight header for debugging
            final Header debugHeader = new Header();
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
            final long deletingAccountId = params[0];

            Cursor c = getContentResolver().query(
                    Account.CONTENT_URI,
                    Account.CONTENT_PROJECTION, null, null, null);
            try {
                int index = 0;
                result = new Header[c.getCount()];

                while (c.moveToNext()) {
                    final long accountId = c.getLong(Account.CONTENT_ID_COLUMN);
                    if (accountId == deletingAccountId) {
                        deletingAccountFound = true;
                        continue;
                    }
                    final String name = c.getString(Account.CONTENT_DISPLAY_NAME_COLUMN);
                    final String email = c.getString(Account.CONTENT_EMAIL_ADDRESS_COLUMN);
                    final Header newHeader = new Header();
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
            final Header[] headers = (Header[]) result[0];
            final boolean deletingAccountFound = (Boolean) result[1];
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
        if ((mCurrentFragment instanceof AccountServerBaseFragment)
                && (((AccountServerBaseFragment)mCurrentFragment).haveSettingsChanged())) {
            UnsavedChangesDialogFragment dialogFragment =
                UnsavedChangesDialogFragment.newInstanceForHeader(position);
            dialogFragment.show(getFragmentManager(), UnsavedChangesDialogFragment.TAG);
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
        switchToHeader(mGeneratedHeaders.get(position));
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
            final AccountSettingsFragment asf = (AccountSettingsFragment) f;
            asf.setCallback(mAccountSettingsFragmentCallback);
        } else if (f instanceof AccountServerBaseFragment) {
            final AccountServerBaseFragment asbf = (AccountServerBaseFragment) f;
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
        public void onEditQuickResponses(com.android.mail.providers.Account account) {
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
        public void onCheckSettingsComplete(int result, SetupData setupData) {
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
    public void onEditQuickResponses(com.android.mail.providers.Account account) {
        try {
            final Bundle args = new Bundle(1);
            args.putParcelable(QUICK_RESPONSE_ACCOUNT_KEY, account);
            startPreferencePanel(AccountSettingsEditQuickResponsesFragment.class.getName(), args,
                    R.string.account_settings_edit_quick_responses_label, null, null, 0);
        } catch (Exception e) {
            LogUtils.d(Logging.LOG_TAG, "Error while trying to invoke edit quick responses.", e);
        }
    }

    /**
     * Dispatch to edit incoming settings.
     */
    public void onIncomingSettings(Account account) {
        try {
            mSetupData = new SetupData(SetupData.FLOW_MODE_EDIT, account);
            final Fragment f = new AccountSetupIncomingFragment();
            f.setArguments(AccountSetupIncomingFragment.getArgs(true));
            // Use startPreferenceFragment here because we need to keep this activity instance
            startPreferenceFragment(f, true);
        } catch (Exception e) {
            LogUtils.d(Logging.LOG_TAG, "Error while trying to invoke store settings.", e);
        }
    }

    /**
     * Dispatch to edit outgoing settings.
     *
     * TODO: Make things less hardwired
     */
    public void onOutgoingSettings(Account account) {
        try {
            mSetupData = new SetupData(SetupData.FLOW_MODE_EDIT, account);
            final Fragment f = new AccountSetupOutgoingFragment();
            f.setArguments(AccountSetupOutgoingFragment.getArgs(true));
            // Use startPreferenceFragment here because we need to keep this activity instance
            startPreferenceFragment(f, true);
        } catch (Exception e) {
            LogUtils.d(Logging.LOG_TAG, "Error while trying to invoke sender settings.", e);
        }
    }

    /**
     * Delete the selected account
     */
    public void deleteAccount(final Account account) {
        // Kick off the work to actually delete the account
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Uri uri = EmailProvider.uiUri("uiaccount", account.mId);
                getContentResolver().delete(uri, null, null);
            }}).start();

        // TODO: Remove ui glue for unified
        // Then update the UI as appropriate:
        // If single pane, return to the header list.  If multi, rebuild header list
        if (onIsMultiPane()) {
            final Header prefsHeader = getAppPreferencesHeader();
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
            final Intent intent = params[0];
            android.accounts.Account acct =
                intent.getParcelableExtra(EXTRA_ACCOUNT_MANAGER_ACCOUNT);
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
    public static class UnsavedChangesDialogFragment extends DialogFragment {
        final static String TAG = "UnsavedChangesDialogFragment";

        // Argument bundle keys
        private final static String BUNDLE_KEY_HEADER = "UnsavedChangesDialogFragment.Header";
        private final static String BUNDLE_KEY_BACK = "UnsavedChangesDialogFragment.Back";

        /**
         * Creates a save changes dialog when the user selects a new header
         * @param position The new header index to make active if the user accepts the dialog. This
         * must be a valid header index although there is no error checking.
         */
        public static UnsavedChangesDialogFragment newInstanceForHeader(int position) {
            final UnsavedChangesDialogFragment f = new UnsavedChangesDialogFragment();
            final Bundle b = new Bundle(1);
            b.putInt(BUNDLE_KEY_HEADER, position);
            f.setArguments(b);
            return f;
        }

        /**
         * Creates a save changes dialog when the user navigates "back".
         * {@link #onBackPressed()} defines in which case this may be triggered.
         */
        public static UnsavedChangesDialogFragment newInstanceForBack() {
            final UnsavedChangesDialogFragment f = new UnsavedChangesDialogFragment();
            final Bundle b = new Bundle(1);
            b.putBoolean(BUNDLE_KEY_BACK, true);
            f.setArguments(b);
            return f;
        }

        // Force usage of newInstance()
        public UnsavedChangesDialogFragment() {}

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
                            @Override
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
        private String mReason;

        // Public no-args constructor needed for fragment re-instantiation
        public LoginWarningDialog() {}

        /**
         * Create a new dialog.
         */
        public static LoginWarningDialog newInstance(String accountName, String reason) {
            final LoginWarningDialog dialog = new LoginWarningDialog();
            final Bundle b = new Bundle(1);
            b.putString(BUNDLE_KEY_ACCOUNT_NAME, accountName);
            dialog.setArguments(b);
            dialog.mReason = reason;
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
            if (mReason != null) {
                final TextView message = new TextView(context);
                final String alert = res.getString(
                        R.string.account_settings_login_dialog_reason_fmt, accountName, mReason);
                SpannableString spannableAlertString = new SpannableString(alert);
                Linkify.addLinks(spannableAlertString, Linkify.WEB_URLS);
                message.setText(spannableAlertString);
                // There must be a better way than specifying size/padding this way
                // It does work and look right, though
                final int textSize = res.getDimensionPixelSize(R.dimen.dialog_text_size);
                message.setTextSize(textSize);
                final int paddingLeft = res.getDimensionPixelSize(R.dimen.dialog_padding_left);
                final int paddingOther = res.getDimensionPixelSize(R.dimen.dialog_padding_other);
                message.setPadding(paddingLeft, paddingOther, paddingOther, paddingOther);
                message.setMovementMethod(LinkMovementMethod.getInstance());
                b.setView(message);
            } else {
                b.setMessage(res.getString(R.string.account_settings_login_dialog_content_fmt,
                        accountName));
            }
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

    @Override
    public Context getActivityContext() {
        return this;
    }

    @Override
    public SetupData getSetupData() {
        return mSetupData;
    }

    @Override
    public void setSetupData(SetupData setupData) {
        mSetupData = setupData;
    }
}
