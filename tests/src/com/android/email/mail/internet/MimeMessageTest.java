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
}