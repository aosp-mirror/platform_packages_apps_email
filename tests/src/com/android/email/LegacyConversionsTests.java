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
import com.android.email.mail.internet.MimeHeader;
import com.android.email.mail.internet.MimeMessage;
import com.android.email.mail.internet.MimeUtility;
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
     * Sunny day test of adding attachments from an IMAP message.
     */
    public void testAddAttachments() throws MessagingException, IOException {
        // Prepare a local message to add the attachments to
        final long accountId = 1;
        final long mailboxId = 1;
        final EmailContent.Message localMessage = ProviderTestUtils.setupMessage(
                "local-message", accountId, mailboxId, false, true, mProviderContext);

        // Prepare a legacy message with attachments
        Part attachment1Part = MessageTestUtils.bodyPart("image/gif", null);
        attachment1Part.setHeader(MimeHeader.HEADER_CONTENT_TYPE,
                "image/gif;\n name=\"attachment1\"");
        attachment1Part.setHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING, "base64");
        attachment1Part.setHeader(MimeHeader.HEADER_CONTENT_DISPOSITION,
                "attachment;\n filename=\"attachment1\";\n size=100");
        attachment1Part.setHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA, "101");

        Part attachment2Part = MessageTestUtils.bodyPart("image/jpg", null);
        attachment2Part.setHeader(MimeHeader.HEADER_CONTENT_TYPE,
                "image/jpg;\n name=\"attachment2\"");
        attachment2Part.setHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING, "base64");
        attachment2Part.setHeader(MimeHeader.HEADER_CONTENT_DISPOSITION,
                "attachment;\n filename=\"attachment2\";\n size=200");
        attachment2Part.setHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA, "102");

        final Message legacyMessage = new MessageBuilder()
            .setBody(new MultipartBuilder("multipart/mixed")
                     .addBodyPart(MessageTestUtils.bodyPart("text/html", null))
                     .addBodyPart(new MultipartBuilder("multipart/mixed")
                             .addBodyPart((BodyPart)attachment1Part)
                             .addBodyPart((BodyPart)attachment2Part)
                             .buildBodyPart())
                     .build())
                .build();

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
                    checkAttachment("attachment1Part", attachment1Part, attachment);
                } else if ("102".equals(attachment.mLocation)) {
                    checkAttachment("attachment2Part", attachment2Part, attachment);
                } else {
                    fail("Unexpected attachment with location " + attachment.mLocation);
                }
            }
        } finally {
            c.close();
        }
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
     * Check equality of a pair of converted message
     */
    private void checkLegacyMessage(String tag, EmailContent.Message expect, Message actual)
            throws MessagingException {
        assertEquals(tag, expect.mServerId, actual.getUid());
        assertEquals(tag, expect.mSubject, actual.getSubject());
        assertEquals(tag, expect.mFrom, Address.pack(actual.getFrom()));
        assertEquals(tag, expect.mTimeStamp, actual.getSentDate().getTime());
        assertEquals(tag, expect.mTo, Address.pack(actual.getRecipients(RecipientType.TO)));
        assertEquals(tag, expect.mCc, Address.pack(actual.getRecipients(RecipientType.CC)));
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
