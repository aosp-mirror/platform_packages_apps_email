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

package com.android.email.mail;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * This is a series of unit tests for the Address class.  These tests must be locally
 * complete - no server(s) required.
 */
@SmallTest
public class AddressUnitTests extends AndroidTestCase {
    
    Address mAddress1;
    Address mAddress2;
    Address mAddress3;

    /**
     * Setup code.  We generate a handful of Address objects
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mAddress1 = new Address("address1", "personal1");
        mAddress2 = new Address("address2", "");
        mAddress3 = new Address("address3", null);
    }

    /**
     * TODO: test parse()
     */
    
    /**
     * TODO: test toString() (single & list)
     */
    
    /**
     * Test various combinations of the toFriendly (single) method
     */
    public void testToFriendlySingle() {        
        assertEquals("personal1", mAddress1.toFriendly());
        assertEquals("address2", mAddress2.toFriendly());
        assertEquals("address3", mAddress3.toFriendly());
    }
    
    /**
     * Test various combinations of the toFriendly (array) method
     */
    public void testToFriendlyArray() {        
        Address[] list1 = null;
        Address[] list2 = new Address[0];
        Address[] list3 = new Address[] { mAddress1 };
        Address[] list4 = new Address[] { mAddress1, mAddress2, mAddress3 };
        
        assertEquals(null, Address.toFriendly(list1));
        assertEquals(null, Address.toFriendly(list2));
        assertEquals("personal1", Address.toFriendly(list3));
        assertEquals("personal1,address2,address3", Address.toFriendly(list4));
    }
    
    /**
     * TODO: test pack() and unpack()
     */
}
