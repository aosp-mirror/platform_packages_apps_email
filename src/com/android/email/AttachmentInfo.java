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
import com.android.emailcommon.utility.AttachmentUtilities;
import com.android.emailcommon.utility.Utility;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.List;

/**
 * Encapsulates commonly used attachment information related to suitability for viewing and saving,
 * based on the attachment's filename and mime type.
 */
public class AttachmentInfo {
    // Projection which can be used with the constructor taking a Cursor argument
    public static final String[] PROJECTION = new String[] {Attachment.RECORD_ID, Attachment.SIZE,
        Attachment.FILENAME, Attachment.MIME_TYPE, Attachment.ACCOUNT_KEY};
    // Offsets into PROJECTION
    public static final int COLUMN_ID = 0;
    public static final int COLUMN_SIZE = 1;
    public static final int COLUMN_FILENAME = 2;
    public static final int COLUMN_MIME_TYPE = 3;
    public static final int COLUMN_ACCOUNT_KEY = 4;

    public final long mId;
    public final long mSize;
    public final String mName;
    public final String mContentType;
    public final long mAccountKey;
    public final boolean mAllowView;
    public final boolean mAllowSave;

    public AttachmentInfo(Context context, Attachment attachment) {
        this(context, attachment.mId, attachment.mSize, attachment.mFileName, attachment.mMimeType,
                attachment.mAccountKey);
    }

    public AttachmentInfo(Context context, Cursor c) {
        this(context, c.getLong(COLUMN_ID), c.getLong(COLUMN_SIZE), c.getString(COLUMN_FILENAME),
                c.getString(COLUMN_MIME_TYPE), c.getLong(COLUMN_ACCOUNT_KEY));
    }

    public AttachmentInfo(Context context, long id, long size, String fileName, String mimeType,
            long accountKey) {
        mSize = size;
        mContentType = AttachmentUtilities.inferMimeType(fileName, mimeType);
        mName = fileName;
        mId = id;
        mAccountKey = accountKey;
        boolean canView = true;
        boolean canSave = true;

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
        }

        // Check for installable attachments by filename extension
        extension = AttachmentUtilities.getFilenameExtension(mName);
        if (!TextUtils.isEmpty(extension) &&
                Utility.arrayContains(AttachmentUtilities.INSTALLABLE_ATTACHMENT_EXTENSIONS,
                        extension)) {
            int sideloadEnabled;
            sideloadEnabled = Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.INSTALL_NON_MARKET_APPS, 0 /* sideload disabled */);
            canView = false;
            canSave &= (sideloadEnabled == 1);
        }

        // Check for file size exceeded
        // The size limit is overridden when on a wifi connection - any size is OK
        if (mSize > AttachmentUtilities.MAX_ATTACHMENT_DOWNLOAD_SIZE) {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo network = cm.getActiveNetworkInfo();
            if (network == null || network.getType() != ConnectivityManager.TYPE_WIFI) {
                canView = false;
                canSave = false;
            }
        }

        // Check to see if any activities can view this attachment; if none, we can't view it
        Intent intent = getAttachmentIntent(context, 0);
        PackageManager pm = context.getPackageManager();
        List<ResolveInfo> activityList = pm.queryIntentActivities(intent, 0 /*no account*/);
        if (activityList.isEmpty()) {
            canView = false;
            canSave = false;
        }

        mAllowView = canView;
        mAllowSave = canSave;
    }

    /**
     * Returns an <code>Intent</code> to load the given attachment.
     * @param context the caller's context
     * @param accountId the account associated with the attachment (or 0 if we don't need to
     * resolve from attachmentUri to contentUri)
     * @return an Intent suitable for loading the attachment
     */
    public Intent getAttachmentIntent(Context context, long accountId) {
        Uri contentUri = AttachmentUtilities.getAttachmentUri(accountId, mId);
        if (accountId > 0) {
            contentUri = AttachmentUtilities.resolveAttachmentIdToContentUri(
                    context.getContentResolver(), contentUri);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(contentUri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
        return intent;
    }

    /**
     * An attachment is eligible for download if it can either be viewed or saved (or both)
     * @return whether the attachment is eligible for download
     */
    public boolean isEligibleForDownload() {
        return mAllowView || mAllowSave;
    }

    @Override
    public String toString() {
        return "{Attachment " + mId + ":" + mName + "," + mContentType + "," + mSize + "}";
    }
}
