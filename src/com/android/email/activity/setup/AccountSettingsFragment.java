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
import com.android.email.mail.MessagingException;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.HostAuth;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.RingtonePreference;
import android.provider.Calendar;
import android.provider.ContactsContract;
import android.util.Log;

/**
 * Fragment containing the main logic for account settings.  This also calls out to other
 * fragments for server settings.
 *
 * TODO: Move all "Restore" ops & other queries out of lifecycle methods and out of UI thread
 *
 * STOPSHIP: Remove fragment lifecycle logging
 */
public class AccountSettingsFragment extends PreferenceFragment {
    private static final String PREFERENCE_TOP_CATEGORY = "account_settings";
    private static final String PREFERENCE_DESCRIPTION = "account_description";
    private static final String PREFERENCE_NAME = "account_name";
    private static final String PREFERENCE_SIGNATURE = "account_signature";
    private static final String PREFERENCE_FREQUENCY = "account_check_frequency";
    private static final String PREFERENCE_DEFAULT = "account_default";
    private static final String PREFERENCE_NOTIFY = "account_notify";
    private static final String PREFERENCE_VIBRATE_WHEN = "account_settings_vibrate_when";
    private static final String PREFERENCE_RINGTONE = "account_ringtone";
    private static final String PREFERENCE_SERVER_CATERGORY = "account_servers";
    private static final String PREFERENCE_INCOMING = "incoming";
    private static final String PREFERENCE_OUTGOING = "outgoing";
    private static final String PREFERENCE_SYNC_CONTACTS = "account_sync_contacts";
    private static final String PREFERENCE_SYNC_CALENDAR = "account_sync_calendar";

    // These strings must match account_settings_vibrate_when_* strings in strings.xml
    private static final String PREFERENCE_VALUE_VIBRATE_WHEN_ALWAYS = "always";
    private static final String PREFERENCE_VALUE_VIBRATE_WHEN_SILENT = "silent";
    private static final String PREFERENCE_VALUE_VIBRATE_WHEN_NEVER = "never";

    private EditTextPreference mAccountDescription;
    private EditTextPreference mAccountName;
    private EditTextPreference mAccountSignature;
    private ListPreference mCheckFrequency;
    private ListPreference mSyncWindow;
    private CheckBoxPreference mAccountDefault;
    private CheckBoxPreference mAccountNotify;
    private ListPreference mAccountVibrateWhen;
    private RingtonePreference mAccountRingtone;
    private CheckBoxPreference mSyncContacts;
    private CheckBoxPreference mSyncCalendar;

    private Context mContext;
    private Account mAccount;
    private boolean mAccountDirty;
    private Callback mCallback = EmptyCallback.INSTANCE;
    private boolean mStarted;
    private boolean mLoaded;

    /**
     * Callback interface that owning activities must implement
     */
    public interface Callback {
        public void onIncomingSettings();
        public void onOutgoingSettings();
        public void abandonEdit();
    }

