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

package com.android.email;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Paint;

/**
 * Helper class to load resources.
 */
public class ResourceHelper {
    public final static int UNDEFINED_RESOURCE_ID = -1;

    private static ResourceHelper sInstance;
    private final Context mContext;
    private final Resources mResources;

    private final int[] mAccountColors;
    private final Paint[] mAccountColorPaints;
    private final TypedArray mAccountColorArray;

    private ResourceHelper(Context context) {
        mContext = context.getApplicationContext();
        mResources = mContext.getResources();

        mAccountColorArray = mResources.obtainTypedArray(R.array.combined_view_account_colors);
        mAccountColors = mResources.getIntArray(R.array.combined_view_account_colors);
        mAccountColorPaints = new Paint[mAccountColors.length];
        for (int i = 0; i < mAccountColors.length; i++) {
            Paint p = new Paint();
            p.setColor(mAccountColors[i]);
            mAccountColorPaints[i] = p;
        }
    }

    public static synchronized ResourceHelper getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ResourceHelper(context);
        }
        return sInstance;
    }

    /* package */ int getAccountColorIndex(long accountId) {
        // The account ID is 1-based, so -1.
        // Use abs so that it'd work for -1 as well.
        return Math.abs((int) ((accountId - 1) % mAccountColors.length));
    }

    /**
     * @return color for an account.
     */
    public int getAccountColor(long accountId) {
        return mAccountColors[getAccountColorIndex(accountId)];
    }

    /**
     * @return The resource ID for an account color.
     * Otherwise, {@value #UNDEFINED_RESOURCE_ID} if color was not specified via ID.
     */
    public int getAccountColorId(long accountId) {
        return mAccountColorArray.getResourceId(getAccountColorIndex(accountId),
                UNDEFINED_RESOURCE_ID);
    }

    /**
     * @return {@link Paint} equivalent to {@link #getAccountColor}.
     */
    public Paint getAccountColorPaint(long accountId) {
        return mAccountColorPaints[getAccountColorIndex(accountId)];
    }
}
