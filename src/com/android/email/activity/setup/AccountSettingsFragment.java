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
import com.android.email.mail.MessagingException;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.HostAuth;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentTransaction;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Vibrator;
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
 * TODO: Remove or make async the mAccountDirty reload logic.  Probably no longer needed.
 * TODO: Can we defer calling addPreferencesFromResource() until after we load the account?  This
 *       could reduce flicker.
 */
public class AccountSettingsFragment extends PreferenceFragment {

    // Keys used for arguments bundle
    private static final String BUNDLE_KEY_ACCOUNT_ID = "AccountSettingsFragment.AccountId";

    private static final String PREFERENCE_CATEGORY_TOP = "account_settings";
    private static final String PREFERENCE_DESCRIPTION = "account_description";
    private static final String PREFERENCE_NAME = "account_name";
    private static final String PREFERENCE_SIGNATURE = "account_signature";
    private static final String PREFERENCE_FREQUENCY = "account_check_frequency";
    private static final String PREFERENCE_DEFAULT = "account_default";
    private static final String PREFERENCE_CATEGORY_NOTIFICATIONS = "account_notifications";
    private static final String PREFERENCE_NOTIFY = "account_notify";
    private static final String PREFERENCE_VIBRATE_WHEN = "account_settings_vibrate_when";
    private static final String PREFERENCE_RINGTONE = "account_ringtone";
    private static final String PREFERENCE_CATEGORY_SERVER = "account_servers";
    private static final String PREFERENCE_INCOMING = "incoming";
    private static final String PREFERENCE_OUTGOING = "outgoing";
    private static final String PREFERENCE_SYNC_CONTACTS = "account_sync_contacts";
    private static final String PREFERENCE_SYNC_CALENDAR = "account_sync_calendar";
    private static final String PREFERENCE_SYNC_EMAIL = "account_sync_email";
    private static final String PREFERENCE_DELETE_ACCOUNT = "delete_account";

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
    private CheckBoxPreference mSyncEmail;

    private Context mContext;
    private Account mAccount;
    private boolean mAccountDirty;
    private long mDefaultAccountId;
    private Callback mCallback = EmptyCallback.INSTANCE;
    private boolean mStarted;
    private boolean mLoaded;
    private boolean mSaveOnExit;

    // Async Tasks
    private AsyncTask<?,?,?> mLoadAccountTask;

    /**
     * Callback interface that owning activities must provide
     */
    public interface Callback {
        public void onIncomingSettings(Account account);
        public void onOutgoingSettings(Account account);
        public void abandonEdit();
        public void deleteAccount(Account account);
    }

