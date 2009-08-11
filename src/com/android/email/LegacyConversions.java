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
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Part;
import com.android.email.mail.internet.MimeHeader;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.AttachmentColumns;

import org.apache.commons.io.IOUtils;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;

public class LegacyConversions {

    /**
     * Copy field-by-field from a "store" message to a "provider" message
     * @param message The message we've just downloaded
     * @param localMessage The message we'd like to write into the DB
     * @result true if dirty (changes were made)
     */
    public static boolean updateMessageFields(EmailContent.Message localMessage, Message message,
                long accountId, long mailboxId) throws MessagingException {

        Address[] from = message.getFrom();
        Address[] to = message.getRecipients(Message.RecipientType.TO);
        Address[] cc = message.getRecipients(Message.RecipientType.CC);
        Address[] bcc = message.getRecipients(Message.RecipientType.BCC);
        Address[] replyTo = message.getReplyTo();
        String subject = message.getSubject();
        Date sentDate = message.getSentDate();

        if (from != null && from.length > 0) {
            localMessage.mDisplayName = from[0].toFriendly();
        }
        if (sentDate != null) {
            localMessage.mTimeStamp = sentDate.getTime();
        }
        if (subject != null) {
            localMessage.mSubject = subject;
        }
//        public String mPreview;
//        public boolean mFlagRead = false;

        // Keep the message in the "unloaded" state until it has (at least) a display name.
        // This prevents early flickering of empty messages in POP download.
        if (localMessage.mFlagLoaded != EmailContent.Message.LOADED) {
            if (localMessage.mDisplayName == null || "".equals(localMessage.mDisplayName)) {
                localMessage.mFlagLoaded = EmailContent.Message.NOT_LOADED;
            } else {
                localMessage.mFlagLoaded = EmailContent.Message.PARTIALLY_LOADED;
            }
        }
        // TODO handle flags, favorites, and read/unread
//        public boolean mFlagFavorite = false;
//        public boolean mFlagAttachment = false;
//        public int mFlags = 0;
//
//        public String mTextInfo;
//        public String mHtmlInfo;
//
        localMessage.mServerId = message.getUid();
//        public int mServerIntId;
//        public String mClientId;
//        public String mMessageId;
//        public String mThreadId;
//
//        public long mBodyKey;
        localMessage.mMailboxKey = mailboxId;
        localMessage.mAccountKey = accountId;
//        public long mReferenceKey;
//
//        public String mSender;
        if (from != null && from.length > 0) {
            localMessage.mFrom = Address.pack(from);
        }

        localMessage.mTo = Address.pack(to);
        localMessage.mCc = Address.pack(cc);
        localMessage.mBcc = Address.pack(bcc);
        localMessage.mReplyTo = Address.pack(replyTo);

//        public String mServerVersion;
//
//        public String mText;
//        public String mHtml;
//
//        // Can be used while building messages, but is NOT saved by the Provider
//        transient public ArrayList<Attachment> mAttachments = null;

        return true;
    }

    /**
     * Copy body text (plain and/or HTML) from MimeMessage to provider Message
     *
     * TODO: Take a closer look at textInfo and deal with it if necessary.
     */
    public static boolean updateBodyFields(EmailContent.Body body,
            EmailContent.Message localMessage, ArrayList<Part> viewables)
            throws MessagingException {

        body.mMessageKey = localMessage.mId;

        StringBuffer sbHtml = new StringBuffer();
        StringBuffer sbText = new StringBuffer();
        for (Part viewable : viewables) {
            String text = MimeUtility.getTextFromPart(viewable);
            if ("text/html".equalsIgnoreCase(viewable.getMimeType())) {
                if (sbHtml.length() > 0) {
                    sbHtml.append('\n');
                }
                sbHtml.append(text);
            } else {
                if (sbText.length() > 0) {
                    sbText.append('\n');
                }
                sbText.append(text);
            }
        }

        // write the combined data to the body part
        if (sbText.length() != 0) {
            localMessage.mTextInfo = "X;X;8;" + sbText.length()*2;
            body.mTextContent = sbText.toString();
        }
        if (sbHtml.length() != 0) {
            localMessage.mHtmlInfo = "X;X;8;" + sbHtml.length()*2;
            body.mHtmlContent = sbHtml.toString();
        }
        return true;
    }

