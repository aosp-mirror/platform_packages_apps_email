/*
 * Copyright (C) 2008 The Android Open Source Project
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

import com.android.email.Email;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.AttachmentColumns;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/*
 * A simple ContentProvider that allows file access to Email's attachments.
 * 
 * The URI scheme is as follows.  For raw file access:
 *   content://com.android.email.attachmentprovider/acct#/attach#/RAW
 * 
 * And for access to thumbnails:
 *   content://com.android.email.attachmentprovider/acct#/attach#/THUMBNAIL/width#/height#
 *
 * The on-disk (storage) schema is as follows.
 * 
 * Attachments are stored at:  <database-path>/account#.db_att/item#
 * Thumbnails are stored at:   <cache-path>/thmb_account#_item#
 * 
 * Using the standard application context, account #10 and attachment # 20, this would be:
 *      /data/data/com.android.email/databases/10.db_att/20
 *      /data/data/com.android.email/cache/thmb_10_20
 */
public class AttachmentProvider extends ContentProvider {

    public static final String AUTHORITY = "com.android.email.attachmentprovider";
    public static final Uri CONTENT_URI = Uri.parse( "content://" + AUTHORITY);

    private static final String FORMAT_RAW = "RAW";
    private static final String FORMAT_THUMBNAIL = "THUMBNAIL";

    public static class AttachmentProviderColumns {
        public static final String _ID = "_id";
        public static final String DATA = "_data";
        public static final String DISPLAY_NAME = "_display_name";
        public static final String SIZE = "_size";
    }

    private static final String[] MIME_TYPE_PROJECTION = new String[] {
            AttachmentColumns.MIME_TYPE, AttachmentColumns.FILENAME };
    private static final int MIME_TYPE_COLUMN_MIME_TYPE = 0;
    private static final int MIME_TYPE_COLUMN_FILENAME = 1;

    private static final String[] PROJECTION_QUERY = new String[] { AttachmentColumns.FILENAME,
            AttachmentColumns.SIZE, AttachmentColumns.CONTENT_URI };

    public static Uri getAttachmentUri(long accountId, long id) {
        return CONTENT_URI.buildUpon()
                .appendPath(Long.toString(accountId))
                .appendPath(Long.toString(id))
                .appendPath(FORMAT_RAW)
                .build();
    }

    public static Uri getAttachmentThumbnailUri(long accountId, long id,
            int width, int height) {
        return CONTENT_URI.buildUpon()
                .appendPath(Long.toString(accountId))
                .appendPath(Long.toString(id))
                .appendPath(FORMAT_THUMBNAIL)
                .appendPath(Integer.toString(width))
                .appendPath(Integer.toString(height))
                .build();
    }

    /**
     * Return the filename for a given attachment.  This should be used by any code that is
     * going to *write* attachments.
     *
     * This does not create or write the file, or even the directories.  It simply builds
     * the filename that should be used.
     */
    public static File getAttachmentFilename(Context context, long accountId, long attachmentId) {
        return new File(getAttachmentDirectory(context, accountId), Long.toString(attachmentId));
    }

    /**
     * Return the directory for a given attachment.  This should be used by any code that is
     * going to *write* attachments.
     *
     * This does not create or write the directory.  It simply builds the pathname that should be
     * used.
     */
    public static File getAttachmentDirectory(Context context, long accountId) {
        return context.getDatabasePath(accountId + ".db_att");
    }

    @Override
    public boolean onCreate() {
        /*
         * We use the cache dir as a temporary directory (since Android doesn't give us one) so
         * on startup we'll clean up any .tmp files from the last run.
         */
        File[] files = getContext().getCacheDir().listFiles();
        for (File file : files) {
            String filename = file.getName();
            if (filename.endsWith(".tmp") || filename.startsWith("thmb_")) {
                file.delete();
            }
        }
        return true;
    }

