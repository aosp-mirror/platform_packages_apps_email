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

package com.android.email.provider;

import com.android.email.provider.EmailStore.Attachment;
import com.android.email.provider.EmailStore.AttachmentColumns;
import com.android.email.provider.EmailStore.Mailbox;
import com.android.email.provider.EmailStore.MailboxColumns;
import com.android.email.provider.EmailStore.Message;
import com.android.email.provider.EmailStore.MessageColumns;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Config;
import android.util.Log;

/*
 * TODO
 * 
 * Add Email.Body class and support, now that this is stored separately
 * Handle deletion cascades (either w/ triggers or code)
 * 
 */
public class EmailProvider extends ContentProvider {

    private static final String TAG = "EmailProvider";

    static final String DATABASE_NAME = "EmailProvider.db";
    
    // In these early versions, updating the database version will cause all tables to be deleted
    // Obviously, we'll handle upgrades differently once things are a bit stable
    public static final int DATABASE_VERSION = 5;

    protected static final String EMAIL_AUTHORITY = "com.android.email.provider";

    private static final int ACCOUNT_BASE = 0;
    private static final int ACCOUNT = ACCOUNT_BASE;
    private static final int ACCOUNT_MAILBOXES = ACCOUNT_BASE + 1;
    private static final int ACCOUNT_ID = ACCOUNT_BASE + 2;
 
    private static final int MAILBOX_BASE = 0x1000;
    private static final int MAILBOX = MAILBOX_BASE;
    private static final int MAILBOX_MESSAGES = MAILBOX_BASE + 1;
    private static final int MAILBOX_ID = MAILBOX_BASE + 2;
    
    private static final int MESSAGE_BASE = 0x2000;
    private static final int MESSAGE = MESSAGE_BASE;
    private static final int MESSAGE_ATTACHMENTS = MESSAGE_BASE + 1;
    private static final int MESSAGE_ID = MESSAGE_BASE + 2;
    
    private static final int ATTACHMENT_BASE = 0x3000;
    private static final int ATTACHMENT = ATTACHMENT_BASE;
    private static final int ATTACHMENT_CONTENT = ATTACHMENT_BASE + 1;
    private static final int ATTACHMENT_ID = ATTACHMENT_BASE + 2;
    
     // TEMPORARY UNTIL ACCOUNT MANAGER CAN BE USED
    private static final int HOSTAUTH_BASE = 0x4000;
    private static final int HOSTAUTH = HOSTAUTH_BASE;
    private static final int HOSTAUTH_ID = HOSTAUTH_BASE + 1;
    
    private static final int BODY_BASE = 0x5000;
    private static final int BODY = BODY_BASE;
    private static final int BODY_ID = BODY_BASE + 1;
    private static final int BODY_HTML = BODY_BASE + 2;
    private static final int BODY_TEXT = BODY_BASE + 4;
   
    
    private static final int BASE_SHIFT = 12;  // 12 bits to the base type: 0, 0x1000, 0x2000, etc.
    
    private static final String[] TABLE_NAMES = {
        EmailStore.Account.TABLE_NAME,
        EmailStore.Mailbox.TABLE_NAME,
        EmailStore.Message.TABLE_NAME,
        EmailStore.Attachment.TABLE_NAME,
        EmailStore.HostAuth.TABLE_NAME
    };
 
    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        // Email URI matching table
        UriMatcher matcher = sURIMatcher;
        // All accounts
        matcher.addURI(EMAIL_AUTHORITY, "account", ACCOUNT); // IMPLEMENTED
        // A specific account
        // insert into this URI causes a mailbox to be added to the account 
        matcher.addURI(EMAIL_AUTHORITY, "account/#", ACCOUNT_ID);  // IMPLEMENTED
        // The mailboxes in a specific account
        matcher.addURI(EMAIL_AUTHORITY, "account/#/mailbox", ACCOUNT_MAILBOXES);
        // All mailboxes
        matcher.addURI(EMAIL_AUTHORITY, "mailbox", MAILBOX);  // IMPLEMENTED
        // A specific mailbox
        // insert into this URI causes a message to be added to the mailbox
        // ** NOTE For now, the accountKey must be set manually in the values!
        matcher.addURI(EMAIL_AUTHORITY, "mailbox/#", MAILBOX_ID);  // IMPLEMENTED
        // The messages in a specific mailbox
        matcher.addURI(EMAIL_AUTHORITY, "mailbox/#/message", MAILBOX_MESSAGES);
        // All messages
        matcher.addURI(EMAIL_AUTHORITY, "message", MESSAGE); // IMPLEMENTED
        // A specific message
        // insert into this URI causes an attachment to be added to the message 
        matcher.addURI(EMAIL_AUTHORITY, "message/#", MESSAGE_ID); // IMPLEMENTED
        // The attachments of a specific message
        matcher.addURI(EMAIL_AUTHORITY, "message/#/attachment", MESSAGE_ATTACHMENTS); // IMPLEMENTED
        // A specific attachment
        matcher.addURI(EMAIL_AUTHORITY, "attachment", ATTACHMENT); // IMPLEMENTED
        // A specific attachment (the header information)
        matcher.addURI(EMAIL_AUTHORITY, "attachment/#", ATTACHMENT_ID);  // IMPLEMENTED
        // The content for a specific attachment
        matcher.addURI(EMAIL_AUTHORITY, "attachment/content/*", ATTACHMENT_CONTENT);