    /**
     * Copy attachments from MimeMessage to provider Message.
     *
     * @param context a context for file operations
     * @param localMessage the attachments will be built against this message
     * @param message the original message from POP or IMAP, that may have attachments
     * @return true if it succeeded
     * @throws IOException
     */
    public static void updateAttachments(Context context, EmailContent.Message localMessage,
            ArrayList<Part> attachments) throws MessagingException, IOException {
        localMessage.mAttachments = null;
        for (Part attachmentPart : attachments) {
            addOneAttachment(context, localMessage, attachmentPart);
        }
    }

    /**
     * Add a single attachment part to the message
     *
     * TODO: This will simply add to any existing attachments - could this ever happen?  If so,
     * change it to find existing attachments and delete/merge them.
     * TODO: Take a closer look at encoding and deal with it if necessary.
     *
     * @param context a context for file operations
     * @param localMessage the attachments will be built against this message
     * @param part a single attachment part from POP or IMAP
     * @return true if it succeeded
     * @throws IOException
     */
    private static void addOneAttachment(Context context, EmailContent.Message localMessage,
            Part part) throws MessagingException, IOException {

        Attachment localAttachment = new Attachment();

        // Transfer fields from mime format to provider format
        String contentType = MimeUtility.unfoldAndDecode(part.getContentType());
        String name = MimeUtility.getHeaderParameter(contentType, "name");
        if (name == null) {
            String contentDisposition = MimeUtility.unfoldAndDecode(part.getContentType());
            name = MimeUtility.getHeaderParameter(contentDisposition, "filename");
        }

        // Try to pull size from disposition (if not downloaded)
        long size = 0;
        String disposition = part.getDisposition();
        if (disposition != null) {
            String s = MimeUtility.getHeaderParameter(disposition, "size");
            if (s != null) {
                size = Long.parseLong(s);
            }
        }

        // Get partId for unloaded IMAP attachments (if any)
        // This is only provided (and used) when we have structure but not the actual attachment
        String[] partIds = part.getHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA);
        String partId = partIds != null ? partIds[0] : null;;

        localAttachment.mFileName = MimeUtility.getHeaderParameter(contentType, "name");
        localAttachment.mMimeType = part.getMimeType();
        localAttachment.mSize = size;           // May be reset below if file handled
        localAttachment.mContentId = part.getContentId();
        localAttachment.mContentUri = null;     // Will be set when file is saved
        localAttachment.mMessageKey = localMessage.mId;
        localAttachment.mLocation = partId;
        localAttachment.mEncoding = "B";        // TODO - convert other known encodings

        // Save the attachment (so far) in order to obtain an id
        localAttachment.save(context);

        // If an attachment body was actually provided, we need to write the file now
        // TODO this should be separated so it can be reused for attachment downloads
        if (part.getBody() != null) {
            long attachmentId = localAttachment.mId;
            long accountId = localMessage.mAccountKey;

            InputStream in = part.getBody().getInputStream();

            File saveIn = AttachmentProvider.getAttachmentDirectory(context, accountId);
            if (!saveIn.exists()) {
                saveIn.mkdirs();
            }
            File saveAs = AttachmentProvider.getAttachmentFilename(context, accountId,
                    attachmentId);
            saveAs.createNewFile();
            FileOutputStream out = new FileOutputStream(saveAs);
            long copySize = IOUtils.copy(in, out);
            in.close();
            out.close();

            // update the attachment with the extra information we now know
            String contentUriString = AttachmentProvider.getAttachmentUri(
                    accountId, attachmentId).toString();

            localAttachment.mSize = copySize;
            localAttachment.mContentUri = contentUriString;

            // update the attachment in the database as well
            ContentValues cv = new ContentValues();
            cv.put(AttachmentColumns.SIZE, copySize);
            cv.put(AttachmentColumns.CONTENT_URI, contentUriString);
            Uri uri = ContentUris.withAppendedId(Attachment.CONTENT_URI, attachmentId);
            context.getContentResolver().update(uri, cv, null, null);
        }

        if (localMessage.mAttachments == null) {
            localMessage.mAttachments = new ArrayList<Attachment>();
        }
        localMessage.mAttachments.add(localAttachment);
        localMessage.mFlagAttachment = true;
    }
}
