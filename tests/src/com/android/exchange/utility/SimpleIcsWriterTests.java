/* Copyright (C) 2010 The Android Open Source Project
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

package com.android.exchange.utility;

import com.android.email.Utility;

import junit.framework.TestCase;

/**
 * Test for {@link SimpleIcsWriter}.
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.utility.SimpleIcsWriterTests email
 */
public class SimpleIcsWriterTests extends TestCase {
    private static final String CRLF = "\r\n";
    private static final String UTF8_1_BYTE = "a";
    private static final String UTF8_2_BYTES = "\u00A2";
    private static final String UTF8_3_BYTES = "\u20AC";
    private static final String UTF8_4_BYTES = "\uD852\uDF62";

    /**
     * Test for {@link SimpleIcsWriter#writeTag}.  It also covers {@link SimpleIcsWriter#getBytes()}
     * and {@link SimpleIcsWriter#escapeTextValue}.
     */
    public void testWriteTag() {
        final SimpleIcsWriter ics = new SimpleIcsWriter();
        ics.writeTag("TAG1", null);
        ics.writeTag("TAG2", "");
        ics.writeTag("TAG3", "xyz");
        ics.writeTag("SUMMARY", "TEST-TEST,;\r\n\\TEST");
        ics.writeTag("SUMMARY2", "TEST-TEST,;\r\n\\TEST");
        final String actual = Utility.fromUtf8(ics.getBytes());

        assertEquals(
                "TAG3:xyz" + CRLF +
                "SUMMARY:TEST-TEST\\,\\;\\n\\\\TEST" + CRLF + // escaped
                "SUMMARY2:TEST-TEST,;\r\n\\TEST" + CRLF // not escaped
                , actual);
    }

    /**
     * Verify that: We're folding lines correctly, and we're not splitting up a UTF-8 character.
     */
    public void testWriteLine() {
        for (String last : new String[] {UTF8_1_BYTE, UTF8_2_BYTES, UTF8_3_BYTES, UTF8_4_BYTES}) {
            for (int i = 70; i < 160; i++) {
                String input = stringOfLength(i) + last;
                checkWriteLine(input);
            }
        }
    }

    /**
     * @return a String of {@code length} bytes in UTF-8.
     */
    private static String stringOfLength(int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append('0' +(i % 10));
        }
        return sb.toString();
    }

    private void checkWriteLine(String input) {
        final SimpleIcsWriter ics = new SimpleIcsWriter();
        ics.writeLine(input);
        final byte[] bytes = ics.getBytes();

        // Verify that no lines are longer than 75 bytes.
        int numBytes = 0;
        for (byte b : bytes) {
            if (b == '\r') {
                continue; // ignore
            }
            if (b == '\n') {
                assertTrue("input=" + input, numBytes <= 75);
                numBytes = 0;
                continue;
            }
            numBytes++;
        }
        assertTrue("input=" + input, numBytes <= 75);

        // If we're splitting up a UTF-8 character, fromUtf8() won't restore it correctly.
        // If it becomes the same as input, we're doing the right thing.
        final String actual = Utility.fromUtf8(bytes);
        final String unfolded = actual.replace("\r\n\t", "");
        assertEquals("input=" + input, input + "\r\n", unfolded);
    }

    public void testQuoteParamValue() {
        assertNull(SimpleIcsWriter.quoteParamValue(null));
        assertEquals("\"\"", SimpleIcsWriter.quoteParamValue(""));
        assertEquals("\"a\"", SimpleIcsWriter.quoteParamValue("a"));
        assertEquals("\"''\"", SimpleIcsWriter.quoteParamValue("\"'"));
        assertEquals("\"abc\"", SimpleIcsWriter.quoteParamValue("abc"));
        assertEquals("\"a'b'c\"", SimpleIcsWriter.quoteParamValue("a\"b\"c"));
    }
}
