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

import com.android.email.mail.Address;
import com.android.email.mail.Flag;
import com.android.email.mail.MessagingException;
import com.android.email.mail.internet.MimeHeader;
import com.android.email.mail.internet.MimeMessage;
import com.android.email.mail.Message.RecipientType;

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
    
    /**
     * Test for encoding address field.
     */
    public void testEncodingAddressField() throws MessagingException {
        Address noName1 = new Address("noname1@dom1.com");
        Address noName2 = new Address("<noname2@dom2.com>", "");
        Address simpleName = new Address("address3@dom3.org", "simple long and long long name");
        Address dquoteName = new Address("address4@dom4.org", "name,4,long long name");
        Address quotedName = new Address("bigG@dom5.net", "big \"G\"");
        Address utf16Name = new Address("<address6@co.jp>", "\"\u65E5\u672C\u8A9E\"");
        Address utf32Name = new Address("<address8@ne.jp>", "\uD834\uDF01\uD834\uDF46");
        
        MimeMessage message = new MimeMessage();
        
        message.setFrom(noName1);
        message.setRecipient(RecipientType.TO, noName2);
        message.setRecipients(RecipientType.CC, new Address[] { simpleName, dquoteName });
        message.setReplyTo(new Address[] { quotedName, utf16Name, utf32Name });
        
        String[] from = message.getHeader("From");
        String[] to = message.getHeader("To");
        String[] cc = message.getHeader("Cc");
        String[] replyTo = message.getHeader("Reply-to");
        
        assertEquals("from address count", 1, from.length); 
        assertEquals("no name 1", "noname1@dom1.com", from[0]);
        
        assertEquals("to address count", 1, to.length); 
        assertEquals("no name 2", "noname2@dom2.com", to[0]);
        
        // folded.
        assertEquals("cc address count", 1, cc.length); 
        assertEquals("simple name & double quoted name",
                "simple long and long long name <address3@dom3.org>, \"name,4,long long\r\n"
                + " name\" <address4@dom4.org>",
                cc[0]);
        
        // folded and encoded.
        assertEquals("reply-to address count", 1, replyTo.length); 
        assertEquals("quoted name & encoded name",
                "\"big \\\"G\\\"\" <bigG@dom5.net>, =?UTF-8?B?5pel5pys6Kqe?=\r\n"
                + " <address6@co.jp>, =?UTF-8?B?8J2MgfCdjYY=?= <address8@ne.jp>",
                replyTo[0]);
    }

    /**
     * Test for parsing address field.
     */
    public void testParsingAddressField() throws MessagingException {
        MimeMessage message = new MimeMessage();
        
        message.setHeader("From", "noname1@dom1.com");
        message.setHeader("To", "<noname2@dom2.com>");
        // folded.
        message.setHeader("Cc",
                "simple name <address3@dom3.org>,\r\n"
                + " \"name,4\" <address4@dom4.org>");
        // folded and encoded.
        message.setHeader("Reply-to", 
                "\"big \\\"G\\\"\" <bigG@dom5.net>,\r\n"
                + " =?UTF-8?B?5pel5pys6Kqe?=\r\n"
                + " <address6@co.jp>,\n"
                + " \"=?UTF-8?B?8J2MgfCdjYY=?=\" <address8@ne.jp>");
        
        Address[] from = message.getFrom();
        Address[] to = message.getRecipients(RecipientType.TO);
        Address[] cc = message.getRecipients(RecipientType.CC);
        Address[] replyTo = message.getReplyTo();
        
        assertEquals("from address count", 1, from.length); 
        assertEquals("no name 1 address", "noname1@dom1.com", from[0].getAddress());
        assertNull("no name 1 name", from[0].getPersonal());
        
        assertEquals("to address count", 1, to.length); 
        assertEquals("no name 2 address", "noname2@dom2.com", to[0].getAddress());
        assertNull("no name 2 name", to[0].getPersonal());

        assertEquals("cc address count", 2, cc.length); 
        assertEquals("simple name address", "address3@dom3.org", cc[0].getAddress());
        assertEquals("simple name name", "simple name", cc[0].getPersonal());
        assertEquals("double quoted name address", "address4@dom4.org", cc[1].getAddress());
        assertEquals("double quoted name name", "name,4", cc[1].getPersonal());

        assertEquals("reply-to address count", 3, replyTo.length); 
        assertEquals("quoted name address", "bigG@dom5.net", replyTo[0].getAddress());
        assertEquals("quoted name name", "big \"G\"", replyTo[0].getPersonal());
        assertEquals("utf-16 name address", "address6@co.jp", replyTo[1].getAddress());
        assertEquals("utf-16 name name", "\u65E5\u672C\u8A9E", replyTo[1].getPersonal());
        assertEquals("utf-32 name address", "address8@ne.jp", replyTo[2].getAddress());
        assertEquals("utf-32 name name", "\uD834\uDF01\uD834\uDF46", replyTo[2].getPersonal());
    }
    
    /*
     * Test setting & getting store-specific flags
     */
    public void testStoreFlags() throws MessagingException {
        MimeMessage message = new MimeMessage();

        // Message should create with no flags
        Flag[] flags = message.getFlags();
        assertEquals(0, flags.length);

        // Set a store flag
        message.setFlag(Flag.X_STORE_1, true);
        assertTrue(message.isSet(Flag.X_STORE_1));
        assertFalse(message.isSet(Flag.X_STORE_2));

        // Set another
        message.setFlag(Flag.X_STORE_2, true);
        assertTrue(message.isSet(Flag.X_STORE_1));
        assertTrue(message.isSet(Flag.X_STORE_2));

        // Set some and clear some
        message.setFlag(Flag.X_STORE_1, false);
        assertFalse(message.isSet(Flag.X_STORE_1));
        assertTrue(message.isSet(Flag.X_STORE_2));

    }

}
