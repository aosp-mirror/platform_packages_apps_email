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
import android.content.res.Resources;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Vibrator;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceFragment;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuInflater;

import com.android.email.R;
import com.android.email.SecurityPolicy;
import com.android.email.provider.EmailProvider;
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
import com.android.mail.preferences.AccountPreferences;
import com.android.mail.preferences.FolderPreferences;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.ui.settings.SettingsUtils;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.NotificationUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Fragment containing the main logic for account settings.  This also calls out to other
 * fragments for server settings.
 *
 * TODO: Remove or make async the mAccountDirty reload logic.  Probably no longer needed.
 * TODO: Can we defer calling addPreferencesFromResource() until after we load the account?  This
 *       could reduce flicker.
 */
public class AccountSettingsFragment extends PreferenceFragment
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
    private static final String PREFERENCE_CATEGORY_DATA_USAGE = "data_usage";
    private static final String PREFERENCE_CATEGORY_NOTIFICATIONS = "account_notifications";
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

    // Request code to start different activities.
    private static final int RINGTONE_REQUEST_CODE = 0;

    private EditTextPreference mAccountDescription;
    private EditTextPreference mAccountName;
    private EditTextPreference mAccountSignature;
    private ListPreference mCheckFrequency;
    private ListPreference mSyncWindow;
    private CheckBoxPreference mAccountBackgroundAttachments;
    private CheckBoxPreference mInboxNotify;
    private CheckBoxPreference mInboxVibrate;
    private Preference mInboxRingtone;
    private PreferenceCategory mNotificationsCategory;
    private CheckBoxPreference mSyncContacts;
    private CheckBoxPreference mSyncCalendar;
    private CheckBoxPreference mSyncEmail;

    private Context mContext;

    /**
     * mAccount is email-specific, transition to using mUiAccount instead
     */
    @Deprecated
    private Account mAccount;
    private boolean mAccountDirty;
    private com.android.mail.providers.Account mUiAccount;
    private Callback mCallback = EmptyCallback.INSTANCE;
    private boolean mStarted;
    private boolean mLoaded;
    private boolean mSaveOnExit;

    private Ringtone mRingtone;

    private FolderPreferences mInboxFolderPreferences;

    /** The e-mail of the account being edited. */
    private String mAccountEmail;

    // Async Tasks
    private AsyncTask<?,?,?> mLoadAccountTask;

    /**
     * Callback interface that owning activities must provide
     */
    public interface Callback {
        public void onSettingsChanged(Account account, String preference, Object value);
        public void onEditQuickResponses(com.android.mail.providers.Account account);
        public void onIncomingSettings(Account account);
        public void onOutgoingSettings(Account account);
        public void abandonEdit();
    }

    private static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onSettingsChanged(Account account, String preference, Object value) {}
        @Override public void onEditQuickResponses(com.android.mail.providers.Account account) {}
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
            LogUtils.d(Logging.LOG_TAG, "AccountSettingsFragment onCreate");
        }
        super.onCreate(savedInstanceState);

        setHasOptionsMenu(true);

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

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSettingsFragment onActivityCreated");
        }
        super.onActivityCreated(savedInstanceState);
    }

    /**
     * Called when the Fragment is visible to the user.
     */
    @Override
    public void onStart() {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSettingsFragment onStart");
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
            LogUtils.d(Logging.LOG_TAG, "AccountSettingsFragment onResume");
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
            if (refreshedAccount == null || mAccount.mHostAuthRecv == null) {
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
            LogUtils.d(Logging.LOG_TAG, "AccountSettingsFragment onPause");
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
            LogUtils.d(Logging.LOG_TAG, "AccountSettingsFragment onStop");
        }
        super.onStop();
        mStarted = false;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case RINGTONE_REQUEST_CODE:
                if (resultCode == Activity.RESULT_OK && data != null) {
                    Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
                    setRingtone(uri);
                }
                break;
        }
    }

    /**
     * Sets the current ringtone.
     */
    private void setRingtone(Uri ringtone) {
        if (ringtone != null) {
            mInboxFolderPreferences.setNotificationRingtoneUri(ringtone.toString());
            mRingtone = RingtoneManager.getRingtone(getActivity(), ringtone);
        } else {
            // Null means silent was selected.
            mInboxFolderPreferences.setNotificationRingtoneUri("");
            mRingtone = null;
        }

        setRingtoneSummary();
    }

    private void setRingtoneSummary() {
        final String summary = mRingtone != null ? mRingtone.getTitle(mContext)
                : mContext.getString(R.string.silent_ringtone);

        mInboxRingtone.setSummary(summary);
    }

    /**
     * Listen to all preference changes in this class.
     * @param preference The changed Preference
     * @param newValue The new value of the Preference
     * @return True to update the state of the Preference with the new value
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
            SettingsUtils.updatePreferenceSummary(mAccountSignature, signature,
                    R.string.preferences_signature_summary_not_set);
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
        } else if (FolderPreferences.PreferenceKeys.NOTIFICATION_VIBRATE.equals(key)) {
            final boolean vibrateSetting = (Boolean) newValue;
            mInboxVibrate.setChecked(vibrateSetting);
            mInboxFolderPreferences.setNotificationVibrateEnabled(vibrateSetting);
            preferenceChanged(FolderPreferences.PreferenceKeys.NOTIFICATION_VIBRATE, newValue);
            return true;
        } else if (FolderPreferences.PreferenceKeys.NOTIFICATIONS_ENABLED.equals(key)) {
            mInboxFolderPreferences.setNotificationsEnabled((Boolean) newValue);
            preferenceChanged(FolderPreferences.PreferenceKeys.NOTIFICATIONS_ENABLED, newValue);
            return true;
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
            LogUtils.d(Logging.LOG_TAG, "AccountSettingsFragment onDestroy");
        }
        super.onDestroy();

        Utility.cancelTaskInterrupt(mLoadAccountTask);
        mLoadAccountTask = null;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        if (Logging.DEBUG_LIFECYCLE && MailActivityEmail.DEBUG) {
            LogUtils.d(Logging.LOG_TAG, "AccountSettingsFragment onSaveInstanceState");
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.settings_fragment_menu, menu);
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
    private class LoadAccountTask extends AsyncTask<Long, Void, Map<String, Object>> {
        static final String ACCOUNT_KEY = "account";
        static final String UI_ACCOUNT_KEY = "uiAccount";

        @Override
        protected Map<String, Object> doInBackground(Long... params) {
            final long accountId = params[0];
            Account account = Account.restoreAccountWithId(mContext, accountId);
            if (account != null) {
                account.mHostAuthRecv =
                    HostAuth.restoreHostAuthWithId(mContext, account.mHostAuthKeyRecv);
                account.mHostAuthSend =
                    HostAuth.restoreHostAuthWithId(mContext, account.mHostAuthKeySend);
                if (account.mHostAuthRecv == null) {
                    account = null;
                }
            }

            final Cursor accountCursor = mContext.getContentResolver().query(EmailProvider
                    .uiUri("uiaccount", accountId), UIProvider.ACCOUNTS_PROJECTION, null,
                    null, null);

            final com.android.mail.providers.Account uiAccount;
            try {
                if (accountCursor != null && accountCursor.moveToFirst()) {
                    uiAccount = new com.android.mail.providers.Account(accountCursor);
                } else {
                    uiAccount = null;
                }
            } finally {
                if (accountCursor != null) {
                    accountCursor.close();
                }
            }

            final Map<String, Object> map = new HashMap<String, Object>(2);
            map.put(ACCOUNT_KEY, account);
            map.put(UI_ACCOUNT_KEY, uiAccount);
            return map;
        }

        @Override
        protected void onPostExecute(Map<String, Object> map) {
            if (!isCancelled()) {
                final Account account = (Account) map.get(ACCOUNT_KEY);
                mUiAccount = (com.android.mail.providers.Account) map.get(UI_ACCOUNT_KEY);
                if (account == null) {
                    mSaveOnExit = false;
                    mCallback.abandonEdit();
                } else {
                    mAccount = account;
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
     * Loads settings that are dependent on a {@link com.android.mail.providers.Account}, which
     * must be obtained off the main thread.
     */
    private void loadSettingsOffMainThread() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                if (mUiAccount == null) {
                    return;
                }
                final Cursor folderCursor = mContext.getContentResolver().query(
                        mUiAccount.settings.defaultInbox, UIProvider.FOLDERS_PROJECTION, null, null,
                        null);
                if (folderCursor == null) {
                    return;
                }

                final Folder folder;
                try {
                    if (folderCursor.moveToFirst()) {
                        folder = new Folder(folderCursor);
                    } else {
                        return;
                    }
                } finally {
                    folderCursor.close();
                }

                final AccountPreferences accountPreferences =
                        new AccountPreferences(mContext, mUiAccount.getEmailAddress());
                mInboxFolderPreferences =
                        new FolderPreferences(mContext, mUiAccount.getEmailAddress(), folder, true);

                NotificationUtils.moveNotificationSetting(
                        accountPreferences, mInboxFolderPreferences);

                final String ringtoneUri = mInboxFolderPreferences.getNotificationRingtoneUri();
                if (!TextUtils.isEmpty(ringtoneUri)) {
                    mRingtone = RingtoneManager.getRingtone(getActivity(), Uri.parse(ringtoneUri));
                }

                final Activity activity = getActivity();
                if (activity != null) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (mInboxNotify == null) {
                                // Should only happen if we've aborted the settings screen
                                return;
                            }
                            mInboxNotify.setChecked(
                                    mInboxFolderPreferences.areNotificationsEnabled());
                            mInboxVibrate.setChecked(
                                    mInboxFolderPreferences.isNotificationVibrateEnabled());
                            setRingtoneSummary();
                            // Notification preferences must be disabled until after
                            // mInboxFolderPreferences is available, so enable them here.
                            mNotificationsCategory.setEnabled(true);
                        }
                    });
                }
            }
        }).start();
    }

    /**
     * Load account data into preference UI. This must be called on the main thread.
     */
    private void loadSettings() {
        // We can only do this once, so prevent repeat
        mLoaded = true;
        // Once loaded the data is ready to be saved, as well
        mSaveOnExit = false;

        loadSettingsOffMainThread();

        final String protocol = Account.getProtocol(mContext, mAccount.mId);
        final EmailServiceInfo info = EmailServiceUtils.getServiceInfo(mContext, protocol);
        if (info == null) {
            LogUtils.e(Logging.LOG_TAG, "Could not find service info for account " + mAccount.mId
                    + " with protocol " + protocol);
            getActivity().onBackPressed();
            // TODO: put up some sort of dialog here to tell the user something went wrong
            return;
        }
        final android.accounts.Account androidAcct = new android.accounts.Account(
                mAccount.mEmailAddress, info.accountType);

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

        final String accountSignature = mAccount.getSignature();
        mAccountSignature = (EditTextPreference) findPreference(PREFERENCE_SIGNATURE);
        mAccountSignature.setText(accountSignature);
        mAccountSignature.setOnPreferenceChangeListener(this);
        SettingsUtils.updatePreferenceSummary(mAccountSignature, accountSignature,
                R.string.preferences_signature_summary_not_set);

        mCheckFrequency = (ListPreference) findPreference(PREFERENCE_FREQUENCY);
        mCheckFrequency.setEntries(info.syncIntervalStrings);
        mCheckFrequency.setEntryValues(info.syncIntervals);
        if (info.syncContacts || info.syncCalendar) {
            // This account allows syncing of contacts and/or calendar, so we will always have
            // separate preferences to enable or disable syncing of email, contacts, and calendar.
            // The "sync frequency" preference really just needs to control the frequency value
            // in our database.
            mCheckFrequency.setValue(String.valueOf(mAccount.getSyncInterval()));
        } else {
            // This account only syncs email (not contacts or calendar), which means that we will
            // hide the preference to turn syncing on and off. In this case, we want the sync
            // frequency preference to also control whether or not syncing is enabled at all. If
            // sync is turned off, we will display "sync never" regardless of what the numeric
            // value we have stored says.
            boolean synced = ContentResolver.getSyncAutomatically(androidAcct,
                    EmailContent.AUTHORITY);
            if (synced) {
                mCheckFrequency.setValue(String.valueOf(mAccount.getSyncInterval()));
            } else {
                mCheckFrequency.setValue(String.valueOf(Account.CHECK_INTERVAL_NEVER));
            }
        }
        mCheckFrequency.setSummary(mCheckFrequency.getEntry());
        mCheckFrequency.setOnPreferenceChangeListener(this);

        findPreference(PREFERENCE_QUICK_RESPONSES).setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        mAccountDirty = true;
                        mCallback.onEditQuickResponses(mUiAccount);
                        return true;
                    }
                });

        // Add check window preference
        PreferenceCategory dataUsageCategory =
                (PreferenceCategory) findPreference(PREFERENCE_CATEGORY_DATA_USAGE);

        final Policy policy;
        if (mAccount.mPolicyKey != 0) {
            // Make sure we have most recent data from account
            mAccount.refresh(mContext);
            policy = Policy.restorePolicyWithId(mContext, mAccount.mPolicyKey);
            if (policy == null) {
                // The account has been deleted?  Crazy, but not impossible
                return;
            }
        } else {
            policy = null;
        }

        mSyncWindow = null;
        if (info.offerLookback) {
            mSyncWindow = new ListPreference(mContext);
            mSyncWindow.setTitle(R.string.account_setup_options_mail_window_label);
            mSyncWindow.setValue(String.valueOf(mAccount.getSyncLookback()));
            final int maxLookback;
            if (policy != null) {
                maxLookback = policy.mMaxEmailLookback;
            } else {
                maxLookback = 0;
            }

            MailboxSettings.setupLookbackPreferenceOptions(mContext, mSyncWindow, maxLookback,
                    false);

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
            Preference trashPreference = findPreference(PREFERENCE_SYSTEM_FOLDERS_TRASH);
            Intent i = new Intent(mContext, FolderPickerActivity.class);
            Uri uri = EmailContent.CONTENT_URI.buildUpon().appendQueryParameter(
                    "account", Long.toString(mAccount.mId)).build();
            i.setData(uri);
            i.putExtra(FolderPickerActivity.MAILBOX_TYPE_EXTRA, Mailbox.TYPE_TRASH);
            trashPreference.setIntent(i);

            Preference sentPreference = findPreference(PREFERENCE_SYSTEM_FOLDERS_SENT);
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

        mInboxNotify = (CheckBoxPreference) findPreference(
                FolderPreferences.PreferenceKeys.NOTIFICATIONS_ENABLED);
        mInboxNotify.setOnPreferenceChangeListener(this);

        mInboxRingtone = findPreference(FolderPreferences.PreferenceKeys.NOTIFICATION_RINGTONE);
        mInboxRingtone.setOnPreferenceChangeListener(this);
        mInboxRingtone.setOnPreferenceClickListener(new OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(final Preference preference) {
                showRingtonePicker();

                return true;
            }
        });

        mNotificationsCategory =
                (PreferenceCategory) findPreference(PREFERENCE_CATEGORY_NOTIFICATIONS);

        // Set the vibrator value, or hide it on devices w/o a vibrator
        mInboxVibrate = (CheckBoxPreference) findPreference(
                FolderPreferences.PreferenceKeys.NOTIFICATION_VIBRATE);
        Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
        if (vibrator.hasVibrator()) {
            // Checked state will be set when we obtain it in #loadSettingsOffMainThread()

            // When the value is changed, update the setting.
            mInboxVibrate.setOnPreferenceChangeListener(this);
        } else {
            // No vibrator present. Remove the preference altogether.
            mNotificationsCategory.removePreference(mInboxVibrate);
        }

        final Preference retryAccount = findPreference(PREFERENCE_POLICIES_RETRY_ACCOUNT);
        final PreferenceCategory policiesCategory = (PreferenceCategory) findPreference(
                PREFERENCE_CATEGORY_POLICIES);
        // TODO: This code for showing policies isn't working. For KLP, just don't even bother
        // showing this data; we'll fix this later.
