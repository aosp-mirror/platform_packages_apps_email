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
 * This is a series of unit tests for snippet creation and highlighting
 *
 * You can run this entire test case with:
 *   runtest -c com.android.emailcommon.utility.TextUtilitiesTests email
 */
package com.android.emailcommon.utility;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import android.text.SpannableStringBuilder;
import android.text.style.BackgroundColorSpan;

@SmallTest
public class TextUtilitiesTests extends AndroidTestCase {

    public void testPlainSnippet() {
        // Test the simplest cases
        assertEquals("", TextUtilities.makeSnippetFromPlainText(null));
        assertEquals("", TextUtilities.makeSnippetFromPlainText(""));

        // Test handling leading, trailing, and duplicated whitespace
        // Just test common whitespace characters; we calls Character.isWhitespace() internally, so
        // other whitespace should be fine as well
        assertEquals("", TextUtilities.makeSnippetFromPlainText(" \n\r\t\r\t\n"));
        char c = TextUtilities.NON_BREAKING_SPACE_CHARACTER;
        assertEquals("foo", TextUtilities.makeSnippetFromPlainText(c + "\r\n\tfoo \n\t\r" + c));
        assertEquals("foo bar",
                TextUtilities.makeSnippetFromPlainText(c + "\r\n\tfoo \r\n bar\n\t\r" + c));

        // Handle duplicated - and =
        assertEquals("Foo-Bar=Bletch",
                TextUtilities.makeSnippetFromPlainText("Foo-----Bar=======Bletch"));

        // We shouldn't muck with HTML entities
        assertEquals("&nbsp;&gt;", TextUtilities.makeSnippetFromPlainText("&nbsp;&gt;"));
    }

    public void testHtmlSnippet() {
        // Test the simplest cases
        assertEquals("", TextUtilities.makeSnippetFromHtmlText(null));
        assertEquals("", TextUtilities.makeSnippetFromHtmlText(""));

        // Test handling leading, trailing, and duplicated whitespace
        // Just test common whitespace characters; we calls Character.isWhitespace() internally, so
        // other whitespace should be fine as well
        assertEquals("", TextUtilities.makeSnippetFromHtmlText(" \n\r\t\r\t\n"));
        char c = TextUtilities.NON_BREAKING_SPACE_CHARACTER;
        assertEquals("foo", TextUtilities.makeSnippetFromHtmlText(c + "\r\n\tfoo \n\t\r" + c));
        assertEquals("foo bar",
                TextUtilities.makeSnippetFromHtmlText(c + "\r\n\tfoo \r\n bar\n\t\r" + c));

        // Handle duplicated - and =
        assertEquals("Foo-Bar=Bletch",
                TextUtilities.makeSnippetFromPlainText("Foo-----Bar=======Bletch"));

        // We should catch HTML entities in these tests
        assertEquals(">", TextUtilities.makeSnippetFromHtmlText("&nbsp;&gt;"));
        assertEquals("&<> \"", TextUtilities.makeSnippetFromHtmlText("&amp;&lt;&gt;&nbsp;&quot;"));
        // Test for decimal and hex entities
        assertEquals("ABC", TextUtilities.makeSnippetFromHtmlText("&#65;&#66;&#67;"));
        assertEquals("ABC", TextUtilities.makeSnippetFromHtmlText("&#x41;&#x42;&#x43;"));

        // Test for stripping simple tags
        assertEquals("Hi there", TextUtilities.makeSnippetFromHtmlText("<html>Hi there</html>"));
        // TODO: Add tests here if/when we find problematic HTML
    }

