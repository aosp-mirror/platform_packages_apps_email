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

public class CalendarUtilitiesTests extends AndroidTestCase {

    // Some prebuilt time zones, Base64 encoded (as they arrive from EAS)
    private static final String ISRAEL_STANDARD_TIME =
        "iP///ygARwBNAFQAKwAwADIAOgAwADAAKQAgAEoAZQByAHUAcwBhAGwAZQBtAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAkAAAAFAAIAAAAAAAAAAAAAACgARwBNAFQAKwAwADIAOgAwADAAKQAgAEoAZQByAHUAcwBhAGwA" +
        "ZQBtAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMABQAFAAIAAAAAAAAAxP///w==";
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
        TimeZone tz = CalendarUtilities.parseTimeZone(PACIFIC_STANDARD_TIME);
        assertEquals("Pacific Standard Time", tz.getDisplayName());
        tz = CalendarUtilities.parseTimeZone(INDIA_STANDARD_TIME);
        assertEquals("India Standard Time", tz.getDisplayName());
        tz = CalendarUtilities.parseTimeZone(ISRAEL_STANDARD_TIME);
        assertEquals("Israel Standard Time", tz.getDisplayName());
    }

    public void testGenerateEasDayOfWeek() {
        String byDay = "TU;WE;SA";
        assertEquals("76", CalendarUtilities.generateEasDayOfWeek(byDay));
        byDay = "MO;TU;WE;TH;FR";
        assertEquals("62", CalendarUtilities.generateEasDayOfWeek(byDay));
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

// TODO In progress
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
