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
        new String[] {Contacts.DISPLAY_NAME, CommonDataKinds.Email.ADDRESS, Contacts.CONTENT_TYPE};
    private static final int GAL_RESULT_DISPLAY_NAME_COLUMN = 0;
    private static final int GAL_RESULT_EMAIL_ADDRESS_COLUMN = 1;
    private static final int GAL_RESULT_CONTENT_TYPE_COLUMN = 2;

    public void testBuildGalResultCursor() {
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
            assertEquals(data.displayName, c.getString(GAL_RESULT_DISPLAY_NAME_COLUMN));
            assertEquals(data.emailAddress, c.getString(GAL_RESULT_EMAIL_ADDRESS_COLUMN));
            assertNull(c.getString(GAL_RESULT_CONTENT_TYPE_COLUMN));
        }
    }
}
