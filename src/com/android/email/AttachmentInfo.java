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

package com.android.email;

import com.android.emailcommon.internet.MimeUtility;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.AttachmentColumns;
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.emailcommon.utility.Utility;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.List;

/**
 * Encapsulates commonly used attachment information related to suitability for viewing and saving,
 * based on the attachment's filename and mimetype.
 */
public class AttachmentInfo {
    // Projection which can be used with the constructor taking a Cursor argument
    public static final String[] PROJECTION = {
            AttachmentColumns._ID,
            AttachmentColumns.SIZE,
            AttachmentColumns.FILENAME,
            AttachmentColumns.MIME_TYPE,
            AttachmentColumns.ACCOUNT_KEY,
            AttachmentColumns.FLAGS
    };
    // Offsets into PROJECTION
    public static final int COLUMN_ID = 0;
    public static final int COLUMN_SIZE = 1;
    public static final int COLUMN_FILENAME = 2;
    public static final int COLUMN_MIME_TYPE = 3;
    public static final int COLUMN_ACCOUNT_KEY = 4;
    public static final int COLUMN_FLAGS = 5;

    /** Attachment not denied */
    public static final int ALLOW           = 0x00;
    /** Attachment suspected of being malware */
    public static final int DENY_MALWARE    = 0x01;
    /** Attachment too large; must download over wi-fi */
    public static final int DENY_WIFIONLY   = 0x02;
    /** No receiving intent to handle attachment type */
    public static final int DENY_NOINTENT   = 0x04;
    /** Side load of applications is disabled */
    public static final int DENY_NOSIDELOAD = 0x08;
    // TODO Remove DENY_APKINSTALL when we can install directly from the Email activity
    /** Unable to install any APK */
    public static final int DENY_APKINSTALL = 0x10;
    /** Security policy prohibits install */
    public static final int DENY_POLICY = 0x20;

    public final long mId;
    public final long mSize;
    public final String mName;
    public final String mContentType;
    public final long mAccountKey;
    public final int mFlags;

    /** Whether or not this attachment can be viewed */
    public final boolean mAllowView;
    /** Whether or not this attachment can be saved */
    public final boolean mAllowSave;
    /** Whether or not this attachment can be installed [only true for APKs] */
    public final boolean mAllowInstall;
    /** Reason(s) why this attachment is denied from being viewed */
    public final int mDenyFlags;

    public AttachmentInfo(Context context, Attachment attachment) {
        this(context, attachment.mId, attachment.mSize, attachment.mFileName, attachment.mMimeType,
                attachment.mAccountKey, attachment.mFlags);
    }

    public AttachmentInfo(Context context, Cursor c) {
        this(context, c.getLong(COLUMN_ID), c.getLong(COLUMN_SIZE), c.getString(COLUMN_FILENAME),
                c.getString(COLUMN_MIME_TYPE), c.getLong(COLUMN_ACCOUNT_KEY),
                c.getInt(COLUMN_FLAGS));
    }

    public AttachmentInfo(Context context, AttachmentInfo info) {
        this(context, info.mId, info.mSize, info.mName, info.mContentType, info.mAccountKey,
                info.mFlags);
    }

