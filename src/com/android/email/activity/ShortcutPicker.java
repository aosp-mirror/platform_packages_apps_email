/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.email.activity;

import com.android.email.R;
import com.android.email.activity.ShortcutPickerFragment.AccountShortcutPickerFragment;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.View.OnClickListener;

/**
 * This class implements a launcher shortcut for directly accessing a single account.
 */
public class ShortcutPicker extends Activity implements OnClickListener {

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // TODO Relax this test slightly in order to re-use this activity for widget creation
        if (!Intent.ACTION_CREATE_SHORTCUT.equals(getIntent().getAction())) {
            // finish() immediately if we aren't supposed to be here
            finish();
            return;
        }

        // Set handler for the "cancel" button
        setContentView(R.layout.account_shortcut_picker);
        findViewById(R.id.cancel).setOnClickListener(this);

        if (getFragmentManager().findFragmentById(R.id.shortcut_list) == null) {
            // Load the account picking fragment
            // NOTE: do not add to history as this is the first fragment in the flow
            AccountShortcutPickerFragment fragment = new AccountShortcutPickerFragment();
            getFragmentManager().beginTransaction().add(R.id.shortcut_list, fragment).commit();
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.cancel:
                finish();
                break;
        }
    }
}
