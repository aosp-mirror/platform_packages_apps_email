/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.Context;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.internet.MimeBodyPart;
import com.android.emailcommon.internet.MimeMessage;
import com.android.emailcommon.internet.MimeMultipart;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.mail.Message.RecipientType;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.mail.Multipart;
import com.android.emailcommon.mail.Part;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.utility.ConversionUtilities;
import com.android.emailcommon.utility.ConversionUtilities.BodyFieldData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Date;

@SmallTest
public class LegacyConversionsTest extends AndroidTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        TempDirectory.setTempDirectory(getContext());
    }

    /**
     * Test basic fields conversion from Store message to Provider message.
     */
    public void testUpdateMessageFields_Basic() throws MessagingException {
        final MimeMessage message = new MimeMessage();
        message.setUid("UID.12345678");
        message.setSentDate(new Date(1));
        message.setMessageId("Test-Message-ID");
        message.setSubject("This is the subject");

        final EmailContent.Message localMessage = new EmailContent.Message();
        final boolean result = LegacyConversions.updateMessageFields(localMessage, message, 1, 1);
        assertTrue(result);

        assertEquals(message.getUid(), localMessage.mServerId);
        assertEquals(message.getSubject(), localMessage.mSubject);
        assertEquals(message.getMessageId(), localMessage.mMessageId);
        assertEquals(message.getSentDate().getTime(), localMessage.mTimeStamp);
    }

    /**
     * Test the conversion of plain ASCII (not MIME-encoded) email addresses.
     */
    public void testUpdateMessageFields_PlainAddresses() throws MessagingException {
        // create plain ASCII email addresses
        final String fromList = "Sender <sender@droid.com>";
        final String replyToList = "Reply1 <reply1@droid.com>,Reply2 <reply2@droid.com>";
        final String toList = "ToA <toA@droid.com>,ToB <toB@droid.com>";
        final String ccList = "CcA <ccA@droid.com>,CcB <ccB@droid.com>";
        final String bccList = "BccA <bccA@droid.com>,Bcc2 <bccB@droid.com>";

        // parse the addresses
        final Address from = Address.fromHeader(fromList)[0];
        final Address[] replies = Address.fromHeader(replyToList);
        final Address[] tos = Address.fromHeader(toList);
        final Address[] ccs = Address.fromHeader(ccList);
        final Address[] bccs = Address.fromHeader(bccList);

        // make a message with the email addresses
        final MimeMessage message = new MimeMessage();
        message.setFrom(from);
        message.setReplyTo(replies);
        message.setRecipients(RecipientType.TO, tos);
        message.setRecipients(RecipientType.CC, ccs);
        message.setRecipients(RecipientType.BCC, bccs);

        // convert the message to a local message using the conversation method
        final EmailContent.Message localMessage = new EmailContent.Message();
        final boolean result = LegacyConversions.updateMessageFields(localMessage, message, 1, 1);
        assertTrue(result);

        // verify that we will store the email addresses in decoded form
        assertEquals(fromList, localMessage.mFrom);
        assertEquals(replyToList, localMessage.mReplyTo);
        assertEquals(toList, localMessage.mTo);
        assertEquals(ccList, localMessage.mCc);
        assertEquals(bccList, localMessage.mBcc);
    }

    /**
     * Test the conversion of MIME-encoded non-ASCII email addresses.
     */
    public void testUpdateMessageFields_EncodedAddresses() throws MessagingException {
        final String e = "=?EUC-KR?B?uvG50Ln4yKO4pg==?="; // Mime Encoded value of 비밀번호를
        final String d = "\uBE44\uBC00\uBC88\uD638\uB97C"; // Mime Decoded value of e

        // create the email address in encoded form
        String fromList = String.format("%s <sender@droid.com>", e);
        String replyToList = String.format("%s <reply1@droid.com>,%s <reply2@droid.com>", e, e);
        String toList = String.format("%s <toA@droid.com>,%s <toB@droid.com>", e, e);
        String ccList = String.format("%s <ccA@droid.com>,%s <ccB@droid.com>", e, e);
        String bccList = String.format("%s <bccA@droid.com>,%s <bccB@droid.com>", e, e);

        // parse the encoded addresses
        final Address from = Address.fromHeader(fromList)[0];
        final Address[] replies = Address.fromHeader(replyToList);
        final Address[] tos = Address.fromHeader(toList);
        final Address[] ccs = Address.fromHeader(ccList);
        final Address[] bccs = Address.fromHeader(bccList);

        // make a message with the email addresses
        final MimeMessage message = new MimeMessage();
        message.setFrom(from);
        message.setReplyTo(replies);
        message.setRecipients(RecipientType.TO, tos);
        message.setRecipients(RecipientType.CC, ccs);
        message.setRecipients(RecipientType.BCC, bccs);

        // convert the message to a local message using the conversion method
        final EmailContent.Message localMessage = new EmailContent.Message();
        final boolean result = LegacyConversions.updateMessageFields(localMessage, message, 1, 1);
        assertTrue(result);

        // verify that we will store the email addresses in decoded form
        String decodedFrom = String.format("%s <sender@droid.com>", d);
        String decodedReply = String.format("%s <reply1@droid.com>,%s <reply2@droid.com>", d, d);
        String decodedTo = String.format("%s <toA@droid.com>,%s <toB@droid.com>", d, d);
        String decodedCc = String.format("%s <ccA@droid.com>,%s <ccB@droid.com>", d, d);
        String decodedBcc = String.format("%s <bccA@droid.com>,%s <bccB@droid.com>", d, d);

        assertEquals(decodedFrom, localMessage.mFrom);
        assertEquals(decodedReply, localMessage.mReplyTo);
        assertEquals(decodedTo, localMessage.mTo);
        assertEquals(decodedCc, localMessage.mCc);
        assertEquals(decodedBcc, localMessage.mBcc);
    }

    /**
     * Test basic conversion from Store message to Provider message, when the provider message
     * does not have a proper message-id.
     */
    public void testUpdateMessageFields_NoMessageId() throws MessagingException {
        final MimeMessage message = new MimeMessage();
        // set, then remove the message id
        message.setMessageId("Test-Message-ID");
        message.removeHeader("Message-ID");

        // create a local message with an ID
        final EmailContent.Message localMessage = new EmailContent.Message();
        localMessage.mMessageId = "Test-Message-ID-Second";

        final boolean result = LegacyConversions.updateMessageFields(localMessage, message, 1, 1);
        assertTrue(result);
        assertEquals("Test-Message-ID-Second", localMessage.mMessageId);
    }

    /**
     * Basic test of body parts conversion from Store message to Provider message.
     * This tests that a null body part simply results in null text, and does not crash
     * or return "null".
     */
    public void testUpdateBodyFieldsNullText() throws MessagingException {
        ArrayList<Part> viewables = new ArrayList<Part>();
        viewables.add(new MimeBodyPart(null, "text/plain"));

        // a "null" body part of type text/plain should result in a null mTextContent
        final BodyFieldData data = ConversionUtilities.parseBodyFields(viewables);
        assertNull(data.textContent);
    }

    /**
     * Test adding an attachment to a message, and then parsing it back out.
     * @throws MessagingException
     */
    public void testAttachmentRoundTrip() throws Exception {
        final Context context = getContext();
        final MimeMultipart mp = new MimeMultipart();
        mp.setSubType("mixed");

        final long size;

        final File tempDir = context.getCacheDir();
        if (!tempDir.isDirectory() && !tempDir.mkdirs()) {
            fail("Could not create temporary directory");
        }

        final File tempAttachmentFile = File.createTempFile("testAttachmentRoundTrip", ".txt",
                tempDir);

        try {
            final OutputStream attOut = new FileOutputStream(tempAttachmentFile);
            try {
                attOut.write("TestData".getBytes());
            } finally {
                attOut.close();
            }
            size = tempAttachmentFile.length();
            final InputStream attIn = new FileInputStream(tempAttachmentFile);
            LegacyConversions.addAttachmentPart(mp, "text/plain", size, "test.txt",
                    "testContentId", attIn);
        } finally {
            if (!tempAttachmentFile.delete()) {
                fail("Setup failure: Could not clean up temp file");
            }
        }

        final MimeMessage outMessage = new MimeMessage();
        outMessage.setBody(mp);

        final MimeMessage inMessage;

        final File tempBodyFile = File.createTempFile("testAttachmentRoundTrip", ".eml",
                context.getCacheDir());
        try {
            final OutputStream bodyOut = new FileOutputStream(tempBodyFile);
            try {
                outMessage.writeTo(bodyOut);
            } finally {
                bodyOut.close();
            }
            final InputStream bodyIn = new FileInputStream(tempBodyFile);
            try {
                inMessage = new MimeMessage(bodyIn);
            } finally {
                bodyIn.close();
            }
        } finally {
            if (!tempBodyFile.delete()) {
                fail("Setup failure: Could not clean up temp file");
            }
        }
        final Multipart inBody = (Multipart) inMessage.getBody();
        final Part attPart = inBody.getBodyPart(0);
        final Attachment att = LegacyConversions.mimePartToAttachment(attPart);
        assertEquals(att.mFileName, "test.txt");
        assertEquals(att.mMimeType, "text/plain");
        assertEquals(att.mSize, size);
        assertEquals(att.mContentId, "testContentId");
    }
}
