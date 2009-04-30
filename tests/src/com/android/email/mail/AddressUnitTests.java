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
    
    private static final String MULTI_ADDRESSES_LIST = 
            "noname1@dom1.com, "
            + "<noname2@dom2.com>, "
            + "simple name <address3@dom3.org>, "
            + "\"name,4\" <address4@dom4.org>,"
            + "\"big \\\"G\\\"\" <bigG@dom5.net>,"
            + "\u65E5\u672C\u8A9E <address6@co.jp>,"
            + "\"\u65E5\u672C\u8A9E\" <address7@co.jp>,"
            + "\uD834\uDF01\uD834\uDF46 <address8@ne.jp>,"
            + "\"\uD834\uDF01\uD834\uDF46\" <address9@ne.jp>";
    private static final int MULTI_ADDRESSES_COUNT = 9;
    
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
     * TODO: more in-depth tests for parse()
     */
    
    /**
     * Simple quick checks of empty-input edge conditions for parse()
     * 
     * NOTE:  This is not a claim that these edge cases are "correct", only to maintain consistent
     * behavior while I am changing some of the code in the function under test.
     */
    public void testEmptyParse() {
        Address[] result;
        
        // null input => empty array
        result = Address.parse(null);
        assertTrue("parsing null address", result != null && result.length == 0);
        
        // empty string input => empty array
        result = Address.parse("");
        assertTrue("parsing zero-length", result != null && result.length == 0);
    }
    
    /**
     * Test parsing for single address.
     */
    public void testSingleParse() {
        Address[] address1 = Address.parse("address1@dom1.com");
        assertEquals("bare address count", 1, address1.length);
        assertEquals("bare address", "address1@dom1.com", address1[0].getAddress());
        assertNull("name of bare address", address1[0].getPersonal());

        Address[] address2 = Address.parse("<address2@dom2.com>");
        assertEquals("bracket address count", 1, address2.length);
        assertEquals("bracket address", "address2@dom2.com", address2[0].getAddress());
        assertNull("name of bracket address", address2[0].getPersonal());

        Address[] address3 = Address.parse("first last <address3@dom3.org>");
        assertEquals("address with name count", 1, address3.length);
        assertEquals("address with name", "address3@dom3.org", address3[0].getAddress());
        assertEquals("name of address with name", "first last", address3[0].getPersonal());

        Address[] address4 = Address.parse("\"first,last\" <address4@dom4.org>");
        assertEquals("address with quoted name count", 1, address4.length);
        assertEquals("address with quoted name", "address4@dom4.org", address4[0].getAddress());
        assertEquals("name of address with quoted name", "first,last", address4[0].getPersonal());
    }
    
    /**
     * Test parsing for illegal address.
     */
    public void testIllegalParse() {
        Address[] address1 = Address.parse("address1");
        assertEquals("no atmark", 0, address1.length);

        Address[] address2 = Address.parse("address2@");
        assertEquals("no domain", 0, address2.length);
        
        Address[] address3 = Address.parse("@dom3.com");
        assertEquals("no local part", 0, address3.length);

        Address[] address4 = Address.parse("address4@sub@dom4.org");
        assertEquals("more than one atmark", 0, address4.length);

        Address[] address5 = Address.parse("address5@dom5");
        assertEquals("not dot in domain part", 0, address5.length);

        Address[] address6 = Address.parse("address6@dom6.com.");
        assertEquals("domain ends with dot", 0, address6.length);

        Address[] address7 = Address.parse("address7@.dom7.org");
        assertEquals("domain starts with dot", 0, address7.length);
    }
    
    /**
     * Test parsing for address part.
     */
    public void testParsingAddress() {
        Address[] addresses = Address.parse("address1@dom1.net, <address2@dom2.com>");
        assertEquals("address count", 2, addresses.length);

        assertEquals("bare address", "address1@dom1.net", addresses[0].getAddress());
        assertNull("bare address name", addresses[0].getPersonal());

        assertEquals("bracket address", "address2@dom2.com", addresses[1].getAddress());
        assertNull("bracket address name", addresses[1].getPersonal());
    }
    
    /**
     * Test parsing for simple name part.
     */
    public void testParsingSimpleName() {
        Address[] addresses = Address.parse(
                "name 1 <address1@dom1.net>, " +
                "\"name,2\" <address2@dom2.org>");
        assertEquals("address count", 2, addresses.length);
        
        assertEquals("bare name address", "address1@dom1.net", addresses[0].getAddress());
        assertEquals("bare name", "name 1", addresses[0].getPersonal());

        assertEquals("double quoted name address", "address2@dom2.org", addresses[1].getAddress());
        assertEquals("double quoted name", "name,2", addresses[1].getPersonal());
    }
    
    /**
     * Test parsing for utf-16 name part.
     */
    public void testParsingUtf16Name() {
        Address[] addresses = Address.parse(
                "\u3042\u3044\u3046 \u3048\u304A <address1@dom1.jp>, " +
                "\"\u3042\u3044\u3046,\u3048\u304A\" <address2@dom2.jp>");
        assertEquals("address count", 2, addresses.length);
        
        assertEquals("bare utf-16 name address", "address1@dom1.jp", addresses[0].getAddress());
        assertEquals("bare utf-16 name",
                "\u3042\u3044\u3046 \u3048\u304A", addresses[0].getPersonal());

        assertEquals("double quoted utf-16 name address",
                "address2@dom2.jp", addresses[1].getAddress());
        assertEquals("double quoted utf-16 name",
                "\u3042\u3044\u3046,\u3048\u304A", addresses[1].getPersonal());
    }
    
    /**
     * Test parsing for utf-32 name part.
     */
    public void testParsingUtf32Name() {
        Address[] addresses = Address.parse(
                "\uD834\uDF01\uD834\uDF46 \uD834\uDF22 <address1@dom1.net>, " +
                "\"\uD834\uDF01\uD834\uDF46,\uD834\uDF22\" <address2@dom2.com>");
        assertEquals("address count", 2, addresses.length);
        
        assertEquals("bare utf-32 name address", "address1@dom1.net", addresses[0].getAddress());
        assertEquals("bare utf-32 name",
                "\uD834\uDF01\uD834\uDF46 \uD834\uDF22", addresses[0].getPersonal());

        assertEquals("double quoted utf-32 name address",
                "address2@dom2.com", addresses[1].getAddress());
        assertEquals("double quoted utf-32 name",
                "\uD834\uDF01\uD834\uDF46,\uD834\uDF22", addresses[1].getPersonal());
    }
    
    /**
     * Test parsing for multi addresses.
     */
    public void testParseMulti() {
        Address[] addresses = Address.parse(MULTI_ADDRESSES_LIST);
        
        assertEquals("multi addrsses count", MULTI_ADDRESSES_COUNT, addresses.length);
        
        assertEquals("no name 1 address", "noname1@dom1.com", addresses[0].getAddress());
        assertNull("no name 1 name", addresses[0].getPersonal());
        assertEquals("no name 2 address", "noname2@dom2.com", addresses[1].getAddress());
        assertNull("no name 2 name", addresses[1].getPersonal());
        assertEquals("simple name address", "address3@dom3.org", addresses[2].getAddress());
        assertEquals("simple name name", "simple name", addresses[2].getPersonal());
        assertEquals("double quoted name address", "address4@dom4.org", addresses[3].getAddress());
        assertEquals("double quoted name name", "name,4", addresses[3].getPersonal());
        assertEquals("quoted name address", "bigG@dom5.net", addresses[4].getAddress());
        assertEquals("quoted name name", "big \"G\"", addresses[4].getPersonal());
        assertEquals("utf-16 name address", "address6@co.jp", addresses[5].getAddress());       
        assertEquals("utf-16 name name", "\u65E5\u672C\u8A9E", addresses[5].getPersonal());       
        assertEquals("utf-16 quoted name address", "address7@co.jp", addresses[6].getAddress());       
        assertEquals("utf-16 quoted name name", "\u65E5\u672C\u8A9E",
                addresses[6].getPersonal());       
        assertEquals("utf-32 name address", "address8@ne.jp", addresses[7].getAddress());       
        assertEquals("utf-32 name name", "\uD834\uDF01\uD834\uDF46", addresses[7].getPersonal());       
        assertEquals("utf-32 quoted name address", "address9@ne.jp", addresses[8].getAddress());       
        assertEquals("utf-32 quoted name name", "\uD834\uDF01\uD834\uDF46",
                addresses[8].getPersonal());       
    }
    
    /**
     * Test various combinations of the toString (single) method
     */
    public void testToStringSingle() {
        Address[] addresses = Address.parse(MULTI_ADDRESSES_LIST);
        
        assertEquals("multi addrsses count", MULTI_ADDRESSES_COUNT, addresses.length);

        // test for toString() results.
        assertEquals("no name 1", "noname1@dom1.com", addresses[0].toString());
        assertEquals("no name 2", "noname2@dom2.com", addresses[1].toString());
        assertEquals("simple name", "simple name <address3@dom3.org>", addresses[2].toString());
        assertEquals("double quoted name", "\"name,4\" <address4@dom4.org>", addresses[3].toString());
        assertEquals("quoted name", "\"big \"G\"\" <bigG@dom5.net>", addresses[4].toString());
        assertEquals("utf-16 name", "\u65E5\u672C\u8A9E <address6@co.jp>",
                addresses[5].toString());       
        assertEquals("utf-16 quoted name", "\u65E5\u672C\u8A9E <address7@co.jp>",
                addresses[6].toString());       
        assertEquals("utf-32 name", "\uD834\uDF01\uD834\uDF46 <address8@ne.jp>",
                addresses[7].toString());       
        assertEquals("utf-32 quoted name", "\uD834\uDF01\uD834\uDF46 <address9@ne.jp>",
                addresses[8].toString());       
    }
    
    /**
     * Test various combinations of the toString (multi) method
     */
    public void testToStringMulti() {
        Address[] addresses = Address.parse(MULTI_ADDRESSES_LIST);
        
        assertEquals("multi addrsses count", MULTI_ADDRESSES_COUNT, addresses.length);

        String line = Address.toString(addresses);
        assertEquals("toString multi", 
                "noname1@dom1.com,"
                + "noname2@dom2.com,"
                + "simple name <address3@dom3.org>,"
                + "\"name,4\" <address4@dom4.org>,"
                + "\"big \"G\"\" <bigG@dom5.net>,"
                + "\u65E5\u672C\u8A9E <address6@co.jp>,"
                + "\u65E5\u672C\u8A9E <address7@co.jp>,"
                + "\uD834\uDF01\uD834\uDF46 <address8@ne.jp>,"
                + "\uD834\uDF01\uD834\uDF46 <address9@ne.jp>",
                line);
    }
    
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
     * TODO: more in-depth tests for pack() and unpack()
     */
    
    /**
     * Simple quick checks of empty-input edge conditions for pack()
     * 
     * NOTE:  This is not a claim that these edge cases are "correct", only to maintain consistent
     * behavior while I am changing some of the code in the function under test.
     */
    public void testEmptyPack() {
        String result;
        
        // null input => null string
        result = Address.pack(null);
        assertNull("packing null", result);
        
        // zero-length input => empty string
        result = Address.pack(new Address[] { });
        assertEquals("packing empty array", "", result);
    }
    
    /**
     * Simple quick checks of empty-input edge conditions for unpack()
     * 
     * NOTE:  This is not a claim that these edge cases are "correct", only to maintain consistent
     * behavior while I am changing some of the code in the function under test.
     */
    public void testEmptyUnpack() {
        Address[] result;
        
        // null input => empty array
        result = Address.unpack(null);
        assertTrue("unpacking null address", result != null && result.length == 0);
        
        // empty string input => empty array
        result = Address.unpack("");
        assertTrue("unpacking zero-length", result != null && result.length == 0);
    }

}
