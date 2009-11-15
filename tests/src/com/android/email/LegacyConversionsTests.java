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

package com.android.email;

import com.android.email.mail.Address;
import com.android.email.mail.BodyPart;
import com.android.email.mail.Flag;
import com.android.email.mail.Message;
import com.android.email.mail.MessageTestUtils;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Part;
import com.android.email.mail.Message.RecipientType;
import com.android.email.mail.MessageTestUtils.MessageBuilder;
import com.android.email.mail.MessageTestUtils.MultipartBuilder;
import com.android.email.mail.internet.MimeBodyPart;
import com.android.email.mail.internet.MimeHeader;
import com.android.email.mail.internet.MimeMessage;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.mail.internet.TextBody;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.email.provider.EmailContent.Attachment;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.test.ProviderTestCase2;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

/**
 * Tests of the Legacy Conversions code (used by MessagingController).
 * 
 * NOTE:  It would probably make sense to rewrite this using a MockProvider, instead of the
 * ProviderTestCase (which is a real provider running on a temp database).  This would be more of
 * a true "unit test".
 * 
 * You can run this entire test case with:
 *   runtest -c com.android.email.LegacyConversionsTests email
 */
public class LegacyConversionsTests extends ProviderTestCase2<EmailProvider> {

    private static final String UID = "UID.12345678";
    private static final String SENDER = "sender@android.com";
    private static final String RECIPIENT_TO = "recipient-to@android.com";
    private static final String RECIPIENT_CC = "recipient-cc@android.com";
    private static final String RECIPIENT_BCC = "recipient-bcc@android.com";
    private static final String REPLY_TO = "reply-to@android.com";
    private static final String SUBJECT = "This is the subject";
    private static final String BODY = "This is the body.  This is also the body.";
    private static final String MESSAGE_ID = "Test-Message-ID";
    private static final String MESSAGE_ID_2 = "Test-Message-ID-Second";

    EmailProvider mProvider;
    Context mProviderContext;
    Context mContext;

