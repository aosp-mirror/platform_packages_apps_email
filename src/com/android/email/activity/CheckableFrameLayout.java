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
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.Checkable;
import android.widget.FrameLayout;

/**
 * Base layout that changes its background when checked.
 */
public abstract class CheckableFrameLayout extends FrameLayout implements Checkable {
    private boolean mChecked = false;
    private Drawable mCheckedBackgroundDrawable;

    private static final int[] CHECKED_STATE_SET = { android.R.attr.state_checked };

    public CheckableFrameLayout(Context context) {
        super(context);
    }

    public CheckableFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CheckableFrameLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    public int[] onCreateDrawableState(int extraSpace) {
        final int[] drawableState = super.onCreateDrawableState(extraSpace + 1);
        if (isChecked()) {
            mergeDrawableStates(drawableState, CHECKED_STATE_SET);
        }
        return drawableState;
    }

    @Override
    public void toggle() {
        setChecked(!mChecked);
    }

    @Override
    public boolean isChecked() {
        return mChecked;
    }

    @Override
    public void setChecked(boolean checked) {
        if (mChecked != checked) {
            mChecked = checked;
            updateBackground();
        }
    }

    private void updateBackground() {
        if (mChecked) {
            if (mCheckedBackgroundDrawable == null) {
                mCheckedBackgroundDrawable = getCheckedBackground();
            }
            setBackgroundDrawable(mCheckedBackgroundDrawable);
        } else {
            setBackgroundDrawable(null);
        }
    }

    /**
     * Subclass implements this and returns the {@link Drawable} for the background for the checked
     * state.
     */
    protected abstract Drawable getCheckedBackground();
}
