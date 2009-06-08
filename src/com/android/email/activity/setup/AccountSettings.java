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
import com.android.email.mail.MessagingException;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.email.provider.EmailStore;
import com.android.email.provider.EmailStore.HostAuth;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.RingtonePreference;
import android.util.Log;
import android.view.KeyEvent;

public class AccountSettings extends PreferenceActivity {
    private static final String EXTRA_ACCOUNT_ID = "account_id";

    private static final String PREFERENCE_TOP_CATEGORY = "account_settings";
    private static final String PREFERENCE_DESCRIPTION = "account_description";
    private static final String PREFERENCE_NAME = "account_name";
    private static final String PREFERENCE_FREQUENCY = "account_check_frequency";
    private static final String PREFERENCE_DEFAULT = "account_default";
    private static final String PREFERENCE_NOTIFY = "account_notify";
    private static final String PREFERENCE_VIBRATE = "account_vibrate";
    private static final String PREFERENCE_RINGTONE = "account_ringtone";
    private static final String PREFERENCE_SERVER_CATERGORY = "account_servers";
    private static final String PREFERENCE_INCOMING = "incoming";
    private static final String PREFERENCE_OUTGOING = "outgoing";
    private static final String PREFERENCE_ADD_ACCOUNT = "add_account";

    private long mAccountId;
    private EmailStore.Account mAccount;
    private boolean mAccountDirty;

