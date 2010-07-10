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

import com.android.exchange.adapter.CalendarSyncAdapter.CalendarOperations;
import com.android.exchange.adapter.CalendarSyncAdapter.EasCalendarSyncParser;
import com.android.exchange.provider.MockProvider;

import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.RemoteException;
import android.provider.Calendar.Attendees;
import android.provider.Calendar.Events;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.TimeZone;

/**
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.adapter.CalendarSyncAdapterTests email
 */

public class CalendarSyncAdapterTests extends SyncAdapterTestCase<CalendarSyncAdapter> {
    private static final String[] ATTENDEE_PROJECTION = new String[] {Attendees.ATTENDEE_EMAIL,
            Attendees.ATTENDEE_NAME, Attendees.ATTENDEE_STATUS};
    private static final int ATTENDEE_EMAIL = 0;
    private static final int ATTENDEE_NAME = 1;
    private static final int ATTENDEE_STATUS = 2;

    private static final String SINGLE_ATTENDEE_EMAIL = "attendee@host.com";
    private static final String SINGLE_ATTENDEE_NAME = "Bill Attendee";

    // This is the US/Pacific time zone as a base64-encoded TIME_ZONE_INFORMATION structure, as
    // it would appear coming from an Exchange server
    private static final String TEST_TIME_ZONE = "4AEAAFAAYQBjAGkAZgBpAGMAIABTAHQAYQBuAGQAYQByA" +
        "GQAIABUAGkAbQBlAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAsAAAABAAIAAAAAAAAAAAAAAFAAY" +
        "QBjAGkAZgBpAGMAIABEAGEAeQBsAGkAZwBoAHQAIABUAGkAbQBlAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAMAAAACAAIAAAAAAAAAxP///w==";

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

    private void addAttendeesToSerializer(Serializer s, int num) throws IOException {
        for (int i = 0; i < num; i++) {
            s.start(Tags.CALENDAR_ATTENDEE);
            s.data(Tags.CALENDAR_ATTENDEE_EMAIL, "frederick" + num +
                    ".flintstone@this.that.verylongservername.com");
            s.data(Tags.CALENDAR_ATTENDEE_TYPE, "1");
            s.data(Tags.CALENDAR_ATTENDEE_NAME, "Frederick" + num + " Flintstone, III");
            s.end();
        }
    }

    private void addAttendeeToSerializer(Serializer s, String email, String name)
            throws IOException {
        s.start(Tags.CALENDAR_ATTENDEE);
        s.data(Tags.CALENDAR_ATTENDEE_EMAIL, email);
        s.data(Tags.CALENDAR_ATTENDEE_TYPE, "1");
        s.data(Tags.CALENDAR_ATTENDEE_NAME, name);
        s.end();
    }

    private int countInsertOperationsForTable(CalendarOperations ops, String tableName) {
        int cnt = 0;
        for (ContentProviderOperation op: ops) {
            List<String> segments = op.getUri().getPathSegments();
            if (segments.get(0).equalsIgnoreCase(tableName) &&
                    op.getType() == ContentProviderOperation.TYPE_INSERT) {
                cnt++;
            }
        }
        return cnt;
    }

    class TestEvent extends Serializer {
        CalendarSyncAdapter mAdapter;
        EasCalendarSyncParser mParser;
        Serializer mSerializer;

        TestEvent() throws IOException {
            super(false);
            mAdapter = getTestSyncAdapter(CalendarSyncAdapter.class);
            mParser = mAdapter.new EasCalendarSyncParser(getTestInputStream(), mAdapter);
        }

        void setUserEmailAddress(String addr) {
            mAdapter.mAccount.mEmailAddress = addr;
            mAdapter.mEmailAddress = addr;
        }

        EasCalendarSyncParser getParser() throws IOException {
            // Set up our parser's input and eat the initial tag
            mParser.resetInput(new ByteArrayInputStream(toByteArray()));
            mParser.nextTag(0);
            return mParser;
        }

