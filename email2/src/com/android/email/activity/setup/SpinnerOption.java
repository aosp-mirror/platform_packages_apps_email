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

package com.android.email.activity.setup;

import android.widget.Spinner;

public class SpinnerOption {
    public final Object value;

    public final String label;

    public static void setSpinnerOptionValue(Spinner spinner, Object value) {
        for (int i = 0, count = spinner.getCount(); i < count; i++) {
            SpinnerOption so = (SpinnerOption)spinner.getItemAtPosition(i);
            if (so.value.equals(value)) {
                spinner.setSelection(i, true);
                return;
            }
        }
    }

    public SpinnerOption(Object value, String label) {
        this.value = value;
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
