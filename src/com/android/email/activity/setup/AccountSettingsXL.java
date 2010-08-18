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

import java.util.List;

/**
 * Handles account preferences using multi-pane arrangement when possible.
 *
 * TODO: Incorporate entry point & other stuff to support launch from AccountManager
 * TODO: In Account settings in Phone UI, change title
 * TODO: Action bar?  Need to work out the handling of next/back type buttons
 */
public class AccountSettingsXL extends PreferenceActivity
        implements AccountSettingsFragment.OnAttachListener {

    private static final String EXTRA_ACCOUNT_ID = "AccountSettingsXL.account_id";

    private long mAccountId;
    private Header[] mAccountListHeaders;
    private Header mAppPreferencesHeader;
    private int mCurrentHeaderPosition;
    private Fragment mCurrentFragment;

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
        mAccountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
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
     * Start the async reload of the accounts list (if the headers are being displayed)
     */
    private void updateAccounts() {
        if (hasHeaders()) {
            Utility.cancelTaskInterrupt(mLoadAccountListTask);
            mLoadAccountListTask = (LoadAccountListTask) new LoadAccountListTask().execute();
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
     */
    private class LoadAccountListTask extends AsyncTask<Void, Void, Header[]> {

        @Override
        protected Header[] doInBackground(Void... params) {
            Header[] result = null;

            Cursor c = getContentResolver().query(
                    EmailContent.Account.CONTENT_URI,
                    EmailContent.Account.CONTENT_PROJECTION, null, null, null);
            try {
                int index = 0;
                int headerCount = c.getCount();
                result = new Header[headerCount];

                while (c.moveToNext()) {
                    String title = c.getString(Account.CONTENT_DISPLAY_NAME_COLUMN);
                    String summary = c.getString(Account.CONTENT_EMAIL_ADDRESS_COLUMN);
                    long accountId = c.getLong(Account.CONTENT_ID_COLUMN);
                    Header newHeader = new Header();
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
            return result;
        }

        @Override
        protected void onPostExecute(Header[] headers) {
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