    public LegacyConversionsTests() {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mProviderContext = getMockContext();
        mContext = getContext();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * TODO: basic Legacy -> Provider Message conversions
     * TODO: basic Legacy -> Provider Body conversions
     * TODO: rainy day tests of all kinds
     */

    /**
     * Test basic conversion from Store message to Provider message
     *
     * TODO: Not a complete test of all fields, and some fields need special tests (e.g. flags)
     * TODO: There are many special cases in the tested function, that need to be
     * tested here as well.
     */
    public void testUpdateMessageFields() throws MessagingException {
        MimeMessage message = buildTestMessage(RECIPIENT_TO, RECIPIENT_CC, RECIPIENT_BCC,
                REPLY_TO, SENDER, SUBJECT, null);
        EmailContent.Message localMessage = new EmailContent.Message();

        boolean result = LegacyConversions.updateMessageFields(localMessage, message, 1, 1);
        assertTrue(result);
        checkProviderMessage("testUpdateMessageFields", message, localMessage);
    }

    /**
     * Test basic conversion from Store message to Provider message, when the provider message
     * does not have a proper message-id.
     */
    public void testUpdateMessageFieldsNoMessageId() throws MessagingException {
        MimeMessage message = buildTestMessage(RECIPIENT_TO, RECIPIENT_CC, RECIPIENT_BCC,
                REPLY_TO, SENDER, SUBJECT, null);
        EmailContent.Message localMessage = new EmailContent.Message();

        // If the source message-id is null, the target should be left as-is
        localMessage.mMessageId = MESSAGE_ID_2;
        message.removeHeader("Message-ID");

        boolean result = LegacyConversions.updateMessageFields(localMessage, message, 1, 1);
        assertTrue(result);
        assertEquals(MESSAGE_ID_2, localMessage.mMessageId);
    }

    /**
     * Build a lightweight Store message with simple field population
     */
    private MimeMessage buildTestMessage(String to, String cc, String bcc, String replyTo,
            String sender, String subject, String content) throws MessagingException {
        MimeMessage message = new MimeMessage();

        if (to != null) {
            Address[] addresses = Address.parse(to);
            message.setRecipients(RecipientType.TO, addresses);
        }
        if (cc != null) {
            Address[] addresses = Address.parse(cc);
            message.setRecipients(RecipientType.CC, addresses);
        }
        if (bcc != null) {
            Address[] addresses = Address.parse(bcc);
            message.setRecipients(RecipientType.BCC, addresses);
        }
        if (replyTo != null) {
            Address[] addresses = Address.parse(replyTo);
            message.setReplyTo(addresses);
        }
        if (sender != null) {
            Address[] addresses = Address.parse(sender);
            message.setFrom(Address.parse(sender)[0]);
        }
        if (subject != null) {
            message.setSubject(subject);
        }
        if (content != null) {
            TextBody body = new TextBody(content);
            message.setBody(body);
        }

        message.setUid(UID);
        message.setSentDate(new Date());
        message.setInternalDate(new Date());
        message.setMessageId(MESSAGE_ID);
        return message;
    }

    /**
     * Basic test of body parts conversion from Store message to Provider message.
     * This tests that a null body part simply results in null text, and does not crash
     * or return "null".
     *
     * TODO very incomplete, there are many permutations to be explored
     */
    public void testUpdateBodyFieldsNullText() throws MessagingException {
        EmailContent.Body localBody = new EmailContent.Body();
        EmailContent.Message localMessage = new EmailContent.Message();
        ArrayList<Part> viewables = new ArrayList<Part>();
        Part emptyTextPart = new MimeBodyPart(null, "text/plain");
        viewables.add(emptyTextPart);

        // a "null" body part of type text/plain should result in a null mTextContent
        boolean result = LegacyConversions.updateBodyFields(localBody, localMessage, viewables);
        assertTrue(result);
        assertNull(localBody.mTextContent);
    }

    /**
     * Sunny day test of adding attachments from an IMAP message.
     */
    public void testAddAttachments() throws MessagingException, IOException {
        // Prepare a local message to add the attachments to
        final long accountId = 1;
        final long mailboxId = 1;
        final EmailContent.Message localMessage = ProviderTestUtils.setupMessage(
                "local-message", accountId, mailboxId, false, true, mProviderContext);

        // Prepare a legacy message with attachments
        final Message legacyMessage = prepareLegacyMessageWithAttachments(2);

        // Now, convert from legacy to provider and see what happens
        ArrayList<Part> viewables = new ArrayList<Part>();
        ArrayList<Part> attachments = new ArrayList<Part>();
        MimeUtility.collectParts(legacyMessage, viewables, attachments);
        LegacyConversions.updateAttachments(mProviderContext, localMessage, attachments);

        // Read back all attachments for message and check field values
        Uri uri = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, localMessage.mId);
        Cursor c = mProviderContext.getContentResolver().query(uri, Attachment.CONTENT_PROJECTION,
                null, null, null);
        try {
            assertEquals(2, c.getCount());
            while (c.moveToNext()) {
                Attachment attachment = Attachment.getContent(c, Attachment.class);
                if ("101".equals(attachment.mLocation)) {
                    checkAttachment("attachment1Part", attachments.get(0), attachment);
                } else if ("102".equals(attachment.mLocation)) {
                    checkAttachment("attachment2Part", attachments.get(1), attachment);
                } else {
                    fail("Unexpected attachment with location " + attachment.mLocation);
                }
            }
        } finally {
            c.close();
        }
    }

