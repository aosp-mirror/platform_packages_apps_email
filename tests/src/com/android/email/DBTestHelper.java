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

package com.android.email;

import com.android.email.provider.EmailProvider;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.mock.MockCursor;

/**
 * Helper classes (and possibly methods) for database related tests.
 */
public final class DBTestHelper {
    private DBTestHelper() { // Utility class.  No instantiation.
    }

    /**
     * A simple {@link Context} that returns {@link MyProvider} as the email content provider.
     */
    public static class MyContext extends MockContext {
        private final MockContentResolver mContentResolver;
        private final MyProvider mProvider;

        public MyContext() {
            mProvider = new MyProvider();
            mContentResolver = new MockContentResolver();
            mContentResolver.addProvider(EmailProvider.EMAIL_AUTHORITY, mProvider);
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }

        public MyProvider getMyProvider() {
            return mProvider;
        }
    }

    /**
     * A simply {@link ContentProvider} to mock out {@link ContentProvider#query}.
     */
    public static class MyProvider extends ContentProvider {
        public Cursor mQueryPresetResult;
        public Uri mPassedUri;
        public String[] mPassedProjection;
        public String mPassedSelection;
        public String[] mPassedSelectionArgs;
        public String mPassedSortOrder;

        public void reset() {
            mQueryPresetResult = null;
            mPassedUri = null;
            mPassedProjection = null;
            mPassedSelection = null;
            mPassedSelectionArgs = null;
            mPassedSortOrder = null;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                String sortOrder) {
            mPassedUri = uri;
            mPassedProjection = projection;
            mPassedSelection = selection;
            mPassedSelectionArgs = selectionArgs;
            mPassedSortOrder = sortOrder;
            return mQueryPresetResult;
        }

        @Override
        public int delete(Uri uri, String selection, String[] selectionArgs) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getType(Uri uri) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean onCreate() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Simple {@link MockCursor} subclass that implements common methods.
     */
    public static class EasyMockCursor extends MockCursor {
        public int mCount;
        public boolean mClosed;

        public EasyMockCursor(int count) {
            mCount = count;
        }

        @Override
        public int getCount() {
            return mCount;
        }

        @Override
        public void close() {
            mClosed = true;
        }
    }
}
