/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.app.ActionBar;
import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.util.Log;
import android.view.MenuItem;

import com.android.email.Email;
import com.android.email.FolderProperties;
import com.android.email.R;
import com.android.email.RefreshManager;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;

/**
 * "Mailbox settings" activity.
 *
 * It's used to update per-mailbox sync settings.  It normally updates Mailbox settings, unless
 * the target mailbox is Inbox, in which case it updates Account settings instead.
 *
 * All changes made by the user will not be immediately saved to the database, as changing the
 * sync window may result in removal of messages.  Instead, we only save to the database in {@link
 * #onDestroy()}, unless it's called for configuration changes.
 */
public class MailboxSettings extends PreferenceActivity {
    private static final String EXTRA_MAILBOX_ID = "MAILBOX_ID";
    private static final String BUNDLE_ACCOUNT = "MailboxSettings.account";
    private static final String BUNDLE_MAILBOX = "MailboxSettings.mailbox";
    private static final String BUNDLE_NEEDS_SAVE = "MailboxSettings.needsSave";

    private static final String PREF_CHECK_FREQUENCY_KEY = "check_frequency";
    private static final String PREF_SYNC_WINDOW_KEY = "sync_window";

    private final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    // Account and Mailbox -- directly loaded by LoadMailboxTask
    private Account mAccount;
    private Mailbox mMailbox;
    private boolean mNeedsSave;

    private ListPreference mSyncIntervalPref;
    private ListPreference mSyncLookbackPref;

