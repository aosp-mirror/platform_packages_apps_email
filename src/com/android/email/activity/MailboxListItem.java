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

package com.android.email.activity;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;


/**
 * List item in {@link MailboxListFragment}.
 *
 * Inheriting from {@link CheckableFrameLayout}, change the background when checked.
 */
public class MailboxListItem extends CheckableFrameLayout {
    private static final Drawable mCheckedBackground = new ColorDrawable(Color.RED);

    public MailboxListItem(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public MailboxListItem(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public MailboxListItem(Context context) {
        super(context);
    }

    @Override
    protected Drawable getCheckedBackground() {
        return mCheckedBackground;
    }
}
