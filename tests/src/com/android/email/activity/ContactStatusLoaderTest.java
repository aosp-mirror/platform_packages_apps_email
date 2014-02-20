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

import com.android.email.activity.ContactStatusLoader.Result;
import com.android.mail.utils.MatrixCursorWithCachedColumns;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.StatusUpdates;
import android.test.ProviderTestCase2;
import android.test.mock.MockContentProvider;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;

import junit.framework.Assert;

/**
 * Test for {@link ContactStatusLoader}
 *
 * Unfortunately this doesn't check {@link ContactStatusLoader.Result#mLookupUri}, because we don't
 * (shouldn't) know how {@link android.provider.ContactsContract.Data#getContactLookupUri} is
 * implemented.
 */
@SmallTest
public class ContactStatusLoaderTest
        extends ProviderTestCase2<ContactStatusLoaderTest.MockContactProvider> {
    private static final String EMAIL = "a@b.c";

    private MockContactProvider mProvider;

    public ContactStatusLoaderTest() {
        super(MockContactProvider.class, ContactsContract.AUTHORITY);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mProvider = getProvider();
    }

    // Contact doesn't exist
    public void brokentestContactNotFound() {
        // Insert empty cursor
        mProvider.mCursors.offer(new MatrixCursorWithCachedColumns(
                ContactStatusLoader.PROJECTION_PHOTO_ID_PRESENCE));

        // Load!
        ContactStatusLoader l = new ContactStatusLoader(getMockContext(), EMAIL);
        Result r = l.loadInBackground();

        // Check input to the provider
        assertEquals(1, mProvider.mUris.size());
        assertEquals("content://com.android.contacts/data/emails/lookup/a%40b.c",
                mProvider.mUris.get(0));

        // Check result
        assertNull(r.mPhoto);
        assertEquals(ContactStatusLoader.PRESENCE_UNKNOWN_RESOURCE_ID, r.mPresenceResId);
    }

    // Contact doesn't exist -- provider returns null for the first query
    public void brokentestNull() {
        // No cursor prepared. (Mock provider will return null)

        // Load!
        ContactStatusLoader l = new ContactStatusLoader(getMockContext(), EMAIL);
        Result r = l.loadInBackground();

        // Check result
        assertNull(r.mPhoto);
        assertEquals(ContactStatusLoader.PRESENCE_UNKNOWN_RESOURCE_ID, r.mPresenceResId);
    }

    // Contact exists, but no photo
    public void brokentestNoPhoto() {
        // Result for the first query (the one for photo-id)
        MatrixCursor cursor1 =
                new MatrixCursorWithCachedColumns(ContactStatusLoader.PROJECTION_PHOTO_ID_PRESENCE);
        cursor1.addRow(new Object[]{12345, StatusUpdates.AWAY});
        mProvider.mCursors.offer(cursor1);

        // Empty cursor for the second query
        mProvider.mCursors.offer(
                new MatrixCursorWithCachedColumns(ContactStatusLoader.PHOTO_PROJECTION));

        // Load!
        ContactStatusLoader l = new ContactStatusLoader(getMockContext(), EMAIL);
        Result r = l.loadInBackground();

        // Check input to the provider
        // We should have had at least two queries from loadInBackground.
        // There can be extra queries from getContactLookupUri(), but this test shouldn't know
        // the details, so use ">= 2".
        assertTrue(mProvider.mUris.size() >= 2);
        assertEquals("content://com.android.contacts/data/emails/lookup/a%40b.c",
                mProvider.mUris.get(0));
        assertEquals("content://com.android.contacts/data/12345",
                mProvider.mUris.get(1));

        // Check result
        assertNull(r.mPhoto); // no photo
        assertEquals(android.R.drawable.presence_away, r.mPresenceResId);
    }

    // Contact exists, but no photo (provider returns null for the second query)
    public void brokentestNull2() {
        // Result for the first query (the one for photo-id)
        MatrixCursor cursor1 =
                new MatrixCursorWithCachedColumns(ContactStatusLoader.PROJECTION_PHOTO_ID_PRESENCE);
        cursor1.addRow(new Object[]{12345, StatusUpdates.AWAY});
        mProvider.mCursors.offer(cursor1);

        // No cursor for the second query

        // Load!
        ContactStatusLoader l = new ContactStatusLoader(getMockContext(), EMAIL);
        Result r = l.loadInBackground();

        // Check result
        assertNull(r.mPhoto); // no photo
        assertEquals(android.R.drawable.presence_away, r.mPresenceResId);
    }

    // Contact exists, with a photo
    public void brokentestWithPhoto() {
        // Result for the first query (the one for photo-id)
        MatrixCursor cursor1 =
                new MatrixCursorWithCachedColumns(ContactStatusLoader.PROJECTION_PHOTO_ID_PRESENCE);
        cursor1.addRow(new Object[]{12345, StatusUpdates.AWAY});
        mProvider.mCursors.offer(cursor1);

        // Prepare for the second query.
        MatrixCursor cursor2 = new PhotoCursor(createJpegData(10, 20));
        mProvider.mCursors.offer(cursor2);

        // Load!
        ContactStatusLoader l = new ContactStatusLoader(getMockContext(), EMAIL);
        Result r = l.loadInBackground();

        // Check result
        assertNotNull(r.mPhoto);
        assertEquals(10, r.mPhoto.getWidth());
        assertEquals(android.R.drawable.presence_away, r.mPresenceResId);
    }

    private static byte[] createJpegData(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 50, out);
        return out.toByteArray();
    }

    // MatrixCursor doesn't support getBlob, so use this...
    private static class PhotoCursor extends MatrixCursorWithCachedColumns {
        private final byte[] mBlob;

        public PhotoCursor(byte[] blob) {
            super(ContactStatusLoader.PHOTO_PROJECTION);
            mBlob = blob;
            addRow(new Object[] {null}); // Add dummy row
        }

        @Override
        public byte[] getBlob(int column) {
            Assert.assertEquals(0, column);
            return mBlob;
        }
    }

    public static class MockContactProvider extends MockContentProvider {
        public ArrayList<String> mUris = new ArrayList<String>();

        public final Queue<Cursor> mCursors = new LinkedBlockingQueue<Cursor>();

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                String sortOrder) {
            mUris.add(uri.toString());
            return mCursors.poll();
        }

        @Override
        public void attachInfo(Context context, ProviderInfo info) {
        }
    }
}