    public AttachmentInfo(Context context, long id, long size, String fileName, String mimeType,
            long accountKey, int flags) {
        mSize = size;
        mContentType = AttachmentUtilities.inferMimeType(fileName, mimeType);
        mName = fileName;
        mId = id;
        mAccountKey = accountKey;
        mFlags = flags;
        boolean canView = true;
        boolean canSave = true;
        boolean canInstall = false;
        int denyFlags = ALLOW;

        // Don't enable the "save" button if we've got no place to save the file
        if (!Utility.isExternalStorageMounted()) {
            canSave = false;
        }

        // Check for acceptable / unacceptable attachments by MIME-type
        if ((!MimeUtility.mimeTypeMatches(mContentType,
                AttachmentUtilities.ACCEPTABLE_ATTACHMENT_VIEW_TYPES)) ||
            (MimeUtility.mimeTypeMatches(mContentType,
                    AttachmentUtilities.UNACCEPTABLE_ATTACHMENT_VIEW_TYPES))) {
            canView = false;
        }

        // Check for unacceptable attachments by filename extension
        String extension = AttachmentUtilities.getFilenameExtension(mName);
        if (!TextUtils.isEmpty(extension) &&
                Utility.arrayContains(AttachmentUtilities.UNACCEPTABLE_ATTACHMENT_EXTENSIONS,
                        extension)) {
            canView = false;
            canSave = false;
            denyFlags |= DENY_MALWARE;
        }

        // Check for policy restrictions on download
        if ((flags & Attachment.FLAG_POLICY_DISALLOWS_DOWNLOAD) != 0) {
            canView = false;
            canSave = false;
            denyFlags |= DENY_POLICY;
        }

        // Check for installable attachments by filename extension
        extension = AttachmentUtilities.getFilenameExtension(mName);
        if (!TextUtils.isEmpty(extension) &&
                Utility.arrayContains(AttachmentUtilities.INSTALLABLE_ATTACHMENT_EXTENSIONS,
                        extension)) {
            boolean sideloadEnabled;
            sideloadEnabled = Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.INSTALL_NON_MARKET_APPS, 0 /* sideload disabled */) == 1;
            canSave &= sideloadEnabled;
            canView = canSave;
            canInstall = canSave;
            if (!sideloadEnabled) {
                denyFlags |= DENY_NOSIDELOAD;
            }
        }

        // Check for file size exceeded
        // The size limit is overridden when on a wifi connection - any size is OK
        if (mSize > AttachmentUtilities.MAX_ATTACHMENT_DOWNLOAD_SIZE) {
            int networkType = EmailConnectivityManager.getActiveNetworkType(context);
            if (networkType != ConnectivityManager.TYPE_WIFI) {
                canView = false;
                canSave = false;
                denyFlags |= DENY_WIFIONLY;
            }
        }

        // Check to see if any activities can view this attachment; if none, we can't view it
        Intent intent = getAttachmentIntent(context, 0);
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> activityList = pm.queryIntentActivities(intent, 0 /*no account*/);
        if (activityList.isEmpty()) {
            canView = false;
            canSave = false;
            denyFlags |= DENY_NOINTENT;
        }

        mAllowView = canView;
        mAllowSave = canSave;
        mAllowInstall = canInstall;
        mDenyFlags = denyFlags;
    }

    /**
     * Returns an <code>Intent</code> to load the given attachment.
     * @param context the caller's context
     * @param accountId the account associated with the attachment (or 0 if we don't need to
     *     resolve from attachmentUri to contentUri)
     * @return an Intent suitable for viewing the attachment
     */
    public Intent getAttachmentIntent(Context context, long accountId) {
        Uri contentUri = getUriForIntent(context, accountId);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(contentUri, mContentType);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        return intent;
    }

    protected Uri getUriForIntent(Context context, long accountId) {
        Uri contentUri = AttachmentUtilities.getAttachmentUri(accountId, mId);
        if (accountId > 0) {
            contentUri = AttachmentUtilities.resolveAttachmentIdToContentUri(
                    context.getContentResolver(), contentUri);
        }

        return contentUri;
    }

    /**
     * An attachment is eligible for download if it can either be viewed or saved (or both)
     * @return whether the attachment is eligible for download
     */
    public boolean isEligibleForDownload() {
        return mAllowView || mAllowSave;
    }

    @Override
    public int hashCode() {
        return (int) (mId ^ (mId >>> 32));
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }

        if ((o == null) || (o.getClass() != getClass())) {
            return false;
        }

        return ((AttachmentInfo) o).mId == mId;
    }

    @Override
    public String toString() {
        return "{Attachment " + mId + ":" + mName + "," + mContentType + "," + mSize + "}";
    }
}
