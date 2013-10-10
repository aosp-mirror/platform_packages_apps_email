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
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.view.MenuItem;

import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.android.mail.utils.LogUtils;
import com.google.common.base.Preconditions;

import java.util.Arrays;

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
    private static final String BUNDLE_MAILBOX = "MailboxSettings.mailbox";
    private static final String BUNDLE_MAX_LOOKBACK = "MailboxSettings.maxLookback";
    private static final String BUNDLE_SYNC_ENABLED_VALUE = "MailboxSettings.syncEnabled";
    private static final String BUNDLE_SYNC_WINDOW_VALUE = "MailboxSettings.syncWindow";

    private static final String PREF_SYNC_ENABLED_KEY = "sync_enabled";
    private static final String PREF_SYNC_WINDOW_KEY = "sync_window";

    /** Projection for loading an account's policy key. */
    private static final String[] POLICY_KEY_PROJECTION = { Account.POLICY_KEY };
    private static final int POLICY_KEY_COLUMN = 0;

    /** Projection for loading the max email lookback. */
    private static final String[] MAX_EMAIL_LOOKBACK_PROJECTION = { Policy.MAX_EMAIL_LOOKBACK };
    private static final int MAX_EMAIL_LOOKBACK_COLUMN = 0;

    private final EmailAsyncTask.Tracker mTaskTracker = new EmailAsyncTask.Tracker();

    private Mailbox mMailbox;
    /** The maximum lookback allowed for this mailbox, or 0 if no max. */
    private int mMaxLookback;

    private CheckBoxPreference mSyncEnabledPref;
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

        mSyncEnabledPref = (CheckBoxPreference) findPreference(PREF_SYNC_ENABLED_KEY);
        mSyncLookbackPref = (ListPreference) findPreference(PREF_SYNC_WINDOW_KEY);

        mSyncLookbackPref.setOnPreferenceChangeListener(mPreferenceChanged);

        if (savedInstanceState != null) {
            mMailbox = savedInstanceState.getParcelable(BUNDLE_MAILBOX);
            mMaxLookback = savedInstanceState.getInt(BUNDLE_MAX_LOOKBACK);
            mSyncEnabledPref.setChecked(savedInstanceState.getBoolean(BUNDLE_SYNC_ENABLED_VALUE));
            mSyncLookbackPref.setValue(savedInstanceState.getString(BUNDLE_SYNC_WINDOW_VALUE));
            onDataLoaded();
        } else {
            // Make them disabled until we load data
            enablePreferences(false);
            new LoadMailboxTask(mailboxId).executeParallel((Void[]) null);
        }

        // Always show "app up" as we expect our parent to be an Email activity.
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayOptions(ActionBar.DISPLAY_HOME_AS_UP, ActionBar.DISPLAY_HOME_AS_UP);
        }
    }

    private void enablePreferences(boolean enabled) {
        mSyncEnabledPref.setEnabled(enabled);
        mSyncLookbackPref.setEnabled(enabled);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(BUNDLE_MAILBOX, mMailbox);
        outState.putInt(BUNDLE_MAX_LOOKBACK, mMaxLookback);
        outState.putBoolean(BUNDLE_SYNC_ENABLED_VALUE, mSyncEnabledPref.isChecked());
        outState.putString(BUNDLE_SYNC_WINDOW_VALUE, mSyncLookbackPref.getValue());
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
     * Loads {@link #mMailbox} and {@link #mMaxLookback} from DB.
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
            mMaxLookback = 0;
            if (mMailbox != null) {
                // Get the max lookback from our policy, if we have one.
                final Long policyKey = Utility.getFirstRowLong(c, ContentUris.withAppendedId(
                        Account.CONTENT_URI, mMailbox.mAccountKey), POLICY_KEY_PROJECTION,
                        null, null, null, POLICY_KEY_COLUMN);
                if (policyKey != null) {
                    mMaxLookback = Utility.getFirstRowInt(c, ContentUris.withAppendedId(
                            Policy.CONTENT_URI, policyKey), MAX_EMAIL_LOOKBACK_PROJECTION,
                            null, null, null, MAX_EMAIL_LOOKBACK_COLUMN, 0);
                }
            }
            return null;
        }

        @Override
        protected void onSuccess(Void result) {
            if (mMailbox == null) {
                finish(); // Account or mailbox removed.
                return;
            }
            mSyncEnabledPref.setChecked(mMailbox.mSyncInterval != 0);
            mSyncLookbackPref.setValue(String.valueOf(mMailbox.mSyncLookback));
            onDataLoaded();
            if (mMailbox.mType != Mailbox.TYPE_DRAFTS) {
                enablePreferences(true);
            }
        }
    }

    /**
     * Setup the entries and entry values for the sync lookback preference
     * @param context the caller's context
     * @param pref a ListPreference to be set up
     * @param maxLookback The maximum lookback allowed, or 0 if no max.
     * @param showWithDefault Whether to show the version with default, or without.
     */
    public static void setupLookbackPreferenceOptions(final Context context,
            final ListPreference pref, final int maxLookback, final boolean showWithDefault) {
        final Resources resources = context.getResources();
        // Load the complete list of entries/values
        CharSequence[] entries;
        CharSequence[] values;
        final int offset;
        if (showWithDefault) {
            entries = resources.getTextArray(
                    R.array.account_settings_mail_window_entries_with_default);
            values = resources.getTextArray(
                    R.array.account_settings_mail_window_values_with_default);
            offset = 1;
        } else {
            entries = resources.getTextArray(R.array.account_settings_mail_window_entries);
            values = resources.getTextArray(R.array.account_settings_mail_window_values);
            offset = 0;
        }
        // If we have a maximum lookback policy, enforce it
        if (maxLookback > 0) {
            final int size = maxLookback + offset;
            entries = Arrays.copyOf(entries, size);
            values = Arrays.copyOf(values, size);
        }
        // Set up the preference
        pref.setEntries(entries);
        pref.setEntryValues(values);
        pref.setSummary(pref.getEntry());
    }

    /**
     * Called when {@link #mMailbox} is loaded (either by the async task or from the saved state).
     */
    private void onDataLoaded() {
        Preconditions.checkNotNull(mMailbox);

        // Update the title with the mailbox name.
        final ActionBar actionBar = getActionBar();
        final String mailboxName = mMailbox.mDisplayName;
        if (actionBar != null) {
            actionBar.setTitle(mailboxName);
            actionBar.setSubtitle(getString(R.string.mailbox_settings_activity_title));
        } else {
            setTitle(getString(R.string.mailbox_settings_activity_title_with_mailbox, mailboxName));
        }

        setupLookbackPreferenceOptions(this, mSyncLookbackPref, mMaxLookback, true);
    }


    private final OnPreferenceChangeListener mPreferenceChanged = new OnPreferenceChangeListener() {
        @Override
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            mSyncLookbackPref.setValue((String) newValue);
            mSyncLookbackPref.setSummary(mSyncLookbackPref.getEntry());
            return false;
        }
    };

    /**
     * Save changes to the database.
     *
     * Note it's called from {@link #onDestroy()}, which is called on the UI thread where we're not
     * allowed to touch the database, so it uses {@link EmailAsyncTask} to do the save on a bg
     * thread. This unfortunately means there's a chance that the app gets killed before the save is
     * finished.
     */
    private void saveToDatabase() {
        final int syncInterval = mSyncEnabledPref.isChecked() ? 1 : 0;
        final int syncLookback = Integer.valueOf(mSyncLookbackPref.getValue());

        final boolean syncIntervalChanged = syncInterval != mMailbox.mSyncInterval;
        final boolean syncLookbackChanged = syncLookback != mMailbox.mSyncLookback;

        // Only save if a preference has changed value.
        if (syncIntervalChanged || syncLookbackChanged) {
            LogUtils.i(Logging.LOG_TAG, "Saving mailbox settings...");
            enablePreferences(false);

            final long id = mMailbox.mId;
            final Context context = getApplicationContext();

            new EmailAsyncTask<Void, Void, Void> (null /* no cancel */) {
                @Override
                protected Void doInBackground(Void... params) {
                    final ContentValues cv = new ContentValues(2);
                    final Uri uri;
                    if (syncIntervalChanged) {
                        cv.put(MailboxColumns.SYNC_INTERVAL, syncInterval);
                    }
                    if (syncLookbackChanged) {
                        cv.put(MailboxColumns.SYNC_LOOKBACK, syncLookback);
                    }
                    uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, id);
                    context.getContentResolver().update(uri, cv, null, null);

                    LogUtils.i(Logging.LOG_TAG, "Saved: " + uri);
                    return null;
                }

                @Override
                protected void onSuccess(Void result) {
                    // must be called on the ui thread
                    //***
                    //RefreshManager.getInstance(context).refreshMessageList(account.mId,
                    //        mailbox.mId, true);
                }
            }.executeSerial((Void [])null);
        }
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
