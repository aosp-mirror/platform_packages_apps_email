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

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

/**
 * Simple text preference allowing a large number of lines
 */
public class PolicyListPreference extends Preference {
    // Arbitrary, but large number (we don't, and won't, have nearly this many)
    public static final int MAX_POLICIES = 24;

    public PolicyListPreference(Context ctx, AttributeSet attrs, int defStyle) {
        super(ctx, attrs, defStyle);
    }

    public PolicyListPreference(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        ((TextView)view.findViewById(android.R.id.summary)).setMaxLines(MAX_POLICIES);
    }
}