/*
        if (policy != null) {
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
*/
        // Remove the category completely if there are no policies
        getPreferenceScreen().removePreference(policiesCategory);

        //}

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
        if (info.usesSmtp && mAccount.mHostAuthSend != null) {
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
            if (info.usesSmtp) {
                // We really ought to have an outgoing host auth but we don't.
                // There's nothing we can do at this point, so just log the error.
                LogUtils.e(Logging.LOG_TAG, "Account %d has a bad outbound hostauth", mAccount.mId);
            }
            PreferenceCategory serverCategory = (PreferenceCategory) findPreference(
                    PREFERENCE_CATEGORY_SERVER);
            serverCategory.removePreference(prefOutgoing);
        }

        mSyncContacts = (CheckBoxPreference) findPreference(PREFERENCE_SYNC_CONTACTS);
        mSyncCalendar = (CheckBoxPreference) findPreference(PREFERENCE_SYNC_CALENDAR);
        mSyncEmail = (CheckBoxPreference) findPreference(PREFERENCE_SYNC_EMAIL);
        if (info.syncContacts || info.syncCalendar) {
            if (info.syncContacts) {
                mSyncContacts.setChecked(ContentResolver
                        .getSyncAutomatically(androidAcct, ContactsContract.AUTHORITY));
                mSyncContacts.setOnPreferenceChangeListener(this);
            } else {
                mSyncContacts.setChecked(false);
                mSyncContacts.setEnabled(false);
            }
            if (info.syncCalendar) {
                mSyncCalendar.setChecked(ContentResolver
                        .getSyncAutomatically(androidAcct, CalendarContract.AUTHORITY));
                mSyncCalendar.setOnPreferenceChangeListener(this);
            } else {
                mSyncCalendar.setChecked(false);
                mSyncCalendar.setEnabled(false);
            }
            mSyncEmail.setChecked(ContentResolver
                    .getSyncAutomatically(androidAcct, EmailContent.AUTHORITY));
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
        int newFlags = mAccount.getFlags() & ~(Account.FLAGS_BACKGROUND_ATTACHMENTS);

        newFlags |= mAccountBackgroundAttachments.isChecked() ?
                Account.FLAGS_BACKGROUND_ATTACHMENTS : 0;

        final EmailServiceInfo info =
                EmailServiceUtils.getServiceInfo(mContext, mAccount.getProtocol(mContext));
        final android.accounts.Account androidAcct = new android.accounts.Account(
                mAccount.mEmailAddress, info.accountType);

        // If the display name has been cleared, we'll reset it to the default value (email addr)
        mAccount.setDisplayName(mAccountDescription.getText().trim());
        // The sender name must never be empty (this is enforced by the preference editor)
        mAccount.setSenderName(mAccountName.getText().trim());
        mAccount.setSignature(mAccountSignature.getText());
        int freq = Integer.parseInt(mCheckFrequency.getValue());
        if (info.syncContacts || info.syncCalendar) {
            // This account allows syncing of contacts and/or calendar, so we will always have
            // separate preferences to enable or disable syncing of email, contacts, and calendar.
            // The "sync frequency" preference really just needs to control the frequency value
            // in our database.
            mAccount.setSyncInterval(Integer.parseInt(mCheckFrequency.getValue()));
        } else {
            // This account only syncs email (not contacts or calendar), which means that we will
            // hide the preference to turn syncing on and off. In this case, we want the sync
            // frequency preference to also control whether or not syncing is enabled at all. If
            // sync is turned off, we will display "sync never" regardless of what the numeric
            // value we have stored says.
            if (freq == Account.CHECK_INTERVAL_NEVER) {
                // Disable syncing from the account manager. Leave the current sync frequency
                // in the database.
                ContentResolver.setSyncAutomatically(androidAcct, EmailContent.AUTHORITY, false);
            } else {
                // Enable syncing from the account manager.
                ContentResolver.setSyncAutomatically(androidAcct, EmailContent.AUTHORITY, true);
                mAccount.setSyncInterval(Integer.parseInt(mCheckFrequency.getValue()));
            }
        }
        if (mSyncWindow != null) {
            mAccount.setSyncLookback(Integer.parseInt(mSyncWindow.getValue()));
        }
        mAccount.setFlags(newFlags);

        if (info.syncContacts || info.syncCalendar) {
            ContentResolver.setSyncAutomatically(androidAcct, ContactsContract.AUTHORITY,
                    mSyncContacts.isChecked());
            ContentResolver.setSyncAutomatically(androidAcct, CalendarContract.AUTHORITY,
                    mSyncCalendar.isChecked());
            ContentResolver.setSyncAutomatically(androidAcct, EmailContent.AUTHORITY,
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

    /**
     * Shows the system ringtone picker.
     */
    private void showRingtonePicker() {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        final String ringtoneUri = mInboxFolderPreferences.getNotificationRingtoneUri();
        if (!TextUtils.isEmpty(ringtoneUri)) {
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(ringtoneUri));
        }
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                Settings.System.DEFAULT_NOTIFICATION_URI);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
        startActivityForResult(intent, RINGTONE_REQUEST_CODE);
    }
}
