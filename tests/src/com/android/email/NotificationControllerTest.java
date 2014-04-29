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
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.Suppress;

/**
 * Test for {@link NotificationController}.
 *
 * TODO Add tests for all methods.
 */
@Suppress
public class NotificationControllerTest extends AndroidTestCase {
    private Context mProviderContext;
    private NotificationController mTarget;

    private final MockClock mMockClock = new MockClock();
    private int mRingerMode;

    /**
     * Subclass {@link NotificationController} to override un-mockable operations.
     */
    protected class NotificationControllerForTest extends NotificationController {
        NotificationControllerForTest(Context context) {
            super(context, mMockClock);
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mProviderContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(mContext);
        mTarget = new NotificationControllerForTest(mProviderContext);
    }

    // the ringtone and vibration flags are depracated and the method that we use
    // to test notification has been removed in
    // https://googleplex-android-review.googlesource.com/#/c/271237/
}
