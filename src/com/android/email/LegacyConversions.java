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

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import com.android.emailcommon.Logging;
import com.android.emailcommon.internet.MimeBodyPart;
import com.android.emailcommon.internet.MimeHeader;
import com.android.emailcommon.internet.MimeMessage;
import com.android.emailcommon.internet.MimeMultipart;
import com.android.emailcommon.internet.MimeUtility;
import com.android.emailcommon.internet.TextBody;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.mail.Flag;
import com.android.emailcommon.mail.Message;
import com.android.emailcommon.mail.Message.RecipientType;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.mail.Part;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.AttachmentColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogUtils;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

public class LegacyConversions {

    /** DO NOT CHECK IN "TRUE" */
    private static final boolean DEBUG_ATTACHMENTS = false;

    /** Used for mapping folder names to type codes (e.g. inbox, drafts, trash) */
    private static final HashMap<String, Integer>
            sServerMailboxNames = new HashMap<String, Integer>();

    /**
     * Values for HEADER_ANDROID_BODY_QUOTED_PART to tag body parts
     */
    /* package */ static final String BODY_QUOTED_PART_REPLY = "quoted-reply";
    /* package */ static final String BODY_QUOTED_PART_FORWARD = "quoted-forward";
    /* package */ static final String BODY_QUOTED_PART_INTRO = "quoted-intro";