    /**
     * Returns the mime type for a given attachment.  There are three possible results:
     *  - If thumbnail Uri, always returns "image/png" (even if there's no attachment)
     *  - If the attachment does not exist, returns null
     *  - Returns the mime type of the attachment
     */
    @Override
    public String getType(Uri uri) {
        long callingId = Binder.clearCallingIdentity();
        try {
            List<String> segments = uri.getPathSegments();
            String id = segments.get(1);
            String format = segments.get(2);
            if (FORMAT_THUMBNAIL.equals(format)) {
                return "image/png";
            } else {
                uri = ContentUris.withAppendedId(Attachment.CONTENT_URI, Long.parseLong(id));
                Cursor c = getContext().getContentResolver().query(uri, MIME_TYPE_PROJECTION,
                        null, null, null);
                try {
                    if (c.moveToFirst()) {
                        String mimeType = c.getString(MIME_TYPE_COLUMN_MIME_TYPE);
                        String fileName = c.getString(MIME_TYPE_COLUMN_FILENAME);
                        mimeType = inferMimeType(fileName, mimeType);
                        return mimeType;
                    }
                } finally {
                    c.close();
                }
                return null;
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /**
     * Helper to convert unknown or unmapped attachments to something useful based on filename
     * extensions.  Imperfect, but helps.
     *
     * If the given mime type is non-empty and anything other than "application/octet-stream",
     * just return it.  (This is the most common case.)
     * If the filename has a recognizable extension and it converts to a mime type, return that.
     * If the filename has an unrecognized extension, return "application/extension"
     * Otherwise return "application/octet-stream".
     *
     * @param fileName The given filename
     * @param mimeType The given mime type
     * @return A likely mime type for the attachment
     */
    public static String inferMimeType(String fileName, String mimeType) {
        // If the given mime type appears to be non-empty and non-generic - return it
        if (!TextUtils.isEmpty(mimeType) &&
                !"application/octet-stream".equalsIgnoreCase(mimeType)) {
            return mimeType;
        }

        // Try to find an extension in the filename
        if (!TextUtils.isEmpty(fileName)) {
            int lastDot = fileName.lastIndexOf('.');
            String extension = null;
            if ((lastDot > 0) && (lastDot < fileName.length() - 1)) {
                extension = fileName.substring(lastDot + 1).toLowerCase();
            }
            if (!TextUtils.isEmpty(extension)) {
                // Extension found.  Look up mime type, or synthesize if none found.
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
                if (mimeType == null) {
                    mimeType = "application/" + extension;
                }
                return mimeType;
            }
        }

        // Fallback case - no good guess could be made.
        return "application/octet-stream";
    }

    /**
     * Open an attachment file.  There are two "modes" - "raw", which returns an actual file,
     * and "thumbnail", which attempts to generate a thumbnail image.
     * 
     * Thumbnails are cached for easy space recovery and cleanup.
     * 
     * TODO:  The thumbnail mode returns null for its failure cases, instead of throwing
     * FileNotFoundException, and should be fixed for consistency.
     * 
     *  @throws FileNotFoundException
     */
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        long callingId = Binder.clearCallingIdentity();
        try {
            List<String> segments = uri.getPathSegments();
            String accountId = segments.get(0);
            String id = segments.get(1);
            String format = segments.get(2);
            if (FORMAT_THUMBNAIL.equals(format)) {
                int width = Integer.parseInt(segments.get(3));
                int height = Integer.parseInt(segments.get(4));
                String filename = "thmb_" + accountId + "_" + id;
                File dir = getContext().getCacheDir();
                File file = new File(dir, filename);
                if (!file.exists()) {
                    Uri attachmentUri =
                        getAttachmentUri(Long.parseLong(accountId), Long.parseLong(id));
                    Cursor c = query(attachmentUri,
                            new String[] { AttachmentProviderColumns.DATA }, null, null, null);
                    if (c != null) {
                        try {
                            if (c.moveToFirst()) {
                                attachmentUri = Uri.parse(c.getString(0));
                            } else {
                                return null;
                            }
                        } finally {
                            c.close();
                        }
                    }
                    String type = getContext().getContentResolver().getType(attachmentUri);
                    try {
                        InputStream in =
                            getContext().getContentResolver().openInputStream(attachmentUri);
                        Bitmap thumbnail = createThumbnail(type, in);
                        if (thumbnail == null) {
                            return null;
                        }
                        thumbnail = Bitmap.createScaledBitmap(thumbnail, width, height, true);
                        FileOutputStream out = new FileOutputStream(file);
                        thumbnail.compress(Bitmap.CompressFormat.PNG, 100, out);
                        out.close();
                        in.close();
                    } catch (IOException ioe) {
                        Log.d(Email.LOG_TAG, "openFile/thumbnail failed with " + ioe.getMessage());
                        return null;
                    } catch (OutOfMemoryError oome) {
                        Log.d(Email.LOG_TAG, "openFile/thumbnail failed with " + oome.getMessage());
                        return null;
                    }
                }
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            else {
                return ParcelFileDescriptor.open(
                        new File(getContext().getDatabasePath(accountId + ".db_att"), id),
                        ParcelFileDescriptor.MODE_READ_ONLY);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public int delete(Uri uri, String arg1, String[] arg2) {
        return 0;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    /**
     * Returns a cursor based on the data in the attachments table, or null if the attachment
     * is not recorded in the table.
     * 
     * Supports REST Uri only, for a single row - selection, selection args, and sortOrder are
     * ignored (non-null values should probably throw an exception....)
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        long callingId = Binder.clearCallingIdentity();
        try {
            if (projection == null) {
                projection =
                    new String[] {
                        AttachmentProviderColumns._ID,
                        AttachmentProviderColumns.DATA,
                };
            }

            List<String> segments = uri.getPathSegments();
            String accountId = segments.get(0);
            String id = segments.get(1);
            String format = segments.get(2);
            String name = null;
            int size = -1;
            String contentUri = null;

            uri = ContentUris.withAppendedId(Attachment.CONTENT_URI, Long.parseLong(id));
            Cursor c = getContext().getContentResolver().query(uri, PROJECTION_QUERY,
                    null, null, null);
            try {
                if (c.moveToFirst()) {
                    name = c.getString(0);
                    size = c.getInt(1);
                    contentUri = c.getString(2);
                } else {
                    return null;
                }
            } finally {
                c.close();
            }

            MatrixCursor ret = new MatrixCursor(projection);
            Object[] values = new Object[projection.length];
            for (int i = 0, count = projection.length; i < count; i++) {
                String column = projection[i];
                if (AttachmentProviderColumns._ID.equals(column)) {
                    values[i] = id;
                }
                else if (AttachmentProviderColumns.DATA.equals(column)) {
                    values[i] = contentUri;
                }
                else if (AttachmentProviderColumns.DISPLAY_NAME.equals(column)) {
                    values[i] = name;
                }
                else if (AttachmentProviderColumns.SIZE.equals(column)) {
                    values[i] = size;
                }
            }
            ret.addRow(values);
            return ret;
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }

    private Bitmap createThumbnail(String type, InputStream data) {
        if(MimeUtility.mimeTypeMatches(type, "image/*")) {
            return createImageThumbnail(data);
        }
        return null;
    }

    private Bitmap createImageThumbnail(InputStream data) {
        try {
            Bitmap bitmap = BitmapFactory.decodeStream(data);
            return bitmap;
        } catch (OutOfMemoryError oome) {
            Log.d(Email.LOG_TAG, "createImageThumbnail failed with " + oome.getMessage());
            return null;
        } catch (Exception e) {
            Log.d(Email.LOG_TAG, "createImageThumbnail failed with " + e.getMessage());
            return null;
        }
    }

    /**
     * Resolve attachment id to content URI.  Returns the resolved content URI (from the attachment
     * DB) or, if not found, simply returns the incoming value.
     * 
     * @param attachmentUri
     * @return resolved content URI
     *
     * TODO:  Throws an SQLite exception on a missing DB file (e.g. unknown URI) instead of just
     * returning the incoming uri, as it should.
     */
    public static Uri resolveAttachmentIdToContentUri(ContentResolver resolver, Uri attachmentUri) {
        Cursor c = resolver.query(attachmentUri,
                new String[] { AttachmentProvider.AttachmentProviderColumns.DATA },
                null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    final String strUri = c.getString(0);
                    if (strUri != null) {
                        return Uri.parse(strUri);
                    } else {
                        Email.log("AttachmentProvider: attachment with null contentUri");
                    }
                }
            } finally {
                c.close();
            }
        }
        return attachmentUri;
    }

    /**
     * In support of deleting a message, find all attachments and delete associated attachment
     * files.
     * @param context
     * @param accountId the account for the message
     * @param messageId the message
     */
    public static void deleteAllAttachmentFiles(Context context, long accountId, long messageId) {
        Uri uri = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, messageId);
        Cursor c = context.getContentResolver().query(uri, Attachment.ID_PROJECTION,
                null, null, null);
        try {
            while (c.moveToNext()) {
                long attachmentId = c.getLong(Attachment.ID_PROJECTION_COLUMN);
                File attachmentFile = getAttachmentFilename(context, accountId, attachmentId);
                // Note, delete() throws no exceptions for basic FS errors (e.g. file not found)
                // it just returns false, which we ignore, and proceed to the next file.
                // This entire loop is best-effort only.
                attachmentFile.delete();
            }
        } finally {
            c.close();
        }
    }

    /**
     * In support of deleting a mailbox, find all messages and delete their attachments.
     *
     * @param context
     * @param accountId the account for the mailbox
     * @param mailboxId the mailbox for the messages
     */
    public static void deleteAllMailboxAttachmentFiles(Context context, long accountId,
            long mailboxId) {
        Cursor c = context.getContentResolver().query(Message.CONTENT_URI,
                Message.ID_COLUMN_PROJECTION, MessageColumns.MAILBOX_KEY + "=?",
                new String[] { Long.toString(mailboxId) }, null);
        try {
            while (c.moveToNext()) {
                long messageId = c.getLong(Message.ID_PROJECTION_COLUMN);
                deleteAllAttachmentFiles(context, accountId, messageId);
            }
        } finally {
            c.close();
        }
    }
}
