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

package com.android.email.view;

import com.android.email.R;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * A {@link FrameLayout} with the max width/height.
 */
public class SizeBoundingFrameLayout extends FrameLayout {
    public static final int DIMENSION_DEFAULT = -1; // unspecified

    private int mMaxWidth = DIMENSION_DEFAULT;
    private int mMaxHeight = DIMENSION_DEFAULT;

    public SizeBoundingFrameLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initFromAttributeSet(context, attrs);
    }

    public SizeBoundingFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        initFromAttributeSet(context, attrs);
    }

    public SizeBoundingFrameLayout(Context context) {
        super(context);
    }

    private void initFromAttributeSet(Context c, AttributeSet attrs) {
        TypedArray a = c.obtainStyledAttributes(attrs,
                R.styleable.SizeBoundingFrameLayout_attributes);
        mMaxWidth = a.getDimensionPixelSize(
                R.styleable.SizeBoundingFrameLayout_attributes_maxWidth, DIMENSION_DEFAULT);
        mMaxHeight = a.getDimensionPixelSize(
                R.styleable.SizeBoundingFrameLayout_attributes_maxHeight, DIMENSION_DEFAULT);
        a.recycle();
    }

    /** Set the max width.  Use {@link #DIMENSION_DEFAULT} for unspecified. */
    public void setMaxWidth(int maxWidth) {
        mMaxWidth = maxWidth;
        requestLayout();
        invalidate();
    }

    public int getMaxWidth() {
        return mMaxWidth;
    }

    /** Set the max height.  Use {@link #DIMENSION_DEFAULT} for unspecified. */
    public void setMaxHeight(int maxHeight) {
        mMaxHeight = maxHeight;
        requestLayout();
        invalidate();
    }

    public int getMaxHeight() {
        return mMaxHeight;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        // Limit the size, unless MeasureSpec.EXACTLY
        if (mMaxWidth >= 0) {
            switch (widthMode) {
                case MeasureSpec.AT_MOST:
                    widthSize = Math.min(widthSize, mMaxWidth);
                    break;
                case MeasureSpec.UNSPECIFIED:
                    widthMode = MeasureSpec.AT_MOST;
                    widthSize = mMaxWidth;
                    break;
            }
        }

        if (mMaxHeight >= 0) {
            switch (heightMode) {
                case MeasureSpec.AT_MOST:
                    heightSize = Math.min(heightSize, mMaxHeight);
                    break;
                case MeasureSpec.UNSPECIFIED:
                    heightMode = MeasureSpec.AT_MOST;
                    heightSize = mMaxHeight;
                    break;
            }
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(widthSize, widthMode),
                MeasureSpec.makeMeasureSpec(heightSize, heightMode));
    }
}
