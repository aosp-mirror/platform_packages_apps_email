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
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import com.android.mail.preferences.MailPrefs.PreferenceKeys;
import com.android.mail.ui.settings.GeneralPrefsFragment;

public class GeneralPreferences extends GeneralPrefsFragment {

    public GeneralPreferences() {}

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final PreferenceScreen ps = getPreferenceScreen();
        final Preference removalAction = findPreference(PreferenceKeys.REMOVAL_ACTION);
        if (removalAction != null) {
            ps.removePreference(removalAction);
        }
        final Preference confirmArchive = findPreference(PreferenceKeys.CONFIRM_ARCHIVE);
        final PreferenceGroup removalGroup =
                (PreferenceGroup) findPreference(REMOVAL_ACTIONS_GROUP);
        if (confirmArchive != null) {
            removalGroup.removePreference(confirmArchive);
        }
    }

    @Override
    protected boolean supportsArchive() {
        return false;
    }
}