    /**
     * Copy field-by-field from a "store" message to a "provider" message
     * @param message The message we've just downloaded (must be a MimeMessage)
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
        Date internalDate = message.getInternalDate();

        if (from != null && from.length > 0) {
            localMessage.mDisplayName = from[0].toFriendly();
        }
        if (sentDate != null) {
            localMessage.mTimeStamp = sentDate.getTime();
        } else if (internalDate != null) {
            LogUtils.w(Logging.LOG_TAG, "No sentDate, falling back to internalDate");
            localMessage.mTimeStamp = internalDate.getTime();
        }
        if (subject != null) {
            localMessage.mSubject = subject;
        }
        localMessage.mFlagRead = message.isSet(Flag.SEEN);
        if (message.isSet(Flag.ANSWERED)) {
            localMessage.mFlags |= EmailContent.Message.FLAG_REPLIED_TO;
        }

        // Keep the message in the "unloaded" state until it has (at least) a display name.
        // This prevents early flickering of empty messages in POP download.
        if (localMessage.mFlagLoaded != EmailContent.Message.FLAG_LOADED_COMPLETE) {
            if (localMessage.mDisplayName == null || "".equals(localMessage.mDisplayName)) {
                localMessage.mFlagLoaded = EmailContent.Message.FLAG_LOADED_UNLOADED;
            } else {
                localMessage.mFlagLoaded = EmailContent.Message.FLAG_LOADED_PARTIAL;
            }
        }
        localMessage.mFlagFavorite = message.isSet(Flag.FLAGGED);
//        public boolean mFlagAttachment = false;
//        public int mFlags = 0;

        localMessage.mServerId = message.getUid();
        if (internalDate != null) {
            localMessage.mServerTimeStamp = internalDate.getTime();
        }
//        public String mClientId;

        // Only replace the local message-id if a new one was found.  This is seen in some ISP's
        // which may deliver messages w/o a message-id header.
        String messageId = ((MimeMessage)message).getMessageId();
        if (messageId != null) {
            localMessage.mMessageId = messageId;
        }

//        public long mBodyKey;
        localMessage.mMailboxKey = mailboxId;
        localMessage.mAccountKey = accountId;

        if (from != null && from.length > 0) {
            localMessage.mFrom = Address.pack(from);
        }

        localMessage.mTo = Address.pack(to);
        localMessage.mCc = Address.pack(cc);
        localMessage.mBcc = Address.pack(bcc);
        localMessage.mReplyTo = Address.pack(replyTo);

//        public String mText;
//        public String mHtml;
//        public String mTextReply;
//        public String mHtmlReply;

//        // Can be used while building messages, but is NOT saved by the Provider
//        transient public ArrayList<Attachment> mAttachments = null;

        return true;
    }

    /**
     * Copy attachments from MimeMessage to provider Message.
     *
     * @param context a context for file operations
     * @param localMessage the attachments will be built against this message
     * @param attachments the attachments to add
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
     * This will skip adding attachments if they are already found in the attachments table.
     * The heuristic for this will fail (false-positive) if two identical attachments are
     * included in a single POP3 message.
     * TODO: Fix that, by (elsewhere) simulating an mLocation value based on the attachments
     * position within the list of multipart/mixed elements.  This would make every POP3 attachment
     * unique, and might also simplify the code (since we could just look at the positions, and
     * ignore the filename, etc.)
     *
     * TODO: Take a closer look at encoding and deal with it if necessary.
     *
     * @param context a context for file operations
     * @param localMessage the attachments will be built against this message
     * @param part a single attachment part from POP or IMAP
     * @throws IOException
     */
    public static void addOneAttachment(Context context, EmailContent.Message localMessage,
            Part part) throws MessagingException, IOException {

        Attachment localAttachment = new Attachment();

        // Transfer fields from mime format to provider format
        String contentType = MimeUtility.unfoldAndDecode(part.getContentType());
        String name = MimeUtility.getHeaderParameter(contentType, "name");
        if (name == null) {
            String contentDisposition = MimeUtility.unfoldAndDecode(part.getDisposition());
            name = MimeUtility.getHeaderParameter(contentDisposition, "filename");
        }

        // Incoming attachment: Try to pull size from disposition (if not downloaded yet)
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
        String partId = partIds != null ? partIds[0] : null;

        // Run the mime type through inferMimeType in case we have something generic and can do
        // better using the filename extension
        String mimeType = AttachmentUtilities.inferMimeType(name, part.getMimeType());
        localAttachment.mMimeType = mimeType;
        localAttachment.mFileName = name;
        localAttachment.mSize = size;           // May be reset below if file handled
        localAttachment.mContentId = part.getContentId();
        localAttachment.setContentUri(null);     // Will be rewritten by saveAttachmentBody
        localAttachment.mMessageKey = localMessage.mId;
        localAttachment.mLocation = partId;
        localAttachment.mEncoding = "B";        // TODO - convert other known encodings
        localAttachment.mAccountKey = localMessage.mAccountKey;

        if (DEBUG_ATTACHMENTS) {
            LogUtils.d(Logging.LOG_TAG, "Add attachment " + localAttachment);
        }

        // To prevent duplication - do we already have a matching attachment?
        // The fields we'll check for equality are:
        //  mFileName, mMimeType, mContentId, mMessageKey, mLocation
        // NOTE:  This will false-positive if you attach the exact same file, twice, to a POP3
        // message.  We can live with that - you'll get one of the copies.
        Uri uri = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, localMessage.mId);
        Cursor cursor = context.getContentResolver().query(uri, Attachment.CONTENT_PROJECTION,
                null, null, null);
        boolean attachmentFoundInDb = false;
        try {
            while (cursor.moveToNext()) {
                Attachment dbAttachment = new Attachment();
                dbAttachment.restore(cursor);
                // We test each of the fields here (instead of in SQL) because they may be
                // null, or may be strings.
                if (stringNotEqual(dbAttachment.mFileName, localAttachment.mFileName)) continue;
                if (stringNotEqual(dbAttachment.mMimeType, localAttachment.mMimeType)) continue;
                if (stringNotEqual(dbAttachment.mContentId, localAttachment.mContentId)) continue;
                if (stringNotEqual(dbAttachment.mLocation, localAttachment.mLocation)) continue;
                // We found a match, so use the existing attachment id, and stop looking/looping
                attachmentFoundInDb = true;
                localAttachment.mId = dbAttachment.mId;
                if (DEBUG_ATTACHMENTS) {
                    LogUtils.d(Logging.LOG_TAG, "Skipped, found db attachment " + dbAttachment);
                }
                break;
            }
        } finally {
            cursor.close();
        }

        // Save the attachment (so far) in order to obtain an id
        if (!attachmentFoundInDb) {
            localAttachment.save(context);
        }

        // If an attachment body was actually provided, we need to write the file now
        saveAttachmentBody(context, part, localAttachment, localMessage.mAccountKey);

