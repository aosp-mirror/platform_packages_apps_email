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
import android.app.LoaderManager;
import android.content.*;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
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
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.mail.preferences.AccountPreferences;
import com.android.mail.preferences.FolderPreferences;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.ui.MailAsyncTaskLoader;
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
    @SuppressWarnings("unused") // temporarily unused pending policy UI
    private static final String PREFERENCE_POLICIES_ENFORCED = "policies_enforced";
    @SuppressWarnings("unused") // temporarily unused pending policy UI
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

    private static final String SAVESTATE_SYNC_INTERVALS = "savestate_sync_intervals";
    private static final String SAVESTATE_SYNC_INTERVAL_STRINGS = "savestate_sync_interval_strings";

    // Request code to start different activities.
    private static final int RINGTONE_REQUEST_CODE = 0;

    private EditTextPreference mAccountDescription;
    private EditTextPreference mAccountName;
    private EditTextPreference mAccountSignature;
    private ListPreference mCheckFrequency;
    private ListPreference mSyncWindow;
    private CheckBoxPreference mAccountBackgroundAttachments;
    private CheckBoxPreference mInboxVibrate;
    private Preference mInboxRingtone;
    private CheckBoxPreference mSyncContacts;
    private CheckBoxPreference mSyncCalendar;
    private CheckBoxPreference mSyncEmail;

    private Context mContext;

    private Account mAccount;
    private com.android.mail.providers.Account mUiAccount;
    private Callback mCallback = EmptyCallback.INSTANCE;
    private boolean mSaveOnExit;

    private Ringtone mRingtone;

    /**
     * This may be null if the account exists but the inbox has not yet been created in the database
     * (waiting for initial sync)
     */
    private FolderPreferences mInboxFolderPreferences;

    // The ID of the account being edited
    private long mAccountId;

    /**
     * Callback interface that owning activities must provide
     */
    public interface Callback {
        public void onSettingsChanged(long accountId, String preference, Object value);
        public void onEditQuickResponses(com.android.mail.providers.Account account);
        public void onIncomingSettings(Account account);
        public void onOutgoingSettings(Account account);
        public void abandonEdit();
    }

    private static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onSettingsChanged(long accountId, String preference, Object value) {}
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
        super.onCreate(savedInstanceState);

        setHasOptionsMenu(true);

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.account_settings_preferences);

        // Start loading the account data, if provided in the arguments
        // If not, activity must call startLoadingAccount() directly
        Bundle b = getArguments();
        if (b != null) {
            mAccountId = b.getLong(BUNDLE_KEY_ACCOUNT_ID, -1);
        }
        if (savedInstanceState != null) {
            // We won't know what the correct set of sync interval values and strings are until
            // our loader completes. The problem is, that if the sync frequency chooser is
            // displayed when the screen rotates, it reinitializes it to the defaults, and doesn't
            // correct it after the loader finishes again. See b/13624066
            // To work around this, we'll save the current set of sync interval values and strings,
            // in onSavedInstanceState, and restore them here.
            final CharSequence [] syncIntervalStrings =
                    savedInstanceState.getCharSequenceArray(SAVESTATE_SYNC_INTERVAL_STRINGS);
            final CharSequence [] syncIntervals =
                    savedInstanceState.getCharSequenceArray(SAVESTATE_SYNC_INTERVALS);
            mCheckFrequency = (ListPreference) findPreference(PREFERENCE_FREQUENCY);
            mCheckFrequency.setEntries(syncIntervalStrings);
            mCheckFrequency.setEntryValues(syncIntervals);
        }
    }

    public void onSaveInstanceState(Bundle outstate) {
        super.onSaveInstanceState(outstate);
        outstate.putCharSequenceArray(SAVESTATE_SYNC_INTERVAL_STRINGS, mCheckFrequency.getEntries());
        outstate.putCharSequenceArray(SAVESTATE_SYNC_INTERVALS, mCheckFrequency.getEntryValues());
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        final Bundle args = new Bundle(1);
        args.putLong(AccountLoaderCallbacks.ARG_ACCOUNT_ID, mAccountId);
        getLoaderManager().initLoader(0, args, new AccountLoaderCallbacks(getActivity()));
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mSaveOnExit) {
            saveSettings();
        }
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
                summary = mUiAccount.getEmailAddress();
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
     * Async task to load account in order to view/edit it
     */
    private static class AccountLoader extends MailAsyncTaskLoader<Map<String, Object>> {
        public static final String RESULT_KEY_ACCOUNT = "account";
        private static final String RESULT_KEY_UIACCOUNT_CURSOR = "uiAccountCursor";
        public static final String RESULT_KEY_UIACCOUNT = "uiAccount";
        public static final String RESULT_KEY_INBOX = "inbox";

        private final ForceLoadContentObserver mObserver;
        private final long mAccountId;

        private AccountLoader(Context context, long accountId) {
            super(context);
            mObserver = new ForceLoadContentObserver();
            mAccountId = accountId;
        }

        @Override
        public Map<String, Object> loadInBackground() {
            final Map<String, Object> map = new HashMap<String, Object>();

            Account account = Account.restoreAccountWithId(getContext(), mAccountId, mObserver);
            if (account == null) {
                return map;
            }

            map.put(RESULT_KEY_ACCOUNT, account);

            // We don't monitor these for changes, but they probably won't change in any meaningful
            // way
            account.getOrCreateHostAuthRecv(getContext());
            account.getOrCreateHostAuthSend(getContext());

            if (account.mHostAuthRecv == null) {
                return map;
            }

            account.mPolicy =
                    Policy.restorePolicyWithId(getContext(), account.mPolicyKey, mObserver);

            final Cursor uiAccountCursor = getContext().getContentResolver().query(
                    EmailProvider.uiUri("uiaccount", mAccountId), UIProvider.ACCOUNTS_PROJECTION,
                    null, null, null);

            if (uiAccountCursor != null) {
                map.put(RESULT_KEY_UIACCOUNT_CURSOR, uiAccountCursor);
                uiAccountCursor.registerContentObserver(mObserver);
            } else {
                return map;
            }

            if (!uiAccountCursor.moveToFirst()) {
                return map;
            }

            final com.android.mail.providers.Account uiAccount =
                    new com.android.mail.providers.Account(uiAccountCursor);

            map.put(RESULT_KEY_UIACCOUNT, uiAccount);

            final Cursor folderCursor = getContext().getContentResolver().query(
                    uiAccount.settings.defaultInbox, UIProvider.FOLDERS_PROJECTION, null, null,
                    null);

            final Folder inbox;
            try {
                if (folderCursor != null && folderCursor.moveToFirst()) {
                    inbox = new Folder(folderCursor);
                } else {
                    return map;
                }
            } finally {
                if (folderCursor != null) {
                    folderCursor.close();
                }
            }

            map.put(RESULT_KEY_INBOX, inbox);
            return map;
        }

        @Override
        protected void onDiscardResult(Map<String, Object> result) {
            final Account account = (Account) result.get(RESULT_KEY_ACCOUNT);
            if (account != null) {
                if (account.mPolicy != null) {
                    account.mPolicy.close(getContext());
                }
                account.close(getContext());
            }
            final Cursor uiAccountCursor = (Cursor) result.get(RESULT_KEY_UIACCOUNT_CURSOR);
            if (uiAccountCursor != null) {
                uiAccountCursor.close();
            }
        }
    }

    private class AccountLoaderCallbacks
            implements LoaderManager.LoaderCallbacks<Map<String, Object>> {
        public static final String ARG_ACCOUNT_ID = "accountId";
        private final Context mContext;

        private AccountLoaderCallbacks(Context context) {
            mContext = context;
        }

        @Override
        public void onLoadFinished(Loader<Map<String, Object>> loader, Map<String, Object> data) {
            if (data == null) {
                mSaveOnExit = false;
                mCallback.abandonEdit();
                return;
            }

            mUiAccount = (com.android.mail.providers.Account)
                    data.get(AccountLoader.RESULT_KEY_UIACCOUNT);
            mAccount = (Account) data.get(AccountLoader.RESULT_KEY_ACCOUNT);

            if (mAccount != null && (mAccount.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
                final Intent i = AccountSecurity.actionUpdateSecurityIntent(mContext,
                        mAccount.getId(), true);
                mContext.startActivity(i);
                mSaveOnExit = false;
                mCallback.abandonEdit();
                return;
            }

            final Folder inbox = (Folder) data.get(AccountLoader.RESULT_KEY_INBOX);

            if (mUiAccount == null || mAccount == null) {
                mSaveOnExit = false;
                mCallback.abandonEdit();
                return;
            }

            if (inbox == null) {
                mInboxFolderPreferences = null;
            } else {
                mInboxFolderPreferences =
                        new FolderPreferences(mContext, mUiAccount.getEmailAddress(), inbox, true);
            }
            if (!mSaveOnExit) {
                loadSettings();
            }
        }

        @Override
        public Loader<Map<String, Object>> onCreateLoader(int id, Bundle args) {
            return new AccountLoader(mContext, args.getLong(ARG_ACCOUNT_ID));
        }

        @Override
        public void onLoaderReset(Loader<Map<String, Object>> loader) {}
    }

    /**
     * From a Policy, create and return an ArrayList of Strings that describe (simply) those
     * policies that are supported by the OS.  At the moment, the strings are simple (e.g.
     * "password required"); we should probably add more information (# characters, etc.), though
     */
    @SuppressWarnings("unused") // temporarily unused pending policy UI
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

    @SuppressWarnings("unused") // temporarily unused pending policy UI
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
     * Load account data into preference UI. This must be called on the main thread.
     */
    private void loadSettings() {
        // Once loaded the data is ready to be saved, as well
        mSaveOnExit = false;

        final AccountPreferences accountPreferences =
                new AccountPreferences(mContext, mUiAccount.getEmailAddress());
        if (mInboxFolderPreferences != null) {
            NotificationUtils.moveNotificationSetting(
                    accountPreferences, mInboxFolderPreferences);
        }

        final String protocol = mAccount.getProtocol(mContext);
        final EmailServiceInfo info = EmailServiceUtils.getServiceInfo(mContext, protocol);
        if (info == null) {
            LogUtils.e(Logging.LOG_TAG,
                    "Could not find service info for account %d with protocol %s", mAccount.mId,
                    protocol);
            getActivity().onBackPressed();
            // TODO: put up some sort of dialog/toast here to tell the user something went wrong
            return;
        }
        final android.accounts.Account androidAcct = mUiAccount.getAccountManagerAccount();

        mAccountDescription = (EditTextPreference) findPreference(PREFERENCE_DESCRIPTION);
        mAccountDescription.setSummary(mAccount.getDisplayName());
        mAccountDescription.setText(mAccount.getDisplayName());
        mAccountDescription.setOnPreferenceChangeListener(this);

        mAccountName = (EditTextPreference) findPreference(PREFERENCE_NAME);
        String senderName = mUiAccount.getSenderName();
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
                        mCallback.onEditQuickResponses(mUiAccount);
                        return true;
                    }
                });

        // Add check window preference
        PreferenceCategory dataUsageCategory =
                (PreferenceCategory) findPreference(PREFERENCE_CATEGORY_DATA_USAGE);

        if (info.offerLookback) {
            if (mSyncWindow == null) {
                mSyncWindow = new ListPreference(mContext);
                dataUsageCategory.addPreference(mSyncWindow);
            }
            mSyncWindow.setTitle(R.string.account_setup_options_mail_window_label);
            mSyncWindow.setValue(String.valueOf(mAccount.getSyncLookback()));
            final int maxLookback;
            if (mAccount.mPolicy != null) {
                maxLookback = mAccount.mPolicy.mMaxEmailLookback;
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
        }

        PreferenceCategory folderPrefs =
                (PreferenceCategory) findPreference(PREFERENCE_SYSTEM_FOLDERS);
        if (folderPrefs != null) {
            if (info.requiresSetup) {
                Preference trashPreference = findPreference(PREFERENCE_SYSTEM_FOLDERS_TRASH);
                Intent i = new Intent(mContext, FolderPickerActivity.class);
                Uri uri = EmailContent.CONTENT_URI.buildUpon().appendQueryParameter(
                        "account", Long.toString(mAccountId)).build();
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
        }

        mAccountBackgroundAttachments = (CheckBoxPreference)
                findPreference(PREFERENCE_BACKGROUND_ATTACHMENTS);
        if (mAccountBackgroundAttachments != null) {
            if (!info.offerAttachmentPreload) {
                dataUsageCategory.removePreference(mAccountBackgroundAttachments);
                mAccountBackgroundAttachments = null;
            } else {
                mAccountBackgroundAttachments.setChecked(
                        0 != (mAccount.getFlags() & Account.FLAGS_BACKGROUND_ATTACHMENTS));
                mAccountBackgroundAttachments.setOnPreferenceChangeListener(this);
            }
        }

        final PreferenceCategory notificationsCategory =
                (PreferenceCategory) findPreference(PREFERENCE_CATEGORY_NOTIFICATIONS);

        if (mInboxFolderPreferences != null) {
            final CheckBoxPreference inboxNotify = (CheckBoxPreference) findPreference(
                FolderPreferences.PreferenceKeys.NOTIFICATIONS_ENABLED);
            inboxNotify.setChecked(mInboxFolderPreferences.areNotificationsEnabled());
            inboxNotify.setOnPreferenceChangeListener(this);

            mInboxRingtone = findPreference(FolderPreferences.PreferenceKeys.NOTIFICATION_RINGTONE);
            final String ringtoneUri = mInboxFolderPreferences.getNotificationRingtoneUri();
            if (!TextUtils.isEmpty(ringtoneUri)) {
                mRingtone = RingtoneManager.getRingtone(getActivity(), Uri.parse(ringtoneUri));
            }
            setRingtoneSummary();
            mInboxRingtone.setOnPreferenceChangeListener(this);
            mInboxRingtone.setOnPreferenceClickListener(new OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(final Preference preference) {
                    showRingtonePicker();

                    return true;
                }
            });

            notificationsCategory.setEnabled(true);

            // Set the vibrator value, or hide it on devices w/o a vibrator
            mInboxVibrate = (CheckBoxPreference) findPreference(
                    FolderPreferences.PreferenceKeys.NOTIFICATION_VIBRATE);
            if (mInboxVibrate != null) {
                mInboxVibrate.setChecked(
                        mInboxFolderPreferences.isNotificationVibrateEnabled());
                Vibrator vibrator = (Vibrator) mContext.getSystemService(Context.VIBRATOR_SERVICE);
                if (vibrator.hasVibrator()) {
                    // When the value is changed, update the setting.
                    mInboxVibrate.setOnPreferenceChangeListener(this);
                } else {
                    // No vibrator present. Remove the preference altogether.
                    notificationsCategory.removePreference(mInboxVibrate);
                    mInboxVibrate = null;
                }
            }
        } else {
            notificationsCategory.setEnabled(false);
        }

        final Preference retryAccount = findPreference(PREFERENCE_POLICIES_RETRY_ACCOUNT);
        final PreferenceCategory policiesCategory = (PreferenceCategory) findPreference(
                PREFERENCE_CATEGORY_POLICIES);
        if (policiesCategory != null) {
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
        }

        if (retryAccount != null) {
            retryAccount.setOnPreferenceClickListener(
                    new Preference.OnPreferenceClickListener() {
                        @Override
                        public boolean onPreferenceClick(Preference preference) {
                            // Release the account
                            SecurityPolicy.setAccountHoldFlag(mContext, mAccount, false);
                            // Remove the preference
                            if (policiesCategory != null) {
                                policiesCategory.removePreference(retryAccount);
                            }
                            return true;
                        }
                    });
        }
        findPreference(PREFERENCE_INCOMING).setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    @Override
                    public boolean onPreferenceClick(Preference preference) {
                        mCallback.onIncomingSettings(mAccount);
                        return true;
                    }
                });

        // Hide the outgoing account setup link if it's not activated
        Preference prefOutgoing = findPreference(PREFERENCE_OUTGOING);
        if (prefOutgoing != null) {
            if (info.usesSmtp && mAccount.mHostAuthSend != null) {
                prefOutgoing.setOnPreferenceClickListener(
                        new Preference.OnPreferenceClickListener() {
                            @Override
                            public boolean onPreferenceClick(Preference preference) {
                                mCallback.onOutgoingSettings(mAccount);
                                return true;
                            }
                        });
            } else {
                if (info.usesSmtp) {
                    // We really ought to have an outgoing host auth but we don't.
                    // There's nothing we can do at this point, so just log the error.
                    LogUtils.e(Logging.LOG_TAG, "Account %d has a bad outbound hostauth",
                            mAccountId);
                }
                PreferenceCategory serverCategory = (PreferenceCategory) findPreference(
                        PREFERENCE_CATEGORY_SERVER);
                serverCategory.removePreference(prefOutgoing);
            }
        }

        mSyncContacts = (CheckBoxPreference) findPreference(PREFERENCE_SYNC_CONTACTS);
        mSyncCalendar = (CheckBoxPreference) findPreference(PREFERENCE_SYNC_CALENDAR);
        mSyncEmail = (CheckBoxPreference) findPreference(PREFERENCE_SYNC_EMAIL);
        if (mSyncContacts != null && mSyncCalendar != null && mSyncEmail != null) {
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
                mSyncContacts = null;
                dataUsageCategory.removePreference(mSyncCalendar);
                mSyncCalendar = null;
                dataUsageCategory.removePreference(mSyncEmail);
                mSyncEmail = null;
            }
        }
    }

    /**
     * Called any time a preference is changed.
     */
    private void preferenceChanged(String preference, Object value) {
        mCallback.onSettingsChanged(mAccountId, preference, value);
        mSaveOnExit = true;
    }

    /*
     * Note: This writes the settings on the UI thread.  This has to be done so the settings are
     * committed before we might be killed.
     */
    private void saveSettings() {
        // Turn off all controlled flags - will turn them back on while checking UI elements
        int newFlags = mAccount.getFlags() & ~(Account.FLAGS_BACKGROUND_ATTACHMENTS);

        if (mAccountBackgroundAttachments != null) {
            newFlags |= mAccountBackgroundAttachments.isChecked() ?
                    Account.FLAGS_BACKGROUND_ATTACHMENTS : 0;
        }

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