    private static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onIncomingSettings(Account account) { }
        @Override public void onOutgoingSettings(Account account) { }
        @Override public void abandonEdit() { }
        @Override public void deleteAccount(Account account) { }
    }

    /**
     * If launching with an arguments bundle, use this method to build the arguments.
     * @param accountId The account being modified
     */
    public static Bundle buildArguments(long accountId) {
        Bundle b = new Bundle();
        b.putLong(BUNDLE_KEY_ACCOUNT_ID, accountId);
        return b;
    }

    /**
     * Called when a fragment is first attached to its activity.
     * {@link #onCreate(Bundle)} will be called after this.
     */
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        mContext = activity;

        // Notify the activity that we're here.
        if (activity instanceof AccountSettingsXL) {
            ((AccountSettingsXL)activity).onAttach(this);
        }
    }

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSettingsFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.account_settings_preferences);

        // Start loading the account data, if provided in the arguments
        // If not, activity must call startLoadingAccount() directly
        Bundle b = getArguments();
        if (b != null) {
            long accountId = b.getLong(BUNDLE_KEY_ACCOUNT_ID, -1);
            if (accountId >= 0 && !mLoaded) {
                startLoadingAccount(accountId);
            }
        }

        mAccountDirty = false;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSettingsFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSettingsFragment onStart");
        }
        super.onStart();
        mStarted = true;

        // If the loaded account is ready now, load the UI
        if (mAccount != null && !mLoaded) {
            loadSettings();
        }
    }

    /**
     * Called when the fragment is visible to the user and actively running.
     * TODO: Don't read account data on UI thread.  This should be fixed by removing the need
     * to do this, not by spinning up yet another thread.
     */
    @Override
    public void onResume() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSettingsFragment onResume");
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
                mSaveOnExit = false;
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
            Log.d(Email.LOG_TAG, "AccountSettingsFragment onPause");
        }
        super.onPause();
        if (mSaveOnExit) {
            saveSettings();
        }
    }

    /**
     * Called when the Fragment is no longer started.
     */
    @Override
    public void onStop() {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSettingsFragment onStop");
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
            Log.d(Email.LOG_TAG, "AccountSettingsFragment onDestroy");
        }
        super.onDestroy();

        Utility.cancelTaskInterrupt(mLoadAccountTask);
        mLoadAccountTask = null;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Email.LOG_TAG, "AccountSettingsFragment onSaveInstanceState");
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
     * Start loading a single account in preparation for editing it
     */
    public void startLoadingAccount(long accountId) {
        Utility.cancelTaskInterrupt(mLoadAccountTask);
        mLoadAccountTask = new LoadAccountTask().execute(accountId);
    }

    /**
     * Async task to load account in order to view/edit it
     */
    private class LoadAccountTask extends AsyncTask<Long, Void, Object[]> {
        @Override
        protected Object[] doInBackground(Long... params) {
            long accountId = params[0];
            Account account = Account.restoreAccountWithId(mContext, accountId);
            if (account != null) {
                account.mHostAuthRecv =
                    HostAuth.restoreHostAuthWithId(mContext, account.mHostAuthKeyRecv);
                account.mHostAuthSend =
                    HostAuth.restoreHostAuthWithId(mContext, account.mHostAuthKeySend);
                if (account.mHostAuthRecv == null || account.mHostAuthSend == null) {
                    account = null;
                }
            }
            long defaultAccountId = Account.getDefaultAccountId(mContext);
            return new Object[] { account, Long.valueOf(defaultAccountId) };
        }

        @Override
        protected void onPostExecute(Object[] results) {
            if (results != null && !isCancelled()) {
                Account account = (Account) results[0];
                if (account == null) {
                    mSaveOnExit = false;
                    mCallback.abandonEdit();
                } else {
                    mAccount = account;
                    mDefaultAccountId = (Long) results[1];
                    if (mStarted && !mLoaded) {
                        loadSettings();
                    }
                }
            }
        }
    }

    /**
     * Load account data into preference UI
     */
    private void loadSettings() {
        // We can only do this once, so prevent repeat
        mLoaded = true;
        // Once loaded the data is ready to be saved, as well
        mSaveOnExit = false;

        PreferenceCategory topCategory =
            (PreferenceCategory) findPreference(PREFERENCE_CATEGORY_TOP);
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
                    onPreferenceChanged();
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
                onPreferenceChanged();
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
                        onPreferenceChanged();
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
                onPreferenceChanged();
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
                    onPreferenceChanged();
                    return false;
                }
            });
            topCategory.addPreference(mSyncWindow);
        }

        mAccountDefault = (CheckBoxPreference) findPreference(PREFERENCE_DEFAULT);
        mAccountDefault.setChecked(mAccount.mId == mDefaultAccountId);
        mAccountDefault.setOnPreferenceChangeListener(mPreferenceChangeListener);

        mAccountNotify = (CheckBoxPreference) findPreference(PREFERENCE_NOTIFY);
        mAccountNotify.setChecked(0 != (mAccount.getFlags() & Account.FLAGS_NOTIFY_NEW_MAIL));
        mAccountNotify.setOnPreferenceChangeListener(mPreferenceChangeListener);

        mAccountRingtone = (RingtonePreference) findPreference(PREFERENCE_RINGTONE);
        mAccountRingtone.setOnPreferenceChangeListener(mPreferenceChangeListener);

        // The following two lines act as a workaround for the RingtonePreference
        // which does not let us set/get the value programmatically
        SharedPreferences prefs = mAccountRingtone.getPreferenceManager().getSharedPreferences();
        prefs.edit().putString(PREFERENCE_RINGTONE, mAccount.getRingtone()).apply();

        // Set the vibrator value, or hide it on devices w/o a vibrator
        mAccountVibrateWhen = (ListPreference) findPreference(PREFERENCE_VIBRATE_WHEN);
        Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator.hasVibrator()) {
            boolean flagsVibrate = 0 != (mAccount.getFlags() & Account.FLAGS_VIBRATE_ALWAYS);
            boolean flagsVibrateSilent =
                    0 != (mAccount.getFlags() & Account.FLAGS_VIBRATE_WHEN_SILENT);
            mAccountVibrateWhen.setValue(
                    flagsVibrate ? PREFERENCE_VALUE_VIBRATE_WHEN_ALWAYS :
                    flagsVibrateSilent ? PREFERENCE_VALUE_VIBRATE_WHEN_SILENT :
                        PREFERENCE_VALUE_VIBRATE_WHEN_NEVER);
            mAccountVibrateWhen.setOnPreferenceChangeListener(mPreferenceChangeListener);
        } else {
            PreferenceCategory notificationsCategory = (PreferenceCategory)
                    findPreference(PREFERENCE_CATEGORY_NOTIFICATIONS);
            notificationsCategory.removePreference(mAccountVibrateWhen);
        }

        findPreference(PREFERENCE_INCOMING).setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        mAccountDirty = true;
                        mCallback.onIncomingSettings(mAccount);
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
                            mCallback.onOutgoingSettings(mAccount);
                            return true;
                        }
                    });
        } else {
            PreferenceCategory serverCategory = (PreferenceCategory) findPreference(
                    PREFERENCE_CATEGORY_SERVER);
            serverCategory.removePreference(prefOutgoing);
        }

        mSyncContacts = (CheckBoxPreference) findPreference(PREFERENCE_SYNC_CONTACTS);
        mSyncCalendar = (CheckBoxPreference) findPreference(PREFERENCE_SYNC_CALENDAR);
        mSyncEmail = (CheckBoxPreference) findPreference(PREFERENCE_SYNC_EMAIL);
        if (mAccount.mHostAuthRecv.mProtocol.equals("eas")) {
            android.accounts.Account acct = new android.accounts.Account(mAccount.mEmailAddress,
                    Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            mSyncContacts.setChecked(ContentResolver
                    .getSyncAutomatically(acct, ContactsContract.AUTHORITY));
            mSyncContacts.setOnPreferenceChangeListener(mPreferenceChangeListener);
            mSyncCalendar.setChecked(ContentResolver
                    .getSyncAutomatically(acct, Calendar.AUTHORITY));
            mSyncCalendar.setOnPreferenceChangeListener(mPreferenceChangeListener);
            mSyncEmail.setChecked(ContentResolver
                    .getSyncAutomatically(acct, EmailContent.AUTHORITY));
            mSyncEmail.setOnPreferenceChangeListener(mPreferenceChangeListener);
        } else {
            PreferenceCategory serverCategory = (PreferenceCategory) findPreference(
                    PREFERENCE_CATEGORY_SERVER);
            serverCategory.removePreference(mSyncContacts);
            serverCategory.removePreference(mSyncCalendar);
            serverCategory.removePreference(mSyncEmail);
        }

        // Temporary home for delete account
        Preference prefDeleteAccount = findPreference(PREFERENCE_DELETE_ACCOUNT);
        prefDeleteAccount.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        DeleteAccountFragment dialogFragment = DeleteAccountFragment.newInstance(
                                mAccount, AccountSettingsFragment.this);
                        FragmentTransaction ft = getFragmentManager().beginTransaction();
                        ft.addToBackStack(null);
                        dialogFragment.show(ft, DeleteAccountFragment.TAG);
                        return true;
                    }
                });
    }

    /**
     * Generic onPreferenceChanged listener for the preferences (above) that just need
     * to be written, without extra tweaks
     */
    private Preference.OnPreferenceChangeListener mPreferenceChangeListener =
        new Preference.OnPreferenceChangeListener() {
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                onPreferenceChanged();
                return true;
            }
    };

    /**
     * Called any time a preference is changed.
     */
    private void onPreferenceChanged() {
        mSaveOnExit = true;
    }

    /*
     * Note: This writes the settings on the UI thread.  This has to be done so the settings are
     * committed before we might be killed.
     */
    private void saveSettings() {
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
            ContentResolver.setSyncAutomatically(acct, EmailContent.AUTHORITY,
                    mSyncEmail.isChecked());
        }

        // Commit the changes
        // Note, this is done in the UI thread because at this point, we must commit
        // all changes - any time after onPause completes, we could be killed.  This is analogous
        // to the way that SharedPreferences tries to work off-thread in apply(), but will pause
        // until completion in onPause().
        ContentValues cv = AccountSettingsUtils.getAccountContentValues(mAccount);
        mAccount.update(mContext, cv);

        // Run the remaining changes off-thread
        Email.setServicesEnabledAsync(mContext);
    }

    /**
     * Dialog fragment to show "remove account?" dialog
     */
    public static class DeleteAccountFragment extends DialogFragment {
        private final static String TAG = "DeleteAccountFragment";

        // Argument bundle keys
        private final static String BUNDLE_KEY_ACCOUNT_NAME = "DeleteAccountFragment.Name";

        /**
         * Create the dialog with parameters
         */
        public static DeleteAccountFragment newInstance(Account account, Fragment parentFragment) {
            DeleteAccountFragment f = new DeleteAccountFragment();
            Bundle b = new Bundle();
            b.putString(BUNDLE_KEY_ACCOUNT_NAME, account.getDisplayName());
            f.setArguments(b);
            f.setTargetFragment(parentFragment, 0);
            return f;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Context context = getActivity();
            final String name = getArguments().getString(BUNDLE_KEY_ACCOUNT_NAME);

            return new AlertDialog.Builder(context)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(R.string.account_delete_dlg_title)
                .setMessage(context.getString(R.string.account_delete_dlg_instructions_fmt, name))
                .setPositiveButton(
                        R.string.okay_action,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                Fragment f = getTargetFragment();
                                if (f instanceof AccountSettingsFragment) {
                                    ((AccountSettingsFragment)f).finishDeleteAccount();
                                }
                                dismiss();
                            }
                        })
                .setNegativeButton(
                        R.string.cancel_action,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                dismiss();
                            }
                        })
                .create();
        }
    }

    /**
     * Callback from delete account dialog - passes the delete command up to the activity
     */
    private void finishDeleteAccount() {
        mSaveOnExit = false;
        mCallback.deleteAccount(mAccount);
    }
}
