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
import android.app.LoaderManager;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.CursorLoader;
import android.content.Intent;
import android.content.Loader;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceFragment;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.view.MenuItem;

import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.utility.EmailAsyncTask;
import com.android.emailcommon.utility.Utility;
import com.android.mail.providers.Folder;
import com.android.mail.providers.UIProvider;
import com.android.mail.ui.MailAsyncTaskLoader;
import com.android.mail.utils.LogUtils;
import com.google.common.base.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private static final String EXTRA_FOLDERS_URI = "FOLDERS_URI";
    private static final String EXTRA_INBOX_ID = "INBOX_ID";

    private static final int FOLDERS_LOADER_ID = 0;
    private Uri mFoldersUri;
    private int mInboxId;
    private final List<Folder> mFolders = new ArrayList<>();

    /**
     * Starts the activity
     */
    public static Intent getIntent(Context context, Uri foldersUri, Folder inbox) {
        final Intent i = new Intent(context, MailboxSettings.class);
        i.putExtra(EXTRA_FOLDERS_URI, foldersUri);
        i.putExtra(EXTRA_INBOX_ID, inbox.id);
        return i;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // This needs to happen before super.onCreate() since that calls onBuildHeaders()
        mInboxId = getIntent().getIntExtra(EXTRA_INBOX_ID, -1);
        mFoldersUri = getIntent().getParcelableExtra(EXTRA_FOLDERS_URI);

        if (mFoldersUri != null) {
            getLoaderManager().initLoader(FOLDERS_LOADER_ID, null,
                    new MailboxSettingsFolderLoaderCallbacks());
        }

        super.onCreate(savedInstanceState);

        // Always show "app up" as we expect our parent to be an Email activity.
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayOptions(ActionBar.DISPLAY_HOME_AS_UP, ActionBar.DISPLAY_HOME_AS_UP);
        }
    }

    @Override
    public void onBuildHeaders(List<Header> target) {
        if (mFolders.isEmpty()) {
            final Header dummy = new Header();
            dummy.titleRes = R.string.mailbox_name_display_inbox;
            dummy.fragment = MailboxSettingsFragment.class.getName();
            dummy.fragmentArguments = MailboxSettingsFragment.getArguments(mInboxId);
            target.add(dummy);
        } else {
            for (final Folder f : mFolders) {
                final Header h = new Header();
                if (!TextUtils.isEmpty(f.hierarchicalDesc)) {
                    h.title = f.hierarchicalDesc;
                } else {
                    h.title = f.name;
                }
                h.fragment = MailboxSettingsFragment.class.getName();
                h.fragmentArguments = MailboxSettingsFragment.getArguments(f.id);
                if (f.id == mInboxId) {
                    target.add(0, h);
                } else {
                    target.add(h);
                }
            }
        }
    }

    @Override
    protected boolean isValidFragment(String fragmentName) {
        // Activity is not exported
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
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

    private class MailboxSettingsFolderLoaderCallbacks
            implements LoaderManager.LoaderCallbacks<Cursor> {

        @Override
        public Loader<Cursor> onCreateLoader(int i, Bundle bundle) {
            return new CursorLoader(MailboxSettings.this, mFoldersUri,
                    UIProvider.FOLDERS_PROJECTION, null, null, null);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> cursorLoader, Cursor cursor) {
            if (cursor == null) {
                return;
            }
            mFolders.clear();

            while(cursor.moveToNext()) {
                final Folder folder = new Folder(cursor);
                if (!folder.supportsCapability(UIProvider.FolderCapabilities.IS_VIRTUAL) &&
                        !folder.isTrash() && !folder.isDraft() && !folder.isOutbox()) {
                    mFolders.add(folder);
                }
            }

            invalidateHeaders();
        }

        @Override
        public void onLoaderReset(Loader<Cursor> cursorLoader) {
            mFolders.clear();
        }
    }

    public static class MailboxSettingsFragment extends PreferenceFragment {
        private static final String EXTRA_MAILBOX_ID = "MailboxId";

        private static final String BUNDLE_MAILBOX = "MailboxSettings.mailbox";
        private static final String BUNDLE_MAX_LOOKBACK = "MailboxSettings.maxLookback";
        private static final String BUNDLE_SYNC_ENABLED_VALUE = "MailboxSettings.syncEnabled";
        private static final String BUNDLE_SYNC_WINDOW_VALUE = "MailboxSettings.syncWindow";

        private static final String PREF_SYNC_ENABLED_KEY = "sync_enabled";
        private static final String PREF_SYNC_WINDOW_KEY = "sync_window";

        private Mailbox mMailbox;
        /** The maximum lookback allowed for this mailbox, or 0 if no max. */
        private int mMaxLookback;

        private CheckBoxPreference mSyncEnabledPref;
        private ListPreference mSyncLookbackPref;

        private static Bundle getArguments(long mailboxId) {
            final Bundle b = new Bundle(1);
            b.putLong(EXTRA_MAILBOX_ID, mailboxId);
            return b;
        }

        public MailboxSettingsFragment() {}

        @Override
        public void onActivityCreated(Bundle savedInstanceState) {
            super.onActivityCreated(savedInstanceState);
            final long mailboxId = getArguments().getLong(EXTRA_MAILBOX_ID, Mailbox.NO_MAILBOX);
            if (mailboxId == Mailbox.NO_MAILBOX) {
                getActivity().finish();
            }

            addPreferencesFromResource(R.xml.mailbox_preferences);

            mSyncEnabledPref = (CheckBoxPreference) findPreference(PREF_SYNC_ENABLED_KEY);
            mSyncLookbackPref = (ListPreference) findPreference(PREF_SYNC_WINDOW_KEY);

            mSyncLookbackPref.setOnPreferenceChangeListener(mPreferenceChanged);

            if (savedInstanceState != null) {
                mMailbox = savedInstanceState.getParcelable(BUNDLE_MAILBOX);
                mMaxLookback = savedInstanceState.getInt(BUNDLE_MAX_LOOKBACK);
                mSyncEnabledPref
                        .setChecked(savedInstanceState.getBoolean(BUNDLE_SYNC_ENABLED_VALUE));
                mSyncLookbackPref.setValue(savedInstanceState.getString(BUNDLE_SYNC_WINDOW_VALUE));
                onDataLoaded();
            } else {
                // Make them disabled until we load data
                enablePreferences(false);
                getLoaderManager().initLoader(0, getArguments(), new MailboxLoaderCallbacks());
            }
        }

        private void enablePreferences(boolean enabled) {
            mSyncEnabledPref.setEnabled(enabled);
            mSyncLookbackPref.setEnabled(enabled);
        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle outState) {
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
        public void onDestroy() {
            super.onDestroy();
            if (!getActivity().isChangingConfigurations()) {
                saveToDatabase();
            }
        }

        private static class MailboxLoader extends MailAsyncTaskLoader<Map<String, Object>> {
            /** Projection for loading an account's policy key. */
            private static final String[] POLICY_KEY_PROJECTION =
                    { AccountColumns.POLICY_KEY };
            private static final int POLICY_KEY_COLUMN = 0;

            /** Projection for loading the max email lookback. */
            private static final String[] MAX_EMAIL_LOOKBACK_PROJECTION =
                    { Policy.MAX_EMAIL_LOOKBACK };
            private static final int MAX_EMAIL_LOOKBACK_COLUMN = 0;

            public static final String RESULT_KEY_MAILBOX = "mailbox";
            public static final String RESULT_KEY_MAX_LOOKBACK = "maxLookback";

            private final long mMailboxId;

            private MailboxLoader(Context context, long mailboxId) {
                super(context);
                mMailboxId = mailboxId;
            }

            @Override
            public Map<String, Object> loadInBackground() {
                final Map<String, Object> result = new HashMap<>();

                final Mailbox mailbox = Mailbox.restoreMailboxWithId(getContext(), mMailboxId);
                result.put(RESULT_KEY_MAILBOX, mailbox);
                result.put(RESULT_KEY_MAX_LOOKBACK, 0);

                if (mailbox == null) {
                    return result;
                }

                // Get the max lookback from our policy, if we have one.
                final Long policyKey = Utility.getFirstRowLong(getContext(),
                        ContentUris.withAppendedId(Account.CONTENT_URI, mailbox.mAccountKey),
                        POLICY_KEY_PROJECTION, null, null, null, POLICY_KEY_COLUMN);
                if (policyKey == null) {
                    // No policy, nothing to look up.
                    return result;
                }

                final int maxLookback = Utility.getFirstRowInt(getContext(),
                        ContentUris.withAppendedId(Policy.CONTENT_URI, policyKey),
                        MAX_EMAIL_LOOKBACK_PROJECTION, null, null, null,
                        MAX_EMAIL_LOOKBACK_COLUMN, 0);
                result.put(RESULT_KEY_MAX_LOOKBACK, maxLookback);

                return result;
            }

            @Override
            protected void onDiscardResult(Map<String, Object> result) {}
        }

        private class MailboxLoaderCallbacks
                implements LoaderManager.LoaderCallbacks<Map<String, Object>> {
            @Override
            public Loader<Map<String, Object>> onCreateLoader(int id, Bundle args) {
                final long mailboxId = args.getLong(EXTRA_MAILBOX_ID);
                return new MailboxLoader(getActivity(), mailboxId);
            }

            @Override
            public void onLoadFinished(Loader<Map<String, Object>> loader,
                    Map<String, Object> data) {
                final Mailbox mailbox = (Mailbox)
                        (data == null ? null : data.get(MailboxLoader.RESULT_KEY_MAILBOX));
                if (mailbox == null) {
                    getActivity().finish();
                    return;
                }

                mMailbox = mailbox;
                mMaxLookback = (Integer) data.get(MailboxLoader.RESULT_KEY_MAX_LOOKBACK);

                mSyncEnabledPref.setChecked(mMailbox.mSyncInterval != 0);
                mSyncLookbackPref.setValue(String.valueOf(mMailbox.mSyncLookback));
                onDataLoaded();
                if (mMailbox.mType != Mailbox.TYPE_DRAFTS) {
                    enablePreferences(true);
                }
            }

            @Override
            public void onLoaderReset(Loader<Map<String, Object>> loader) {}
        }

        /**
         * Called when {@link #mMailbox} is loaded (either by the loader or from the saved state).
         */
        private void onDataLoaded() {
            Preconditions.checkNotNull(mMailbox);

            // Update the title with the mailbox name.
            final ActionBar actionBar = getActivity().getActionBar();
            final String mailboxName = mMailbox.mDisplayName;
            if (actionBar != null) {
                actionBar.setTitle(mailboxName);
                actionBar.setSubtitle(getString(R.string.mailbox_settings_activity_title));
            } else {
                getActivity().setTitle(
                        getString(R.string.mailbox_settings_activity_title_with_mailbox,
                                mailboxName));
            }

            MailboxSettings.setupLookbackPreferenceOptions(getActivity(), mSyncLookbackPref,
                    mMaxLookback, true);
        }


        private final OnPreferenceChangeListener mPreferenceChanged =
                new OnPreferenceChangeListener() {
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
         * Note it's called from {@link #onDestroy()}, which is called on the UI thread where we're
         * not allowed to touch the database, so it uses {@link EmailAsyncTask} to do the save on a
         * bg thread. This unfortunately means there's a chance that the app gets killed before the
         * save is finished.
         */
        private void saveToDatabase() {
            if (mMailbox == null) {
                // We haven't loaded yet, nothing to save.
                return;
            }
            final int syncInterval = mSyncEnabledPref.isChecked() ? 1 : 0;
            final int syncLookback = Integer.valueOf(mSyncLookbackPref.getValue());

            final boolean syncIntervalChanged = syncInterval != mMailbox.mSyncInterval;
            final boolean syncLookbackChanged = syncLookback != mMailbox.mSyncLookback;

            // Only save if a preference has changed value.
            if (!syncIntervalChanged && !syncLookbackChanged) {
                return;
            }

            LogUtils.i(Logging.LOG_TAG, "Saving mailbox settings...");
            enablePreferences(false);

            final long id = mMailbox.mId;
            final Context context = getActivity().getApplicationContext();

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
            }.executeSerial((Void [])null);
        }
    }
}
