/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.email.mail;

import com.android.email.Email;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Tests of StoreInfo & Store lookup in the Store abstract class
 */
@MediumTest
public class StoreTests extends AndroidTestCase {

    /**
     * Test StoreInfo & Store lookup for POP accounts
     */
    public void testStoreLookupPOP() throws MessagingException {
        final String storeUri = "pop3://user:password@server.com";
        Store.StoreInfo info = Store.StoreInfo.getStoreInfo(storeUri, getContext());
        
        assertNotNull("storeInfo null", info);
        assertNotNull("scheme null", info.mScheme);
        assertNotNull("classname null", info.mClassName);
        assertFalse(info.mPushSupported);
        assertEquals(Email.VISIBLE_LIMIT_DEFAULT, info.mVisibleLimitDefault);
        assertEquals(Email.VISIBLE_LIMIT_INCREMENT, info.mVisibleLimitIncrement);
        
        // This will throw MessagingException if the result would have been null
        Store store = Store.getInstance(storeUri, getContext(), null);
    }
        
    /**
     * Test StoreInfo & Store lookup for IMAP accounts
     */
    public void testStoreLookupIMAP() throws MessagingException {
        final String storeUri = "imap://user:password@server.com";
        Store.StoreInfo info = Store.StoreInfo.getStoreInfo(storeUri, getContext());
        
        assertNotNull("storeInfo null", info);
        assertNotNull("scheme null", info.mScheme);
        assertNotNull("classname null", info.mClassName);
        assertFalse(info.mPushSupported);
        assertEquals(Email.VISIBLE_LIMIT_DEFAULT, info.mVisibleLimitDefault);
        assertEquals(Email.VISIBLE_LIMIT_INCREMENT, info.mVisibleLimitIncrement);
        
        // This will throw MessagingException if the result would have been null
        Store store = Store.getInstance(storeUri, getContext(), null);
    }
        
    /**
     * Test StoreInfo & Store lookup for EAS accounts
     * TODO: EAS store will probably require implementation of Store.PersistentDataCallbacks
     */
    public void testStoreLookupEAS() throws MessagingException {
        final String storeUri = "eas://user:password@server.com";
        Store.StoreInfo info = Store.StoreInfo.getStoreInfo(storeUri, getContext());
        if (info != null) {
            assertNotNull("scheme null", info.mScheme);
            assertNotNull("classname null", info.mClassName);
            assertTrue(info.mPushSupported);
            assertEquals(-1, info.mVisibleLimitDefault);
            assertEquals(-1, info.mVisibleLimitIncrement);
            
            // This will throw MessagingException if the result would have been null
            Store store = Store.getInstance(storeUri, getContext(), null);
        } else {
            try {
                Store store = Store.getInstance(storeUri, getContext(), null);
                fail("MessagingException expected when EAS not supported");
            } catch (MessagingException me) {
                // expected - fall through
            }
        }
    }
    
    /**
     * Test StoreInfo & Store lookup for unknown accounts
     */
    public void testStoreLookupUnknown() {
        final String storeUri = "bogus-scheme://user:password@server.com";
        Store.StoreInfo info = Store.StoreInfo.getStoreInfo(storeUri, getContext());
        assertNull(info);

        try {
            Store store = Store.getInstance(storeUri, getContext(), null);
            fail("MessagingException expected from bogus URI scheme");
        } catch (MessagingException me) {
            // expected - fall through
        }
    }

}
