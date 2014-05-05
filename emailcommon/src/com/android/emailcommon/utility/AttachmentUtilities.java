/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.emailcommon.utility;

import android.app.DownloadManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.AttachmentColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.mail.providers.UIProvider;
import com.android.mail.utils.LogUtils;

import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class AttachmentUtilities {

    public static final String FORMAT_RAW = "RAW";
    public static final String FORMAT_THUMBNAIL = "THUMBNAIL";

    public static class Columns {
        public static final String _ID = "_id";
        public static final String DATA = "_data";
        public static final String DISPLAY_NAME = "_display_name";
        public static final String SIZE = "_size";
    }

    private static final String[] ATTACHMENT_CACHED_FILE_PROJECTION = new String[] {
            AttachmentColumns.CACHED_FILE
    };

    /**
     * The MIME type(s) of attachments we're willing to send via attachments.
     *
     * Any attachments may be added via Intents with Intent.ACTION_SEND or ACTION_SEND_MULTIPLE.
     */
    public static final String[] ACCEPTABLE_ATTACHMENT_SEND_INTENT_TYPES = new String[] {
        "*/*",
    };
    /**
     * The MIME type(s) of attachments we're willing to send from the internal UI.
     *
     * NOTE:  At the moment it is not possible to open a chooser with a list of filter types, so
     * the chooser is only opened with the first item in the list.
     */
    public static final String[] ACCEPTABLE_ATTACHMENT_SEND_UI_TYPES = new String[] {
        "image/*",
        "video/*",
    };
    /**
     * The MIME type(s) of attachments we're willing to view.
     */
    public static final String[] ACCEPTABLE_ATTACHMENT_VIEW_TYPES = new String[] {
        "*/*",
    };
    /**
     * The MIME type(s) of attachments we're not willing to view.
     */
    public static final String[] UNACCEPTABLE_ATTACHMENT_VIEW_TYPES = new String[] {
    };
    /**
     * The MIME type(s) of attachments we're willing to download to SD.
     */
    public static final String[] ACCEPTABLE_ATTACHMENT_DOWNLOAD_TYPES = new String[] {
        "*/*",
    };
    /**
     * The MIME type(s) of attachments we're not willing to download to SD.
     */
    public static final String[] UNACCEPTABLE_ATTACHMENT_DOWNLOAD_TYPES = new String[] {
    };
    /**
     * Filename extensions of attachments we're never willing to download (potential malware).
     * Entries in this list are compared to the end of the lower-cased filename, so they must
     * be lower case, and should not include a "."
     */
    public static final String[] UNACCEPTABLE_ATTACHMENT_EXTENSIONS = new String[] {
        // File types that contain malware
        "ade", "adp", "bat", "chm", "cmd", "com", "cpl", "dll", "exe",
        "hta", "ins", "isp", "jse", "lib", "mde", "msc", "msp",
        "mst", "pif", "scr", "sct", "shb", "sys", "vb", "vbe",
        "vbs", "vxd", "wsc", "wsf", "wsh",
        // File types of common compression/container formats (again, to avoid malware)
        "zip", "gz", "z", "tar", "tgz", "bz2",
    };
    /**
     * Filename extensions of attachments that can be installed.
     * Entries in this list are compared to the end of the lower-cased filename, so they must
     * be lower case, and should not include a "."
     */
    public static final String[] INSTALLABLE_ATTACHMENT_EXTENSIONS = new String[] {
        "apk",
    };
    /**
     * The maximum size of an attachment we're willing to download (either View or Save)
     * Attachments that are base64 encoded (most) will be about 1.375x their actual size
     * so we should probably factor that in. A 5MB attachment will generally be around
     * 6.8MB downloaded but only 5MB saved.
     */
    public static final int MAX_ATTACHMENT_DOWNLOAD_SIZE = (5 * 1024 * 1024);
    /**
     * The maximum size of an attachment we're willing to upload (measured as stored on disk).
     * Attachments that are base64 encoded (most) will be about 1.375x their actual size
     * so we should probably factor that in. A 5MB attachment will generally be around
     * 6.8MB uploaded.
     */
    public static final int MAX_ATTACHMENT_UPLOAD_SIZE = (5 * 1024 * 1024);

    private static Uri sUri;
    public static Uri getAttachmentUri(long accountId, long id) {
        if (sUri == null) {
            sUri = Uri.parse(Attachment.ATTACHMENT_PROVIDER_URI_PREFIX);
        }
        return sUri.buildUpon()
                .appendPath(Long.toString(accountId))
                .appendPath(Long.toString(id))
                .appendPath(FORMAT_RAW)
                .build();
    }

    // exposed for testing
    public static Uri getAttachmentThumbnailUri(long accountId, long id, long width, long height) {
        if (sUri == null) {
            sUri = Uri.parse(Attachment.ATTACHMENT_PROVIDER_URI_PREFIX);
        }
        return sUri.buildUpon()
                .appendPath(Long.toString(accountId))
                .appendPath(Long.toString(id))
                .appendPath(FORMAT_THUMBNAIL)
                .appendPath(Long.toString(width))
                .appendPath(Long.toString(height))
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

    /**
     * Helper to convert unknown or unmapped attachments to something useful based on filename
     * extensions. The mime type is inferred based upon the table below. It's not perfect, but
     * it helps.
     *
     * <pre>
     *                   |---------------------------------------------------------|
     *                   |                  E X T E N S I O N                      |
     *                   |---------------------------------------------------------|
     *                   | .eml        | known(.png) | unknown(.abc) | none        |
     * | M |-----------------------------------------------------------------------|
     * | I | none        | msg/rfc822  | image/png   | app/abc       | app/oct-str |
     * | M |-------------| (always     |             |               |             |
     * | E | app/oct-str |  overrides  |             |               |             |
     * | T |-------------|             |             |-----------------------------|
     * | Y | text/plain  |             |             | text/plain                  |
     * | P |-------------|             |-------------------------------------------|
     * | E | any/type    |             | any/type                                  |
     * |---|-----------------------------------------------------------------------|
     * </pre>
     *
     * NOTE: Since mime types on Android are case-*sensitive*, return values are always in
     * lower case.
     *
     * @param fileName The given filename
     * @param mimeType The given mime type
     * @return A likely mime type for the attachment
     */
    public static String inferMimeType(final String fileName, final String mimeType) {
        String resultType = null;
        String fileExtension = getFilenameExtension(fileName);
        boolean isTextPlain = "text/plain".equalsIgnoreCase(mimeType);

        if ("eml".equals(fileExtension)) {
            resultType = "message/rfc822";
        } else {
            boolean isGenericType =
                    isTextPlain || "application/octet-stream".equalsIgnoreCase(mimeType);
            // If the given mime type is non-empty and non-generic, return it
            if (isGenericType || TextUtils.isEmpty(mimeType)) {
                if (!TextUtils.isEmpty(fileExtension)) {
                    // Otherwise, try to find a mime type based upon the file extension
                    resultType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileExtension);
                    if (TextUtils.isEmpty(resultType)) {
                        // Finally, if original mimetype is text/plain, use it; otherwise synthesize
                        resultType = isTextPlain ? mimeType : "application/" + fileExtension;
                    }
                }
            } else {
                resultType = mimeType;
            }
        }

        // No good guess could be made; use an appropriate generic type
        if (TextUtils.isEmpty(resultType)) {
            resultType = isTextPlain ? "text/plain" : "application/octet-stream";
        }
        return resultType.toLowerCase();
    }

    /**
     * Extract and return filename's extension, converted to lower case, and not including the "."
     *
     * @return extension, or null if not found (or null/empty filename)
     */
    public static String getFilenameExtension(String fileName) {
        String extension = null;
        if (!TextUtils.isEmpty(fileName)) {
            int lastDot = fileName.lastIndexOf('.');
            if ((lastDot > 0) && (lastDot < fileName.length() - 1)) {
                extension = fileName.substring(lastDot + 1).toLowerCase();
            }
        }
        return extension;
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
                new String[] { Columns.DATA },
                null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    final String strUri = c.getString(0);
                    if (strUri != null) {
                        return Uri.parse(strUri);
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
     * In support of deleting a message, find all attachments and delete associated cached
     * attachment files.
     * @param context
     * @param accountId the account for the message
     * @param messageId the message
     */
    public static void deleteAllCachedAttachmentFiles(Context context, long accountId,
            long messageId) {
        final Uri uri = ContentUris.withAppendedId(Attachment.MESSAGE_ID_URI, messageId);
        final Cursor c = context.getContentResolver().query(uri, ATTACHMENT_CACHED_FILE_PROJECTION,
                null, null, null);
        try {
            while (c.moveToNext()) {
                final String fileName = c.getString(0);
                if (!TextUtils.isEmpty(fileName)) {
                    final File cachedFile = new File(fileName);
                    // Note, delete() throws no exceptions for basic FS errors (e.g. file not found)
                    // it just returns false, which we ignore, and proceed to the next file.
                    // This entire loop is best-effort only.
                    cachedFile.delete();
                }
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

    /**
     * In support of deleting or wiping an account, delete all related attachments.
     *
     * @param context
     * @param accountId the account to scrub
     */
    public static void deleteAllAccountAttachmentFiles(Context context, long accountId) {
        File[] files = getAttachmentDirectory(context, accountId).listFiles();
        if (files == null) return;
        for (File file : files) {
            boolean result = file.delete();
            if (!result) {
                LogUtils.e(Logging.LOG_TAG, "Failed to delete attachment file " + file.getName());
            }
        }
    }

    private static long copyFile(InputStream in, OutputStream out) throws IOException {
        long size = IOUtils.copy(in, out);
        in.close();
        out.flush();
        out.close();
        return size;
    }

    /**
     * Save the attachment to its final resting place (cache or sd card)
     */
    public static void saveAttachment(Context context, InputStream in, Attachment attachment) {
        final Uri uri = ContentUris.withAppendedId(Attachment.CONTENT_URI, attachment.mId);
        final ContentValues cv = new ContentValues();
        final long attachmentId = attachment.mId;
        final long accountId = attachment.mAccountKey;
        final String contentUri;
        final long size;

        try {
            ContentResolver resolver = context.getContentResolver();
            if (attachment.mUiDestination == UIProvider.AttachmentDestination.CACHE) {
                Uri attUri = getAttachmentUri(accountId, attachmentId);
                size = copyFile(in, resolver.openOutputStream(attUri));
                contentUri = attUri.toString();
            } else if (Utility.isExternalStorageMounted()) {
                if (TextUtils.isEmpty(attachment.mFileName)) {
                    // TODO: This will prevent a crash but does not surface the underlying problem
                    // to the user correctly.
                    LogUtils.w(Logging.LOG_TAG, "Trying to save an attachment with no name: %d",
                            attachmentId);
                    throw new IOException("Can't save an attachment with no name");
                }
                File downloads = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                downloads.mkdirs();
                File file = Utility.createUniqueFile(downloads, attachment.mFileName);
                size = copyFile(in, new FileOutputStream(file));
                String absolutePath = file.getAbsolutePath();

                // Although the download manager can scan media files, scanning only happens
                // after the user clicks on the item in the Downloads app. So, we run the
                // attachment through the media scanner ourselves so it gets added to
                // gallery / music immediately.
                MediaScannerConnection.scanFile(context, new String[] {absolutePath},
                        null, null);

                final String mimeType = TextUtils.isEmpty(attachment.mMimeType) ?
                        "application/octet-stream" :
                        attachment.mMimeType;

                try {
                    DownloadManager dm =
                            (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
                    long id = dm.addCompletedDownload(attachment.mFileName, attachment.mFileName,
                            false /* do not use media scanner */,
                            mimeType, absolutePath, size,
                            true /* show notification */);
                    contentUri = dm.getUriForDownloadedFile(id).toString();
                } catch (final IllegalArgumentException e) {
                    LogUtils.d(LogUtils.TAG, e, "IAE from DownloadManager while saving attachment");
                    throw new IOException(e);
                }
            } else {
                LogUtils.w(Logging.LOG_TAG,
                        "Trying to save an attachment without external storage?");
                throw new IOException();
            }

            // Update the attachment
            cv.put(AttachmentColumns.SIZE, size);
            cv.put(AttachmentColumns.CONTENT_URI, contentUri);
            cv.put(AttachmentColumns.UI_STATE, UIProvider.AttachmentState.SAVED);
        } catch (IOException e) {
            // Handle failures here...
            cv.put(AttachmentColumns.UI_STATE, UIProvider.AttachmentState.FAILED);
        }
        context.getContentResolver().update(uri, cv, null, null);
    }
}