    /**
     * Starts the activity for a mailbox.
     */
    public static final void start(Activity parent, long mailboxId) {
        Intent i = new Intent(parent, MailboxSettings.class);
        i.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        parent.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final long mailboxId = getIntent().getLongExtra(EXTRA_MAILBOX_ID, Mailbox.NO_MAILBOX);
        if (mailboxId == Mailbox.NO_MAILBOX) {
            finish();
            return;
        }

        addPreferencesFromResource(R.xml.mailbox_preferences);

        mSyncIntervalPref = (ListPreference) findPreference(PREF_CHECK_FREQUENCY_KEY);
        mSyncLookbackPref = (ListPreference) findPreference(PREF_SYNC_WINDOW_KEY);

        mSyncIntervalPref.setOnPreferenceChangeListener(mPreferenceChanged);
        mSyncLookbackPref.setOnPreferenceChangeListener(mPreferenceChanged);

        // Make them disabled until we load data
        enablePreferences(false);

        if (savedInstanceState != null) {
            mAccount = savedInstanceState.getParcelable(BUNDLE_ACCOUNT);
            mMailbox = savedInstanceState.getParcelable(BUNDLE_MAILBOX);
            mNeedsSave = savedInstanceState.getBoolean(BUNDLE_NEEDS_SAVE);
        }
        if (mAccount == null) {
            new LoadMailboxTask(mailboxId).executeParallel((Void[]) null);
        } else {
            onDataLoaded();
        }

        // Always show "app up" as we expect our parent to be an Email activity.
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayOptions(ActionBar.DISPLAY_HOME_AS_UP, ActionBar.DISPLAY_HOME_AS_UP);
        }
    }

    private void enablePreferences(boolean enabled) {
        mSyncIntervalPref.setEnabled(enabled);
        mSyncLookbackPref.setEnabled(enabled);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(BUNDLE_ACCOUNT, mAccount);
        outState.putParcelable(BUNDLE_MAILBOX, mMailbox);
        outState.putBoolean(BUNDLE_NEEDS_SAVE, mNeedsSave);
    }

    /**
     * We save all the settings in onDestroy, *unless it's for configuration changes*.
     */
    @Override
    protected void onDestroy() {
        mTaskTracker.cancellAllInterrupt();
        if (!isChangingConfigurations()) {
            saveToDatabase();
        }
        super.onDestroy();
    }

    /**
     * Loads {@link #mAccount} and {@link #mMailbox}.
     */
    private class LoadMailboxTask extends EmailAsyncTask<Void, Void, Void> {
        private final long mMailboxId;

        public LoadMailboxTask(long mailboxId) {
            super(mTaskTracker);
            mMailboxId = mailboxId;
        }

        @Override
        protected Void doInBackground(Void... params) {
            final Context c = MailboxSettings.this;
            mMailbox = Mailbox.restoreMailboxWithId(c, mMailboxId);
            if (mMailbox != null) {
                mAccount = Account.restoreAccountWithId(c, mMailbox.mAccountKey);
            }
            return null;
        }

        @Override
        protected void onSuccess(Void result) {
            if ((mAccount == null) || (mMailbox == null)) {
                finish(); // Account or mailbox removed.
                return;
            }
            onDataLoaded();
        }
    }

    /**
     * Setup the entries and entry values for the sync lookback preference
     * @param context the caller's context
     * @param pref a ListPreference to be set up
     * @param account the Account (or owner of a Mailbox) whose preference is being set
     */
    public static void setupLookbackPreferenceOptions(Context context, ListPreference pref,
            Account account) {
        Resources resources = context.getResources();
        // Load the complete list of entries/values
        CharSequence[] entries =
                resources.getTextArray(R.array.account_settings_mail_window_entries);
        CharSequence[] values =
                resources.getTextArray(R.array.account_settings_mail_window_values);
        // If we have a maximum lookback policy, enforce it
        if (account.mPolicyKey > 0) {
            Policy policy = Policy.restorePolicyWithId(context, account.mPolicyKey);
            if (policy != null && (policy.mMaxEmailLookback != 0)) {
                int maxEntry  = policy.mMaxEmailLookback + 1;
                // Copy the proper number of values into new entries/values array
                CharSequence[] policyEntries = new CharSequence[maxEntry];
                CharSequence[] policyValues = new CharSequence[maxEntry];
                for (int i = 0; i < maxEntry; i++) {
                    policyEntries[i] = entries[i];
                    policyValues[i] = values[i];
                }
                // Point entries/values to the new arrays
                entries = policyEntries;
                values = policyValues;
            }
        }
        // Set up the preference
        pref.setEntries(entries);
        pref.setEntryValues(values);
    }

    /**
     * Called when {@link #mAccount} and {@link #mMailbox} are loaded (either by the async task
     * or from the saved state).
     */
    private void onDataLoaded() {
        Preconditions.checkNotNull(mAccount);
        Preconditions.checkNotNull(mMailbox);

        // Update the title with the mailbox name.
        ActionBar actionBar = getActionBar();
        String mailboxName = FolderProperties.getInstance(this).getDisplayName(mMailbox);
        if (actionBar != null) {
            actionBar.setTitle(mailboxName);
            actionBar.setSubtitle(getString(R.string.mailbox_settings_activity_title));
        } else {
            setTitle(getString(R.string.mailbox_settings_activity_title_with_mailbox, mailboxName));
        }

        setupLookbackPreferenceOptions(this, mSyncLookbackPref, mAccount);

        // Set default value & update summary
        mSyncIntervalPref.setValue(String.valueOf(getSyncInterval()));
        mSyncLookbackPref.setValue(String.valueOf(getSyncLookback()));

        updatePreferenceSummary();

        // Make then enabled
        enablePreferences(true);
    }

    private void updatePreferenceSummary() {
        mSyncIntervalPref.setSummary(mSyncIntervalPref.getEntry());
        mSyncLookbackPref.setSummary(mSyncLookbackPref.getEntry());
    }

    /**
     * @return current sync interval setting from the objects
     */
    private int getSyncInterval() {
        int syncInterval;
        if (mMailbox.mType == Mailbox.TYPE_INBOX) {
            syncInterval = mAccount.mSyncInterval;
        } else {
            if (mMailbox.mSyncInterval == 0) {
                // 0 is the default value, and it means "don't sync" (for non-inbox mailboxes)
                syncInterval = Mailbox.CHECK_INTERVAL_NEVER;
            } else {
                syncInterval = mMailbox.mSyncInterval;
            }
        }
        // In the case of the internal push states, use "push"
        if (syncInterval == Mailbox.CHECK_INTERVAL_PING ||
                syncInterval == Mailbox.CHECK_INTERVAL_PUSH_HOLD) {
            syncInterval = Mailbox.CHECK_INTERVAL_PUSH;
        }
        return syncInterval;
    }

    /**
     * @return current sync lookback setting from the objects
     */
    private int getSyncLookback() {
        if (mMailbox.mType == Mailbox.TYPE_INBOX) {
            return mAccount.mSyncLookback;
        } else {
            // Here, 0 is valid and means "use the account default sync window".
            return mMailbox.mSyncLookback;
        }
    }

    private final OnPreferenceChangeListener mPreferenceChanged = new OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            final ListPreference lp = (ListPreference) preference;
            if (Objects.equal(lp.getValue(), newValue)) {
                return false;
            }
            mNeedsSave = true;
            if (Email.DEBUG) {
                Log.i(Logging.LOG_TAG, "Setting changed");
            }
            // In order to set the current entry to the summary, we need to udpate the value
            // manually, rather than letting the framework do that (by returning true).
            lp.setValue((String) newValue);
            updatePreferenceSummary();
            updateObjects();
            return false;
        }
    };

    /**
     * Updates {@link #mAccount}/{@link #mMailbox}, but doesn't save to the database yet.
     */
    private void updateObjects() {
        final int syncInterval = Integer.valueOf(mSyncIntervalPref.getValue());
        final int syncLookback = Integer.valueOf(mSyncLookbackPref.getValue());
        if (Email.DEBUG) {
            Log.i(Logging.LOG_TAG, "Updating object: " + syncInterval + "," + syncLookback);
        }
        if (mMailbox.mType == Mailbox.TYPE_INBOX) {
            mAccount.mSyncInterval = syncInterval;
            mAccount.mSyncLookback = syncLookback;
        } else {
            mMailbox.mSyncInterval = syncInterval;
            mMailbox.mSyncLookback = syncLookback;
        }
    }

    /**
     * Save changes to the database.
     *
     * Note it's called from {@link #onDestroy()}, which is called on the UI thread where we're not
     * allowed to touch the database, so it uses {@link EmailAsyncTask} to do the save on a bg
     * thread. This unfortunately means there's a chance that the app gets killed before the save is
     * finished.
     */
    private void saveToDatabase() {
        if (!mNeedsSave) {
            return;
        }
        Log.i(Logging.LOG_TAG, "Saving mailbox settings...");
        enablePreferences(false);

        // Since the activity will be destroyed...
        // Create local references (Although it's really okay to touch members of a destroyed
        // activity...)
        final Account account = mAccount;
        final Mailbox mailbox = mMailbox;
        final Context context = getApplicationContext();

        new EmailAsyncTask<Void, Void, Void> (null /* no cancel */) {
            @Override
            protected Void doInBackground(Void... params) {
                final ContentValues cv = new ContentValues();
                final Uri uri;

                if (mailbox.mType == Mailbox.TYPE_INBOX) {
                    cv.put(AccountColumns.SYNC_INTERVAL, account.mSyncInterval);
                    cv.put(AccountColumns.SYNC_LOOKBACK, account.mSyncLookback);
                    uri = ContentUris.withAppendedId(Account.CONTENT_URI, account.mId);
                } else {
                    cv.put(MailboxColumns.SYNC_INTERVAL, mailbox.mSyncInterval);
                    cv.put(MailboxColumns.SYNC_LOOKBACK, mailbox.mSyncLookback);
                    uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailbox.mId);
                }
                context.getContentResolver().update(uri, cv, null, null);

                Log.i(Logging.LOG_TAG, "Saved: " + uri);
                return null;
            }

            @Override
            protected void onSuccess(Void result) {
                // must be called on the ui thread
                RefreshManager.getInstance(context).refreshMessageList(account.mId, mailbox.mId,
                        true);
            }
        }.executeSerial((Void [])null);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
