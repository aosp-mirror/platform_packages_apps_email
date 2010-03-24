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

import android.test.MoreAsserts;

import junit.framework.TestCase;

/**
 * Utility methods used only by tests.
 */
public class TestUtils extends TestCase /* It tests itself */ {
    /** Shortcut to create byte array */
    public static byte[] b(int... array) {
        if (array == null) {
            return null;
        }
        byte[] ret = new byte[array.length];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = (byte) array[i];
        }
        return ret;
    }

    public void testB() {
        assertNull(b(null));
        MoreAsserts.assertEquals(new byte[] {}, b());
        MoreAsserts.assertEquals(new byte[] {1, 2, (byte) 0xff}, b(1, 2, 0xff));
    }
}