        // setupPreAttendees and setupPostAttendees initialize calendar data in the order in which
        // they would appear in an actual EAS session.  Between these two calls, we initialize
        // attendee data, which varies between the following tests
        TestEvent setupPreAttendees() throws IOException {
            start(Tags.SYNC_APPLICATION_DATA);
            data(Tags.CALENDAR_TIME_ZONE, TEST_TIME_ZONE);
            data(Tags.CALENDAR_DTSTAMP, "20100518T213156Z");
            data(Tags.CALENDAR_START_TIME, "20100518T220000Z");
            data(Tags.CALENDAR_SUBJECT, "Documentation");
            data(Tags.CALENDAR_UID, "4417556B-27DE-4ECE-B679-A63EFE1F9E85");
            data(Tags.CALENDAR_ORGANIZER_NAME, "Fred Squatibuquitas");
            data(Tags.CALENDAR_ORGANIZER_EMAIL, "fred.squatibuquitas@prettylongdomainname.com");
            return this;
        }

        TestEvent setupPostAttendees()throws IOException {
            data(Tags.CALENDAR_LOCATION, "CR SF 601T2/North Shore Presentation Self Service (16)");
            data(Tags.CALENDAR_END_TIME, "20100518T223000Z");
            start(Tags.BASE_BODY);
            data(Tags.BASE_BODY_PREFERENCE, "1");
            data(Tags.BASE_ESTIMATED_DATA_SIZE, "69105"); // The number is ignored by the parser
            data(Tags.BASE_DATA,
                    "This is the event description; we should probably make it longer");
            end(); // BASE_BODY
            start(Tags.CALENDAR_RECURRENCE);
            data(Tags.CALENDAR_RECURRENCE_TYPE, "1"); // weekly
            data(Tags.CALENDAR_RECURRENCE_INTERVAL, "1");
            data(Tags.CALENDAR_RECURRENCE_OCCURRENCES, "10");
            data(Tags.CALENDAR_RECURRENCE_DAYOFWEEK, "12"); // tue, wed
            data(Tags.CALENDAR_RECURRENCE_UNTIL, "2005-04-14T00:00:00.000Z");
            end();  // CALENDAR_RECURRENCE
            data(Tags.CALENDAR_SENSITIVITY, "0");
            data(Tags.CALENDAR_BUSY_STATUS, "2");
            data(Tags.CALENDAR_ALL_DAY_EVENT, "0");
            data(Tags.CALENDAR_MEETING_STATUS, "3");
            data(Tags.BASE_NATIVE_BODY_TYPE, "3");
            end().done(); // SYNC_APPLICATION_DATA
            return this;
        }
    }

    public void testAddEvent() throws IOException {
        TestEvent event = new TestEvent();
        event.setupPreAttendees();
        event.start(Tags.CALENDAR_ATTENDEES);
        addAttendeesToSerializer(event, 10);
        event.end(); // CALENDAR_ATTENDEES
        event.setupPostAttendees();

        EasCalendarSyncParser p = event.getParser();
        p.addEvent(p.mOps, "1:1", false);
        // There should be 1 event
        assertEquals(1, countInsertOperationsForTable(p.mOps, "events"));
        // Two attendees (organizer and 10 attendees)
        assertEquals(11, countInsertOperationsForTable(p.mOps, "attendees"));
        // dtstamp, meeting status, attendees, attendees redacted, and upsync prohibited
        assertEquals(5, countInsertOperationsForTable(p.mOps, "extendedproperties"));
    }

    public void testAddEventIllegal() throws IOException {
        // We don't send a start time; the event is illegal and nothing should be added
        TestEvent event = new TestEvent();
        event.start(Tags.SYNC_APPLICATION_DATA);
        event.data(Tags.CALENDAR_TIME_ZONE, TEST_TIME_ZONE);
        event.data(Tags.CALENDAR_DTSTAMP, "20100518T213156Z");
        event.data(Tags.CALENDAR_SUBJECT, "Documentation");
        event.data(Tags.CALENDAR_UID, "4417556B-27DE-4ECE-B679-A63EFE1F9E85");
        event.data(Tags.CALENDAR_ORGANIZER_NAME, "Fred Squatibuquitas");
        event.data(Tags.CALENDAR_ORGANIZER_EMAIL, "fred.squatibuquitas@prettylongdomainname.com");
        event.start(Tags.CALENDAR_ATTENDEES);
        addAttendeesToSerializer(event, 10);
        event.end(); // CALENDAR_ATTENDEES
        event.setupPostAttendees();

        EasCalendarSyncParser p = event.getParser();
        p.addEvent(p.mOps, "1:1", false);
        assertEquals(0, countInsertOperationsForTable(p.mOps, "events"));
        assertEquals(0, countInsertOperationsForTable(p.mOps, "attendees"));
        assertEquals(0, countInsertOperationsForTable(p.mOps, "extendedproperties"));
    }

