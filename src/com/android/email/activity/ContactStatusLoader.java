/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.email.activity;

import android.content.AsyncTaskLoader;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.StatusUpdates;

import com.android.emailcommon.Logging;
import com.android.emailcommon.utility.Utility;
import com.android.mail.utils.LogUtils;
import com.google.common.annotations.VisibleForTesting;

/**
 * Loader to load presence statuses and the contact photo.
 */
public class ContactStatusLoader extends AsyncTaskLoader<ContactStatusLoader.Result> {
    @VisibleForTesting
    static final int PRESENCE_UNKNOWN_RESOURCE_ID = android.R.drawable.presence_offline;

    /** email address -> photo id, presence */
    /* package */ static final String[] PROJECTION_PHOTO_ID_PRESENCE = new String[] {
            Contacts.PHOTO_ID,
            Contacts.CONTACT_PRESENCE
            };
    private static final int COLUMN_PHOTO_ID = 0;
    private static final int COLUMN_PRESENCE = 1;

    /** photo id -> photo data */
    /* package */ static final String[] PHOTO_PROJECTION = new String[] {
            Photo.PHOTO
            };
    private static final int PHOTO_COLUMN = 0;

    private final Context mContext;
    private final String mEmailAddress;

    /**
     * Class that encapsulates the result.
     */
    public static class Result {
        public static final Result UNKNOWN = new Result(null, PRESENCE_UNKNOWN_RESOURCE_ID, null);

        /** Contact photo.  Null if unknown */
        public final Bitmap mPhoto;

        /** Presence image resource ID.  Always has a valid value, even if unknown. */
        public final int mPresenceResId;

        /** URI for opening quick contact.  Null if unknown. */
        public final Uri mLookupUri;

        public Result(Bitmap photo, int presenceResId, Uri lookupUri) {
            mPhoto = photo;
            mPresenceResId = presenceResId;
            mLookupUri = lookupUri;
        }

        public boolean isUnknown() {
            return PRESENCE_UNKNOWN_RESOURCE_ID == mPresenceResId;
        }
    }

    public ContactStatusLoader(Context context, String emailAddress) {
        super(context);
        mContext = context;
        mEmailAddress = emailAddress;
    }

    @Override
    public Result loadInBackground() {
        return getContactInfo(mContext, mEmailAddress);
    }

    /**
     * Synchronously loads contact data.
     *
     * NOTE: DO NOT CALL THIS METHOD FROM THE UI THREAD (DATABASE ACCESS)
     */
    public static Result getContactInfo(Context context, String emailAddress) {
        // Load photo-id and presence status.
        Uri uri = Uri.withAppendedPath(Email.CONTENT_LOOKUP_URI, Uri.encode(emailAddress));
        Cursor c = context.getContentResolver().query(
                uri,
                PROJECTION_PHOTO_ID_PRESENCE, null, null, null);
        if (c == null) {
            return Result.UNKNOWN;
        }
        final long photoId;
        final int presenceStatus;
        try {
            if (!c.moveToFirst()) {
                return Result.UNKNOWN;
            }
            photoId = c.getLong(COLUMN_PHOTO_ID);
            presenceStatus = c.getInt(COLUMN_PRESENCE);
        } finally {
            c.close();
        }

        // Convert presence status into the res id.
        final int presenceStatusResId = StatusUpdates.getPresenceIconResourceId(presenceStatus);

        // load photo from photo-id.
        Bitmap photo = null;
        if (photoId != -1) {
            final byte[] photoData = Utility.getFirstRowBlob(context,
                    ContentUris.withAppendedId(Data.CONTENT_URI, photoId), PHOTO_PROJECTION,
                    null, null, null, PHOTO_COLUMN, null);
            if (photoData != null) {
                try {
                    photo = BitmapFactory.decodeByteArray(photoData, 0, photoData.length, null);
                } catch (OutOfMemoryError e) {
                    LogUtils.d(Logging.LOG_TAG, "Decoding bitmap failed with " + e.getMessage());
                }
            }
        }

        // Get lookup URI
        final Uri lookupUri = Data.getContactLookupUri(context.getContentResolver(), uri);
        return new Result(photo, presenceStatusResId, lookupUri);
    }

    @Override
    protected void onStartLoading() {
        cancelLoad();
        forceLoad();
    }

    @Override
    protected void onStopLoading() {
        cancelLoad();
    }

    @Override
    protected void onReset() {
        stopLoading();
    }
}
