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

import com.android.email.mail.PackedString;
import com.android.exchange.provider.GalResult.GalData;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Contacts;
import android.test.AndroidTestCase;

/**
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.provider.ExchangeDirectoryProviderTests email
 */
public class ExchangeDirectoryProviderTests extends AndroidTestCase {

    // Create a test projection; we should only get back values for display name and email address
    private static final String[] GAL_RESULT_PROJECTION =
        new String[] {Contacts.DISPLAY_NAME, CommonDataKinds.Email.ADDRESS, Contacts.CONTENT_TYPE,
            Contacts.LOOKUP_KEY};
    private static final int GAL_RESULT_COLUMN_DISPLAY_NAME = 0;
    private static final int GAL_RESULT_COLUMN_EMAIL_ADDRESS = 1;
    private static final int GAL_RESULT_COLUMN_CONTENT_TYPE = 2;
    private static final int GAL_RESULT_COLUMN_LOOKUP_KEY = 3;

    public void testBuildSimpleGalResultCursor() {
        GalResult result = new GalResult();
        result.addGalData(1, "Alice Aardvark", "alice@aardvark.com");
        result.addGalData(2, "Bob Badger", "bob@badger.com");
        result.addGalData(3, "Clark Cougar", "clark@cougar.com");
        result.addGalData(4, "Dan Dolphin", "dan@dolphin.com");
        // Make sure our returned cursor has the expected contents
        ExchangeDirectoryProvider provider = new ExchangeDirectoryProvider();
        Cursor c = provider.buildGalResultCursor(GAL_RESULT_PROJECTION, result);
        assertNotNull(c);
        assertEquals(MatrixCursor.class, c.getClass());
        assertEquals(4, c.getCount());
        for (int i = 0; i < 4; i++) {
            GalData data = result.galData.get(i);
            assertTrue(c.moveToNext());
            assertEquals(data.displayName, c.getString(GAL_RESULT_COLUMN_DISPLAY_NAME));
            assertEquals(data.emailAddress, c.getString(GAL_RESULT_COLUMN_EMAIL_ADDRESS));
            assertNull(c.getString(GAL_RESULT_COLUMN_CONTENT_TYPE));
        }
    }

    private static final String[][] DISPLAY_NAME_TEST_FIELDS = {
        {"Alice", "Aardvark", "Another Name"},
        {"Alice", "Aardvark", null},
        {"Alice", null, null},
        {null, "Aardvark", null},
        {null, null, null}
    };
    private static final int TEST_FIELD_FIRST_NAME = 0;
    private static final int TEST_FIELD_LAST_NAME = 1;
    private static final int TEST_FIELD_DISPLAY_NAME = 2;
    private static final String[] EXPECTED_DISPLAY_NAMES = new String[] {"Another Name",
        "Alice Aardvark", "Alice", "Aardvark", null};

    private GalResult getTestDisplayNameResult() {
        GalResult result = new GalResult();
        for (int i = 0; i < DISPLAY_NAME_TEST_FIELDS.length; i++) {
            GalData galData = new GalData();
            String[] names = DISPLAY_NAME_TEST_FIELDS[i];
            galData.put(GalData.FIRST_NAME, names[TEST_FIELD_FIRST_NAME]);
            galData.put(GalData.LAST_NAME, names[TEST_FIELD_LAST_NAME]);
            galData.put(GalData.DISPLAY_NAME, names[TEST_FIELD_DISPLAY_NAME]);
            result.addGalData(galData);
        }
        return result;
    }

    public void testDisplayNameLogic() {
        GalResult result = getTestDisplayNameResult();
        // Make sure our returned cursor has the expected contents
        ExchangeDirectoryProvider provider = new ExchangeDirectoryProvider();
        Cursor c = provider.buildGalResultCursor(GAL_RESULT_PROJECTION, result);
        assertNotNull(c);
        assertEquals(MatrixCursor.class, c.getClass());
        assertEquals(DISPLAY_NAME_TEST_FIELDS.length, c.getCount());
        for (int i = 0; i < EXPECTED_DISPLAY_NAMES.length; i++) {
            assertTrue(c.moveToNext());
            assertEquals(EXPECTED_DISPLAY_NAMES[i], c.getString(GAL_RESULT_COLUMN_DISPLAY_NAME));
        }
    }

    public void testLookupKeyLogic() {
        GalResult result = getTestDisplayNameResult();
        // Make sure our returned cursor has the expected contents
        ExchangeDirectoryProvider provider = new ExchangeDirectoryProvider();
        Cursor c = provider.buildGalResultCursor(GAL_RESULT_PROJECTION, result);
        assertNotNull(c);
        assertEquals(MatrixCursor.class, c.getClass());
        assertEquals(DISPLAY_NAME_TEST_FIELDS.length, c.getCount());
        for (int i = 0; i < EXPECTED_DISPLAY_NAMES.length; i++) {
            assertTrue(c.moveToNext());
            PackedString ps = new PackedString(c.getString(GAL_RESULT_COLUMN_LOOKUP_KEY));
            String[] testFields = DISPLAY_NAME_TEST_FIELDS[i];
            assertEquals(testFields[TEST_FIELD_FIRST_NAME], ps.get(GalData.FIRST_NAME));
            assertEquals(testFields[TEST_FIELD_LAST_NAME], ps.get(GalData.LAST_NAME));
            assertEquals(EXPECTED_DISPLAY_NAMES[i], ps.get(GalData.DISPLAY_NAME));
        }
    }
}
