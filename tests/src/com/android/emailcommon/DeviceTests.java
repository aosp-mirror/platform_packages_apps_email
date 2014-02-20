/*
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

package com.android.emailcommon;

import android.content.Context;
import android.telephony.TelephonyManager;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.mail.utils.LogUtils;

@SmallTest
public class DeviceTests extends AndroidTestCase {

    public void testGetConsistentDeviceId() {
        TelephonyManager tm =
                (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) {
            LogUtils.w(Logging.LOG_TAG, "TelephonyManager not supported.  Skipping.");
            return;
        }

        // Note null is a valid return value.  But still it should be consistent.
        final String deviceId = Device.getConsistentDeviceId(getContext());
        final String deviceId2 = Device.getConsistentDeviceId(getContext());
        // Should be consistent.
        assertEquals(deviceId, deviceId2);
    }

}