    public void testAddEventRedactedAttendees() throws IOException {
        TestEvent event = new TestEvent();
        event.setupPreAttendees();
        event.start(Tags.CALENDAR_ATTENDEES);
        addAttendeesToSerializer(event, 100);
        event.end(); // CALENDAR_ATTENDEES
        event.setupPostAttendees();

        EasCalendarSyncParser p = event.getParser();
        p.addEvent(p.mOps, "1:1", false);
        // There should be 1 event
        assertEquals(1, countInsertOperationsForTable(p.mOps, "events"));
        // One attendees (organizer; all others are redacted)
        assertEquals(1, countInsertOperationsForTable(p.mOps, "attendees"));
        // dtstamp, meeting status, and attendees redacted
        assertEquals(3, countInsertOperationsForTable(p.mOps, "extendedproperties"));
    }

    /**
     * Setup for the following three tests, which check attendee status of an added event
     * @param userEmail the email address of the user
     * @param update whether or not the event is an update (rather than new)
     * @return a Cursor to the Attendee records added to our MockProvider
     * @throws IOException
     * @throws RemoteException
     * @throws OperationApplicationException
     */
    private Cursor setupAddEventOneAttendee(String userEmail, boolean update)
            throws IOException, RemoteException, OperationApplicationException {
        TestEvent event = new TestEvent();
        event.setupPreAttendees();
        event.start(Tags.CALENDAR_ATTENDEES);
        addAttendeeToSerializer(event, SINGLE_ATTENDEE_EMAIL, SINGLE_ATTENDEE_NAME);
        event.setUserEmailAddress(userEmail);
        event.end(); // CALENDAR_ATTENDEES
        event.setupPostAttendees();

        EasCalendarSyncParser p = event.getParser();
        p.addEvent(p.mOps, "1:1", update);
        // Send the CPO's to the mock provider
        mMockResolver.applyBatch(MockProvider.AUTHORITY, p.mOps);
        return mMockResolver.query(MockProvider.uri(Attendees.CONTENT_URI), ATTENDEE_PROJECTION,
                null, null, null);
    }

    public void testAddEventOneAttendee() throws IOException, RemoteException,
            OperationApplicationException {
        Cursor c = setupAddEventOneAttendee("foo@bar.com", false);
        assertEquals(2, c.getCount());
        // The organizer should be "accepted", the unknown attendee "none"
        while (c.moveToNext()) {
            if (SINGLE_ATTENDEE_EMAIL.equals(c.getString(ATTENDEE_EMAIL))) {
                assertEquals(Attendees.ATTENDEE_STATUS_NONE, c.getInt(ATTENDEE_STATUS));
            } else {
                assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED, c.getInt(ATTENDEE_STATUS));
            }
        }
    }

    public void testAddEventSelfAttendee() throws IOException, RemoteException,
            OperationApplicationException {
        Cursor c = setupAddEventOneAttendee(SINGLE_ATTENDEE_EMAIL, false);
        // The organizer should be "accepted", and our user/attendee should be "done" even though
        // the busy status = 2 (because we can't tell from a status of 2 on new events)
        while (c.moveToNext()) {
            if (SINGLE_ATTENDEE_EMAIL.equals(c.getString(ATTENDEE_EMAIL))) {
                assertEquals(Attendees.ATTENDEE_STATUS_NONE, c.getInt(ATTENDEE_STATUS));
            } else {
                assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED, c.getInt(ATTENDEE_STATUS));
            }
        }
    }

    public void testAddEventSelfAttendeeUpdate() throws IOException, RemoteException,
            OperationApplicationException {
        Cursor c = setupAddEventOneAttendee(SINGLE_ATTENDEE_EMAIL, true);
        // The organizer should be "accepted", and our user/attendee should be "accepted" (because
        // busy status = 2 and this is an update
        while (c.moveToNext()) {
            if (SINGLE_ATTENDEE_EMAIL.equals(c.getString(ATTENDEE_EMAIL))) {
                assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED, c.getInt(ATTENDEE_STATUS));
            } else {
                assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED, c.getInt(ATTENDEE_STATUS));
            }
        }
    }
}
