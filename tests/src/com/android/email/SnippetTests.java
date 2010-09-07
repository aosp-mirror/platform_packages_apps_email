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

/**
 * This is a series of unit tests for snippet creation
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.SnippetTests email
 */
package com.android.email;

import android.test.AndroidTestCase;

public class SnippetTests extends AndroidTestCase {

    public void testPlainSnippet() {
        // Test the simplest cases
        assertEquals("", Snippet.fromPlainText(null));
        assertEquals("", Snippet.fromPlainText(""));

        // Test handling leading, trailing, and duplicated whitespace
        // Just test common whitespace characters; we calls Character.isWhitespace() internally, so
        // other whitespace should be fine as well
        assertEquals("", Snippet.fromPlainText(" \n\r\t\r\t\n"));
        char c = Snippet.NON_BREAKING_SPACE_CHARACTER;
        assertEquals("foo", Snippet.fromPlainText(c + "\r\n\tfoo \n\t\r" + c));
        assertEquals("foo bar", Snippet.fromPlainText(c + "\r\n\tfoo \r\n bar\n\t\r" + c));

        // Handle duplicated - and =
        assertEquals("Foo-Bar=Bletch", Snippet.fromPlainText("Foo-----Bar=======Bletch"));

        // We shouldn't muck with HTML entities
        assertEquals("&nbsp;&gt;", Snippet.fromPlainText("&nbsp;&gt;"));
    }

    public void testHtmlSnippet() {
        // Test the simplest cases
        assertEquals("", Snippet.fromHtmlText(null));
        assertEquals("", Snippet.fromHtmlText(""));

        // Test handling leading, trailing, and duplicated whitespace
        // Just test common whitespace characters; we calls Character.isWhitespace() internally, so
        // other whitespace should be fine as well
        assertEquals("", Snippet.fromHtmlText(" \n\r\t\r\t\n"));
        char c = Snippet.NON_BREAKING_SPACE_CHARACTER;
        assertEquals("foo", Snippet.fromHtmlText(c + "\r\n\tfoo \n\t\r" + c));
        assertEquals("foo bar", Snippet.fromHtmlText(c + "\r\n\tfoo \r\n bar\n\t\r" + c));

        // Handle duplicated - and =
        assertEquals("Foo-Bar=Bletch", Snippet.fromPlainText("Foo-----Bar=======Bletch"));

        // We should catch HTML entities in these tests
        assertEquals(">", Snippet.fromHtmlText("&nbsp;&gt;"));
        assertEquals("&<> \"", Snippet.fromHtmlText("&amp;&lt;&gt;&nbsp;&quot;"));
        // Test for decimal and hex entities
        assertEquals("ABC", Snippet.fromHtmlText("&#65;&#66;&#67;"));
        assertEquals("ABC", Snippet.fromHtmlText("&#x41;&#x42;&#x43;"));

        // Test for stripping simple tags
        assertEquals("Hi there", Snippet.fromHtmlText("<html>Hi there</html>"));
        // TODO: Add tests here if/when we find problematic HTML
    }

    public void testStripHtmlEntityEdgeCases() {
        int[] skipCount = new int[1];
        // Bare & isn't an entity
        char c = Snippet.stripHtmlEntity("&", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        // Also not legal
        c = Snippet.stripHtmlEntity("&;", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        // This is an entity, but shouldn't be found
        c = Snippet.stripHtmlEntity("&nosuch;", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        // This is too long for an entity, even though it starts like a valid one
        c = Snippet.stripHtmlEntity("&nbspandmore;", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        // Illegal decimal entities
        c = Snippet.stripHtmlEntity("&#ABC", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        c = Snippet.stripHtmlEntity("&#12B", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        // Illegal hex entities
        c = Snippet.stripHtmlEntity("&#xABC", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        // Illegal hex entities
        c = Snippet.stripHtmlEntity("&#x19G", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
    }
 }
