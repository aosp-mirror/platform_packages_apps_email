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

package com.android.emailcommon.utility;

import android.test.AndroidTestCase;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.Random;
import java.util.regex.Pattern;

/**
 * Unit tests for SSLUtils.
 */
@SmallTest
public class SSLUtilsTest extends AndroidTestCase {

    String SAFE_SCHEME_PATTERN = "[a-z][a-z0-9+\\-]*";
    private void assertSchemeNameValid(String s) {
        assertTrue(Pattern.matches(SAFE_SCHEME_PATTERN, s));
    }

    public void testSchemeNameEscapeAlreadySafe() {
        // Safe names are unmodified.
        assertEquals("http", SSLUtils.escapeForSchemeName("http"));
        assertEquals("https", SSLUtils.escapeForSchemeName("https"));
        assertEquals("ftp", SSLUtils.escapeForSchemeName("ftp"));
        assertEquals("z39.50r", SSLUtils.escapeForSchemeName("z39.50r"));
        assertEquals("fake-protocol.yes", SSLUtils.escapeForSchemeName("fake-protocol.yes"));
    }

    public void testSchemeNameEscapeIsSafe() {
        // Invalid characters are escaped properly
        assertSchemeNameValid(SSLUtils.escapeForSchemeName("name with spaces"));
        assertSchemeNameValid(SSLUtils.escapeForSchemeName("odd * & characters"));
        assertSchemeNameValid(SSLUtils.escapeForSchemeName("f3v!l;891023-47 +"));
    }

    private static final char[] RANDOM_DICT = new char[] {
        'x', '.', '^', '4', ';', ' ', 'j', '#', '~', '+'
    };
    private String randomString(Random r) {
        // 5 to 15 characters
        int length = (r.nextInt() % 5) + 10;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM_DICT[Math.abs(r.nextInt()) % RANDOM_DICT.length]);
        }
        return sb.toString();
    }

    public void testSchemeNamesAreMoreOrLessUnique() {
        assertEquals(
                SSLUtils.escapeForSchemeName("name with spaces"),
                SSLUtils.escapeForSchemeName("name with spaces"));

        // As expected, all escaping is case insensitive.
        assertEquals(
                SSLUtils.escapeForSchemeName("NAME with spaces"),
                SSLUtils.escapeForSchemeName("name with spaces"));

        Random random = new Random(314159 /* seed */);
        for (int i = 0; i < 100; i++) {
            // Other strings should more or less be unique.
            String s1 = randomString(random);
            String s2 = randomString(random);
            MoreAsserts.assertNotEqual(
                    SSLUtils.escapeForSchemeName(s1),
                    SSLUtils.escapeForSchemeName(s2));
        }
    }
}

