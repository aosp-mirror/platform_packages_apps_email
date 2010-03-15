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

import java.io.IOException;

import junit.framework.TestCase;

/**
 * Tests of EAS Calendar Utilities
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.utility.SimpleIcsWriterTests email
 */
public class SimpleIcsWriterTests extends TestCase {
    private final String string63Chars =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789*";
    private final String string80Chars =
        "ZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZZ12345" + "67890";
    private static final String tag11Chars = "DESCRIPTION";

    // Where our line breaks should end up
    private final String expectedFirstLineBreak =
        string63Chars.charAt(string63Chars.length() - 1) + SimpleIcsWriter.LINE_BREAK;
    private final String expectedSecondLineBreak =
        string80Chars.charAt(SimpleIcsWriter.MAX_LINE_LENGTH - 1) + SimpleIcsWriter.LINE_BREAK;

    public void testCrlf() throws IOException {
        SimpleIcsWriter w = new SimpleIcsWriter();
        w.writeTag("TAG", "A\r\nB\nC\r\nD");
        String str = w.toString();
        // Make sure \r's are stripped and that \n is turned into two chars, \ and n
        assertEquals("TAG:A\\nB\\nC\\nD\r\n", str);
    }

    public void testWriter() throws IOException {
        // Sanity test on constant strings
        assertEquals(63, string63Chars.length());
        assertEquals(80, string80Chars.length());
        // Add 1 for the colon between the tag and the value
        assertEquals(SimpleIcsWriter.MAX_LINE_LENGTH,
                tag11Chars.length() + 1 + string63Chars.length());

        SimpleIcsWriter w = new SimpleIcsWriter();
        w.writeTag(tag11Chars, string63Chars + string80Chars);

        // We should always end a tag on a new line
        assertEquals(0, w.mColumnCount);

        // Get the final string
        String str = w.toString();
        assertEquals(SimpleIcsWriter.MAX_LINE_LENGTH-1, str.indexOf(expectedFirstLineBreak));
        assertEquals(SimpleIcsWriter.MAX_LINE_LENGTH + SimpleIcsWriter.LINE_BREAK_LENGTH +
                (SimpleIcsWriter.MAX_LINE_LENGTH - 1), str.indexOf(expectedSecondLineBreak));
    }

    public void testQuoteParamValue() {
        assertNull(SimpleIcsWriter.quoteParamValue(null));
        assertEquals("\"\"", SimpleIcsWriter.quoteParamValue(""));
        assertEquals("\"a\"", SimpleIcsWriter.quoteParamValue("a"));
        assertEquals("\"\"", SimpleIcsWriter.quoteParamValue("\""));
        assertEquals("\"abc\"", SimpleIcsWriter.quoteParamValue("abc"));
        assertEquals("\"abc\"", SimpleIcsWriter.quoteParamValue("a\"b\"c"));
    }
}
