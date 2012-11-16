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

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import com.android.emailcommon.utility.TextUtilities;
import com.android.emailcommon.utility.Utility;
import com.android.mail.providers.UIProvider;
import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.util.ArrayList;


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

    public static final Uri MAILBOX_NOTIFICATION_URI =
            Uri.parse("content://" + EmailContent.AUTHORITY + "/mailboxNotification");
    public static final String[] NOTIFICATION_PROJECTION =
        new String[] {MailboxColumns.ID, MailboxColumns.UNREAD_COUNT, MailboxColumns.MESSAGE_COUNT};
    public static final int NOTIFICATION_MAILBOX_ID_COLUMN = 0;
    public static final int NOTIFICATION_MAILBOX_UNREAD_COUNT_COLUMN = 1;
    public static final int NOTIFICATION_MAILBOX_MESSAGE_COUNT_COLUMN = 2;

    public static final Uri MAILBOX_MOST_RECENT_MESSAGE_URI =
            Uri.parse("content://" + EmailContent.AUTHORITY + "/mailboxMostRecentMessage");

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
    public static final String SET_COLUMN_NAME = "set";

    public static final int SYNC_STATUS_NONE = UIProvider.SyncStatus.NO_SYNC;
    public static final int SYNC_STATUS_USER = UIProvider.SyncStatus.USER_REFRESH;
    public static final int SYNC_STATUS_BACKGROUND = UIProvider.SyncStatus.BACKGROUND_SYNC;

    public static final int LAST_SYNC_RESULT_SUCCESS = UIProvider.LastSyncResult.SUCCESS;
    public static final int LAST_SYNC_RESULT_AUTH_ERROR = UIProvider.LastSyncResult.AUTH_ERROR;
    public static final int LAST_SYNC_RESULT_SECURITY_ERROR =
            UIProvider.LastSyncResult.SECURITY_ERROR;
    public static final int LAST_SYNC_RESULT_CONNECTION_ERROR =
            UIProvider.LastSyncResult.CONNECTION_ERROR;
    public static final int LAST_SYNC_RESULT_INTERNAL_ERROR =
            UIProvider.LastSyncResult.INTERNAL_ERROR;

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


    /**
     * Restore a subclass of EmailContent from the database
     * @param context the caller's context
     * @param klass the class to restore
     * @param contentUri the content uri of the EmailContent subclass
     * @param contentProjection the content projection for the EmailContent subclass
     * @param id the unique id of the object
     * @return the instantiated object
     */
    public static <T extends EmailContent> T restoreContentWithId(Context context,
            Class<T> klass, Uri contentUri, String[] contentProjection, long id) {
        long token = Binder.clearCallingIdentity();
        Uri u = ContentUris.withAppendedId(contentUri, id);
        Cursor c = context.getContentResolver().query(u, contentProjection, null, null, null);
        if (c == null) throw new ProviderUnavailableException();
        try {
            if (c.moveToFirst()) {
                return getContent(c, klass);
            } else {
                return null;
            }
        } finally {
            c.close();
            Binder.restoreCallingIdentity(token);
        }
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
    protected EmailContent() {
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
        // The start of quoted text within our text content
        public static final String QUOTED_TEXT_START_POS = "quotedTextStartPos";
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
        public static final int CONTENT_QUOTED_TEXT_START_POS_COLUMN = 8;

        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, BodyColumns.MESSAGE_KEY, BodyColumns.HTML_CONTENT, BodyColumns.TEXT_CONTENT,
            BodyColumns.HTML_REPLY, BodyColumns.TEXT_REPLY, BodyColumns.SOURCE_MESSAGE_KEY,
            BodyColumns.INTRO_TEXT, BodyColumns.QUOTED_TEXT_START_POS
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
        public int mQuotedTextStartPos;

        /**
         * Points to the ID of the message being replied to or forwarded. Will always be set,
         * even if {@link #mHtmlReply} and {@link #mTextReply} are null (indicating the user doesn't
         * want to include quoted text.
         */
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

        /**
         * Given a cursor, restore a Body from it
         * @param cursor a cursor which must NOT be null
         * @return the Body as restored from the cursor
         */
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
            if (c == null) throw new ProviderUnavailableException();
            return restoreBodyWithCursor(c);
        }

        public static Body restoreBodyWithMessageId(Context context, long messageId) {
            Cursor c = context.getContentResolver().query(Body.CONTENT_URI,
                    Body.CONTENT_PROJECTION, Body.MESSAGE_KEY + "=?",
                    new String[] {Long.toString(messageId)}, null);
            if (c == null) throw new ProviderUnavailableException();
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

        @VisibleForTesting
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
            if (c == null) throw new ProviderUnavailableException();
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
            mQuotedTextStartPos = cursor.getInt(CONTENT_QUOTED_TEXT_START_POS_COLUMN);
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
        // A column that can be used by sync adapters to store search-related information about
        // a retrieved message (the messageKey for search results will be a TYPE_SEARCH mailbox
        // and the sync adapter might, for example, need more information about the original source
        // of the message)
        public static final String PROTOCOL_SEARCH_INFO = "protocolSearchInfo";
        // Simple thread topic
        public static final String THREAD_TOPIC = "threadTopic";
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
        public static final int CONTENT_PROTOCOL_SEARCH_INFO_COLUMN = 22;
        public static final int CONTENT_THREAD_TOPIC_COLUMN = 23;

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
            MessageColumns.SNIPPET, MessageColumns.PROTOCOL_SEARCH_INFO,
            MessageColumns.THREAD_TOPIC
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

        /**
         * Selection for messages that are loaded
         *
         * POP messages at the initial stage have very little information. (Server UID only)
         * Use this to make sure they're not visible on any UI.
         * This means unread counts on the mailbox list can be different from the
         * number of messages in the message list, but it should be transient...
         */
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
        public static final String ALL_INBOX_SELECTION =
            MessageColumns.MAILBOX_KEY + " IN ("
            +     "SELECT " + MailboxColumns.ID + " FROM " + Mailbox.TABLE_NAME
            +     " WHERE " + MailboxColumns.TYPE + " = " + Mailbox.TYPE_INBOX
            +     ")"
            + " AND " + FLAG_LOADED_SELECTION;

        /** Selection to retrieve all messages in "drafts" for any account */
        public static final String ALL_DRAFT_SELECTION =
            MessageColumns.MAILBOX_KEY + " IN ("
            +     "SELECT " + MailboxColumns.ID + " FROM " + Mailbox.TABLE_NAME
            +     " WHERE " + MailboxColumns.TYPE + " = " + Mailbox.TYPE_DRAFTS
            +     ")"
            + " AND " + FLAG_LOADED_SELECTION;

        /** Selection to retrieve all messages in "outbox" for any account */
        public static final String ALL_OUTBOX_SELECTION =
            MessageColumns.MAILBOX_KEY + " IN ("
            +     "SELECT " + MailboxColumns.ID + " FROM " + Mailbox.TABLE_NAME
            +     " WHERE " + MailboxColumns.TYPE + " = " + Mailbox.TYPE_OUTBOX
            +     ")"; // NOTE No flag_loaded test for outboxes.

        /** Selection to retrieve unread messages in "inbox" for any account */
        public static final String ALL_UNREAD_SELECTION =
            MessageColumns.FLAG_READ + "=0 AND " + ALL_INBOX_SELECTION;

        /** Selection to retrieve unread messages in "inbox" for one account */
        public static final String PER_ACCOUNT_UNREAD_SELECTION =
            ACCOUNT_KEY_SELECTION + " AND " + ALL_UNREAD_SELECTION;

        /** Selection to retrieve all messages in "inbox" for one account */
        public static final String PER_ACCOUNT_INBOX_SELECTION =
            ACCOUNT_KEY_SELECTION + " AND " + ALL_INBOX_SELECTION;

        public static final String PER_ACCOUNT_FAVORITE_SELECTION =
            ACCOUNT_KEY_SELECTION + " AND " + ALL_FAVORITE_SELECTION;

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

        public String mProtocolSearchInfo;

        public String mThreadTopic;

        /**
         * Base64-encoded representation of the byte array provided by servers for identifying
         * messages belonging to the same conversation thread. Currently unsupported and not
         * persisted in the database.
         */
        public String mServerConversationId;

        // The following transient members may be used while building and manipulating messages,
        // but they are NOT persisted directly by EmailProvider. See Body for related fields.
        transient public String mText;
        transient public String mHtml;
        transient public String mTextReply;
        transient public String mHtmlReply;
        transient public long mSourceKey;
        transient public ArrayList<Attachment> mAttachments = null;
        transient public String mIntroText;
        transient public int mQuotedTextStartPos;


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
        /** If set, the outgoing message should *not* include the quoted original message. */
        public static final int FLAG_NOT_INCLUDE_QUOTED_TEXT = 1 << 17;
        public static final int FLAG_REPLIED_TO = 1 << 18;
        public static final int FLAG_FORWARDED = 1 << 19;

        // Outgoing, original message
        public static final int FLAG_TYPE_ORIGINAL = 1 << 20;
        // Outgoing, reply all message; note, FLAG_TYPE_REPLY should also be set for backward
        // compatibility
        public static final int FLAG_TYPE_REPLY_ALL = 1 << 21;

        /** a pseudo ID for "no message". */
        public static final long NO_MESSAGE = -1L;

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

            values.put(MessageColumns.PROTOCOL_SEARCH_INFO, mProtocolSearchInfo);

            values.put(MessageColumns.THREAD_TOPIC, mThreadTopic);
            return values;
        }

        public static Message restoreMessageWithId(Context context, long id) {
            return EmailContent.restoreContentWithId(context, Message.class,
                    Message.CONTENT_URI, Message.CONTENT_PROJECTION, id);
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
            mProtocolSearchInfo = cursor.getString(CONTENT_PROTOCOL_SEARCH_INFO_COLUMN);
            mThreadTopic = cursor.getString(CONTENT_THREAD_TOPIC_COLUMN);
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

        /**
         * Save or update a message
         * @param ops an array of CPOs that we'll add to
         */
        public void addSaveOps(ArrayList<ContentProviderOperation> ops) {
            boolean isNew = !isSaved();
            ContentProviderOperation.Builder b;
            // First, save/update the message
            if (isNew) {
                b = ContentProviderOperation.newInsert(mBaseUri);
            } else {
                b = ContentProviderOperation.newUpdate(mBaseUri)
                        .withSelection(Message.RECORD_ID + "=?", new String[] {Long.toString(mId)});
            }
            // Generate the snippet here, before we create the CPO for Message
            if (mText != null) {
                mSnippet = TextUtilities.makeSnippetFromPlainText(mText);
            } else if (mHtml != null) {
                mSnippet = TextUtilities.makeSnippetFromHtmlText(mHtml);
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
            if (mQuotedTextStartPos != 0) {
                cv.put(Body.QUOTED_TEXT_START_POS, mQuotedTextStartPos);
            }
            b = ContentProviderOperation.newInsert(Body.CONTENT_URI);
            // Put our message id in the Body
            if (!isNew) {
                cv.put(Body.MESSAGE_KEY, mId);
            }
            b.withValues(cv);
            // We'll need this if we're new
            int messageBackValue = ops.size() - 1;
            // If we're new, create a back value entry
            if (isNew) {
                ContentValues backValues = new ContentValues();
                backValues.put(Body.MESSAGE_KEY, messageBackValue);
                b.withValueBackReferences(backValues);
            }
            // And add the Body operation
            ops.add(b.build());

            // Create the attaachments, if any
            if (mAttachments != null) {
                for (Attachment att: mAttachments) {
                    if (!isNew) {
                        att.mMessageKey = mId;
                    }
                    b = ContentProviderOperation.newInsert(Attachment.CONTENT_URI)
                            .withValues(att.toContentValues());
                    if (isNew) {
                        b.withValueBackReference(Attachment.MESSAGE_KEY, messageBackValue);
                    }
                    ops.add(b.build());
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
            return count(context, Message.CONTENT_URI, PER_ACCOUNT_FAVORITE_SELECTION,
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
         * Returns the where clause for a message list selection.
         *
         * Accesses the detabase to determine the mailbox type.  DO NOT CALL FROM UI THREAD.
         */
        public static String buildMessageListSelection(
                Context context, long accountId, long mailboxId) {

            if (mailboxId == Mailbox.QUERY_ALL_INBOXES) {
                return Message.ALL_INBOX_SELECTION;
            }
            if (mailboxId == Mailbox.QUERY_ALL_DRAFTS) {
                return Message.ALL_DRAFT_SELECTION;
            }
            if (mailboxId == Mailbox.QUERY_ALL_OUTBOX) {
                return Message.ALL_OUTBOX_SELECTION;
            }
            if (mailboxId == Mailbox.QUERY_ALL_UNREAD) {
                return Message.ALL_UNREAD_SELECTION;
            }
            // TODO: we only support per-account starred mailbox right now, but presumably, we
            // can surface the same thing for unread.
            if (mailboxId == Mailbox.QUERY_ALL_FAVORITES) {
                if (accountId == Account.ACCOUNT_ID_COMBINED_VIEW) {
                    return Message.ALL_FAVORITE_SELECTION;
                }

                final StringBuilder selection = new StringBuilder();
                selection.append(MessageColumns.ACCOUNT_KEY).append('=').append(accountId)
                        .append(" AND ")
                        .append(Message.ALL_FAVORITE_SELECTION);
                return selection.toString();
            }

            // Now it's a regular mailbox.
            final StringBuilder selection = new StringBuilder();

            selection.append(MessageColumns.MAILBOX_KEY).append('=').append(mailboxId);

            if (Mailbox.getMailboxType(context, mailboxId) != Mailbox.TYPE_OUTBOX) {
                selection.append(" AND ").append(Message.FLAG_LOADED_SELECTION);
            }
            return selection.toString();
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
        // The UIProvider state of the attachment
        public static final String UI_STATE = "uiState";
        // The UIProvider destination of the attachment
        public static final String UI_DESTINATION = "uiDestination";
        // The UIProvider downloaded size of the attachment
        public static final String UI_DOWNLOADED_SIZE = "uiDownloadedSize";
    }

    public static final class Attachment extends EmailContent
            implements AttachmentColumns, Parcelable {
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
        public int mUiState;
        public int mUiDestination;
        public int mUiDownloadedSize;

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
        public static final int CONTENT_UI_STATE_COLUMN = 13;
        public static final int CONTENT_UI_DESTINATION_COLUMN = 14;
        public static final int CONTENT_UI_DOWNLOADED_SIZE_COLUMN = 15;
        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, AttachmentColumns.FILENAME, AttachmentColumns.MIME_TYPE,
            AttachmentColumns.SIZE, AttachmentColumns.CONTENT_ID, AttachmentColumns.CONTENT_URI,
            AttachmentColumns.MESSAGE_KEY, AttachmentColumns.LOCATION, AttachmentColumns.ENCODING,
            AttachmentColumns.CONTENT, AttachmentColumns.FLAGS, AttachmentColumns.CONTENT_BYTES,
            AttachmentColumns.ACCOUNT_KEY, AttachmentColumns.UI_STATE,
            AttachmentColumns.UI_DESTINATION, AttachmentColumns.UI_DOWNLOADED_SIZE
        };

        // All attachments with an empty URI, regardless of mailbox
        public static final String PRECACHE_SELECTION =
            AttachmentColumns.CONTENT_URI + " isnull AND " + Attachment.FLAGS + "=0";
        // Attachments with an empty URI that are in an inbox
        public static final String PRECACHE_INBOX_SELECTION =
            PRECACHE_SELECTION + " AND " + AttachmentColumns.MESSAGE_KEY + " IN ("
            +     "SELECT " + MessageColumns.ID + " FROM " + Message.TABLE_NAME
            +     " WHERE " + Message.ALL_INBOX_SELECTION
            +     ")";

        // Bits used in mFlags
        // WARNING: AttachmentDownloadService relies on the fact that ALL of the flags below
        // disqualify attachments for precaching.  If you add a flag that does NOT disqualify an
        // attachment for precaching, you MUST change the PRECACHE_SELECTION definition above

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
        // Indicates that the attachment cannot be forwarded due to a policy restriction
        public static final int FLAG_POLICY_DISALLOWS_DOWNLOAD = 1<<9;
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
        public static Attachment restoreAttachmentWithId(Context context, long id) {
            return EmailContent.restoreContentWithId(context, Attachment.class,
                    Attachment.CONTENT_URI, Attachment.CONTENT_PROJECTION, id);
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
            mUiState = cursor.getInt(CONTENT_UI_STATE_COLUMN);
            mUiDestination = cursor.getInt(CONTENT_UI_DESTINATION_COLUMN);
            mUiDownloadedSize = cursor.getInt(CONTENT_UI_DOWNLOADED_SIZE_COLUMN);
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
            values.put(AttachmentColumns.UI_STATE, mUiState);
            values.put(AttachmentColumns.UI_DESTINATION, mUiDestination);
            values.put(AttachmentColumns.UI_DOWNLOADED_SIZE, mUiDownloadedSize);
            return values;
        }

        @Override
        public int describeContents() {
             return 0;
        }

        @Override
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
            dest.writeInt(mUiState);
            dest.writeInt(mUiDestination);
            dest.writeInt(mUiDownloadedSize);
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
            mUiState = in.readInt();
            mUiDestination = in.readInt();
            mUiDownloadedSize = in.readInt();
         }

        public static final Parcelable.Creator<EmailContent.Attachment> CREATOR
                = new Parcelable.Creator<EmailContent.Attachment>() {
            @Override
            public EmailContent.Attachment createFromParcel(Parcel in) {
                return new EmailContent.Attachment(in);
            }

            @Override
            public EmailContent.Attachment[] newArray(int size) {
                return new EmailContent.Attachment[size];
            }
        };

        @Override
        public String toString() {
            return "[" + mFileName + ", " + mMimeType + ", " + mSize + ", " + mContentId + ", "
                    + mContentUri + ", " + mMessageKey + ", " + mLocation + ", " + mEncoding  + ", "
                    + mFlags + ", " + mContentBytes + ", " + mAccountKey +  "," + mUiState + ","
                    + mUiDestination + "," + mUiDownloadedSize + "]";
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
        // Legacy flags defining security (provisioning) requirements of this account; this
        // information is now found in the Policy table; POLICY_KEY (below) is the foreign key
        @Deprecated
        public static final String SECURITY_FLAGS = "securityFlags";
        // Server-based sync key for the security policies currently enforced
        public static final String SECURITY_SYNC_KEY = "securitySyncKey";
        // Signature to use with this account
        public static final String SIGNATURE = "signature";
        // A foreign key into the Policy table
        public static final String POLICY_KEY = "policyKey";
        // For compatibility w/ Email1
        public static final String NOTIFIED_MESSAGE_ID = "notifiedMessageId";
        // For compatibility w/ Email1
        public static final String NOTIFIED_MESSAGE_COUNT = "notifiedMessageCount";
    }

    public interface QuickResponseColumns {
        // The QuickResponse text
        static final String TEXT = "quickResponse";
        // A foreign key into the Account table owning the QuickResponse
        static final String ACCOUNT_KEY = "accountKey";
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
        // The last time a message in this mailbox has been read (in millis)
        public static final String LAST_TOUCHED_TIME = "lastTouchedTime";
        // The UIProvider sync status
        public static final String UI_SYNC_STATUS = "uiSyncStatus";
        // The UIProvider last sync result
        public static final String UI_LAST_SYNC_RESULT = "uiLastSyncResult";
        // The UIProvider sync status
        public static final String LAST_NOTIFIED_MESSAGE_KEY = "lastNotifiedMessageKey";
        // The UIProvider last sync result
        public static final String LAST_NOTIFIED_MESSAGE_COUNT = "lastNotifiedMessageCount";
        // The total number of messages in the remote mailbox
        public static final String TOTAL_COUNT = "totalCount";
        // For compatibility with Email1
        public static final String LAST_SEEN_MESSAGE_KEY = "lastSeenMessageKey";
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
        // An alias to a local client certificate for SSL
        static final String CLIENT_CERT_ALIAS = "certAlias";
        // DEPRECATED - Will not be set or stored
        static final String ACCOUNT_KEY = "accountKey";
    }

    public interface PolicyColumns {
        public static final String ID = "_id";
        public static final String PASSWORD_MODE = "passwordMode";
        public static final String PASSWORD_MIN_LENGTH = "passwordMinLength";
        public static final String PASSWORD_EXPIRATION_DAYS = "passwordExpirationDays";
        public static final String PASSWORD_HISTORY = "passwordHistory";
        public static final String PASSWORD_COMPLEX_CHARS = "passwordComplexChars";
        public static final String PASSWORD_MAX_FAILS = "passwordMaxFails";
        public static final String MAX_SCREEN_LOCK_TIME = "maxScreenLockTime";
        public static final String REQUIRE_REMOTE_WIPE = "requireRemoteWipe";
        public static final String REQUIRE_ENCRYPTION = "requireEncryption";
        public static final String REQUIRE_ENCRYPTION_EXTERNAL = "requireEncryptionExternal";
        // ICS additions
        // Note: the appearance of these columns does not imply that we support these features; only
        // that we store them in the Policy structure
        public static final String REQUIRE_MANUAL_SYNC_WHEN_ROAMING = "requireManualSyncRoaming";
        public static final String DONT_ALLOW_CAMERA = "dontAllowCamera";
        public static final String DONT_ALLOW_ATTACHMENTS = "dontAllowAttachments";
        public static final String DONT_ALLOW_HTML = "dontAllowHtml";
        public static final String MAX_ATTACHMENT_SIZE = "maxAttachmentSize";
        public static final String MAX_TEXT_TRUNCATION_SIZE = "maxTextTruncationSize";
        public static final String MAX_HTML_TRUNCATION_SIZE = "maxHTMLTruncationSize";
        public static final String MAX_EMAIL_LOOKBACK = "maxEmailLookback";
        public static final String MAX_CALENDAR_LOOKBACK = "maxCalendarLookback";
        // Indicates that the server allows password recovery, not that we support it
        public static final String PASSWORD_RECOVERY_ENABLED = "passwordRecoveryEnabled";
        // Tokenized strings indicating protocol specific policies enforced/unsupported
        public static final String PROTOCOL_POLICIES_ENFORCED = "protocolPoliciesEnforced";
        public static final String PROTOCOL_POLICIES_UNSUPPORTED = "protocolPoliciesUnsupported";
    }
}
