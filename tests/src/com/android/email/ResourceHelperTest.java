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

import android.graphics.Paint;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.Suppress;

@Suppress
public class ResourceHelperTest extends AndroidTestCase {
    private ResourceHelper mResourceHelper;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mResourceHelper = ResourceHelper.getInstance(getContext());
    }

    public void brokentestGetAccountColor() {
        Integer lastColor = null;
        Paint lastPaint = null;

        for (long accountId = -1; accountId < 100; accountId++) {
            // Shouldn't throw any exception (such as IndexOutOfRange)
            Integer color = mResourceHelper.getAccountColor(accountId);
            Paint paint = mResourceHelper.getAccountColorPaint(accountId);

            // Should be different from the previous one
            assertNotNull(color);
            assertNotNull(paint);
            assertFalse(color.equals(lastColor));
            assertFalse(paint.equals(lastPaint));

            lastColor = color;
            lastPaint = paint;
        }
    }
}
