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
import com.android.email.R;
import com.android.email.activity.Welcome;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.AccountColumns;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.database.Cursor;
import android.os.Bundle;
import android.util.Log;

/**
 * TODO: At this point this is used only to support the Account Manager, and needs to be removed.
 */
public class AccountSettings extends Activity
        implements AccountSettingsFragment.Callback, AccountSettingsFragment.OnAttachListener {
    // NOTE: This string must match the one in res/xml/account_preferences.xml
    private static final String ACTION_ACCOUNT_MANAGER_ENTRY =
        "com.android.email.activity.setup.ACCOUNT_MANAGER_ENTRY";
    // NOTE: This constant should eventually be defined in android.accounts.Constants, but for
    // now we define it here
    private static final String ACCOUNT_MANAGER_EXTRA_ACCOUNT = "account";
    private static final String EXTRA_ACCOUNT_ID = "account_id";

    // UI values
    /* package */ AccountSettingsFragment mFragment;

    // Account data values
    private long mAccountId = -1;

    /**
     * Display (and edit) settings for a specific account
     */
    public static void actionSettings(Activity fromActivity, long accountId) {
        Intent i = new Intent(fromActivity, AccountSettings.class);
        i.putExtra(EXTRA_ACCOUNT_ID, accountId);
        fromActivity.startActivity(i);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent i = getIntent();
        if (ACTION_ACCOUNT_MANAGER_ENTRY.equals(i.getAction())) {
            // This case occurs if we're changing account settings from Settings -> Accounts
            setAccountIdFromAccountManagerIntent();
        } else {
            // Otherwise, we're called from within the Email app and look for our extra
            mAccountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        }

        // If there's no accountId, we're done
        if (mAccountId == -1) {
            finish();
            return;
        }

        // Now set up the UI and the fragment
        setContentView(R.layout.account_settings);
        mFragment = (AccountSettingsFragment)
                getFragmentManager().findFragmentById(R.id.settings_fragment);
        mFragment.setCallback(this);
        mFragment.startLoadingAccount(mAccountId);
    }

    private void setAccountIdFromAccountManagerIntent() {
        // First, get the AccountManager account that we've been ask to handle
        android.accounts.Account acct =
            (android.accounts.Account)getIntent()
            .getParcelableExtra(ACCOUNT_MANAGER_EXTRA_ACCOUNT);
        // Find a HostAuth using eas and whose login is the name of the AccountManager account
        Cursor c = getContentResolver().query(Account.CONTENT_URI,
                new String[] {AccountColumns.ID}, AccountColumns.EMAIL_ADDRESS + "=?",
                new String[] {acct.name}, null);
        try {
            if (c.moveToFirst()) {
                mAccountId = c.getLong(0);
            }
        } finally {
            c.close();
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        // Exit immediately if the accounts list has changed (e.g. externally deleted)
        if (Email.getNotifyUiAccountsChanged()) {
            Welcome.actionStart(this);
            finish();
            return;
        }
    }

    /**
     * Implements AccountSettingsFragment.Callback
     */
    @Override
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
     * Implements AccountSettingsFragment.Callback
     */
    @Override
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
     * Implements AccountSettingsFragment.Callback
     */
    @Override
    public void abandonEdit() {
        finish();
    }

    /**
     * Implements AccountSettingsFragment.Callback
     */
    @Override
    public void deleteAccount(Account account) {
        // STOPSHIP - this is not implemented because this entire activity is deprecated
        // If you need to delete an account, use the AccountSettingsXL, which will eventually
        // become the only settings activity.
    }

    /**
     * Implements AccountSettingsFragment.OnAttachListener
     * Does nothing (this activity sets up the fragment in onCreate via its layout)
     */
    @Override
    public void onAttach(Fragment f) {
    }
}
