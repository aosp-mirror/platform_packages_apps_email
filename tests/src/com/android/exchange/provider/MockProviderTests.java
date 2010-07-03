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

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.test.ProviderTestCase2;
import android.test.mock.MockContentResolver;

import java.util.ArrayList;

/**
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.provider.MockProviderTests email
 */
public class MockProviderTests extends ProviderTestCase2<MockProvider> {
    Context mMockContext;
    MockContentResolver mMockResolver;

    private static final String CANHAZ_AUTHORITY = "com.android.canhaz";
    private static final String PONY_TABLE = "pony";
    private static final String PONY_COLUMN_NAME = "name";
    private static final String PONY_COLUMN_TYPE = "type";
    private static final String PONY_COLUMN_LEGS= "legs";
    private static final String PONY_COLUMN_CAN_RIDE = "canRide";
    private static final String[] PONY_PROJECTION = {MockProvider.ID_COLUMN, PONY_COLUMN_NAME,
        PONY_COLUMN_TYPE, PONY_COLUMN_LEGS, PONY_COLUMN_CAN_RIDE};
    private static final int PONY_ID = 0;
    private static final int PONY_NAME = 1;
    private static final int PONY_TYPE = 2;
    private static final int PONY_LEGS = 3;
    private static final int PONY_CAN_RIDE = 4;

    public MockProviderTests() {
        super(MockProvider.class, MockProvider.AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
        mMockResolver = (MockContentResolver)mMockContext.getContentResolver();
        mMockResolver.addProvider(CANHAZ_AUTHORITY, new MockProvider());
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    private ContentValues ponyValues(String name, String type, int legs, boolean canRide) {
        ContentValues cv = new ContentValues();
        cv.put(PONY_COLUMN_NAME, name);
        cv.put(PONY_COLUMN_TYPE, type);
        cv.put(PONY_COLUMN_LEGS, legs);
        cv.put(PONY_COLUMN_CAN_RIDE, canRide ? 1 : 0);
        return cv;
    }

    private ContentProviderResult[] setupPonies() throws RemoteException,
            OperationApplicationException {
        // The Uri is content://com.android.canhaz/pony
        Uri uri = new Uri.Builder().scheme("content").authority(CANHAZ_AUTHORITY)
            .path(PONY_TABLE).build();
        // Our array of CPO's to be used with applyBatch
        ArrayList<ContentProviderOperation> ops = new ArrayList<ContentProviderOperation>();

        // Insert two ponies
        ContentValues pony1 = ponyValues("Flicka", "wayward", 4, true);
        ops.add(ContentProviderOperation.newInsert(uri).withValues(pony1).build());
        ContentValues pony2 = ponyValues("Elise", "dastardly", 3, false);
        ops.add(ContentProviderOperation.newInsert(uri).withValues(pony2).build());
        // Apply the batch with one insert operation
        return mMockResolver.applyBatch(MockProvider.AUTHORITY, ops);
    }

    private Uri getPonyUri() {
        return new Uri.Builder().scheme("content").authority(CANHAZ_AUTHORITY)
            .path(PONY_TABLE).build();
    }

    public void testInsertQueryandDelete() throws RemoteException, OperationApplicationException {
        // The Uri is content://com.android.canhaz/pony
        ContentProviderResult[] results = setupPonies();
        Uri uri = getPonyUri();

        // Check the results
        assertNotNull(results);
        assertEquals(2, results.length);
        // Make sure that we've created matcher entries for pony and pony/#
        assertEquals(MockProvider.TABLE, MockProvider.sURIMatcher.match(MockProvider.uri(uri)));
        assertEquals(MockProvider.RECORD,
                MockProvider.sURIMatcher.match(MockProvider.uri(results[0].uri)));
        Cursor c = mMockResolver.query(MockProvider.uri(uri), PONY_PROJECTION, null, null, null);
        assertNotNull(c);
        assertEquals(2, c.getCount());
        long eliseId = -1;
        long flickaId = -1;
        while (c.moveToNext()) {
            String name = c.getString(PONY_NAME);
            if ("Flicka".equals(name)) {
                assertEquals("Flicka", c.getString(PONY_NAME));
                assertEquals("wayward", c.getString(PONY_TYPE));
                assertEquals(4, c.getInt(PONY_LEGS));
                assertEquals(1, c.getInt(PONY_CAN_RIDE));
                flickaId = c.getLong(PONY_ID);
            } else if ("Elise".equals(name)) {
                assertEquals("dastardly", c.getString(PONY_TYPE));
                assertEquals(3, c.getInt(PONY_LEGS));
                assertEquals(0, c.getInt(PONY_CAN_RIDE));
                eliseId = c.getLong(PONY_ID);
            } else {
                fail("Wrong record: " + name);
            }
        }

        // eliseId and flickaId should have been set
        assertNotSame(-1, eliseId);
        assertNotSame(-1, flickaId);
        // Delete the elise record
        assertEquals(1, mMockResolver.delete(ContentUris.withAppendedId(MockProvider.uri(uri),
                eliseId), null, null));
        c = mMockResolver.query(MockProvider.uri(uri), PONY_PROJECTION, null, null, null);
        assertNotNull(c);
        // There should be one left (Flicka)
        assertEquals(1, c.getCount());
        assertTrue(c.moveToNext());
        assertEquals("Flicka", c.getString(PONY_NAME));
    }

    public void testUpdate() throws RemoteException, OperationApplicationException {
        // The Uri is content://com.android.canhaz/pony
        Uri uri = getPonyUri();
        setupPonies();
        Cursor c = mMockResolver.query(MockProvider.uri(uri), PONY_PROJECTION, null, null, null);
        assertNotNull(c);
        assertEquals(2, c.getCount());
        // Give all the ponies 5 legs
        ContentValues cv = new ContentValues();
        cv.put(PONY_COLUMN_LEGS, 5);
        assertEquals(2, mMockResolver.update(MockProvider.uri(uri), cv, null, null));
        c = mMockResolver.query(MockProvider.uri(uri), PONY_PROJECTION, null, null, null);
        assertNotNull(c);
        // We should still have two records, and each should have 5 legs, but otherwise be the same
        assertEquals(2, c.getCount());
        long eliseId = -1;
        long flickaId = -1;
        while (c.moveToNext()) {
            String name = c.getString(PONY_NAME);
            if ("Flicka".equals(name)) {
                assertEquals("Flicka", c.getString(PONY_NAME));
                assertEquals("wayward", c.getString(PONY_TYPE));
                assertEquals(5, c.getInt(PONY_LEGS));
                assertEquals(1, c.getInt(PONY_CAN_RIDE));
                flickaId = c.getLong(PONY_ID);
            } else if ("Elise".equals(name)) {
                assertEquals("dastardly", c.getString(PONY_TYPE));
                assertEquals(5, c.getInt(PONY_LEGS));
                assertEquals(0, c.getInt(PONY_CAN_RIDE));
                eliseId = c.getLong(PONY_ID);
            } else {
                fail("Wrong record: " + name);
            }
        }
        // eliseId and flickaId should have been set
        assertNotSame(-1, eliseId);
        assertNotSame(-1, flickaId);
    }
}