        // All mail bodies
        matcher.addURI(EMAIL_AUTHORITY, "body", BODY);
        // A specific mail body
        matcher.addURI(EMAIL_AUTHORITY, "body/#", BODY_ID);
        // The HTML part of a specific mail body
        matcher.addURI(EMAIL_AUTHORITY, "body/#/html", BODY_HTML);
        // The plain text part of a specific mail body
        matcher.addURI(EMAIL_AUTHORITY, "body/#/text", BODY_TEXT);
        
        // A specific attachment
        matcher.addURI(EMAIL_AUTHORITY, "hostauth", HOSTAUTH); // IMPLEMENTED
        // A specific attachment (the header information)
        matcher.addURI(EMAIL_AUTHORITY, "hostauth/#", HOSTAUTH_ID);  // IMPLEMENTED
   
    }

    private final int mDatabaseVersion = DATABASE_VERSION;
    
    private SQLiteDatabase mDatabase;
    
    public SQLiteDatabase getDatabase(Context context) {
        if (mDatabase !=  null)
            return mDatabase;
        DatabaseHelper helper = new DatabaseHelper(context, DATABASE_NAME);
        mDatabase = helper.getWritableDatabase();
        if (mDatabase != null)
            mDatabase.setLockingEnabled(true);
        return mDatabase;
    }


    
    private class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context, String name) {
             super(context, name, null, mDatabaseVersion);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Create all tables here; each class has its own method
            EmailStore.Message.createTable(db);
            EmailStore.Attachment.createTable(db);
            EmailStore.Mailbox.createTable(db);
            EmailStore.HostAuth.createTable(db);
            EmailStore.Account.createTable(db);
       }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            EmailStore.Message.upgradeTable(db, oldVersion, newVersion);
            EmailStore.Attachment.upgradeTable(db, oldVersion, newVersion);
            EmailStore.Mailbox.upgradeTable(db, oldVersion, newVersion);
            EmailStore.HostAuth.upgradeTable(db, oldVersion, newVersion);
            EmailStore.Account.upgradeTable(db, oldVersion, newVersion);
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        SQLiteDatabase db = getDatabase(getContext());
        int match = sURIMatcher.match(uri);
        int table = match >> BASE_SHIFT;

        if (Config.LOGV) 
            Log.v(TAG, "EmailProvider.delete: uri=" + uri + ", match is " + match);

        switch (match) {
        case MESSAGE_ID:
        case ATTACHMENT_ID:
        case MAILBOX_ID:
        case ACCOUNT_ID:
        case HOSTAUTH_ID:
            String id = uri.getPathSegments().get(1);
            return db.delete(TABLE_NAMES[table], whereWithId(id, selection), selectionArgs);
        case MESSAGE:
        case ATTACHMENT:
        case MAILBOX:
        case ACCOUNT:
        case HOSTAUTH:
            return db.delete(TABLE_NAMES[table], selection, selectionArgs);
        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    // Use the email- prefix because message, mailbox, and account are so generic (e.g. SMS, IM)
    public String getType(Uri uri) {
        int match = sURIMatcher.match(uri);
        switch (match) {
        case MESSAGE_ID:
            return "vnd.android.cursor.dir/email-message";
        case MAILBOX_MESSAGES:
        case MESSAGE:
            return "vnd.android.cursor.item/email-message";
        case ACCOUNT_MAILBOXES:
        case MAILBOX:
            return "vnd.android.cursor.dir/email-mailbox";
        case MAILBOX_ID:
            return "vnd.android.cursor.item/email-mailbox";
        case ACCOUNT:
            return "vnd.android.cursor.dir/email-account";
        case ACCOUNT_ID:
            return "vnd.android.cursor.item/email-account";
        case MESSAGE_ATTACHMENTS:
        case ATTACHMENT:
            return "vnd.android.cursor.dir/email-attachment";
        case ATTACHMENT_ID:
            return "vnd.android.cursor.item/email-attachment";
        case HOSTAUTH:
            return "vnd.android.cursor.dir/email-hostauth";
        case HOSTAUTH_ID:
            return "vnd.android.cursor.item/email-hostauth";
        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        SQLiteDatabase db = getDatabase(getContext());
        long id;
        int match = sURIMatcher.match(uri);
        int table = match >> BASE_SHIFT;
        
        if (Config.LOGV) 
            Log.v(TAG, "EmailProvider.insert: uri=" + uri + ", match is " + match);

        switch (match) {
        case MESSAGE:
        case ATTACHMENT:
        case MAILBOX:
        case ACCOUNT:
        case HOSTAUTH:
            id = db.insert(TABLE_NAMES[table], "foo", values);
            return ContentUris.withAppendedId(uri, id);
        case MAILBOX_ID:
            // This implies adding a message to a mailbox
            // Hmm, one problem here is that we can't link the account as well, so it must be already in the values...
            id = Long.parseLong(uri.getPathSegments().get(1));
            values.put(MessageColumns.MAILBOX_KEY, id);
            return insert(Message.CONTENT_URI, values);
        case MESSAGE_ID:
            // This implies adding an attachment to a message.
            id = Long.parseLong(uri.getPathSegments().get(1));
            values.put(AttachmentColumns.MESSAGE_KEY, id);
            return insert(Attachment.CONTENT_URI, values);
        case ACCOUNT_ID:
            // This implies adding a mailbox to an account.
            id = Long.parseLong(uri.getPathSegments().get(1));
            values.put(MailboxColumns.ACCOUNT_KEY, id);
            return insert(Mailbox.CONTENT_URI, values);
        case MESSAGE_ATTACHMENTS:
            id = db.insert(TABLE_NAMES[table], "foo", values);
            return ContentUris.withAppendedId(EmailStore.Attachment.CONTENT_URI, id);
        default:
            throw new IllegalArgumentException("Unknown URL " + uri);
        }
    }

    @Override
    public boolean onCreate() {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        SQLiteDatabase db = getDatabase(getContext());
        Cursor c = null;
        Uri notificationUri = EmailStore.CONTENT_URI;
        int match = sURIMatcher.match(uri);
        int table = match >> BASE_SHIFT;
        String id;
        
        if (Config.LOGV) 
            Log.v(TAG, "EmailProvider.query: uri=" + uri + ", match is " + match);

        switch (match) {
        case MESSAGE:
        case ATTACHMENT:
        case MAILBOX:
        case ACCOUNT:
        case HOSTAUTH:
            c = db.query(TABLE_NAMES[table], projection, selection, selectionArgs, null, null, sortOrder);
            break;
        case MESSAGE_ID:
        case ATTACHMENT_ID:
        case MAILBOX_ID:
        case ACCOUNT_ID:
        case HOSTAUTH_ID:
            id = uri.getPathSegments().get(1);
            c = db.query(TABLE_NAMES[table], projection, whereWithId(id, selection), selectionArgs, null, null, sortOrder);
            break;
        case MESSAGE_ATTACHMENTS:
            // All attachments for the given message
            id = uri.getPathSegments().get(1);
            c = db.query(Attachment.TABLE_NAME, projection, whereWith(Attachment.MESSAGE_KEY + "=" + id, selection), selectionArgs, null, null, sortOrder);
            break;
        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        if ((c != null) && !isTemporary()) {
            c.setNotificationUri(getContext().getContentResolver(), notificationUri);
        }
        return c;
    }

    private String whereWithId(String id, String selection) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("_id=");
        sb.append(id);
        if (selection != null) {
            sb.append(" AND ");
            sb.append(selection);
        }
        return sb.toString();
    }
    
    private String whereWith(String where, String selection) {
        StringBuilder sb = new StringBuilder(where);
        if (selection != null) {
            sb.append(" AND ");
            sb.append(selection);
        }
        return sb.toString();
    }
    
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        SQLiteDatabase db = getDatabase(getContext());
        int match = sURIMatcher.match(uri);
        int table = match >> BASE_SHIFT;
        if (Config.LOGV) 
            Log.v(TAG, "EmailProvider.update: uri=" + uri + ", match is " + match);

        switch (match) {
        case MESSAGE_ID:
        case ATTACHMENT_ID:
        case MAILBOX_ID:
        case ACCOUNT_ID:
            String id = uri.getPathSegments().get(1);
            return db.update(TABLE_NAMES[table], values, whereWithId(id, selection), selectionArgs);
        case MESSAGE:
        case ATTACHMENT:
        case MAILBOX:
        case ACCOUNT:
            return db.update(TABLE_NAMES[table], values, selection, selectionArgs);
        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }
}
