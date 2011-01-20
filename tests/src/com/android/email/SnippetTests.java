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

/**
 * Tests of Snippet
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.SnippetTests email
 */
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

    public void testStripContent() {
        assertEquals("Visible", Snippet.fromHtmlText(
            "<html><style foo=\"bar\">Not</style>Visible</html>"));
        assertEquals("Visible", Snippet.fromHtmlText(
            "<html><STYLE foo=\"bar\">Not</STYLE>Visible</html>"));
        assertEquals("IsVisible", Snippet.fromHtmlText(
            "<html><nostrip foo=\"bar\">Is</nostrip>Visible</html>"));
        assertEquals("Visible", Snippet.fromHtmlText(
            "<html>Visible<style foo=\"bar\">Not"));
        assertEquals("VisibleAgainVisible", Snippet.fromHtmlText(
            "<html>Visible<style foo=\"bar\">Not</style>AgainVisible"));
        assertEquals("VisibleAgainVisible", Snippet.fromHtmlText(
            "<html>Visible<style foo=\"bar\"/>AgainVisible"));
        assertEquals("VisibleAgainVisible", Snippet.fromHtmlText(
            "<html>Visible<style foo=\"bar\"/><head><//blah<style>Not</head>AgainVisible"));
    }

    /**
     * We pass in HTML text in which an ampersand (@) is two chars ahead of the correct end position
     * for the tag named 'tag' and then check whether the calculated end position matches the known
     * correct position.  HTML text not containing an ampersand should generate a calculated end of
     * -1
     * @param text the HTML text to test
     */
    private void findTagEnd(String text, String tag) {
        int calculatedEnd = Snippet.findTagEnd(text , tag, 0);
        int knownEnd = text.indexOf('@') + 2;
        if (knownEnd == 1) {
            // indexOf will return -1, so we'll get 1 as knownEnd
            assertEquals(-1, calculatedEnd);
        } else {
            assertEquals(calculatedEnd, knownEnd);
        }
    }

    public void testFindTagEnd() {
        // Test with <tag ... />
        findTagEnd("<tag foo=\"bar\"@ /> <blah blah>", "tag");
        // Test with <tag ...> ... </tag>
        findTagEnd("<tag foo=\"bar\">some text@</tag>some more text", "tag");
        // Test with incomplete tag
        findTagEnd("<tag foo=\"bar\">some more text but no end tag", "tag");
        // Test with space at end of tag
        findTagEnd("<tag foo=\"bar\">some more text but no end tag", "tag ");
    }

    // For debugging large HTML samples

//    private String readLargeSnippet(String fn) {
//        File file = mContext.getFileStreamPath(fn);
//        StringBuffer sb = new StringBuffer();
//        BufferedReader reader = null;
//        try {
//            String text;
//            reader = new BufferedReader(new FileReader(file));
//            while ((text = reader.readLine()) != null) {
//                sb.append(text);
//                sb.append(" ");
//            }
//        } catch (IOException e) {
//        }
//        return sb.toString();
//    }
 }
