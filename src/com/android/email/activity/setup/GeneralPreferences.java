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

import android.content.ContentResolver;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

import com.android.email.R;
import com.android.email.provider.EmailProvider;
import com.android.mail.preferences.MailPrefs;
import com.android.mail.ui.settings.ClearPictureApprovalsDialogFragment;

public class GeneralPreferences extends PreferenceFragment implements
        OnPreferenceChangeListener {

    private static final String AUTO_ADVANCE_MODE_WIDGET = "auto-advance-mode-widget";

    private MailPrefs mMailPrefs;
    private ListPreference mAutoAdvance;

    private boolean mSettingsChanged = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        mMailPrefs = MailPrefs.get(getActivity());
        getPreferenceManager().setSharedPreferencesName(mMailPrefs.getSharedPreferencesName());

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.general_preferences);
    }

    @Override
    public void onResume() {
        loadSettings();
        mSettingsChanged = false;
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (mSettingsChanged) {
            // Notify the account list that we have changes
            ContentResolver resolver = getActivity().getContentResolver();
            resolver.notifyChange(EmailProvider.UIPROVIDER_ALL_ACCOUNTS_NOTIFIER, null);
        }
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();
        // Indicate we need to send notifications to UI
        mSettingsChanged = true;
        if (AUTO_ADVANCE_MODE_WIDGET.equals(key)) {
            mMailPrefs.setAutoAdvanceMode(mAutoAdvance.findIndexOfValue((String) newValue) + 1);
            return true;
        }
        return false;
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (getActivity() == null) {
            // Guard against monkeys.
            return false;
        }
        // Indicate we need to send notifications to UI
        mSettingsChanged = true;
        return false;
    }

    private void loadSettings() {
        mAutoAdvance = (ListPreference) findPreference(AUTO_ADVANCE_MODE_WIDGET);
        mAutoAdvance.setValueIndex(mMailPrefs.getAutoAdvanceMode() - 1);
        mAutoAdvance.setOnPreferenceChangeListener(this);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.general_prefs_fragment_menu, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.clear_picture_approvals_menu_item:
                clearDisplayImages();
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void clearDisplayImages() {
        final ClearPictureApprovalsDialogFragment fragment =
                ClearPictureApprovalsDialogFragment.newInstance();
        fragment.show(getActivity().getFragmentManager(),
                ClearPictureApprovalsDialogFragment.FRAGMENT_TAG);
    }

}
