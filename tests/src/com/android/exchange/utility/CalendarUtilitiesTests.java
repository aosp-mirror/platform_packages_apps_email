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

import com.android.email.R;
import com.android.email.Utility;
import com.android.email.mail.Address;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Message;

import android.content.ContentValues;
import android.content.Entity;
import android.content.res.Resources;
import android.provider.Calendar.Attendees;
import android.provider.Calendar.Events;
import android.test.AndroidTestCase;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
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

    // This time zone has no DST
    private static final String ASIA_CALCUTTA_TIME =
        "tv7//0kAbgBkAGkAYQAgAFMAdABhAG4AZABhAHIAZAAgAFQAaQBtAGUAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEkAbgBkAGkAYQAgAEQAYQB5AGwAaQBnAGgAdAAgAFQAaQBtAGUA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";

    // This time zone is equivalent to PST and uses DST
    private static final String AMERICA_DAWSON_TIME =
        "4AEAAFAAYQBjAGkAZgBpAGMAIABTAHQAYQBuAGQAYQByAGQAIABUAGkAbQBlAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAsAAAABAAIAAAAAAAAAAAAAAFAAYQBjAGkAZgBpAGMAIABEAGEAeQBsAGkAZwBoAHQAIABUAGkA" +
        "bQBlAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMAAAACAAIAAAAAAAAAxP///w==";

    // Test a southern hemisphere time zone w/ DST
    private static final String AUSTRALIA_ACT_TIME =
        "qP3//0EAVQBTACAARQBhAHMAdABlAHIAbgAgAFMAdABhAG4AZABhAHIAZAAgAFQAaQBtAGUAAAAAAAAAAAAA" +
        "AAAAAAAAAAQAAAABAAMAAAAAAAAAAAAAAEEAVQBTACAARQBhAHMAdABlAHIAbgAgAEQAYQB5AGwAaQBnAGgA" +
        "dAAgAFQAaQBtAGUAAAAAAAAAAAAAAAAAAAAAAAoAAAABAAIAAAAAAAAAxP///w==";

    // Test a european time zone w/ DST
    private static final String EUROPE_MOSCOW_TIME =
        "TP///1IAdQBzAHMAaQBhAG4AIABTAHQAYQBuAGQAYQByAGQAIABUAGkAbQBlAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAoAAAAFAAMAAAAAAAAAAAAAAFIAdQBzAHMAaQBhAG4AIABEAGEAeQBsAGkAZwBoAHQAIABUAGkA" +
        "bQBlAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAMAAAAFAAIAAAAAAAAAxP///w==";

    // Test a timezone with GMT bias but bogus DST parameters (there is no equivalent time zone
    // in the database)
    private static final String GMT_UNKNOWN_DAYLIGHT_TIME =
        "AAAAACgARwBNAFQAKwAwADAAOgAwADAAKQAgAFQAaQBtAGUAIABaAG8AbgBlAAAAAAAAAAAAAAAAAAAAAAAA" +
        "AAAAAAAAAAEAAAABAAAAAAAAAAAAAAAAACgARwBNAFQAKwAwADAAOgAwADAAKQAgAFQAaQBtAGUAIABaAG8A" +
        "bgBlAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAoAAAAFAAEAAAAAAAAAxP///w==";

    // This time zone has no DST, but earlier, buggy code retrieved a TZ WITH DST
    private static final String ARIZONA_TIME =
        "pAEAAFUAUwAgAE0AbwB1AG4AdABhAGkAbgAgAFMAdABhAG4AZABhAHIAZAAgAFQAaQBtAGUAAAAAAAAAAAAA" +
        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAFUAUwAgAE0AbwB1AG4AdABhAGkAbgAgAEQAYQB5AGwAaQBnAGgA" +
        "dAAgAFQAaQBtAGUAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA==";

    private static final String ORGANIZER = "organizer@server.com";
    private static final String ATTENDEE = "attendee@server.com";

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
        TimeZone tz = CalendarUtilities.tziStringToTimeZone(AMERICA_DAWSON_TIME);
        assertEquals("America/Dawson", tz.getID());
        tz = CalendarUtilities.tziStringToTimeZone(ASIA_CALCUTTA_TIME);
        assertEquals("Asia/Calcutta", tz.getID());
        tz = CalendarUtilities.tziStringToTimeZone(AUSTRALIA_ACT_TIME);
        assertEquals("Australia/ACT", tz.getID());
        tz = CalendarUtilities.tziStringToTimeZone(EUROPE_MOSCOW_TIME);
        assertEquals("Europe/Moscow", tz.getID());
        tz = CalendarUtilities.tziStringToTimeZone(GMT_UNKNOWN_DAYLIGHT_TIME);
        int bias = tz.getOffset(System.currentTimeMillis());
        assertEquals(0, bias);
        // Make sure non-DST TZ's work properly
        tz = CalendarUtilities.tziStringToTimeZone(ARIZONA_TIME);
        assertEquals("America/Phoenix", tz.getID());
    }

    public void testGenerateEasDayOfWeek() {
        String byDay = "TU,WE,SA";
        // TU = 4, WE = 8; SA = 64;
        assertEquals("76", CalendarUtilities.generateEasDayOfWeek(byDay));
        // MO = 2, TU = 4; WE = 8; TH = 16; FR = 32
        byDay = "MO,TU,WE,TH,FR";
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
        assertEquals("WE,TH,SA", CalendarUtilities.tokenFromRrule(rrule, "BYDAY="));
        assertNull(CalendarUtilities.tokenFromRrule(rrule, "UNTIL="));
    }

    public void testRecurrenceUntilToEasUntil() {
        // Test full format
        assertEquals("YYYYMMDDT000000Z",
                CalendarUtilities.recurrenceUntilToEasUntil("YYYYMMDDTHHMMSSZ"));
        // Test date only format
        assertEquals("YYYYMMDDT000000Z",
                CalendarUtilities.recurrenceUntilToEasUntil("YYYYMMDD"));
    }

    public void testParseEmailDateTimeToMillis(String date) {
        // Format for email date strings is 2010-02-23T16:00:00.000Z
        String dateString = "2010-02-23T15:16:17.000Z";
        long dateTime = Utility.parseEmailDateTimeToMillis(dateString);
        GregorianCalendar cal = new GregorianCalendar();
        cal.setTimeInMillis(dateTime);
        cal.setTimeZone(TimeZone.getTimeZone("GMT"));
        assertEquals(cal.get(Calendar.YEAR), 2010);
        assertEquals(cal.get(Calendar.MONTH), 1);  // 0 based
        assertEquals(cal.get(Calendar.DAY_OF_MONTH), 23);
        assertEquals(cal.get(Calendar.HOUR_OF_DAY), 16);
        assertEquals(cal.get(Calendar.MINUTE), 16);
        assertEquals(cal.get(Calendar.SECOND), 17);
    }

    public void testParseDateTimeToMillis(String date) {
        // Format for calendar date strings is 20100223T160000000Z
        String dateString = "20100223T151617000Z";
        long dateTime = Utility.parseDateTimeToMillis(dateString);
        GregorianCalendar cal = new GregorianCalendar();
        cal.setTimeInMillis(dateTime);
        cal.setTimeZone(TimeZone.getTimeZone("GMT"));
        assertEquals(cal.get(Calendar.YEAR), 2010);
        assertEquals(cal.get(Calendar.MONTH), 1);  // 0 based
        assertEquals(cal.get(Calendar.DAY_OF_MONTH), 23);
        assertEquals(cal.get(Calendar.HOUR_OF_DAY), 16);
        assertEquals(cal.get(Calendar.MINUTE), 16);
        assertEquals(cal.get(Calendar.SECOND), 17);
    }

    private Entity setupTestEventEntity(String organizer, String attendee, String title) {
        // Create an Entity for an Event
        ContentValues entityValues = new ContentValues();
        Entity entity = new Entity(entityValues);

        // Set up values for the Event
        String location = "Meeting Location";

        // Fill in times, location, title, and organizer
        entityValues.put("DTSTAMP",
                CalendarUtilities.convertEmailDateTimeToCalendarDateTime("2010-04-05T14:30:51Z"));
        entityValues.put(Events.DTSTART,
                Utility.parseEmailDateTimeToMillis("2010-04-12T18:30:00Z"));
        entityValues.put(Events.DTEND,
                Utility.parseEmailDateTimeToMillis("2010-04-12T19:30:00Z"));
        entityValues.put(Events.EVENT_LOCATION, location);
        entityValues.put(Events.TITLE, title);
        entityValues.put(Events.ORGANIZER, organizer);
        entityValues.put(Events._SYNC_DATA, "31415926535");

        // Add the attendee
        ContentValues attendeeValues = new ContentValues();
        attendeeValues.put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE);
        attendeeValues.put(Attendees.ATTENDEE_EMAIL, attendee);
        entity.addSubValue(Attendees.CONTENT_URI, attendeeValues);

        // Add the organizer
        ContentValues organizerValues = new ContentValues();
        organizerValues.put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ORGANIZER);
        organizerValues.put(Attendees.ATTENDEE_EMAIL, organizer);
        entity.addSubValue(Attendees.CONTENT_URI, organizerValues);
        return entity;
    }

    private Entity setupTestExceptionEntity(String organizer, String attendee, String title) {
        Entity entity = setupTestEventEntity(organizer, attendee, title);
        ContentValues entityValues = entity.getEntityValues();
        entityValues.put(Events.ORIGINAL_EVENT, 69);
        // The exception will be on April 26th
        entityValues.put(Events.ORIGINAL_INSTANCE_TIME,
                Utility.parseEmailDateTimeToMillis("2010-04-26T18:30:00Z"));
        return entity;
    }

    public void testCreateMessageForEntity_Reply() {
        // Set up the "event"
        String title = "Discuss Unit Tests";
        Entity entity = setupTestEventEntity(ORGANIZER, ATTENDEE, title);

        // Create a dummy account for the attendee
        Account account = new Account();
        account.mEmailAddress = ATTENDEE;

        // The uid is required, but can be anything
        String uid = "31415926535";

        // Create the outgoing message
        Message msg = CalendarUtilities.createMessageForEntity(mContext, entity,
                Message.FLAG_OUTGOING_MEETING_ACCEPT, uid, account);

        // First, we should have a message
        assertNotNull(msg);

        // Now check some of the fields of the message
        assertEquals(Address.pack(new Address[] {new Address(ORGANIZER)}), msg.mTo);
        Resources resources = getContext().getResources();
        String accept = resources.getString(R.string.meeting_accepted, title);
        assertEquals(accept, msg.mSubject);
        assertNotNull(msg.mText);
        assertTrue(msg.mText.contains(resources.getString(R.string.meeting_where, "")));

        // And make sure we have an attachment
        assertNotNull(msg.mAttachments);
        assertEquals(1, msg.mAttachments.size());
        Attachment att = msg.mAttachments.get(0);
        // And that the attachment has the correct elements
        assertEquals("invite.ics", att.mFileName);
        assertEquals(Attachment.FLAG_ICS_ALTERNATIVE_PART,
                att.mFlags & Attachment.FLAG_ICS_ALTERNATIVE_PART);
        assertEquals("text/calendar; method=REPLY", att.mMimeType);
        assertNotNull(att.mContentBytes);
        assertEquals(att.mSize, att.mContentBytes.length);

        //TODO Check the contents of the attachment using an iCalendar parser
    }

    public void testCreateMessageForEntity_Invite_AllDay() throws IOException {
        // Set up the "event"
        String title = "Discuss Unit Tests";
        Entity entity = setupTestEventEntity(ORGANIZER, ATTENDEE, title);
        entity.getEntityValues().put(Events.ALL_DAY, 1);

        // Create a dummy account for the attendee
        Account account = new Account();
        account.mEmailAddress = ORGANIZER;

        // The uid is required, but can be anything
        String uid = "31415926535";

        // Create the outgoing message
        Message msg = CalendarUtilities.createMessageForEntity(mContext, entity,
                Message.FLAG_OUTGOING_MEETING_INVITE, uid, account);

        // First, we should have a message
        assertNotNull(msg);

        // Now check some of the fields of the message
        assertEquals(Address.pack(new Address[] {new Address(ATTENDEE)}), msg.mTo);
        assertEquals(title, msg.mSubject);

        // And make sure we have an attachment
        assertNotNull(msg.mAttachments);
        assertEquals(1, msg.mAttachments.size());
        Attachment att = msg.mAttachments.get(0);
        // And that the attachment has the correct elements
        assertEquals("invite.ics", att.mFileName);
        assertEquals(Attachment.FLAG_ICS_ALTERNATIVE_PART,
                att.mFlags & Attachment.FLAG_ICS_ALTERNATIVE_PART);
        assertEquals("text/calendar; method=REQUEST", att.mMimeType);
        assertNotNull(att.mContentBytes);
        assertEquals(att.mSize, att.mContentBytes.length);

        // We'll check the contents of the ics file here
        BlockHash vcalendar = parseIcsContent(att.mContentBytes);
        assertNotNull(vcalendar);

        // We should have a VCALENDAR with a REQUEST method
        assertEquals("VCALENDAR", vcalendar.name);
        assertEquals("REQUEST", vcalendar.get("METHOD"));

        // We should have one block under VCALENDAR
        assertEquals(1, vcalendar.blocks.size());
        BlockHash vevent = vcalendar.blocks.get(0);
        // It's a VEVENT with the following fields
        assertEquals("VEVENT", vevent.name);
        assertEquals("Meeting Location", vevent.get("LOCATION"));
        assertEquals("0", vevent.get("SEQUENCE"));
        assertEquals("Discuss Unit Tests", vevent.get("SUMMARY"));
        assertEquals(uid, vevent.get("UID"));
        assertEquals("MAILTO:" + ATTENDEE,
                vevent.get("ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE"));

        // These next two fields should have a date only
        assertEquals("20100412", vevent.get("DTSTART;VALUE=DATE"));
        assertEquals("20100412", vevent.get("DTEND;VALUE=DATE"));
        // This should be set to TRUE for all-day events
        assertEquals("TRUE", vevent.get("X-MICROSOFT-CDO-ALLDAYEVENT"));
    }

    public void testCreateMessageForEntity_Invite() throws IOException {
        // Set up the "event"
        String title = "Discuss Unit Tests";
        Entity entity = setupTestEventEntity(ORGANIZER, ATTENDEE, title);

        // Create a dummy account for the attendee
        Account account = new Account();
        account.mEmailAddress = ORGANIZER;

        // The uid is required, but can be anything
        String uid = "31415926535";

        // Create the outgoing message
        Message msg = CalendarUtilities.createMessageForEntity(mContext, entity,
                Message.FLAG_OUTGOING_MEETING_INVITE, uid, account);

        // First, we should have a message
        assertNotNull(msg);

        // Now check some of the fields of the message
        assertEquals(Address.pack(new Address[] {new Address(ATTENDEE)}), msg.mTo);
        assertEquals(title, msg.mSubject);

        // And make sure we have an attachment
        assertNotNull(msg.mAttachments);
        assertEquals(1, msg.mAttachments.size());
        Attachment att = msg.mAttachments.get(0);
        // And that the attachment has the correct elements
        assertEquals("invite.ics", att.mFileName);
        assertEquals(Attachment.FLAG_ICS_ALTERNATIVE_PART,
                att.mFlags & Attachment.FLAG_ICS_ALTERNATIVE_PART);
        assertEquals("text/calendar; method=REQUEST", att.mMimeType);
        assertNotNull(att.mContentBytes);
        assertEquals(att.mSize, att.mContentBytes.length);

        // We'll check the contents of the ics file here
        BlockHash vcalendar = parseIcsContent(att.mContentBytes);
        assertNotNull(vcalendar);

        // We should have a VCALENDAR with a REQUEST method
        assertEquals("VCALENDAR", vcalendar.name);
        assertEquals("REQUEST", vcalendar.get("METHOD"));

        // We should have one block under VCALENDAR
        assertEquals(1, vcalendar.blocks.size());
        BlockHash vevent = vcalendar.blocks.get(0);
        // It's a VEVENT with the following fields
        assertEquals("VEVENT", vevent.name);
        assertEquals("Meeting Location", vevent.get("LOCATION"));
        assertEquals("0", vevent.get("SEQUENCE"));
        assertEquals("Discuss Unit Tests", vevent.get("SUMMARY"));
        assertEquals(uid, vevent.get("UID"));
        assertEquals("MAILTO:" + ATTENDEE,
                vevent.get("ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE"));

        // These next two fields should exist (without the VALUE=DATE suffix)
        assertNotNull(vevent.get("DTSTART"));
        assertNotNull(vevent.get("DTEND"));
        assertNull(vevent.get("DTSTART;VALUE=DATE"));
        assertNull(vevent.get("DTEND;VALUE=DATE"));
        // This shouldn't exist for this event
        assertNull(vevent.get("X-MICROSOFT-CDO-ALLDAYEVENT"));
    }

    public void testCreateMessageForEntity_Recurring() throws IOException {
        // Set up the "event"
        String title = "Discuss Unit Tests";
        Entity entity = setupTestEventEntity(ORGANIZER, ATTENDEE, title);
        // Set up a RRULE for this event
        entity.getEntityValues().put(Events.RRULE, "FREQ=DAILY");

        // Create a dummy account for the attendee
        Account account = new Account();
        account.mEmailAddress = ORGANIZER;

        // The uid is required, but can be anything
        String uid = "31415926535";

        // Create the outgoing message
        Message msg = CalendarUtilities.createMessageForEntity(mContext, entity,
                Message.FLAG_OUTGOING_MEETING_INVITE, uid, account);

        // First, we should have a message
        assertNotNull(msg);

        // Now check some of the fields of the message
        assertEquals(Address.pack(new Address[] {new Address(ATTENDEE)}), msg.mTo);
        assertEquals(title, msg.mSubject);

        // And make sure we have an attachment
        assertNotNull(msg.mAttachments);
        assertEquals(1, msg.mAttachments.size());
        Attachment att = msg.mAttachments.get(0);
        // And that the attachment has the correct elements
        assertEquals("invite.ics", att.mFileName);
        assertEquals(Attachment.FLAG_ICS_ALTERNATIVE_PART,
                att.mFlags & Attachment.FLAG_ICS_ALTERNATIVE_PART);
        assertEquals("text/calendar; method=REQUEST", att.mMimeType);
        assertNotNull(att.mContentBytes);
        assertEquals(att.mSize, att.mContentBytes.length);

        // We'll check the contents of the ics file here
        BlockHash vcalendar = parseIcsContent(att.mContentBytes);
        assertNotNull(vcalendar);

        // We should have a VCALENDAR with a REQUEST method
        assertEquals("VCALENDAR", vcalendar.name);
        assertEquals("REQUEST", vcalendar.get("METHOD"));

        // We should have two blocks under VCALENDAR (VTIMEZONE and VEVENT)
        assertEquals(2, vcalendar.blocks.size());

        // This is the time zone that should be used
        TimeZone timeZone = TimeZone.getDefault();

        BlockHash vtimezone = vcalendar.blocks.get(0);
        // It should be a VTIMEZONE for timeZone
        assertEquals("VTIMEZONE", vtimezone.name);
        assertEquals(timeZone.getID(), vtimezone.get("TZID"));

        BlockHash vevent = vcalendar.blocks.get(1);
        // It's a VEVENT with the following fields
        assertEquals("VEVENT", vevent.name);
        assertEquals("Meeting Location", vevent.get("LOCATION"));
        assertEquals("0", vevent.get("SEQUENCE"));
        assertEquals("Discuss Unit Tests", vevent.get("SUMMARY"));
        assertEquals(uid, vevent.get("UID"));
        assertEquals("MAILTO:" + ATTENDEE,
                vevent.get("ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE"));

        // We should have DTSTART/DTEND with time zone
        assertNotNull(vevent.get("DTSTART;TZID=" + timeZone.getID()));
        assertNotNull(vevent.get("DTEND;TZID=" + timeZone.getID()));
        assertNull(vevent.get("DTSTART"));
        assertNull(vevent.get("DTEND"));
        assertNull(vevent.get("DTSTART;VALUE=DATE"));
        assertNull(vevent.get("DTEND;VALUE=DATE"));
        // This shouldn't exist for this event
        assertNull(vevent.get("X-MICROSOFT-CDO-ALLDAYEVENT"));
    }

    public void testCreateMessageForEntity_Exception_Cancel() throws IOException {
        // Set up the "exception"...
        String title = "Discuss Unit Tests";
        Entity entity = setupTestExceptionEntity(ORGANIZER, ATTENDEE, title);
        
        ContentValues entityValues = entity.getEntityValues();
        // Mark the Exception as dirty
        entityValues.put(Events._SYNC_DIRTY, 1);
        // And mark it canceled
        entityValues.put(Events.STATUS, Events.STATUS_CANCELED);

        // Create a dummy account for the attendee
        Account account = new Account();
        account.mEmailAddress = ORGANIZER;

        // The uid is required, but can be anything
        String uid = "31415926535";

        // Create the outgoing message
        Message msg = CalendarUtilities.createMessageForEntity(mContext, entity,
                Message.FLAG_OUTGOING_MEETING_CANCEL, uid, account);

        // First, we should have a message
        assertNotNull(msg);

        // Now check some of the fields of the message
        assertEquals(Address.pack(new Address[] {new Address(ATTENDEE)}), msg.mTo);
        String cancel = getContext().getResources().getString(R.string.meeting_canceled, title);
        assertEquals(cancel, msg.mSubject);

        // And make sure we have an attachment
        assertNotNull(msg.mAttachments);
        assertEquals(1, msg.mAttachments.size());
        Attachment att = msg.mAttachments.get(0);
        // And that the attachment has the correct elements
        assertEquals("invite.ics", att.mFileName);
        assertEquals(Attachment.FLAG_ICS_ALTERNATIVE_PART,
                att.mFlags & Attachment.FLAG_ICS_ALTERNATIVE_PART);
        assertEquals("text/calendar; method=CANCEL", att.mMimeType);
        assertNotNull(att.mContentBytes);

        // We'll check the contents of the ics file here
        BlockHash vcalendar = parseIcsContent(att.mContentBytes);
        assertNotNull(vcalendar);

        // We should have a VCALENDAR with a CANCEL method
        assertEquals("VCALENDAR", vcalendar.name);
        assertEquals("CANCEL", vcalendar.get("METHOD"));

        // This is the time zone that should be used
        TimeZone timeZone = TimeZone.getDefault();

        // We should have two blocks under VCALENDAR (VTIMEZONE and VEVENT)
        assertEquals(2, vcalendar.blocks.size());

        BlockHash vtimezone = vcalendar.blocks.get(0);
        // It should be a VTIMEZONE for timeZone
        assertEquals("VTIMEZONE", vtimezone.name);
        assertEquals(timeZone.getID(), vtimezone.get("TZID"));

        BlockHash vevent = vcalendar.blocks.get(1);
        // It's a VEVENT with the following fields
        assertEquals("VEVENT", vevent.name);
        assertEquals("Meeting Location", vevent.get("LOCATION"));
        assertEquals("0", vevent.get("SEQUENCE"));
        assertEquals("Discuss Unit Tests", vevent.get("SUMMARY"));
        assertEquals(uid, vevent.get("UID"));
        assertEquals("MAILTO:" + ATTENDEE,
                vevent.get("ATTENDEE;ROLE=REQ-PARTICIPANT"));
        long originalTime = entityValues.getAsLong(Events.ORIGINAL_INSTANCE_TIME);
        assertNotSame(0, originalTime);
        // For an exception, RECURRENCE-ID is critical
        assertEquals(CalendarUtilities.millisToEasDateTime(originalTime, timeZone,
                true /*withTime*/), vevent.get("RECURRENCE-ID" + ";TZID=" + timeZone.getID()));
    }

    public void testUtcOffsetString() {
        assertEquals(CalendarUtilities.utcOffsetString(540), "+0900");
        assertEquals(CalendarUtilities.utcOffsetString(-480), "-0800");
        assertEquals(CalendarUtilities.utcOffsetString(0), "+0000");
    }

    public void testFindTransitionDate() {
        // We'll find some transitions and make sure that we're properly in or out of daylight time
        // on either side of the transition.
        // Use CST for testing (any other will do as well, as long as it has DST)
        TimeZone tz = TimeZone.getTimeZone("US/Central");
        // Get a calendar at January 1st of the current year
        GregorianCalendar calendar = new GregorianCalendar(tz);
        calendar.set(CalendarUtilities.sCurrentYear, Calendar.JANUARY, 1);
        // Get start and end times at start and end of year
        long startTime = calendar.getTimeInMillis();
        long endTime = startTime + (365*CalendarUtilities.DAYS);
        // Find the first transition
        GregorianCalendar transitionCalendar =
            CalendarUtilities.findTransitionDate(tz, startTime, endTime, false);
        long transitionTime = transitionCalendar.getTimeInMillis();
        // Before should be in standard time; after in daylight time
        Date beforeDate = new Date(transitionTime - CalendarUtilities.HOURS);
        Date afterDate = new Date(transitionTime + CalendarUtilities.HOURS);
        assertFalse(tz.inDaylightTime(beforeDate));
        assertTrue(tz.inDaylightTime(afterDate));

        // Find the next one...
        transitionCalendar = CalendarUtilities.findTransitionDate(tz, transitionTime +
                CalendarUtilities.DAYS, endTime, true);
        transitionTime = transitionCalendar.getTimeInMillis();
        // This time, Before should be in daylight time; after in standard time
        beforeDate = new Date(transitionTime - CalendarUtilities.HOURS);
        afterDate = new Date(transitionTime + CalendarUtilities.HOURS);
        assertTrue(tz.inDaylightTime(beforeDate));
        assertFalse(tz.inDaylightTime(afterDate));

        // Captain Renault: What in heaven's name brought you to Casablanca?
        // Rick: My health. I came to Casablanca for the waters.
        // Also, they have no daylight savings time
        tz = TimeZone.getTimeZone("Africa/Casablanca");
        // Get a calendar at January 1st of the current year
        calendar = new GregorianCalendar(tz);
        calendar.set(CalendarUtilities.sCurrentYear, Calendar.JANUARY, 1);
        // Get start and end times at start and end of year
        startTime = calendar.getTimeInMillis();
        endTime = startTime + (365*CalendarUtilities.DAYS);
        // Find the first transition
        transitionCalendar = CalendarUtilities.findTransitionDate(tz, startTime, endTime, false);
        // There had better not be one
        assertNull(transitionCalendar);
    }

    public void testRruleFromRecurrence() {
        // Every Monday for 2 weeks
        String rrule = CalendarUtilities.rruleFromRecurrence(
                1 /*Weekly*/, 2 /*Occurrences*/, 1 /*Interval*/, 2 /*Monday*/, 0, 0, 0, null);
        assertEquals("FREQ=WEEKLY;INTERVAL=1;COUNT=2;BYDAY=MO", rrule);
        // Every Tuesday and Friday
        rrule = CalendarUtilities.rruleFromRecurrence(
                1 /*Weekly*/, 0 /*Occurrences*/, 0 /*Interval*/, 36 /*Tue&Fri*/, 0, 0, 0, null);
        assertEquals("FREQ=WEEKLY;BYDAY=TU,FR", rrule);
        // The last Saturday of the month
        rrule = CalendarUtilities.rruleFromRecurrence(
                3 /*Monthly/DayofWeek*/, 0, 0, 64 /*Sat*/, 0, 5 /*Last*/, 0, null);
        assertEquals("FREQ=MONTHLY;BYDAY=-1SA", rrule);
        // The third Wednesday and Thursday of the month
        rrule = CalendarUtilities.rruleFromRecurrence(
                3 /*Monthly/DayofWeek*/, 0, 0, 24 /*Wed&Thu*/, 0, 3 /*3rd*/, 0, null);
        assertEquals("FREQ=MONTHLY;BYDAY=3WE,3TH", rrule);
        // The 14th of the every month
        rrule = CalendarUtilities.rruleFromRecurrence(
                2 /*Monthly/Date*/, 0, 0, 0, 14 /*14th*/, 0, 0, null);
        assertEquals("FREQ=MONTHLY;BYMONTHDAY=14", rrule);
        // Every 31st of October
        rrule = CalendarUtilities.rruleFromRecurrence(
                5 /*Yearly/Date*/, 0, 0, 0, 31 /*31st*/, 0, 10 /*October*/, null);
        assertEquals("FREQ=YEARLY;BYMONTHDAY=31;BYMONTH=10", rrule);
        // The first Tuesday of June
        rrule = CalendarUtilities.rruleFromRecurrence(
                6 /*Yearly/Month/DayOfWeek*/, 0, 0, 4 /*Tue*/, 0, 1 /*1st*/, 6 /*June*/, null);
        assertEquals("FREQ=YEARLY;BYDAY=1TU;BYMONTH=6", rrule);
    }

    /**
     * For debugging purposes, to help keep track of parsing errors.
     */
    private class UnterminatedBlockException extends IOException {
        private static final long serialVersionUID = 1L;
        UnterminatedBlockException(String name) {
            super(name);
        }
    }

    /**
     * A lightweight representation of block object containing a hash of individual values and an
     * array of inner blocks.  The object is build by pulling elements from a BufferedReader.
     * NOTE: Multiple values of a given field are not supported.  We'd see this with ATTENDEEs, for
     * example, and possibly RDATEs in VTIMEZONEs without an RRULE; these cases will be handled
     * at a later time.
     */
    private class BlockHash {
        String name;
        HashMap<String, String> hash = new HashMap<String, String>();
        ArrayList<BlockHash> blocks = new ArrayList<BlockHash>();

        BlockHash (String _name, BufferedReader reader) throws IOException {
            name = _name;
            String lastField = null;
            String lastValue = null;
            while (true) {
                // Get a line; we're done if it's null
                String line = reader.readLine();
                if (line == null) {
                    throw new UnterminatedBlockException(name);
                }
                int length = line.length();
                if (length == 0) {
                    // We shouldn't ever see an empty line
                    throw new IllegalArgumentException();
                }
                // A line starting with tab is a continuation
                if (line.charAt(0) == '\t') {
                    // Remember the line and length
                    lastValue = line.substring(1);
                    // Save the concatenation of old and new values
                    hash.put(lastField, hash.get(lastField) + lastValue);
                    continue;
                }
                // Find the field delimiter
                int pos = line.indexOf(':');
                // If not found, or at EOL, this is a bad ics
                if (pos < 0 || pos >= length) {
                    throw new IllegalArgumentException();
                }
                // Remember the field, value, and length
                lastField = line.substring(0, pos);
                lastValue = line.substring(pos + 1);
                if (lastField.equals("BEGIN")) {
                    blocks.add(new BlockHash(lastValue, reader));
                    continue;
                } else if (lastField.equals("END")) {
                    if (!lastValue.equals(name)) {
                        throw new UnterminatedBlockException(name);
                    }
                    break;
                }

                // Save it away and continue
                hash.put(lastField, lastValue);
            }
        }

        String get(String field) {
            return hash.get(field);
        }
    }

    private BlockHash parseIcsContent(byte[] bytes) throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader(Utility.fromUtf8(bytes)));
        String line = reader.readLine();
        if (!line.equals("BEGIN:VCALENDAR")) {
            throw new IllegalArgumentException();
        }
        return new BlockHash("VCALENDAR", reader);
    }

    public void testBuildMessageTextFromEntityValues() {
        // Set up a test event
        String title = "Event Title";
        Entity entity = setupTestEventEntity(ORGANIZER, ATTENDEE, title);
        ContentValues entityValues = entity.getEntityValues();

        // Save this away; we'll use it a few times below
        Resources resources = mContext.getResources();
        Date date = new Date(entityValues.getAsLong(Events.DTSTART));
        String dateTimeString = DateFormat.getDateTimeInstance().format(date);

        // Get the text for this message
        StringBuilder sb = new StringBuilder();
        CalendarUtilities.buildMessageTextFromEntityValues(mContext, entityValues, sb);
        String text = sb.toString();
        // We'll just check the when and where
        assertTrue(text.contains(resources.getString(R.string.meeting_when, dateTimeString)));
        String location = entityValues.getAsString(Events.EVENT_LOCATION);
        assertTrue(text.contains(resources.getString(R.string.meeting_where, location)));

        // Make this event recurring
        entity.getEntityValues().put(Events.RRULE, "FREQ=WEEKLY;BYDAY=MO");
        sb = new StringBuilder();
        CalendarUtilities.buildMessageTextFromEntityValues(mContext, entityValues, sb);
        text = sb.toString();
        assertTrue(text.contains(resources.getString(R.string.meeting_recurring, dateTimeString)));
    }

    /**
     * Sanity test for time zone generation.  Most important, make sure that we can run through
     * all of the time zones without generating an exception.  Second, make sure that we're finding
     * rules for at least 90% of time zones that use daylight time (empirically, it's more like
     * 95%).  Log those without rules.
     * @throws IOException
     */
    public void testTimeZoneToVTimezone() throws IOException {
        SimpleIcsWriter writer = new SimpleIcsWriter();
        int rule = 0;
        int nodst = 0;
        int norule = 0;
        ArrayList<String> norulelist = new ArrayList<String>();
        for (String tzs: TimeZone.getAvailableIDs()) {
            TimeZone tz = TimeZone.getTimeZone(tzs);
            writer = new SimpleIcsWriter();
            CalendarUtilities.timeZoneToVTimezone(tz, writer);
            String vc = writer.toString();
            boolean hasRule = vc.indexOf("RRULE") > 0;
            if (hasRule) {
                rule++;
            } else if (tz.useDaylightTime()) {
                norule++;
                norulelist.add(tz.getID());
            } else {
                nodst++;
            }
        }
        assertTrue(norule < rule/10);
        Log.d("TimeZoneGeneration",
                "Rule: " + rule + ", No DST: " + nodst + ", No rule: " + norule);
        for (String nr: norulelist) {
            Log.d("TimeZoneGeneration", "No rule: " + nr);
        }
    }

    public void testGetUidFromGlobalObjId() {
        // This is a "foreign" uid (from some vCalendar client)
        String globalObjId = "BAAAAIIA4AB0xbcQGoLgCAAAAAAAAAAAAAAAAAAAAAAAAAAAMQAAA" +
                "HZDYWwtVWlkAQAAADI3NjU1NmRkLTg1MzAtNGZiZS1iMzE0LThiM2JlYTYwMjE0OQA=";
        String uid = CalendarUtilities.getUidFromGlobalObjId(globalObjId);
        assertEquals(uid, "276556dd-8530-4fbe-b314-8b3bea602149");
        // This is a native EAS uid
        globalObjId =
            "BAAAAIIA4AB0xbcQGoLgCAAAAADACTu7KbPKAQAAAAAAAAAAEAAAAObgsG6HVt1Fmy+7GlLbGhY=";
        uid = CalendarUtilities.getUidFromGlobalObjId(globalObjId);
        assertEquals(uid, "040000008200E00074C5B7101A82E00800000000C0093BBB29B3CA" +
                "01000000000000000010000000E6E0B06E8756DD459B2FBB1A52DB1A16");
    }

    public void testSelfAttendeeStatusFromBusyStatus() {
        assertEquals(Attendees.ATTENDEE_STATUS_ACCEPTED,
                CalendarUtilities.attendeeStatusFromBusyStatus(
                        CalendarUtilities.BUSY_STATUS_BUSY));
        assertEquals(Attendees.ATTENDEE_STATUS_TENTATIVE,
                CalendarUtilities.attendeeStatusFromBusyStatus(
                        CalendarUtilities.BUSY_STATUS_TENTATIVE));
        assertEquals(Attendees.ATTENDEE_STATUS_NONE,
                CalendarUtilities.attendeeStatusFromBusyStatus(
                        CalendarUtilities.BUSY_STATUS_FREE));
        assertEquals(Attendees.ATTENDEE_STATUS_NONE,
                CalendarUtilities.attendeeStatusFromBusyStatus(
                        CalendarUtilities.BUSY_STATUS_OUT_OF_OFFICE));
    }

    public void testBusyStatusFromSelfStatus() {
        assertEquals(CalendarUtilities.BUSY_STATUS_FREE,
                CalendarUtilities.busyStatusFromAttendeeStatus(
                        Attendees.ATTENDEE_STATUS_DECLINED));
        assertEquals(CalendarUtilities.BUSY_STATUS_FREE,
                CalendarUtilities.busyStatusFromAttendeeStatus(
                        Attendees.ATTENDEE_STATUS_NONE));
        assertEquals(CalendarUtilities.BUSY_STATUS_FREE,
                CalendarUtilities.busyStatusFromAttendeeStatus(
                        Attendees.ATTENDEE_STATUS_INVITED));
        assertEquals(CalendarUtilities.BUSY_STATUS_TENTATIVE,
                CalendarUtilities.busyStatusFromAttendeeStatus(
                        Attendees.ATTENDEE_STATUS_TENTATIVE));
        assertEquals(CalendarUtilities.BUSY_STATUS_BUSY,
                CalendarUtilities.busyStatusFromAttendeeStatus(
                        Attendees.ATTENDEE_STATUS_ACCEPTED));
    }

    public void testGetUtcAllDayCalendarTime() {
        GregorianCalendar correctUtc = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        correctUtc.set(2011, 2, 10, 0, 0, 0);
        long correctUtcTime = correctUtc.getTimeInMillis();

        TimeZone localTimeZone = TimeZone.getTimeZone("GMT-0700");
        GregorianCalendar localCalendar = new GregorianCalendar(localTimeZone);
        localCalendar.set(2011, 2, 10, 12, 23, 34);
        long localTimeMillis = localCalendar.getTimeInMillis();
        long convertedUtcTime =
            CalendarUtilities.getUtcAllDayCalendarTime(localTimeMillis, localTimeZone);
        // Milliseconds aren't zeroed out and may not be the same
        assertEquals(convertedUtcTime/1000, correctUtcTime/1000);

        localTimeZone = TimeZone.getTimeZone("GMT+0700");
        localCalendar = new GregorianCalendar(localTimeZone);
        localCalendar.set(2011, 2, 10, 12, 23, 34);
        localTimeMillis = localCalendar.getTimeInMillis();
        convertedUtcTime =
            CalendarUtilities.getUtcAllDayCalendarTime(localTimeMillis, localTimeZone);
        assertEquals(convertedUtcTime/1000, correctUtcTime/1000);
    }

    public void testGetLocalAllDayCalendarTime() {
        TimeZone utcTimeZone = TimeZone.getTimeZone("UTC");
        TimeZone localTimeZone = TimeZone.getTimeZone("GMT-0700");
        GregorianCalendar correctLocal = new GregorianCalendar(localTimeZone);
        correctLocal.set(2011, 2, 10, 0, 0, 0);
        long correctLocalTime = correctLocal.getTimeInMillis();

        GregorianCalendar utcCalendar = new GregorianCalendar(utcTimeZone);
        utcCalendar.set(2011, 2, 10, 12, 23, 34);
        long utcTimeMillis = utcCalendar.getTimeInMillis();
        long convertedLocalTime =
            CalendarUtilities.getLocalAllDayCalendarTime(utcTimeMillis, localTimeZone);
        // Milliseconds aren't zeroed out and may not be the same
        assertEquals(convertedLocalTime/1000, correctLocalTime/1000);

        localTimeZone = TimeZone.getTimeZone("GMT+0700");
        correctLocal = new GregorianCalendar(localTimeZone);
        correctLocal.set(2011, 2, 10, 0, 0, 0);
        correctLocalTime = correctLocal.getTimeInMillis();

        utcCalendar = new GregorianCalendar(utcTimeZone);
        utcCalendar.set(2011, 2, 10, 12, 23, 34);
        utcTimeMillis = utcCalendar.getTimeInMillis();
        convertedLocalTime =
            CalendarUtilities.getLocalAllDayCalendarTime(utcTimeMillis, localTimeZone);
        // Milliseconds aren't zeroed out and may not be the same
        assertEquals(convertedLocalTime/1000, correctLocalTime/1000);
    }

    public void testGetIntegerValueAsBoolean() {
        ContentValues cv = new ContentValues();
        cv.put("A", 1);
        cv.put("B", 69);
        cv.put("C", 0);
        assertTrue(CalendarUtilities.getIntegerValueAsBoolean(cv, "A"));
        assertTrue(CalendarUtilities.getIntegerValueAsBoolean(cv, "B"));
        assertFalse(CalendarUtilities.getIntegerValueAsBoolean(cv, "C"));
        assertFalse(CalendarUtilities.getIntegerValueAsBoolean(cv, "D"));
    }
}

    // TODO Planned unit tests
    // findNextTransition
    // recurrenceFromRrule
    // timeZoneToTziStringImpl
    // getDSTCalendars
    // millisToVCalendarTime
    // millisToEasDateTime
    // getTrueTransitionMinute
    // getTrueTransitionHour

