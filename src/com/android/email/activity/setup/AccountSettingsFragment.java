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

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Vibrator;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.RingtonePreference;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.Log;

import com.android.email.R;
import com.android.email.SecurityPolicy;
import com.android.email.provider.FolderPickerActivity;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.utility.Utility;

import java.util.ArrayList;

/**
 * Fragment containing the main logic for account settings.  This also calls out to other
 * fragments for server settings.
 *
 * TODO: Remove or make async the mAccountDirty reload logic.  Probably no longer needed.
 * TODO: Can we defer calling addPreferencesFromResource() until after we load the account?  This
 *       could reduce flicker.
 */
public class AccountSettingsFragment extends EmailPreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    // Keys used for arguments bundle
    private static final String BUNDLE_KEY_ACCOUNT_ID = "AccountSettingsFragment.AccountId";
    private static final String BUNDLE_KEY_ACCOUNT_EMAIL = "AccountSettingsFragment.Email";

    public static final String PREFERENCE_DESCRIPTION = "account_description";
    private static final String PREFERENCE_NAME = "account_name";
    private static final String PREFERENCE_SIGNATURE = "account_signature";
    private static final String PREFERENCE_QUICK_RESPONSES = "account_quick_responses";
    private static final String PREFERENCE_FREQUENCY = "account_check_frequency";
    private static final String PREFERENCE_BACKGROUND_ATTACHMENTS =
            "account_background_attachments";
    private static final String PREFERENCE_DEFAULT = "account_default";
    private static final String PREFERENCE_CATEGORY_DATA_USAGE = "data_usage";
    private static final String PREFERENCE_CATEGORY_NOTIFICATIONS = "account_notifications";
    private static final String PREFERENCE_NOTIFY = "account_notify";
    private static final String PREFERENCE_VIBRATE = "account_settings_vibrate";
    private static final String PREFERENCE_VIBRATE_OLD = "account_settings_vibrate_when";
    private static final String PREFERENCE_RINGTONE = "account_ringtone";
    private static final String PREFERENCE_CATEGORY_SERVER = "account_servers";
    private static final String PREFERENCE_CATEGORY_POLICIES = "account_policies";
    private static final String PREFERENCE_POLICIES_ENFORCED = "policies_enforced";
    private static final String PREFERENCE_POLICIES_UNSUPPORTED = "policies_unsupported";
    private static final String PREFERENCE_POLICIES_RETRY_ACCOUNT = "policies_retry_account";
    private static final String PREFERENCE_INCOMING = "incoming";
    private static final String PREFERENCE_OUTGOING = "outgoing";
    private static final String PREFERENCE_SYNC_CONTACTS = "account_sync_contacts";
    private static final String PREFERENCE_SYNC_CALENDAR = "account_sync_calendar";
    private static final String PREFERENCE_SYNC_EMAIL = "account_sync_email";

    private static final String PREFERENCE_SYSTEM_FOLDERS = "system_folders";
    private static final String PREFERENCE_SYSTEM_FOLDERS_TRASH = "system_folders_trash";
    private static final String PREFERENCE_SYSTEM_FOLDERS_SENT = "system_folders_sent";

    private EditTextPreference mAccountDescription;
    private EditTextPreference mAccountName;
    private EditTextPreference mAccountSignature;
    private ListPreference mCheckFrequency;
    private ListPreference mSyncWindow;
    private CheckBoxPreference mAccountBackgroundAttachments;
    private CheckBoxPreference mAccountDefault;
    private CheckBoxPreference mAccountNotify;
    private CheckBoxPreference mAccountVibrate;
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

    /** The e-mail of the account being edited. */
    private String mAccountEmail;

    // Async Tasks
    private AsyncTask<?,?,?> mLoadAccountTask;

    /**
     * Callback interface that owning activities must provide
     */
    public interface Callback {
        public void onSettingsChanged(Account account, String preference, Object value);
        public void onEditQuickResponses(Account account);
        public void onIncomingSettings(Account account);
        public void onOutgoingSettings(Account account);
        public void abandonEdit();
    }

    private static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onSettingsChanged(Account account, String preference, Object value) {}
        @Override public void onEditQuickResponses(Account account) {}
        @Override public void onIncomingSettings(Account account) {}
        @Override public void onOutgoingSettings(Account account) {}
        @Override public void abandonEdit() {}
    }

    /**
     * If launching with an arguments bundle, use this method to build the arguments.
     */
    public static Bundle buildArguments(long accountId, String email) {
        Bundle b = new Bundle();
        b.putLong(BUNDLE_KEY_ACCOUNT_ID, accountId);
        b.putString(BUNDLE_KEY_ACCOUNT_EMAIL, email);
        return b;
    }

    public static String getTitleFromArgs(Bundle args) {
        return (args == null) ? null : args.getString(BUNDLE_KEY_ACCOUNT_EMAIL);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mContext = activity;
    }

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSettingsFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        upgradeVibrateSetting();

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.account_settings_preferences);

        // Start loading the account data, if provided in the arguments
        // If not, activity must call startLoadingAccount() directly
        Bundle b = getArguments();
        if (b != null) {
            long accountId = b.getLong(BUNDLE_KEY_ACCOUNT_ID, -1);
            mAccountEmail = b.getString(BUNDLE_KEY_ACCOUNT_EMAIL);
            if (accountId >= 0 && !mLoaded) {
                startLoadingAccount(accountId);
            }
        }

        mAccountDirty = false;
    }

    /**
     * Upgrades the old tri-state vibrate setting to the new boolean value.
     */
    private void upgradeVibrateSetting() {
        final SharedPreferences sharedPreferences = getPreferenceManager().getSharedPreferences();

        if (!sharedPreferences.contains(PREFERENCE_VIBRATE)) {
            // Try to migrate the old one
            final boolean vibrate =
                    "always".equals(sharedPreferences.getString(PREFERENCE_VIBRATE_OLD, ""));
            sharedPreferences.edit().putBoolean(PREFERENCE_VIBRATE, vibrate);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSettingsFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSettingsFragment onStart");
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
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSettingsFragment onResume");
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
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSettingsFragment onPause");
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
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSettingsFragment onStop");
        }
        super.onStop();
        mStarted = false;
    }

    /**
     * Listen to all preference changes in this class.
     * @param preference
     * @param newValue
     * @return
     */
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue){
        // Can't use a switch here. Falling back to a giant conditional.
        final String key = preference.getKey();
        if (key.equals(PREFERENCE_DESCRIPTION)){
            String summary = newValue.toString().trim();
            if (TextUtils.isEmpty(summary)) {
                summary = mAccount.mEmailAddress;
            }
            mAccountDescription.setSummary(summary);
            mAccountDescription.setText(summary);
            preferenceChanged(PREFERENCE_DESCRIPTION, summary);
            return false;
        } else if (key.equals(PREFERENCE_FREQUENCY)) {
            final String summary = newValue.toString();
            final int index = mCheckFrequency.findIndexOfValue(summary);
            mCheckFrequency.setSummary(mCheckFrequency.getEntries()[index]);
            mCheckFrequency.setValue(summary);
            preferenceChanged(PREFERENCE_FREQUENCY, newValue);
            return false;
        } else if (key.equals(PREFERENCE_SIGNATURE)) {
            // Clean up signature if it's only whitespace (which is easy to do on a
            // soft keyboard) but leave whitespace in place otherwise, to give the user
            // maximum flexibility, e.g. the ability to indent
            String signature = newValue.toString();
            if (signature.trim().isEmpty()) {
                signature = "";
            }
            mAccountSignature.setText(signature);
            preferenceChanged(PREFERENCE_SIGNATURE, signature);
            return false;
        } else if (key.equals(PREFERENCE_NAME)) {
            final String summary = newValue.toString().trim();
            if (!TextUtils.isEmpty(summary)) {
                mAccountName.setSummary(summary);
                mAccountName.setText(summary);
                preferenceChanged(PREFERENCE_NAME, summary);
            }
            return false;
        } else if (key.equals(PREFERENCE_VIBRATE)) {
            final boolean vibrateSetting = (Boolean) newValue;
            mAccountVibrate.setChecked(vibrateSetting);
            preferenceChanged(PREFERENCE_VIBRATE, newValue);
            return false;
        } else {
            // Default behavior, just indicate that the preferences were written
            preferenceChanged(key, newValue);
            return true;
        }
    }

    /**
     * Called when the fragment is no longer in use.
     */
    @Override
    public void onDestroy() {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSettingsFragment onDestroy");
        }
        super.onDestroy();

        Utility.cancelTaskInterrupt(mLoadAccountTask);
        mLoadAccountTask = null;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            Log.d(Logging.LOG_TAG, "AccountSettingsFragment onSaveInstanceState");
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
        mLoadAccountTask = new LoadAccountTask().executeOnExecutor(
                AsyncTask.THREAD_POOL_EXECUTOR, accountId);
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
     * From a Policy, create and return an ArrayList of Strings that describe (simply) those
     * policies that are supported by the OS.  At the moment, the strings are simple (e.g.
     * "password required"); we should probably add more information (# characters, etc.), though
     */
    private ArrayList<String> getSystemPoliciesList(Policy policy) {
        Resources res = mContext.getResources();
        ArrayList<String> policies = new ArrayList<String>();
        if (policy.mPasswordMode != Policy.PASSWORD_MODE_NONE) {
            policies.add(res.getString(R.string.policy_require_password));
        }
        if (policy.mPasswordHistory > 0) {
            policies.add(res.getString(R.string.policy_password_history));
        }
        if (policy.mPasswordExpirationDays > 0) {
            policies.add(res.getString(R.string.policy_password_expiration));
        }
        if (policy.mMaxScreenLockTime > 0) {
            policies.add(res.getString(R.string.policy_screen_timeout));
        }
        if (policy.mDontAllowCamera) {
            policies.add(res.getString(R.string.policy_dont_allow_camera));
        }
        if (policy.mMaxEmailLookback != 0) {
            policies.add(res.getString(R.string.policy_email_age));
        }
        if (policy.mMaxCalendarLookback != 0) {
            policies.add(res.getString(R.string.policy_calendar_age));
        }
        return policies;
    }

    private void setPolicyListSummary(ArrayList<String> policies, String policiesToAdd,
            String preferenceName) {
        Policy.addPolicyStringToList(policiesToAdd, policies);
        if (policies.size() > 0) {
            Preference p = findPreference(preferenceName);
            StringBuilder sb = new StringBuilder();
            for (String desc: policies) {
                sb.append(desc);
                sb.append('\n');
            }
            p.setSummary(sb.toString());
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

        mAccountDescription = (EditTextPreference) findPreference(PREFERENCE_DESCRIPTION);
        mAccountDescription.setSummary(mAccount.getDisplayName());
        mAccountDescription.setText(mAccount.getDisplayName());
        mAccountDescription.setOnPreferenceChangeListener(this);

        mAccountName = (EditTextPreference) findPreference(PREFERENCE_NAME);
        String senderName = mAccount.getSenderName();
        // In rare cases, sendername will be null;  Change this to empty string to avoid NPE's
        if (senderName == null) senderName = "";
        mAccountName.setSummary(senderName);
        mAccountName.setText(senderName);
        mAccountName.setOnPreferenceChangeListener(this);

        mAccountSignature = (EditTextPreference) findPreference(PREFERENCE_SIGNATURE);
        mAccountSignature.setText(mAccount.getSignature());
        mAccountSignature.setOnPreferenceChangeListener(this);

        mCheckFrequency = (ListPreference) findPreference(PREFERENCE_FREQUENCY);
        String protocol = Account.getProtocol(mContext, mAccount.mId);
        EmailServiceInfo info = EmailServiceUtils.getServiceInfo(mContext, protocol);
        mCheckFrequency.setEntries(info.syncIntervalStrings);
        mCheckFrequency.setEntryValues(info.syncIntervals);
        mCheckFrequency.setValue(String.valueOf(mAccount.getSyncInterval()));
        mCheckFrequency.setSummary(mCheckFrequency.getEntry());
        mCheckFrequency.setOnPreferenceChangeListener(this);

        findPreference(PREFERENCE_QUICK_RESPONSES).setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        mAccountDirty = true;
                        mCallback.onEditQuickResponses(mAccount);
                        return true;
                    }
                });

        // Add check window preference
        PreferenceCategory dataUsageCategory =
                (PreferenceCategory) findPreference(PREFERENCE_CATEGORY_DATA_USAGE);

        mSyncWindow = null;
        if (info.offerLookback) {
            mSyncWindow = new ListPreference(mContext);
            mSyncWindow.setTitle(R.string.account_setup_options_mail_window_label);
            mSyncWindow.setValue(String.valueOf(mAccount.getSyncLookback()));
            mSyncWindow.setSummary(mSyncWindow.getEntry());
            MailboxSettings.setupLookbackPreferenceOptions(mContext, mSyncWindow, mAccount);

            // Must correspond to the hole in the XML file that's reserved.
            mSyncWindow.setOrder(2);
            mSyncWindow.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    final String summary = newValue.toString();
                    int index = mSyncWindow.findIndexOfValue(summary);
                    mSyncWindow.setSummary(mSyncWindow.getEntries()[index]);
                    mSyncWindow.setValue(summary);
                    preferenceChanged(preference.getKey(), newValue);
                    return false;
                }
            });
            dataUsageCategory.addPreference(mSyncWindow);
        }

        PreferenceCategory folderPrefs =
                (PreferenceCategory) findPreference(PREFERENCE_SYSTEM_FOLDERS);
        if (info.requiresSetup) {
            Preference trashPreference =
                    (Preference) findPreference(PREFERENCE_SYSTEM_FOLDERS_TRASH);
            Intent i = new Intent(mContext, FolderPickerActivity.class);
            Uri uri = EmailContent.CONTENT_URI.buildUpon().appendQueryParameter(
                    "account", Long.toString(mAccount.mId)).build();
            i.setData(uri);
            i.putExtra(FolderPickerActivity.MAILBOX_TYPE_EXTRA, Mailbox.TYPE_TRASH);
            trashPreference.setIntent(i);

            Preference sentPreference =
                    (Preference) findPreference(PREFERENCE_SYSTEM_FOLDERS_SENT);
            i = new Intent(mContext, FolderPickerActivity.class);
            i.setData(uri);
            i.putExtra(FolderPickerActivity.MAILBOX_TYPE_EXTRA, Mailbox.TYPE_SENT);
            sentPreference.setIntent(i);
        } else {
            getPreferenceScreen().removePreference(folderPrefs);
        }

        mAccountBackgroundAttachments = (CheckBoxPreference)
                findPreference(PREFERENCE_BACKGROUND_ATTACHMENTS);
        if (!info.offerAttachmentPreload) {
            dataUsageCategory.removePreference(mAccountBackgroundAttachments);
        } else {
            mAccountBackgroundAttachments.setChecked(
                    0 != (mAccount.getFlags() & Account.FLAGS_BACKGROUND_ATTACHMENTS));
            mAccountBackgroundAttachments.setOnPreferenceChangeListener(this);
        }

        mAccountDefault = (CheckBoxPreference) findPreference(PREFERENCE_DEFAULT);
        mAccountDefault.setChecked(mAccount.mId == mDefaultAccountId);
        mAccountDefault.setOnPreferenceChangeListener(this);

        mAccountNotify = (CheckBoxPreference) findPreference(PREFERENCE_NOTIFY);
        mAccountNotify.setChecked(0 != (mAccount.getFlags() & Account.FLAGS_NOTIFY_NEW_MAIL));
        mAccountNotify.setOnPreferenceChangeListener(this);

        mAccountRingtone = (RingtonePreference) findPreference(PREFERENCE_RINGTONE);
        mAccountRingtone.setOnPreferenceChangeListener(this);

        // The following two lines act as a workaround for the RingtonePreference
        // which does not let us set/get the value programmatically
        SharedPreferences prefs = mAccountRingtone.getPreferenceManager().getSharedPreferences();
        prefs.edit().putString(PREFERENCE_RINGTONE, mAccount.getRingtone()).apply();

        // Set the vibrator value, or hide it on devices w/o a vibrator
        mAccountVibrate = (CheckBoxPreference) findPreference(PREFERENCE_VIBRATE);
        Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator.hasVibrator()) {
            // Calculate the value to set based on the choices, and set the value.
            final boolean vibrateSetting = 0 != (mAccount.getFlags() & Account.FLAGS_VIBRATE);
            mAccountVibrate.setChecked(vibrateSetting);

            // When the value is changed, update the setting.
            mAccountVibrate.setOnPreferenceChangeListener(this);
        } else {
            // No vibrator present. Remove the preference altogether.
            PreferenceCategory notificationsCategory = (PreferenceCategory)
                    findPreference(PREFERENCE_CATEGORY_NOTIFICATIONS);
            notificationsCategory.removePreference(mAccountVibrate);
        }

        final Preference retryAccount = findPreference(PREFERENCE_POLICIES_RETRY_ACCOUNT);
        final PreferenceCategory policiesCategory = (PreferenceCategory) findPreference(
                PREFERENCE_CATEGORY_POLICIES);
        if (mAccount.mPolicyKey > 0) {
            // Make sure we have most recent data from account
            mAccount.refresh(mContext);
            Policy policy = Policy.restorePolicyWithId(mContext, mAccount.mPolicyKey);
            if (policy == null) {
                // The account has been deleted?  Crazy, but not impossible
                return;
            }
            if (policy.mProtocolPoliciesEnforced != null) {
                ArrayList<String> policies = getSystemPoliciesList(policy);
                setPolicyListSummary(policies, policy.mProtocolPoliciesEnforced,
                        PREFERENCE_POLICIES_ENFORCED);
            }
            if (policy.mProtocolPoliciesUnsupported != null) {
                ArrayList<String> policies = new ArrayList<String>();
                setPolicyListSummary(policies, policy.mProtocolPoliciesUnsupported,
                        PREFERENCE_POLICIES_UNSUPPORTED);
            } else {
                // Don't show "retry" unless we have unsupported policies
                policiesCategory.removePreference(retryAccount);
            }
        } else {
            // Remove the category completely if there are no policies
            getPreferenceScreen().removePreference(policiesCategory);
        }

        retryAccount.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        // Release the account
                        SecurityPolicy.setAccountHoldFlag(mContext, mAccount, false);
                        // Remove the preference
                        policiesCategory.removePreference(retryAccount);
                        return true;
                    }
                });
        findPreference(PREFERENCE_INCOMING).setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        mAccountDirty = true;
                        mCallback.onIncomingSettings(mAccount);
                        return true;
                    }
                });

        // Hide the outgoing account setup link if it's not activated
        Preference prefOutgoing = findPreference(PREFERENCE_OUTGOING);
        if (info.usesSmtp) {
            prefOutgoing.setOnPreferenceClickListener(
                    new Preference.OnPreferenceClickListener() {
                        @Override
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
        if (info.syncContacts || info.syncCalendar) {
            android.accounts.Account acct = new android.accounts.Account(mAccount.mEmailAddress,
                    info.accountType);
            if (info.syncContacts) {
                mSyncContacts.setChecked(ContentResolver
                        .getSyncAutomatically(acct, ContactsContract.AUTHORITY));
                mSyncContacts.setOnPreferenceChangeListener(this);
            } else {
                mSyncContacts.setChecked(false);
                mSyncContacts.setEnabled(false);
            }
            if (info.syncCalendar) {
                mSyncCalendar.setChecked(ContentResolver
                        .getSyncAutomatically(acct, CalendarContract.AUTHORITY));
                mSyncCalendar.setOnPreferenceChangeListener(this);
            } else {
                mSyncCalendar.setChecked(false);
                mSyncCalendar.setEnabled(false);
            }
            mSyncEmail.setChecked(ContentResolver
                    .getSyncAutomatically(acct, EmailContent.AUTHORITY));
            mSyncEmail.setOnPreferenceChangeListener(this);
        } else {
            dataUsageCategory.removePreference(mSyncContacts);
            dataUsageCategory.removePreference(mSyncCalendar);
            dataUsageCategory.removePreference(mSyncEmail);
        }
    }

    /**
     * Called any time a preference is changed.
     */
    private void preferenceChanged(String preference, Object value) {
        mCallback.onSettingsChanged(mAccount, preference, value);
        mSaveOnExit = true;
    }

    /*
     * Note: This writes the settings on the UI thread.  This has to be done so the settings are
     * committed before we might be killed.
     */
    private void saveSettings() {
        // Turn off all controlled flags - will turn them back on while checking UI elements
        int newFlags = mAccount.getFlags() &
                ~(Account.FLAGS_NOTIFY_NEW_MAIL |
                        Account.FLAGS_VIBRATE |
                        Account.FLAGS_BACKGROUND_ATTACHMENTS);

        newFlags |= mAccountBackgroundAttachments.isChecked() ?
                Account.FLAGS_BACKGROUND_ATTACHMENTS : 0;
        mAccount.setDefaultAccount(mAccountDefault.isChecked());
        // If the display name has been cleared, we'll reset it to the default value (email addr)
        mAccount.setDisplayName(mAccountDescription.getText().trim());
        // The sender name must never be empty (this is enforced by the preference editor)
        mAccount.setSenderName(mAccountName.getText().trim());
        mAccount.setSignature(mAccountSignature.getText());
        newFlags |= mAccountNotify.isChecked() ? Account.FLAGS_NOTIFY_NEW_MAIL : 0;
        mAccount.setSyncInterval(Integer.parseInt(mCheckFrequency.getValue()));
        if (mSyncWindow != null) {
            mAccount.setSyncLookback(Integer.parseInt(mSyncWindow.getValue()));
        }
        if (mAccountVibrate.isChecked()) {
            newFlags |= Account.FLAGS_VIBRATE;
        }
        SharedPreferences prefs = mAccountRingtone.getPreferenceManager().getSharedPreferences();
        mAccount.setRingtone(prefs.getString(PREFERENCE_RINGTONE, null));
        mAccount.setFlags(newFlags);

        EmailServiceInfo info =
                EmailServiceUtils.getServiceInfo(mContext, mAccount.getProtocol(mContext));
        if (info.syncContacts || info.syncCalendar) {
            android.accounts.Account acct = new android.accounts.Account(mAccount.mEmailAddress,
                    info.accountType);
            ContentResolver.setSyncAutomatically(acct, ContactsContract.AUTHORITY,
                    mSyncContacts.isChecked());
            ContentResolver.setSyncAutomatically(acct, CalendarContract.AUTHORITY,
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
        MailActivityEmail.setServicesEnabledAsync(mContext);
    }

    public String getAccountEmail() {
        // Get the e-mail address of the account being editted, if this is for an existing account.
        return mAccountEmail;
    }
}