    private static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onIncomingSettings() { }
        @Override public void onOutgoingSettings() { }
        @Override public void abandonEdit() { }
    }

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MailboxListFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.account_settings_preferences);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MailboxListFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);

        mAccountDirty = false;
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MailboxListFragment onStart");
        }
        super.onStart();
        mStarted = true;
        if (mAccount != null && !mLoaded) {
            loadSettings();
        }
    }

    /**
     * Called when the fragment is visible to the user and actively running.
     */
    @Override
    public void onResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MailboxListFragment onResume");
        }
        super.onResume();

        if (mAccountDirty) {
            // if we are coming back from editing incoming or outgoing settings,
            // we need to refresh them here so we don't accidentally overwrite the
            // old values we're still holding here
            mAccount.mHostAuthRecv =
                HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeyRecv);
            mAccount.mHostAuthSend =
                HostAuth.restoreHostAuthWithId(mContext, mAccount.mHostAuthKeySend);
            // Because "delete policy" UI is on edit incoming settings, we have
            // to refresh that as well.
            Account refreshedAccount = Account.restoreAccountWithId(mContext, mAccount.mId);
            if (refreshedAccount == null || mAccount.mHostAuthRecv == null
                    || mAccount.mHostAuthSend == null) {
                mCallback.abandonEdit();
                return;
            }
            mAccount.setDeletePolicy(refreshedAccount.getDeletePolicy());
            mAccountDirty = false;
        }
    }

    @Override
    public void onPause() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MailboxListFragment onPause");
        }
        super.onPause();
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MailboxListFragment onStop");
        }
        super.onStop();
        mStarted = false;
    }

    /**
     * Called when the fragment is no longer in use.
     */
    @Override
    public void onDestroy() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MailboxListFragment onDestroy");
        }
        super.onDestroy();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "MailboxListFragment onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
    }

    /**
     * Activity provides callbacks here
     */
    public void setCallback(Callback callback) {
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
    }

    /**
     * Set the account, and start loading if ready to.  Note, this is a one-shot, because
     * once we mutate the preferences UX to match the account provided, we cannot re-mute
     * it (the fragment must be replaced.)
     */
    public void setAccount(Account account) {
        if (mAccount != null) {
            throw new IllegalStateException();
        }
        mAccount = account;
        mContext = getActivity();
        if (mStarted && !mLoaded) {
            loadSettings();
        }
    }

    /**
     * Load account data into preference UI
     */
    private void loadSettings() {
        // We can only do this once, so prevent repeat
        mLoaded = true;

        PreferenceCategory topCategory =
            (PreferenceCategory) findPreference(PREFERENCE_TOP_CATEGORY);
        topCategory.setTitle(mContext.getString(R.string.account_settings_title_fmt));

        mAccountDescription = (EditTextPreference) findPreference(PREFERENCE_DESCRIPTION);
        mAccountDescription.setSummary(mAccount.getDisplayName());
        mAccountDescription.setText(mAccount.getDisplayName());
        mAccountDescription.setOnPreferenceChangeListener(
            new Preference.OnPreferenceChangeListener() {
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    final String summary = newValue.toString();
                    mAccountDescription.setSummary(summary);
                    mAccountDescription.setText(summary);
                    return false;
                }
            }
        );

        mAccountName = (EditTextPreference) findPreference(PREFERENCE_NAME);
        mAccountName.setSummary(mAccount.getSenderName());
        mAccountName.setText(mAccount.getSenderName());
        mAccountName.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                final String summary = newValue.toString();
                mAccountName.setSummary(summary);
                mAccountName.setText(summary);
                return false;
            }
        });

        mAccountSignature = (EditTextPreference) findPreference(PREFERENCE_SIGNATURE);
        mAccountSignature.setSummary(mAccount.getSignature());
        mAccountSignature.setText(mAccount.getSignature());
        mAccountSignature.setOnPreferenceChangeListener(
                new Preference.OnPreferenceChangeListener() {
                    public boolean onPreferenceChange(Preference preference, Object newValue) {
                        String summary = newValue.toString();
                        if (summary == null || summary.length() == 0) {
                            mAccountSignature.setSummary(R.string.account_settings_signature_hint);
                        } else {
                            mAccountSignature.setSummary(summary);
                        }
                        mAccountSignature.setText(summary);
                        return false;
                    }
                });

        mCheckFrequency = (ListPreference) findPreference(PREFERENCE_FREQUENCY);

        // Before setting value, we may need to adjust the lists
        Store.StoreInfo info = Store.StoreInfo.getStoreInfo(mAccount.getStoreUri(mContext),
                mContext);
        if (info.mPushSupported) {
            mCheckFrequency.setEntries(R.array.account_settings_check_frequency_entries_push);
            mCheckFrequency.setEntryValues(R.array.account_settings_check_frequency_values_push);
        }

        mCheckFrequency.setValue(String.valueOf(mAccount.getSyncInterval()));
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
            mSyncWindow = new ListPreference(mContext);
            mSyncWindow.setTitle(R.string.account_setup_options_mail_window_label);
            mSyncWindow.setEntries(R.array.account_settings_mail_window_entries);
            mSyncWindow.setEntryValues(R.array.account_settings_mail_window_values);
            mSyncWindow.setValue(String.valueOf(mAccount.getSyncLookback()));
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
        mAccountDefault.setChecked(mAccount.mId == Account.getDefaultAccountId(mContext));

        mAccountNotify = (CheckBoxPreference) findPreference(PREFERENCE_NOTIFY);
        mAccountNotify.setChecked(0 != (mAccount.getFlags() & Account.FLAGS_NOTIFY_NEW_MAIL));

        mAccountRingtone = (RingtonePreference) findPreference(PREFERENCE_RINGTONE);

        // The following two lines act as a workaround for the RingtonePreference
        // which does not let us set/get the value programmatically
        SharedPreferences prefs = mAccountRingtone.getPreferenceManager().getSharedPreferences();
        prefs.edit().putString(PREFERENCE_RINGTONE, mAccount.getRingtone()).commit();

        mAccountVibrateWhen = (ListPreference) findPreference(PREFERENCE_VIBRATE_WHEN);
        boolean flagsVibrate = 0 != (mAccount.getFlags() & Account.FLAGS_VIBRATE_ALWAYS);
        boolean flagsVibrateSilent = 0 != (mAccount.getFlags() & Account.FLAGS_VIBRATE_WHEN_SILENT);
        mAccountVibrateWhen.setValue(
                flagsVibrate ? PREFERENCE_VALUE_VIBRATE_WHEN_ALWAYS :
                flagsVibrateSilent ? PREFERENCE_VALUE_VIBRATE_WHEN_SILENT :
                    PREFERENCE_VALUE_VIBRATE_WHEN_NEVER);

        findPreference(PREFERENCE_INCOMING).setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        mAccountDirty = true;
                        mCallback.onIncomingSettings();
                        return true;
                    }
                });

        // Hide the outgoing account setup link if it's not activated
        Preference prefOutgoing = findPreference(PREFERENCE_OUTGOING);
        boolean showOutgoing = true;
        try {
            Sender sender = Sender.getInstance(mContext, mAccount.getSenderUri(mContext));
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
                            mAccountDirty = true;
                            mCallback.onOutgoingSettings();
                            return true;
                        }
                    });
        } else {
            PreferenceCategory serverCategory = (PreferenceCategory) findPreference(
                    PREFERENCE_SERVER_CATERGORY);
            serverCategory.removePreference(prefOutgoing);
        }

        mSyncContacts = (CheckBoxPreference) findPreference(PREFERENCE_SYNC_CONTACTS);
        mSyncCalendar = (CheckBoxPreference) findPreference(PREFERENCE_SYNC_CALENDAR);
        if (mAccount.mHostAuthRecv.mProtocol.equals("eas")) {
            android.accounts.Account acct = new android.accounts.Account(mAccount.mEmailAddress,
                    Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            mSyncContacts.setChecked(ContentResolver
                    .getSyncAutomatically(acct, ContactsContract.AUTHORITY));
            mSyncCalendar.setChecked(ContentResolver
                    .getSyncAutomatically(acct, Calendar.AUTHORITY));
        } else {
            PreferenceCategory serverCategory = (PreferenceCategory) findPreference(
                    PREFERENCE_SERVER_CATERGORY);
            serverCategory.removePreference(mSyncContacts);
            serverCategory.removePreference(mSyncCalendar);
        }
    }

    public void saveSettings() {
        int newFlags = mAccount.getFlags() &
                ~(Account.FLAGS_NOTIFY_NEW_MAIL |
                        Account.FLAGS_VIBRATE_ALWAYS | Account.FLAGS_VIBRATE_WHEN_SILENT);

        mAccount.setDefaultAccount(mAccountDefault.isChecked());
        mAccount.setDisplayName(mAccountDescription.getText());
        mAccount.setSenderName(mAccountName.getText());
        mAccount.setSignature(mAccountSignature.getText());
        newFlags |= mAccountNotify.isChecked() ? Account.FLAGS_NOTIFY_NEW_MAIL : 0;
        mAccount.setSyncInterval(Integer.parseInt(mCheckFrequency.getValue()));
        if (mSyncWindow != null) {
            mAccount.setSyncLookback(Integer.parseInt(mSyncWindow.getValue()));
        }
        if (mAccountVibrateWhen.getValue().equals(PREFERENCE_VALUE_VIBRATE_WHEN_ALWAYS)) {
            newFlags |= Account.FLAGS_VIBRATE_ALWAYS;
        } else if (mAccountVibrateWhen.getValue().equals(PREFERENCE_VALUE_VIBRATE_WHEN_SILENT)) {
            newFlags |= Account.FLAGS_VIBRATE_WHEN_SILENT;
        }
        SharedPreferences prefs = mAccountRingtone.getPreferenceManager().getSharedPreferences();
        mAccount.setRingtone(prefs.getString(PREFERENCE_RINGTONE, null));
        mAccount.setFlags(newFlags);

        if (mAccount.mHostAuthRecv.mProtocol.equals("eas")) {
            android.accounts.Account acct = new android.accounts.Account(mAccount.mEmailAddress,
                    Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            ContentResolver.setSyncAutomatically(acct, ContactsContract.AUTHORITY,
                    mSyncContacts.isChecked());
            ContentResolver.setSyncAutomatically(acct, Calendar.AUTHORITY,
                    mSyncCalendar.isChecked());

        }
        AccountSettingsUtils.commitSettings(mContext, mAccount);
        Email.setServicesEnabled(mContext);
    }
}
