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
import android.os.Bundle;

import com.android.emailcommon.VendorPolicyLoader;

public class MockVendorPolicy {
    public static String passedPolicy;
    public static Bundle passedBundle;
    public static Bundle mockResult;

    public static Bundle getPolicy(String policy, Bundle args) {
        passedPolicy = policy;
        passedBundle = args;
        return mockResult;
    }

    /**
     * Call it to enable {@link MockVendorPolicy}.
     */
    public static void inject(Context context) {
        VendorPolicyLoader.injectPolicyForTest(context, context.getPackageName(),
                MockVendorPolicy.class);
    }
}
