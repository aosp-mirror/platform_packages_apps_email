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

import com.android.email.mail.BodyPart;
import com.android.email.mail.Message;
import com.android.email.mail.MessageTestUtils;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Part;
import com.android.email.mail.MessageTestUtils.MessageBuilder;
import com.android.email.mail.MessageTestUtils.MultipartBuilder;
import com.android.email.mail.internet.MimeHeader;
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

}