    public void testStripHtmlEntityEdgeCases() {
        int[] skipCount = new int[1];
        // Bare & isn't an entity
        char c = TextUtilities.stripHtmlEntity("&", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        // Also not legal
        c = TextUtilities.stripHtmlEntity("&;", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        // This is an entity, but shouldn't be found
        c = TextUtilities.stripHtmlEntity("&nosuch;", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        // This is too long for an entity, even though it starts like a valid one
        c = TextUtilities.stripHtmlEntity("&nbspandmore;", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        // Illegal decimal entities
        c = TextUtilities.stripHtmlEntity("&#ABC", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        c = TextUtilities.stripHtmlEntity("&#12B", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        // Illegal hex entities
        c = TextUtilities.stripHtmlEntity("&#xABC", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
        // Illegal hex entities
        c = TextUtilities.stripHtmlEntity("&#x19G", 0, skipCount);
        assertEquals(c, '&');
        assertEquals(0, skipCount[0]);
    }

    public void testStripContent() {
        assertEquals("Visible", TextUtilities.makeSnippetFromHtmlText(
            "<html><style foo=\"bar\">Not</style>Visible</html>"));
        assertEquals("Visible", TextUtilities.makeSnippetFromHtmlText(
            "<html><STYLE foo=\"bar\">Not</STYLE>Visible</html>"));
        assertEquals("IsVisible", TextUtilities.makeSnippetFromHtmlText(
            "<html><nostrip foo=\"bar\">Is</nostrip>Visible</html>"));
        assertEquals("Visible", TextUtilities.makeSnippetFromHtmlText(
            "<html>Visible<style foo=\"bar\">Not"));
        assertEquals("VisibleAgainVisible", TextUtilities.makeSnippetFromHtmlText(
            "<html>Visible<style foo=\"bar\">Not</style>AgainVisible"));
        assertEquals("VisibleAgainVisible", TextUtilities.makeSnippetFromHtmlText(
            "<html>Visible<style foo=\"bar\"/>AgainVisible"));
        assertEquals("VisibleAgainVisible", TextUtilities.makeSnippetFromHtmlText(
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
        int calculatedEnd = TextUtilities.findTagEnd(text , tag, 0);
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

    private void assertHighlightUnchanged(String str) {
        assertEquals(str, TextUtilities.highlightTermsInHtml(str, null));
    }

    public void testHighlightNoTerm() {
        // With no search terms, the html should be unchanged
        assertHighlightUnchanged("<html><style foo=\"bar\">Not</style>Visible</html>");
        assertHighlightUnchanged("<html><nostrip foo=\"bar\">Is</nostrip>Visible</html>");
        assertHighlightUnchanged("<html>Visible<style foo=\"bar\">Not");
        assertHighlightUnchanged("<html>Visible<style foo=\"bar\">Not</style>AgainVisible");
        assertHighlightUnchanged("<html>Visible<style foo=\"bar\"/>AgainVisible");
        assertHighlightUnchanged(
                "<html>Visible<style foo=\"bar\"/><head><//blah<style>Not</head>AgainVisible");
    }

    public void testHighlightSingleTermHtml() {
        String str = "<html><style foo=\"bar\">Not</style>Visible</html>";
        // Test that tags aren't highlighted
        assertEquals(str, TextUtilities.highlightTermsInHtml(
                "<html><style foo=\"bar\">Not</style>Visible</html>", "style"));
        // Test that non-tags are
        assertEquals("<html><style foo=\"bar\">Not</style><span " +
                "style=\"background-color: " + TextUtilities.HIGHLIGHT_COLOR_STRING +
                "\">Visi</span>ble</html>",
                TextUtilities.highlightTermsInHtml(str, "Visi"));
        assertEquals("<html>Visible<style foo=\"bar\">Not</style>A<span" +
                " style=\"background-color: " + TextUtilities.HIGHLIGHT_COLOR_STRING +
                "\">gain</span>Visible",
                TextUtilities.highlightTermsInHtml(
                        "<html>Visible<style foo=\"bar\">Not</style>AgainVisible", "gain"));
    }

    public void brokentestHighlightSingleTermText() {
        // Sprinkle text with a few HTML characters to make sure they're ignored
        String text = "This< should be visibl>e";
        // We should find this, because search terms are case insensitive
        SpannableStringBuilder ssb =
            (SpannableStringBuilder)TextUtilities.highlightTermsInText(text, "Visi");
        BackgroundColorSpan[] spans = ssb.getSpans(0, ssb.length(), BackgroundColorSpan.class);
        assertEquals(1, spans.length);
        BackgroundColorSpan span = spans[0];
        assertEquals(text.indexOf("visi"), ssb.getSpanStart(span));
        assertEquals(text.indexOf("bl>e"), ssb.getSpanEnd(span));
        // Heh; this next test fails.. we use the search term!
        assertEquals(text, ssb.toString());

        // Multiple instances of the term
        text = "The research word should be a search result";
        ssb = (SpannableStringBuilder)TextUtilities.highlightTermsInText(text, "Search");
        spans = ssb.getSpans(0, ssb.length(), BackgroundColorSpan.class);
        assertEquals(2, spans.length);
        span = spans[0];
        assertEquals(text.indexOf("search word"), ssb.getSpanStart(span));
        assertEquals(text.indexOf(" word"), ssb.getSpanEnd(span));
        span = spans[1];
        assertEquals(text.indexOf("search result"), ssb.getSpanStart(span));
        assertEquals(text.indexOf(" result"), ssb.getSpanEnd(span));
        assertEquals(text, ssb.toString());
    }

    public void brokentestHighlightTwoTermText() {
        String text = "This should be visible";
        // We should find this, because search terms are case insensitive
        SpannableStringBuilder ssb =
            (SpannableStringBuilder)TextUtilities.highlightTermsInText(text, "visi should");
        BackgroundColorSpan[] spans = ssb.getSpans(0, ssb.length(), BackgroundColorSpan.class);
        assertEquals(2, spans.length);
        BackgroundColorSpan span = spans[0];
        assertEquals(text.indexOf("should"), ssb.getSpanStart(span));
        assertEquals(text.indexOf(" be"), ssb.getSpanEnd(span));
        span = spans[1];
        assertEquals(text.indexOf("visi"), ssb.getSpanStart(span));
        assertEquals(text.indexOf("ble"), ssb.getSpanEnd(span));
        assertEquals(text, ssb.toString());
    }

    public void brokentestHighlightDuplicateTermText() {
        String text = "This should be visible";
        // We should find this, because search terms are case insensitive
        SpannableStringBuilder ssb =
            (SpannableStringBuilder)TextUtilities.highlightTermsInText(text, "should should");
        BackgroundColorSpan[] spans = ssb.getSpans(0, ssb.length(), BackgroundColorSpan.class);
        assertEquals(1, spans.length);
        BackgroundColorSpan span = spans[0];
        assertEquals(text.indexOf("should"), ssb.getSpanStart(span));
        assertEquals(text.indexOf(" be"), ssb.getSpanEnd(span));
    }

    public void brokentestHighlightOverlapTermText() {
        String text = "This shoulder is visible";
        // We should find this, because search terms are case insensitive
        SpannableStringBuilder ssb =
            (SpannableStringBuilder)TextUtilities.highlightTermsInText(text, "should ould");
        BackgroundColorSpan[] spans = ssb.getSpans(0, ssb.length(), BackgroundColorSpan.class);
        assertEquals(1, spans.length);
        BackgroundColorSpan span = spans[0];
        assertEquals(text.indexOf("should"), ssb.getSpanStart(span));
        assertEquals(text.indexOf("er is"), ssb.getSpanEnd(span));
    }


    public void brokentestHighlightOverlapTermText2() {
        String text = "The shoulders are visible";
        // We should find this, because search terms are case insensitive
        SpannableStringBuilder ssb =
            (SpannableStringBuilder)TextUtilities.highlightTermsInText(text, "shoulder shoulders");
        BackgroundColorSpan[] spans = ssb.getSpans(0, ssb.length(), BackgroundColorSpan.class);
        assertEquals(2, spans.length);
        BackgroundColorSpan span = spans[0];
        assertEquals(text.indexOf("shoulder"), ssb.getSpanStart(span));
        assertEquals(text.indexOf("s are visible"), ssb.getSpanEnd(span));
        span = spans[1];
        // Just the 's' should be caught in the 2nd span
        assertEquals(text.indexOf("s are visible"), ssb.getSpanStart(span));
        assertEquals(text.indexOf(" are visible"), ssb.getSpanEnd(span));
        assertEquals(text, ssb.toString());
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
