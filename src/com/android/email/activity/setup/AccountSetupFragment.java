/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.app.Fragment;
import android.os.Bundle;

/**
 * Superclass for setup UI fragments.
 * Currently holds a super-interface for the callbacks, as well as the state it was launched for so
 * we can unwind things correctly when the user navigates the back stack.
 */
public class AccountSetupFragment extends Fragment {
    private static final String SAVESTATE_STATE = "AccountSetupFragment.state";
    private int mState;

    public interface Callback {
        void setNextButtonEnabled(boolean enabled);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mState = savedInstanceState.getInt(SAVESTATE_STATE);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SAVESTATE_STATE, mState);
    }

    public void setState(int state) {
        mState = state;
    }

    public int getState() {
        return mState;
    }
}
