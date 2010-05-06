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

package com.android.exchange.adapter;

import com.android.exchange.adapter.CalendarSyncAdapter.EasCalendarSyncParser;

import android.content.ContentValues;
import android.provider.Calendar.Events;

import java.io.IOException;
import java.util.GregorianCalendar;
import java.util.TimeZone;

/**
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.adapter.CalendarSyncAdapterTests email
 */

public class CalendarSyncAdapterTests extends SyncAdapterTestCase<CalendarSyncAdapter> {

    public CalendarSyncAdapterTests() {
        super();
    }

    public void testSetTimes() throws IOException {
        CalendarSyncAdapter adapter = getTestSyncAdapter(CalendarSyncAdapter.class);
        EasCalendarSyncParser p = adapter.new EasCalendarSyncParser(getTestInputStream(), adapter);

        ContentValues cv = new ContentValues();

        // Basic, one-time meeting lasting an hour
        GregorianCalendar startCalendar = new GregorianCalendar(2010, 5, 10, 8, 30);
        Long startTime = startCalendar.getTimeInMillis();
        GregorianCalendar endCalendar = new GregorianCalendar(2010, 5, 10, 9, 30);
        Long endTime = endCalendar.getTimeInMillis();

        p.setTimes(cv, startTime, endTime, 0);
        assertNull(cv.getAsInteger(Events.DURATION));
        assertEquals(startTime, cv.getAsLong(Events.DTSTART));
        assertEquals(endTime, cv.getAsLong(Events.DTEND));
        assertEquals(endTime, cv.getAsLong(Events.LAST_DATE));
        assertNull(cv.getAsString(Events.EVENT_TIMEZONE));

        // Recurring meeting lasting an hour
        cv.clear();
        cv.put(Events.RRULE, "FREQ=DAILY");
        p.setTimes(cv, startTime, endTime, 0);
        assertEquals("P60M", cv.getAsString(Events.DURATION));
        assertEquals(startTime, cv.getAsLong(Events.DTSTART));
        assertNull(cv.getAsLong(Events.DTEND));
        assertNull(cv.getAsLong(Events.LAST_DATE));
        assertNull(cv.getAsString(Events.EVENT_TIMEZONE));

        // Recurring all-day event lasting one day
        cv.clear();
        startCalendar = new GregorianCalendar(2010, 5, 10, 8, 30);
        startTime = startCalendar.getTimeInMillis();
        endCalendar = new GregorianCalendar(2010, 5, 11, 8, 30);
        endTime = endCalendar.getTimeInMillis();
        cv.put(Events.RRULE, "FREQ=WEEKLY;BYDAY=MO");
        p.setTimes(cv, startTime, endTime, 1);

        // The start time should have hour/min/sec zero'd out
        startCalendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        startCalendar.set(2010, 5, 10, 0, 0, 0);
        startCalendar.set(GregorianCalendar.MILLISECOND, 0);
        startTime = startCalendar.getTimeInMillis();
        assertEquals(startTime, cv.getAsLong(Events.DTSTART));

        // The duration should be in days
        assertEquals("P1D", cv.getAsString(Events.DURATION));
        assertNull(cv.getAsLong(Events.DTEND));
        assertNull(cv.getAsLong(Events.LAST_DATE));
        // There must be a timezone
        assertNotNull(cv.getAsString(Events.EVENT_TIMEZONE));
    }

    public void testIsValidEventValues() throws IOException {
        CalendarSyncAdapter adapter = getTestSyncAdapter(CalendarSyncAdapter.class);
        EasCalendarSyncParser p = adapter.new EasCalendarSyncParser(getTestInputStream(), adapter);

        long validTime = System.currentTimeMillis();
        String validData = "foo-bar-bletch";
        String validDuration = "P30M";
        String validRrule = "FREQ=DAILY";

        ContentValues cv = new ContentValues();

        cv.put(Events.DTSTART, validTime);
        // Needs _SYNC_DATA and DTEND/DURATION
        assertFalse(p.isValidEventValues(cv, false));
        cv.put(Events._SYNC_DATA, validData);
        // Needs DTEND/DURATION
        assertFalse(p.isValidEventValues(cv, false));
        cv.put(Events.DURATION, validDuration);
        // Valid (DTSTART, _SYNC_DATA, DURATION)
        assertTrue(p.isValidEventValues(cv, false));
        cv.remove(Events.DURATION);
        cv.put(Events.DTEND, validTime);
        // Valid (DTSTART, _SYNC_DATA, DTEND)
        assertTrue(p.isValidEventValues(cv, false));
        cv.remove(Events.DTSTART);
        // Needs DTSTART
        assertFalse(p.isValidEventValues(cv, false));
        cv.put(Events.DTSTART, validTime);
        cv.put(Events.RRULE, validRrule);
        // With RRULE, needs DURATION
        assertFalse(p.isValidEventValues(cv, false));
        cv.put(Events.DURATION, "P30M");
        // Valid (RRULE+DURATION)
        assertTrue(p.isValidEventValues(cv, false));
        cv.put(Events.ALL_DAY, "1");
        // Needs DURATION in the form P<n>D
        assertFalse(p.isValidEventValues(cv, false));
        // Valid (RRULE+ALLDAY+DURATION(P<n>D)
        cv.put(Events.DURATION, "P1D");
        assertTrue(p.isValidEventValues(cv, false));
    }
}
