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
import com.android.email.mail.store.ImapResponseParser.ImapList;
import com.android.email.mail.store.ImapResponseParser.ImapResponse;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.ByteArrayInputStream;

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
        ImapResponseParser parser = new ImapResponseParser(is);
        
        ImapResponse line1 = parser.readResponse();
        assertNull("Line 1 tag", line1.mTag);
        assertTrue("Line 1 completed", line1.completed());
        assertEquals("Line 1 count", 3, line1.size());
        
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
}