        if (localMessage.mAttachments == null) {
            localMessage.mAttachments = new ArrayList<Attachment>();
        }
        localMessage.mAttachments.add(localAttachment);
        localMessage.mFlagAttachment = true;
    }

    /**
     * Helper for addOneAttachment that compares two strings, deals with nulls, and treats
     * nulls and empty strings as equal.
     */
    /* package */ static boolean stringNotEqual(String a, String b) {
        if (a == null && b == null) return false;       // fast exit for two null strings
        if (a == null) a = "";
        if (b == null) b = "";
        return !a.equals(b);
    }

    /**
     * Save the body part of a single attachment, to a file in the attachments directory.
     */
    public static void saveAttachmentBody(Context context, Part part, Attachment localAttachment,
            long accountId) throws MessagingException, IOException {
        if (part.getBody() != null) {
            long attachmentId = localAttachment.mId;

            InputStream in = part.getBody().getInputStream();

            File saveIn = AttachmentUtilities.getAttachmentDirectory(context, accountId);
            if (!saveIn.exists()) {
                saveIn.mkdirs();
            }
            File saveAs = AttachmentUtilities.getAttachmentFilename(context, accountId,
                    attachmentId);
            saveAs.createNewFile();
            FileOutputStream out = new FileOutputStream(saveAs);
            long copySize = IOUtils.copy(in, out);
            in.close();
            out.close();

            // update the attachment with the extra information we now know
            String contentUriString = AttachmentUtilities.getAttachmentUri(
                    accountId, attachmentId).toString();

            localAttachment.mSize = copySize;
            localAttachment.setContentUri(contentUriString);

            // update the attachment in the database as well
            ContentValues cv = new ContentValues();
            cv.put(AttachmentColumns.SIZE, copySize);
            cv.put(AttachmentColumns.CONTENT_URI, contentUriString);
            cv.put(AttachmentColumns.UI_STATE, UIProvider.AttachmentState.SAVED);
            Uri uri = ContentUris.withAppendedId(Attachment.CONTENT_URI, attachmentId);
            context.getContentResolver().update(uri, cv, null, null);
        }
    }

    /**
     * Read a complete Provider message into a legacy message (for IMAP upload).  This
     * is basically the equivalent of LocalFolder.getMessages() + LocalFolder.fetch().
     */
    public static Message makeMessage(Context context, EmailContent.Message localMessage)
            throws MessagingException {
        MimeMessage message = new MimeMessage();

        // LocalFolder.getMessages() equivalent:  Copy message fields
        message.setSubject(localMessage.mSubject == null ? "" : localMessage.mSubject);
        Address[] from = Address.unpack(localMessage.mFrom);
        if (from.length > 0) {
            message.setFrom(from[0]);
        }
        message.setSentDate(new Date(localMessage.mTimeStamp));
        message.setUid(localMessage.mServerId);
        message.setFlag(Flag.DELETED,
                localMessage.mFlagLoaded == EmailContent.Message.FLAG_LOADED_DELETED);
        message.setFlag(Flag.SEEN, localMessage.mFlagRead);
        message.setFlag(Flag.FLAGGED, localMessage.mFlagFavorite);
//      message.setFlag(Flag.DRAFT, localMessage.mMailboxKey == draftMailboxKey);
        message.setRecipients(RecipientType.TO, Address.unpack(localMessage.mTo));
        message.setRecipients(RecipientType.CC, Address.unpack(localMessage.mCc));
        message.setRecipients(RecipientType.BCC, Address.unpack(localMessage.mBcc));
        message.setReplyTo(Address.unpack(localMessage.mReplyTo));
        message.setInternalDate(new Date(localMessage.mServerTimeStamp));
        message.setMessageId(localMessage.mMessageId);

        // LocalFolder.fetch() equivalent: build body parts
        message.setHeader(MimeHeader.HEADER_CONTENT_TYPE, "multipart/mixed");
        MimeMultipart mp = new MimeMultipart();
        mp.setSubType("mixed");
        message.setBody(mp);

        try {
            addTextBodyPart(mp, "text/html", null,
                    EmailContent.Body.restoreBodyHtmlWithMessageId(context, localMessage.mId));
        } catch (RuntimeException rte) {
            LogUtils.d(Logging.LOG_TAG, "Exception while reading html body " + rte.toString());
        }

        try {
            addTextBodyPart(mp, "text/plain", null,
                    EmailContent.Body.restoreBodyTextWithMessageId(context, localMessage.mId));
        } catch (RuntimeException rte) {
            LogUtils.d(Logging.LOG_TAG, "Exception while reading text body " + rte.toString());
        }

        boolean isReply = (localMessage.mFlags & EmailContent.Message.FLAG_TYPE_REPLY) != 0;
        boolean isForward = (localMessage.mFlags & EmailContent.Message.FLAG_TYPE_FORWARD) != 0;

        // If there is a quoted part (forwarding or reply), add the intro first, and then the
        // rest of it.  If it is opened in some other viewer, it will (hopefully) be displayed in
        // the same order as we've just set up the blocks:  composed text, intro, replied text
        if (isReply || isForward) {
            try {
                addTextBodyPart(mp, "text/plain", BODY_QUOTED_PART_INTRO,
                        EmailContent.Body.restoreIntroTextWithMessageId(context, localMessage.mId));
            } catch (RuntimeException rte) {
                LogUtils.d(Logging.LOG_TAG, "Exception while reading text reply " + rte.toString());
            }

            String replyTag = isReply ? BODY_QUOTED_PART_REPLY : BODY_QUOTED_PART_FORWARD;
            try {
                addTextBodyPart(mp, "text/html", replyTag,
                        EmailContent.Body.restoreReplyHtmlWithMessageId(context, localMessage.mId));
            } catch (RuntimeException rte) {
                LogUtils.d(Logging.LOG_TAG, "Exception while reading html reply " + rte.toString());
            }

            try {
                addTextBodyPart(mp, "text/plain", replyTag,
                        EmailContent.Body.restoreReplyTextWithMessageId(context, localMessage.mId));
            } catch (RuntimeException rte) {
                LogUtils.d(Logging.LOG_TAG, "Exception while reading text reply " + rte.toString());
            }
        }

        // Attachments
        // TODO: Make sure we deal with these as structures and don't accidentally upload files
//        Uri uri = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, localMessage.mId);
//        Cursor attachments = context.getContentResolver().query(uri, Attachment.CONTENT_PROJECTION,
//                null, null, null);
//        try {
//
//        } finally {
//            attachments.close();
//        }

        return message;
    }

    /**
     * Helper method to add a body part for a given type of text, if found
     *
     * @param mp The text body part will be added to this multipart
     * @param contentType The content-type of the text being added
     * @param quotedPartTag If non-null, HEADER_ANDROID_BODY_QUOTED_PART will be set to this value
     * @param partText The text to add.  If null, nothing happens
     */
    private static void addTextBodyPart(MimeMultipart mp, String contentType, String quotedPartTag,
            String partText) throws MessagingException {
        if (partText == null) {
            return;
        }
        TextBody body = new TextBody(partText);
        MimeBodyPart bp = new MimeBodyPart(body, contentType);
        if (quotedPartTag != null) {
            bp.addHeader(MimeHeader.HEADER_ANDROID_BODY_QUOTED_PART, quotedPartTag);
        }
        mp.addBodyPart(bp);
    }


    /**
     * Infer mailbox type from mailbox name.  Used by MessagingController (for live folder sync).
     */
    public static synchronized int inferMailboxTypeFromName(Context context, String mailboxName) {
        if (sServerMailboxNames.size() == 0) {
            // preload the hashmap, one time only
            sServerMailboxNames.put(
                    context.getString(R.string.mailbox_name_server_inbox).toLowerCase(),
                    Mailbox.TYPE_INBOX);
            sServerMailboxNames.put(
                    context.getString(R.string.mailbox_name_server_outbox).toLowerCase(),
                    Mailbox.TYPE_OUTBOX);
            sServerMailboxNames.put(
                    context.getString(R.string.mailbox_name_server_drafts).toLowerCase(),
                    Mailbox.TYPE_DRAFTS);
            sServerMailboxNames.put(
                    context.getString(R.string.mailbox_name_server_trash).toLowerCase(),
                    Mailbox.TYPE_TRASH);
            sServerMailboxNames.put(
                    context.getString(R.string.mailbox_name_server_sent).toLowerCase(),
                    Mailbox.TYPE_SENT);
            sServerMailboxNames.put(
                    context.getString(R.string.mailbox_name_server_junk).toLowerCase(),
                    Mailbox.TYPE_JUNK);
        }
        if (mailboxName == null || mailboxName.length() == 0) {
            return Mailbox.TYPE_MAIL;
        }
        String lowerCaseName = mailboxName.toLowerCase();
        Integer type = sServerMailboxNames.get(lowerCaseName);
        if (type != null) {
            return type;
        }
        return Mailbox.TYPE_MAIL;
    }
}
