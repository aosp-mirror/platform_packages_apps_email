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

import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.Preference.OnPreferenceChangeListener;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.widget.Toast;

import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.activity.UiUtilities;

public class GeneralPreferences extends PreferenceFragment implements OnPreferenceChangeListener  {

    private static final String PREFERENCE_CATEGORY_KEY = "category_general_preferences";

    private static final String PREFERENCE_KEY_AUTO_ADVANCE = "auto_advance";
    private static final String PREFERENCE_KEY_TEXT_ZOOM = "text_zoom";
    private static final String PREFERENCE_KEY_REPLY_ALL = Preferences.REPLY_ALL;
    private static final String PREFERENCE_KEY_CLEAR_TRUSTED_SENDERS = "clear_trusted_senders";

    private Preferences mPreferences;
    private ListPreference mAutoAdvance;
    private ListPreference mTextZoom;

    CharSequence[] mSizeSummaries;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getPreferenceManager().setSharedPreferencesName(Preferences.PREFERENCES_FILE);

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.general_preferences);

        if (UiUtilities.useTwoPane(getActivity())) {
            // "Reply All" should only be shown on phones
            PreferenceCategory pc = (PreferenceCategory) findPreference(PREFERENCE_CATEGORY_KEY);
            pc.removePreference(findPreference(PREFERENCE_KEY_REPLY_ALL));
        }
    }

    @Override
    public void onResume() {
        loadSettings();
        super.onResume();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        String key = preference.getKey();

        if (PREFERENCE_KEY_AUTO_ADVANCE.equals(key)) {
            mPreferences.setAutoAdvanceDirection(mAutoAdvance.findIndexOfValue((String) newValue));
            return true;
        } else if (PREFERENCE_KEY_TEXT_ZOOM.equals(key)) {
            mPreferences.setTextZoom(mTextZoom.findIndexOfValue((String) newValue));
            reloadDynamicSummaries();
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

        String key = preference.getKey();
        if (key.equals(PREFERENCE_KEY_CLEAR_TRUSTED_SENDERS)) {
            mPreferences.clearTrustedSenders();
            Toast.makeText(
                    getActivity(), R.string.trusted_senders_cleared, Toast.LENGTH_SHORT).show();
            return true;
        }

        return false;
    }

    private void loadSettings() {
        mPreferences = Preferences.getPreferences(getActivity());
        mAutoAdvance = (ListPreference) findPreference(PREFERENCE_KEY_AUTO_ADVANCE);
        mAutoAdvance.setValueIndex(mPreferences.getAutoAdvanceDirection());
        mAutoAdvance.setOnPreferenceChangeListener(this);

        mTextZoom = (ListPreference) findPreference(PREFERENCE_KEY_TEXT_ZOOM);
        mTextZoom.setValueIndex(mPreferences.getTextZoom());
        mTextZoom.setOnPreferenceChangeListener(this);

        reloadDynamicSummaries();
    }

    /**
     * Reload any preference summaries that are updated dynamically
     */
    private void reloadDynamicSummaries() {
        int textZoomIndex = mPreferences.getTextZoom();
        // Update summary - but only load the array once
        if (mSizeSummaries == null) {
            mSizeSummaries = getActivity().getResources()
                    .getTextArray(R.array.general_preference_text_zoom_summary_array);
        }
        CharSequence summary = null;
        if (textZoomIndex >= 0 && textZoomIndex < mSizeSummaries.length) {
            summary = mSizeSummaries[textZoomIndex];
        }
        mTextZoom.setSummary(summary);
    }
}
