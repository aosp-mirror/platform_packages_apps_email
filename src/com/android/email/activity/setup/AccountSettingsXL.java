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

import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import java.util.List;

/**
 * Handles account preferences using multi-pane arrangement when possible.
 *
 * TODO: Go directly to specific account when requested - post runnable after onBuildHeaders
 * TODO: Incorporate entry point & other stuff to support launch from AccountManager
 * TODO: In Account settings in Phone UI, change title
 * TODO: Rework all remaining calls to DB from UI thread
 * TODO: Handle dynamic changes to the account list (exit if necessary)
 * TODO: Clean up show/hide icon for add account
 * TODO: Finish delete account
 */
public class AccountSettingsXL extends PreferenceActivity
        implements AccountSettingsFragment.OnAttachListener {

    private static final String EXTRA_ACCOUNT_ID = "AccountSettingsXL.account_id";

    private long mRequestedAccountId;
    private Header[] mAccountListHeaders;
    private Header mAppPreferencesHeader;
    private int mCurrentHeaderPosition;
    private Fragment mCurrentFragment;
    private long mDeletingAccountId = -1;

    // Async Tasks
    private LoadAccountListTask mLoadAccountListTask;

    // Specific callbacks used by settings fragments
    private AccountSettingsFragmentCallback mAccountSettingsFragmentCallback
            = new AccountSettingsFragmentCallback();

    /**
     * Display (and edit) settings for a specific account, or -1 for any/all accounts
     */
    public static void actionSettings(Activity fromActivity, long accountId) {
        Intent i = new Intent(fromActivity, AccountSettingsXL.class);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        fromActivity.startActivity(i);
    }

    /**
     * Header for general app preferences
     */

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent i = getIntent();
        mRequestedAccountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateAccounts();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Utility.cancelTaskInterrupt(mLoadAccountListTask);
        mLoadAccountListTask = null;
    }

    /**
     * Only show "add account" when header showing and not in a sub-fragment (like incoming)
     *
     * TODO: it might make more sense to do this by having fragments add the items, except for
     * this problem:  How do we add items that are shown in single pane headers-only mode?
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        if (shouldShowNewAccount()) {
            getMenuInflater().inflate(R.menu.account_settings_option, menu);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.add_new_account:
                onAddNewAccount();
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    /**
     * Decide if "add account" should be shown
     *
     * TODO: it might make more sense to do this by having fragments add the items, except for
     * this problem:  How do we add items that are shown in single pane headers-only mode?
     */
    private boolean shouldShowNewAccount() {
        // If in single pane mode, only add accounts at top level
        if (!isMultiPane()) {
            if (!hasHeaders()) return false;
        } else {
            // If in multi pane mode, only add accounts when showing a top level fragment
            // Note: null is OK; This is the case when we first launch the activity
            if ((mCurrentFragment != null)
                && !(mCurrentFragment instanceof AccountSettingsFragment)
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
     */
    @Override
    public void onBuildHeaders(List<Header> target) {
        // Set up fixed header for general settings
        if (mAppPreferencesHeader == null) {
            mAppPreferencesHeader = new Header();
            mAppPreferencesHeader.title = getText(R.string.header_label_general_preferences);
            mAppPreferencesHeader.summary = null;
            mAppPreferencesHeader.iconRes = 0;
            mAppPreferencesHeader.icon = null;
            mAppPreferencesHeader.fragment = GeneralPreferences.getCanonicalName();
            mAppPreferencesHeader.fragmentArguments = null;
        }
        target.clear();
        target.add(mAppPreferencesHeader);
        if (mAccountListHeaders != null) {
            int headerCount = mAccountListHeaders.length;
            for (int index = 0; index < headerCount; index++) {
                target.add(mAccountListHeaders[index]);
            }
        }
    }

    /**
     * This AsyncTask reads the accounts list and generates the headers.  When the headers are
     * ready, we'll trigger PreferenceActivity to refresh the account list with them.
     *
     * TODO: Smaller projection
     * TODO: Convert to Loader
     * TODO: Write a test, including operation of deletingAccountId param
     */
    private class LoadAccountListTask extends AsyncTask<Long, Void, Header[]> {

        @Override
        protected Header[] doInBackground(Long... params) {
            Header[] result = null;
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
                        continue;
                    }
                    String title = c.getString(Account.CONTENT_DISPLAY_NAME_COLUMN);
                    String summary = c.getString(Account.CONTENT_EMAIL_ADDRESS_COLUMN);
                    Header newHeader = new Header();
                    newHeader.title = title;
                    newHeader.summary = summary;
                    newHeader.fragment = AccountSettingsFragment.class.getCanonicalName();
                    newHeader.fragmentArguments =
                        AccountSettingsFragment.buildArguments(accountId);
                    result[index++] = newHeader;
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
            return result;
        }

        @Override
        protected void onPostExecute(Header[] headers) {
            if (this.isCancelled()) return;
            // report the settings
            mAccountListHeaders = headers;
            AccountSettingsXL.this.invalidateHeaders();
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
        if (position != mCurrentHeaderPosition) {
            // if showing a sub-panel (e.g. server settings) we need to trap & post a dialog
        }
        super.onHeaderClick(header, position);
    }

    /**
     * Implements AccountSettingsFragment.OnAttachListener
     */
    @Override
    public void onAttach(Fragment f) {
        mCurrentFragment = f;
        // dispatch per-fragment setup
        if (f instanceof AccountSettingsFragment) {
            AccountSettingsFragment asf = (AccountSettingsFragment) f;
            asf.setCallback(mAccountSettingsFragmentCallback);
        } else if (f instanceof AccountSetupIncomingFragment) {
            // TODO
        } else if (f instanceof AccountSetupOutgoingFragment) {
            // TODO
        } else if (f instanceof AccountSetupExchangeFragment) {
            // TODO
        }
        // Since we're changing fragments, reset the options menu (not all choices are shown)
        invalidateOptionsMenu();
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
     * STOPSHIP: non-fragmented dispatch to edit incoming settings.  Replace with fragment flip.
     */
    public void onIncomingSettings(Account account) {
        try {
            Store store = Store.getInstance(account.getStoreUri(this), getApplication(), null);
            if (store != null) {
                Class<? extends android.app.Activity> setting = store.getSettingActivityClass();
                if (setting != null) {
                    java.lang.reflect.Method m = setting.getMethod("actionEditIncomingSettings",
                            Activity.class, int.class, Account.class);
                    m.invoke(null, this, SetupData.FLOW_MODE_EDIT, account);
                }
            }
        } catch (Exception e) {
            Log.d(Email.LOG_TAG, "Error while trying to invoke store settings.", e);
        }
    }

    /**
     * STOPSHIP: non-fragmented dispatch to edit outgoing settings.  Replace with fragment flip.
     */
    public void onOutgoingSettings(Account account) {
        try {
            Sender sender = Sender.getInstance(getApplication(), account.getSenderUri(this));
            if (sender != null) {
                Class<? extends android.app.Activity> setting = sender.getSettingActivityClass();
                if (setting != null) {
                    java.lang.reflect.Method m = setting.getMethod("actionEditOutgoingSettings",
                            Activity.class, int.class, Account.class);
                    m.invoke(null, this, SetupData.FLOW_MODE_EDIT, account);
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
        Utility.showToast(this, "Pretend to delete " + account.getDisplayName());
        // Kick off the work to delete the account
            // TODO copy this logic from AccountFolderList
        // If single pane, return to the header list.  If multi, rebuild header list
        if (isMultiPane()) {
            mDeletingAccountId = account.mId;
            invalidateHeaders();
        } else {
            // We should only be calling this while showing AccountSettingsFragment,
            // so a finish() should bring us back to headers.  No point hiding the deleted account.
            finish();
        }
    }

    /**
     * Placeholder for app-wide preferences
     * STOPSHIP - make this real
     */
    public static class GeneralPreferences extends PreferenceFragment {

        /** STOPSHIP - this is hardcoded for now because getCanonicalName() doesn't return $ */
        public static String getCanonicalName() {
            return "com.android.email.activity.setup.AccountSettingsXL$GeneralPreferences";
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            // Load the preferences from an XML resource
            addPreferencesFromResource(R.xml.general_preferences);
        }
    }

}
