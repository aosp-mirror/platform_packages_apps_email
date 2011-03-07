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

package com.android.emailcommon.provider;

import com.android.emailcommon.mail.Snippet;
import com.android.emailcommon.utility.Utility;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.text.TextUtils;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;


/**
 * EmailContent is the superclass of the various classes of content stored by EmailProvider.
 *
 * It is intended to include 1) column definitions for use with the Provider, and 2) convenience
 * methods for saving and retrieving content from the Provider.
 *
 * This class will be used by 1) the Email process (which includes the application and
 * EmaiLProvider) as well as 2) the Exchange process (which runs independently).  It will
 * necessarily be cloned for use in these two cases.
 *
 * Conventions used in naming columns:
 *   RECORD_ID is the primary key for all Email records
 *   The SyncColumns interface is used by all classes that are synced to the server directly
 *   (Mailbox and Email)
 *
 *   <name>_KEY always refers to a foreign key
 *   <name>_ID always refers to a unique identifier (whether on client, server, etc.)
 *
 */
public abstract class EmailContent {

    public static final String AUTHORITY = "com.android.email.provider";
    // The notifier authority is used to send notifications regarding changes to messages (insert,
    // delete, or update) and is intended as an optimization for use by clients of message list
    // cursors (initially, the email AppWidget).
    public static final String NOTIFIER_AUTHORITY = "com.android.email.notifier";

    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);
    public static final String PARAMETER_LIMIT = "limit";

    public static final Uri CONTENT_NOTIFIER_URI = Uri.parse("content://" + NOTIFIER_AUTHORITY);

    public static final String PROVIDER_PERMISSION = "com.android.email.permission.ACCESS_PROVIDER";

    // All classes share this
    public static final String RECORD_ID = "_id";

    public static final String[] COUNT_COLUMNS = new String[]{"count(*)"};

    /**
     * This projection can be used with any of the EmailContent classes, when all you need
     * is a list of id's.  Use ID_PROJECTION_COLUMN to access the row data.
     */
    public static final String[] ID_PROJECTION = new String[] {
        RECORD_ID
    };
    public static final int ID_PROJECTION_COLUMN = 0;

    public static final String ID_SELECTION = RECORD_ID + " =?";

    public static final String FIELD_COLUMN_NAME = "field";
    public static final String ADD_COLUMN_NAME = "add";

    // Newly created objects get this id
    public static final int NOT_SAVED = -1;
    // The base Uri that this piece of content came from
    public Uri mBaseUri;
    // Lazily initialized uri for this Content
    private Uri mUri = null;
    // The id of the Content
    public long mId = NOT_SAVED;

    // Write the Content into a ContentValues container
    public abstract ContentValues toContentValues();
    // Read the Content from a ContentCursor
    public abstract void restore (Cursor cursor);

    // The Uri is lazily initialized
    public Uri getUri() {
        if (mUri == null) {
            mUri = ContentUris.withAppendedId(mBaseUri, mId);
        }
        return mUri;
    }

    public boolean isSaved() {
        return mId != NOT_SAVED;
    }

    // The Content sub class must have a no-arg constructor
    static public <T extends EmailContent> T getContent(Cursor cursor, Class<T> klass) {
        try {
            T content = klass.newInstance();
            content.mId = cursor.getLong(0);
            content.restore(cursor);
            return content;
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        }
        return null;
    }

    public Uri save(Context context) {
        if (isSaved()) {
            throw new UnsupportedOperationException();
        }
        Uri res = context.getContentResolver().insert(mBaseUri, toContentValues());
        mId = Long.parseLong(res.getPathSegments().get(1));
        return res;
    }

    public int update(Context context, ContentValues contentValues) {
        if (!isSaved()) {
            throw new UnsupportedOperationException();
        }
        return context.getContentResolver().update(getUri(), contentValues, null, null);
    }

    static public int update(Context context, Uri baseUri, long id, ContentValues contentValues) {
        return context.getContentResolver()
            .update(ContentUris.withAppendedId(baseUri, id), contentValues, null, null);
    }

    static public int delete(Context context, Uri baseUri, long id) {
        return context.getContentResolver()
            .delete(ContentUris.withAppendedId(baseUri, id), null, null);
    }

    /**
     * Generic count method that can be used for any ContentProvider
     *
     * @param context the calling Context
     * @param uri the Uri for the provider query
     * @param selection as with a query call
     * @param selectionArgs as with a query call
     * @return the number of items matching the query (or zero)
     */
    static public int count(Context context, Uri uri, String selection, String[] selectionArgs) {
        return Utility.getFirstRowLong(context,
                uri, COUNT_COLUMNS, selection, selectionArgs, null, 0, Long.valueOf(0)).intValue();
    }

    /**
     * Same as {@link #count(Context, Uri, String, String[])} without selection.
     */
    static public int count(Context context, Uri uri) {
        return count(context, uri, null, null);
    }

    static public Uri uriWithLimit(Uri uri, int limit) {
        return uri.buildUpon().appendQueryParameter(EmailContent.PARAMETER_LIMIT,
                Integer.toString(limit)).build();
    }

    /**
     * no public constructor since this is a utility class
     */
    private EmailContent() {
    }

    public interface SyncColumns {
        public static final String ID = "_id";
        // source id (string) : the source's name of this item
        public static final String SERVER_ID = "syncServerId";
        // source's timestamp (long) for this item
        public static final String SERVER_TIMESTAMP = "syncServerTimeStamp";
    }

    public interface BodyColumns {
        public static final String ID = "_id";
        // Foreign key to the message corresponding to this body
        public static final String MESSAGE_KEY = "messageKey";
        // The html content itself
        public static final String HTML_CONTENT = "htmlContent";
        // The plain text content itself
        public static final String TEXT_CONTENT = "textContent";
        // Replied-to or forwarded body (in html form)
        public static final String HTML_REPLY = "htmlReply";
        // Replied-to or forwarded body (in text form)
        public static final String TEXT_REPLY = "textReply";
        // A reference to a message's unique id used in reply/forward.
        // Protocol code can be expected to use this column in determining whether a message can be
        // deleted safely (i.e. isn't referenced by other messages)
        public static final String SOURCE_MESSAGE_KEY = "sourceMessageKey";
        // The text to be placed between a reply/forward response and the original message
        public static final String INTRO_TEXT = "introText";
    }

    public static final class Body extends EmailContent implements BodyColumns {
        public static final String TABLE_NAME = "Body";

        @SuppressWarnings("hiding")
        public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/body");

        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_MESSAGE_KEY_COLUMN = 1;
        public static final int CONTENT_HTML_CONTENT_COLUMN = 2;
        public static final int CONTENT_TEXT_CONTENT_COLUMN = 3;
        public static final int CONTENT_HTML_REPLY_COLUMN = 4;
        public static final int CONTENT_TEXT_REPLY_COLUMN = 5;
        public static final int CONTENT_SOURCE_KEY_COLUMN = 6;
        public static final int CONTENT_INTRO_TEXT_COLUMN = 7;
        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, BodyColumns.MESSAGE_KEY, BodyColumns.HTML_CONTENT, BodyColumns.TEXT_CONTENT,
            BodyColumns.HTML_REPLY, BodyColumns.TEXT_REPLY, BodyColumns.SOURCE_MESSAGE_KEY,
            BodyColumns.INTRO_TEXT
        };

        public static final String[] COMMON_PROJECTION_TEXT = new String[] {
            RECORD_ID, BodyColumns.TEXT_CONTENT
        };
        public static final String[] COMMON_PROJECTION_HTML = new String[] {
            RECORD_ID, BodyColumns.HTML_CONTENT
        };
        public static final String[] COMMON_PROJECTION_REPLY_TEXT = new String[] {
            RECORD_ID, BodyColumns.TEXT_REPLY
        };
        public static final String[] COMMON_PROJECTION_REPLY_HTML = new String[] {
            RECORD_ID, BodyColumns.HTML_REPLY
        };
        public static final String[] COMMON_PROJECTION_INTRO = new String[] {
            RECORD_ID, BodyColumns.INTRO_TEXT
        };
        public static final String[] COMMON_PROJECTION_SOURCE = new String[] {
            RECORD_ID, BodyColumns.SOURCE_MESSAGE_KEY
        };
         public static final int COMMON_PROJECTION_COLUMN_TEXT = 1;

        private static final String[] PROJECTION_SOURCE_KEY =
            new String[] { BodyColumns.SOURCE_MESSAGE_KEY };

        public long mMessageKey;
        public String mHtmlContent;
        public String mTextContent;
        public String mHtmlReply;
        public String mTextReply;
        public long mSourceKey;
        public String mIntroText;

        public Body() {
            mBaseUri = CONTENT_URI;
        }

        @Override
        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();

            // Assign values for each row.
            values.put(BodyColumns.MESSAGE_KEY, mMessageKey);
            values.put(BodyColumns.HTML_CONTENT, mHtmlContent);
            values.put(BodyColumns.TEXT_CONTENT, mTextContent);
            values.put(BodyColumns.HTML_REPLY, mHtmlReply);
            values.put(BodyColumns.TEXT_REPLY, mTextReply);
            values.put(BodyColumns.SOURCE_MESSAGE_KEY, mSourceKey);
            values.put(BodyColumns.INTRO_TEXT, mIntroText);
            return values;
        }

        private static Body restoreBodyWithCursor(Cursor cursor) {
            try {
                if (cursor.moveToFirst()) {
                    return getContent(cursor, Body.class);
                } else {
                    return null;
                }
            } finally {
                cursor.close();
            }
        }

        public static Body restoreBodyWithId(Context context, long id) {
            Uri u = ContentUris.withAppendedId(Body.CONTENT_URI, id);
            Cursor c = context.getContentResolver().query(u, Body.CONTENT_PROJECTION,
                    null, null, null);
            return restoreBodyWithCursor(c);
        }

        public static Body restoreBodyWithMessageId(Context context, long messageId) {
            Cursor c = context.getContentResolver().query(Body.CONTENT_URI,
                    Body.CONTENT_PROJECTION, Body.MESSAGE_KEY + "=?",
                    new String[] {Long.toString(messageId)}, null);
            return restoreBodyWithCursor(c);
        }

        /**
         * Returns the bodyId for the given messageId, or -1 if no body is found.
         */
        public static long lookupBodyIdWithMessageId(Context context, long messageId) {
            return Utility.getFirstRowLong(context, Body.CONTENT_URI,
                    ID_PROJECTION, Body.MESSAGE_KEY + "=?",
                    new String[] {Long.toString(messageId)}, null, ID_PROJECTION_COLUMN,
                            Long.valueOf(-1));
        }

        /**
         * Updates the Body for a messageId with the given ContentValues.
         * If the message has no body, a new body is inserted for the message.
         * Warning: the argument "values" is modified by this method, setting MESSAGE_KEY.
         */
        public static void updateBodyWithMessageId(Context context, long messageId,
                ContentValues values) {
            ContentResolver resolver = context.getContentResolver();
            long bodyId = lookupBodyIdWithMessageId(context, messageId);
            values.put(BodyColumns.MESSAGE_KEY, messageId);
            if (bodyId == -1) {
                resolver.insert(CONTENT_URI, values);
            } else {
                final Uri uri = ContentUris.withAppendedId(CONTENT_URI, bodyId);
                resolver.update(uri, values, null, null);
            }
        }

        public static long restoreBodySourceKey(Context context, long messageId) {
            return Utility.getFirstRowLong(context, Body.CONTENT_URI,
                    Body.PROJECTION_SOURCE_KEY,
                    Body.MESSAGE_KEY + "=?", new String[] {Long.toString(messageId)}, null, 0,
                            Long.valueOf(0));
        }

        private static String restoreTextWithMessageId(Context context, long messageId,
                String[] projection) {
            Cursor c = context.getContentResolver().query(Body.CONTENT_URI, projection,
                    Body.MESSAGE_KEY + "=?", new String[] {Long.toString(messageId)}, null);
            try {
                if (c.moveToFirst()) {
                    return c.getString(COMMON_PROJECTION_COLUMN_TEXT);
                } else {
                    return null;
                }
            } finally {
                c.close();
            }
        }

        public static String restoreBodyTextWithMessageId(Context context, long messageId) {
            return restoreTextWithMessageId(context, messageId, Body.COMMON_PROJECTION_TEXT);
        }

        public static String restoreBodyHtmlWithMessageId(Context context, long messageId) {
            return restoreTextWithMessageId(context, messageId, Body.COMMON_PROJECTION_HTML);
        }

        public static String restoreReplyTextWithMessageId(Context context, long messageId) {
            return restoreTextWithMessageId(context, messageId, Body.COMMON_PROJECTION_REPLY_TEXT);
        }

        public static String restoreReplyHtmlWithMessageId(Context context, long messageId) {
            return restoreTextWithMessageId(context, messageId, Body.COMMON_PROJECTION_REPLY_HTML);
        }

        public static String restoreIntroTextWithMessageId(Context context, long messageId) {
            return restoreTextWithMessageId(context, messageId, Body.COMMON_PROJECTION_INTRO);
        }

        @Override
        public void restore(Cursor cursor) {
            mBaseUri = EmailContent.Body.CONTENT_URI;
            mMessageKey = cursor.getLong(CONTENT_MESSAGE_KEY_COLUMN);
            mHtmlContent = cursor.getString(CONTENT_HTML_CONTENT_COLUMN);
            mTextContent = cursor.getString(CONTENT_TEXT_CONTENT_COLUMN);
            mHtmlReply = cursor.getString(CONTENT_HTML_REPLY_COLUMN);
            mTextReply = cursor.getString(CONTENT_TEXT_REPLY_COLUMN);
            mSourceKey = cursor.getLong(CONTENT_SOURCE_KEY_COLUMN);
            mIntroText = cursor.getString(CONTENT_INTRO_TEXT_COLUMN);
        }

        public boolean update() {
            // TODO Auto-generated method stub
            return false;
        }
    }

    public interface MessageColumns {
        public static final String ID = "_id";
        // Basic columns used in message list presentation
        // The name as shown to the user in a message list
        public static final String DISPLAY_NAME = "displayName";
        // The time (millis) as shown to the user in a message list [INDEX]
        public static final String TIMESTAMP = "timeStamp";
        // Message subject
        public static final String SUBJECT = "subject";
        // Boolean, unread = 0, read = 1 [INDEX]
        public static final String FLAG_READ = "flagRead";
        // Load state, see constants below (unloaded, partial, complete, deleted)
        public static final String FLAG_LOADED = "flagLoaded";
        // Boolean, unflagged = 0, flagged (favorite) = 1
        public static final String FLAG_FAVORITE = "flagFavorite";
        // Boolean, no attachment = 0, attachment = 1
        public static final String FLAG_ATTACHMENT = "flagAttachment";
        // Bit field for flags which we'll not be selecting on
        public static final String FLAGS = "flags";

        // Sync related identifiers
        // Any client-required identifier
        public static final String CLIENT_ID = "clientId";
        // The message-id in the message's header
        public static final String MESSAGE_ID = "messageId";

        // References to other Email objects in the database
        // Foreign key to the Mailbox holding this message [INDEX]
        public static final String MAILBOX_KEY = "mailboxKey";
        // Foreign key to the Account holding this message
        public static final String ACCOUNT_KEY = "accountKey";

        // Address lists, packed with Address.pack()
        public static final String FROM_LIST = "fromList";
        public static final String TO_LIST = "toList";
        public static final String CC_LIST = "ccList";
        public static final String BCC_LIST = "bccList";
        public static final String REPLY_TO_LIST = "replyToList";
        // Meeting invitation related information (for now, start time in ms)
        public static final String MEETING_INFO = "meetingInfo";
        // A text "snippet" derived from the body of the message
        public static final String SNIPPET = "snippet";
    }

    public static final class Message extends EmailContent implements SyncColumns, MessageColumns {
        public static final String TABLE_NAME = "Message";
        public static final String UPDATED_TABLE_NAME = "Message_Updates";
        public static final String DELETED_TABLE_NAME = "Message_Deletes";

        // To refer to a specific message, use ContentUris.withAppendedId(CONTENT_URI, id)
        @SuppressWarnings("hiding")
        public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/message");
        public static final Uri CONTENT_URI_LIMIT_1 = uriWithLimit(CONTENT_URI, 1);
        public static final Uri SYNCED_CONTENT_URI =
            Uri.parse(EmailContent.CONTENT_URI + "/syncedMessage");
        public static final Uri DELETED_CONTENT_URI =
            Uri.parse(EmailContent.CONTENT_URI + "/deletedMessage");
        public static final Uri UPDATED_CONTENT_URI =
            Uri.parse(EmailContent.CONTENT_URI + "/updatedMessage");
        public static final Uri NOTIFIER_URI =
            Uri.parse(EmailContent.CONTENT_NOTIFIER_URI + "/message");

        public static final String KEY_TIMESTAMP_DESC = MessageColumns.TIMESTAMP + " desc";

        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_DISPLAY_NAME_COLUMN = 1;
        public static final int CONTENT_TIMESTAMP_COLUMN = 2;
        public static final int CONTENT_SUBJECT_COLUMN = 3;
        public static final int CONTENT_FLAG_READ_COLUMN = 4;
        public static final int CONTENT_FLAG_LOADED_COLUMN = 5;
        public static final int CONTENT_FLAG_FAVORITE_COLUMN = 6;
        public static final int CONTENT_FLAG_ATTACHMENT_COLUMN = 7;
        public static final int CONTENT_FLAGS_COLUMN = 8;
        public static final int CONTENT_SERVER_ID_COLUMN = 9;
        public static final int CONTENT_CLIENT_ID_COLUMN = 10;
        public static final int CONTENT_MESSAGE_ID_COLUMN = 11;
        public static final int CONTENT_MAILBOX_KEY_COLUMN = 12;
        public static final int CONTENT_ACCOUNT_KEY_COLUMN = 13;
        public static final int CONTENT_FROM_LIST_COLUMN = 14;
        public static final int CONTENT_TO_LIST_COLUMN = 15;
        public static final int CONTENT_CC_LIST_COLUMN = 16;
        public static final int CONTENT_BCC_LIST_COLUMN = 17;
        public static final int CONTENT_REPLY_TO_COLUMN = 18;
        public static final int CONTENT_SERVER_TIMESTAMP_COLUMN = 19;
        public static final int CONTENT_MEETING_INFO_COLUMN = 20;
        public static final int CONTENT_SNIPPET_COLUMN = 21;

        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID,
            MessageColumns.DISPLAY_NAME, MessageColumns.TIMESTAMP,
            MessageColumns.SUBJECT, MessageColumns.FLAG_READ,
            MessageColumns.FLAG_LOADED, MessageColumns.FLAG_FAVORITE,
            MessageColumns.FLAG_ATTACHMENT, MessageColumns.FLAGS,
            SyncColumns.SERVER_ID, MessageColumns.CLIENT_ID,
            MessageColumns.MESSAGE_ID, MessageColumns.MAILBOX_KEY,
            MessageColumns.ACCOUNT_KEY, MessageColumns.FROM_LIST,
            MessageColumns.TO_LIST, MessageColumns.CC_LIST,
            MessageColumns.BCC_LIST, MessageColumns.REPLY_TO_LIST,
            SyncColumns.SERVER_TIMESTAMP, MessageColumns.MEETING_INFO,
            MessageColumns.SNIPPET
        };

        public static final int LIST_ID_COLUMN = 0;
        public static final int LIST_DISPLAY_NAME_COLUMN = 1;
        public static final int LIST_TIMESTAMP_COLUMN = 2;
        public static final int LIST_SUBJECT_COLUMN = 3;
        public static final int LIST_READ_COLUMN = 4;
        public static final int LIST_LOADED_COLUMN = 5;
        public static final int LIST_FAVORITE_COLUMN = 6;
        public static final int LIST_ATTACHMENT_COLUMN = 7;
        public static final int LIST_FLAGS_COLUMN = 8;
        public static final int LIST_MAILBOX_KEY_COLUMN = 9;
        public static final int LIST_ACCOUNT_KEY_COLUMN = 10;
        public static final int LIST_SERVER_ID_COLUMN = 11;
        public static final int LIST_SNIPPET_COLUMN = 12;

        // Public projection for common list columns
        public static final String[] LIST_PROJECTION = new String[] {
            RECORD_ID,
            MessageColumns.DISPLAY_NAME, MessageColumns.TIMESTAMP,
            MessageColumns.SUBJECT, MessageColumns.FLAG_READ,
            MessageColumns.FLAG_LOADED, MessageColumns.FLAG_FAVORITE,
            MessageColumns.FLAG_ATTACHMENT, MessageColumns.FLAGS,
            MessageColumns.MAILBOX_KEY, MessageColumns.ACCOUNT_KEY,
            SyncColumns.SERVER_ID, MessageColumns.SNIPPET
        };

        public static final int ID_COLUMNS_ID_COLUMN = 0;
        public static final int ID_COLUMNS_SYNC_SERVER_ID = 1;
        public static final String[] ID_COLUMNS_PROJECTION = new String[] {
            RECORD_ID, SyncColumns.SERVER_ID
        };

        public static final int ID_MAILBOX_COLUMN_ID = 0;
        public static final int ID_MAILBOX_COLUMN_MAILBOX_KEY = 1;
        public static final String[] ID_MAILBOX_PROJECTION = new String[] {
            RECORD_ID, MessageColumns.MAILBOX_KEY
        };

        public static final String[] ID_COLUMN_PROJECTION = new String[] { RECORD_ID };

        private static final String ACCOUNT_KEY_SELECTION =
            MessageColumns.ACCOUNT_KEY + "=?";

        /** Selection for messages that are loaded */
        public static final String FLAG_LOADED_SELECTION =
            MessageColumns.FLAG_LOADED + " IN ("
            +     Message.FLAG_LOADED_PARTIAL + "," + Message.FLAG_LOADED_COMPLETE
            +     ")";

        public static final String ALL_FAVORITE_SELECTION =
            MessageColumns.FLAG_FAVORITE + "=1 AND "
            + MessageColumns.MAILBOX_KEY + " NOT IN ("
            +     "SELECT " + MailboxColumns.ID + " FROM " + Mailbox.TABLE_NAME + ""
            +     " WHERE " + MailboxColumns.TYPE + " = " + Mailbox.TYPE_TRASH
            +     ")"
            + " AND " + FLAG_LOADED_SELECTION;
        /** Selection to retrieve all messages in "inbox" for any account */
        public static final String INBOX_SELECTION =
            MessageColumns.MAILBOX_KEY + " IN ("
            +     "SELECT " + MailboxColumns.ID + " FROM " + Mailbox.TABLE_NAME
            +     " WHERE " + MailboxColumns.TYPE + " = " + Mailbox.TYPE_INBOX
            +     ")"
            + " AND " + FLAG_LOADED_SELECTION;
        /** Selection to retrieve unread messages in "inbox" for any account */
        public static final String UNREAD_SELECTION =
            MessageColumns.FLAG_READ + "=0 AND " + INBOX_SELECTION;
        /** Selection to retrieve all messages in "inbox" for one account */
        public static final String PER_ACCOUNT_INBOX_SELECTION =
            ACCOUNT_KEY_SELECTION + " AND " + INBOX_SELECTION;

        private static final String ACCOUNT_FAVORITE_SELECTION =
            ACCOUNT_KEY_SELECTION + " AND " + ALL_FAVORITE_SELECTION;

        /**
         * Selection for latest incoming messages.  In order to tell whether incoming or not,
         * we need the mailbox type, which is in the mailbox table, not the message table, so
         * use a subquery.
         */
        private static final String LATEST_INCOMING_MESSAGE_SELECTION =
            MessageColumns.MAILBOX_KEY + " IN (SELECT " + RECORD_ID + " FROM " + Mailbox.TABLE_NAME
            + " WHERE " + MailboxColumns.ACCOUNT_KEY + "=? AND "
            + Mailbox.USER_VISIBLE_MAILBOX_SELECTION + " AND "
            + MailboxColumns.TYPE + " NOT IN ("
                + Mailbox.TYPE_DRAFTS + ","
                + Mailbox.TYPE_OUTBOX + ","
                + Mailbox.TYPE_SENT
            + "))";

        // _id field is in AbstractContent
        public String mDisplayName;
        public long mTimeStamp;
        public String mSubject;
        public boolean mFlagRead = false;
        public int mFlagLoaded = FLAG_LOADED_UNLOADED;
        public boolean mFlagFavorite = false;
        public boolean mFlagAttachment = false;
        public int mFlags = 0;

        public String mServerId;
        public long mServerTimeStamp;
        public String mClientId;
        public String mMessageId;

        public long mMailboxKey;
        public long mAccountKey;

        public String mFrom;
        public String mTo;
        public String mCc;
        public String mBcc;
        public String mReplyTo;

        // For now, just the start time of a meeting invite, in ms
        public String mMeetingInfo;

        public String mSnippet;

        // The following transient members may be used while building and manipulating messages,
        // but they are NOT persisted directly by EmailProvider
        transient public String mText;
        transient public String mHtml;
        transient public String mTextReply;
        transient public String mHtmlReply;
        transient public long mSourceKey;
        transient public ArrayList<Attachment> mAttachments = null;
        transient public String mIntroText;

        // Values used in mFlagRead
        public static final int UNREAD = 0;
        public static final int READ = 1;

        // Values used in mFlagLoaded
        public static final int FLAG_LOADED_UNLOADED = 0;
        public static final int FLAG_LOADED_COMPLETE = 1;
        public static final int FLAG_LOADED_PARTIAL = 2;
        public static final int FLAG_LOADED_DELETED = 3;

        // Bits used in mFlags
        // The following three states are mutually exclusive, and indicate whether the message is an
        // original, a reply, or a forward
        public static final int FLAG_TYPE_ORIGINAL = 0;
        public static final int FLAG_TYPE_REPLY = 1<<0;
        public static final int FLAG_TYPE_FORWARD = 1<<1;
        public static final int FLAG_TYPE_MASK = FLAG_TYPE_REPLY | FLAG_TYPE_FORWARD;
        // The following flags indicate messages that are determined to be incoming meeting related
        // (e.g. invites from others)
        public static final int FLAG_INCOMING_MEETING_INVITE = 1<<2;
        public static final int FLAG_INCOMING_MEETING_CANCEL = 1<<3;
        public static final int FLAG_INCOMING_MEETING_MASK =
            FLAG_INCOMING_MEETING_INVITE | FLAG_INCOMING_MEETING_CANCEL;
        // The following flags indicate messages that are outgoing and meeting related
        // (e.g. invites TO others)
        public static final int FLAG_OUTGOING_MEETING_INVITE = 1<<4;
        public static final int FLAG_OUTGOING_MEETING_CANCEL = 1<<5;
        public static final int FLAG_OUTGOING_MEETING_ACCEPT = 1<<6;
        public static final int FLAG_OUTGOING_MEETING_DECLINE = 1<<7;
        public static final int FLAG_OUTGOING_MEETING_TENTATIVE = 1<<8;
        public static final int FLAG_OUTGOING_MEETING_MASK =
            FLAG_OUTGOING_MEETING_INVITE | FLAG_OUTGOING_MEETING_CANCEL |
            FLAG_OUTGOING_MEETING_ACCEPT | FLAG_OUTGOING_MEETING_DECLINE |
            FLAG_OUTGOING_MEETING_TENTATIVE;
        public static final int FLAG_OUTGOING_MEETING_REQUEST_MASK =
            FLAG_OUTGOING_MEETING_INVITE | FLAG_OUTGOING_MEETING_CANCEL;
        // 8 general purpose flags (bits) that may be used at the discretion of the sync adapter
        public static final int FLAG_SYNC_ADAPTER_SHIFT = 9;
        public static final int FLAG_SYNC_ADAPTER_MASK = 255 << FLAG_SYNC_ADAPTER_SHIFT;
        /**
         * Bit used in mFlags indicating that the outgoing message should *not* include quoted
         * original message.  ("Not", in order to keep compatibility with old databases)
         */
        public static final int FLAG_NOT_INCLUDE_QUOTED_TEXT_SHIFT = 17;
        public static final int FLAG_NOT_INCLUDE_QUOTED_TEXT
                = 1 << FLAG_NOT_INCLUDE_QUOTED_TEXT_SHIFT;

        public Message() {
            mBaseUri = CONTENT_URI;
        }

        @Override
        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();

            // Assign values for each row.
            values.put(MessageColumns.DISPLAY_NAME, mDisplayName);
            values.put(MessageColumns.TIMESTAMP, mTimeStamp);
            values.put(MessageColumns.SUBJECT, mSubject);
            values.put(MessageColumns.FLAG_READ, mFlagRead);
            values.put(MessageColumns.FLAG_LOADED, mFlagLoaded);
            values.put(MessageColumns.FLAG_FAVORITE, mFlagFavorite);
            values.put(MessageColumns.FLAG_ATTACHMENT, mFlagAttachment);
            values.put(MessageColumns.FLAGS, mFlags);

            values.put(SyncColumns.SERVER_ID, mServerId);
            values.put(SyncColumns.SERVER_TIMESTAMP, mServerTimeStamp);
            values.put(MessageColumns.CLIENT_ID, mClientId);
            values.put(MessageColumns.MESSAGE_ID, mMessageId);

            values.put(MessageColumns.MAILBOX_KEY, mMailboxKey);
            values.put(MessageColumns.ACCOUNT_KEY, mAccountKey);

            values.put(MessageColumns.FROM_LIST, mFrom);
            values.put(MessageColumns.TO_LIST, mTo);
            values.put(MessageColumns.CC_LIST, mCc);
            values.put(MessageColumns.BCC_LIST, mBcc);
            values.put(MessageColumns.REPLY_TO_LIST, mReplyTo);

            values.put(MessageColumns.MEETING_INFO, mMeetingInfo);

            values.put(MessageColumns.SNIPPET, mSnippet);

            return values;
        }

        public static Message restoreMessageWithId(Context context, long id) {
            Uri u = ContentUris.withAppendedId(Message.CONTENT_URI, id);
            if (context == null) {
                throw new NullPointerException("context");
            }
            ContentResolver resolver = context.getContentResolver();
            if (resolver == null) {
                throw new NullPointerException("resolver");
            }
            Cursor c = resolver.query(u, Message.CONTENT_PROJECTION, null, null, null);

            try {
                if (c.moveToFirst()) {
                    return getContent(c, Message.class);
                } else {
                    return null;
                }
            } finally {
                c.close();
            }
        }

        @Override
        public void restore(Cursor cursor) {
            mBaseUri = CONTENT_URI;
            mId = cursor.getLong(CONTENT_ID_COLUMN);
            mDisplayName = cursor.getString(CONTENT_DISPLAY_NAME_COLUMN);
            mTimeStamp = cursor.getLong(CONTENT_TIMESTAMP_COLUMN);
            mSubject = cursor.getString(CONTENT_SUBJECT_COLUMN);
            mFlagRead = cursor.getInt(CONTENT_FLAG_READ_COLUMN) == 1;
            mFlagLoaded = cursor.getInt(CONTENT_FLAG_LOADED_COLUMN);
            mFlagFavorite = cursor.getInt(CONTENT_FLAG_FAVORITE_COLUMN) == 1;
            mFlagAttachment = cursor.getInt(CONTENT_FLAG_ATTACHMENT_COLUMN) == 1;
            mFlags = cursor.getInt(CONTENT_FLAGS_COLUMN);
            mServerId = cursor.getString(CONTENT_SERVER_ID_COLUMN);
            mServerTimeStamp = cursor.getLong(CONTENT_SERVER_TIMESTAMP_COLUMN);
            mClientId = cursor.getString(CONTENT_CLIENT_ID_COLUMN);
            mMessageId = cursor.getString(CONTENT_MESSAGE_ID_COLUMN);
            mMailboxKey = cursor.getLong(CONTENT_MAILBOX_KEY_COLUMN);
            mAccountKey = cursor.getLong(CONTENT_ACCOUNT_KEY_COLUMN);
            mFrom = cursor.getString(CONTENT_FROM_LIST_COLUMN);
            mTo = cursor.getString(CONTENT_TO_LIST_COLUMN);
            mCc = cursor.getString(CONTENT_CC_LIST_COLUMN);
            mBcc = cursor.getString(CONTENT_BCC_LIST_COLUMN);
            mReplyTo = cursor.getString(CONTENT_REPLY_TO_COLUMN);
            mMeetingInfo = cursor.getString(CONTENT_MEETING_INFO_COLUMN);
            mSnippet = cursor.getString(CONTENT_SNIPPET_COLUMN);
        }

        public boolean update() {
            // TODO Auto-generated method stub
            return false;
        }

        /*
         * Override this so that we can store the Body first and link it to the Message
         * Also, attachments when we get there...
         * (non-Javadoc)
         * @see com.android.email.provider.EmailContent#save(android.content.Context)
         */
        @Override
        public Uri save(Context context) {

            boolean doSave = !isSaved();

            // This logic is in place so I can (a) short circuit the expensive stuff when
            // possible, and (b) override (and throw) if anyone tries to call save() or update()
            // directly for Message, which are unsupported.
            if (mText == null && mHtml == null && mTextReply == null && mHtmlReply == null &&
                    (mAttachments == null || mAttachments.isEmpty())) {
                if (doSave) {
                    return super.save(context);
                } else {
                    // Call update, rather than super.update in case we ever override it
                    if (update(context, toContentValues()) == 1) {
                        return getUri();
                    }
                    return null;
                }
            }

            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
            addSaveOps(ops);
            try {
                ContentProviderResult[] results =
                    context.getContentResolver().applyBatch(AUTHORITY, ops);
                // If saving, set the mId's of the various saved objects
                if (doSave) {
                    Uri u = results[0].uri;
                    mId = Long.parseLong(u.getPathSegments().get(1));
                    if (mAttachments != null) {
                        int resultOffset = 2;
                        for (Attachment a : mAttachments) {
                            // Save the id of the attachment record
                            u = results[resultOffset++].uri;
                            if (u != null) {
                                a.mId = Long.parseLong(u.getPathSegments().get(1));
                            }
                            a.mMessageKey = mId;
                        }
                    }
                    return u;
                } else {
                    return null;
                }
            } catch (RemoteException e) {
                // There is nothing to be done here; fail by returning null
            } catch (OperationApplicationException e) {
                // There is nothing to be done here; fail by returning null
            }
            return null;
        }

        public void addSaveOps(ArrayList<ContentProviderOperation> ops) {
            // First, save the message
            ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(mBaseUri);
            // Generate the snippet here, before we create the CPO for Message
            if (mText != null) {
                mSnippet = Snippet.fromPlainText(mText);
            } else if (mHtml != null) {
                mSnippet = Snippet.fromHtmlText(mHtml);
            }
            ops.add(b.withValues(toContentValues()).build());

            // Create and save the body
            ContentValues cv = new ContentValues();
            if (mText != null) {
                cv.put(Body.TEXT_CONTENT, mText);
            }
            if (mHtml != null) {
                cv.put(Body.HTML_CONTENT, mHtml);
            }
            if (mTextReply != null) {
                cv.put(Body.TEXT_REPLY, mTextReply);
            }
            if (mHtmlReply != null) {
                cv.put(Body.HTML_REPLY, mHtmlReply);
            }
            if (mSourceKey != 0) {
                cv.put(Body.SOURCE_MESSAGE_KEY, mSourceKey);
            }
            if (mIntroText != null) {
                cv.put(Body.INTRO_TEXT, mIntroText);
            }
            b = ContentProviderOperation.newInsert(Body.CONTENT_URI);
            b.withValues(cv);
            ContentValues backValues = new ContentValues();
            int messageBackValue = ops.size() - 1;
            backValues.put(Body.MESSAGE_KEY, messageBackValue);
            ops.add(b.withValueBackReferences(backValues).build());

            // Create the attaachments, if any
            if (mAttachments != null) {
                for (Attachment att: mAttachments) {
                    ops.add(ContentProviderOperation.newInsert(Attachment.CONTENT_URI)
                        .withValues(att.toContentValues())
                        .withValueBackReference(Attachment.MESSAGE_KEY, messageBackValue)
                        .build());
                }
            }
        }

        /**
         * @return number of favorite (starred) messages throughout all accounts.
         */
        public static int getFavoriteMessageCount(Context context) {
            return count(context, Message.CONTENT_URI, ALL_FAVORITE_SELECTION, null);
        }

        /**
         * @return number of favorite (starred) messages for an account
         */
        public static int getFavoriteMessageCount(Context context, long accountId) {
            return count(context, Message.CONTENT_URI, ACCOUNT_FAVORITE_SELECTION,
                    new String[]{Long.toString(accountId)});
        }

        public static long getKeyColumnLong(Context context, long messageId, String column) {
            String[] columns =
                Utility.getRowColumns(context, Message.CONTENT_URI, messageId, column);
            if (columns != null && columns[0] != null) {
                return Long.parseLong(columns[0]);
            }
            return -1;
        }

        /**
         * @return the latest messages on an account.
         */
        public static Message getLatestIncomingMessage(Context context, Long accountId) {
            Cursor c = context.getContentResolver().query(Message.CONTENT_URI_LIMIT_1,
                    Message.CONTENT_PROJECTION,
                    LATEST_INCOMING_MESSAGE_SELECTION, new String[] {Long.toString(accountId)},
                    EmailContent.MessageColumns.TIMESTAMP + " DESC");
            try {
                if (c.moveToFirst()) {
                    Message m = new Message();
                    m.restore(c);
                    return m;
                }
            } finally {
                c.close();
            }
            return null; // not found;
        }
    }

    public interface AccountColumns {
        public static final String ID = "_id";
        // The display name of the account (user-settable)
        public static final String DISPLAY_NAME = "displayName";
        // The email address corresponding to this account
        public static final String EMAIL_ADDRESS = "emailAddress";
        // A server-based sync key on an account-wide basis (EAS needs this)
        public static final String SYNC_KEY = "syncKey";
        // The default sync lookback period for this account
        public static final String SYNC_LOOKBACK = "syncLookback";
        // The default sync frequency for this account, in minutes
        public static final String SYNC_INTERVAL = "syncInterval";
        // A foreign key into the account manager, having host, login, password, port, and ssl flags
        public static final String HOST_AUTH_KEY_RECV = "hostAuthKeyRecv";
        // (optional) A foreign key into the account manager, having host, login, password, port,
        // and ssl flags
        public static final String HOST_AUTH_KEY_SEND = "hostAuthKeySend";
        // Flags
        public static final String FLAGS = "flags";
        // Default account
        public static final String IS_DEFAULT = "isDefault";
        // Old-Style UUID for compatibility with previous versions
        public static final String COMPATIBILITY_UUID = "compatibilityUuid";
        // User name (for outgoing messages)
        public static final String SENDER_NAME = "senderName";
        // Ringtone
        public static final String RINGTONE_URI = "ringtoneUri";
        // Protocol version (arbitrary string, used by EAS currently)
        public static final String PROTOCOL_VERSION = "protocolVersion";
        // The number of new messages (reported by the sync/download engines
        public static final String NEW_MESSAGE_COUNT = "newMessageCount";
        // Flags defining security (provisioning) requirements of this account
        public static final String SECURITY_FLAGS = "securityFlags";
        // Server-based sync key for the security policies currently enforced
        public static final String SECURITY_SYNC_KEY = "securitySyncKey";
        // Signature to use with this account
        public static final String SIGNATURE = "signature";
    }

    public static final class Account extends EmailContent implements AccountColumns, Parcelable {
        public static final String TABLE_NAME = "Account";
        @SuppressWarnings("hiding")
        public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/account");
        public static final Uri ADD_TO_FIELD_URI =
            Uri.parse(EmailContent.CONTENT_URI + "/accountIdAddToField");
        public static final Uri RESET_NEW_MESSAGE_COUNT_URI =
            Uri.parse(EmailContent.CONTENT_URI + "/resetNewMessageCount");

        /**
         * Value used by UI to represent "combined view".
         *
         * NOTE: This must be used only by UI, and mustn't be stored in the database.
         *
         * This is defined here to avoid conflict with other pseudo account IDs, if any.
         */
        public static final long ACCOUNT_ID_COMBINED_VIEW = 0x1000000000000000L;

        public final static int FLAGS_NOTIFY_NEW_MAIL = 1;
        public final static int FLAGS_VIBRATE_ALWAYS = 2;
        public static final int FLAGS_DELETE_POLICY_MASK = 4+8;
        public static final int FLAGS_DELETE_POLICY_SHIFT = 2;
        public static final int FLAGS_INCOMPLETE = 16;
        public static final int FLAGS_SECURITY_HOLD = 32;
        public static final int FLAGS_VIBRATE_WHEN_SILENT = 64;
        public static final int FLAGS_SUPPORTS_SMART_FORWARD = 128;
        public static final int FLAGS_BACKGROUND_ATTACHMENTS = 256;
        // Available to sync adapter
        public static final int FLAGS_SYNC_ADAPTER = 512;

        public static final int DELETE_POLICY_NEVER = 0;
        public static final int DELETE_POLICY_7DAYS = 1;        // not supported
        public static final int DELETE_POLICY_ON_DELETE = 2;

        // Sentinel values for the mSyncInterval field of both Account records
        public static final int CHECK_INTERVAL_NEVER = -1;
        public static final int CHECK_INTERVAL_PUSH = -2;

        public String mDisplayName;
        public String mEmailAddress;
        public String mSyncKey;
        public int mSyncLookback;
        public int mSyncInterval;
        public long mHostAuthKeyRecv;
        public long mHostAuthKeySend;
        public int mFlags;
        public boolean mIsDefault;          // note: callers should use getDefaultAccountId()
        public String mCompatibilityUuid;
        public String mSenderName;
        public String mRingtoneUri;
        public String mProtocolVersion;
        public int mNewMessageCount;
        public long mSecurityFlags;
        public String mSecuritySyncKey;
        public String mSignature;

        // Convenience for creating an account
        public transient HostAuth mHostAuthRecv;
        public transient HostAuth mHostAuthSend;

        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_DISPLAY_NAME_COLUMN = 1;
        public static final int CONTENT_EMAIL_ADDRESS_COLUMN = 2;
        public static final int CONTENT_SYNC_KEY_COLUMN = 3;
        public static final int CONTENT_SYNC_LOOKBACK_COLUMN = 4;
        public static final int CONTENT_SYNC_INTERVAL_COLUMN = 5;
        public static final int CONTENT_HOST_AUTH_KEY_RECV_COLUMN = 6;
        public static final int CONTENT_HOST_AUTH_KEY_SEND_COLUMN = 7;
        public static final int CONTENT_FLAGS_COLUMN = 8;
        public static final int CONTENT_IS_DEFAULT_COLUMN = 9;
        public static final int CONTENT_COMPATIBILITY_UUID_COLUMN = 10;
        public static final int CONTENT_SENDER_NAME_COLUMN = 11;
        public static final int CONTENT_RINGTONE_URI_COLUMN = 12;
        public static final int CONTENT_PROTOCOL_VERSION_COLUMN = 13;
        public static final int CONTENT_NEW_MESSAGE_COUNT_COLUMN = 14;
        public static final int CONTENT_SECURITY_FLAGS_COLUMN = 15;
        public static final int CONTENT_SECURITY_SYNC_KEY_COLUMN = 16;
        public static final int CONTENT_SIGNATURE_COLUMN = 17;

        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, AccountColumns.DISPLAY_NAME,
            AccountColumns.EMAIL_ADDRESS, AccountColumns.SYNC_KEY, AccountColumns.SYNC_LOOKBACK,
            AccountColumns.SYNC_INTERVAL, AccountColumns.HOST_AUTH_KEY_RECV,
            AccountColumns.HOST_AUTH_KEY_SEND, AccountColumns.FLAGS, AccountColumns.IS_DEFAULT,
            AccountColumns.COMPATIBILITY_UUID, AccountColumns.SENDER_NAME,
            AccountColumns.RINGTONE_URI, AccountColumns.PROTOCOL_VERSION,
            AccountColumns.NEW_MESSAGE_COUNT, AccountColumns.SECURITY_FLAGS,
            AccountColumns.SECURITY_SYNC_KEY, AccountColumns.SIGNATURE
        };

        public static final int CONTENT_MAILBOX_TYPE_COLUMN = 1;

        /**
         * This projection is for listing account id's only
         */
        public static final String[] ID_TYPE_PROJECTION = new String[] {
            RECORD_ID, MailboxColumns.TYPE
        };

        public static final int ACCOUNT_FLAGS_COLUMN_ID = 0;
        public static final int ACCOUNT_FLAGS_COLUMN_FLAGS = 1;
        public static final String[] ACCOUNT_FLAGS_PROJECTION = new String[] {
                AccountColumns.ID, AccountColumns.FLAGS};

        public static final String MAILBOX_SELECTION =
            MessageColumns.MAILBOX_KEY + " =?";

        public static final String UNREAD_COUNT_SELECTION =
            MessageColumns.MAILBOX_KEY + " =? and " + MessageColumns.FLAG_READ + "= 0";

        public static final String UUID_SELECTION = AccountColumns.COMPATIBILITY_UUID + " =?";

        public static final String SECURITY_NONZERO_SELECTION =
            Account.SECURITY_FLAGS + " IS NOT NULL AND " + Account.SECURITY_FLAGS + "!=0";

        private static final String FIND_INBOX_SELECTION =
                MailboxColumns.TYPE + " = " + Mailbox.TYPE_INBOX +
                " AND " + MailboxColumns.ACCOUNT_KEY + " =?";

        /**
         * This projection is for searching for the default account
         */
        private static final String[] DEFAULT_ID_PROJECTION = new String[] {
            RECORD_ID, IS_DEFAULT
        };

        /**
         * no public constructor since this is a utility class
         */
        public Account() {
            mBaseUri = CONTENT_URI;

            // other defaults (policy)
            mRingtoneUri = "content://settings/system/notification_sound";
            mSyncInterval = -1;
            mSyncLookback = -1;
            mFlags = FLAGS_NOTIFY_NEW_MAIL;
            mCompatibilityUuid = UUID.randomUUID().toString();
        }

        public static Account restoreAccountWithId(Context context, long id) {
            Uri u = ContentUris.withAppendedId(Account.CONTENT_URI, id);
            Cursor c = context.getContentResolver().query(u, Account.CONTENT_PROJECTION,
                    null, null, null);

            try {
                if (c.moveToFirst()) {
                    return getContent(c, Account.class);
                } else {
                    return null;
                }
            } finally {
                c.close();
            }
        }

        /**
         * Refresh an account that has already been loaded.  This is slightly less expensive
         * that generating a brand-new account object.
         */
        public void refresh(Context context) {
            Cursor c = context.getContentResolver().query(this.getUri(), Account.CONTENT_PROJECTION,
                    null, null, null);
            try {
                c.moveToFirst();
                restore(c);
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        }

        @Override
        public void restore(Cursor cursor) {
            mId = cursor.getLong(CONTENT_ID_COLUMN);
            mBaseUri = CONTENT_URI;
            mDisplayName = cursor.getString(CONTENT_DISPLAY_NAME_COLUMN);
            mEmailAddress = cursor.getString(CONTENT_EMAIL_ADDRESS_COLUMN);
            mSyncKey = cursor.getString(CONTENT_SYNC_KEY_COLUMN);
            mSyncLookback = cursor.getInt(CONTENT_SYNC_LOOKBACK_COLUMN);
            mSyncInterval = cursor.getInt(CONTENT_SYNC_INTERVAL_COLUMN);
            mHostAuthKeyRecv = cursor.getLong(CONTENT_HOST_AUTH_KEY_RECV_COLUMN);
            mHostAuthKeySend = cursor.getLong(CONTENT_HOST_AUTH_KEY_SEND_COLUMN);
            mFlags = cursor.getInt(CONTENT_FLAGS_COLUMN);
            mIsDefault = cursor.getInt(CONTENT_IS_DEFAULT_COLUMN) == 1;
            mCompatibilityUuid = cursor.getString(CONTENT_COMPATIBILITY_UUID_COLUMN);
            mSenderName = cursor.getString(CONTENT_SENDER_NAME_COLUMN);
            mRingtoneUri = cursor.getString(CONTENT_RINGTONE_URI_COLUMN);
            mProtocolVersion = cursor.getString(CONTENT_PROTOCOL_VERSION_COLUMN);
            mNewMessageCount = cursor.getInt(CONTENT_NEW_MESSAGE_COUNT_COLUMN);
            mSecurityFlags = cursor.getLong(CONTENT_SECURITY_FLAGS_COLUMN);
            mSecuritySyncKey = cursor.getString(CONTENT_SECURITY_SYNC_KEY_COLUMN);
            mSignature = cursor.getString(CONTENT_SIGNATURE_COLUMN);
        }

        private long getId(Uri u) {
            return Long.parseLong(u.getPathSegments().get(1));
        }

        /**
         * @return the user-visible name for the account
         */
        public String getDisplayName() {
            return mDisplayName;
        }

        /**
         * Set the description.  Be sure to call save() to commit to database.
         * @param description the new description
         */
        public void setDisplayName(String description) {
            mDisplayName = description;
        }

        /**
         * @return the email address for this account
         */
        public String getEmailAddress() {
            return mEmailAddress;
        }

        /**
         * Set the Email address for this account.  Be sure to call save() to commit to database.
         * @param emailAddress the new email address for this account
         */
        public void setEmailAddress(String emailAddress) {
            mEmailAddress = emailAddress;
        }

        /**
         * @return the sender's name for this account
         */
        public String getSenderName() {
            return mSenderName;
        }

        /**
         * Set the sender's name.  Be sure to call save() to commit to database.
         * @param name the new sender name
         */
        public void setSenderName(String name) {
            mSenderName = name;
        }

        public String getSignature() {
            return mSignature;
        }

        public void setSignature(String signature) {
            mSignature = signature;
        }


        /**
         * @return the minutes per check (for polling)
         * TODO define sentinel values for "never", "push", etc.  See Account.java
         */
        public int getSyncInterval()
        {
            return mSyncInterval;
        }

        /**
         * Set the minutes per check (for polling).  Be sure to call save() to commit to database.
         * TODO define sentinel values for "never", "push", etc.  See Account.java
         * @param minutes the number of minutes between polling checks
         */
        public void setSyncInterval(int minutes)
        {
            mSyncInterval = minutes;
        }

        /**
         * @return One of the {@code Account.SYNC_WINDOW_*} constants that represents the sync
         *     lookback window.
         * TODO define sentinel values for "all", "1 month", etc.  See Account.java
         */
        public int getSyncLookback() {
            return mSyncLookback;
        }

        /**
         * Set the sync lookback window.  Be sure to call save() to commit to database.
         * TODO define sentinel values for "all", "1 month", etc.  See Account.java
         * @param value One of the {@code Account.SYNC_WINDOW_*} constants
         */
        public void setSyncLookback(int value) {
            mSyncLookback = value;
        }

        /**
         * @return the flags for this account
         * @see #FLAGS_NOTIFY_NEW_MAIL
         * @see #FLAGS_VIBRATE_ALWAYS
         * @see #FLAGS_VIBRATE_WHEN_SILENT
         */
        public int getFlags() {
            return mFlags;
        }

        /**
         * Set the flags for this account
         * @see #FLAGS_NOTIFY_NEW_MAIL
         * @see #FLAGS_VIBRATE_ALWAYS
         * @see #FLAGS_VIBRATE_WHEN_SILENT
         * @param newFlags the new value for the flags
         */
        public void setFlags(int newFlags) {
            mFlags = newFlags;
        }

        /**
         * @return the ringtone Uri for this account
         */
        public String getRingtone() {
            return mRingtoneUri;
        }

        /**
         * Set the ringtone Uri for this account
         * @param newUri the new URI string for the ringtone for this account
         */
        public void setRingtone(String newUri) {
            mRingtoneUri = newUri;
        }

        /**
         * Set the "delete policy" as a simple 0,1,2 value set.
         * @param newPolicy the new delete policy
         */
        public void setDeletePolicy(int newPolicy) {
            mFlags &= ~FLAGS_DELETE_POLICY_MASK;
            mFlags |= (newPolicy << FLAGS_DELETE_POLICY_SHIFT) & FLAGS_DELETE_POLICY_MASK;
        }

        /**
         * Return the "delete policy" as a simple 0,1,2 value set.
         * @return the current delete policy
         */
        public int getDeletePolicy() {
            return (mFlags & FLAGS_DELETE_POLICY_MASK) >> FLAGS_DELETE_POLICY_SHIFT;
        }

        /**
         * Return the Uuid associated with this account.  This is primarily for compatibility
         * with accounts set up by previous versions, because there are externals references
         * to the Uuid (e.g. desktop shortcuts).
         */
        public String getUuid() {
            return mCompatibilityUuid;
        }

        /**
         * For compatibility while converting to provider model, generate a "store URI"
         *
         * @return a string in the form of a Uri, as used by the other parts of the email app
         */
        public String getStoreUri(Context context) {
            // reconstitute if necessary
            if (mHostAuthRecv == null) {
                mHostAuthRecv = HostAuth.restoreHostAuthWithId(context, mHostAuthKeyRecv);
            }
            // convert if available
            if (mHostAuthRecv != null) {
                String storeUri = mHostAuthRecv.getStoreUri();
                if (storeUri != null) {
                    return storeUri;
                }
            }
            return "";
        }

        /**
         * For compatibility while converting to provider model, generate a "sender URI"
         *
         * @return a string in the form of a Uri, as used by the other parts of the email app
         */
        public String getSenderUri(Context context) {
            // reconstitute if necessary
            if (mHostAuthSend == null) {
                mHostAuthSend = HostAuth.restoreHostAuthWithId(context, mHostAuthKeySend);
            }
            // convert if available
            if (mHostAuthSend != null) {
                String senderUri = mHostAuthSend.getStoreUri();
                if (senderUri != null) {
                    return senderUri;
                }
            }
            return "";
        }

        public HostAuth getOrCreateHostAuthSend(Context context) {
            if (mHostAuthSend == null) {
                if (mHostAuthKeySend != 0) {
                    mHostAuthSend = HostAuth.restoreHostAuthWithId(context, mHostAuthKeySend);
                } else {
                    mHostAuthSend = new EmailContent.HostAuth();
                }
            }
            return mHostAuthSend;
        }

        public HostAuth getOrCreateHostAuthRecv(Context context) {
            if (mHostAuthRecv == null) {
                if (mHostAuthKeyRecv != 0) {
                    mHostAuthRecv = HostAuth.restoreHostAuthWithId(context, mHostAuthKeyRecv);
                } else {
                    mHostAuthRecv = new EmailContent.HostAuth();
                }
            }
            return mHostAuthRecv;
        }

        /**
         * For compatibility while converting to provider model, generate a "local store URI"
         *
         * @return a string in the form of a Uri, as used by the other parts of the email app
         */
        public String getLocalStoreUri(Context context) {
            return "local://localhost/" + context.getDatabasePath(getUuid() + ".db");
        }

        /**
         * @return true if the instance is of an EAS account.
         *
         * NOTE This method accesses the DB if {@link #mHostAuthRecv} hasn't been restored yet.
         * Use caution when you use this on the main thread.
         */
        public boolean isEasAccount(Context context) {
            return "eas".equals(getProtocol(context));
        }

        /**
         * @return true if the account supports "move messages".
         */
        public static boolean supportsMoveMessages(Context context, long accountId) {
            String protocol = getProtocol(context, accountId);
            return "eas".equals(protocol) || "imap".equals(protocol);
        }

        /**
         * Set the account to be the default account.  If this is set to "true", when the account
         * is saved, all other accounts will have the same value set to "false".
         * @param newDefaultState the new default state - if true, others will be cleared.
         */
        public void setDefaultAccount(boolean newDefaultState) {
            mIsDefault = newDefaultState;
        }

        /**
         * Helper method for finding the default account.
         */
        static private long getDefaultAccountWhere(Context context, String where) {
            return Utility.getFirstRowLong(context, CONTENT_URI,
                    DEFAULT_ID_PROJECTION,
                    where, null, null, 0, Long.valueOf(-1));
        }

        /**
         * @return {@link Uri} to this {@link Account} in the
         * {@code content://com.android.email.provider/account/UUID} format, which is safe to use
         * for desktop shortcuts.
         *
         * <p>We don't want to store _id in shortcuts, because
         * {@link com.android.email.AccountBackupRestore} won't preserve it.
         */
        public Uri getShortcutSafeUri() {
            return getShortcutSafeUriFromUuid(mCompatibilityUuid);
        }

        /**
         * @return {@link Uri} to an {@link Account} with a {@code uuid}.
         */
        public static Uri getShortcutSafeUriFromUuid(String uuid) {
            return CONTENT_URI.buildUpon().appendEncodedPath(uuid).build();
        }

        /**
         * Parse {@link Uri} in the {@code content://com.android.email.provider/account/ID} format
         * where ID = account id (used on Eclair, Android 2.0-2.1) or UUID, and return _id of
         * the {@link Account} associated with it.
         *
         * @param context context to access DB
         * @param uri URI of interest
         * @return _id of the {@link Account} associated with ID, or -1 if none found.
         */
        public static long getAccountIdFromShortcutSafeUri(Context context, Uri uri) {
            // Make sure the URI is in the correct format.
            if (!"content".equals(uri.getScheme())
                    || !AUTHORITY.equals(uri.getAuthority())) {
                return -1;
            }

            final List<String> ps = uri.getPathSegments();
            if (ps.size() != 2 || !"account".equals(ps.get(0))) {
                return -1;
            }

            // Now get the ID part.
            final String id = ps.get(1);

            // First, see if ID can be parsed as long.  (Eclair-style)
            // (UUIDs have '-' in them, so they are always non-parsable.)
            try {
                return Long.parseLong(id);
            } catch (NumberFormatException ok) {
                // OK, it's not a long.  Continue...
            }

            // Now id is a UUId.
            return Utility.getFirstRowLong(context,
                    CONTENT_URI, ID_PROJECTION,
                    UUID_SELECTION, new String[] {id}, null, 0, Long.valueOf(-1));
        }

        /**
         * Return the id of the default account.  If one hasn't been explicitly specified, return
         * the first one in the database.  For any account saved in the DB, this must be used
         * to check for the default account - the mIsDefault field is set lazily and may be
         * incorrect.
         * @param context the caller's context
         * @return the id of the default account, or -1 if there are no accounts
         */
        static public long getDefaultAccountId(Context context) {
            long id = getDefaultAccountWhere(context, AccountColumns.IS_DEFAULT + "=1");
            if (id == -1) {
                id = getDefaultAccountWhere(context, null);
            }
            return id;
        }

        /**
         * Given an account id, return the account's protocol
         * @param context the caller's context
         * @param accountId the id of the account to be examined
         * @return the account's protocol (or null if the Account or HostAuth do not exist)
         */
        public static String getProtocol(Context context, long accountId) {
            Account account = Account.restoreAccountWithId(context, accountId);
            if (account != null) {
                return account.getProtocol(context);
             }
            return null;
        }

        /**
         * Return the account's protocol
         * @param context the caller's context
         * @return the account's protocol (or null if the HostAuth doesn't not exist)
         */
        public String getProtocol(Context context) {
            HostAuth hostAuth = HostAuth.restoreHostAuthWithId(context, mHostAuthKeyRecv);
            if (hostAuth != null) {
                return hostAuth.mProtocol;
            }
            return null;
        }

        /**
         * Return the account ID for a message with a given id
         *
         * @param context the caller's context
         * @param messageId the id of the message
         * @return the account ID, or -1 if the account doesn't exist
         */
        public static long getAccountIdForMessageId(Context context, long messageId) {
            return Message.getKeyColumnLong(context, messageId, MessageColumns.ACCOUNT_KEY);
        }

        /**
         * Return the account for a message with a given id
         * @param context the caller's context
         * @param messageId the id of the message
         * @return the account, or null if the account doesn't exist
         */
        public static Account getAccountForMessageId(Context context, long messageId) {
            long accountId = getAccountIdForMessageId(context, messageId);
            if (accountId != -1) {
                return Account.restoreAccountWithId(context, accountId);
            }
            return null;
        }

        /**
         * @return true if an {@code accountId} is assigned to any existing account.
         */
        public static boolean isValidId(Context context, long accountId) {
            return null != Utility.getFirstRowLong(context, CONTENT_URI, ID_PROJECTION,
                    ID_SELECTION, new String[] {Long.toString(accountId)}, null,
                    ID_PROJECTION_COLUMN);
        }

        /**
         * Check a single account for security hold status.
         */
        public static boolean isSecurityHold(Context context, long accountId) {
            return (Utility.getFirstRowLong(context,
                    ContentUris.withAppendedId(Account.CONTENT_URI, accountId),
                    ACCOUNT_FLAGS_PROJECTION, null, null, null, ACCOUNT_FLAGS_COLUMN_FLAGS, 0L)
                    & Account.FLAGS_SECURITY_HOLD) != 0;
        }

        /**
         * @return id of the "inbox" mailbox, or -1 if not found.
         */
        public static long getInboxId(Context context, long accountId) {
            return Utility.getFirstRowLong(context, Mailbox.CONTENT_URI, ID_PROJECTION,
                    FIND_INBOX_SELECTION, new String[] {Long.toString(accountId)}, null,
                    ID_PROJECTION_COLUMN, -1L);
        }

        /**
         * Clear all account hold flags that are set.
         *
         * (This will trigger watchers, and in particular will cause EAS to try and resync the
         * account(s).)
         */
        public static void clearSecurityHoldOnAllAccounts(Context context) {
            ContentResolver resolver = context.getContentResolver();
            Cursor c = resolver.query(Account.CONTENT_URI, ACCOUNT_FLAGS_PROJECTION,
                    SECURITY_NONZERO_SELECTION, null, null);
            try {
                while (c.moveToNext()) {
                    int flags = c.getInt(ACCOUNT_FLAGS_COLUMN_FLAGS);

                    if (0 != (flags & FLAGS_SECURITY_HOLD)) {
                        ContentValues cv = new ContentValues();
                        cv.put(AccountColumns.FLAGS, flags & ~FLAGS_SECURITY_HOLD);
                        long accountId = c.getLong(ACCOUNT_FLAGS_COLUMN_ID);
                        Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, accountId);
                        resolver.update(uri, cv, null, null);
                    }
                }
            } finally {
                c.close();
            }
        }

        /**
         * Override update to enforce a single default account, and do it atomically
         */
        @Override
        public int update(Context context, ContentValues cv) {
            if (cv.containsKey(AccountColumns.IS_DEFAULT) &&
                    cv.getAsBoolean(AccountColumns.IS_DEFAULT)) {
                ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
                ContentValues cv1 = new ContentValues();
                cv1.put(AccountColumns.IS_DEFAULT, false);
                // Clear the default flag in all accounts
                ops.add(ContentProviderOperation.newUpdate(CONTENT_URI).withValues(cv1).build());
                // Update this account
                ops.add(ContentProviderOperation
                        .newUpdate(ContentUris.withAppendedId(CONTENT_URI, mId))
                        .withValues(cv).build());
                try {
                    context.getContentResolver().applyBatch(AUTHORITY, ops);
                    return 1;
                } catch (RemoteException e) {
                    // There is nothing to be done here; fail by returning 0
                } catch (OperationApplicationException e) {
                    // There is nothing to be done here; fail by returning 0
                }
                return 0;
            }
            return super.update(context, cv);
        }

        /*
         * Override this so that we can store the HostAuth's first and link them to the Account
         * (non-Javadoc)
         * @see com.android.email.provider.EmailContent#save(android.content.Context)
         */
        @Override
        public Uri save(Context context) {
            if (isSaved()) {
                throw new UnsupportedOperationException();
            }
            // This logic is in place so I can (a) short circuit the expensive stuff when
            // possible, and (b) override (and throw) if anyone tries to call save() or update()
            // directly for Account, which are unsupported.
            if (mHostAuthRecv == null && mHostAuthSend == null && mIsDefault == false) {
                    return super.save(context);
            }

            int index = 0;
            int recvIndex = -1;
            int sendIndex = -1;

            // Create operations for saving the send and recv hostAuths
            // Also, remember which operation in the array they represent
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
            if (mHostAuthRecv != null) {
                recvIndex = index++;
                ops.add(ContentProviderOperation.newInsert(mHostAuthRecv.mBaseUri)
                        .withValues(mHostAuthRecv.toContentValues())
                        .build());
            }
            if (mHostAuthSend != null) {
                sendIndex = index++;
                ops.add(ContentProviderOperation.newInsert(mHostAuthSend.mBaseUri)
                        .withValues(mHostAuthSend.toContentValues())
                        .build());
            }

            // Create operations for making this the only default account
            // Note, these are always updates because they change existing accounts
            if (mIsDefault) {
                index++;
                ContentValues cv1 = new ContentValues();
                cv1.put(AccountColumns.IS_DEFAULT, 0);
                ops.add(ContentProviderOperation.newUpdate(CONTENT_URI).withValues(cv1).build());
            }

            // Now do the Account
            ContentValues cv = null;
            if (recvIndex >= 0 || sendIndex >= 0) {
                cv = new ContentValues();
                if (recvIndex >= 0) {
                    cv.put(Account.HOST_AUTH_KEY_RECV, recvIndex);
                }
                if (sendIndex >= 0) {
                    cv.put(Account.HOST_AUTH_KEY_SEND, sendIndex);
                }
            }

            ContentProviderOperation.Builder b = ContentProviderOperation.newInsert(mBaseUri);
            b.withValues(toContentValues());
            if (cv != null) {
                b.withValueBackReferences(cv);
            }
            ops.add(b.build());

            try {
                ContentProviderResult[] results =
                    context.getContentResolver().applyBatch(AUTHORITY, ops);
                // If saving, set the mId's of the various saved objects
                if (recvIndex >= 0) {
                    long newId = getId(results[recvIndex].uri);
                    mHostAuthKeyRecv = newId;
                    mHostAuthRecv.mId = newId;
                }
                if (sendIndex >= 0) {
                    long newId = getId(results[sendIndex].uri);
                    mHostAuthKeySend = newId;
                    mHostAuthSend.mId = newId;
                }
                Uri u = results[index].uri;
                mId = getId(u);
                return u;
            } catch (RemoteException e) {
                // There is nothing to be done here; fail by returning null
            } catch (OperationApplicationException e) {
                // There is nothing to be done here; fail by returning null
            }
            return null;
        }

        @Override
        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();
            values.put(AccountColumns.DISPLAY_NAME, mDisplayName);
            values.put(AccountColumns.EMAIL_ADDRESS, mEmailAddress);
            values.put(AccountColumns.SYNC_KEY, mSyncKey);
            values.put(AccountColumns.SYNC_LOOKBACK, mSyncLookback);
            values.put(AccountColumns.SYNC_INTERVAL, mSyncInterval);
            values.put(AccountColumns.HOST_AUTH_KEY_RECV, mHostAuthKeyRecv);
            values.put(AccountColumns.HOST_AUTH_KEY_SEND, mHostAuthKeySend);
            values.put(AccountColumns.FLAGS, mFlags);
            values.put(AccountColumns.IS_DEFAULT, mIsDefault);
            values.put(AccountColumns.COMPATIBILITY_UUID, mCompatibilityUuid);
            values.put(AccountColumns.SENDER_NAME, mSenderName);
            values.put(AccountColumns.RINGTONE_URI, mRingtoneUri);
            values.put(AccountColumns.PROTOCOL_VERSION, mProtocolVersion);
            values.put(AccountColumns.NEW_MESSAGE_COUNT, mNewMessageCount);
            values.put(AccountColumns.SECURITY_FLAGS, mSecurityFlags);
            values.put(AccountColumns.SECURITY_SYNC_KEY, mSecuritySyncKey);
            values.put(AccountColumns.SIGNATURE, mSignature);
            return values;
        }

        /**
         * Supports Parcelable
         */
        public int describeContents() {
            return 0;
        }

        /**
         * Supports Parcelable
         */
        public static final Parcelable.Creator<EmailContent.Account> CREATOR
                = new Parcelable.Creator<EmailContent.Account>() {
            public EmailContent.Account createFromParcel(Parcel in) {
                return new EmailContent.Account(in);
            }

            public EmailContent.Account[] newArray(int size) {
                return new EmailContent.Account[size];
            }
        };

        /**
         * Supports Parcelable
         */
        public void writeToParcel(Parcel dest, int flags) {
            // mBaseUri is not parceled
            dest.writeLong(mId);
            dest.writeString(mDisplayName);
            dest.writeString(mEmailAddress);
            dest.writeString(mSyncKey);
            dest.writeInt(mSyncLookback);
            dest.writeInt(mSyncInterval);
            dest.writeLong(mHostAuthKeyRecv);
            dest.writeLong(mHostAuthKeySend);
            dest.writeInt(mFlags);
            dest.writeByte(mIsDefault ? (byte)1 : (byte)0);
            dest.writeString(mCompatibilityUuid);
            dest.writeString(mSenderName);
            dest.writeString(mRingtoneUri);
            dest.writeString(mProtocolVersion);
            dest.writeInt(mNewMessageCount);
            dest.writeLong(mSecurityFlags);
            dest.writeString(mSecuritySyncKey);
            dest.writeString(mSignature);

            if (mHostAuthRecv != null) {
                dest.writeByte((byte)1);
                mHostAuthRecv.writeToParcel(dest, flags);
            } else {
                dest.writeByte((byte)0);
            }

            if (mHostAuthSend != null) {
                dest.writeByte((byte)1);
                mHostAuthSend.writeToParcel(dest, flags);
            } else {
                dest.writeByte((byte)0);
            }
        }

        /**
         * Supports Parcelable
         */
        public Account(Parcel in) {
            mBaseUri = EmailContent.Account.CONTENT_URI;
            mId = in.readLong();
            mDisplayName = in.readString();
            mEmailAddress = in.readString();
            mSyncKey = in.readString();
            mSyncLookback = in.readInt();
            mSyncInterval = in.readInt();
            mHostAuthKeyRecv = in.readLong();
            mHostAuthKeySend = in.readLong();
            mFlags = in.readInt();
            mIsDefault = in.readByte() == 1;
            mCompatibilityUuid = in.readString();
            mSenderName = in.readString();
            mRingtoneUri = in.readString();
            mProtocolVersion = in.readString();
            mNewMessageCount = in.readInt();
            mSecurityFlags = in.readLong();
            mSecuritySyncKey = in.readString();
            mSignature = in.readString();

            mHostAuthRecv = null;
            if (in.readByte() == 1) {
                mHostAuthRecv = new EmailContent.HostAuth(in);
            }

            mHostAuthSend = null;
            if (in.readByte() == 1) {
                mHostAuthSend = new EmailContent.HostAuth(in);
            }
        }

        /**
         * For debugger support only - DO NOT use for code.
         */
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder('[');
            if (mHostAuthRecv != null && mHostAuthRecv.mProtocol != null) {
                sb.append(mHostAuthRecv.mProtocol);
                sb.append(':');
            }
            if (mDisplayName != null)   sb.append(mDisplayName);
            sb.append(':');
            if (mEmailAddress != null)  sb.append(mEmailAddress);
            sb.append(':');
            if (mSenderName != null)    sb.append(mSenderName);
            sb.append(']');
            return sb.toString();
        }

    }

    public interface AttachmentColumns {
        public static final String ID = "_id";
        // The display name of the attachment
        public static final String FILENAME = "fileName";
        // The mime type of the attachment
        public static final String MIME_TYPE = "mimeType";
        // The size of the attachment in bytes
        public static final String SIZE = "size";
        // The (internal) contentId of the attachment (inline attachments will have these)
        public static final String CONTENT_ID = "contentId";
        // The location of the loaded attachment (probably a file)
        @SuppressWarnings("hiding")
        public static final String CONTENT_URI = "contentUri";
        // A foreign key into the Message table (the message owning this attachment)
        public static final String MESSAGE_KEY = "messageKey";
        // The location of the attachment on the server side
        // For IMAP, this is a part number (e.g. 2.1); for EAS, it's the internal file name
        public static final String LOCATION = "location";
        // The transfer encoding of the attachment
        public static final String ENCODING = "encoding";
        // Not currently used
        public static final String CONTENT = "content";
        // Flags
        public static final String FLAGS = "flags";
        // Content that is actually contained in the Attachment row
        public static final String CONTENT_BYTES = "content_bytes";
        // A foreign key into the Account table (for the message owning this attachment)
        public static final String ACCOUNT_KEY = "accountKey";
    }

    public static final class Attachment extends EmailContent implements AttachmentColumns {
        public static final String TABLE_NAME = "Attachment";
        @SuppressWarnings("hiding")
        public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/attachment");
        // This must be used with an appended id: ContentUris.withAppendedId(MESSAGE_ID_URI, id)
        public static final Uri MESSAGE_ID_URI = Uri.parse(
                EmailContent.CONTENT_URI + "/attachment/message");

        public String mFileName;
        public String mMimeType;
        public long mSize;
        public String mContentId;
        public String mContentUri;
        public long mMessageKey;
        public String mLocation;
        public String mEncoding;
        public String mContent; // Not currently used
        public int mFlags;
        public byte[] mContentBytes;
        public long mAccountKey;

        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_FILENAME_COLUMN = 1;
        public static final int CONTENT_MIME_TYPE_COLUMN = 2;
        public static final int CONTENT_SIZE_COLUMN = 3;
        public static final int CONTENT_CONTENT_ID_COLUMN = 4;
        public static final int CONTENT_CONTENT_URI_COLUMN = 5;
        public static final int CONTENT_MESSAGE_ID_COLUMN = 6;
        public static final int CONTENT_LOCATION_COLUMN = 7;
        public static final int CONTENT_ENCODING_COLUMN = 8;
        public static final int CONTENT_CONTENT_COLUMN = 9; // Not currently used
        public static final int CONTENT_FLAGS_COLUMN = 10;
        public static final int CONTENT_CONTENT_BYTES_COLUMN = 11;
        public static final int CONTENT_ACCOUNT_KEY_COLUMN = 12;
        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, AttachmentColumns.FILENAME, AttachmentColumns.MIME_TYPE,
            AttachmentColumns.SIZE, AttachmentColumns.CONTENT_ID, AttachmentColumns.CONTENT_URI,
            AttachmentColumns.MESSAGE_KEY, AttachmentColumns.LOCATION, AttachmentColumns.ENCODING,
            AttachmentColumns.CONTENT, AttachmentColumns.FLAGS, AttachmentColumns.CONTENT_BYTES,
            AttachmentColumns.ACCOUNT_KEY
        };

        // All attachments with an empty URI, regardless of mailbox
        public static final String EMPTY_URI_SELECTION =
            AttachmentColumns.CONTENT_URI + " isnull AND " + Attachment.FLAGS + "=0";
        // Attachments with an empty URI that are in an inbox
        public static final String EMPTY_URI_INBOX_SELECTION =
            EMPTY_URI_SELECTION + " AND " + AttachmentColumns.MESSAGE_KEY + " IN ("
            +     "SELECT " + MessageColumns.ID + " FROM " + Message.TABLE_NAME
            +     " WHERE " + Message.INBOX_SELECTION
            +     ")";

        // Bits used in mFlags
        // Instruct Rfc822Output to 1) not use Content-Disposition and 2) use multipart/alternative
        // with this attachment.  This is only valid if there is one and only one attachment and
        // that attachment has this flag set
        public static final int FLAG_ICS_ALTERNATIVE_PART = 1<<0;
        // Indicate that this attachment has been requested for downloading by the user; this is
        // the highest priority for attachment downloading
        public static final int FLAG_DOWNLOAD_USER_REQUEST = 1<<1;
        // Indicate that this attachment needs to be downloaded as part of an outgoing forwarded
        // message
        public static final int FLAG_DOWNLOAD_FORWARD = 1<<2;
        // Indicates that the attachment download failed in a non-recoverable manner
        public static final int FLAG_DOWNLOAD_FAILED = 1<<3;
        // Allow "room" for some additional download-related flags here
        // Indicates that the attachment will be smart-forwarded
        public static final int FLAG_SMART_FORWARD = 1<<8;
        /**
         * no public constructor since this is a utility class
         */
        public Attachment() {
            mBaseUri = CONTENT_URI;
        }

         /**
         * Restore an Attachment from the database, given its unique id
         * @param context
         * @param id
         * @return the instantiated Attachment
         */
        public static Attachment restoreAttachmentWithId (Context context, long id) {
            Uri u = ContentUris.withAppendedId(Attachment.CONTENT_URI, id);
            Cursor c = context.getContentResolver().query(u, Attachment.CONTENT_PROJECTION,
                    null, null, null);

            try {
                if (c.moveToFirst()) {
                    return getContent(c, Attachment.class);
                } else {
                    return null;
                }
            } finally {
                c.close();
            }
        }

        /**
         * Restore all the Attachments of a message given its messageId
         */
        public static Attachment[] restoreAttachmentsWithMessageId(Context context,
                long messageId) {
            Uri uri = ContentUris.withAppendedId(MESSAGE_ID_URI, messageId);
            Cursor c = context.getContentResolver().query(uri, CONTENT_PROJECTION,
                    null, null, null);
            try {
                int count = c.getCount();
                Attachment[] attachments = new Attachment[count];
                for (int i = 0; i < count; ++i) {
                    c.moveToNext();
                    Attachment attach = new Attachment();
                    attach.restore(c);
                    attachments[i] = attach;
                }
                return attachments;
            } finally {
                c.close();
            }
        }

        /**
         * Creates a unique file in the external store by appending a hyphen
         * and a number to the given filename.
         * @param filename
         * @return a new File object, or null if one could not be created
         */
        public static File createUniqueFile(String filename) {
            // TODO Handle internal storage, as required
            if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                File directory = Environment.getExternalStorageDirectory();
                File file = new File(directory, filename);
                if (!file.exists()) {
                    return file;
                }
                // Get the extension of the file, if any.
                int index = filename.lastIndexOf('.');
                String name = filename;
                String extension = "";
                if (index != -1) {
                    name = filename.substring(0, index);
                    extension = filename.substring(index);
                }
                for (int i = 2; i < Integer.MAX_VALUE; i++) {
                    file = new File(directory, name + '-' + i + extension);
                    if (!file.exists()) {
                        return file;
                    }
                }
                return null;
            }
            return null;
        }

        @Override
        public void restore(Cursor cursor) {
            mBaseUri = CONTENT_URI;
            mId = cursor.getLong(CONTENT_ID_COLUMN);
            mFileName= cursor.getString(CONTENT_FILENAME_COLUMN);
            mMimeType = cursor.getString(CONTENT_MIME_TYPE_COLUMN);
            mSize = cursor.getLong(CONTENT_SIZE_COLUMN);
            mContentId = cursor.getString(CONTENT_CONTENT_ID_COLUMN);
            mContentUri = cursor.getString(CONTENT_CONTENT_URI_COLUMN);
            mMessageKey = cursor.getLong(CONTENT_MESSAGE_ID_COLUMN);
            mLocation = cursor.getString(CONTENT_LOCATION_COLUMN);
            mEncoding = cursor.getString(CONTENT_ENCODING_COLUMN);
            mContent = cursor.getString(CONTENT_CONTENT_COLUMN);
            mFlags = cursor.getInt(CONTENT_FLAGS_COLUMN);
            mContentBytes = cursor.getBlob(CONTENT_CONTENT_BYTES_COLUMN);
            mAccountKey = cursor.getLong(CONTENT_ACCOUNT_KEY_COLUMN);
        }

        @Override
        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();
            values.put(AttachmentColumns.FILENAME, mFileName);
            values.put(AttachmentColumns.MIME_TYPE, mMimeType);
            values.put(AttachmentColumns.SIZE, mSize);
            values.put(AttachmentColumns.CONTENT_ID, mContentId);
            values.put(AttachmentColumns.CONTENT_URI, mContentUri);
            values.put(AttachmentColumns.MESSAGE_KEY, mMessageKey);
            values.put(AttachmentColumns.LOCATION, mLocation);
            values.put(AttachmentColumns.ENCODING, mEncoding);
            values.put(AttachmentColumns.CONTENT, mContent);
            values.put(AttachmentColumns.FLAGS, mFlags);
            values.put(AttachmentColumns.CONTENT_BYTES, mContentBytes);
            values.put(AttachmentColumns.ACCOUNT_KEY, mAccountKey);
            return values;
        }

        public int describeContents() {
             return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            // mBaseUri is not parceled
            dest.writeLong(mId);
            dest.writeString(mFileName);
            dest.writeString(mMimeType);
            dest.writeLong(mSize);
            dest.writeString(mContentId);
            dest.writeString(mContentUri);
            dest.writeLong(mMessageKey);
            dest.writeString(mLocation);
            dest.writeString(mEncoding);
            dest.writeString(mContent);
            dest.writeInt(mFlags);
            dest.writeLong(mAccountKey);
            if (mContentBytes == null) {
                dest.writeInt(-1);
            } else {
                dest.writeInt(mContentBytes.length);
                dest.writeByteArray(mContentBytes);
            }
        }

        public Attachment(Parcel in) {
            mBaseUri = EmailContent.Attachment.CONTENT_URI;
            mId = in.readLong();
            mFileName = in.readString();
            mMimeType = in.readString();
            mSize = in.readLong();
            mContentId = in.readString();
            mContentUri = in.readString();
            mMessageKey = in.readLong();
            mLocation = in.readString();
            mEncoding = in.readString();
            mContent = in.readString();
            mFlags = in.readInt();
            mAccountKey = in.readLong();
            final int contentBytesLen = in.readInt();
            if (contentBytesLen == -1) {
                mContentBytes = null;
            } else {
                mContentBytes = new byte[contentBytesLen];
                in.readByteArray(mContentBytes);
            }
         }

        public static final Parcelable.Creator<EmailContent.Attachment> CREATOR
        = new Parcelable.Creator<EmailContent.Attachment>() {
            public EmailContent.Attachment createFromParcel(Parcel in) {
                return new EmailContent.Attachment(in);
            }

            public EmailContent.Attachment[] newArray(int size) {
                return new EmailContent.Attachment[size];
            }
        };

        @Override
        public String toString() {
            return "[" + mFileName + ", " + mMimeType + ", " + mSize + ", " + mContentId + ", "
                    + mContentUri + ", " + mMessageKey + ", " + mLocation + ", " + mEncoding  + ", "
                    + mFlags + ", " + mContentBytes + ", " + mAccountKey + "]";
        }
    }

    public interface MailboxColumns {
        public static final String ID = "_id";
        // The display name of this mailbox [INDEX]
        static final String DISPLAY_NAME = "displayName";
        // The server's identifier for this mailbox
        public static final String SERVER_ID = "serverId";
        // The server's identifier for the parent of this mailbox (null = top-level)
        public static final String PARENT_SERVER_ID = "parentServerId";
        // A foreign key for the parent of this mailbox (-1 = top-level, 0=uninitialized)
        public static final String PARENT_KEY = "parentKey";
        // A foreign key to the Account that owns this mailbox
        public static final String ACCOUNT_KEY = "accountKey";
        // The type (role) of this mailbox
        public static final String TYPE = "type";
        // The hierarchy separator character
        public static final String DELIMITER = "delimiter";
        // Server-based sync key or validity marker (e.g. "SyncKey" for EAS, "uidvalidity" for IMAP)
        public static final String SYNC_KEY = "syncKey";
        // The sync lookback period for this mailbox (or null if using the account default)
        public static final String SYNC_LOOKBACK = "syncLookback";
        // The sync frequency for this mailbox (or null if using the account default)
        public static final String SYNC_INTERVAL = "syncInterval";
        // The time of last successful sync completion (millis)
        public static final String SYNC_TIME = "syncTime";
        // Cached unread count
        public static final String UNREAD_COUNT = "unreadCount";
        // Visibility of this folder in a list of folders [INDEX]
        public static final String FLAG_VISIBLE = "flagVisible";
        // Other states, as a bit field, e.g. CHILDREN_VISIBLE, HAS_CHILDREN
        public static final String FLAGS = "flags";
        // Backward compatible
        public static final String VISIBLE_LIMIT = "visibleLimit";
        // Sync status (can be used as desired by sync services)
        public static final String SYNC_STATUS = "syncStatus";
        // Number of messages in the mailbox.
        public static final String MESSAGE_COUNT = "messageCount";
    }

    public static final class Mailbox extends EmailContent implements SyncColumns, MailboxColumns {
        public static final String TABLE_NAME = "Mailbox";
        @SuppressWarnings("hiding")
        public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/mailbox");
        public static final Uri ADD_TO_FIELD_URI =
            Uri.parse(EmailContent.CONTENT_URI + "/mailboxIdAddToField");

        public String mDisplayName;
        public String mServerId;
        public String mParentServerId;
        public long mParentKey;
        public long mAccountKey;
        public int mType;
        public int mDelimiter;
        public String mSyncKey;
        public int mSyncLookback;
        public int mSyncInterval;
        public long mSyncTime;
        public boolean mFlagVisible = true;
        public int mFlags;
        public int mVisibleLimit;
        public String mSyncStatus;

        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_DISPLAY_NAME_COLUMN = 1;
        public static final int CONTENT_SERVER_ID_COLUMN = 2;
        public static final int CONTENT_PARENT_SERVER_ID_COLUMN = 3;
        public static final int CONTENT_ACCOUNT_KEY_COLUMN = 4;
        public static final int CONTENT_TYPE_COLUMN = 5;
        public static final int CONTENT_DELIMITER_COLUMN = 6;
        public static final int CONTENT_SYNC_KEY_COLUMN = 7;
        public static final int CONTENT_SYNC_LOOKBACK_COLUMN = 8;
        public static final int CONTENT_SYNC_INTERVAL_COLUMN = 9;
        public static final int CONTENT_SYNC_TIME_COLUMN = 10;
        public static final int CONTENT_FLAG_VISIBLE_COLUMN = 11;
        public static final int CONTENT_FLAGS_COLUMN = 12;
        public static final int CONTENT_VISIBLE_LIMIT_COLUMN = 13;
        public static final int CONTENT_SYNC_STATUS_COLUMN = 14;
        public static final int CONTENT_PARENT_KEY_COLUMN = 15;
        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, MailboxColumns.DISPLAY_NAME, MailboxColumns.SERVER_ID,
            MailboxColumns.PARENT_SERVER_ID, MailboxColumns.ACCOUNT_KEY, MailboxColumns.TYPE,
            MailboxColumns.DELIMITER, MailboxColumns.SYNC_KEY, MailboxColumns.SYNC_LOOKBACK,
            MailboxColumns.SYNC_INTERVAL, MailboxColumns.SYNC_TIME,
            MailboxColumns.FLAG_VISIBLE, MailboxColumns.FLAGS, MailboxColumns.VISIBLE_LIMIT,
            MailboxColumns.SYNC_STATUS, MailboxColumns.PARENT_KEY
        };

        private static final String ACCOUNT_AND_MAILBOX_TYPE_SELECTION =
                MailboxColumns.ACCOUNT_KEY + " =? AND " +
                MailboxColumns.TYPE + " =?";
        private static final String MAILBOX_TYPE_SELECTION =
                MailboxColumns.TYPE + " =?";
        private static final String[] MAILBOX_SUM_OF_UNREAD_COUNT_PROJECTION = new String [] {
                "sum(" + MailboxColumns.UNREAD_COUNT + ")"
                };
        private static final int UNREAD_COUNT_COUNT_COLUMN = 0;
        private static final String[] MAILBOX_SUM_OF_MESSAGE_COUNT_PROJECTION = new String [] {
                "sum(" + MailboxColumns.MESSAGE_COUNT + ")"
                };
        private static final int MESSAGE_COUNT_COUNT_COLUMN = 0;

        private static final String[] MAILBOX_TYPE_PROJECTION = new String [] {
                MailboxColumns.TYPE
                };
        private static final int MAILBOX_TYPE_TYPE_COLUMN = 0;

        private static final String[] MAILBOX_DISPLAY_NAME_PROJECTION = new String [] {
                MailboxColumns.DISPLAY_NAME
                };
        private static final int MAILBOX_DISPLAY_NAME_COLUMN = 0;

        public static final long NO_MAILBOX = -1;

        // Sentinel values for the mSyncInterval field of both Mailbox records
        public static final int CHECK_INTERVAL_NEVER = -1;
        public static final int CHECK_INTERVAL_PUSH = -2;
        // The following two sentinel values are used by EAS
        // Ping indicates that the EAS mailbox is synced based on a "ping" from the server
        public static final int CHECK_INTERVAL_PING = -3;
        // Push-Hold indicates an EAS push or ping Mailbox shouldn't sync just yet
        public static final int CHECK_INTERVAL_PUSH_HOLD = -4;

        // Sentinels for PARENT_KEY
        public static final long PARENT_KEY_NONE = -1L;
        public static final long PARENT_KEY_UNINITIALIZED = 0L;

        private static final String WHERE_TYPE_AND_ACCOUNT_KEY =
            MailboxColumns.TYPE + "=? and " + MailboxColumns.ACCOUNT_KEY + "=?";

        public static final String MOVE_TO_TARGET_MAILBOX_SELECTION =
            MailboxColumns.TYPE + " NOT IN (" + Mailbox.TYPE_DRAFTS + "," +
            Mailbox.TYPE_OUTBOX + "," + Mailbox.TYPE_SENT + "," + Mailbox.TYPE_TRASH + ")";
        public static final Integer[] INVALID_DROP_TARGETS = new Integer[] {Mailbox.TYPE_DRAFTS,
            Mailbox.TYPE_OUTBOX, Mailbox.TYPE_SENT};

        public static final String USER_VISIBLE_MAILBOX_SELECTION =
            MailboxColumns.TYPE + "<" + Mailbox.TYPE_NOT_EMAIL +
            " AND " + MailboxColumns.FLAG_VISIBLE + "=1";

        // Types of mailboxes.  The list is ordered to match a typical UI presentation, e.g.
        // placing the inbox at the top.
        // The "main" mailbox for the account, almost always referred to as "Inbox"
        // Arrays of "special_mailbox_display_names" and "special_mailbox_icons" are depends on
        // types Id of mailboxes.
        public static final int TYPE_INBOX = 0;
        // Types of mailboxes
        // Holds mail (generic)
        public static final int TYPE_MAIL = 1;
        // Parent-only mailbox; holds no mail
        public static final int TYPE_PARENT = 2;
        // Holds drafts
        public static final int TYPE_DRAFTS = 3;
        // The local outbox associated with the Account
        public static final int TYPE_OUTBOX = 4;
        // Holds sent mail
        public static final int TYPE_SENT = 5;
        // Holds deleted mail
        public static final int TYPE_TRASH = 6;
        // Holds junk mail
        public static final int TYPE_JUNK = 7;

        // Types after this are used for non-mail mailboxes (as in EAS)
        public static final int TYPE_NOT_EMAIL = 0x40;
        public static final int TYPE_CALENDAR = 0x41;
        public static final int TYPE_CONTACTS = 0x42;
        public static final int TYPE_TASKS = 0x43;
        public static final int TYPE_EAS_ACCOUNT_MAILBOX = 0x44;
        public static final int TYPE_UNKNOWN = 0x45;

        public static final int TYPE_NOT_SYNCABLE = 0x100;
        // A mailbox that holds Messages that are attachments
        public static final int TYPE_ATTACHMENT = 0x101;

        // Bit field flags; each is defined below
        // Warning: Do not read these flags until POP/IMAP/EAS all populate them
        // This mailbox has children in the mailbox hierarchy
        public static final int FLAG_HAS_CHILDREN = 1<<0;
        // This mailbox's children are visible in the UI
        public static final int FLAG_CHILDREN_VISIBLE = 1<<1;
        // This mailbox cannot receive "pushed" mail
        public static final int FLAG_CANT_PUSH = 1<<2;
        // This mailbox can hold emails (i.e. some parent mailboxes cannot themselves contain mail)
        public static final int FLAG_HOLDS_MAIL = 1<<3;
        // This mailbox is a valid target for moving messages within the account
        public static final int FLAG_ACCEPTS_MOVED_MAIL = 1<<4;
        // This mailbox is a valid target for appending messages
        public static final int FLAG_ACCEPTS_APPENDED_MAIL = 1<<5;

        // Magic mailbox ID's
        // NOTE:  This is a quick solution for merged mailboxes.  I would rather implement this
        // with a more generic way of packaging and sharing queries between activities
        public static final long QUERY_ALL_INBOXES = -2;
        public static final long QUERY_ALL_UNREAD = -3;
        public static final long QUERY_ALL_FAVORITES = -4;
        public static final long QUERY_ALL_DRAFTS = -5;
        public static final long QUERY_ALL_OUTBOX = -6;

        public Mailbox() {
            mBaseUri = CONTENT_URI;
        }

         /**
         * Restore a Mailbox from the database, given its unique id
         * @param context
         * @param id
         * @return the instantiated Mailbox
         */
        public static Mailbox restoreMailboxWithId(Context context, long id) {
            Uri u = ContentUris.withAppendedId(Mailbox.CONTENT_URI, id);
            Cursor c = context.getContentResolver().query(u, Mailbox.CONTENT_PROJECTION,
                    null, null, null);

            try {
                if (c.moveToFirst()) {
                    return getContent(c, Mailbox.class);
                } else {
                    return null;
                }
            } finally {
                c.close();
            }
        }

        @Override
        public void restore(Cursor cursor) {
            mBaseUri = CONTENT_URI;
            mId = cursor.getLong(CONTENT_ID_COLUMN);
            mDisplayName = cursor.getString(CONTENT_DISPLAY_NAME_COLUMN);
            mServerId = cursor.getString(CONTENT_SERVER_ID_COLUMN);
            mParentServerId = cursor.getString(CONTENT_PARENT_SERVER_ID_COLUMN);
            mParentKey = cursor.getLong(CONTENT_PARENT_KEY_COLUMN);
            mAccountKey = cursor.getLong(CONTENT_ACCOUNT_KEY_COLUMN);
            mType = cursor.getInt(CONTENT_TYPE_COLUMN);
            mDelimiter = cursor.getInt(CONTENT_DELIMITER_COLUMN);
            mSyncKey = cursor.getString(CONTENT_SYNC_KEY_COLUMN);
            mSyncLookback = cursor.getInt(CONTENT_SYNC_LOOKBACK_COLUMN);
            mSyncInterval = cursor.getInt(CONTENT_SYNC_INTERVAL_COLUMN);
            mSyncTime = cursor.getLong(CONTENT_SYNC_TIME_COLUMN);
            mFlagVisible = cursor.getInt(CONTENT_FLAG_VISIBLE_COLUMN) == 1;
            mFlags = cursor.getInt(CONTENT_FLAGS_COLUMN);
            mVisibleLimit = cursor.getInt(CONTENT_VISIBLE_LIMIT_COLUMN);
            mSyncStatus = cursor.getString(CONTENT_SYNC_STATUS_COLUMN);
        }

        @Override
        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();
            values.put(MailboxColumns.DISPLAY_NAME, mDisplayName);
            values.put(MailboxColumns.SERVER_ID, mServerId);
            values.put(MailboxColumns.PARENT_SERVER_ID, mParentServerId);
            values.put(MailboxColumns.PARENT_KEY, mParentKey);
            values.put(MailboxColumns.ACCOUNT_KEY, mAccountKey);
            values.put(MailboxColumns.TYPE, mType);
            values.put(MailboxColumns.DELIMITER, mDelimiter);
            values.put(MailboxColumns.SYNC_KEY, mSyncKey);
            values.put(MailboxColumns.SYNC_LOOKBACK, mSyncLookback);
            values.put(MailboxColumns.SYNC_INTERVAL, mSyncInterval);
            values.put(MailboxColumns.SYNC_TIME, mSyncTime);
            values.put(MailboxColumns.FLAG_VISIBLE, mFlagVisible);
            values.put(MailboxColumns.FLAGS, mFlags);
            values.put(MailboxColumns.VISIBLE_LIMIT, mVisibleLimit);
            values.put(MailboxColumns.SYNC_STATUS, mSyncStatus);
            return values;
        }

        /**
         * Convenience method to return the id of a given type of Mailbox for a given Account
         * @param context the caller's context, used to get a ContentResolver
         * @param accountId the id of the account to be queried
         * @param type the mailbox type, as defined above
         * @return the id of the mailbox, or -1 if not found
         */
        public static long findMailboxOfType(Context context, long accountId, int type) {
            String[] bindArguments = new String[] {Long.toString(type), Long.toString(accountId)};
            return Utility.getFirstRowLong(context, Mailbox.CONTENT_URI,
                    ID_PROJECTION, WHERE_TYPE_AND_ACCOUNT_KEY, bindArguments, null,
                    ID_PROJECTION_COLUMN, NO_MAILBOX);
        }

        /**
         * Convenience method that returns the mailbox found using the method above
         */
        public static Mailbox restoreMailboxOfType(Context context, long accountId, int type) {
            long mailboxId = findMailboxOfType(context, accountId, type);
            if (mailboxId != Mailbox.NO_MAILBOX) {
                return Mailbox.restoreMailboxWithId(context, mailboxId);
            }
            return null;
        }

        public static int getUnreadCountByAccountAndMailboxType(Context context, long accountId,
                int type) {
            return Utility.getFirstRowInt(context, Mailbox.CONTENT_URI,
                    MAILBOX_SUM_OF_UNREAD_COUNT_PROJECTION,
                    ACCOUNT_AND_MAILBOX_TYPE_SELECTION,
                    new String[] { String.valueOf(accountId), String.valueOf(type) },
                    null, UNREAD_COUNT_COUNT_COLUMN, 0);
        }

        public static int getUnreadCountByMailboxType(Context context, int type) {
            return Utility.getFirstRowInt(context, Mailbox.CONTENT_URI,
                    MAILBOX_SUM_OF_UNREAD_COUNT_PROJECTION,
                    MAILBOX_TYPE_SELECTION,
                    new String[] { String.valueOf(type) }, null, UNREAD_COUNT_COUNT_COLUMN, 0);
        }

        public static int getMessageCountByMailboxType(Context context, int type) {
            return Utility.getFirstRowInt(context, Mailbox.CONTENT_URI,
                    MAILBOX_SUM_OF_MESSAGE_COUNT_PROJECTION,
                    MAILBOX_TYPE_SELECTION,
                    new String[] { String.valueOf(type) }, null, MESSAGE_COUNT_COUNT_COLUMN, 0);
        }

        /**
         * Return the mailbox for a message with a given id
         * @param context the caller's context
         * @param messageId the id of the message
         * @return the mailbox, or null if the mailbox doesn't exist
         */
        public static Mailbox getMailboxForMessageId(Context context, long messageId) {
            long mailboxId = Message.getKeyColumnLong(context, messageId,
                    MessageColumns.MAILBOX_KEY);
            if (mailboxId != -1) {
                return Mailbox.restoreMailboxWithId(context, mailboxId);
            }
            return null;
        }

        /**
         * @return mailbox type, or -1 if mailbox not found.
         */
        public static int getMailboxType(Context context, long mailboxId) {
            Uri url = ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId);
            return Utility.getFirstRowInt(context, url, MAILBOX_TYPE_PROJECTION,
                    null, null, null, MAILBOX_TYPE_TYPE_COLUMN, -1);
        }

        /**
         * @return mailbox display name, or null if mailbox not found.
         */
        public static String getDisplayName(Context context, long mailboxId) {
            Uri url = ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId);
            return Utility.getFirstRowString(context, url, MAILBOX_DISPLAY_NAME_PROJECTION,
                    null, null, null, MAILBOX_DISPLAY_NAME_COLUMN);
        }

        /**
         * @param mailboxId ID of a mailbox.  This method accepts magic mailbox IDs, such as
         * {@link #QUERY_ALL_INBOXES}. (They're all non-refreshable.)
         * @return true if a mailbox is refreshable.
         */
        public static boolean isRefreshable(Context context, long mailboxId) {
            if (mailboxId < 0) {
                return false; // magic mailboxes
            }
            switch (getMailboxType(context, mailboxId)) {
                case -1: // not found
                case TYPE_DRAFTS:
                case TYPE_OUTBOX:
                    return false;
            }
            return true;
        }

        /**
         * @param mailboxId ID of a mailbox.  This method DOES NOT accept magic mailbox IDs, such as
         * {@link #QUERY_ALL_INBOXES} (because only the actual mailbox ID matters here. e.g.
         * {@link #QUERY_ALL_FAVORITES} can contain ANY kind of messages), so don't pass a negative
         * value.
         * @return true if messages in a mailbox can be moved to another mailbox.
         * This method only checks the mailbox information. It doesn't check its account/protocol,
         * so it may return true even for POP3 mailbox.
         */
        public static boolean canMoveFrom(Context context, long mailboxId) {
            if (mailboxId < 0) {
                throw new InvalidParameterException();
            }
            Uri url = ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId);
            int type = Utility.getFirstRowInt(context, url, MAILBOX_TYPE_PROJECTION,
                    null, null, null, MAILBOX_TYPE_TYPE_COLUMN);
            switch (type) {
                case TYPE_INBOX:
                case TYPE_MAIL:
                case TYPE_TRASH:
                case TYPE_JUNK:
                    return true;
            }
            return false; // TYPE_DRAFTS, TYPE_OUTBOX, TYPE_SENT, etc
        }

        /**
         * @return true if messages in a mailbox of a type can be replied/forwarded.
         */
        public static boolean isMailboxTypeReplyAndForwardable(int type) {
            return (type != TYPE_TRASH) && (type != TYPE_DRAFTS);
        }
    }

    public interface HostAuthColumns {
        public static final String ID = "_id";
        // The protocol (e.g. "imap", "pop3", "eas", "smtp"
        static final String PROTOCOL = "protocol";
        // The host address
        static final String ADDRESS = "address";
        // The port to use for the connection
        static final String PORT = "port";
        // General purpose flags
        static final String FLAGS = "flags";
        // The login (user name)
        static final String LOGIN = "login";
        // Password
        static final String PASSWORD = "password";
        // A domain or path, if required (used in IMAP and EAS)
        static final String DOMAIN = "domain";
        // DEPRECATED - Will not be set or stored
        static final String ACCOUNT_KEY = "accountKey";
    }

    public static final class HostAuth extends EmailContent implements HostAuthColumns, Parcelable {
        public static final String TABLE_NAME = "HostAuth";
        @SuppressWarnings("hiding")
        public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/hostauth");

        public static final int PORT_UNKNOWN = -1;

        public static final int FLAG_NONE         = 0x00;    // No flags
        public static final int FLAG_SSL          = 0x01;    // Use SSL
        public static final int FLAG_TLS          = 0x02;    // Use TLS
        public static final int FLAG_AUTHENTICATE = 0x04;    // Use name/password for authentication
        public static final int FLAG_TRUST_ALL    = 0x08;    // Trust all certificates
        // Mask of settings directly configurable by the user
        public static final int USER_CONFIG_MASK  = 0x0b;

        public String mProtocol;
        public String mAddress;
        public int mPort;
        public int mFlags;
        public String mLogin;
        public String mPassword;
        public String mDomain;

        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_PROTOCOL_COLUMN = 1;
        public static final int CONTENT_ADDRESS_COLUMN = 2;
        public static final int CONTENT_PORT_COLUMN = 3;
        public static final int CONTENT_FLAGS_COLUMN = 4;
        public static final int CONTENT_LOGIN_COLUMN = 5;
        public static final int CONTENT_PASSWORD_COLUMN = 6;
        public static final int CONTENT_DOMAIN_COLUMN = 7;

        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, HostAuthColumns.PROTOCOL, HostAuthColumns.ADDRESS, HostAuthColumns.PORT,
            HostAuthColumns.FLAGS, HostAuthColumns.LOGIN,
            HostAuthColumns.PASSWORD, HostAuthColumns.DOMAIN
        };

        /**
         * no public constructor since this is a utility class
         */
        public HostAuth() {
            mBaseUri = CONTENT_URI;

            // other defaults policy)
            mPort = PORT_UNKNOWN;
        }

         /**
         * Restore a HostAuth from the database, given its unique id
         * @param context
         * @param id
         * @return the instantiated HostAuth
         */
        public static HostAuth restoreHostAuthWithId(Context context, long id) {
            Uri u = ContentUris.withAppendedId(EmailContent.HostAuth.CONTENT_URI, id);
            Cursor c = context.getContentResolver().query(u, HostAuth.CONTENT_PROJECTION,
                    null, null, null);

            try {
                if (c.moveToFirst()) {
                    return getContent(c, HostAuth.class);
                } else {
                    return null;
                }
            } finally {
                c.close();
            }
        }


        /**
         * Returns the scheme for the specified flags.
         */
        public static String getSchemeString(String protocol, int flags) {
            String security = "";
            switch (flags & USER_CONFIG_MASK) {
                case FLAG_SSL:
                    security = "+ssl+";
                    break;
                case FLAG_SSL | FLAG_TRUST_ALL:
                    security = "+ssl+trustallcerts";
                    break;
                case FLAG_TLS:
                    security = "+tls+";
                    break;
                case FLAG_TLS | FLAG_TRUST_ALL:
                    security = "+tls+trustallcerts";
                    break;
            }
            return protocol + security;
        }

        /**
         * Returns the flags for the specified scheme.
         */
        public static int getSchemeFlags(String scheme) {
            String[] schemeParts = scheme.split("\\+");
            int flags = HostAuth.FLAG_NONE;
            if (schemeParts.length >= 2) {
                String part1 = schemeParts[1];
                if ("ssl".equals(part1)) {
                    flags |= HostAuth.FLAG_SSL;
                } else if ("tls".equals(part1)) {
                    flags |= HostAuth.FLAG_TLS;
                }
                if (schemeParts.length >= 3) {
                    String part2 = schemeParts[2];
                    if ("trustallcerts".equals(part2)) {
                        flags |= HostAuth.FLAG_TRUST_ALL;
                    }
                }
            }
            return flags;
        }

        @Override
        public void restore(Cursor cursor) {
            mBaseUri = CONTENT_URI;
            mId = cursor.getLong(CONTENT_ID_COLUMN);
            mProtocol = cursor.getString(CONTENT_PROTOCOL_COLUMN);
            mAddress = cursor.getString(CONTENT_ADDRESS_COLUMN);
            mPort = cursor.getInt(CONTENT_PORT_COLUMN);
            mFlags = cursor.getInt(CONTENT_FLAGS_COLUMN);
            mLogin = cursor.getString(CONTENT_LOGIN_COLUMN);
            mPassword = cursor.getString(CONTENT_PASSWORD_COLUMN);
            mDomain = cursor.getString(CONTENT_DOMAIN_COLUMN);
        }

        @Override
        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();
            values.put(HostAuthColumns.PROTOCOL, mProtocol);
            values.put(HostAuthColumns.ADDRESS, mAddress);
            values.put(HostAuthColumns.PORT, mPort);
            values.put(HostAuthColumns.FLAGS, mFlags);
            values.put(HostAuthColumns.LOGIN, mLogin);
            values.put(HostAuthColumns.PASSWORD, mPassword);
            values.put(HostAuthColumns.DOMAIN, mDomain);
            values.put(HostAuthColumns.ACCOUNT_KEY, 0); // Need something to satisfy the DB
            return values;
        }

        /**
         * For compatibility while converting to provider model, generate a "store URI"
         * TODO cache this so we don't rebuild every time
         *
         * @return a string in the form of a Uri, as used by the other parts of the email app
         */
        public String getStoreUri() {
            String userInfo = null;
            if ((mFlags & FLAG_AUTHENTICATE) != 0) {
                String trimUser = (mLogin != null) ? mLogin.trim() : "";
                String password = (mPassword != null) ? mPassword : "";
                userInfo = trimUser + ":" + password;
            }
            String scheme = getSchemeString(mProtocol, mFlags);
            String address = (mAddress != null) ? mAddress.trim() : null;
            String path = (mDomain != null) ? "/" + mDomain : null;

            URI uri;
            try {
                uri = new URI(
                        scheme,
                        userInfo,
                        address,
                        mPort,
                        path,
                        null,
                        null);
                return uri.toString();
            } catch (URISyntaxException e) {
                return null;
            }
        }

        /**
         * Sets the user name and password from URI user info string
         */
        public void setLogin(String userInfo) {
            String userName = null;
            String userPassword = null;
            if (!TextUtils.isEmpty(userInfo)) {
                String[] userInfoParts = userInfo.split(":", 2);
                userName = userInfoParts[0];
                if (userInfoParts.length > 1) {
                    userPassword = userInfoParts[1];
                }
            }
            setLogin(userName, userPassword);
        }

        /**
         * Sets the user name and password
         */
        public void setLogin(String userName, String userPassword) {
            mLogin = userName;
            mPassword = userPassword;

            if (mLogin == null) {
                mFlags &= ~FLAG_AUTHENTICATE;
            } else {
                mFlags |= FLAG_AUTHENTICATE;
            }
        }

        /**
         * Sets the connection values of the auth structure per the given scheme, host and port.
         */
        public void setConnection(String scheme, String host, int port) {
            String[] schemeParts = scheme.split("\\+");
            String protocol = schemeParts[0];
            int flags = getSchemeFlags(scheme);

            setConnection(protocol, host, port, flags);
        }

        public void setConnection(String protocol, String address, int port, int flags) {
            // Set protocol, security, and additional flags based on uri scheme
            mProtocol = protocol;

            mFlags &= ~(FLAG_SSL | FLAG_TLS | FLAG_TRUST_ALL);
            mFlags |= (flags & USER_CONFIG_MASK);

            mAddress = address;
            mPort = port;
            if (mPort == PORT_UNKNOWN) {
                boolean useSSL = ((mFlags & FLAG_SSL) != 0);
                // infer port# from protocol + security
                // SSL implies a different port - TLS runs in the "regular" port
                // NOTE: Although the port should be setup in the various setup screens, this
                // block cannot easily be moved because we get process URIs from other sources
                // (e.g. for tests, provider templates and account restore) that may or may not
                // have a port specified.
                if ("pop3".equals(mProtocol)) {
                    mPort = useSSL ? 995 : 110;
                } else if ("imap".equals(mProtocol)) {
                    mPort = useSSL ? 993 : 143;
                } else if ("eas".equals(mProtocol)) {
                    mPort = useSSL ? 443 : 80;
                }  else if ("smtp".equals(mProtocol)) {
                    mPort = useSSL ? 465 : 587;
                }
            }
        }

        /**
         * Supports Parcelable
         */
        public int describeContents() {
            return 0;
        }

        /**
         * Supports Parcelable
         */
        public static final Parcelable.Creator<EmailContent.HostAuth> CREATOR
                = new Parcelable.Creator<EmailContent.HostAuth>() {
            public EmailContent.HostAuth createFromParcel(Parcel in) {
                return new EmailContent.HostAuth(in);
            }

            public EmailContent.HostAuth[] newArray(int size) {
                return new EmailContent.HostAuth[size];
            }
        };

        /**
         * Supports Parcelable
         */
        public void writeToParcel(Parcel dest, int flags) {
            // mBaseUri is not parceled
            dest.writeLong(mId);
            dest.writeString(mProtocol);
            dest.writeString(mAddress);
            dest.writeInt(mPort);
            dest.writeInt(mFlags);
            dest.writeString(mLogin);
            dest.writeString(mPassword);
            dest.writeString(mDomain);
        }

        /**
         * Supports Parcelable
         */
        public HostAuth(Parcel in) {
            mBaseUri = CONTENT_URI;
            mId = in.readLong();
            mProtocol = in.readString();
            mAddress = in.readString();
            mPort = in.readInt();
            mFlags = in.readInt();
            mLogin = in.readString();
            mPassword = in.readString();
            mDomain = in.readString();
        }

        /**
         * For debugger support only - DO NOT use for code.
         */
        @Override
        public String toString() {
            return getStoreUri();
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof HostAuth)) {
                return false;
            }
            HostAuth that = (HostAuth)o;
            return mPort == that.mPort
                    && mFlags == that.mFlags
                    && Utility.areStringsEqual(mProtocol, that.mProtocol)
                    && Utility.areStringsEqual(mAddress, that.mAddress)
                    && Utility.areStringsEqual(mLogin, that.mLogin)
                    && Utility.areStringsEqual(mPassword, that.mPassword)
                    && Utility.areStringsEqual(mDomain, that.mDomain);
        }
    }
}
