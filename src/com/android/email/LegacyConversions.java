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
import android.text.TextUtils;

import com.android.emailcommon.Logging;
import com.android.emailcommon.internet.MimeBodyPart;
import com.android.emailcommon.internet.MimeHeader;
import com.android.emailcommon.internet.MimeMessage;
import com.android.emailcommon.internet.MimeMultipart;
import com.android.emailcommon.internet.MimeUtility;
import com.android.emailcommon.internet.TextBody;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.mail.Base64Body;
import com.android.emailcommon.mail.Flag;
import com.android.emailcommon.mail.Message;
import com.android.emailcommon.mail.Message.RecipientType;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.mail.Multipart;
import com.android.emailcommon.mail.Part;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.AttachmentColumns;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogUtils;
import com.google.common.annotations.VisibleForTesting;

import org.apache.commons.io.IOUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
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
     * Copy field-by-field from a "store" message to a "provider" message
     *
     * @param message      The message we've just downloaded (must be a MimeMessage)
     * @param localMessage The message we'd like to write into the DB
     * @return true if dirty (changes were made)
     */
    public static boolean updateMessageFields(final EmailContent.Message localMessage,
            final Message message, final long accountId, final long mailboxId)
            throws MessagingException {

        final Address[] from = message.getFrom();
        final Address[] to = message.getRecipients(Message.RecipientType.TO);
        final Address[] cc = message.getRecipients(Message.RecipientType.CC);
        final Address[] bcc = message.getRecipients(Message.RecipientType.BCC);
        final Address[] replyTo = message.getReplyTo();
        final String subject = message.getSubject();
        final Date sentDate = message.getSentDate();
        final Date internalDate = message.getInternalDate();

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
        final String messageId = message.getMessageId();
        if (messageId != null) {
            localMessage.mMessageId = messageId;
        }

//        public long mBodyKey;
        localMessage.mMailboxKey = mailboxId;
        localMessage.mAccountKey = accountId;

        if (from != null && from.length > 0) {
            localMessage.mFrom = Address.toString(from);
        }

        localMessage.mTo = Address.toString(to);
        localMessage.mCc = Address.toString(cc);
        localMessage.mBcc = Address.toString(bcc);
        localMessage.mReplyTo = Address.toString(replyTo);

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
     * @param context      a context for file operations
     * @param localMessage the attachments will be built against this message
     * @param attachments  the attachments to add
     */
    public static void updateAttachments(final Context context,
            final EmailContent.Message localMessage, final ArrayList<Part> attachments)
            throws MessagingException, IOException {
        localMessage.mAttachments = null;
        for (Part attachmentPart : attachments) {
            addOneAttachment(context, localMessage, attachmentPart);
        }
    }

    public static void updateInlineAttachments(final Context context,
            final EmailContent.Message localMessage, final ArrayList<Part> inlineAttachments)
            throws MessagingException, IOException {
        for (final Part inlinePart : inlineAttachments) {
            final String disposition = MimeUtility.getHeaderParameter(
                    MimeUtility.unfoldAndDecode(inlinePart.getDisposition()), null);
            if (!TextUtils.isEmpty(disposition)) {
                // Treat inline parts as attachments
                addOneAttachment(context, localMessage, inlinePart);
            }
        }
    }

    /**
     * Convert a MIME Part object into an Attachment object. Separated for unit testing.
     *
     * @param part MIME part object to convert
     * @return Populated Account object
     * @throws MessagingException
     */
    @VisibleForTesting
    protected static Attachment mimePartToAttachment(final Part part) throws MessagingException {
        // Transfer fields from mime format to provider format
        final String contentType = MimeUtility.unfoldAndDecode(part.getContentType());

        String name = MimeUtility.getHeaderParameter(contentType, "name");
        if (TextUtils.isEmpty(name)) {
            final String contentDisposition = MimeUtility.unfoldAndDecode(part.getDisposition());
            name = MimeUtility.getHeaderParameter(contentDisposition, "filename");
        }

        // Incoming attachment: Try to pull size from disposition (if not downloaded yet)
        long size = 0;
        final String disposition = part.getDisposition();
        if (!TextUtils.isEmpty(disposition)) {
            String s = MimeUtility.getHeaderParameter(disposition, "size");
            if (!TextUtils.isEmpty(s)) {
                try {
                    size = Long.parseLong(s);
                } catch (final NumberFormatException e) {
                    LogUtils.d(LogUtils.TAG, e, "Could not decode size \"%s\" from attachment part",
                            size);
                }
            }
        }

        // Get partId for unloaded IMAP attachments (if any)
        // This is only provided (and used) when we have structure but not the actual attachment
        final String[] partIds = part.getHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA);
        final String partId = partIds != null ? partIds[0] : null;

        final Attachment localAttachment = new Attachment();

        // Run the mime type through inferMimeType in case we have something generic and can do
        // better using the filename extension
        localAttachment.mMimeType = AttachmentUtilities.inferMimeType(name, part.getMimeType());
        localAttachment.mFileName = name;
        localAttachment.mSize = size;
        localAttachment.mContentId = part.getContentId();
        localAttachment.setContentUri(null); // Will be rewritten by saveAttachmentBody
        localAttachment.mLocation = partId;
        localAttachment.mEncoding = "B"; // TODO - convert other known encodings

        return localAttachment;
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
     * @param context      a context for file operations
     * @param localMessage the attachments will be built against this message
     * @param part         a single attachment part from POP or IMAP
     */
    public static void addOneAttachment(final Context context,
            final EmailContent.Message localMessage, final Part part)
            throws MessagingException, IOException {
        final Attachment localAttachment = mimePartToAttachment(part);
        localAttachment.mMessageKey = localMessage.mId;
        localAttachment.mAccountKey = localMessage.mAccountKey;

        if (DEBUG_ATTACHMENTS) {
            LogUtils.d(Logging.LOG_TAG, "Add attachment " + localAttachment);
        }

        // To prevent duplication - do we already have a matching attachment?
        // The fields we'll check for equality are:
        //  mFileName, mMimeType, mContentId, mMessageKey, mLocation
        // NOTE:  This will false-positive if you attach the exact same file, twice, to a POP3
        // message.  We can live with that - you'll get one of the copies.
        final Uri uri = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, localMessage.mId);
        final Cursor cursor = context.getContentResolver().query(uri, Attachment.CONTENT_PROJECTION,
                null, null, null);
        boolean attachmentFoundInDb = false;
        try {
            while (cursor.moveToNext()) {
                final Attachment dbAttachment = new Attachment();
                dbAttachment.restore(cursor);
                // We test each of the fields here (instead of in SQL) because they may be
                // null, or may be strings.
                if (!TextUtils.equals(dbAttachment.mFileName, localAttachment.mFileName) ||
                        !TextUtils.equals(dbAttachment.mMimeType, localAttachment.mMimeType) ||
                        !TextUtils.equals(dbAttachment.mContentId, localAttachment.mContentId) ||
                        !TextUtils.equals(dbAttachment.mLocation, localAttachment.mLocation)) {
                    continue;
                }
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
     * Save the body part of a single attachment, to a file in the attachments directory.
     */
    public static void saveAttachmentBody(final Context context, final Part part,
            final Attachment localAttachment, long accountId)
            throws MessagingException, IOException {
        if (part.getBody() != null) {
            final long attachmentId = localAttachment.mId;

            final File saveIn = AttachmentUtilities.getAttachmentDirectory(context, accountId);

            if (!saveIn.isDirectory() && !saveIn.mkdirs()) {
                throw new IOException("Could not create attachment directory");
            }
            final File saveAs = AttachmentUtilities.getAttachmentFilename(context, accountId,
                    attachmentId);

            InputStream in = null;
            FileOutputStream out = null;
            final long copySize;
            try {
                in = part.getBody().getInputStream();
                out = new FileOutputStream(saveAs);
                copySize = IOUtils.copyLarge(in, out);
            } finally {
                if (in != null) {
                    in.close();
                }
                if (out != null) {
                    out.close();
                }
            }

            // update the attachment with the extra information we now know
            final String contentUriString = AttachmentUtilities.getAttachmentUri(
                    accountId, attachmentId).toString();

            localAttachment.mSize = copySize;
            localAttachment.setContentUri(contentUriString);

            // update the attachment in the database as well
            final ContentValues cv = new ContentValues(3);
            cv.put(AttachmentColumns.SIZE, copySize);
            cv.put(AttachmentColumns.CONTENT_URI, contentUriString);
            cv.put(AttachmentColumns.UI_STATE, UIProvider.AttachmentState.SAVED);
            final Uri uri = ContentUris.withAppendedId(Attachment.CONTENT_URI, attachmentId);
            context.getContentResolver().update(uri, cv, null, null);
        }
    }

    /**
     * Read a complete Provider message into a legacy message (for IMAP upload).  This
     * is basically the equivalent of LocalFolder.getMessages() + LocalFolder.fetch().
     */
    public static Message makeMessage(final Context context,
            final EmailContent.Message localMessage)
            throws MessagingException {
        final MimeMessage message = new MimeMessage();

        // LocalFolder.getMessages() equivalent:  Copy message fields
        message.setSubject(localMessage.mSubject == null ? "" : localMessage.mSubject);
        final Address[] from = Address.fromHeader(localMessage.mFrom);
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
        message.setRecipients(RecipientType.TO, Address.fromHeader(localMessage.mTo));
        message.setRecipients(RecipientType.CC, Address.fromHeader(localMessage.mCc));
        message.setRecipients(RecipientType.BCC, Address.fromHeader(localMessage.mBcc));
        message.setReplyTo(Address.fromHeader(localMessage.mReplyTo));
        message.setInternalDate(new Date(localMessage.mServerTimeStamp));
        message.setMessageId(localMessage.mMessageId);

        // LocalFolder.fetch() equivalent: build body parts
        message.setHeader(MimeHeader.HEADER_CONTENT_TYPE, "multipart/mixed");
        final MimeMultipart mp = new MimeMultipart();
        mp.setSubType("mixed");
        message.setBody(mp);

        try {
            addTextBodyPart(mp, "text/html",
                    EmailContent.Body.restoreBodyHtmlWithMessageId(context, localMessage.mId));
        } catch (RuntimeException rte) {
            LogUtils.d(Logging.LOG_TAG, "Exception while reading html body " + rte.toString());
        }

        try {
            addTextBodyPart(mp, "text/plain",
                    EmailContent.Body.restoreBodyTextWithMessageId(context, localMessage.mId));
        } catch (RuntimeException rte) {
            LogUtils.d(Logging.LOG_TAG, "Exception while reading text body " + rte.toString());
        }

        // Attachments
        final Uri uri = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, localMessage.mId);
        final Cursor attachments =
                context.getContentResolver().query(uri, Attachment.CONTENT_PROJECTION,
                        null, null, null);

        try {
            while (attachments != null && attachments.moveToNext()) {
                final Attachment att = new Attachment();
                att.restore(attachments);
                try {
                    final InputStream content;
                    if (att.mContentBytes != null) {
                        // This is generally only the case for synthetic attachments, such as those
                        // generated by unit tests or calendar invites
                        content = new ByteArrayInputStream(att.mContentBytes);
                    } else {
                        String contentUriString = att.getCachedFileUri();
                        if (TextUtils.isEmpty(contentUriString)) {
                            contentUriString = att.getContentUri();
                        }
                        if (TextUtils.isEmpty(contentUriString)) {
                            content = null;
                        } else {
                            final Uri contentUri = Uri.parse(contentUriString);
                            content = context.getContentResolver().openInputStream(contentUri);
                        }
                    }
                    final String mimeType = att.mMimeType;
                    final Long contentSize = att.mSize;
                    final String contentId = att.mContentId;
                    final String filename = att.mFileName;
                    if (content != null) {
                        addAttachmentPart(mp, mimeType, contentSize, filename, contentId, content);
                    } else {
                        LogUtils.e(LogUtils.TAG, "Could not open attachment file for upsync");
                    }
                } catch (final FileNotFoundException e) {
                    LogUtils.e(LogUtils.TAG, "File Not Found error on %s while upsyncing message",
                            att.getCachedFileUri());
                }
            }
        } finally {
            if (attachments != null) {
                attachments.close();
            }
        }

        return message;
    }

    /**
     * Helper method to add a body part for a given type of text, if found
     *
     * @param mp          The text body part will be added to this multipart
     * @param contentType The content-type of the text being added
     * @param partText    The text to add.  If null, nothing happens
     */
    private static void addTextBodyPart(final MimeMultipart mp, final String contentType,
            final String partText)
            throws MessagingException {
        if (partText == null) {
            return;
        }
        final TextBody body = new TextBody(partText);
        final MimeBodyPart bp = new MimeBodyPart(body, contentType);
        mp.addBodyPart(bp);
    }

    /**
     * Helper method to add an attachment part
     *
     * @param mp          Multipart message to append attachment part to
     * @param contentType Mime type
     * @param contentSize Attachment metadata: unencoded file size
     * @param filename    Attachment metadata: file name
     * @param contentId   as referenced from cid: uris in the message body (if applicable)
     * @param content     unencoded bytes
     */
    @VisibleForTesting
    protected static void addAttachmentPart(final Multipart mp, final String contentType,
            final Long contentSize, final String filename, final String contentId,
            final InputStream content) throws MessagingException {
        final Base64Body body = new Base64Body(content);
        final MimeBodyPart bp = new MimeBodyPart(body, contentType);
        bp.setHeader(MimeHeader.HEADER_CONTENT_TRANSFER_ENCODING, "base64");
        bp.setHeader(MimeHeader.HEADER_CONTENT_DISPOSITION, "attachment;\n "
                + (!TextUtils.isEmpty(filename) ? "filename=\"" + filename + "\";" : "")
                + "size=" + contentSize);
        if (contentId != null) {
            bp.setHeader(MimeHeader.HEADER_CONTENT_ID, contentId);
        }
        mp.addBodyPart(bp);
    }

    /**
     * Infer mailbox type from mailbox name.  Used by MessagingController (for live folder sync).
     *
     * Deprecation: this should be configured in the UI, in conjunction with RF6154 support
     */
    @Deprecated
    public static synchronized int inferMailboxTypeFromName(Context context, String mailboxName) {
        if (sServerMailboxNames.size() == 0) {
            // preload the hashmap, one time only
            sServerMailboxNames.put(
                    context.getString(R.string.mailbox_name_server_inbox),
                    Mailbox.TYPE_INBOX);
            sServerMailboxNames.put(
                    context.getString(R.string.mailbox_name_server_outbox),
                    Mailbox.TYPE_OUTBOX);
            sServerMailboxNames.put(
                    context.getString(R.string.mailbox_name_server_drafts),
                    Mailbox.TYPE_DRAFTS);
            sServerMailboxNames.put(
                    context.getString(R.string.mailbox_name_server_trash),
                    Mailbox.TYPE_TRASH);
            sServerMailboxNames.put(
                    context.getString(R.string.mailbox_name_server_sent),
                    Mailbox.TYPE_SENT);
            sServerMailboxNames.put(
                    context.getString(R.string.mailbox_name_server_junk),
                    Mailbox.TYPE_JUNK);
        }
        if (mailboxName == null || mailboxName.length() == 0) {
            return Mailbox.TYPE_MAIL;
        }
        Integer type = sServerMailboxNames.get(mailboxName);
        if (type != null) {
            return type;
        }
        return Mailbox.TYPE_MAIL;
    }
}
