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
package com.android.exchange;

import com.android.email.R;
import com.android.email.provider.EmailProvider;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
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

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.UUID;

/**
 * This is a local copy of com.android.email.EmailProvider
 *
 * Last copied from com.android.email.EmailProvider on 7/18/09
 */

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
    public static final String AUTHORITY = EmailProvider.EMAIL_AUTHORITY;
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    private static final String[] COUNT_COLUMNS = new String[]{"count(*)"};

    // Newly created objects get this id
    private static final int NOT_SAVED = -1;
    // The base Uri that this piece of content came from
    public Uri mBaseUri;
    // Lazily initialized uri for this Content
    private Uri mUri = null;
    // The id of the Content
    public long mId = NOT_SAVED;
    
    // Write the Content into a ContentValues container
    public abstract ContentValues toContentValues();
    // Read the Content from a ContentCursor
    public abstract <T extends EmailContent> T restore (Cursor cursor);
    
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
    
    @SuppressWarnings("unchecked")
    // The Content sub class must have a no-arg constructor
    static public <T extends EmailContent> T getContent(Cursor cursor, Class<T> klass) {
        try {
            T content = klass.newInstance();
            content.mId = cursor.getLong(0);
            return (T)content.restore(cursor);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        }
        return null;
    }
    
    /**
     * Convenience method to save or update content.  Note:  This is designed to save or update
     * a single row, not for any bulk changes.
     */
    public Uri saveOrUpdate(Context context) {
        if (!isSaved()) {
            return save(context);
        } else {
            if (update(context, toContentValues()) == 1) {
                return getUri();
            }
            return null;
        }
    }
    
    public Uri save(Context context) {
        Uri res = context.getContentResolver().insert(mBaseUri, toContentValues());
        mId = Long.parseLong(res.getPathSegments().get(1));
        return res;
    }
    
    public int update(Context context, ContentValues contentValues) {
        return context.getContentResolver().update(getUri(), contentValues, null, null);
    }
    
    static public int update(Context context, Uri baseUri, long id, ContentValues contentValues) {
        return context.getContentResolver()
            .update(ContentUris.withAppendedId(baseUri, id), contentValues, null, null);
    }
    
    /**
     * Generic count method that can be used for any ContentProvider
     * @param context the calling Context
     * @param uri the Uri for the provider query
     * @param selection as with a query call
     * @param selectionArgs as with a query call
     * @return the number of items matching the query (or zero)
     */
    static public int count(Context context, Uri uri, String selection, String[] selectionArgs) {
        Cursor cursor = context.getContentResolver()
            .query(uri, COUNT_COLUMNS, selection, selectionArgs, null);
        try {
            if (!cursor.moveToFirst()) {
                return 0;
            }
            return cursor.getInt(0);
        } finally {
            cursor.close();
        }
    }

    /**
     * no public constructor since this is a utility class
     */
    private EmailContent() {
    }

    // All classes share this
    public static final String RECORD_ID = "_id";

    static ContentProviderOperation.Builder getSaveOrUpdateBuilder(boolean doSave, 
            Uri uri, long id) {
        if (doSave) {
            return ContentProviderOperation.newInsert(uri);
        } else {
            return ContentProviderOperation.newUpdate(ContentUris.withAppendedId(uri, id));
        }
    }
     
    public interface SyncColumns {
        // source (account name and type) : foreign key into the AccountsProvider
        public static final String ACCOUNT_KEY = "syncAccountKey";
        // source id (string) : the source's name of this item
        public static final String SERVER_ID = "syncServerId";
        // source version (string) : the source's concept of the version of this item
        public static final String SERVER_VERSION = "syncServerVersion";
        // source sync data (string) : any other data needed to sync this item back to the source
        public static final String DATA = "syncData";
        // dirty count (boolean) : the number of times this item has changed since the last time it
        // was synced to the server
        public static final String DIRTY_COUNT = "syncDirtyCount";
    }

    public interface BodyColumns {
        // Foreign key to the message corresponding to this body
        public static final String MESSAGE_KEY = "messageKey";      
        // The html content itself
        public static final String HTML_CONTENT = "htmlContent";    
        // The plain text content itself
        public static final String TEXT_CONTENT = "textContent";    
    }

    public static final class Body extends EmailContent implements BodyColumns {
        public static final String TABLE_NAME = "Body";
        public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/body");
  
       
        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_MESSAGE_KEY_COLUMN = 1;
        public static final int CONTENT_HTML_CONTENT_COLUMN = 2;
        public static final int CONTENT_TEXT_CONTENT_COLUMN = 3;
        public static final String[] CONTENT_PROJECTION = new String[] { 
            RECORD_ID, BodyColumns.MESSAGE_KEY, BodyColumns.HTML_CONTENT, BodyColumns.TEXT_CONTENT
        };

        public static final int TEXT_TEXT_COLUMN = 1;
        public static final String[] TEXT_PROJECTION = new String[] { 
            RECORD_ID, BodyColumns.TEXT_CONTENT
        };

        public static final int HTML_HTML_COLUMN = 1;
        public static final String[] HTML_PROJECTION = new String[] { 
            RECORD_ID, BodyColumns.HTML_CONTENT
        };

        public static final int COMMON_TEXT_COLUMN = 1;
        
        public long mMessageKey;
        public String mHtmlContent;
        public String mTextContent;

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

         private static String restoreTextWithMessageId(Context context, long messageId,
                 String[] projection) {
            Cursor c = context.getContentResolver().query(Body.CONTENT_URI, projection,
                    Body.MESSAGE_KEY + "=?", new String[] {Long.toString(messageId)}, null);
            try {
                if (c.moveToFirst()) {
                    return c.getString(COMMON_TEXT_COLUMN);
                } else {
                    return null;
                }
            } finally {
                c.close();
            }
        }

        public static String restoreBodyTextWithMessageId(Context context, long messageId) {
            return restoreTextWithMessageId(context, messageId, Body.TEXT_PROJECTION);
        }

        public static String restoreBodyHtmlWithMessageId(Context context, long messageId) {
            return restoreTextWithMessageId(context, messageId, Body.HTML_PROJECTION);
        }

        @Override
        @SuppressWarnings("unchecked")
        public EmailContent.Body restore(Cursor c) {
            mBaseUri = EmailContent.Body.CONTENT_URI;
            mMessageKey = c.getLong(CONTENT_MESSAGE_KEY_COLUMN);
            mHtmlContent = c.getString(CONTENT_HTML_CONTENT_COLUMN);
            mTextContent = c.getString(CONTENT_TEXT_CONTENT_COLUMN);
            return this;
        }

        public boolean update() {
            // TODO Auto-generated method stub
            return false;
        }
    }

    public interface MessageColumns {
        // Basic columns used in message list presentation
        // The name as shown to the user in a message list
        public static final String DISPLAY_NAME = "displayName";    
        // The time (millis) as shown to the user in a message list [INDEX]
        public static final String TIMESTAMP = "timeStamp";         
        // Message subject
        public static final String SUBJECT = "subject";             
        // A preview, as might be shown to the user in a message list
        public static final String PREVIEW = "preview";             
        // Boolean, unread = 0, read = 1 [INDEX]
        public static final String FLAG_READ = "flagRead";          
        // Three state, unloaded = 0, loaded = 1, partially loaded (optional) = 2 [INDEX]
        public static final String FLAG_LOADED = "flagLoaded";      
        // Boolean, unflagged = 0, flagged (favorite) = 1
        public static final String FLAG_FAVORITE = "flagFavorite";  
        // Boolean, no attachment = 0, attachment = 1
        public static final String FLAG_ATTACHMENT = "flagAttachment";  
        // Bit field, e.g. replied, deleted
        public static final String FLAGS = "flags";                 

        // Body related
        // charset: U = us-ascii; 8 = utf-8; I = iso-8559-1; others literally (e.g. KOI8-R)
        // encodings: B = base64; Q = quoted printable; X = none
        // Information about the text part (if any) in form <location>;<encoding>;<charset>;<length>
        public static final String TEXT_INFO = "textInfo";          
        // Information about the html part (if any) in form <location>;<encoding>;<charset>;<length>
        public static final String HTML_INFO = "htmlInfo";          

        // Sync related identifiers
        // Any client-required identifier
        public static final String CLIENT_ID = "clientId";          
        // The message-id in the message's header
        public static final String MESSAGE_ID = "messageId";        
        // Thread identifier
        public static final String THREAD_ID = "threadId";

        // References to other Email objects in the database
        // Foreign key to the Mailbox holding this message [INDEX]
        public static final String MAILBOX_KEY = "mailboxKey";      
        // Foreign key to the Account holding this message
        public static final String ACCOUNT_KEY = "accountKey";      
        // Foreign key to a referenced Message (e.g. for a reply/forward)
        public static final String REFERENCE_KEY = "referenceKey";  

        // Address lists, packed with Address.pack()
        public static final String SENDER_LIST = "senderList";
        public static final String FROM_LIST = "fromList";
        public static final String TO_LIST = "toList";
        public static final String CC_LIST = "ccList";
        public static final String BCC_LIST = "bccList";
        public static final String REPLY_TO_LIST = "replyToList";
    }

    public static final class Message extends EmailContent implements SyncColumns, MessageColumns {
        public static final String TABLE_NAME = "Message";
        public static final String UPDATED_TABLE_NAME = "Message_Updates";
        public static final String DELETED_TABLE_NAME = "Message_Deletes";

        // To refer to a specific message, use ContentUris.withAppendedId(CONTENT_URI, id)
        public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/message");
        public static final Uri SYNCED_CONTENT_URI =
            Uri.parse(EmailContent.CONTENT_URI + "/syncedMessage");
        public static final Uri DELETED_CONTENT_URI =
            Uri.parse(EmailContent.CONTENT_URI + "/deletedMessage");
        public static final Uri UPDATED_CONTENT_URI =
            Uri.parse(EmailContent.CONTENT_URI + "/updatedMessage");

        public static final String KEY_TIMESTAMP_DESC = MessageColumns.TIMESTAMP + " desc";

        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_DISPLAY_NAME_COLUMN = 1;
        public static final int CONTENT_TIMESTAMP_COLUMN = 2;
        public static final int CONTENT_SUBJECT_COLUMN = 3;
        public static final int CONTENT_PREVIEW_COLUMN = 4;
        public static final int CONTENT_FLAG_READ_COLUMN = 5;
        public static final int CONTENT_FLAG_LOADED_COLUMN = 6;
        public static final int CONTENT_FLAG_FAVORITE_COLUMN = 7;
        public static final int CONTENT_FLAG_ATTACHMENT_COLUMN = 8;
        public static final int CONTENT_FLAGS_COLUMN = 9;
        public static final int CONTENT_TEXT_INFO_COLUMN = 10;
        public static final int CONTENT_HTML_INFO_COLUMN = 11;
        public static final int CONTENT_SERVER_ID_COLUMN = 12;
        public static final int CONTENT_CLIENT_ID_COLUMN = 13;
        public static final int CONTENT_MESSAGE_ID_COLUMN = 14;
        public static final int CONTENT_THREAD_ID_COLUMN = 15;
        public static final int CONTENT_MAILBOX_KEY_COLUMN = 16;
        public static final int CONTENT_ACCOUNT_KEY_COLUMN = 17;
        public static final int CONTENT_REFERENCE_KEY_COLUMN = 18;
        public static final int CONTENT_SENDER_LIST_COLUMN = 19;
        public static final int CONTENT_FROM_LIST_COLUMN = 20;
        public static final int CONTENT_TO_LIST_COLUMN = 21;
        public static final int CONTENT_CC_LIST_COLUMN = 22;
        public static final int CONTENT_BCC_LIST_COLUMN = 23;
        public static final int CONTENT_REPLY_TO_COLUMN = 24;
        public static final int CONTENT_SERVER_VERSION_COLUMN = 25;
        public static final String[] CONTENT_PROJECTION = new String[] { 
            RECORD_ID, MessageColumns.DISPLAY_NAME, MessageColumns.TIMESTAMP, 
            MessageColumns.SUBJECT, MessageColumns.PREVIEW, MessageColumns.FLAG_READ,
            MessageColumns.FLAG_LOADED, MessageColumns.FLAG_FAVORITE,
            MessageColumns.FLAG_ATTACHMENT, MessageColumns.FLAGS, MessageColumns.TEXT_INFO,
            MessageColumns.HTML_INFO, SyncColumns.SERVER_ID,
            MessageColumns.CLIENT_ID, MessageColumns.MESSAGE_ID, MessageColumns.THREAD_ID,
            MessageColumns.MAILBOX_KEY, MessageColumns.ACCOUNT_KEY, MessageColumns.REFERENCE_KEY,
            MessageColumns.SENDER_LIST, MessageColumns.FROM_LIST, MessageColumns.TO_LIST,
            MessageColumns.CC_LIST, MessageColumns.BCC_LIST, MessageColumns.REPLY_TO_LIST,
            SyncColumns.SERVER_VERSION
        };

        public static final int LIST_ID_COLUMN = 0;
        public static final int LIST_DISPLAY_NAME_COLUMN = 1;
        public static final int LIST_TIMESTAMP_COLUMN = 2;
        public static final int LIST_SUBJECT_COLUMN = 3;
        public static final int LIST_PREVIEW_COLUMN = 4;
        public static final int LIST_READ_COLUMN = 5;
        public static final int LIST_LOADED_COLUMN = 6;
        public static final int LIST_FAVORITE_COLUMN = 7;
        public static final int LIST_ATTACHMENT_COLUMN = 8;
        public static final int LIST_FLAGS_COLUMN = 9;
        public static final int LIST_MAILBOX_KEY_COLUMN = 10;
        public static final int LIST_ACCOUNT_KEY_COLUMN = 11;
        public static final int LIST_SERVER_ID_COLUMN = 12;

        // Public projection for common list columns
        public static final String[] LIST_PROJECTION = new String[] { 
            RECORD_ID, MessageColumns.DISPLAY_NAME, MessageColumns.TIMESTAMP,
            MessageColumns.SUBJECT, MessageColumns.PREVIEW, MessageColumns.FLAG_READ,
            MessageColumns.FLAG_LOADED, MessageColumns.FLAG_FAVORITE,
            MessageColumns.FLAG_ATTACHMENT, MessageColumns.FLAGS, MessageColumns.MAILBOX_KEY,
            MessageColumns.ACCOUNT_KEY , SyncColumns.SERVER_ID
        };

        public static final int LOAD_BODY_ID_COLUMN = 0;
        public static final int LOAD_BODY_SERVER_ID_COLUMN = 1;
        public static final int LOAD_BODY_TEXT_INFO_COLUMN = 2;
        public static final int LOAD_BODY_HTML_INFO_COLUMN  = 3;
        public static final String[] LOAD_BODY_PROJECTION = new String[] {
            RECORD_ID, SyncColumns.SERVER_ID, MessageColumns.TEXT_INFO, MessageColumns.HTML_INFO
        };

        public static final int ID_COLUMNS_ID_COLUMN = 0;
        public static final int ID_COLUMNS_SYNC_SERVER_ID = 1;
        public static final String[] ID_COLUMNS_PROJECTION = new String[] {
            RECORD_ID, SyncColumns.SERVER_ID
        };

        public static final String[] ID_COLUMN_PROJECTION = new String[] { RECORD_ID };

        // _id field is in AbstractContent
        public String mDisplayName;
        public long mTimeStamp;
        public String mSubject;
        public String mPreview;
        public boolean mFlagRead = false;
        public int mFlagLoaded = 0;
        public boolean mFlagFavorite = false;
        public boolean mFlagAttachment = false;
        public int mFlags = 0;

        public String mTextInfo;
        public String mHtmlInfo;

        public String mServerId;
        public int mServerIntId;
        public String mClientId;
        public String mMessageId;
        public String mThreadId;

        public long mMailboxKey;
        public long mAccountKey;
        public long mReferenceKey;

        public String mSender;
        public String mFrom;
        public String mTo;
        public String mCc;
        public String mBcc;
        public String mReplyTo;
        
        public String mServerVersion;

        transient public String mText;
        transient public String mHtml;

        // Can be used while building messages, but is NOT saved by the Provider
        transient public ArrayList<Attachment> mAttachments = null;

        public static final int UNREAD = 0;
        public static final int READ = 1;
        public static final int DELETED = 2;

        public static final int NOT_LOADED = 0;
        public static final int LOADED = 1;
        public static final int PARTIALLY_LOADED = 2;

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
            values.put(MessageColumns.PREVIEW, mPreview);
            values.put(MessageColumns.FLAG_READ, mFlagRead); 
            values.put(MessageColumns.FLAG_LOADED, mFlagLoaded); 
            values.put(MessageColumns.FLAG_FAVORITE, mFlagFavorite); 
            values.put(MessageColumns.FLAG_ATTACHMENT, mFlagAttachment); 
            values.put(MessageColumns.FLAGS, mFlags);

            values.put(MessageColumns.TEXT_INFO, mTextInfo);
            values.put(MessageColumns.HTML_INFO, mHtmlInfo);

            if (mServerId != null) {
                values.put(SyncColumns.SERVER_ID, mServerId);
            } else {
                values.put(SyncColumns.SERVER_ID, mServerIntId);
            }

            values.put(MessageColumns.CLIENT_ID, mClientId);
            values.put(MessageColumns.MESSAGE_ID, mMessageId);
            values.put(MessageColumns.THREAD_ID, mThreadId);

            values.put(MessageColumns.MAILBOX_KEY, mMailboxKey);
            values.put(MessageColumns.ACCOUNT_KEY, mAccountKey);
            values.put(MessageColumns.REFERENCE_KEY, mReferenceKey);

            values.put(MessageColumns.SENDER_LIST, mSender);
            values.put(MessageColumns.FROM_LIST, mFrom);
            values.put(MessageColumns.TO_LIST, mTo);
            values.put(MessageColumns.CC_LIST, mCc);
            values.put(MessageColumns.BCC_LIST, mBcc);
            values.put(MessageColumns.REPLY_TO_LIST, mReplyTo);
            
            values.put(SyncColumns.SERVER_VERSION, mServerVersion);

            return values;
        }

        public static Message restoreMessageWithId(Context context, long id) {
            Uri u = ContentUris.withAppendedId(Message.CONTENT_URI, id);
            Cursor c = context.getContentResolver().query(u, Message.CONTENT_PROJECTION,
                    null, null, null);

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
        @SuppressWarnings("unchecked")
        public EmailContent.Message restore(Cursor c) {
            mBaseUri = CONTENT_URI;
            mId = c.getLong(CONTENT_ID_COLUMN);
            mDisplayName = c.getString(CONTENT_DISPLAY_NAME_COLUMN);
            mTimeStamp = c.getLong(CONTENT_TIMESTAMP_COLUMN);
            mSubject = c.getString(CONTENT_SUBJECT_COLUMN);
            mPreview = c.getString(CONTENT_PREVIEW_COLUMN);
            mFlagRead = c.getInt(CONTENT_FLAG_READ_COLUMN) == 1;
            mFlagLoaded = c.getInt(CONTENT_FLAG_LOADED_COLUMN);
            mFlagFavorite = c.getInt(CONTENT_FLAG_FAVORITE_COLUMN) == 1;
            mFlagAttachment = c.getInt(CONTENT_FLAG_ATTACHMENT_COLUMN) == 1;
            mFlags = c.getInt(CONTENT_FLAGS_COLUMN);
            mTextInfo = c.getString(CONTENT_TEXT_INFO_COLUMN);
            mHtmlInfo = c.getString(CONTENT_HTML_INFO_COLUMN);
            mServerId = c.getString(CONTENT_SERVER_ID_COLUMN);
            mServerIntId = c.getInt(CONTENT_SERVER_ID_COLUMN);
            mClientId = c.getString(CONTENT_CLIENT_ID_COLUMN);
            mMessageId = c.getString(CONTENT_MESSAGE_ID_COLUMN);
            mThreadId = c.getString(CONTENT_THREAD_ID_COLUMN);
            mMailboxKey = c.getLong(CONTENT_MAILBOX_KEY_COLUMN);
            mAccountKey = c.getLong(CONTENT_ACCOUNT_KEY_COLUMN);
            mReferenceKey = c.getLong(CONTENT_REFERENCE_KEY_COLUMN);
            mSender = c.getString(CONTENT_SENDER_LIST_COLUMN);
            mFrom = c.getString(CONTENT_FROM_LIST_COLUMN);
            mTo = c.getString(CONTENT_TO_LIST_COLUMN);
            mCc = c.getString(CONTENT_CC_LIST_COLUMN);
            mBcc = c.getString(CONTENT_BCC_LIST_COLUMN);
            mReplyTo = c.getString(CONTENT_REPLY_TO_COLUMN);
            mServerVersion = c.getString(CONTENT_SERVER_VERSION_COLUMN);
            return this;
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
            if (mText == null && mHtml == null &&
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
                    context.getContentResolver().applyBatch(EmailProvider.EMAIL_AUTHORITY, ops);
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
            ContentProviderOperation.Builder b = getSaveOrUpdateBuilder(true, mBaseUri, -1);
            ops.add(b.withValues(toContentValues()).build());

            // Create and save the body
            ContentValues cv = new ContentValues();
            if (mText != null) {
                cv.put(Body.TEXT_CONTENT, mText);
            }
            if (mHtml != null) {
                cv.put(Body.HTML_CONTENT, mHtml);
            }
            b = getSaveOrUpdateBuilder(true, Body.CONTENT_URI, 0);
            b.withValues(cv);
            ContentValues backValues = new ContentValues();
            int messageBackValue = ops.size() - 1;
            backValues.put(Body.MESSAGE_KEY, messageBackValue);
            ops.add(b.withValueBackReferences(backValues).build());

            // Create the attaachments, if any
            if (mAttachments != null) {
                for (Attachment att: mAttachments) {
                    ops.add(getSaveOrUpdateBuilder(true, Attachment.CONTENT_URI, -1)
                        .withValues(att.toContentValues())
                        .withValueBackReference(Attachment.MESSAGE_KEY, messageBackValue)
                        .build());
                }
            }
         }

       // Text and Html information are stored as <location>;<encoding>;<charset>;<length>
        // charset: U = us-ascii; 8 = utf-8; I = iso-8559-1; others literally (e.g. KOI8-R)
        // encodings: B = base64; Q = quoted printable; X = none

        public static final class BodyInfo {
            public String mLocation;
            public char mEncoding;
            public String mCharset;
            public long mLength;

            static public BodyInfo expandFromTextOrHtmlInfo (String info) {
                BodyInfo b = new BodyInfo();
                int start = 0;
                int next = info.indexOf(';');
                if (next > 0) {
                    b.mLocation = info.substring(start, next);
                    start = next + 1;
                    next = info.indexOf(';', start);
                    if (next > 0) {
                        b.mEncoding = info.charAt(start);
                        start = next + 1;
                        next = info.indexOf(';', start);
                        if (next > 0) {
                            String cs = info.substring(start, next);
                            if (cs.equals("U")) {
                                b.mCharset = "us-ascii";
                            } else if (cs.equals("I")) {
                                b.mCharset = "iso-8859-1";
                            } else if (cs.equals("8")) {
                                b.mCharset = "utf-8";
                            } else {
                                b.mCharset = cs;
                            }
                            start = next + 1;
                            b.mLength = Integer.parseInt(info.substring(start));
                            return b;
                        }
                    }

                }
                return null;
            }
        }
    }

    public interface AccountColumns {
        // The display name of the account (user-settable)
        public static final String DISPLAY_NAME = "displayName";
        // The email address corresponding to this account
        public static final String EMAIL_ADDRESS = "emailAddress";
        // A server-based sync key on an account-wide basis (EAS needs this)
        public static final String SYNC_KEY = "syncKey";
        // The default sync lookback period for this account
        public static final String SYNC_LOOKBACK = "syncLookback";
        // The default sync frequency for this account
        public static final String SYNC_FREQUENCY = "syncFrequency";
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
    }

    public static final class Account extends EmailContent implements AccountColumns, Parcelable {
        public static final String TABLE_NAME = "Account";
        public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/account");

        
        public final static int FLAGS_NOTIFY_NEW_MAIL = 1;
        public final static int FLAGS_VIBRATE = 2;
        public static final int FLAGS_DELETE_POLICY_MASK = 4+8;
        public static final int FLAGS_DELETE_POLICY_SHIFT = 2;
        
        public static final int DELETE_POLICY_NEVER = 0;
        public static final int DELETE_POLICY_7DAYS = 1;
        public static final int DELETE_POLICY_ON_DELETE = 2;
        
        public static final int CHECK_INTERVAL_NEVER = -1;
        public static final int CHECK_INTERVAL_PUSH = -2;
        public static final int CHECK_INTERVAL_PING = -3;
        
        public static final int SYNC_WINDOW_USER = -1;

        public String mDisplayName;
        public String mEmailAddress;
        public String mSyncKey;
        public int mSyncLookback;
        public int mSyncFrequency;
        public long mHostAuthKeyRecv; 
        public long mHostAuthKeySend;
        public int mFlags;
        public boolean mIsDefault;
        public String mCompatibilityUuid;
        public String mSenderName;
        public String mRingtoneUri;
        public String mProtocolVersion;

        // Convenience for creating an account
        public transient HostAuth mHostAuthRecv;
        public transient HostAuth mHostAuthSend;

        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_DISPLAY_NAME_COLUMN = 1;
        public static final int CONTENT_EMAIL_ADDRESS_COLUMN = 2;
        public static final int CONTENT_SYNC_KEY_COLUMN = 3;
        public static final int CONTENT_SYNC_LOOKBACK_COLUMN = 4;
        public static final int CONTENT_SYNC_FREQUENCY_COLUMN = 5;
        public static final int CONTENT_HOST_AUTH_KEY_RECV_COLUMN = 6;
        public static final int CONTENT_HOST_AUTH_KEY_SEND_COLUMN = 7;
        public static final int CONTENT_FLAGS_COLUMN = 8;
        public static final int CONTENT_IS_DEFAULT_COLUMN = 9;
        public static final int CONTENT_COMPATIBILITY_UUID_COLUMN = 10;
        public static final int CONTENT_SENDER_NAME_COLUMN = 11;
        public static final int CONTENT_RINGTONE_URI_COLUMN = 12;
        public static final int CONTENT_PROTOCOL_VERSION_COLUMN = 13;

        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, AccountColumns.DISPLAY_NAME,
            AccountColumns.EMAIL_ADDRESS, AccountColumns.SYNC_KEY, AccountColumns.SYNC_LOOKBACK,
            AccountColumns.SYNC_FREQUENCY, AccountColumns.HOST_AUTH_KEY_RECV,
            AccountColumns.HOST_AUTH_KEY_SEND, AccountColumns.FLAGS, AccountColumns.IS_DEFAULT,
            AccountColumns.COMPATIBILITY_UUID, AccountColumns.SENDER_NAME,
            AccountColumns.RINGTONE_URI, AccountColumns.PROTOCOL_VERSION
        };
        
        /**
         * This projection is for listing account id's only
         */
        public static final String[] ID_PROJECTION = new String[] {
            RECORD_ID
        };

        /**
         * no public constructor since this is a utility class
         */
        public Account() {
            mBaseUri = CONTENT_URI;
            
            // other defaults (policy)
            mRingtoneUri = "content://settings/system/notification_sound";
            mSyncFrequency = -1;
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
        @SuppressWarnings("unchecked")
        public EmailContent.Account restore(Cursor cursor) {
            mId = cursor.getLong(CONTENT_ID_COLUMN);
            mBaseUri = CONTENT_URI;
            mDisplayName = cursor.getString(CONTENT_DISPLAY_NAME_COLUMN);
            mEmailAddress = cursor.getString(CONTENT_EMAIL_ADDRESS_COLUMN);
            mSyncKey = cursor.getString(CONTENT_SYNC_KEY_COLUMN);
            mSyncLookback = cursor.getInt(CONTENT_SYNC_LOOKBACK_COLUMN);
            mSyncFrequency = cursor.getInt(CONTENT_SYNC_FREQUENCY_COLUMN);
            mHostAuthKeyRecv = cursor.getLong(CONTENT_HOST_AUTH_KEY_RECV_COLUMN);
            mHostAuthKeySend = cursor.getLong(CONTENT_HOST_AUTH_KEY_SEND_COLUMN);
            mFlags = cursor.getInt(CONTENT_FLAGS_COLUMN);
            mIsDefault = cursor.getInt(CONTENT_IS_DEFAULT_COLUMN) == 1;
            mCompatibilityUuid = cursor.getString(CONTENT_COMPATIBILITY_UUID_COLUMN);
            mSenderName = cursor.getString(CONTENT_SENDER_NAME_COLUMN);
            mRingtoneUri = cursor.getString(CONTENT_RINGTONE_URI_COLUMN);
            mProtocolVersion = cursor.getString(CONTENT_PROTOCOL_VERSION_COLUMN);
            return this;
        }

        private long getId(Uri u) {
            return Long.parseLong(u.getPathSegments().get(1));
        }
        
        /**
         * @return the user-visible name for the account
         */
        public String getDescription() {
            return mDisplayName;
        }
        
        /**
         * Set the description.  Be sure to call save() to commit to database.
         * @param description the new description
         */
        public void setDescription(String description) {
            mDisplayName = description;
        }
        
        /**
         * @return the email address for this account
         */
        public String getEmail() {
            return mEmailAddress;
        }
        
        /**
         * Set the Email address for this account.  Be sure to call save() to commit to database.
         * @param emailAddress the new email address for this account
         */
        public void setEmail(String emailAddress) {
            mEmailAddress = emailAddress;
        }
        
        /**
         * @return the sender's name for this account
         */
        public String getName() {
            return mSenderName;
        }
        
        /**
         * Set the sender's name.  Be sure to call save() to commit to database.
         * @param name the new sender name
         */
        public void setName(String name) {
            mSenderName = name;
        }
        
        /**
         * @return the minutes per check (for polling)
         * TODO define sentinel values for "never", "push", etc.  See Account.java
         */
        public int getAutomaticCheckIntervalMinutes()
        {
            return mSyncFrequency;
        }
        
        /**
         * Set the minutes per check (for polling).  Be sure to call save() to commit to database.
         * TODO define sentinel values for "never", "push", etc.  See Account.java
         * @param minutes the number of minutes between polling checks
         */
        public void setAutomaticCheckIntervalMinutes(int minutes)
        {
            mSyncFrequency = minutes;
        }
        
        /**
         * @return the sync lookback window in # of days
         * TODO define sentinel values for "all", "1 month", etc.  See Account.java
         */
        public int getSyncWindow() {
            return mSyncLookback;
        }
        
        /**
         * Set the sync lookback window in # of days.  Be sure to call save() to commit to database.
         * TODO define sentinel values for "all", "1 month", etc.  See Account.java
         * @param days number of days to look back for syncing messages
         */
        public void setSyncWindow(int days) {
            mSyncLookback = days;
        }
        
        /**
         * @return the flags for this account
         * @see #FLAGS_NOTIFY_NEW_MAIL
         * @see #FLAGS_VIBRATE
         */
        public int getFlags() {
            return mFlags;
        }
        
        /**
         * Set the flags for this account
         * @see #FLAGS_NOTIFY_NEW_MAIL
         * @see #FLAGS_VIBRATE
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
        String getUuid() {
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
        
        /**
         * For compatibility while converting to provider model, set the store URI
         * 
         * @param the new value
         */
        @Deprecated
        public void setStoreUri(Context context, String senderUri) {
            // reconstitute or create if necessary
            if (mHostAuthRecv == null) {
                if (mHostAuthKeyRecv != 0) {
                    mHostAuthRecv = HostAuth.restoreHostAuthWithId(context, mHostAuthKeyRecv);
                } else {
                    mHostAuthRecv = new EmailContent.HostAuth();
                }
            }
            
            if (mHostAuthRecv != null) {
                mHostAuthRecv.setStoreUri(senderUri);
            }
        }
        
        /**
         * For compatibility while converting to provider model, set the sender URI
         * 
         * @param the new value
         */
        @Deprecated
        public void setSenderUri(Context context, String senderUri) {
            // reconstitute or create if necessary
            if (mHostAuthSend == null) {
                if (mHostAuthKeySend != 0) {
                    mHostAuthSend = HostAuth.restoreHostAuthWithId(context, mHostAuthKeySend);
                } else {
                    mHostAuthSend = new EmailContent.HostAuth();
                }
            }
            
            if (mHostAuthSend != null) {
                mHostAuthSend.setStoreUri(senderUri);
            }
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
         * Set the account to be the default account.  If this is set to "true", when the account
         * is saved, all other accounts will have the same value set to "false".
         * @param newDefaultState the new default state - if true, others will be cleared.
         */
        public void setDefaultAccount(boolean newDefaultState) {
            mIsDefault = newDefaultState;
        }

        static private Account getAccountWhere(Context context, String where) {
            Cursor cursor = context.getContentResolver().query(CONTENT_URI, CONTENT_PROJECTION, 
                    where, null, null);
            try {
                if (cursor.moveToFirst()) {
                    return getContent(cursor, Account.class);
                }
            } finally {
                cursor.close();
            }
            return null;
        }

        /**
         * Return the default Account; if one hasn't been explicitly specified, return the first
         * account found in the database.
         * @param context
         * @return the default Account (or none, if there are no accounts)
         */
        static public Account getDefaultAccount(Context context) {
            Account acct = getAccountWhere(context, AccountColumns.IS_DEFAULT + "=1");
            if (acct == null) {
                acct = getAccountWhere(context, null);
            }
            return acct;
        }

        /**
         * Do not use Account.save().  Use Account.saveOrUpdate()
         */
        @Override 
        public Uri save(Context context) {
            throw new UnsupportedOperationException();
        }
        
        /**
         * Do not use Account.update().  Use Account.saveOrUpdate()
         */
        @Override 
        public int update(Context context, ContentValues contentValues) {
            throw new UnsupportedOperationException();
        }
        
        /* 
         * Override this so that we can store the HostAuth's first and link them to the Account
         * (non-Javadoc)
         * @see com.android.email.provider.EmailContent#save(android.content.Context)
         */
        @Override 
        public Uri saveOrUpdate(Context context) {
            
            boolean doSave = !isSaved();
            
            // This logic is in place so I can (a) short circuit the expensive stuff when
            // possible, and (b) override (and throw) if anyone tries to call save() or update()
            // directly for Account, which are unsupported.
            if (mHostAuthRecv == null && mHostAuthSend == null && mIsDefault == false) {
                if (doSave) {
                    return super.save(context);
                } else {
                    if (super.update(context, toContentValues()) == 1) {
                        return getUri();
                    }
                    return null;
                }
            }
            
            int index = 0;
            int recvIndex = -1;
            int sendIndex = -1;

            // Create operations for saving the send and recv hostAuths
            // Also, remember which operation in the array they represent
            ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();
            if (mHostAuthRecv != null) {
                recvIndex = index++;
                ops.add(getSaveOrUpdateBuilder(doSave, mHostAuthRecv.mBaseUri, mHostAuthRecv.mId)
                        .withValues(mHostAuthRecv.toContentValues())
                        .build());
            }
            if (mHostAuthSend != null) {
                sendIndex = index++;
                ops.add(getSaveOrUpdateBuilder(doSave, mHostAuthSend.mBaseUri, mHostAuthSend.mId)
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
            if (doSave && (recvIndex >= 0 || sendIndex >= 0)) {
                cv = new ContentValues();
                if (recvIndex >= 0) {
                    cv.put(Account.HOST_AUTH_KEY_RECV, recvIndex);
                }
                if (sendIndex >= 0) {
                    cv.put(Account.HOST_AUTH_KEY_SEND, sendIndex);
                }
            }

            ContentProviderOperation.Builder b = getSaveOrUpdateBuilder(doSave, mBaseUri, mId);
            b.withValues(toContentValues());
            if (cv != null) {
                b.withValueBackReferences(cv);
            }
            ops.add(b.build());
            
            try {
                ContentProviderResult[] results = 
                    context.getContentResolver().applyBatch(EmailProvider.EMAIL_AUTHORITY, ops);
                // If saving, set the mId's of the various saved objects
                if (doSave) {
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

        @Override
        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();
            values.put(AccountColumns.DISPLAY_NAME, mDisplayName);
            values.put(AccountColumns.EMAIL_ADDRESS, mEmailAddress);
            values.put(AccountColumns.SYNC_KEY, mSyncKey);
            values.put(AccountColumns.SYNC_LOOKBACK, mSyncLookback);
            values.put(AccountColumns.SYNC_FREQUENCY, mSyncFrequency);
            values.put(AccountColumns.HOST_AUTH_KEY_RECV, mHostAuthKeyRecv);
            values.put(AccountColumns.HOST_AUTH_KEY_SEND, mHostAuthKeySend);
            values.put(AccountColumns.FLAGS, mFlags);
            values.put(AccountColumns.IS_DEFAULT, mIsDefault ? 1 : 0);
            values.put(AccountColumns.COMPATIBILITY_UUID, mCompatibilityUuid);
            values.put(AccountColumns.SENDER_NAME, mSenderName);
            values.put(AccountColumns.RINGTONE_URI, mRingtoneUri);
            values.put(AccountColumns.PROTOCOL_VERSION, mProtocolVersion);
            return values;
        }
        
        /**
         * TODO don't store these names in the account - just tag the folders
         */
        public String getDraftsFolderName(Context context) {
            return context.getString(R.string.special_mailbox_name_drafts);
        }

        /**
         * TODO don't store these names in the account - just tag the folders
         */
        public String getSentFolderName(Context context) {
            return context.getString(R.string.special_mailbox_name_sent);
        }

        /**
         * TODO don't store these names in the account - just tag the folders
         */
        public String getTrashFolderName(Context context) {
            return context.getString(R.string.special_mailbox_name_trash);
        }

        /**
         * TODO don't store these names in the account - just tag the folders
         */
        public String getOutboxFolderName(Context context) {
            return context.getString(R.string.special_mailbox_name_outbox);
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
            dest.writeInt(mSyncFrequency);
            dest.writeLong(mHostAuthKeyRecv); 
            dest.writeLong(mHostAuthKeySend);
            dest.writeInt(mFlags);
            dest.writeByte(mIsDefault ? (byte)1 : (byte)0);
            dest.writeString(mCompatibilityUuid);
            dest.writeString(mSenderName);
            dest.writeString(mRingtoneUri);
            
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
            mSyncFrequency = in.readInt();
            mHostAuthKeyRecv = in.readLong(); 
            mHostAuthKeySend = in.readLong();
            mFlags = in.readInt();
            mIsDefault = in.readByte() == 1;
            mCompatibilityUuid = in.readString();
            mSenderName = in.readString();
            mRingtoneUri = in.readString();
            
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
        // The display name of the attachment 
        public static final String FILENAME = "fileName";
        // The mime type of the attachment
        public static final String MIME_TYPE = "mimeType";
        // The size of the attachment in bytes
        public static final String SIZE = "size";
        // The (internal) contentId of the attachment (inline attachments will have these)
        public static final String CONTENT_ID = "contentId";
        // The location of the loaded attachment (probably a file)
        public static final String CONTENT_URI = "contentUri";
        // A foreign key into the Message table (the message owning this attachment)
        public static final String MESSAGE_KEY = "messageKey";
        // The location of the attachment on the server side
        // For IMAP, this is a part number (e.g. 2.1); for EAS, it's the internal file name
        public static final String LOCATION = "location";
        // The transfer encoding of the attachment
        public static final String ENCODING = "encoding";
    }

    public static final class Attachment extends EmailContent implements AttachmentColumns {
        public static final String TABLE_NAME = "Attachment";
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

        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_FILENAME_COLUMN = 1;
        public static final int CONTENT_MIME_TYPE_COLUMN = 2;
        public static final int CONTENT_SIZE_COLUMN = 3;
        public static final int CONTENT_CONTENT_ID_COLUMN = 4;
        public static final int CONTENT_CONTENT_URI_COLUMN = 5;
        public static final int CONTENT_MESSAGE_ID_COLUMN = 6;
        public static final int CONTENT_LOCATION_COLUMN = 7;
        public static final int CONTENT_ENCODING_COLUMN = 8;
        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, AttachmentColumns.FILENAME, AttachmentColumns.MIME_TYPE,
            AttachmentColumns.SIZE, AttachmentColumns.CONTENT_ID, AttachmentColumns.CONTENT_URI,
            AttachmentColumns.MESSAGE_KEY, AttachmentColumns.LOCATION, AttachmentColumns.ENCODING
        };

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
        @SuppressWarnings("unchecked")
        public EmailContent.Attachment restore(Cursor cursor) {
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
            return this;
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
    }

    public interface MailboxColumns {
        public static final String ID = "_id";
        // The display name of this mailbox [INDEX]
        static final String DISPLAY_NAME = "displayName"; 
        // The server's identifier for this mailbox
        public static final String SERVER_ID = "serverId";
        // The server's identifier for the parent of this mailbox (null = top-level)
        public static final String PARENT_SERVER_ID = "parentServerId";
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
        public static final String SYNC_FREQUENCY = "syncFrequency";
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
    }

    public static final class Mailbox extends EmailContent implements SyncColumns, MailboxColumns {
        public static final String TABLE_NAME = "Mailbox";
        public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/mailbox");

        public String mDisplayName;
        public String mServerId;
        public String mParentServerId;
        public long mAccountKey;
        public int mType;
        public int mDelimiter;
        public String mSyncKey;
        public int mSyncLookback;
        public int mSyncFrequency;
        public long mSyncTime;
        public int mUnreadCount;
        public boolean mFlagVisible = true;
        public int mFlags;
        public int mVisibleLimit;

        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_DISPLAY_NAME_COLUMN = 1;
        public static final int CONTENT_SERVER_ID_COLUMN = 2;
        public static final int CONTENT_PARENT_SERVER_ID_COLUMN = 3;
        public static final int CONTENT_ACCOUNT_KEY_COLUMN = 4;
        public static final int CONTENT_TYPE_COLUMN = 5;
        public static final int CONTENT_DELIMITER_COLUMN = 6;
        public static final int CONTENT_SYNC_KEY_COLUMN = 7;
        public static final int CONTENT_SYNC_LOOKBACK_COLUMN = 8;
        public static final int CONTENT_SYNC_FREQUENCY_COLUMN = 9;
        public static final int CONTENT_SYNC_TIME_COLUMN = 10;
        public static final int CONTENT_UNREAD_COUNT_COLUMN = 11;
        public static final int CONTENT_FLAG_VISIBLE_COLUMN = 12;
        public static final int CONTENT_FLAGS_COLUMN = 13;
        public static final int CONTENT_VISIBLE_LIMIT_COLUMN = 14;
        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, MailboxColumns.DISPLAY_NAME, MailboxColumns.SERVER_ID,
            MailboxColumns.PARENT_SERVER_ID, MailboxColumns.ACCOUNT_KEY, MailboxColumns.TYPE,
            MailboxColumns.DELIMITER, MailboxColumns.SYNC_KEY, MailboxColumns.SYNC_LOOKBACK,
            MailboxColumns.SYNC_FREQUENCY, MailboxColumns.SYNC_TIME,MailboxColumns.UNREAD_COUNT,
            MailboxColumns.FLAG_VISIBLE, MailboxColumns.FLAGS, MailboxColumns.VISIBLE_LIMIT
        };
        public static final long NO_MAILBOX = -1;

        private static final String WHERE_TYPE_AND_ACCOUNT_KEY =
            MailboxColumns.TYPE + "=? and " + MailboxColumns.ACCOUNT_KEY + "=?";

        private static final int ID_PROJECTION_ID = 0;
        private static final String[] ID_PROJECTION = new String[] { ID };

        public Mailbox() {
            mBaseUri = CONTENT_URI;
        }

        // Types of mailboxes.  The list is ordered to match a typical UI presentation, e.g.
        // placing the inbox at the top.
        // The "main" mailbox for the account, almost always referred to as "Inbox"
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

        // Bit field flags
        public static final int FLAG_HAS_CHILDREN = 1<<0;
        public static final int FLAG_CHILDREN_VISIBLE = 1<<1;
        public static final int FLAG_CANT_PUSH = 1<<2;

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
                    return EmailContent.getContent(c, Mailbox.class);
                } else {
                    return null;
                }
            } finally {
                c.close();
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public EmailContent.Mailbox restore(Cursor cursor) {
            mBaseUri = CONTENT_URI;
            mId = cursor.getLong(CONTENT_ID_COLUMN);
            mDisplayName = cursor.getString(CONTENT_DISPLAY_NAME_COLUMN);
            mServerId = cursor.getString(CONTENT_SERVER_ID_COLUMN);
            mParentServerId = cursor.getString(CONTENT_PARENT_SERVER_ID_COLUMN);
            mAccountKey = cursor.getLong(CONTENT_ACCOUNT_KEY_COLUMN);
            mType = cursor.getInt(CONTENT_TYPE_COLUMN);
            mDelimiter = cursor.getInt(CONTENT_DELIMITER_COLUMN);
            mSyncKey = cursor.getString(CONTENT_SYNC_KEY_COLUMN);
            mSyncLookback = cursor.getInt(CONTENT_SYNC_LOOKBACK_COLUMN);
            mSyncFrequency = cursor.getInt(CONTENT_SYNC_FREQUENCY_COLUMN);
            mSyncTime = cursor.getLong(CONTENT_SYNC_TIME_COLUMN);
            mUnreadCount = cursor.getInt(CONTENT_UNREAD_COUNT_COLUMN);
            mFlagVisible = cursor.getInt(CONTENT_FLAG_VISIBLE_COLUMN) == 1;
            mFlags = cursor.getInt(CONTENT_FLAGS_COLUMN);
            mVisibleLimit = cursor.getInt(CONTENT_VISIBLE_LIMIT_COLUMN);
            return this;
        }

        @Override
        public ContentValues toContentValues() {
            ContentValues values = new ContentValues();
            values.put(MailboxColumns.DISPLAY_NAME, mDisplayName);           
            values.put(MailboxColumns.SERVER_ID, mServerId);         
            values.put(MailboxColumns.PARENT_SERVER_ID, mParentServerId);            
            values.put(MailboxColumns.ACCOUNT_KEY, mAccountKey);         
            values.put(MailboxColumns.TYPE, mType);          
            values.put(MailboxColumns.DELIMITER, mDelimiter);            
            values.put(MailboxColumns.SYNC_KEY, mSyncKey);           
            values.put(MailboxColumns.SYNC_LOOKBACK, mSyncLookback);         
            values.put(MailboxColumns.SYNC_FREQUENCY, mSyncFrequency);           
            values.put(MailboxColumns.SYNC_TIME, mSyncTime);         
            values.put(MailboxColumns.UNREAD_COUNT, mUnreadCount);           
            values.put(MailboxColumns.FLAG_VISIBLE, mFlagVisible);           
            values.put(MailboxColumns.FLAGS, mFlags);            
            values.put(MailboxColumns.VISIBLE_LIMIT, mVisibleLimit);         
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
            long mailboxId = NO_MAILBOX;
            String[] bindArguments = new String[] {Long.toString(type), Long.toString(accountId)};
            Cursor c = context.getContentResolver().query(Mailbox.CONTENT_URI,
                    ID_PROJECTION, WHERE_TYPE_AND_ACCOUNT_KEY, bindArguments, null);
            try {
                if (c.moveToFirst()) {
                    mailboxId = c.getLong(ID_PROJECTION_ID);
                }
            } finally {
                c.close();
            }
            return mailboxId;
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
        // Foreign key of the Account this is attached to
        static final String ACCOUNT_KEY = "accountKey";
    }

    public static final class HostAuth extends EmailContent implements HostAuthColumns, Parcelable {
        public static final String TABLE_NAME = "HostAuth";
        public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/hostauth");

        public static final int FLAG_SSL = 1;
        public static final int FLAG_TLS = 2;
        public static final int FLAG_AUTHENTICATE = 4;

        public String mProtocol;
        public String mAddress;
        public int mPort;
        public int mFlags;
        public String mLogin;
        public String mPassword;
        public String mDomain;
        public long mAccountKey;

        public static final int CONTENT_ID_COLUMN = 0;
        public static final int CONTENT_PROTOCOL_COLUMN = 1;
        public static final int CONTENT_ADDRESS_COLUMN = 2;
        public static final int CONTENT_PORT_COLUMN = 3;
        public static final int CONTENT_FLAGS_COLUMN = 4;
        public static final int CONTENT_LOGIN_COLUMN = 5;
        public static final int CONTENT_PASSWORD_COLUMN = 6;
        public static final int CONTENT_DOMAIN_COLUMN = 7;
        public static final int CONTENT_ACCOUNT_KEY_COLUMN = 8;
        
        public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID, HostAuthColumns.PROTOCOL, HostAuthColumns.ADDRESS, HostAuthColumns.PORT,
            HostAuthColumns.FLAGS, HostAuthColumns.LOGIN,
            HostAuthColumns.PASSWORD, HostAuthColumns.DOMAIN,
            HostAuthColumns.ACCOUNT_KEY
        };

        /**
         * no public constructor since this is a utility class
         */
        public HostAuth() {
            mBaseUri = CONTENT_URI;
            
            // other defaults policy)
            mPort = -1;
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

        @Override
        @SuppressWarnings("unchecked")
        public EmailContent.HostAuth restore(Cursor cursor) {
            mBaseUri = CONTENT_URI;
            mId = cursor.getLong(CONTENT_ID_COLUMN);
            mProtocol = cursor.getString(CONTENT_PROTOCOL_COLUMN);
            mAddress = cursor.getString(CONTENT_ADDRESS_COLUMN);
            mPort = cursor.getInt(CONTENT_PORT_COLUMN);
            mFlags = cursor.getInt(CONTENT_FLAGS_COLUMN);
            mLogin = cursor.getString(CONTENT_LOGIN_COLUMN);
            mPassword = cursor.getString(CONTENT_PASSWORD_COLUMN);
            mDomain = cursor.getString(CONTENT_DOMAIN_COLUMN);
            mAccountKey = cursor.getLong(CONTENT_ACCOUNT_KEY_COLUMN);
            return this;
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
            values.put(HostAuthColumns.ACCOUNT_KEY, mAccountKey);
            return values;
        }
        
        /**
         * For compatibility while converting to provider model, generate a "store URI"
         * TODO cache this so we don't rebuild every time
         * 
         * @return a string in the form of a Uri, as used by the other parts of the email app
         */
        public String getStoreUri() {
            String security = "";
            if ((mFlags & FLAG_SSL) != 0) {
                security = "+ssl+";
            } else if ((mFlags & FLAG_TLS) != 0) {
                security = "+tls+";
            }
            String userInfo = null;
            if ((mFlags & FLAG_AUTHENTICATE) != 0) {
                String trimUser = (mLogin != null) ? mLogin.trim() : "";
                String trimPassword = (mPassword != null) ? mPassword.trim() : "";
                userInfo = trimUser + ":" + trimPassword;
            }
            String address = (mAddress != null) ? mAddress.trim() : null;
            String path = (mDomain != null) ? "/" + mDomain : null;
            
            URI uri;
            try {
                uri = new URI(
                        mProtocol + security,
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
         * For compatibility while converting to provider model, set fields from a "store URI"
         * 
         * @param uriString a String containing a Uri
         */
        @Deprecated
        public void setStoreUri(String uriString) {
            try {
                URI uri = new URI(uriString);
                mLogin = null;
                mPassword = null;
                mFlags &= ~FLAG_AUTHENTICATE;
                if (uri.getUserInfo() != null) {
                    String[] userInfoParts = uri.getUserInfo().split(":", 2);
                    mLogin = userInfoParts[0];
                    mFlags |= FLAG_AUTHENTICATE;
                    if (userInfoParts.length > 1) {
                        mPassword = userInfoParts[1];
                    }
                }
                
                String[] schemeParts = uri.getScheme().split("\\+");
                mProtocol = (schemeParts.length >= 1) ? schemeParts[0] : null;
                boolean ssl = false;
                boolean tls = false;
                if (schemeParts.length >= 2) {
                    if ("ssl".equals(schemeParts[1])) {
                        ssl = true;
                    } else if ("tls".equals(schemeParts[1])) {
                        tls = true;
                    }
                }
                
                mFlags &= ~(FLAG_SSL | FLAG_TLS);
                if (ssl) {
                    mFlags |= FLAG_SSL;
                }
                if (tls) {
                    mFlags |= FLAG_TLS;
                }

                mAddress = uri.getHost();
                mPort = uri.getPort();
                if (mPort == -1) {
                    // infer port# from protocol + security
                    // SSL implies a different port - TLS runs in the "regular" port
                    if ("pop3".equals(mProtocol)) {
                        mPort = ssl ? 995 : 110;
                    } else if ("imap".equals(mProtocol)) {
                        mPort = ssl ? 993 : 143;
                    } else if ("eas".equals(mProtocol)) {
                        mPort = ssl ? 443 : 80;
                    }  else if ("smtp".equals(mProtocol)) {
                        mPort = ssl ? 465 : 25;
                    }
                }
                
                if (uri.getPath() != null && uri.getPath().length() > 0) {
                    mDomain = uri.getPath().substring(1);
                }


            } catch (URISyntaxException use) {
                /*
                 * We should always be able to parse our own settings.
                 */
                throw new Error(use);
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
            dest.writeLong(mAccountKey);
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
            mAccountKey = in.readLong();
        }

        /**
         * For debugger support only - DO NOT use for code.
         */
        @Override
        public String toString() {
            return getStoreUri();
        }
    }
}