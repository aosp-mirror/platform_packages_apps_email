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

package com.android.exchange.utility;

import android.test.AndroidTestCase;

import java.util.TimeZone;

/**
 * Tests of EAS Calendar Utilities
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.utility.CalendarUtilitiesTests email
 *
 * Please see RFC2445 for RRULE definition
 * http://www.ietf.org/rfc/rfc2445.txt
 */

public class CalendarUtilitiesTests extends AndroidTestCase {

    // Some prebuilt time zones, Base64 encoded (as they arrive from EAS)
    // More time zones to be added over time

    // Not all time zones are appropriate for testing.  For example, ISRAEL_STANDARD_TIME cannot be
    // used because DST is determined from year to year in a non-standard way (related to the lunar
    // calendar); therefore, the test would only work during the year in which it was created
    private static final String INDIA_STANDARD_TIME =
        "tv7//0kAbgBkAGkAYQAgAFMAdABhAG4AZABhAHIAZAAgAFQAaQBtAGUAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEkAbgBkAGkAYQAgAEQAYQB5AGwAaQBnAGgAdAAgAFQAaQBtAGUA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";
    private static final String PACIFIC_STANDARD_TIME =
        "4AEAAFAAYQBjAGkAZgBpAGMAIABTAHQAYQBuAGQAYQByAGQAIABUAGkAbQBlAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAsAAAABAAIAAAAAAAAAAAAAAFAAYQBjAGkAZgBpAGMAIABEAGEAeQBsAGkAZwBoAHQAIABUAGkA" +
        "bQBlAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMAAAACAAIAAAAAAAAAxP///w==";

    public void testGetSet() {
        byte[] bytes = new byte[] {0, 1, 2, 3, 4, 5, 6, 7};

        // First, check that getWord/Long are properly little endian
        assertEquals(0x0100, CalendarUtilities.getWord(bytes, 0));
        assertEquals(0x03020100, CalendarUtilities.getLong(bytes, 0));
        assertEquals(0x07060504, CalendarUtilities.getLong(bytes, 4));

        // Set some words and longs
        CalendarUtilities.setWord(bytes, 0, 0xDEAD);
        CalendarUtilities.setLong(bytes, 2, 0xBEEFBEEF);
        CalendarUtilities.setWord(bytes, 6, 0xCEDE);

        // Retrieve them
        assertEquals(0xDEAD, CalendarUtilities.getWord(bytes, 0));
        assertEquals(0xBEEFBEEF, CalendarUtilities.getLong(bytes, 2));
        assertEquals(0xCEDE, CalendarUtilities.getWord(bytes, 6));
    }

    public void testParseTimeZoneEndToEnd() {
        TimeZone tz = CalendarUtilities.tziStringToTimeZone(PACIFIC_STANDARD_TIME);
        assertEquals("Pacific Standard Time", tz.getDisplayName());
        tz = CalendarUtilities.tziStringToTimeZone(INDIA_STANDARD_TIME);
        assertEquals("India Standard Time", tz.getDisplayName());
    }

    public void testGenerateEasDayOfWeek() {
        String byDay = "TU;WE;SA";
        // TU = 4, WE = 8; SA = 64;
        assertEquals("76", CalendarUtilities.generateEasDayOfWeek(byDay));
        // MO = 2, TU = 4; WE = 8; TH = 16; FR = 32
        byDay = "MO;TU;WE;TH;FR";
        assertEquals("62", CalendarUtilities.generateEasDayOfWeek(byDay));
        // SU = 1
        byDay = "SU";
        assertEquals("1", CalendarUtilities.generateEasDayOfWeek(byDay));
    }

    public void testTokenFromRrule() {
        String rrule = "FREQ=DAILY;INTERVAL=1;BYDAY=WE,TH,SA;BYMONTHDAY=17";
        assertEquals("DAILY", CalendarUtilities.tokenFromRrule(rrule, "FREQ="));
        assertEquals("1", CalendarUtilities.tokenFromRrule(rrule, "INTERVAL="));
        assertEquals("17", CalendarUtilities.tokenFromRrule(rrule, "BYMONTHDAY="));
        assertNull(CalendarUtilities.tokenFromRrule(rrule, "UNTIL="));
    }

    // Tests in progress...

//    public void testTimeZoneToTziString() {
//        for (String timeZoneId: TimeZone.getAvailableIDs()) {
//            TimeZone timeZone = TimeZone.getTimeZone(timeZoneId);
//            if (timeZone != null) {
//                String tzs = CalendarUtilities.timeZoneToTziString(timeZone);
//                TimeZone newTimeZone = CalendarUtilities.tziStringToTimeZone(tzs);
//                System.err.println("In: " + timeZone.getDisplayName() + ", Out: " + newTimeZone.getDisplayName());
//            }
//        }
//     }
//    public void testParseTimeZone() {
//        GregorianCalendar cal = getTestCalendar(parsedTimeZone, dstStart);
//        cal.add(GregorianCalendar.MINUTE, -1);
//        Date b = cal.getTime();
//        cal.add(GregorianCalendar.MINUTE, 2);
//        Date a = cal.getTime();
//        if (parsedTimeZone.inDaylightTime(b) || !parsedTimeZone.inDaylightTime(a)) {
//            userLog("ERROR IN TIME ZONE CONTROL!");
//        }
//        cal = getTestCalendar(parsedTimeZone, dstEnd);
//        cal.add(GregorianCalendar.HOUR, -2);
//        b = cal.getTime();
//        cal.add(GregorianCalendar.HOUR, 2);
//        a = cal.getTime();
//        if (!parsedTimeZone.inDaylightTime(b)) userLog("ERROR IN TIME ZONE CONTROL");
//        if (parsedTimeZone.inDaylightTime(a)) userLog("ERROR IN TIME ZONE CONTROL!");
//    }
}
