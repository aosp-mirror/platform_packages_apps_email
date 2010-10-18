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

package com.android.exchange.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.Map.Entry;

/**
 * MockProvider is a ContentProvider that can be used to simulate the storage and retrieval of
 * records from any ContentProvider, even if that ContentProvider does not exist in the caller's
 * package.  It is specifically designed to enable testing of sync adapters that create
 * ContentProviderOperations (CPOs) that are then executed using ContentResolver.applyBatch()
 *
 * Why is this useful?  Because we can't instantiate CalendarProvider or ContactsProvider from our
 * package, as required by MockContentResolver.addProvider()
 *
 * Usage:
 *     ContentResolver.applyBatch(MockProvider.AUTHORITY, batch) will cause the CPOs to be executed,
 *     returning an array of ContentProviderResult; in the case of inserts, the result will include
 *     a Uri that can be used via query().  Note that the CPOs in the batch can contain references
 *     to any authority.
 *
 *     query() does not allow non-null selection, selectionArgs, or sortOrder arguments; the
 *     presence of these will result in an UnsupportedOperationException
 *
 *     insert() acts as expected, returning a Uri that can be directly used in a query
 *
 *     delete() and update() do not allow non-null selection or selectionArgs arguments; the
 *     presence of these will result in an UnsupportedOperationException
 *
 *     NOTE: When using any operation other than applyBatch, the Uri to be used must be created
 *     with MockProvider.uri(yourUri).  This guarantees that the operation is sent to MockProvider
 *
 *     NOTE: MockProvider only simulates direct storage/retrieval of rows; it does not (and can not)
 *     simulate other actions (e.g. creation of ancillary data) that the actual provider might
 *     perform
 *
 *     NOTE: See MockProviderTests for usage examples
 **/
public class MockProvider extends ContentProvider {
    public static final String AUTHORITY = "com.android.exchange.mock.provider";
    /*package*/ static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    /*package*/ static final int TABLE = 100;
    /*package*/ static final int RECORD = 101;

    public static final String ID_COLUMN = "_id";

    public MockProvider(Context context) {
        super(context, null, null, null);
    }

    public MockProvider() {
        super();
    }

    // We'll store our values here
    private HashMap<String, ContentValues> mMockStore = new HashMap<String, ContentValues>();
    // And we'll generate new id's from here
    long mMockId = 1;

    /**
     * Create a Uri for MockProvider from a given Uri
     * @param uri the Uri from which the MockProvider Uri will be created
     * @return a Uri that can be used with MockProvider
     */
    public static Uri uri(Uri uri) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY)
            .path(uri.getPath().substring(1)).build();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (selection != null || selectionArgs != null) {
            throw new UnsupportedOperationException();
        }
        String path = uri.getPath();
        if (mMockStore.containsKey(path)) {
            mMockStore.remove(path);
            return 1;
        } else {
            return 0;
        }
    }

    @Override
    public String getType(Uri uri) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        // Remove the leading slash
        String table = uri.getPath().substring(1);
        long id = mMockId++;
        Uri newUri = new Uri.Builder().scheme("content").authority(AUTHORITY).path(table)
            .appendPath(Long.toString(id)).build();
        // Remember to store the _id
        values.put(ID_COLUMN, id);
        mMockStore.put(newUri.getPath(), values);
        int match = sURIMatcher.match(uri);
        if (match == UriMatcher.NO_MATCH) {
            sURIMatcher.addURI(AUTHORITY, table, TABLE);
            sURIMatcher.addURI(AUTHORITY, table + "/#", RECORD);
        }
        return newUri;
    }

    @Override
    public boolean onCreate() {
        return false;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        if (selection != null || selectionArgs != null || sortOrder != null || projection == null) {
            throw new UnsupportedOperationException();
        }
        final int match = sURIMatcher.match(uri(uri));
        ArrayList<ContentValues> valuesList = new ArrayList<ContentValues>();
        switch(match) {
            case TABLE:
                Set<Entry<String, ContentValues>> entrySet = mMockStore.entrySet();
                String prefix = uri.getPath() + "/";
                for (Entry<String, ContentValues> entry: entrySet) {
                    if (entry.getKey().startsWith(prefix)) {
                        valuesList.add(entry.getValue());
                    }
                }
                break;
            case RECORD:
                ContentValues values = mMockStore.get(uri.getPath());
                if (values != null) {
                    valuesList.add(values);
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        MatrixCursor cursor = new MatrixCursor(projection, 1);
        for (ContentValues cv: valuesList) {
            Object[] rowValues = new Object[projection.length];
            int i = 0;
            for (String column: projection) {
                rowValues[i++] = cv.get(column);
            }
            cursor.addRow(rowValues);
        }
        return cursor;
    }

    @Override
    public int update(Uri uri, ContentValues newValues, String selection, String[] selectionArgs) {
        if (selection != null || selectionArgs != null) {
            throw new UnsupportedOperationException();
        }
        final int match = sURIMatcher.match(uri(uri));
        ArrayList<ContentValues> updateValuesList = new ArrayList<ContentValues>();
        String path = uri.getPath();
        switch(match) {
            case TABLE:
                Set<Entry<String, ContentValues>> entrySet = mMockStore.entrySet();
                String prefix = path + "/";
                for (Entry<String, ContentValues> entry: entrySet) {
                    if (entry.getKey().startsWith(prefix)) {
                        updateValuesList.add(entry.getValue());
                    }
                }
                break;
            case RECORD:
                ContentValues cv = mMockStore.get(path);
                if (cv != null) {
                    updateValuesList.add(cv);
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
        Set<Entry<String, Object>> newValuesSet = newValues.valueSet();
        for (Entry<String, Object> entry: newValuesSet) {
            String key = entry.getKey();
            Object value = entry.getValue();
            for (ContentValues targetValues: updateValuesList) {
                if (value instanceof Integer) {
                    targetValues.put(key, (Integer)value);
                } else if (value instanceof Long) {
                    targetValues.put(key, (Long)value);
                } else if (value instanceof String) {
                    targetValues.put(key, (String)value);
                } else if (value instanceof Boolean) {
                    targetValues.put(key, (Boolean)value);
                } else {
                    throw new IllegalArgumentException();
                }
            }
        }
        for (ContentValues targetValues: updateValuesList) {
            mMockStore.put(path + "/" + targetValues.getAsLong(ID_COLUMN), targetValues);
        }
        return updateValuesList.size();
    }
}
