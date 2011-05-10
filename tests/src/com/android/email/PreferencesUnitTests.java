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

package com.android.email;

import android.content.Context;
import android.net.Uri;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.HashMap;

/**
 * This is a series of unit tests for the Preferences class.
 *
 * Technically these are functional because they use the underlying preferences framework.  It
 * would be a really good idea if we could inject our own underlying preferences storage, to better
 * test cases like zero accounts behavior (right now, we have to allow for any number of accounts
 * already being on the device, and not trashing any.)
 */
@SmallTest
public class PreferencesUnitTests extends AndroidTestCase {

    private Preferences mPreferences;
    private Context mMockContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mMockContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(mContext);
        mPreferences = Preferences.getPreferences(mMockContext);
    }

    /** Just because this does exist anywhere else */
    private void assertEquals(long[] expected, long[] actual) {
        assertNotNull(actual);
        assertEquals(expected.length, actual.length);
        for (int i = expected.length - 1; i >= 0; i--) {
            if (expected[i] != actual[i]) {
                fail("expected array element[" + i + "]:<"
                        + expected[i] + "> but was:<" + actual[i] + '>');
            }
        }
    }
    private void assertEquals(HashMap<Long, long[]> expected, HashMap<Long, long[]> actual) {
        assertNotNull(actual);
        assertEquals(expected.size(), actual.size());
        for (Long key : expected.keySet()) {
            assertTrue(actual.containsKey(key));
            long[] expectedArray = expected.get(key);
            long[] actualArray = actual.get(key);
            assertEquals(expectedArray, actualArray);
        }
    }
    /**
     * Test the new getAccountByContentUri() API.  This should return null if no
     * accounts are configured, or the Uri doesn't match, and it should return a desired account
     * otherwise.
     *
     * TODO: Not actually testing the no-accounts case
     */
    public void testGetAccountByContentUri() {
        // Create a dummy account
        Account account = new Account(mMockContext);
        account.save(mPreferences);

        // test sunny-day lookup by Uri
        Uri testAccountUri = account.getContentUri();
        Account lookup = mPreferences.getAccountByContentUri(testAccountUri);
        assertEquals(account, lookup);

        // now make it a bogus Uri - bad scheme, good path, good UUID
        testAccountUri = Uri.parse("bogus://accounts/" + account.getUuid());
        lookup = mPreferences.getAccountByContentUri(testAccountUri);
        assertNull(lookup);

        // now make it a bogus Uri - good scheme, bad path, good UUID
        testAccountUri = Uri.parse("content://bogus/" + account.getUuid());
        lookup = mPreferences.getAccountByContentUri(testAccountUri);
        assertNull(lookup);

        // now make it a bogus Uri - good scheme/path, bad UUID
        testAccountUri = Uri.parse("content://accounts/" + account.getUuid() + "-bogus");
        lookup = mPreferences.getAccountByContentUri(testAccountUri);
        assertNull(lookup);
    }

    public void testSetMessageNotificationTable() {
        HashMap<Long, long[]> testTable = new HashMap<Long, long[]>();
        String testString;

        // One account
        testTable.clear();
        testTable.put(5L, new long[] { 2L, 3L });
        mPreferences.mSharedPreferences.edit()
                .putString("messageNotificationTable", "value_unset").apply();
        mPreferences.setMessageNotificationTable(testTable);
        testString = mPreferences.mSharedPreferences.getString("messageNotificationTable", null);
        assertEquals("5:2,3", testString);

        // Multiple accounts
        // NOTE: This assumes a very specific order in the hash map and is fragile; if the hash
        // map is ever changed, this may break unexpectedly.
        testTable.clear();
        testTable.put(5L, new long[] { 2L, 3L });
        testTable.put(3L, new long[] { 1L, 8L });
        mPreferences.mSharedPreferences.edit()
                .putString("messageNotificationTable", "value_unset").apply();
        mPreferences.setMessageNotificationTable(testTable);
        testString = mPreferences.mSharedPreferences.getString("messageNotificationTable", null);
        assertEquals("5:2,3;3:1,8", testString);

        // Wrong number of elements in the array
        // NOTE: This assumes a very specific order in the hash map and is fragile; if the hash
        // map is ever changed, this may break unexpectedly.
        testTable.clear();
        testTable.put(5L, new long[] { 2L, 3L, 8L }); // too many
        testTable.put(3L, new long[] { 1L, 8L });
        mPreferences.mSharedPreferences.edit()
                .putString("messageNotificationTable", "value_unset_1").apply();
        try {
            mPreferences.setMessageNotificationTable(testTable);
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
        }
        testString = mPreferences.mSharedPreferences.getString("messageNotificationTable", null);
        assertEquals("value_unset_1", testString);
        testTable.clear();
        testTable.put(5L, new long[] { 2L }); // too few
        testTable.put(3L, new long[] { 1L, 8L });
        mPreferences.mSharedPreferences.edit()
                .putString("messageNotificationTable", "value_unset_2").apply();
        try {
            mPreferences.setMessageNotificationTable(testTable);
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
        }
        testString = mPreferences.mSharedPreferences.getString("messageNotificationTable", null);
        assertEquals("value_unset_2", testString);

        // Nulls in strange places
        testTable.clear();
        testTable.put(5L, null); // no array
        testTable.put(3L, new long[] { 1L, 8L });
        mPreferences.mSharedPreferences.edit()
                .putString("messageNotificationTable", "value_unset_3").apply();
        try {
            mPreferences.setMessageNotificationTable(testTable);
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
        }
        testString = mPreferences.mSharedPreferences.getString("messageNotificationTable", null);
        assertEquals("value_unset_3", testString);
        mPreferences.mSharedPreferences.edit()
                .putString("messageNotificationTable", "value_unset_4").apply();
        try {
            mPreferences.setMessageNotificationTable(null); // no table
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
        }
        testString = mPreferences.mSharedPreferences.getString("messageNotificationTable", null);
        assertEquals("value_unset_4", testString);
    }

    public void testGetMessageNotificationTable() {
        HashMap<Long, long[]> testTable;
        HashMap<Long, long[]> expectedTable = new HashMap<Long, long[]>();

        // Test initial condition
        assertFalse(mPreferences.mSharedPreferences.contains("messageNotificationTable"));

        // One account
        mPreferences.mSharedPreferences.edit()
                .putString("messageNotificationTable", "5:2,3").apply();
        testTable = mPreferences.getMessageNotificationTable();
        expectedTable.clear();
        expectedTable.put(5L, new long[] { 2L, 3L });
        assertEquals(expectedTable, testTable);

        // Multiple accounts
        mPreferences.mSharedPreferences.edit()
                .putString("messageNotificationTable", "5:2,3;3:1,8;6:5,3").apply();
        testTable = mPreferences.getMessageNotificationTable();
        expectedTable.clear();
        expectedTable.put(5L, new long[] { 2L, 3L });
        expectedTable.put(3L, new long[] { 1L, 8L });
        expectedTable.put(6L, new long[] { 5L, 3L });
        assertEquals(expectedTable, testTable);

        // Empty account
        mPreferences.mSharedPreferences.edit()
                .putString("messageNotificationTable", "5:2,3;").apply();
        testTable = mPreferences.getMessageNotificationTable();
        expectedTable.clear();
        expectedTable.put(5L, new long[] { 2L, 3L });
        assertEquals(expectedTable, testTable);

        // Empty fields
        mPreferences.mSharedPreferences.edit()
                .putString("messageNotificationTable", "5:2,3;3:").apply();
        testTable = mPreferences.getMessageNotificationTable();
        expectedTable.clear();
        expectedTable.put(5L, new long[] { 2L, 3L });
        assertEquals(expectedTable, testTable);
        mPreferences.mSharedPreferences.edit()
                .putString("messageNotificationTable", "5:2,3;3:1,").apply();
        testTable = mPreferences.getMessageNotificationTable();
        expectedTable.clear();
        expectedTable.put(5L, new long[] { 2L, 3L });
        assertEquals(expectedTable, testTable);

        // Garbage
        mPreferences.mSharedPreferences.edit()
                .putString("messageNotificationTable", "blahblahblah").apply();
        testTable = mPreferences.getMessageNotificationTable();
        expectedTable.clear();
        assertEquals(expectedTable, testTable); // empty table
        mPreferences.mSharedPreferences.edit()
                .putString("messageNotificationTable", "5:2,3;blahblahblah").apply();
        testTable = mPreferences.getMessageNotificationTable();
        expectedTable.clear();
        expectedTable.put(5L, new long[] { 2L, 3L });
        assertEquals(expectedTable, testTable);
    }
}
