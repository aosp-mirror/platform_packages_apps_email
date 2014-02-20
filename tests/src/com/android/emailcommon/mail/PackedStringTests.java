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

package com.android.emailcommon.mail;

import android.test.suitebuilder.annotation.SmallTest;

import com.android.emailcommon.mail.PackedString;

import junit.framework.TestCase;

/**
 * Tests of PackedString
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.mail.PackedStringTests email
 */
@SmallTest
public class PackedStringTests extends TestCase {
    /** Note: copied from actual class */
    private static final char DELIMITER_ELEMENT = '\1';
    private static final char DELIMITER_TAG = '\2';

    // A packed string with tags and values
    private static final String PACKED_STRING_TAGGED = "val1" + DELIMITER_TAG + "tag1" +
            DELIMITER_ELEMENT + "val2" + DELIMITER_TAG + "tag2" +
            DELIMITER_ELEMENT + "val3" + DELIMITER_TAG + "tag3" +
            DELIMITER_ELEMENT + "val4" + DELIMITER_TAG + "tag4";

    public void testPackedString() {
        // Start with a packed string and make sure we can extract the correct Strings
        PackedString ps = new PackedString(PACKED_STRING_TAGGED);
        assertEquals("val1", ps.get("tag1"));
        assertEquals("val2", ps.get("tag2"));
        assertEquals("val3", ps.get("tag3"));
        assertEquals("val4", ps.get("tag4"));
        assertNull(ps.get("tag100"));
    }

    // test the builder in "create mode"
    public void testPackedStringBuilderCreate() {
        PackedString.Builder b = new PackedString.Builder();
        b.put("tag1", "value1");
        b.put("tag2", "value2");
        b.put("tag3", "value3");
        b.put("tag4", "value4");
        // can't use simple string compare on output, because order not guaranteed
        // for now, we'll just pump into another one and test results
        String packedOut = b.toString();
        PackedString.Builder b2 = new PackedString.Builder(packedOut);
        assertEquals("value1", b2.get("tag1"));
        assertEquals("value2", b2.get("tag2"));
        assertEquals("value3", b2.get("tag3"));
        assertEquals("value4", b2.get("tag4"));
        assertNull(b2.get("tag100"));
    }

    // test the builder in "edit mode"
    public void testPackedStringBuilderEdit() {
        // Start with a Builder based on a non-empty packed string
        PackedString.Builder b = new PackedString.Builder(PACKED_STRING_TAGGED);
        // Test readback in-place
        assertEquals("val1", b.get("tag1"));
        assertEquals("val2", b.get("tag2"));
        assertEquals("val3", b.get("tag3"));
        assertEquals("val4", b.get("tag4"));
        assertNull(b.get("tag100"));

        // Test modifications in-place
        b.put("tag2", "TWO");                   // edit
        b.put("tag3", null);                    // delete
        b.put("tag5", "value5");                // add
        // Read-back modifications in place
        assertEquals("val1", b.get("tag1"));
        assertEquals("TWO", b.get("tag2"));     // edited
        assertEquals(null, b.get("tag3"));      // deleted
        assertEquals("val4", b.get("tag4"));
        assertEquals("value5", b.get("tag5"));  // added
        assertNull(b.get("tag100"));

        // Confirm resulting packed string is as-expected
        String packedOut = b.toString();
        PackedString.Builder b2 = new PackedString.Builder(packedOut);
        assertEquals("val1", b2.get("tag1"));
        assertEquals("TWO", b2.get("tag2"));
        assertEquals(null, b2.get("tag3"));
        assertEquals("val4", b2.get("tag4"));
        assertEquals("value5", b2.get("tag5"));
        assertNull(b2.get("tag100"));
    }
}
