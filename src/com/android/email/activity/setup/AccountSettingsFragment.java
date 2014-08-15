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
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
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
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceScreen;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.support.annotation.NonNull;
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
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.mail.preferences.AccountPreferences;
import com.android.mail.preferences.FolderPreferences;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.ui.MailAsyncTaskLoader;
import com.android.mail.ui.settings.MailAccountPrefsFragment;
import com.android.mail.ui.settings.SettingsUtils;
import com.android.mail.utils.ContentProviderTask.UpdateTask;
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
public class AccountSettingsFragment extends MailAccountPrefsFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String ARG_ACCOUNT_ID = "account_id";

    public static final String PREFERENCE_DESCRIPTION = "account_description";
    private static final String PREFERENCE_NAME = "account_name";
    private static final String PREFERENCE_SIGNATURE = "account_signature";
    private static final String PREFERENCE_QUICK_RESPONSES = "account_quick_responses";
    private static final String PREFERENCE_FREQUENCY = "account_check_frequency";
    private static final String PREFERENCE_SYNC_WINDOW = "account_sync_window";
    private static final String PREFERENCE_SYNC_SETTINGS = "account_sync_settings";
    private static final String PREFERENCE_SYNC_EMAIL = "account_sync_email";
    private static final String PREFERENCE_SYNC_CONTACTS = "account_sync_contacts";
    private static final String PREFERENCE_SYNC_CALENDAR = "account_sync_calendar";
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
    private Preference mSyncSettings;
    private CheckBoxPreference mInboxVibrate;
    private Preference mInboxRingtone;

    private Context mContext;

    private Account mAccount;
    private com.android.mail.providers.Account mUiAccount;
    private EmailServiceInfo mServiceInfo;
    private Folder mInboxFolder;

    private Ringtone mRingtone;

    /**
     * This may be null if the account exists but the inbox has not yet been created in the database
     * (waiting for initial sync)
     */
    private FolderPreferences mInboxFolderPreferences;

    // The email of the account being edited
    private String mAccountEmail;

    /**
     * If launching with an email address, use this method to build the arguments.
     */
    public static Bundle buildArguments(final String email) {
        final Bundle b = new Bundle(1);
        b.putString(ARG_ACCOUNT_EMAIL, email);
        return b;
    }

    /**
     * If launching with an account ID, use this method to build the arguments.
     */
    public static Bundle buildArguments(final long accountId) {
        final Bundle b = new Bundle(1);
        b.putLong(ARG_ACCOUNT_ID, accountId);
        return b;
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

        if (!getResources().getBoolean(R.bool.quickresponse_supported)) {
            final Preference quickResponsePref = findPreference(PREFERENCE_QUICK_RESPONSES);
            if (quickResponsePref != null) {
                getPreferenceScreen().removePreference(quickResponsePref);
            }
        }

        // Start loading the account data, if provided in the arguments
        // If not, activity must call startLoadingAccount() directly
        Bundle b = getArguments();
        if (b != null) {
            mAccountEmail = b.getString(ARG_ACCOUNT_EMAIL);
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
            if (mCheckFrequency != null) {
                mCheckFrequency.setEntries(syncIntervalStrings);
                mCheckFrequency.setEntryValues(syncIntervals);
            }
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outstate) {
        super.onSaveInstanceState(outstate);
        if (mCheckFrequency != null) {
            outstate.putCharSequenceArray(SAVESTATE_SYNC_INTERVAL_STRINGS,
                    mCheckFrequency.getEntries());
            outstate.putCharSequenceArray(SAVESTATE_SYNC_INTERVALS,
                    mCheckFrequency.getEntryValues());
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        final Bundle args = new Bundle(1);
        if (!TextUtils.isEmpty(mAccountEmail)) {
            args.putString(AccountLoaderCallbacks.ARG_ACCOUNT_EMAIL, mAccountEmail);
        } else {
            args.putLong(AccountLoaderCallbacks.ARG_ACCOUNT_ID,
                    getArguments().getLong(ARG_ACCOUNT_ID, -1));
        }
        getLoaderManager().initLoader(0, args, new AccountLoaderCallbacks(getActivity()));
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

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen,
            @NonNull Preference preference) {
        final String key = preference.getKey();
        if (key.equals(PREFERENCE_SYNC_SETTINGS)) {
            startActivity(MailboxSettings.getIntent(getActivity(), mUiAccount.fullFolderListUri,
                    mInboxFolder));
            return true;
        } else {
            return super.onPreferenceTreeClick(preferenceScreen, preference);
        }
    }

    /**
     * Listen to all preference changes in this class.
     * @param preference The changed Preference
     * @param newValue The new value of the Preference
     * @return True to update the state of the Preference with the new value
     */
    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        // Can't use a switch here. Falling back to a giant conditional.
        final String key = preference.getKey();
        final ContentValues cv = new ContentValues(1);
        if (key.equals(PREFERENCE_DESCRIPTION)){
            String summary = newValue.toString().trim();
            if (TextUtils.isEmpty(summary)) {
                summary = mUiAccount.getEmailAddress();
            }
            mAccountDescription.setSummary(summary);
            mAccountDescription.setText(summary);
            cv.put(AccountColumns.DISPLAY_NAME, summary);
        } else if (key.equals(PREFERENCE_NAME)) {
            final String summary = newValue.toString().trim();
            if (!TextUtils.isEmpty(summary)) {
                mAccountName.setSummary(summary);
                mAccountName.setText(summary);
                cv.put(AccountColumns.SENDER_NAME, summary);
            }
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
            cv.put(AccountColumns.SIGNATURE, signature);
        } else if (key.equals(PREFERENCE_FREQUENCY)) {
            final String summary = newValue.toString();
            final int index = mCheckFrequency.findIndexOfValue(summary);
            mCheckFrequency.setSummary(mCheckFrequency.getEntries()[index]);
            mCheckFrequency.setValue(summary);
            if (mServiceInfo.syncContacts || mServiceInfo.syncCalendar) {
                // This account allows syncing of contacts and/or calendar, so we will always have
                // separate preferences to enable or disable syncing of email, contacts, and
                // calendar.
                // The "sync frequency" preference really just needs to control the frequency value
                // in our database.
                cv.put(AccountColumns.SYNC_INTERVAL, Integer.parseInt(summary));
            } else {
                // This account only syncs email (not contacts or calendar), which means that we
                // will hide the preference to turn syncing on and off. In this case, we want the
                // sync frequency preference to also control whether or not syncing is enabled at
                // all. If sync is turned off, we will display "sync never" regardless of what the
                // numeric value we have stored says.
                final android.accounts.Account androidAcct = new android.accounts.Account(
                        mAccount.mEmailAddress, mServiceInfo.accountType);
                if (Integer.parseInt(summary) == Account.CHECK_INTERVAL_NEVER) {
                    // Disable syncing from the account manager. Leave the current sync frequency
                    // in the database.
                    ContentResolver.setSyncAutomatically(androidAcct, EmailContent.AUTHORITY,
                            false);
                } else {
                    // Enable syncing from the account manager.
                    ContentResolver.setSyncAutomatically(androidAcct, EmailContent.AUTHORITY,
                            true);
                    cv.put(AccountColumns.SYNC_INTERVAL, Integer.parseInt(summary));
                }
            }
        } else if (key.equals(PREFERENCE_SYNC_WINDOW)) {
            final String summary = newValue.toString();
            int index = mSyncWindow.findIndexOfValue(summary);
            mSyncWindow.setSummary(mSyncWindow.getEntries()[index]);
            mSyncWindow.setValue(summary);
            cv.put(AccountColumns.SYNC_LOOKBACK, Integer.parseInt(summary));
        } else if (key.equals(PREFERENCE_SYNC_EMAIL)) {
            final android.accounts.Account androidAcct = new android.accounts.Account(
                    mAccount.mEmailAddress, mServiceInfo.accountType);
            ContentResolver.setSyncAutomatically(androidAcct, EmailContent.AUTHORITY,
                    (Boolean) newValue);
        } else if (key.equals(PREFERENCE_SYNC_CONTACTS)) {
            final android.accounts.Account androidAcct = new android.accounts.Account(
                    mAccount.mEmailAddress, mServiceInfo.accountType);
            ContentResolver.setSyncAutomatically(androidAcct, ContactsContract.AUTHORITY,
                    (Boolean) newValue);
        } else if (key.equals(PREFERENCE_SYNC_CALENDAR)) {
            final android.accounts.Account androidAcct = new android.accounts.Account(
                    mAccount.mEmailAddress, mServiceInfo.accountType);
            ContentResolver.setSyncAutomatically(androidAcct, CalendarContract.AUTHORITY,
                    (Boolean) newValue);
        } else if (key.equals(PREFERENCE_BACKGROUND_ATTACHMENTS)) {
            int newFlags = mAccount.getFlags() & ~(Account.FLAGS_BACKGROUND_ATTACHMENTS);

            newFlags |= (Boolean) newValue ?
                    Account.FLAGS_BACKGROUND_ATTACHMENTS : 0;

            cv.put(AccountColumns.FLAGS, newFlags);
        } else if (FolderPreferences.PreferenceKeys.NOTIFICATIONS_ENABLED.equals(key)) {
            mInboxFolderPreferences.setNotificationsEnabled((Boolean) newValue);
            return true;
        } else if (FolderPreferences.PreferenceKeys.NOTIFICATION_VIBRATE.equals(key)) {
            final boolean vibrateSetting = (Boolean) newValue;
            mInboxVibrate.setChecked(vibrateSetting);
            mInboxFolderPreferences.setNotificationVibrateEnabled(vibrateSetting);
            return true;
        } else if (FolderPreferences.PreferenceKeys.NOTIFICATION_RINGTONE.equals(key)) {
            return true;
        } else {
            // Default behavior, just indicate that the preferences were written
            LogUtils.d(LogUtils.TAG, "Unknown preference key %s", key);
            return true;
        }
        if (cv.size() > 0) {
            new UpdateTask().run(mContext.getContentResolver(), mAccount.getUri(), cv, null, null);
            MailActivityEmail.setServicesEnabledAsync(mContext);
        }
        return false;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.settings_fragment_menu, menu);
    }

    /**
     * Async task loader to load account in order to view/edit it
     */
    private static class AccountLoader extends MailAsyncTaskLoader<Map<String, Object>> {
        public static final String RESULT_KEY_ACCOUNT = "account";
        private static final String RESULT_KEY_UIACCOUNT_CURSOR = "uiAccountCursor";
        public static final String RESULT_KEY_UIACCOUNT = "uiAccount";
        public static final String RESULT_KEY_INBOX = "inbox";

        private final ForceLoadContentObserver mObserver;
        private final String mAccountEmail;
        private final long mAccountId;

        private AccountLoader(Context context, String accountEmail, long accountId) {
            super(context);
            mObserver = new ForceLoadContentObserver();
            mAccountEmail = accountEmail;
            mAccountId = accountId;
        }

        @Override
        public Map<String, Object> loadInBackground() {
            final Map<String, Object> map = new HashMap<>();

            final Account account;
            if (!TextUtils.isEmpty(mAccountEmail)) {
                account = Account.restoreAccountWithAddress(getContext(), mAccountEmail, mObserver);
            } else {
                account = Account.restoreAccountWithId(getContext(), mAccountId, mObserver);
            }
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
                    EmailProvider.uiUri("uiaccount", account.getId()),
                    UIProvider.ACCOUNTS_PROJECTION,
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
                    com.android.mail.providers.Account.builder().buildFrom(uiAccountCursor);

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
        public static final String ARG_ACCOUNT_EMAIL = "accountEmail";
        public static final String ARG_ACCOUNT_ID = "accountId";
        private final Context mContext;

        private AccountLoaderCallbacks(Context context) {
            mContext = context;
        }

        @Override
        public void onLoadFinished(Loader<Map<String, Object>> loader, Map<String, Object> data) {
            final Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            if (data == null) {
                activity.finish();
                return;
            }

            mUiAccount = (com.android.mail.providers.Account)
                    data.get(AccountLoader.RESULT_KEY_UIACCOUNT);
            mAccount = (Account) data.get(AccountLoader.RESULT_KEY_ACCOUNT);

            if (mAccount != null && (mAccount.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
                final Intent i = AccountSecurity.actionUpdateSecurityIntent(mContext,
                        mAccount.getId(), true);
                mContext.startActivity(i);
                activity.finish();
                return;
            }

            mInboxFolder = (Folder) data.get(AccountLoader.RESULT_KEY_INBOX);

            if (mUiAccount == null || mAccount == null) {
                activity.finish();
                return;
            }

            mServiceInfo =
                    EmailServiceUtils.getServiceInfo(mContext, mAccount.getProtocol(mContext));

            if (mInboxFolder == null) {
                mInboxFolderPreferences = null;
            } else {
                mInboxFolderPreferences = new FolderPreferences(mContext,
                        mUiAccount.getEmailAddress(), mInboxFolder, true);
            }
            loadSettings();
        }

        @Override
        public Loader<Map<String, Object>> onCreateLoader(int id, Bundle args) {
            return new AccountLoader(mContext, args.getString(ARG_ACCOUNT_EMAIL),
                    args.getLong(ARG_ACCOUNT_ID));
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
        ArrayList<String> policies = new ArrayList<>();
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
        final AccountPreferences accountPreferences =
                new AccountPreferences(mContext, mUiAccount.getEmailAddress());
        if (mInboxFolderPreferences != null) {
            NotificationUtils.moveNotificationSetting(
                    accountPreferences, mInboxFolderPreferences);
        }

        final String protocol = mAccount.getProtocol(mContext);
        if (mServiceInfo == null) {
            LogUtils.e(LogUtils.TAG,
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
        if (senderName == null) {
            senderName = "";
        }
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
        mCheckFrequency.setEntries(mServiceInfo.syncIntervalStrings);
        mCheckFrequency.setEntryValues(mServiceInfo.syncIntervals);
        if (mServiceInfo.syncContacts || mServiceInfo.syncCalendar) {
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

        final Preference quickResponsePref = findPreference(PREFERENCE_QUICK_RESPONSES);
        if (quickResponsePref != null) {
            quickResponsePref.setOnPreferenceClickListener(
                    new Preference.OnPreferenceClickListener() {
                        @Override
                        public boolean onPreferenceClick(Preference preference) {
                            onEditQuickResponses(mUiAccount);
                            return true;
                        }
                    });
        }

        // Add check window preference
        final PreferenceCategory dataUsageCategory =
                (PreferenceCategory) findPreference(PREFERENCE_CATEGORY_DATA_USAGE);

        if (mServiceInfo.offerLookback) {
            if (mSyncWindow == null) {
                mSyncWindow = new ListPreference(mContext);
                mSyncWindow.setKey(PREFERENCE_SYNC_WINDOW);
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
            mSyncWindow.setOnPreferenceChangeListener(this);

            if (mSyncSettings == null) {
                mSyncSettings = new Preference(mContext);
                mSyncSettings.setKey(PREFERENCE_SYNC_SETTINGS);
                dataUsageCategory.addPreference(mSyncSettings);
            }

            mSyncSettings.setTitle(R.string.folder_sync_settings_pref_title);
            mSyncSettings.setOrder(3);
        }

        final PreferenceCategory folderPrefs =
                (PreferenceCategory) findPreference(PREFERENCE_SYSTEM_FOLDERS);
        if (folderPrefs != null) {
            if (mServiceInfo.requiresSetup) {
                Preference trashPreference = findPreference(PREFERENCE_SYSTEM_FOLDERS_TRASH);
                Intent i = new Intent(mContext, FolderPickerActivity.class);
                Uri uri = EmailContent.CONTENT_URI.buildUpon().appendQueryParameter(
                        "account", Long.toString(mAccount.getId())).build();
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

        final CheckBoxPreference backgroundAttachments = (CheckBoxPreference)
                findPreference(PREFERENCE_BACKGROUND_ATTACHMENTS);
        if (backgroundAttachments != null) {
            if (!mServiceInfo.offerAttachmentPreload) {
                dataUsageCategory.removePreference(backgroundAttachments);
            } else {
                backgroundAttachments.setChecked(
                        0 != (mAccount.getFlags() & Account.FLAGS_BACKGROUND_ATTACHMENTS));
                backgroundAttachments.setOnPreferenceChangeListener(this);
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
                        onIncomingSettings(mAccount);
                        return true;
                    }
                });

        // Hide the outgoing account setup link if it's not activated
        final Preference prefOutgoing = findPreference(PREFERENCE_OUTGOING);
        if (prefOutgoing != null) {
            if (mServiceInfo.usesSmtp && mAccount.mHostAuthSend != null) {
                prefOutgoing.setOnPreferenceClickListener(
                        new Preference.OnPreferenceClickListener() {
                            @Override
                            public boolean onPreferenceClick(Preference preference) {
                                onOutgoingSettings(mAccount);
                                return true;
                            }
                        });
            } else {
                if (mServiceInfo.usesSmtp) {
                    // We really ought to have an outgoing host auth but we don't.
                    // There's nothing we can do at this point, so just log the error.
                    LogUtils.e(LogUtils.TAG, "Account %d has a bad outbound hostauth",
                            mAccount.getId());
                }
                PreferenceCategory serverCategory = (PreferenceCategory) findPreference(
                        PREFERENCE_CATEGORY_SERVER);
                serverCategory.removePreference(prefOutgoing);
            }
        }

        final CheckBoxPreference syncContacts =
                (CheckBoxPreference) findPreference(PREFERENCE_SYNC_CONTACTS);
        final CheckBoxPreference syncCalendar =
                (CheckBoxPreference) findPreference(PREFERENCE_SYNC_CALENDAR);
        final CheckBoxPreference syncEmail =
                (CheckBoxPreference) findPreference(PREFERENCE_SYNC_EMAIL);
        if (syncContacts != null && syncCalendar != null && syncEmail != null) {
            if (mServiceInfo.syncContacts || mServiceInfo.syncCalendar) {
                if (mServiceInfo.syncContacts) {
                    syncContacts.setChecked(ContentResolver
                            .getSyncAutomatically(androidAcct, ContactsContract.AUTHORITY));
                    syncContacts.setOnPreferenceChangeListener(this);
                } else {
                    syncContacts.setChecked(false);
                    syncContacts.setEnabled(false);
                }
                if (mServiceInfo.syncCalendar) {
                    syncCalendar.setChecked(ContentResolver
                            .getSyncAutomatically(androidAcct, CalendarContract.AUTHORITY));
                    syncCalendar.setOnPreferenceChangeListener(this);
                } else {
                    syncCalendar.setChecked(false);
                    syncCalendar.setEnabled(false);
                }
                syncEmail.setChecked(ContentResolver
                        .getSyncAutomatically(androidAcct, EmailContent.AUTHORITY));
                syncEmail.setOnPreferenceChangeListener(this);
            } else {
                dataUsageCategory.removePreference(syncContacts);
                dataUsageCategory.removePreference(syncCalendar);
                dataUsageCategory.removePreference(syncEmail);
            }
        }
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

    /**
     * Dispatch to edit quick responses.
     */
    public void onEditQuickResponses(com.android.mail.providers.Account account) {
        final Bundle args = AccountSettingsEditQuickResponsesFragment.createArgs(account);
        final PreferenceActivity activity = (PreferenceActivity) getActivity();
        activity.startPreferencePanel(AccountSettingsEditQuickResponsesFragment.class.getName(),
                args, R.string.account_settings_edit_quick_responses_label, null, null, 0);
    }

    /**
     * Dispatch to edit incoming settings.
     */
    public void onIncomingSettings(Account account) {
        final Intent intent =
                AccountServerSettingsActivity.getIntentForIncoming(getActivity(), account);
        getActivity().startActivity(intent);
    }

    /**
     * Dispatch to edit outgoing settings.
     */
    public void onOutgoingSettings(Account account) {
        final Intent intent =
                AccountServerSettingsActivity.getIntentForOutgoing(getActivity(), account);
        getActivity().startActivity(intent);
    }
}
