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

package com.android.email.mail.internet;

import com.android.email.mail.MessagingException;
import com.android.email.mail.internet.MimeHeader;
import com.android.email.mail.internet.MimeMessage;

import android.test.suitebuilder.annotation.SmallTest;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import junit.framework.TestCase;

/**
 * This is a series of unit tests for the MimeMessage class.  These tests must be locally
 * complete - no server(s) required.
 */
@SmallTest
public class MimeMessageTest extends TestCase {
    
    /** up arrow, down arrow, left arrow, right arrow */
    private final String SHORT_UNICODE = "\u2191\u2193\u2190\u2192";
    private final String SHORT_UNICODE_ENCODED = "=?UTF-8?B?4oaR4oaT4oaQ4oaS?=";
    
    /** a string without any unicode */
    private final String SHORT_PLAIN = "abcd";
    
    /** longer unicode strings */
    private final String LONG_UNICODE_16 = SHORT_UNICODE + SHORT_UNICODE + 
            SHORT_UNICODE + SHORT_UNICODE;
    private final String LONG_UNICODE_64 = LONG_UNICODE_16 + LONG_UNICODE_16 + 
            LONG_UNICODE_16 + LONG_UNICODE_16;

    /** longer plain strings (with fold points) */
    private final String LONG_PLAIN_16 = "abcdefgh ijklmno";
    private final String LONG_PLAIN_64 = 
        LONG_PLAIN_16 + LONG_PLAIN_16 + LONG_PLAIN_16 + LONG_PLAIN_16;
    private final String LONG_PLAIN_256 = 
        LONG_PLAIN_64 + LONG_PLAIN_64 + LONG_PLAIN_64 + LONG_PLAIN_64;

    // TODO: more tests.
    
    /**
     * Confirms that setSentDate() correctly set the "Date" header of a Mime message.
     * 
     * We tries a same test twice using two locales, Locale.US and the other, since
     * MimeMessage depends on the date formatter, which may emit wrong date format
     * in the locale other than Locale.US.
     * @throws MessagingException
     * @throws ParseException
     */
    public void testSetSentDate() throws MessagingException, ParseException {
        Locale savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        doTestSetSentDate();        
        Locale.setDefault(Locale.JAPAN);
        doTestSetSentDate();        
        Locale.setDefault(savedLocale);
    }
    
    private void doTestSetSentDate() throws MessagingException, ParseException {
        // "Thu, 01 Jan 2009 09:00:00 +0000" => 1230800400000L 
        long expectedTime = 1230800400000L;
        Date date = new Date(expectedTime);
        MimeMessage message = new MimeMessage();
        message.setSentDate(date);
        String[] headers = message.getHeader("Date");
        assertEquals(1, headers.length);
        // Explicitly specify the locale so that the object does not depend on the default
        // locale.
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        
        Date result = format.parse(headers[0]);
        assertEquals(expectedTime, result.getTime());
    }
    
    /**
     * Simple tests of the new "Message-ID" header
     */
    public void testMessageId() throws MessagingException {
        
        // Test 1.  Every message gets a default and unique message-id
        MimeMessage message1 = new MimeMessage();
        MimeMessage message2 = new MimeMessage();
        String id1 = message1.getMessageId();
        String id2 = message2.getMessageId();
        assertNotNull(id1);
        assertNotNull(id2);
        assertFalse("Message-ID should be unique", id1.equals(id2));
        
        // Test 2.  Set and get using API
        final String testId1 = "test-message-id-one";
        message1.setMessageId(testId1);
        assertEquals("set and get Message-ID", testId1, message1.getMessageId());
        
        // Test 3.  Should only be one Message-ID per message
        final String testId2 = "test-message-id-two";
        message2.setMessageId(testId1);
        message2.setMessageId(testId2);
        assertEquals("set and get Message-ID", testId2, message2.getMessageId());
    }

    /**
     * Confirm getContentID() correctly works.
     */
    public void testGetContentId() throws MessagingException {
        MimeMessage message = new MimeMessage();

        // no content-id
        assertNull(message.getContentId());

        // normal case
        final String cid1 = "cid.1@android.com";
        message.setHeader(MimeHeader.HEADER_CONTENT_ID, cid1);
        assertEquals(cid1, message.getContentId());

        // surrounded by optional bracket
        message.setHeader(MimeHeader.HEADER_CONTENT_ID, "<" + cid1 + ">");
        assertEquals(cid1, message.getContentId());
    }
    
    /**
     * Confirm that setSubject() works with plain strings
     */
    public void testSetSubjectPlain() throws MessagingException {
        MimeMessage message = new MimeMessage();

        message.setSubject(SHORT_PLAIN);
        
        // test 1: readback
        assertEquals("plain subjects", SHORT_PLAIN, message.getSubject());
        
        // test 2: raw readback is not escaped
        String rawHeader = message.getFirstHeader("Subject");
        assertEquals("plain subject not encoded", -1, rawHeader.indexOf("=?"));
        
        // test 3: long subject (shouldn't fold)
        message.setSubject(LONG_PLAIN_64);
        rawHeader = message.getFirstHeader("Subject");
        String[] split = rawHeader.split("\r\n");
        assertEquals("64 shouldn't fold", 1, split.length);
        
        // test 4: very long subject (should fold)
        message.setSubject(LONG_PLAIN_256);
        rawHeader = message.getFirstHeader("Subject");
        split = rawHeader.split("\r\n");
        assertTrue("long subject should fold", split.length > 1);
        for (String s : split) {
            assertTrue("split lines max length 78", s.length() <= 76);  // 76+\r\n = 78
            String trimmed = s.trim();
            assertFalse("split lines are not encoded", trimmed.startsWith("=?"));
        }
    }
    
    /**
     * Confirm that setSubject() works with unicode strings
     */
    public void testSetSubject() throws MessagingException {
        MimeMessage message = new MimeMessage();

        message.setSubject(SHORT_UNICODE);
        
        // test 1: readback in unicode
        assertEquals("unicode readback", SHORT_UNICODE, message.getSubject());
        
        // test 2: raw readback is escaped
        String rawHeader = message.getFirstHeader("Subject");
        assertEquals("raw readback", SHORT_UNICODE_ENCODED, rawHeader);
    }
    
    /**
     * Confirm folding operations on unicode subjects
     */
    public void testSetLongSubject() throws MessagingException {
        MimeMessage message = new MimeMessage();
        
        // test 1: long unicode - readback in unicode
        message.setSubject(LONG_UNICODE_16);
        assertEquals("unicode readback 16", LONG_UNICODE_16, message.getSubject());
        
        // test 2: longer unicode (will fold)
        message.setSubject(LONG_UNICODE_64);
        assertEquals("unicode readback 64", LONG_UNICODE_64, message.getSubject());
        
        // test 3: check folding & encoding
        String rawHeader = message.getFirstHeader("Subject");
        String[] split = rawHeader.split("\r\n");
        assertTrue("long subject should fold", split.length > 1);
        for (String s : split) {
            assertTrue("split lines max length 78", s.length() <= 76);  // 76+\r\n = 78
            String trimmed = s.trim();
            assertTrue("split lines are encoded", 
                    trimmed.startsWith("=?") && trimmed.endsWith("?="));
        }
    }

}