    /**
     * Test that attachments aren't re-added in the DB.  This supports the "partial download"
     * nature of POP messages.
     */
    public void testAddDuplicateAttachments() throws MessagingException, IOException {
        // Prepare a local message to add the attachments to
        final long accountId = 1;
        final long mailboxId = 1;
        final EmailContent.Message localMessage = ProviderTestUtils.setupMessage(
                "local-message", accountId, mailboxId, false, true, mProviderContext);

        // Prepare a legacy message with attachments
        Message legacyMessage = prepareLegacyMessageWithAttachments(2);

        // Now, convert from legacy to provider and see what happens
        ArrayList<Part> viewables = new ArrayList<Part>();
        ArrayList<Part> attachments = new ArrayList<Part>();
        MimeUtility.collectParts(legacyMessage, viewables, attachments);
        LegacyConversions.updateAttachments(mProviderContext, localMessage, attachments);

        // Confirm two attachment objects created
        Uri uri = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, localMessage.mId);
        assertEquals(2, EmailContent.count(mProviderContext, uri, null, null));

        // Now add the attachments again and confirm there are still only two
        LegacyConversions.updateAttachments(mProviderContext, localMessage, attachments);
        assertEquals(2, EmailContent.count(mProviderContext, uri, null, null));

        // Now add a 3rd & 4th attachment and make sure the total is 4, not 2 or 6
        legacyMessage = prepareLegacyMessageWithAttachments(4);
        viewables = new ArrayList<Part>();
        attachments = new ArrayList<Part>();
        MimeUtility.collectParts(legacyMessage, viewables, attachments);
        LegacyConversions.updateAttachments(mProviderContext, localMessage, attachments);
        assertEquals(4, EmailContent.count(mProviderContext, uri, null, null));
    }

    /**
     * Prepare a legacy message with 1+ attachments
     */
    private static Message prepareLegacyMessageWithAttachments(int numAttachments)
            throws MessagingException {
        // First, build one or more attachment parts
        MultipartBuilder mpBuilder = new MultipartBuilder("multipart/mixed");
        for (int i = 1; i <= numAttachments; ++i) {
            BodyPart attachmentPart = MessageTestUtils.bodyPart("image/jpg", null);

            // name=attachmentN size=N00 location=10N
            attachmentPart.setHeader(MimeHeader.HEADER_CONTENT_TYPE,
                    "image/jpg;\n name=\"attachment" + i + "\"");
            attachmentPart.setHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING, "base64");
            attachmentPart.setHeader(MimeHeader.HEADER_CONTENT_DISPOSITION,
                    "attachment;\n filename=\"attachment2\";\n size=" + i + "00");
            attachmentPart.setHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA, "10" + i);

            mpBuilder.addBodyPart(attachmentPart);
        }

        // Now build a message with them
        final Message legacyMessage = new MessageBuilder()
            .setBody(new MultipartBuilder("multipart/mixed")
                     .addBodyPart(MessageTestUtils.bodyPart("text/html", null))
                     .addBodyPart(mpBuilder.buildBodyPart())
                     .build())
                .build();

        return legacyMessage;
    }

    /**
     * Test the stringInequal helper
     */
    public void testStringInequal() {
        // Pairs that are "equal"
        assertFalse(LegacyConversions.stringNotEqual(null, null));
        assertFalse(LegacyConversions.stringNotEqual(null, ""));
        assertFalse(LegacyConversions.stringNotEqual("", null));
        assertFalse(LegacyConversions.stringNotEqual("", ""));
        assertFalse(LegacyConversions.stringNotEqual("string-equal", "string-equal"));
        // Pairs that are "inequal"
        assertTrue(LegacyConversions.stringNotEqual(null, "string-inequal"));
        assertTrue(LegacyConversions.stringNotEqual("", "string-inequal"));
        assertTrue(LegacyConversions.stringNotEqual("string-inequal", null));
        assertTrue(LegacyConversions.stringNotEqual("string-inequal", ""));
        assertTrue(LegacyConversions.stringNotEqual("string-inequal-a", "string-inequal-b"));
    }

    /**
     * Compare attachment that was converted from Part (expected) to Provider Attachment (actual)
     * 
     * TODO content URI should only be set if we also saved a file
     * TODO other data encodings
     */
    private void checkAttachment(String tag, Part expected, EmailContent.Attachment actual)
            throws MessagingException {
        String contentType = MimeUtility.unfoldAndDecode(expected.getContentType());
        String expectedName = MimeUtility.getHeaderParameter(contentType, "name");
        assertEquals(tag, expectedName, actual.mFileName);
        assertEquals(tag, expected.getMimeType(), actual.mMimeType);
        String disposition = expected.getDisposition();
        String sizeString = MimeUtility.getHeaderParameter(disposition, "size");
        long expectedSize = Long.parseLong(sizeString);
        assertEquals(tag, expectedSize, actual.mSize);
        assertEquals(tag, expected.getContentId(), actual.mContentId);
        assertNull(tag, actual.mContentUri);
        assertTrue(tag, 0 != actual.mMessageKey);
        String expectedPartId =
            expected.getHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA)[0];
        assertEquals(tag, expectedPartId, actual.mLocation);
        assertEquals(tag, "B", actual.mEncoding);
    }

    /**
     * TODO: Sunny day test of adding attachments from a POP message.
     */

    /**
     * Sunny day tests of converting an original message to a legacy message
     */
    public void testMakeLegacyMessage() throws MessagingException {
        // Set up and store a message in the provider
        long account1Id = 1;
        long mailbox1Id = 1;

        // Test message 1: No body
        EmailContent.Message localMessage1 = ProviderTestUtils.setupMessage("make-legacy",
                account1Id, mailbox1Id, false, true, mProviderContext);
        Message getMessage1 = LegacyConversions.makeMessage(mProviderContext, localMessage1);
        checkLegacyMessage("no body", localMessage1, getMessage1);

        // Test message 2: Simple body
        EmailContent.Message localMessage2 = ProviderTestUtils.setupMessage("make-legacy",
                account1Id, mailbox1Id, true, false, mProviderContext);
        localMessage2.mTextReply = null;
        localMessage2.mHtmlReply = null;
        localMessage2.mIntroText = null;
        localMessage2.mFlags &= ~EmailContent.Message.FLAG_TYPE_MASK;
        localMessage2.save(mProviderContext);
        Message getMessage2 = LegacyConversions.makeMessage(mProviderContext, localMessage2);
        checkLegacyMessage("simple body", localMessage2, getMessage2);

        // Test message 3: Body + replied-to text
        EmailContent.Message localMessage3 = ProviderTestUtils.setupMessage("make-legacy",
                account1Id, mailbox1Id, true, false, mProviderContext);
        localMessage3.mFlags &= ~EmailContent.Message.FLAG_TYPE_MASK;
        localMessage3.mFlags |= EmailContent.Message.FLAG_TYPE_REPLY;
        localMessage3.save(mProviderContext);
        Message getMessage3 = LegacyConversions.makeMessage(mProviderContext, localMessage3);
        checkLegacyMessage("reply-to", localMessage3, getMessage3);

        // Test message 4: Body + forwarded text
        EmailContent.Message localMessage4 = ProviderTestUtils.setupMessage("make-legacy",
                account1Id, mailbox1Id, true, false, mProviderContext);
        localMessage4.mFlags &= ~EmailContent.Message.FLAG_TYPE_MASK;
        localMessage4.mFlags |= EmailContent.Message.FLAG_TYPE_FORWARD;
        localMessage4.save(mProviderContext);
        Message getMessage4 = LegacyConversions.makeMessage(mProviderContext, localMessage4);
        checkLegacyMessage("forwarding", localMessage4, getMessage4);
    }

    /**
     * Check equality of a pair of converted messages
     */
    private void checkProviderMessage(String tag, Message expect, EmailContent.Message actual)
            throws MessagingException {
        assertEquals(tag, expect.getUid(), actual.mServerId);
        assertEquals(tag, expect.getSubject(), actual.mSubject);
        assertEquals(tag, Address.pack(expect.getFrom()), actual.mFrom);
        assertEquals(tag, expect.getSentDate().getTime(), actual.mTimeStamp);
        assertEquals(tag, Address.pack(expect.getRecipients(RecipientType.TO)), actual.mTo);
        assertEquals(tag, Address.pack(expect.getRecipients(RecipientType.CC)), actual.mCc);
        assertEquals(tag, ((MimeMessage)expect).getMessageId(), actual.mMessageId);
        assertEquals(tag, expect.isSet(Flag.SEEN), actual.mFlagRead);
        assertEquals(tag, expect.isSet(Flag.FLAGGED), actual.mFlagFavorite);
    }

    /**
     * Check equality of a pair of converted messages
     */
    private void checkLegacyMessage(String tag, EmailContent.Message expect, Message actual)
            throws MessagingException {
        assertEquals(tag, expect.mServerId, actual.getUid());
        assertEquals(tag, expect.mServerTimeStamp, actual.getInternalDate().getTime());
        assertEquals(tag, expect.mSubject, actual.getSubject());
        assertEquals(tag, expect.mFrom, Address.pack(actual.getFrom()));
        assertEquals(tag, expect.mTimeStamp, actual.getSentDate().getTime());
        assertEquals(tag, expect.mTo, Address.pack(actual.getRecipients(RecipientType.TO)));
        assertEquals(tag, expect.mCc, Address.pack(actual.getRecipients(RecipientType.CC)));
        assertEquals(tag, expect.mBcc, Address.pack(actual.getRecipients(RecipientType.BCC)));
        assertEquals(tag, expect.mReplyTo, Address.pack(actual.getReplyTo()));
        assertEquals(tag, expect.mMessageId, ((MimeMessage)actual).getMessageId());
        // check flags
        assertEquals(tag, expect.mFlagRead, actual.isSet(Flag.SEEN));
        assertEquals(tag, expect.mFlagFavorite, actual.isSet(Flag.FLAGGED));

        // Check the body of the message
        ArrayList<Part> viewables = new ArrayList<Part>();
        ArrayList<Part> attachments = new ArrayList<Part>();
        MimeUtility.collectParts(actual, viewables, attachments);
        String get1Text = null;
        String get1Html = null;
        String get1TextReply = null;
        String get1HtmlReply = null;
        String get1TextIntro = null;
        for (Part viewable : viewables) {
            String text = MimeUtility.getTextFromPart(viewable);
            boolean isHtml = viewable.getMimeType().equalsIgnoreCase("text/html");
            String[] headers = viewable.getHeader(MimeHeader.HEADER_ANDROID_BODY_QUOTED_PART);
            if (headers != null) {
                String header = headers[0];
                boolean isReply = LegacyConversions.BODY_QUOTED_PART_REPLY.equalsIgnoreCase(header);
                boolean isFwd = LegacyConversions.BODY_QUOTED_PART_FORWARD.equalsIgnoreCase(header);
                boolean isIntro = LegacyConversions.BODY_QUOTED_PART_INTRO.equalsIgnoreCase(header);
                if (isReply || isFwd) {
                    if (isHtml) {
                        get1HtmlReply = text;
                    } else {
                        get1TextReply = text;
                    }
                } else if (isIntro) {
                    get1TextIntro = text;
                }
                // Check flags
                int replyTypeFlags = expect.mFlags & EmailContent.Message.FLAG_TYPE_MASK;
                if (isReply) {
                    assertEquals(tag, EmailContent.Message.FLAG_TYPE_REPLY, replyTypeFlags);
                }
                if (isFwd) {
                    assertEquals(tag, EmailContent.Message.FLAG_TYPE_FORWARD, replyTypeFlags);
                }
            } else {
                if (isHtml) {
                    get1Html = text;
                } else {
                    get1Text = text;
                }
            }
        }
        assertEquals(tag, expect.mText, get1Text);
        assertEquals(tag, expect.mHtml, get1Html);
        assertEquals(tag, expect.mTextReply, get1TextReply);
        assertEquals(tag, expect.mHtmlReply, get1HtmlReply);
        assertEquals(tag, expect.mIntroText, get1TextIntro);

        // TODO Check the attachments

//      cv.put("attachment_count", attachments.size());
    }
}
