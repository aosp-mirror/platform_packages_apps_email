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

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.database.Cursor;
import android.net.Uri;
import android.test.IsolatedContext;
import android.test.RenamingDelegatingContext;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.mock.MockCursor;

import com.android.email.provider.AttachmentProvider;
import com.android.email.provider.ContentCache;
import com.android.email.provider.EmailProvider;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;

import java.io.File;

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
            mContentResolver.addProvider(EmailContent.AUTHORITY, mProvider);
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

    /**
     * This class has only one method, that creats an isolated {@link Context} similar to what
     * {@link android.test.ProviderTestCase2} provides.
     *
     * The method also creates a {@link ContentProvider} of {@code providerClass}, and add it to
     * the context.  See the javadoc on android.test.ProviderTestCase2 for the details.
     */
    public static class ProviderContextSetupHelper {
        // Based on ProviderTestCase2.MockContext2.
        private static class MockContext2 extends MockContext {
            private final Context mBaseContext;

            public MockContext2(Context baseContext) {
                mBaseContext = baseContext;
            }

            @Override
            public Resources getResources() {
                return mBaseContext.getResources();
            }

            @Override
            public File getDir(String name, int mode) {
                return mBaseContext.getDir("mockcontext2_" + name, mode);
            }
        }

        /** {@link IsolatedContext} + getApplicationContext() */
        private static class MyIsolatedContext extends IsolatedContext {
            private final Context mRealContext;

            public MyIsolatedContext(ContentResolver resolver, Context targetContext,
                    Context realContext) {
                super(resolver, targetContext);
                mRealContext = realContext;
            }

            @Override
            public Context getApplicationContext() {
                return this;
            }

            // Following methods are not supported by the mock context.
            // Redirect to the actual context.
            @Override
            public String getPackageName() {
                return mRealContext.getPackageName();
            }

            @Override
            public Theme getTheme() {
                return mRealContext.getTheme();
            }

            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return new MockSharedPreferences();
            }

            @Override
            public Object getSystemService(String name) {
                if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) {
                    return mRealContext.getSystemService(name);
                }
                return super.getSystemService(name);
            }
        }

        /**
         * Return {@link Context} with isolated EmailProvider and AttachmentProvider.  This method
         * also invalidates the DB cache.
         */
        public static Context getProviderContext(Context context) throws Exception {
            MockContentResolver resolver = new MockContentResolver();
            final String filenamePrefix = "test.";
            RenamingDelegatingContext targetContextWrapper = new RenamingDelegatingContext(
                    new MockContext2(context), // The context that most methods are delegated to
                    context, // The context that file methods are delegated to
                    filenamePrefix);
            final Context providerContext = new MyIsolatedContext(resolver, targetContextWrapper,
                    context);
            providerContext.getContentResolver();

            // register EmailProvider and AttachmentProvider.
            final EmailProvider ep = new EmailProvider();
            ep.attachInfo(providerContext, null);
            resolver.addProvider(EmailContent.AUTHORITY, ep);

            final AttachmentProvider ap = new AttachmentProvider();
            ap.attachInfo(providerContext, null);
            resolver.addProvider(Attachment.ATTACHMENT_PROVIDER_LEGACY_URI_PREFIX, ap);

            ContentCache.invalidateAllCaches();

            return providerContext;
        }
    }
}
