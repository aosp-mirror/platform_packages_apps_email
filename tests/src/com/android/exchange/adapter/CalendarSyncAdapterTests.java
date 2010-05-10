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

    public void testSetTimeRelatedValues_NonRecurring() throws IOException {
        CalendarSyncAdapter adapter = getTestSyncAdapter(CalendarSyncAdapter.class);
        EasCalendarSyncParser p = adapter.new EasCalendarSyncParser(getTestInputStream(), adapter);
        ContentValues cv = new ContentValues();
        // Basic, one-time meeting lasting an hour
        GregorianCalendar startCalendar = new GregorianCalendar(2010, 5, 10, 8, 30);
        Long startTime = startCalendar.getTimeInMillis();
        GregorianCalendar endCalendar = new GregorianCalendar(2010, 5, 10, 9, 30);
        Long endTime = endCalendar.getTimeInMillis();

        p.setTimeRelatedValues(cv, startTime, endTime, 0);
        assertNull(cv.getAsInteger(Events.DURATION));
        assertEquals(startTime, cv.getAsLong(Events.DTSTART));
        assertEquals(endTime, cv.getAsLong(Events.DTEND));
        assertEquals(endTime, cv.getAsLong(Events.LAST_DATE));
        assertNull(cv.getAsString(Events.EVENT_TIMEZONE));
    }

    public void testSetTimeRelatedValues_Recurring() throws IOException {
        CalendarSyncAdapter adapter = getTestSyncAdapter(CalendarSyncAdapter.class);
        EasCalendarSyncParser p = adapter.new EasCalendarSyncParser(getTestInputStream(), adapter);
        ContentValues cv = new ContentValues();
        // Recurring meeting lasting an hour
        GregorianCalendar startCalendar = new GregorianCalendar(2010, 5, 10, 8, 30);
        Long startTime = startCalendar.getTimeInMillis();
        GregorianCalendar endCalendar = new GregorianCalendar(2010, 5, 10, 9, 30);
        Long endTime = endCalendar.getTimeInMillis();
        cv.put(Events.RRULE, "FREQ=DAILY");
        p.setTimeRelatedValues(cv, startTime, endTime, 0);
        assertEquals("P60M", cv.getAsString(Events.DURATION));
        assertEquals(startTime, cv.getAsLong(Events.DTSTART));
        assertNull(cv.getAsLong(Events.DTEND));
        assertNull(cv.getAsLong(Events.LAST_DATE));
        assertNull(cv.getAsString(Events.EVENT_TIMEZONE));
    }

    public void testSetTimeRelatedValues_AllDay() throws IOException {
        CalendarSyncAdapter adapter = getTestSyncAdapter(CalendarSyncAdapter.class);
        EasCalendarSyncParser p = adapter.new EasCalendarSyncParser(getTestInputStream(), adapter);
        ContentValues cv = new ContentValues();
        GregorianCalendar startCalendar = new GregorianCalendar(2010, 5, 10, 8, 30);
        Long startTime = startCalendar.getTimeInMillis();
        GregorianCalendar endCalendar = new GregorianCalendar(2010, 5, 11, 8, 30);
        Long endTime = endCalendar.getTimeInMillis();
        cv.put(Events.RRULE, "FREQ=WEEKLY;BYDAY=MO");
        p.setTimeRelatedValues(cv, startTime, endTime, 1);

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

    public void testSetTimeRelatedValues_Recurring_AllDay_Exception () throws IOException {
        CalendarSyncAdapter adapter = getTestSyncAdapter(CalendarSyncAdapter.class);
        EasCalendarSyncParser p = adapter.new EasCalendarSyncParser(getTestInputStream(), adapter);
        ContentValues cv = new ContentValues();

        // Recurrence exception for all-day event; the exception is NOT all-day
        GregorianCalendar startCalendar = new GregorianCalendar(2010, 5, 17, 8, 30);
        Long startTime = startCalendar.getTimeInMillis();
        GregorianCalendar endCalendar = new GregorianCalendar(2010, 5, 17, 9, 30);
        Long endTime = endCalendar.getTimeInMillis();
        cv.put(Events.ORIGINAL_ALL_DAY, 1);
        GregorianCalendar instanceCalendar = new GregorianCalendar(2010, 5, 17, 8, 30);
        cv.put(Events.ORIGINAL_INSTANCE_TIME, instanceCalendar.getTimeInMillis());
        p.setTimeRelatedValues(cv, startTime, endTime, 0);

        // The original instance time should have hour/min/sec zero'd out
        GregorianCalendar testCalendar = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        testCalendar.set(2010, 5, 17, 0, 0, 0);
        testCalendar.set(GregorianCalendar.MILLISECOND, 0);
        Long testTime = testCalendar.getTimeInMillis();
        assertEquals(testTime, cv.getAsLong(Events.ORIGINAL_INSTANCE_TIME));

        // The exception isn't all-day, so we should have DTEND and LAST_DATE and no EVENT_TIMEZONE
        assertNull(cv.getAsString(Events.DURATION));
        assertEquals(endTime, cv.getAsLong(Events.DTEND));
        assertEquals(endTime, cv.getAsLong(Events.LAST_DATE));
        assertNull(cv.getAsString(Events.EVENT_TIMEZONE));
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
        assertFalse(p.isValidEventValues(cv));
        cv.put(Events._SYNC_DATA, validData);
        // Needs DTEND/DURATION since not an exception
        assertFalse(p.isValidEventValues(cv));
        cv.put(Events.DURATION, validDuration);
        // Valid (DTSTART, _SYNC_DATA, DURATION)
        assertTrue(p.isValidEventValues(cv));
        cv.remove(Events.DURATION);
        cv.put(Events.ORIGINAL_INSTANCE_TIME, validTime);
        // Needs DTEND since it's an exception
        assertFalse(p.isValidEventValues(cv));
        cv.put(Events.DTEND, validTime);
        // Valid (DTSTART, DTEND, ORIGINAL_INSTANCE_TIME)
        cv.remove(Events.ORIGINAL_INSTANCE_TIME);
        // Valid (DTSTART, _SYNC_DATA, DTEND)
        assertTrue(p.isValidEventValues(cv));
        cv.remove(Events.DTSTART);
        // Needs DTSTART
        assertFalse(p.isValidEventValues(cv));
        cv.put(Events.DTSTART, validTime);
        cv.put(Events.RRULE, validRrule);
        // With RRULE, needs DURATION
        assertFalse(p.isValidEventValues(cv));
        cv.put(Events.DURATION, "P30M");
        // Valid (DTSTART, RRULE, DURATION)
        assertTrue(p.isValidEventValues(cv));
        cv.put(Events.ALL_DAY, "1");
        // Needs DURATION in the form P<n>D
        assertFalse(p.isValidEventValues(cv));
        // Valid (DTSTART, RRULE, ALL_DAY, DURATION(P<n>D)
        cv.put(Events.DURATION, "P1D");
        assertTrue(p.isValidEventValues(cv));
    }
}
