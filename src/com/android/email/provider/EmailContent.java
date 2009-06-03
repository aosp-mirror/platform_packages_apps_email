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

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

public abstract class EmailContent {
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
        if (mUri == null)
            mUri = ContentUris.withAppendedId(mBaseUri, mId);
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
}
