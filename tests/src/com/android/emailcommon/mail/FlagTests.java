/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.emailcommon.mail;

import com.android.emailcommon.mail.Flag;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * Tests of Flag enum
 */
@SmallTest
public class FlagTests extends TestCase {

    /**
     * Confirm that all flags are upper-case.  This removes the need for wasteful toUpper
     * conversions in code that uses the flags.
     */
    public void testFlagsUpperCase() {
        for (Flag flag : Flag.values()) {
            String name = flag.name();
            assertEquals(name.toUpperCase(), name);
        }
    }
}
