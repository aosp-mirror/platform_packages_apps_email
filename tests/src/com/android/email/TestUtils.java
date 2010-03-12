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

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

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

    /** Converts a String from UTF-8 */
    public static String fromUtf8(byte[] b) {
        if (b == null) {
            return null;
        }
        final CharBuffer cb = Utility.UTF_8.decode(ByteBuffer.wrap(b));
        return new String(cb.array(), 0, cb.length());
    }

    public void testUtf8() {
        assertNull(fromUtf8(null));
        assertEquals("", fromUtf8(new byte[] {}));
        assertEquals("a", fromUtf8(b('a')));
        assertEquals("ABC", fromUtf8(b('A', 'B', 'C')));
        assertEquals("\u65E5\u672C\u8A9E",
                fromUtf8(b(0xE6, 0x97, 0xA5, 0xE6, 0x9C, 0xAC, 0xE8, 0xAA, 0x9E)));
    }
}
