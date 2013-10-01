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
import android.os.Bundle;

import com.android.emailcommon.Logging;
import com.android.mail.utils.LogUtils;

/**
 * Superclass of all of the account setup activities; ensures that SetupData state is saved/restored
 * automatically as required
 */
public class AccountSetupActivity extends Activity implements SetupData.SetupDataContainer {
    private static final boolean DEBUG_SETUP_FLOWS = false;  // Don't check in set to true
    protected SetupData mSetupData;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            mSetupData = savedInstanceState.getParcelable(SetupData.EXTRA_SETUP_DATA);
        } else {
            final Bundle b = getIntent().getExtras();
            if (b != null) {
                mSetupData = b.getParcelable(SetupData.EXTRA_SETUP_DATA);
            }
        }
        if (mSetupData == null) {
            mSetupData = new SetupData();
        }

        super.onCreate(savedInstanceState);
        if (DEBUG_SETUP_FLOWS) {
            LogUtils.d(Logging.LOG_TAG, "%s onCreate %s", getClass().getName(), mSetupData.debugString());
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(SetupData.EXTRA_SETUP_DATA, mSetupData);
    }

    @Override
    public SetupData getSetupData() {
        return mSetupData;
    }

    @Override
    public void setSetupData(SetupData setupData) {
        mSetupData = setupData;
    }
}
