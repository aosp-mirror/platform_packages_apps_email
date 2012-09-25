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


package com.android.emailcommon.provider;

import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.QuickResponseColumns;
import com.google.common.base.Objects;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A user-modifiable message that may be quickly inserted into the body while user is composing
 * a message. Tied to a specific account.
 */
public final class QuickResponse extends EmailContent
        implements QuickResponseColumns, Parcelable {
    public static final String TABLE_NAME = "QuickResponse";
    @SuppressWarnings("hiding")
    public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI
            + "/quickresponse");
    public static final Uri ACCOUNT_ID_URI = Uri.parse(
            EmailContent.CONTENT_URI + "/quickresponse/account");

    private String mText;
    private long mAccountKey;

    private static final int CONTENT_ID_COLUMN = 0;
    private static final int CONTENT_QUICK_RESPONSE_COLUMN = 1;
    private static final int CONTENT_ACCOUNT_KEY_COLUMN = 2;
    public static final String[] CONTENT_PROJECTION = new String[] {
            RECORD_ID,
            QuickResponseColumns.TEXT,
            QuickResponseColumns.ACCOUNT_KEY
    };

    /**
     * Creates an empty QuickResponse. Restore should be called after.
     */
    private QuickResponse() {
        // empty
    }

    /**
     * Constructor used by CREATOR for parceling.
     */
    private QuickResponse(Parcel in) {
        mBaseUri = CONTENT_URI;
        mId = in.readLong();
        mText = in.readString();
        mAccountKey = in.readLong();
    }

    /**
     * Creates QuickResponse associated with a particular account using the given string.
     */
    public QuickResponse(long accountKey, String quickResponse) {
        mBaseUri = CONTENT_URI;
        mAccountKey = accountKey;
        mText = quickResponse;
    }

    /**
     * @see com.android.emailcommon.provider.EmailContent#restore(android.database.Cursor)
     */
    @Override
    public void restore(Cursor cursor) {
        mBaseUri = CONTENT_URI;
        mId = cursor.getLong(CONTENT_ID_COLUMN);
        mText = cursor.getString(CONTENT_QUICK_RESPONSE_COLUMN);
        mAccountKey = cursor.getLong(CONTENT_ACCOUNT_KEY_COLUMN);
    }

    /**
     * @see com.android.emailcommon.provider.EmailContent#toContentValues()
     */
    @Override
    public ContentValues toContentValues() {
        ContentValues values = new ContentValues();

        values.put(QuickResponseColumns.TEXT, mText);
        values.put(QuickResponseColumns.ACCOUNT_KEY, mAccountKey);

        return values;
    }

    @Override
    public String toString() {
        return mText;
    }

    /**
     * Given an array of QuickResponses, returns the an array of the String values
     * corresponding to each QuickResponse.
     */
    public static String[] getQuickResponseStrings(QuickResponse[] quickResponses) {
        int count = quickResponses.length;
        String[] quickResponseStrings = new String[count];
        for (int i = 0; i < count; i++) {
            quickResponseStrings[i] = quickResponses[i].toString();
        }

        return quickResponseStrings;
    }

    /**
     * @param context
     * @param accountId
     * @return array of QuickResponses for the account with id accountId
     */
    public static QuickResponse[] restoreQuickResponsesWithAccountId(Context context,
            long accountId) {
        Uri uri = ContentUris.withAppendedId(ACCOUNT_ID_URI, accountId);
        Cursor c = context.getContentResolver().query(uri, CONTENT_PROJECTION,
                null, null, null);

        try {
            int count = c.getCount();
            QuickResponse[] quickResponses = new QuickResponse[count];
            for (int i = 0; i < count; ++i) {
                c.moveToNext();
                QuickResponse quickResponse = new QuickResponse();
                quickResponse.restore(c);
                quickResponses[i] = quickResponse;
            }
            return quickResponses;
        } finally {
            c.close();
        }
    }

    /**
     * Returns the base URI for this QuickResponse
     */
    public Uri getBaseUri() {
        return mBaseUri;
    }

    /**
     * Returns the unique id for this QuickResponse
     */
    public long getId() {
        return mId;
    }

    @Override
    public boolean equals(Object objectThat) {
        if (this == objectThat) return true;
        if (!(objectThat instanceof QuickResponse)) return false;

        QuickResponse that = (QuickResponse) objectThat;
        return
            mText.equals(that.mText) &&
            mId == that.mId &&
            mAccountKey == that.mAccountKey;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mId, mText, mAccountKey);
    }

    /**
     * Implements Parcelable. Not used.
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Implements Parcelable.
     */
    public void writeToParcel(Parcel dest, int flags) {
        // mBaseUri is not parceled
        dest.writeLong(mId);
        dest.writeString(mText);
        dest.writeLong(mAccountKey);
    }

    /**
     * Implements Parcelable
     */
    public static final Parcelable.Creator<QuickResponse> CREATOR
            = new Parcelable.Creator<QuickResponse>() {
        @Override
        public QuickResponse createFromParcel(Parcel in) {
            return new QuickResponse(in);
        }

        @Override
        public QuickResponse[] newArray(int size) {
            return new QuickResponse[size];
        }
    };

}