    private EditTextPreference mAccountDescription;
    private EditTextPreference mAccountName;
    private ListPreference mCheckFrequency;
    private ListPreference mSyncWindow;
    private CheckBoxPreference mAccountDefault;
    private CheckBoxPreference mAccountNotify;
    private CheckBoxPreference mAccountVibrate;
    private RingtonePreference mAccountRingtone;

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
        mAccountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, -1);
        mAccount = EmailStore.Account.restoreAccountWithId(this, mAccountId);
        mAccountDirty = false;

        addPreferencesFromResource(R.xml.account_settings_preferences);

        PreferenceCategory topCategory = (PreferenceCategory) findPreference(PREFERENCE_TOP_CATEGORY);
        topCategory.setTitle(getString(R.string.account_settings_title_fmt));

        mAccountDescription = (EditTextPreference) findPreference(PREFERENCE_DESCRIPTION);
        mAccountDescription.setSummary(mAccount.getDescription());
        mAccountDescription.setText(mAccount.getDescription());
        mAccountDescription.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final String summary = newValue.toString();
                mAccountDescription.setSummary(summary);
                mAccountDescription.setText(summary);
                return false;
            }
        });

        mAccountName = (EditTextPreference) findPreference(PREFERENCE_NAME);
        mAccountName.setSummary(mAccount.getName());
        mAccountName.setText(mAccount.getName());
        mAccountName.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final String summary = newValue.toString();
                mAccountName.setSummary(summary);
                mAccountName.setText(summary);
                return false;
            }
        });
        
        mCheckFrequency = (ListPreference) findPreference(PREFERENCE_FREQUENCY);
        
        // Before setting value, we may need to adjust the lists
        Store.StoreInfo info = Store.StoreInfo.getStoreInfo(mAccount.getStoreUri(this), this);
        if (info.mPushSupported) {
            mCheckFrequency.setEntries(R.array.account_settings_check_frequency_entries_push);
            mCheckFrequency.setEntryValues(R.array.account_settings_check_frequency_values_push);
        }

        mCheckFrequency.setValue(String.valueOf(mAccount.getAutomaticCheckIntervalMinutes()));
        mCheckFrequency.setSummary(mCheckFrequency.getEntry());
        mCheckFrequency.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final String summary = newValue.toString();
                int index = mCheckFrequency.findIndexOfValue(summary);
                mCheckFrequency.setSummary(mCheckFrequency.getEntries()[index]);
                mCheckFrequency.setValue(summary);
                return false;
            }
        });
        
        // Add check window preference
        mSyncWindow = null;
        if (info.mVisibleLimitDefault == -1) {
            mSyncWindow = new ListPreference(this);
            mSyncWindow.setTitle(R.string.account_setup_options_mail_window_label);
            mSyncWindow.setEntries(R.array.account_settings_mail_window_entries);
            mSyncWindow.setEntryValues(R.array.account_settings_mail_window_values);
            mSyncWindow.setValue(String.valueOf(mAccount.getSyncWindow()));
            mSyncWindow.setSummary(mSyncWindow.getEntry());
            mSyncWindow.setOrder(4);
            mSyncWindow.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    final String summary = newValue.toString();
                    int index = mSyncWindow.findIndexOfValue(summary);
                    mSyncWindow.setSummary(mSyncWindow.getEntries()[index]);
                    mSyncWindow.setValue(summary);
                    return false;
                }
            });
            topCategory.addPreference(mSyncWindow);
        }

        mAccountDefault = (CheckBoxPreference) findPreference(PREFERENCE_DEFAULT);
        mAccountDefault.setChecked(mAccount.mIsDefault);

        mAccountNotify = (CheckBoxPreference) findPreference(PREFERENCE_NOTIFY);
        mAccountNotify.setChecked(0 != 
            (mAccount.getFlags() & EmailStore.Account.FLAGS_NOTIFY_NEW_MAIL));

        mAccountRingtone = (RingtonePreference) findPreference(PREFERENCE_RINGTONE);

        // XXX: The following two lines act as a workaround for the RingtonePreference
        //      which does not let us set/get the value programmatically
        SharedPreferences prefs = mAccountRingtone.getPreferenceManager().getSharedPreferences();
        prefs.edit().putString(PREFERENCE_RINGTONE, mAccount.getRingtone()).commit();

        mAccountVibrate = (CheckBoxPreference) findPreference(PREFERENCE_VIBRATE);
        mAccountVibrate.setChecked(0 != 
            (mAccount.getFlags() & EmailStore.Account.FLAGS_VIBRATE));

        findPreference(PREFERENCE_INCOMING).setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        onIncomingSettings();
                        return true;
                    }
                });

        // Hide the outgoing account setup link if it's not activated
        Preference prefOutgoing = findPreference(PREFERENCE_OUTGOING);
        boolean showOutgoing = true;
        try {
            Sender sender = Sender.getInstance(mAccount.getSenderUri(this), getApplication());
            if (sender != null) {
                Class<? extends android.app.Activity> setting = sender.getSettingActivityClass();
                showOutgoing = (setting != null);
            }
        } catch (MessagingException me) {
            // just leave showOutgoing as true - bias towards showing it, so user can fix it
        }
        if (showOutgoing) {
            prefOutgoing.setOnPreferenceClickListener(
                    new Preference.OnPreferenceClickListener() {
                        public boolean onPreferenceClick(Preference preference) {
                            onOutgoingSettings();
                            return true;
                        }
                    });
        } else {
            PreferenceCategory serverCategory = (PreferenceCategory) findPreference(
                    PREFERENCE_SERVER_CATERGORY);
            serverCategory.removePreference(prefOutgoing);
        }
        
        findPreference(PREFERENCE_ADD_ACCOUNT).setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        onAddNewAccount();
                        return true;
                    }
                });
    }
    
    @Override
    public void onResume() {
        super.onResume();
        if (mAccountDirty) {
            // if we are coming back from editing incoming or outgoing settings, 
            // we need to refresh them here so we don't accidentally overwrite the
            // old values we're still holding here
            mAccount.mHostAuthRecv = HostAuth.restoreHostAuthWithId(
                    this, mAccount.mHostAuthKeyRecv);
            mAccount.mHostAuthSend = HostAuth.restoreHostAuthWithId(
                    this, mAccount.mHostAuthKeySend);
            
            // TODO write me.
            mAccountDirty = false;
        }
    }

    private void saveSettings() {
        int newFlags = mAccount.getFlags() & 
                ~(EmailStore.Account.FLAGS_NOTIFY_NEW_MAIL | EmailStore.Account.FLAGS_VIBRATE);
        
        mAccount.setDefaultAccount(mAccountDefault.isChecked());
        mAccount.setDescription(mAccountDescription.getText());
        mAccount.setName(mAccountName.getText());
        newFlags |= mAccountNotify.isChecked() ? EmailStore.Account.FLAGS_NOTIFY_NEW_MAIL : 0;
        mAccount.setAutomaticCheckIntervalMinutes(Integer.parseInt(mCheckFrequency.getValue()));
        if (mSyncWindow != null)
        {
            mAccount.setSyncWindow(Integer.parseInt(mSyncWindow.getValue()));
        }
        newFlags |= mAccountVibrate.isChecked() ? EmailStore.Account.FLAGS_VIBRATE : 0;
        SharedPreferences prefs = mAccountRingtone.getPreferenceManager().getSharedPreferences();
        mAccount.setRingtone(prefs.getString(PREFERENCE_RINGTONE, null));
        mAccount.setFlags(newFlags);
        
        mAccount.saveOrUpdate(this);
        Email.setServicesEnabled(this);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            saveSettings();
        }
        return super.onKeyDown(keyCode, event);
    }

    private void onIncomingSettings() {
        try {
            Store store = Store.getInstance(mAccount.getStoreUri(this), getApplication(), null);
            if (store != null) {
                Class<? extends android.app.Activity> setting = store.getSettingActivityClass();
                if (setting != null) {
                    java.lang.reflect.Method m = setting.getMethod("actionEditIncomingSettings",
                            android.app.Activity.class, EmailStore.Account.class);
                    m.invoke(null, this, mAccount);
                    mAccountDirty = true;
                }
            }
        } catch (Exception e) {
            Log.d(Email.LOG_TAG, "Error while trying to invoke store settings.", e);
        }
    }

    private void onOutgoingSettings() {
        try {
            Sender sender = Sender.getInstance(mAccount.getSenderUri(this), getApplication());
            if (sender != null) {
                Class<? extends android.app.Activity> setting = sender.getSettingActivityClass();
                if (setting != null) {
                    java.lang.reflect.Method m = setting.getMethod("actionEditOutgoingSettings",
                            android.app.Activity.class, EmailStore.Account.class);
                    m.invoke(null, this, mAccount);
                    mAccountDirty = true;
                }
            }
        } catch (Exception e) {
            Log.d(Email.LOG_TAG, "Error while trying to invoke sender settings.", e);
        }
    }

    private void onAddNewAccount() {
        AccountSetupBasics.actionNewAccount(this);
        finish();
    }
}
