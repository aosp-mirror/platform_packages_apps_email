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

package com.android.emailcommon.internet;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.mail.Flag;
import com.android.emailcommon.mail.Message.RecipientType;
import com.android.emailcommon.mail.MessagingException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * This is a series of unit tests for the MimeMessage class.  These tests must be locally
 * complete - no server(s) required.
 */
@SmallTest
public class MimeMessageTest extends AndroidTestCase {

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

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        TempDirectory.setTempDirectory(getContext());
    }

    /**
     * Confirms that setSentDate() correctly set the "Date" header of a Mime message.
     *
     * We tries a same test twice using two locales, Locale.US and the other, since
     * MimeMessage depends on the date formatter, which may emit wrong date format
     * in the locale other than Locale.US.
     * @throws MessagingException
     * @throws ParseException
     */
    @MediumTest
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

    /*
     * Test for setExtendedHeader() and getExtendedHeader()
     */
    public void testExtendedHeader() throws MessagingException {
        MimeMessage message = new MimeMessage();

        assertNull("non existent header", message.getExtendedHeader("X-Non-Existent"));

        message.setExtendedHeader("X-Header1", "value1");
        message.setExtendedHeader("X-Header2", "value2\n value3\r\n value4\r\n");
        assertEquals("simple value", "value1",
                message.getExtendedHeader("X-Header1"));
        assertEquals("multi line value", "value2 value3 value4",
                message.getExtendedHeader("X-Header2"));
        assertNull("non existent header 2", message.getExtendedHeader("X-Non-Existent"));

        message.setExtendedHeader("X-Header1", "value4");
        assertEquals("over written value", "value4", message.getExtendedHeader("X-Header1"));

        message.setExtendedHeader("X-Header1", null);
        assertNull("remove header", message.getExtendedHeader("X-Header1"));
    }

    /*
     * Test for setExtendedHeaders() and getExtendedheaders()
     */
    public void testExtendedHeaders() throws MessagingException {
        MimeMessage message = new MimeMessage();

        assertNull("new message", message.getExtendedHeaders());
        message.setExtendedHeaders(null);
        assertNull("null headers", message.getExtendedHeaders());
        message.setExtendedHeaders("");
        assertNull("empty headers", message.getExtendedHeaders());

        message.setExtendedHeaders("X-Header1: value1\r\n");
        assertEquals("header 1 value", "value1", message.getExtendedHeader("X-Header1"));
        assertEquals("header 1", "X-Header1: value1\r\n", message.getExtendedHeaders());

        message.setExtendedHeaders(null);
        message.setExtendedHeader("X-Header2", "value2");
        message.setExtendedHeader("X-Header3",  "value3\n value4\r\n value5\r\n");
        assertEquals("headers 2,3",
                "X-Header2: value2\r\n" +
                "X-Header3: value3 value4 value5\r\n",
                message.getExtendedHeaders());

        message.setExtendedHeaders(
                "X-Header3: value3 value4 value5\r\n" +
                "X-Header2: value2\r\n");
        assertEquals("header 2", "value2", message.getExtendedHeader("X-Header2"));
        assertEquals("header 3", "value3 value4 value5", message.getExtendedHeader("X-Header3"));
        assertEquals("headers 3,2",
                "X-Header3: value3 value4 value5\r\n" +
                "X-Header2: value2\r\n",
                message.getExtendedHeaders());
    }

    /*
     * Test for writeTo(), only for header part.
     * NOTE:  This test is fragile because it assumes headers will be written in a specific order
     */
    public void testWriteToHeader() throws Exception {
        MimeMessage message = new MimeMessage();

        message.setHeader("Header1", "value1");
        message.setHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA, "value2");
        message.setExtendedHeader("X-Header3", "value3");
        message.setHeader("Header4", "value4");
        message.setExtendedHeader("X-Header5", "value5");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        out.close();
        String expectedString =
                "Header1: value1\r\n" +
                "Header4: value4\r\n" +
                "Message-ID: " + message.getMessageId() + "\r\n" +
                "\r\n";
        byte[] expected = expectedString.getBytes();
        byte[] actual = out.toByteArray();
        assertEquals("output length", expected.length, actual.length);
        for (int i = 0; i < actual.length; ++i) {
            assertEquals("output byte["+i+"]", expected[i], actual[i]);
        }
    }

    /**
     * Test for parsing headers with extra whitespace and commennts.
     *
     * The lines up to Content-Type were copied directly out of RFC 2822
     * "Section A.5. White space, comments, and other oddities"
     */
    public void brokentestWhiteSpace() throws MessagingException, IOException {
        String entireMessage =
            "From: Pete(A wonderful \\) chap) <pete(his account)@silly.test(his host)>\r\n"+
            "To:A Group(Some people)\r\n"+
            "     :Chris Jones <c@(Chris's host.)public.example>,\r\n"+
            "         joe@example.org,\r\n"+
            "  John <jdoe@one.test> (my dear friend); (the end of the group)\r\n"+
            "Cc:(Empty list)(start)Undisclosed recipients  :(nobody(that I know))  ;\r\n"+
            "Date: Thu,\r\n"+
            "      13\r\n"+
            "        Feb\r\n"+
            "          1969\r\n"+
            "      23:32\r\n"+
            "               -0330 (Newfoundland Time)\r\n"+
            "Message-ID:              <testabcd.1234@silly.test>\r\n"+
            "Content-Type:                \r\n"+
            "          TEXT/hTML \r\n"+
            "       ; x-blah=\"y-blah\" ; \r\n"+
            "       CHARSET=\"us-ascii\" ; (comment)\r\n"+
            "\r\n"+
            "<html><body>Testing.</body></html>\r\n";
        MimeMessage mm = null;
        mm = new MimeMessage(new ByteArrayInputStream(
            entireMessage.getBytes("us-ascii")));
        assertTrue(mm.getMimeType(), MimeUtility.mimeTypeMatches("text/html",mm.getMimeType()));
        assertEquals(new Date(-27723480000L),mm.getSentDate());
        assertEquals("<testabcd.1234@silly.test>",mm.getMessageId());
        Address[] toAddresses = mm.getRecipients(MimeMessage.RecipientType.TO);
        assertEquals("joe@example.org", toAddresses[1].getAddress());
        assertEquals("jdoe@one.test", toAddresses[2].getAddress());


        // Note: The parentheses in the middle of email addresses are not removed.
        //assertEquals("c@public.example", toAddresses[0].getAddress());
        //assertEquals("pete@silly.test",mm.getFrom()[0].getAddress());
    }

    /**
     * Confirm parser doesn't crash when seeing "Undisclosed recipients:;".
     */
    public void testUndisclosedRecipients() throws MessagingException, IOException {
        String entireMessage =
            "To:Undisclosed recipients:;\r\n"+
            "Cc:Undisclosed recipients:;\r\n"+
            "Bcc:Undisclosed recipients:;\r\n"+
            "\r\n";
        MimeMessage mm = null;
        mm = new MimeMessage(new ByteArrayInputStream(
            entireMessage.getBytes("us-ascii")));

        assertEquals(0, mm.getRecipients(MimeMessage.RecipientType.TO).length);
        assertEquals(0, mm.getRecipients(MimeMessage.RecipientType.CC).length);
        assertEquals(0, mm.getRecipients(MimeMessage.RecipientType.BCC).length);
    }

    /**
     * Confirm parser doesn't crash when seeing invalid headers/addresses.
     */
    public void testInvalidHeaders() throws MessagingException, IOException {
        String entireMessage =
            "To:\r\n"+
            "Cc:!invalid!address!, a@b.com\r\n"+
            "Bcc:Undisclosed recipients;\r\n"+ // no colon at the end
            "invalid header\r\n"+
            "Message-ID:<testabcd.1234@silly.test>\r\n"+
            "\r\n"+
            "Testing\r\n";
        MimeMessage mm = null;
        mm = new MimeMessage(new ByteArrayInputStream(
            entireMessage.getBytes("us-ascii")));

        assertEquals(0, mm.getRecipients(MimeMessage.RecipientType.TO).length);
        assertEquals(1, mm.getRecipients(MimeMessage.RecipientType.CC).length);
        assertEquals("a@b.com", mm.getRecipients(MimeMessage.RecipientType.CC)[0].getAddress());
        assertEquals(0, mm.getRecipients(MimeMessage.RecipientType.BCC).length);
        assertEquals("<testabcd.1234@silly.test>", mm.getMessageId());
    }

    /**
     * Confirm parser w/o a message-id inhibits a local message-id from being generated
     */
    public void testParseNoMessageId() throws MessagingException, IOException {
        String entireMessage =
            "To: user@domain.com\r\n" +
            "\r\n" +
            "Testing\r\n";
        MimeMessage mm = null;
        mm = new MimeMessage(new ByteArrayInputStream(entireMessage.getBytes("us-ascii")));

        assertNull(mm.getMessageId());
    }

    /**
     * Make sure the parser accepts the "eBay style" date format.
     *
     * Messages from ebay have been seen that they use the wrong date format.
     * @see com.android.emailcommon.utility.Utility#cleanUpMimeDate
     */
    public void testEbayDate() throws MessagingException, IOException {
        String entireMessage =
            "To:a@b.com\r\n" +
            "Date:Thu, 10 Dec 09 15:08:08 GMT-0700" +
            "\r\n" +
            "\r\n";
        MimeMessage mm = null;
        mm = new MimeMessage(new ByteArrayInputStream(entireMessage.getBytes("us-ascii")));
        Date actual = mm.getSentDate();
        Date expected = new Date(Date.UTC(109, 11, 10, 15, 8, 8) + 7 * 60 * 60 * 1000);
        assertEquals(expected, actual);
    }

    // TODO more test for writeTo()
}
