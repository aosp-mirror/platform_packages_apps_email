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
import com.android.email.mail.Body;
import com.android.email.mail.Flag;
import com.android.email.mail.Folder;
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Part;
import com.android.email.mail.Message.RecipientType;
import com.android.email.mail.internet.MimeBodyPart;
import com.android.email.mail.internet.MimeHeader;
import com.android.email.mail.internet.MimeMessage;
import com.android.email.mail.internet.MimeMultipart;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.mail.internet.TextBody;
import com.android.email.mail.store.LocalStore;
import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.AttachmentColumns;
import com.android.email.provider.EmailContent.Mailbox;

import org.apache.commons.io.IOUtils;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

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
     * Standard columns for querying content providers
     */
    private static final String[] ATTACHMENT_META_COLUMNS_PROJECTION = {
        OpenableColumns.DISPLAY_NAME,
        OpenableColumns.SIZE
    };
    private static final int ATTACHMENT_META_COLUMNS_SIZE = 1;

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
        }
        if (subject != null) {
            localMessage.mSubject = subject;
        }
        localMessage.mFlagRead = message.isSet(Flag.SEEN);

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
     * Copy body text (plain and/or HTML) from MimeMessage to provider Message
     */
    public static boolean updateBodyFields(EmailContent.Body body,
            EmailContent.Message localMessage, ArrayList<Part> viewables)
            throws MessagingException {

        body.mMessageKey = localMessage.mId;

        StringBuffer sbHtml = null;
        StringBuffer sbText = null;
        StringBuffer sbHtmlReply = null;
        StringBuffer sbTextReply = null;
        StringBuffer sbIntroText = null;

        for (Part viewable : viewables) {
            String text = MimeUtility.getTextFromPart(viewable);
            String[] replyTags = viewable.getHeader(MimeHeader.HEADER_ANDROID_BODY_QUOTED_PART);
            String replyTag = null;
            if (replyTags != null && replyTags.length > 0) {
                replyTag = replyTags[0];
            }
            // Deploy text as marked by the various tags
            boolean isHtml = "text/html".equalsIgnoreCase(viewable.getMimeType());

            if (replyTag != null) {
                boolean isQuotedReply = BODY_QUOTED_PART_REPLY.equalsIgnoreCase(replyTag);
                boolean isQuotedForward = BODY_QUOTED_PART_FORWARD.equalsIgnoreCase(replyTag);
                boolean isQuotedIntro = BODY_QUOTED_PART_INTRO.equalsIgnoreCase(replyTag);

                if (isQuotedReply || isQuotedForward) {
                    if (isHtml) {
                        sbHtmlReply = appendTextPart(sbHtmlReply, text);
                    } else {
                        sbTextReply = appendTextPart(sbTextReply, text);
                    }
                    // Set message flags as well
                    localMessage.mFlags &= ~EmailContent.Message.FLAG_TYPE_MASK;
                    localMessage.mFlags |= isQuotedReply
                            ? EmailContent.Message.FLAG_TYPE_REPLY
                            : EmailContent.Message.FLAG_TYPE_FORWARD;
                    continue;
                }
                if (isQuotedIntro) {
                    sbIntroText = appendTextPart(sbIntroText, text);
                    continue;
                }
            }

            // Most of the time, just process regular body parts
            if (isHtml) {
                sbHtml = appendTextPart(sbHtml, text);
            } else {
                sbText = appendTextPart(sbText, text);
            }
        }

        // write the combined data to the body part
        if (sbText != null && sbText.length() != 0) {
            body.mTextContent = sbText.toString();
        }
        if (sbHtml != null && sbHtml.length() != 0) {
            body.mHtmlContent = sbHtml.toString();
        }
        if (sbHtmlReply != null && sbHtmlReply.length() != 0) {
            body.mHtmlReply = sbHtmlReply.toString();
        }
        if (sbTextReply != null && sbTextReply.length() != 0) {
            body.mTextReply = sbTextReply.toString();
        }
        if (sbIntroText != null && sbIntroText.length() != 0) {
            body.mIntroText = sbIntroText.toString();
        }
        return true;
    }

    /**
     * Helper function to append text to a StringBuffer, creating it if necessary.
     * Optimization:  The majority of the time we are *not* appending - we should have a path
     * that deals with single strings.
     */
    private static StringBuffer appendTextPart(StringBuffer sb, String newText) {
        if (newText == null) {
            return sb;
        }
        else if (sb == null) {
            sb = new StringBuffer(newText);
        } else {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(newText);
        }
        return sb;
    }

    /**
     * Copy attachments from MimeMessage to provider Message.
     *
     * @param context a context for file operations
     * @param localMessage the attachments will be built against this message
     * @param attachments the attachments to add
     * @param upgrading if true, we are upgrading a local account - handle attachments differently
     * @throws IOException
     */
    public static void updateAttachments(Context context, EmailContent.Message localMessage,
            ArrayList<Part> attachments, boolean upgrading) throws MessagingException, IOException {
        localMessage.mAttachments = null;
        for (Part attachmentPart : attachments) {
            addOneAttachment(context, localMessage, attachmentPart, upgrading);
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
     * @param upgrading true if upgrading a local account - handle attachments differently
     * @throws IOException
     */
    private static void addOneAttachment(Context context, EmailContent.Message localMessage,
            Part part, boolean upgrading) throws MessagingException, IOException {

        Attachment localAttachment = new Attachment();

        // Transfer fields from mime format to provider format
        String contentType = MimeUtility.unfoldAndDecode(part.getContentType());
        String name = MimeUtility.getHeaderParameter(contentType, "name");
        if (name == null) {
            String contentDisposition = MimeUtility.unfoldAndDecode(part.getDisposition());
            name = MimeUtility.getHeaderParameter(contentDisposition, "filename");
        }

        // Select the URI for the new attachments.  For attachments downloaded by the legacy
        // IMAP/POP code, this is not determined yet, so is null (it will be rewritten below,
        // or later, when the actual attachment file is created.)
        //
        // When upgrading older local accounts, the URI represents a local asset (e.g. a photo)
        // so we need to preserve the URI.
        // TODO This works for outgoing messages, where the URI does not change.  May need
        // additional logic to handle the case of rewriting URI for received attachments.
        Uri contentUri = null;
        String contentUriString = null;
        if (upgrading) {
            Body body = part.getBody();
            if (body instanceof LocalStore.LocalAttachmentBody) {
                LocalStore.LocalAttachmentBody localBody = (LocalStore.LocalAttachmentBody) body;
                contentUri = localBody.getContentUri();
                if (contentUri != null) {
                    contentUriString = contentUri.toString();
                }
            }
        }

        // Find size, if available, via a number of techniques:
        long size = 0;
        if (upgrading) {
            // If upgrading a legacy account, the size must be recaptured from the data source
            if (contentUri != null) {
                Cursor metadataCursor = context.getContentResolver().query(contentUri,
                        ATTACHMENT_META_COLUMNS_PROJECTION, null, null, null);
                if (metadataCursor != null) {
                    try {
                        if (metadataCursor.moveToFirst()) {
                            size = metadataCursor.getInt(ATTACHMENT_META_COLUMNS_SIZE);
                        }
                    } finally {
                        metadataCursor.close();
                    }
                }
            }
            // TODO: a downloaded legacy attachment - see if the above code works
        } else {
            // Incoming attachment: Try to pull size from disposition (if not downloaded yet)
            String disposition = part.getDisposition();
            if (disposition != null) {
                String s = MimeUtility.getHeaderParameter(disposition, "size");
                if (s != null) {
                    size = Long.parseLong(s);
                }
            }
        }

        // Get partId for unloaded IMAP attachments (if any)
        // This is only provided (and used) when we have structure but not the actual attachment
        String[] partIds = part.getHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA);
        String partId = partIds != null ? partIds[0] : null;

        localAttachment.mFileName = name;
        localAttachment.mMimeType = part.getMimeType();
        localAttachment.mSize = size;           // May be reset below if file handled
        localAttachment.mContentId = part.getContentId();
        localAttachment.mContentUri = contentUriString;
        localAttachment.mMessageKey = localMessage.mId;
        localAttachment.mLocation = partId;
        localAttachment.mEncoding = "B";        // TODO - convert other known encodings

        if (DEBUG_ATTACHMENTS) {
            Log.d(Email.LOG_TAG, "Add attachment " + localAttachment);
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
                Attachment dbAttachment = new Attachment().restore(cursor);
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
                    Log.d(Email.LOG_TAG, "Skipped, found db attachment " + dbAttachment);
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
        if (!upgrading) {
            saveAttachmentBody(context, part, localAttachment, localMessage.mAccountKey);
        }

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
            Log.d(Email.LOG_TAG, "Exception while reading html body " + rte.toString());
        }

        try {
            addTextBodyPart(mp, "text/plain", null,
                    EmailContent.Body.restoreBodyTextWithMessageId(context, localMessage.mId));
        } catch (RuntimeException rte) {
            Log.d(Email.LOG_TAG, "Exception while reading text body " + rte.toString());
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
                Log.d(Email.LOG_TAG, "Exception while reading text reply " + rte.toString());
            }

            String replyTag = isReply ? BODY_QUOTED_PART_REPLY : BODY_QUOTED_PART_FORWARD;
            try {
                addTextBodyPart(mp, "text/html", replyTag,
                        EmailContent.Body.restoreReplyHtmlWithMessageId(context, localMessage.mId));
            } catch (RuntimeException rte) {
                Log.d(Email.LOG_TAG, "Exception while reading html reply " + rte.toString());
            }

            try {
                addTextBodyPart(mp, "text/plain", replyTag,
                        EmailContent.Body.restoreReplyTextWithMessageId(context, localMessage.mId));
            } catch (RuntimeException rte) {
                Log.d(Email.LOG_TAG, "Exception while reading text reply " + rte.toString());
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
     * Conversion from provider account to legacy account
     *
     * Used for backup/restore.
     *
     * @param context application context
     * @param fromAccount the provider account to be backed up (including transient hostauth's)
     * @return a legacy Account object ready to be committed to preferences
     */
    /* package */ static Account makeLegacyAccount(Context context,
            EmailContent.Account fromAccount) {
        Account result = new Account(context);

        result.setDescription(fromAccount.getDisplayName());
        result.setEmail(fromAccount.getEmailAddress());
        // fromAccount.mSyncKey - assume lost if restoring
        result.setSyncWindow(fromAccount.getSyncLookback());
        result.setAutomaticCheckIntervalMinutes(fromAccount.getSyncInterval());
        // fromAccount.mHostAuthKeyRecv - id not saved; will be reassigned when restoring
        // fromAccount.mHostAuthKeySend - id not saved; will be reassigned when restoring

        // Provider Account flags, and how they are mapped.
        //  FLAGS_NOTIFY_NEW_MAIL       -> mNotifyNewMail
        //  FLAGS_VIBRATE_ALWAYS        -> mVibrate
        //  FLAGS_VIBRATE_WHEN_SILENT   -> mVibrateWhenSilent
        //  DELETE_POLICY_NEVER         -> mDeletePolicy
        //  DELETE_POLICY_7DAYS
        //  DELETE_POLICY_ON_DELETE
        result.setNotifyNewMail(0 !=
            (fromAccount.getFlags() & EmailContent.Account.FLAGS_NOTIFY_NEW_MAIL));
        result.setVibrate(0 !=
            (fromAccount.getFlags() & EmailContent.Account.FLAGS_VIBRATE_ALWAYS));
        result.setVibrateWhenSilent(0 !=
            (fromAccount.getFlags() & EmailContent.Account.FLAGS_VIBRATE_WHEN_SILENT));
        result.setDeletePolicy(fromAccount.getDeletePolicy());

        result.mUuid = fromAccount.getUuid();
        result.setName(fromAccount.mSenderName);
        result.setRingtone(fromAccount.mRingtoneUri);
        result.mProtocolVersion = fromAccount.mProtocolVersion;
        // int fromAccount.mNewMessageCount = will be reset on next sync
        result.mSecurityFlags = fromAccount.mSecurityFlags;
        result.mSignature = fromAccount.mSignature;

        // Use the existing conversions from HostAuth <-> Uri
        result.setStoreUri(fromAccount.getStoreUri(context));
        result.setSenderUri(fromAccount.getSenderUri(context));

        return result;
    }

    /**
     * Conversion from legacy account to provider account
     *
     * Used for backup/restore and for account migration.
     *
     * @param context application context
     * @param fromAccount the legacy account to convert to modern format
     * @return an Account ready to be committed to provider
     */
    public static EmailContent.Account makeAccount(Context context, Account fromAccount) {

        EmailContent.Account result = new EmailContent.Account();

        result.setDisplayName(fromAccount.getDescription());
        result.setEmailAddress(fromAccount.getEmail());
        result.mSyncKey = null;
        result.setSyncLookback(fromAccount.getSyncWindow());
        result.setSyncInterval(fromAccount.getAutomaticCheckIntervalMinutes());
        // result.mHostAuthKeyRecv;     -- will be set when object is saved
        // result.mHostAuthKeySend;     -- will be set when object is saved
        int flags = 0;
        if (fromAccount.isNotifyNewMail())  flags |= EmailContent.Account.FLAGS_NOTIFY_NEW_MAIL;
        if (fromAccount.isVibrate())        flags |= EmailContent.Account.FLAGS_VIBRATE_ALWAYS;
        if (fromAccount.isVibrateWhenSilent())
            flags |= EmailContent.Account.FLAGS_VIBRATE_WHEN_SILENT;
        result.setFlags(flags);
        result.setDeletePolicy(fromAccount.getDeletePolicy());
        // result.setDefaultAccount();  -- will be set by caller, if neededf
        result.mCompatibilityUuid = fromAccount.getUuid();
        result.setSenderName(fromAccount.getName());
        result.setRingtone(fromAccount.getRingtone());
        result.mProtocolVersion = fromAccount.mProtocolVersion;
        result.mNewMessageCount = 0;
        result.mSecurityFlags = fromAccount.mSecurityFlags;
        result.mSecuritySyncKey = null;
        result.mSignature = fromAccount.mSignature;

        result.setStoreUri(context, fromAccount.getStoreUri());
        result.setSenderUri(context, fromAccount.getSenderUri());

        return result;
    }

    /**
     * Conversion from legacy folder to provider mailbox.  Used for account migration.
     * Note: Many mailbox fields are unused in IMAP & POP accounts.
     *
     * @param context application context
     * @param toAccount the provider account that this folder will be associated with
     * @param fromFolder the legacy folder to convert to modern format
     * @return an Account ready to be committed to provider
     */
    public static EmailContent.Mailbox makeMailbox(Context context, EmailContent.Account toAccount,
            Folder fromFolder) throws MessagingException {
        EmailContent.Mailbox result = new EmailContent.Mailbox();

        result.mDisplayName = fromFolder.getName();
        // result.mServerId
        // result.mParentServerId
        result.mAccountKey = toAccount.mId;
        result.mType = inferMailboxTypeFromName(context, fromFolder.getName());
        // result.mDelimiter
        // result.mSyncKey
        // result.mSyncLookback
        // result.mSyncInterval
        result.mSyncTime = 0;
        result.mUnreadCount = fromFolder.getUnreadMessageCount();
        result.mFlagVisible = true;
        result.mFlags = 0;
        result.mVisibleLimit = Email.VISIBLE_LIMIT_DEFAULT;
        // result.mSyncStatus

        return result;
    }

    /**
     * Infer mailbox type from mailbox name.  Used by MessagingController (for live folder sync)
     * and for legacy account upgrades.
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
            return EmailContent.Mailbox.TYPE_MAIL;
        }
        String lowerCaseName = mailboxName.toLowerCase();
        Integer type = sServerMailboxNames.get(lowerCaseName);
        if (type != null) {
            return type;
        }
        return EmailContent.Mailbox.TYPE_MAIL;
    }
}
