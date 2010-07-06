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

package com.android.email.mail.transport;

import com.android.email.R;
import com.android.email.mail.MessagingException;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Message;

import org.apache.james.mime4j.field.Field;
import org.apache.james.mime4j.message.Body;
import org.apache.james.mime4j.message.Entity;
import org.apache.james.mime4j.message.Header;
import org.apache.james.mime4j.message.Multipart;

import android.content.Context;
import android.test.ProviderTestCase2;

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
public class Rfc822OutputTests extends ProviderTestCase2<EmailProvider> {
    private static final String SENDER = "sender@android.com";
    private static final String REPLYTO = "replyto@android.com";
    private static final String RECIPIENT_TO = "recipient-to@android.com";
    private static final String RECIPIENT_CC = "recipient-cc@android.com";
    private static final String RECIPIENT_BCC = "recipient-bcc@android.com";
    private static final String SUBJECT = "This is the subject";
    private static final String BODY = "This is the body.  This is also the body.";
    private static final String TEXT = "Here is some new text.";

    private Context mMockContext;
    private String mForwardIntro;
    private String mReplyIntro;
    private String mReplyBody;

    public Rfc822OutputTests () {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
        mForwardIntro = mMockContext.getString(R.string.message_compose_fwd_header_fmt, SUBJECT,
                SENDER, RECIPIENT_TO, RECIPIENT_CC);
        mReplyIntro = mMockContext.getString(R.string.message_compose_reply_header_fmt, SENDER);
        mReplyBody = mReplyIntro + ">" + BODY;
    }

    // TODO Create more tests here.  Specifically, we should test to make sure that forward works
    // properly instead of just reply

    // TODO Write test that ensures that bcc is handled properly (i.e. sent/not send depending
    // on the flag passed to writeTo

    /**
     * Test for buildBodyText().
     * Compare with expected values.
     * Also test the situation where the message has no body.
     */
    public void testBuildBodyTextWithReply() {
        // Create the least necessary; sender, flags, and the body of the reply
        Message msg = new Message();
        msg.mText = "";
        msg.mFrom = SENDER;
        msg.mFlags = Message.FLAG_TYPE_REPLY;
        msg.mTextReply = BODY;
        msg.mIntroText = mReplyIntro;
        msg.save(mMockContext);

        String body = Rfc822Output.buildBodyText(mMockContext, msg, true);
        assertEquals(mReplyBody, body);

        // Save a different message with no reply body (so we reset the id)
        msg.mId = -1;
        msg.mTextReply = null;
        msg.save(mMockContext);
        body = Rfc822Output.buildBodyText(mMockContext, msg, true);
        assertEquals(mReplyIntro, body);
    }

    /**
     * Test for buildBodyText().
     * Compare with expected values.
     * Also test the situation where the message has no body.
     */
    public void testBuildBodyTextWithoutReply() {
        // Create the least necessary; sender, flags, and the body of the reply
        Message msg = new Message();
        msg.mText = TEXT;
        msg.mFrom = SENDER;
        msg.mFlags = Message.FLAG_TYPE_REPLY;
        msg.mTextReply = BODY;
        msg.mIntroText = mReplyIntro;
        msg.save(mMockContext);

        String body = Rfc822Output.buildBodyText(mMockContext, msg, false);
        assertEquals(TEXT + mReplyIntro, body);

        // Save a different message with no reply body (so we reset the id)
        msg.mId = -1;
        msg.mTextReply = null;
        msg.save(mMockContext);
        body = Rfc822Output.buildBodyText(mMockContext, msg, false);
        assertEquals(TEXT + mReplyIntro, body);
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
        msg.mTextReply = BODY;
        msg.mIntroText = mForwardIntro;
        msg.save(mMockContext);
        String body = Rfc822Output.buildBodyText(mMockContext, msg, true);
        assertEquals(TEXT + mForwardIntro + BODY, body);
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
        Rfc822Output.writeTo(mMockContext, msg.mId, byteStream, false, false);

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
        Rfc822Output.writeTo(mMockContext, msg.mId, byteStream, false, false);

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
        List<Body> partList = multipart.getBodyParts();
        assertEquals(2, partList.size());
        Entity part = (Entity)partList.get(0);
        assertEquals("text/plain", part.getMimeType());
        part = (Entity)partList.get(1);
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
        Rfc822Output.writeTo(mMockContext, msg.mId, byteStream, false, false);

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
        List<Body> partList = multipart.getBodyParts();
        assertEquals(2, partList.size());
        Entity part = (Entity)partList.get(0);
        assertEquals("text/plain", part.getMimeType());
        part = (Entity)partList.get(1);
        assertEquals("text/html", part.getMimeType());
        header = part.getHeader();
        assertNotNull(header.getField("content-disposition"));
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
