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

package com.android.email.mail.store;

import com.android.email.FixedLengthInputStream;
import com.android.email.mail.MessagingException;
import com.android.email.mail.store.ImapResponseParser.ImapList;
import com.android.email.mail.store.ImapResponseParser.ImapResponse;
import com.android.email.mail.transport.DiscourseLogger;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * This is a series of unit tests for the ImapStore class.  These tests must be locally
 * complete - no server(s) required.
 */
@SmallTest
public class ImapResponseParserUnitTests extends AndroidTestCase {

    // TODO more comprehensive test for parsing

    /**
     * Test for parsing literal string
     */
    public void testParseLiteral() throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(
                ("* STATUS \"INBOX\" (UNSEEN 2)\r\n"
                + "100 OK STATUS completed\r\n"
                + "* STATUS {5}\r\n"
                + "INBOX (UNSEEN 10)\r\n"
                + "101 OK STATUS completed\r\n")
                .getBytes());
        ImapResponseParser parser = new ImapResponseParser(is, new DiscourseLogger(4));

        ImapResponse line1 = parser.readResponse();
        assertNull("Line 1 tag", line1.mTag);
        assertTrue("Line 1 completed", line1.completed());
        assertEquals("Line 1 count", 3, line1.size());
        Object line1list = line1.get(2);
        assertEquals("Line 1 list count", 2, ((ImapList)line1list).size());

        ImapResponse line2 = parser.readResponse();
        assertEquals("Line 2 tag", "100", line2.mTag);
        assertTrue("Line 2 completed", line2.completed());
        assertEquals("Line 2 count", 3, line2.size());

        ImapResponse line3 = parser.readResponse();
        assertNull("Line 3 tag", line3.mTag);
        assertFalse("Line 3 completed", line3.completed());
        assertEquals("Line 3 count", 2, line3.size());
        assertEquals("Line 3 word 2 class", FixedLengthInputStream.class, line3.get(1).getClass());

        line3.nailDown();
        assertEquals("Line 3 word 2 nailed down", String.class, line3.get(1).getClass());
        assertEquals("Line 3 word 2 value", "INBOX", line3.getString(1));

        ImapResponse line4 = parser.readResponse();
        assertEquals("Line 4 tag", "", line4.mTag);
        assertTrue("Line 4 completed", line4.completed());
        assertEquals("Line 4 count", 1, line4.size());

        line3.appendAll(line4);
        assertNull("Line 3-4 tag", line3.mTag);
        assertTrue("Line 3-4 completed", line3.completed());
        assertEquals("Line 3-4 count", 3, line3.size());
        assertEquals("Line 3-4 word 3 class", ImapList.class, line3.get(2).getClass());

        ImapResponse line5 = parser.readResponse();
        assertEquals("Line 5 tag", "101", line5.mTag);
        assertTrue("Line 5 completed", line5.completed());
        assertEquals("Line 5 count", 3, line5.size());
    }

    /**
     * Test for parsing expansion resp-text in OK or related responses
     */
    public void testParseResponseText() throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(
                ("101 OK STATUS completed\r\n"
                + "102 OK [APPENDUID 2 238257] APPEND completed\r\n")
                .getBytes());
        ImapResponseParser parser = new ImapResponseParser(is, new DiscourseLogger(4));

        ImapResponse line1 = parser.readResponse();
        assertEquals("101", line1.mTag);
        assertTrue(line1.completed());
        assertEquals(3, line1.size());  // "OK STATUS COMPLETED"

        ImapResponse line2 = parser.readResponse();
        assertEquals("102", line2.mTag);
        assertTrue(line2.completed());
        assertEquals(4, line2.size());  // "OK [APPENDUID 2 238257] APPEND completed"
        Object responseList = line2.get(1);
        assertEquals(3, ((ImapList)responseList).size());
    }

    /**
     * Test special parser of [ALERT] responses
     */
    public void testAlertText() throws IOException {
        ByteArrayInputStream is = new ByteArrayInputStream(
                ("* OK [AlErT] system going down\r\n"
                + "* OK [ALERT]\r\n"
                + "* OK [SOME-OTHER-TAG]\r\n")
                .getBytes());
        ImapResponseParser parser = new ImapResponseParser(is, new DiscourseLogger(4));

        ImapResponse line1 = parser.readResponse();
        assertEquals("system going down", line1.getAlertText());

        ImapResponse line2 = parser.readResponse();
        assertEquals("", line2.getAlertText());

        ImapResponse line3 = parser.readResponse();
        assertNull(line3.getAlertText());
    }

    /**
     * Test basic ImapList functionality
     * TODO: Add tests for keyed lists
     */
    public void testImapList() throws MessagingException {
        ByteArrayInputStream is = new ByteArrayInputStream("foo".getBytes());
        ImapResponseParser parser = new ImapResponseParser(is, new DiscourseLogger(4));
        ImapList list1 = parser.new ImapList();
        list1.add("foo");
        list1.add("bar");
        list1.add("20");
        list1.add(is);
        list1.add("01-Jan-2009 11:20:39 -0800");
        ImapList list2 = parser.new ImapList();
        list2.add(list1);
        // Test getString(), getStringOrNull(), getList(), getListOrNull, getNumber()
        // getLiteral(), and getDate()
        assertEquals("foo", list1.getString(0));
        assertEquals("foo", list1.getStringOrNull(0));
        assertNull(list1.getListOrNull(0));

        assertEquals("bar", list1.getString(1));
        assertEquals("bar", list1.getStringOrNull(1));
        assertNull(list1.getListOrNull(1));

        assertEquals("20", list1.getString(2));
        assertEquals("20", list1.getStringOrNull(2));
        assertEquals(20, list1.getNumber(2));

        assertNull(list1.getStringOrNull(3));
        assertNotNull(list1.getLiteral(3));

        // getDate() is removed by proguard.  (aparently it's not used.)
        // assertNotNull(list1.getDate(4));

        // Test getList() and getListOrNull() with list value
        assertEquals(list1, list2.getList(0));
        assertEquals(list1, list2.getListOrNull(0));
        assertNull(list2.getListOrNull(20));
        assertNull(list2.getStringOrNull(20));
    }
}
