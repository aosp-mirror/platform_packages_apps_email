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

import android.content.Context;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.Suppress;

import com.android.email.R;
import com.android.email.provider.EmailProvider;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.Message;

import org.apache.james.mime4j.field.Field;
import org.apache.james.mime4j.message.BodyPart;
import org.apache.james.mime4j.message.Entity;
import org.apache.james.mime4j.message.Header;
import org.apache.james.mime4j.message.Multipart;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


/**
 * Tests of the Rfc822Output (used for sending mail)
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.mail.transport.Rfc822OutputTests email
 */
@Suppress
public class Rfc822OutputTests extends ProviderTestCase2<EmailProvider> {
    private static final String SENDER = "sender@android.com";
    private static final String RECIPIENT_TO = "recipient-to@android.com";
    private static final String RECIPIENT_CC = "recipient-cc@android.com";
    private static final String SUBJECT = "This is the subject";
    private static final String REPLY_TEXT_BODY = "This is the body.  This is also the body.";
    private static final String TEXT = "Here is some new text.";

    // Full HTML document
    private static final String HTML_FULL_BODY = "<html><head><title>MyTitle</title></head>"
            + "<body bgcolor=\"#ffffff\" text=\"#000000\">"
            + "<a href=\"google.com\">test1</a></body></html>";
    private static final String HTML_FULL_RESULT = "<a href=\"google.com\">test1</a>";
    // <body/> element w/ content
    private static final String HTML_BODY_BODY =
            "<body bgcolor=\"#ffffff\" text=\"#000000\"><a href=\"google.com\">test2</a></body>";
    private static final String HTML_BODY_RESULT = "<a href=\"google.com\">test2</a>";
    // No <body/> tag; just content
    private static final String HTML_NO_BODY_BODY =
            "<a href=\"google.com\">test3</a>";
    private static final String HTML_NO_BODY_RESULT = "<a href=\"google.com\">test3</a>";

    private Context mMockContext;
    private String mForwardIntro;

    public Rfc822OutputTests () {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
        mForwardIntro = mMockContext.getString(R.string.message_compose_fwd_header_fmt, SUBJECT,
                SENDER, RECIPIENT_TO, RECIPIENT_CC);
    }

    // TODO Create more tests here.  Specifically, we should test to make sure that forward works
    // properly instead of just reply

    // TODO Write test that ensures that bcc is handled properly (i.e. sent/not send depending
    // on the flag passed to writeTo

    private Body createTestBody(Message message) {
        return Body.restoreBodyWithMessageId(mMockContext, message.mId);
    }

    /**
     * Test for buildBodyText().
     * Compare with expected values.
     */
    public void testBuildBodyTextWithForward() {
        Message msg = new Message();
        msg.mText = TEXT;
        msg.mFrom = SENDER;
        msg.mTo = RECIPIENT_TO;
        msg.mCc = RECIPIENT_CC;
        msg.mSubject = SUBJECT;
        msg.mFlags = Message.FLAG_TYPE_FORWARD;
        msg.save(mMockContext);
        Body body = createTestBody(msg);
        String[] bodyParts = Rfc822Output.buildBodyText(body, false);
        assertEquals(TEXT + mForwardIntro + REPLY_TEXT_BODY, bodyParts[0]);
    }

    public void testWriteToText() throws IOException, MessagingException {
        // Create a simple text message
        Message msg = new Message();
        msg.mText = TEXT;
        msg.mFrom = SENDER;
        // Save this away
        msg.save(mMockContext);

        // Write out an Rfc822 message
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        Rfc822Output.writeTo(mMockContext, msg, byteStream, true, false, null);

        // Get the message and create a mime4j message from it
        // We'll take advantage of its parsing capabilities
        ByteArrayInputStream messageInputStream =
            new ByteArrayInputStream(byteStream.toByteArray());
        org.apache.james.mime4j.message.Message mimeMessage =
            new org.apache.james.mime4j.message.Message(messageInputStream);

        // Make sure its structure is correct
        checkMimeVersion(mimeMessage);
        assertFalse(mimeMessage.isMultipart());
        assertEquals("text/plain", mimeMessage.getMimeType());
    }

    @SuppressWarnings("unchecked")
    public void testWriteToAlternativePart() throws IOException, MessagingException {
        // Create a message with alternative part
        Message msg = new Message();
        msg.mText = TEXT;
        msg.mFrom = SENDER;
        msg.mAttachments = new ArrayList<Attachment>();
        // Attach a meeting invitation, which needs to be sent as multipart/alternative
        Attachment att = new Attachment();
        att.mContentBytes = "__CONTENT__".getBytes("UTF-8");
        att.mFlags = Attachment.FLAG_ICS_ALTERNATIVE_PART;
        att.mMimeType = "text/calendar";
        att.mFileName = "invite.ics";
        msg.mAttachments.add(att);
        // Save this away
        msg.save(mMockContext);

        // Write out an Rfc822 message
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        Rfc822Output.writeTo(mMockContext, msg, byteStream, true, false, null);

        // Get the message and create a mime4j message from it
        // We'll take advantage of its parsing capabilities
        ByteArrayInputStream messageInputStream =
            new ByteArrayInputStream(byteStream.toByteArray());
        org.apache.james.mime4j.message.Message mimeMessage =
            new org.apache.james.mime4j.message.Message(messageInputStream);

        // Make sure its structure is correct
        checkMimeVersion(mimeMessage);
        assertTrue(mimeMessage.isMultipart());
        Header header = mimeMessage.getHeader();
        Field contentType = header.getField("content-type");
        assertTrue(contentType.getBody().contains("multipart/alternative"));
        Multipart multipart = (Multipart)mimeMessage.getBody();
        List<BodyPart> partList = multipart.getBodyParts();
        assertEquals(2, partList.size());
        Entity part = partList.get(0);
        assertEquals("text/plain", part.getMimeType());
        part = partList.get(1);
        assertEquals("text/calendar", part.getMimeType());
        header = part.getHeader();
        assertNull(header.getField("content-disposition"));
    }

