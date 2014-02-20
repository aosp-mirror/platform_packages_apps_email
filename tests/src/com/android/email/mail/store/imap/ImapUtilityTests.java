/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.email.mail.store.imap;

import android.test.AndroidTestCase;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.SmallTest;

@SmallTest
public class ImapUtilityTests extends AndroidTestCase {
    public static final String[] EmptyArrayString = new String[0];

    /**
     * Tests of the IMAP quoting rules function.
     */
    public void testImapQuote() {
        // Simple strings should come through with simple quotes
        assertEquals("\"abcd\"", ImapUtility.imapQuoted("abcd"));
        // Quoting internal double quotes with \
        assertEquals("\"ab\\\"cd\"", ImapUtility.imapQuoted("ab\"cd"));
        // Quoting internal \ with \\
        assertEquals("\"ab\\\\cd\"", ImapUtility.imapQuoted("ab\\cd"));
    }

    /**
     * Test getting elements of an IMAP sequence set.
     */
    public void testGetImapSequenceValues() {
        String[] expected;
        String[] actual;

        // Test valid sets
        expected = new String[] {"1"};
        actual = ImapUtility.getImapSequenceValues("1");
        MoreAsserts.assertEquals(expected, actual);

        expected = new String[] {"1", "3", "2"};
        actual = ImapUtility.getImapSequenceValues("1,3,2");
        MoreAsserts.assertEquals(expected, actual);

        expected = new String[] {"4", "5", "6"};
        actual = ImapUtility.getImapSequenceValues("4:6");
        MoreAsserts.assertEquals(expected, actual);

        expected = new String[] {"9", "8", "7"};
        actual = ImapUtility.getImapSequenceValues("9:7");
        MoreAsserts.assertEquals(expected, actual);

        expected = new String[] {"1", "2", "3", "4", "9", "8", "7"};
        actual = ImapUtility.getImapSequenceValues("1,2:4,9:7");
        MoreAsserts.assertEquals(expected, actual);

        // Test partially invalid sets
        expected = new String[] { "1", "5" };
        actual = ImapUtility.getImapSequenceValues("1,x,5");
        MoreAsserts.assertEquals(expected, actual);

        expected = new String[] { "1", "2", "3" };
        actual = ImapUtility.getImapSequenceValues("a:d,1:3");
        MoreAsserts.assertEquals(expected, actual);

        // Test invalid sets
        expected = EmptyArrayString;
        actual = ImapUtility.getImapSequenceValues("");
        MoreAsserts.assertEquals(expected, actual);

        expected = EmptyArrayString;
        actual = ImapUtility.getImapSequenceValues(null);
        MoreAsserts.assertEquals(expected, actual);

        expected = EmptyArrayString;
        actual = ImapUtility.getImapSequenceValues("a");
        MoreAsserts.assertEquals(expected, actual);

        expected = EmptyArrayString;
        actual = ImapUtility.getImapSequenceValues("1:x");
        MoreAsserts.assertEquals(expected, actual);
    }

    /**
     * Test getting elements of an IMAP range.
     */
    public void testGetImapRangeValues() {
        String[] expected;
        String[] actual;

        // Test valid ranges
        expected = new String[] {"1", "2", "3"};
        actual = ImapUtility.getImapRangeValues("1:3");
        MoreAsserts.assertEquals(expected, actual);

        expected = new String[] {"16", "15", "14"};
        actual = ImapUtility.getImapRangeValues("16:14");
        MoreAsserts.assertEquals(expected, actual);

        // Test in-valid ranges
        expected = EmptyArrayString;
        actual = ImapUtility.getImapRangeValues("");
        MoreAsserts.assertEquals(expected, actual);

        expected = EmptyArrayString;
        actual = ImapUtility.getImapRangeValues(null);
        MoreAsserts.assertEquals(expected, actual);

        expected = EmptyArrayString;
        actual = ImapUtility.getImapRangeValues("a");
        MoreAsserts.assertEquals(expected, actual);

        expected = EmptyArrayString;
        actual = ImapUtility.getImapRangeValues("6");
        MoreAsserts.assertEquals(expected, actual);

        expected = EmptyArrayString;
        actual = ImapUtility.getImapRangeValues("1:3,6");
        MoreAsserts.assertEquals(expected, actual);

        expected = EmptyArrayString;
        actual = ImapUtility.getImapRangeValues("1:x");
        MoreAsserts.assertEquals(expected, actual);

        expected = EmptyArrayString;
        actual = ImapUtility.getImapRangeValues("1:*");
        MoreAsserts.assertEquals(expected, actual);
    }
}
