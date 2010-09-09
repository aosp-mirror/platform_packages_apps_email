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

import com.android.email.Preferences;
import com.android.email.R;

import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.PreferenceFragment;

public class GeneralPreferences extends PreferenceFragment {

    private static final String PREFERENCE_AUTO_ADVANCE= "auto_advance";

    private Preferences mPreferences;
    private ListPreference mAutoAdvance;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.general_preferences);
    }

    @Override
    public void onResume() {
        loadSettings();
        super.onResume();
    }

    @Override
    public void onPause() {
        super.onPause();
        saveSettings();
    }

    private void loadSettings() {
        mPreferences = Preferences.getPreferences(getActivity());
        mAutoAdvance = (ListPreference) findPreference(PREFERENCE_AUTO_ADVANCE);
        mAutoAdvance.setValueIndex(mPreferences.getAutoAdvanceDirection());
    }

    private void saveSettings() {
        mPreferences.setAutoAdvanceDirection(
                mAutoAdvance.findIndexOfValue(mAutoAdvance.getValue()));
    }
}