    @SuppressWarnings("unchecked")
    public void testWriteToMixedPart() throws IOException, MessagingException {
        // Create a message with a mixed part
        Message msg = new Message();
        msg.mText = TEXT;
        msg.mFrom = SENDER;
        msg.mAttachments = new ArrayList<Attachment>();
        // Attach a simple html "file"
        Attachment att = new Attachment();
        att.mContentBytes = "<html>Hi</html>".getBytes("UTF-8");
        att.mMimeType = "text/html";
        att.mFileName = "test.html";
        msg.mAttachments.add(att);
        // Save this away
        msg.save(mMockContext);

        // Write out an Rfc822 message
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        Rfc822Output.writeTo(mMockContext, msg, byteStream, true, false, null);

        // Get the message and create a mime4j message from it
        // We'll take advantage of its parsing capabilities
        ByteArrayInputStream messageInputStream =
            new ByteArrayInputStream(byteStream.toByteArray());
        org.apache.james.mime4j.message.Message mimeMessage =
            new org.apache.james.mime4j.message.Message(messageInputStream);

        // Make sure its structure is correct
        checkMimeVersion(mimeMessage);
        assertTrue(mimeMessage.isMultipart());
        Header header = mimeMessage.getHeader();
        Field contentType = header.getField("content-type");
        assertTrue(contentType.getBody().contains("multipart/mixed"));
        Multipart multipart = (Multipart)mimeMessage.getBody();
        List<BodyPart> partList = multipart.getBodyParts();
        assertEquals(2, partList.size());
        Entity part = partList.get(0);
        assertEquals("text/plain", part.getMimeType());
        part = partList.get(1);
        assertEquals("text/html", part.getMimeType());
        header = part.getHeader();
        assertNotNull(header.getField("content-disposition"));
    }

    /**
     * Tests various types of HTML reply text -- with full <html/> tags,
     * with just the <body/> tags and without any surrounding tags.
     */
    public void testGetHtmlBody() {
        String actual;
        actual = Rfc822Output.getHtmlBody(HTML_FULL_BODY);
        assertEquals(HTML_FULL_RESULT, actual);
        actual = Rfc822Output.getHtmlBody(HTML_BODY_BODY);
        assertEquals(HTML_BODY_RESULT, actual);
        actual = Rfc822Output.getHtmlBody(HTML_NO_BODY_BODY);
        assertEquals(HTML_NO_BODY_RESULT, actual);
    }

    /**
     * Test the boundary digit. We modify it indirectly.
     */
    public void testBoundaryDigit() {
        // Use getBoundary() to update the boundary digit
        Rfc822Output.sBoundaryDigit = 0; // ensure it starts at a known value

        Rfc822Output.getNextBoundary();
        assertEquals(1, Rfc822Output.sBoundaryDigit);
        Rfc822Output.getNextBoundary();
        assertEquals(2, Rfc822Output.sBoundaryDigit);
        Rfc822Output.getNextBoundary();
        assertEquals(3, Rfc822Output.sBoundaryDigit);
        Rfc822Output.getNextBoundary();
        assertEquals(4, Rfc822Output.sBoundaryDigit);
        Rfc822Output.getNextBoundary();
        assertEquals(5, Rfc822Output.sBoundaryDigit);
        Rfc822Output.getNextBoundary();
        assertEquals(6, Rfc822Output.sBoundaryDigit);
        Rfc822Output.getNextBoundary();
        assertEquals(7, Rfc822Output.sBoundaryDigit);
        Rfc822Output.getNextBoundary();
        assertEquals(8, Rfc822Output.sBoundaryDigit);
        Rfc822Output.getNextBoundary();
        assertEquals(9, Rfc822Output.sBoundaryDigit);
        Rfc822Output.getNextBoundary(); // roll over
        assertEquals(0, Rfc822Output.sBoundaryDigit);
    }

    private static final int BOUNDARY_COUNT = 12;
    public void testGetNextBoundary() {
        String[] resultArray = new String[BOUNDARY_COUNT];
        for (int i = 0; i < BOUNDARY_COUNT; i++) {
            resultArray[i] = Rfc822Output.getNextBoundary();
        }
        for (int i = 0; i < BOUNDARY_COUNT; i++) {
            final String result1 = resultArray[i];
            for (int j = 0; j < BOUNDARY_COUNT; j++) {
                if (i == j) {
                    continue; // Don't verify the same result
                }
                final String result2 = resultArray[j];
                assertFalse(result1.equals(result2));
            }
        }
    }

    /**
     * Confirm that the constructed message includes "MIME-VERSION: 1.0"
     */
    private void checkMimeVersion(org.apache.james.mime4j.message.Message mimeMessage) {
        Header header = mimeMessage.getHeader();
        Field contentType = header.getField("MIME-VERSION");
        assertTrue(contentType.getBody().equals("1.0"));
    }
}
