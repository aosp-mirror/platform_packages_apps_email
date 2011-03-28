/*
 * Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

import com.android.email.Email;
import com.android.email.Utility;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.exchange.Eas;
import com.android.exchange.EasOutboxService;
import com.android.exchange.EasSyncService;
import com.android.exchange.SyncManager;
import com.android.exchange.utility.CalendarUtilities;
import com.android.exchange.utility.Duration;

import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Entity;
import android.content.Entity.NamedContentValues;
import android.content.EntityIterator;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.Calendar;
import android.provider.Calendar.Attendees;
import android.provider.Calendar.Calendars;
import android.provider.Calendar.Events;
import android.provider.Calendar.EventsEntity;
import android.provider.Calendar.ExtendedProperties;
import android.provider.Calendar.Reminders;
import android.provider.Calendar.SyncState;
import android.provider.ContactsContract.RawContacts;
import android.provider.SyncStateContract;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.Map.Entry;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Sync adapter class for EAS calendars
 *
 */
public class CalendarSyncAdapter extends AbstractSyncAdapter {

    private static final String TAG = "EasCalendarSyncAdapter";
    // Since exceptions will have the same _SYNC_ID as the original event we have to check that
    // there's no original event when finding an item by _SYNC_ID
    private static final String SERVER_ID_AND_CALENDAR_ID = Events._SYNC_ID + "=? AND " +
        Events.ORIGINAL_EVENT + " ISNULL AND " + Events.CALENDAR_ID + "=?";
    private static final String DIRTY_OR_MARKED_TOP_LEVEL_IN_CALENDAR =
        "(" + Events._SYNC_DIRTY + "=1 OR " + Events._SYNC_MARK + "= 1) AND " +
        Events.ORIGINAL_EVENT + " ISNULL AND " + Events.CALENDAR_ID + "=?";
    private static final String DIRTY_EXCEPTION_IN_CALENDAR =
        Events._SYNC_DIRTY + "=1 AND " + Events.ORIGINAL_EVENT + " NOTNULL AND " +
        Events.CALENDAR_ID + "=?";
    private static final String CLIENT_ID_SELECTION = Events._SYNC_DATA + "=?";
    private static final String ORIGINAL_EVENT_AND_CALENDAR =
        Events.ORIGINAL_EVENT + "=? AND " + Events.CALENDAR_ID + "=?";
    private static final String ATTENDEES_EXCEPT_ORGANIZER = Attendees.EVENT_ID + "=? AND " +
        Attendees.ATTENDEE_RELATIONSHIP + "!=" + Attendees.RELATIONSHIP_ORGANIZER;
    private static final String[] ID_PROJECTION = new String[] {Events._ID};
    private static final String[] ORIGINAL_EVENT_PROJECTION =
        new String[] {Events.ORIGINAL_EVENT, Events._ID};
    private static final String EVENT_ID_AND_NAME =
        ExtendedProperties.EVENT_ID + "=? AND " + ExtendedProperties.NAME + "=?";

    // Note that we use LIKE below for its case insensitivity
    private static final String EVENT_AND_EMAIL  =
        Attendees.EVENT_ID + "=? AND "+ Attendees.ATTENDEE_EMAIL + " LIKE ?";
    private static final int ATTENDEE_STATUS_COLUMN_STATUS = 0;
    private static final String[] ATTENDEE_STATUS_PROJECTION =
        new String[] {Attendees.ATTENDEE_STATUS};

    public static final String CALENDAR_SELECTION =
        Calendars._SYNC_ACCOUNT + "=? AND " + Calendars._SYNC_ACCOUNT_TYPE + "=?";
    private static final int CALENDAR_SELECTION_ID = 0;

    private static final String[] EXTENDED_PROPERTY_PROJECTION =
        new String[] {ExtendedProperties._ID};
    private static final int EXTENDED_PROPERTY_ID = 0;

    private static final String CATEGORY_TOKENIZER_DELIMITER = "\\";
    private static final String ATTENDEE_TOKENIZER_DELIMITER = CATEGORY_TOKENIZER_DELIMITER;

    private static final String EXTENDED_PROPERTY_USER_ATTENDEE_STATUS = "userAttendeeStatus";
    private static final String EXTENDED_PROPERTY_ATTENDEES = "attendees";
    private static final String EXTENDED_PROPERTY_DTSTAMP = "dtstamp";
    private static final String EXTENDED_PROPERTY_MEETING_STATUS = "meeting_status";
    private static final String EXTENDED_PROPERTY_CATEGORIES = "categories";
    // Used to indicate that we removed the attendee list because it was too large
    private static final String EXTENDED_PROPERTY_ATTENDEES_REDACTED = "attendeesRedacted";
    // Used to indicate that upsyncs aren't allowed (we catch this in sendLocalChanges)
    private static final String EXTENDED_PROPERTY_UPSYNC_PROHIBITED = "upsyncProhibited";

    private static final ContentProviderOperation PLACEHOLDER_OPERATION =
        ContentProviderOperation.newInsert(Uri.EMPTY).build();

    private static final Uri EVENTS_URI = asSyncAdapter(Events.CONTENT_URI);
    private static final Uri ATTENDEES_URI = asSyncAdapter(Attendees.CONTENT_URI);
    private static final Uri EXTENDED_PROPERTIES_URI =
        asSyncAdapter(ExtendedProperties.CONTENT_URI);
    private static final Uri REMINDERS_URI = asSyncAdapter(Reminders.CONTENT_URI);

    private static final Object sSyncKeyLock = new Object();

    private static final TimeZone UTC_TIMEZONE = TimeZone.getTimeZone("UTC");
    private final TimeZone mLocalTimeZone = TimeZone.getDefault();

    // Change this to use the constant in Calendar, when that constant is defined
    private static final String EVENT_TIMEZONE2_COLUMN = "eventTimezone2";

    // Maximum number of allowed attendees; above this number, we mark the Event with the
    // attendeesRedacted extended property and don't allow the event to be upsynced to the server
    private static final int MAX_SYNCED_ATTENDEES = 50;
    // We set the organizer to this when the user is the organizer and we've redacted the
    // attendee list.  By making the meeting organizer OTHER than the user, we cause the UI to
    // prevent edits to this event (except local changes like reminder).
    private static final String BOGUS_ORGANIZER_EMAIL = "upload_disallowed@uploadisdisallowed.aaa";
    // Maximum number of CPO's before we start redacting attendees in exceptions
    // The number 500 has been determined empirically; 1500 CPOs appears to be the limit before
    // binder failures occur, but we need room at any point for additional events/exceptions so
    // we set our limit at 1/3 of the apparent maximum for extra safety
    // TODO Find a better solution to this workaround
    private static final int MAX_OPS_BEFORE_EXCEPTION_ATTENDEE_REDACTION = 500;

    private long mCalendarId = -1;
    private String mCalendarIdString;
    private String[] mCalendarIdArgument;
    private String mEmailAddress;

    private ArrayList<Long> mDeletedIdList = new ArrayList<Long>();
    private ArrayList<Long> mUploadedIdList = new ArrayList<Long>();
    private ArrayList<Long> mSendCancelIdList = new ArrayList<Long>();
    private ArrayList<Message> mOutgoingMailList = new ArrayList<Message>();

    public CalendarSyncAdapter(Mailbox mailbox, EasSyncService service) {
        super(mailbox, service);
        mEmailAddress = mAccount.mEmailAddress;
        Cursor c = mService.mContentResolver.query(Calendars.CONTENT_URI,
                new String[] {Calendars._ID}, CALENDAR_SELECTION,
                new String[] {mEmailAddress, Email.EXCHANGE_ACCOUNT_MANAGER_TYPE}, null);
        if (c == null) return;
        try {
            if (c.moveToFirst()) {
                mCalendarId = c.getLong(CALENDAR_SELECTION_ID);
            } else {
                mCalendarId = CalendarUtilities.createCalendar(mService, mAccount, mMailbox);
            }
            mCalendarIdString = Long.toString(mCalendarId);
            mCalendarIdArgument = new String[] {mCalendarIdString};
        } finally {
            c.close();
        }
    }

    @Override
    public String getCollectionName() {
        return "Calendar";
    }

    @Override
    public void cleanup() {
    }

    @Override
    public boolean isSyncable() {
        return ContentResolver.getSyncAutomatically(mAccountManagerAccount, Calendar.AUTHORITY);
    }

    @Override
    public boolean parse(InputStream is) throws IOException {
        EasCalendarSyncParser p = new EasCalendarSyncParser(is, this);
        return p.parse();
    }

    static Uri asSyncAdapter(Uri uri) {
        return uri.buildUpon().appendQueryParameter(Calendar.CALLER_IS_SYNCADAPTER, "true").build();
    }

    /**
     * Generate the uri for the data row associated with this NamedContentValues object
     * @param ncv the NamedContentValues object
     * @return a uri that can be used to refer to this row
     */
    public Uri dataUriFromNamedContentValues(NamedContentValues ncv) {
        long id = ncv.values.getAsLong(RawContacts._ID);
        Uri dataUri = ContentUris.withAppendedId(ncv.uri, id);
        return dataUri;
    }

    /**
     * We get our SyncKey from CalendarProvider.  If there's not one, we set it to "0" (the reset
     * state) and save that away.
     */
    @Override
    public String getSyncKey() throws IOException {
        synchronized (sSyncKeyLock) {
            ContentProviderClient client = mService.mContentResolver
                    .acquireContentProviderClient(Calendar.CONTENT_URI);
            try {
                byte[] data = SyncStateContract.Helpers.get(client,
                        asSyncAdapter(Calendar.SyncState.CONTENT_URI), mAccountManagerAccount);
                if (data == null || data.length == 0) {
                    // Initialize the SyncKey
                    setSyncKey("0", false);
                    return "0";
                } else {
                    String syncKey = new String(data);
                    userLog("SyncKey retrieved as ", syncKey, " from CalendarProvider");
                    return syncKey;
                }
            } catch (RemoteException e) {
                throw new IOException("Can't get SyncKey from CalendarProvider");
            }
        }
    }

    /**
     * We only need to set this when we're forced to make the SyncKey "0" (a reset).  In all other
     * cases, the SyncKey is set within Calendar
     */
    @Override
    public void setSyncKey(String syncKey, boolean inCommands) throws IOException {
        synchronized (sSyncKeyLock) {
            if ("0".equals(syncKey) || !inCommands) {
                ContentProviderClient client = mService.mContentResolver
                        .acquireContentProviderClient(Calendar.CONTENT_URI);
                try {
                    SyncStateContract.Helpers.set(client,
                            asSyncAdapter(Calendar.SyncState.CONTENT_URI), mAccountManagerAccount,
                            syncKey.getBytes());
                    userLog("SyncKey set to ", syncKey, " in CalendarProvider");
                } catch (RemoteException e) {
                    throw new IOException("Can't set SyncKey in CalendarProvider");
                }
            }
            mMailbox.mSyncKey = syncKey;
        }
    }

    public class EasCalendarSyncParser extends AbstractSyncParser {

        String[] mBindArgument = new String[1];
        Uri mAccountUri;
        CalendarOperations mOps = new CalendarOperations();

        public EasCalendarSyncParser(InputStream in, CalendarSyncAdapter adapter)
                throws IOException {
            super(in, adapter);
            setLoggingTag("CalendarParser");
            mAccountUri = Events.CONTENT_URI;
        }

        @Override
        public void wipe() {
            // Delete the calendar associated with this account
            // CalendarProvider2 does NOT handle selection arguments in deletions
            mContentResolver.delete(Calendars.CONTENT_URI, Calendars._SYNC_ACCOUNT +
                    "=" + DatabaseUtils.sqlEscapeString(mEmailAddress) + " AND " +
                    Calendars._SYNC_ACCOUNT_TYPE + "=" +
                    DatabaseUtils.sqlEscapeString(Email.EXCHANGE_ACCOUNT_MANAGER_TYPE), null);
            // Invalidate our calendar observers
            SyncManager.unregisterCalendarObservers();
        }

        private void addOrganizerToAttendees(CalendarOperations ops, long eventId,
                String organizerName, String organizerEmail) {
            // Handle the organizer (who IS an attendee on device, but NOT in EAS)
            if (organizerName != null || organizerEmail != null) {
                ContentValues attendeeCv = new ContentValues();
                if (organizerName != null) {
                    attendeeCv.put(Attendees.ATTENDEE_NAME, organizerName);
                }
                if (organizerEmail != null) {
                    attendeeCv.put(Attendees.ATTENDEE_EMAIL, organizerEmail);
                }
                attendeeCv.put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ORGANIZER);
                attendeeCv.put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED);
                attendeeCv.put(Attendees.ATTENDEE_STATUS, Attendees.ATTENDEE_STATUS_ACCEPTED);
                if (eventId < 0) {
                    ops.newAttendee(attendeeCv);
                } else {
                    ops.updatedAttendee(attendeeCv, eventId);
                }
            }
        }

        /**
         * Set DTSTART, DTEND, DURATION and EVENT_TIMEZONE as appropriate for the given Event
         * The follow rules are enforced by CalendarProvider2:
         *   Events that aren't exceptions MUST have either 1) a DTEND or 2) a DURATION
         *   Recurring events (i.e. events with RRULE) must have a DURATION
         *   All-day recurring events MUST have a DURATION that is in the form P<n>D
         *   Other events MAY have a DURATION in any valid form (we use P<n>M)
         *   All-day events MUST have hour, minute, and second = 0; in addition, they must have
         *   the EVENT_TIMEZONE set to UTC
         *   Also, exceptions to all-day events need to have an ORIGINAL_INSTANCE_TIME that has
         *   hour, minute, and second = 0 and be set in UTC
         * @param cv the ContentValues for the Event
         * @param startTime the start time for the Event
         * @param endTime the end time for the Event
         * @param allDayEvent whether this is an all day event (1) or not (0)
         */
        /*package*/ void setTimeRelatedValues(ContentValues cv, long startTime, long endTime,
                int allDayEvent) {
            // If there's no startTime, the event will be found to be invalid, so return
            if (startTime < 0) return;
            // EAS events can arrive without an end time, but CalendarProvider requires them
            // so we'll default to 30 minutes; this will be superceded if this is an all-day event
            if (endTime < 0) endTime = startTime + (30*MINUTES);

            // If this is an all-day event, set hour, minute, and second to zero, and use UTC
            if (allDayEvent != 0) {
                startTime = CalendarUtilities.getUtcAllDayCalendarTime(startTime, mLocalTimeZone);
                endTime = CalendarUtilities.getUtcAllDayCalendarTime(endTime, mLocalTimeZone);
                String originalTimeZone = cv.getAsString(Events.EVENT_TIMEZONE);
                cv.put(EVENT_TIMEZONE2_COLUMN, originalTimeZone);
                cv.put(Events.EVENT_TIMEZONE, UTC_TIMEZONE.getID());
            }

            // If this is an exception, and the original was an all-day event, make sure the
            // original instance time has hour, minute, and second set to zero, and is in UTC
            if (cv.containsKey(Events.ORIGINAL_INSTANCE_TIME) &&
                    cv.containsKey(Events.ORIGINAL_ALL_DAY)) {
                Integer ade = cv.getAsInteger(Events.ORIGINAL_ALL_DAY);
                if (ade != null && ade != 0) {
                    long exceptionTime = cv.getAsLong(Events.ORIGINAL_INSTANCE_TIME);
                    GregorianCalendar cal = new GregorianCalendar(UTC_TIMEZONE);
                    cal.setTimeInMillis(exceptionTime);
                    cal.set(GregorianCalendar.HOUR_OF_DAY, 0);
                    cal.set(GregorianCalendar.MINUTE, 0);
                    cal.set(GregorianCalendar.SECOND, 0);
                    cv.put(Events.ORIGINAL_INSTANCE_TIME, cal.getTimeInMillis());
                }
            }

            // Always set DTSTART
            cv.put(Events.DTSTART, startTime);
            // For recurring events, set DURATION.  Use P<n>D format for all day events
            if (cv.containsKey(Events.RRULE)) {
                if (allDayEvent != 0) {
                    cv.put(Events.DURATION, "P" + ((endTime - startTime) / DAYS) + "D");
                }
                else {
                    cv.put(Events.DURATION, "P" + ((endTime - startTime) / MINUTES) + "M");
                }
            // For other events, set DTEND and LAST_DATE
            } else {
                cv.put(Events.DTEND, endTime);
                cv.put(Events.LAST_DATE, endTime);
            }
        }

        public void addEvent(CalendarOperations ops, String serverId, boolean update)
                throws IOException {
            ContentValues cv = new ContentValues();
            cv.put(Events.CALENDAR_ID, mCalendarId);
            cv.put(Events._SYNC_ACCOUNT, mEmailAddress);
            cv.put(Events._SYNC_ACCOUNT_TYPE, Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            cv.put(Events._SYNC_ID, serverId);
            cv.put(Events.HAS_ATTENDEE_DATA, 1);
            cv.put(Events._SYNC_DATA, "0");

            int allDayEvent = 0;
            String organizerName = null;
            String organizerEmail = null;
            int eventOffset = -1;
            int deleteOffset = -1;
            int busyStatus = CalendarUtilities.BUSY_STATUS_TENTATIVE;

            boolean firstTag = true;
            long eventId = -1;
            long startTime = -1;
            long endTime = -1;
            TimeZone timeZone = null;

            // Keep track of the attendees; exceptions will need them
            ArrayList<ContentValues> attendeeValues = new ArrayList<ContentValues>();
            int reminderMins = -1;
            String dtStamp = null;
            boolean organizerAdded = false;

            while (nextTag(Tags.SYNC_APPLICATION_DATA) != END) {
                if (update && firstTag) {
                    // Find the event that's being updated
                    Cursor c = getServerIdCursor(serverId);
                    long id = -1;
                    try {
                        if (c.moveToFirst()) {
                            id = c.getLong(0);
                        }
                    } finally {
                        c.close();
                    }
                    if (id > 0) {
                        // DTSTAMP can come first, and we simply need to track it
                        if (tag == Tags.CALENDAR_DTSTAMP) {
                            dtStamp = getValue();
                            continue;
                        } else if (tag == Tags.CALENDAR_ATTENDEES) {
                            // This is an attendees-only update; just delete/re-add attendees
                            mBindArgument[0] = Long.toString(id);
                            ops.add(ContentProviderOperation.newDelete(ATTENDEES_URI)
                                    .withSelection(ATTENDEES_EXCEPT_ORGANIZER, mBindArgument)
                                    .build());
                            eventId = id;
                        } else {
                            // Otherwise, delete the original event and recreate it
                            userLog("Changing (delete/add) event ", serverId);
                            deleteOffset = ops.newDelete(id, serverId);
                            // Add a placeholder event so that associated tables can reference
                            // this as a back reference.  We add the event at the end of the method
                            eventOffset = ops.newEvent(PLACEHOLDER_OPERATION);
                        }
                    } else {
                        // The changed item isn't found. We'll treat this as a new item
                        eventOffset = ops.newEvent(PLACEHOLDER_OPERATION);
                        userLog(TAG, "Changed item not found; treating as new.");
                    }
                } else if (firstTag) {
                    // Add a placeholder event so that associated tables can reference
                    // this as a back reference.  We add the event at the end of the method
                   eventOffset = ops.newEvent(PLACEHOLDER_OPERATION);
                }
                firstTag = false;
                switch (tag) {
                    case Tags.CALENDAR_ALL_DAY_EVENT:
                        allDayEvent = getValueInt();
                        if (allDayEvent != 0 && timeZone != null) {
                            // If the event doesn't start at midnight local time, we won't consider
                            // this an all-day event in the local time zone (this is what OWA does)
                            GregorianCalendar cal = new GregorianCalendar(mLocalTimeZone);
                            cal.setTimeInMillis(startTime);
                            userLog("All-day event arrived in: " + timeZone.getID());
                            if (cal.get(GregorianCalendar.HOUR_OF_DAY) != 0 ||
                                    cal.get(GregorianCalendar.MINUTE) != 0) {
                                allDayEvent = 0;
                                userLog("Not an all-day event locally: " + mLocalTimeZone.getID());
                            }
                        }
                        cv.put(Events.ALL_DAY, allDayEvent);
                        break;
                    case Tags.CALENDAR_ATTENDEES:
                        // If eventId >= 0, this is an update; otherwise, a new Event
                        attendeeValues = attendeesParser(ops, eventId);
                        break;
                    case Tags.BASE_BODY:
                        cv.put(Events.DESCRIPTION, bodyParser());
                        break;
                    case Tags.CALENDAR_BODY:
                        cv.put(Events.DESCRIPTION, getValue());
                        break;
                    case Tags.CALENDAR_TIME_ZONE:
                        timeZone = CalendarUtilities.tziStringToTimeZone(getValue());
                        if (timeZone == null) {
                            timeZone = mLocalTimeZone;
                        }
                        cv.put(Events.EVENT_TIMEZONE, timeZone.getID());
                        break;
                    case Tags.CALENDAR_START_TIME:
                        startTime = Utility.parseDateTimeToMillis(getValue());
                        break;
                    case Tags.CALENDAR_END_TIME:
                        endTime = Utility.parseDateTimeToMillis(getValue());
                        break;
                    case Tags.CALENDAR_EXCEPTIONS:
                        // For exceptions to show the organizer, the organizer must be added before
                        // we call exceptionsParser
                        addOrganizerToAttendees(ops, eventId, organizerName, organizerEmail);
                        organizerAdded = true;
                        exceptionsParser(ops, cv, attendeeValues, reminderMins, busyStatus,
                                startTime, endTime);
                        break;
                    case Tags.CALENDAR_LOCATION:
                        cv.put(Events.EVENT_LOCATION, getValue());
                        break;
                    case Tags.CALENDAR_RECURRENCE:
                        String rrule = recurrenceParser(ops);
                        if (rrule != null) {
                            cv.put(Events.RRULE, rrule);
                        }
                        break;
                    case Tags.CALENDAR_ORGANIZER_EMAIL:
                        organizerEmail = getValue();
                        cv.put(Events.ORGANIZER, organizerEmail);
                        break;
                    case Tags.CALENDAR_SUBJECT:
                        cv.put(Events.TITLE, getValue());
                        break;
                    case Tags.CALENDAR_SENSITIVITY:
                        cv.put(Events.VISIBILITY, encodeVisibility(getValueInt()));
                        break;
                    case Tags.CALENDAR_ORGANIZER_NAME:
                        organizerName = getValue();
                        break;
                    case Tags.CALENDAR_REMINDER_MINS_BEFORE:
                        reminderMins = getValueInt();
                        ops.newReminder(reminderMins);
                        cv.put(Events.HAS_ALARM, 1);
                        break;
                    // The following are fields we should save (for changes), though they don't
                    // relate to data used by CalendarProvider at this point
                    case Tags.CALENDAR_UID:
                        cv.put(Events._SYNC_DATA, getValue());
                        break;
                    case Tags.CALENDAR_DTSTAMP:
                        dtStamp = getValue();
                        break;
                    case Tags.CALENDAR_MEETING_STATUS:
                        ops.newExtendedProperty(EXTENDED_PROPERTY_MEETING_STATUS, getValue());
                        break;
                    case Tags.CALENDAR_BUSY_STATUS:
                        // We'll set the user's status in the Attendees table below
                        // Don't set selfAttendeeStatus or CalendarProvider will create a duplicate
                        // attendee!
                        busyStatus = getValueInt();
                        break;
                    case Tags.CALENDAR_CATEGORIES:
                        String categories = categoriesParser(ops);
                        if (categories.length() > 0) {
                            ops.newExtendedProperty(EXTENDED_PROPERTY_CATEGORIES, categories);
                        }
                        break;
                    default:
                        skipTag();
                }
            }

            // Enforce CalendarProvider required properties
            setTimeRelatedValues(cv, startTime, endTime, allDayEvent);

            // If we haven't added the organizer to attendees, do it now
            if (!organizerAdded) {
                addOrganizerToAttendees(ops, eventId, organizerName, organizerEmail);
            }

            // Note that organizerEmail can be null with a DTSTAMP only change from the server
            boolean selfOrganizer = (mEmailAddress.equals(organizerEmail));

            // Store email addresses of attendees (in a tokenizable string) in ExtendedProperties
            // If the user is an attendee, set the attendee status using busyStatus (note that the
            // busyStatus is inherited from the parent unless it's specified in the exception)
            // Add the insert/update operation for each attendee (based on whether it's add/change)
            int numAttendees = attendeeValues.size();
            if (numAttendees > MAX_SYNCED_ATTENDEES) {
                // Indicate that we've redacted attendees.  If we're the organizer, disable edit
                // by setting organizerEmail to a bogus value and by setting the upsync prohibited
                // extended properly.
                // Note that we don't set ANY attendees if we're in this branch; however, the
                // organizer has already been included above, and WILL show up (which is good)
                if (eventId < 0) {
                    ops.newExtendedProperty(EXTENDED_PROPERTY_ATTENDEES_REDACTED, "1");
                    if (selfOrganizer) {
                        ops.newExtendedProperty(EXTENDED_PROPERTY_UPSYNC_PROHIBITED, "1");
                    }
                } else {
                    ops.updatedExtendedProperty(EXTENDED_PROPERTY_ATTENDEES_REDACTED, "1", eventId);
                    if (selfOrganizer) {
                        ops.updatedExtendedProperty(EXTENDED_PROPERTY_UPSYNC_PROHIBITED, "1",
                                eventId);
                    }
                }
                if (selfOrganizer) {
                    organizerEmail = BOGUS_ORGANIZER_EMAIL;
                    cv.put(Events.ORGANIZER, organizerEmail);
                }
                // Tell UI that we don't have any attendees
                cv.put(Events.HAS_ATTENDEE_DATA, "0");
                mService.userLog("Maximum number of attendees exceeded; redacting");
            } else if (numAttendees > 0) {
                StringBuilder sb = new StringBuilder();
                for (ContentValues attendee: attendeeValues) {
                    String attendeeEmail = attendee.getAsString(Attendees.ATTENDEE_EMAIL);
                    sb.append(attendeeEmail);
                    sb.append(ATTENDEE_TOKENIZER_DELIMITER);
                    if (mEmailAddress.equalsIgnoreCase(attendeeEmail)) {
                        // For new events of a non-organizer, we can't tell whether Busy means
                        // accepted or not responded; it's safest to set this to Free (which will be
                        // shown in the UI as "No response"), allowing any setting by the user to
                        // be uploaded and a reply sent to the organizer
                        if (!update && !selfOrganizer &&
                                (busyStatus == CalendarUtilities.BUSY_STATUS_BUSY)) {
                            busyStatus = CalendarUtilities.BUSY_STATUS_FREE;
                        }
                        int attendeeStatus =
                            CalendarUtilities.attendeeStatusFromBusyStatus(busyStatus);
                        attendee.put(Attendees.ATTENDEE_STATUS, attendeeStatus);
                        // If we're an attendee, save away our initial attendee status in the
                        // event's ExtendedProperties (we look for differences between this and
                        // the user's current attendee status to determine whether an email needs
                        // to be sent to the organizer)
                        // organizerEmail will be null in the case that this is an attendees-only
                        // change from the server
                        if (organizerEmail == null ||
                                !organizerEmail.equalsIgnoreCase(attendeeEmail)) {
                            if (eventId < 0) {
                                ops.newExtendedProperty(EXTENDED_PROPERTY_USER_ATTENDEE_STATUS,
                                        Integer.toString(attendeeStatus));
                            } else {
                                ops.updatedExtendedProperty(EXTENDED_PROPERTY_USER_ATTENDEE_STATUS,
                                        Integer.toString(attendeeStatus), eventId);

                            }
                        }
                    }
                    if (eventId < 0) {
                        ops.newAttendee(attendee);
                    } else {
                        ops.updatedAttendee(attendee, eventId);
                    }
                }
                if (eventId < 0) {
                    ops.newExtendedProperty(EXTENDED_PROPERTY_ATTENDEES, sb.toString());
                    ops.newExtendedProperty(EXTENDED_PROPERTY_ATTENDEES_REDACTED, "0");
                    ops.newExtendedProperty(EXTENDED_PROPERTY_UPSYNC_PROHIBITED, "0");
                } else {
                    ops.updatedExtendedProperty(EXTENDED_PROPERTY_ATTENDEES, sb.toString(),
                            eventId);
                    ops.updatedExtendedProperty(EXTENDED_PROPERTY_ATTENDEES_REDACTED, "0", eventId);
                    ops.updatedExtendedProperty(EXTENDED_PROPERTY_UPSYNC_PROHIBITED, "0", eventId);
                }
            }

            // Put the real event in the proper place in the ops ArrayList
            if (eventOffset >= 0) {
                // Store away the DTSTAMP here
                if (dtStamp != null) {
                    ops.newExtendedProperty(EXTENDED_PROPERTY_DTSTAMP, dtStamp);
                }

                if (isValidEventValues(cv)) {
                    ops.set(eventOffset, ContentProviderOperation
                            .newInsert(EVENTS_URI).withValues(cv).build());
                } else {
                    // If we can't add this event (it's invalid), remove all of the inserts
                    // we've built for it
                    int cnt = ops.mCount - eventOffset;
                    userLog(TAG, "Removing " + cnt + " inserts from mOps");
                    for (int i = 0; i < cnt; i++) {
                        ops.remove(eventOffset);
                    }
                    ops.mCount = eventOffset;
                    // If this is a change, we need to also remove the deletion that comes
                    // before the addition
                    if (deleteOffset >= 0) {
                        // Remove the deletion
                        ops.remove(deleteOffset);
                        // And the deletion of exceptions
                        ops.remove(deleteOffset);
                        userLog(TAG, "Removing deletion ops from mOps");
                        ops.mCount = deleteOffset;
                    }
                }
            }
        }

        private void logEventColumns(ContentValues cv, String reason) {
            if (Eas.USER_LOG) {
                StringBuilder sb =
                    new StringBuilder("Event invalid, " + reason + ", skipping: Columns = ");
                for (Entry<String, Object> entry: cv.valueSet()) {
                    sb.append(entry.getKey());
                    sb.append('/');
                }
                userLog(TAG, sb.toString());
            }
        }

        /*package*/ boolean isValidEventValues(ContentValues cv) {
            boolean isException = cv.containsKey(Events.ORIGINAL_INSTANCE_TIME);
            // All events require DTSTART
            if (!cv.containsKey(Events.DTSTART)) {
                logEventColumns(cv, "DTSTART missing");
                return false;
            // If we're a top-level event, we must have _SYNC_DATA (uid)
            } else if (!isException && !cv.containsKey(Events._SYNC_DATA)) {
                logEventColumns(cv, "_SYNC_DATA missing");
                return false;
            // We must also have DTEND or DURATION if we're not an exception
            } else if (!isException && !cv.containsKey(Events.DTEND) &&
                    !cv.containsKey(Events.DURATION)) {
                logEventColumns(cv, "DTEND/DURATION missing");
                return false;
            // Exceptions require DTEND
            } else if (isException && !cv.containsKey(Events.DTEND)) {
                logEventColumns(cv, "Exception missing DTEND");
                return false;
            // If this is a recurrence, we need a DURATION (in days if an all-day event)
            } else if (cv.containsKey(Events.RRULE)) {
                String duration = cv.getAsString(Events.DURATION);
                if (duration == null) return false;
                if (cv.containsKey(Events.ALL_DAY)) {
                    Integer ade = cv.getAsInteger(Events.ALL_DAY);
                    if (ade != null && ade != 0 && !duration.endsWith("D")) {
                        return false;
                    }
                }
            }
            return true;
        }

        private String recurrenceParser(CalendarOperations ops) throws IOException {
            // Turn this information into an RRULE
            int type = -1;
            int occurrences = -1;
            int interval = -1;
            int dow = -1;
            int dom = -1;
            int wom = -1;
            int moy = -1;
            String until = null;

            while (nextTag(Tags.CALENDAR_RECURRENCE) != END) {
                switch (tag) {
                    case Tags.CALENDAR_RECURRENCE_TYPE:
                        type = getValueInt();
                        break;
                    case Tags.CALENDAR_RECURRENCE_INTERVAL:
                        interval = getValueInt();
                        break;
                    case Tags.CALENDAR_RECURRENCE_OCCURRENCES:
                        occurrences = getValueInt();
                        break;
                    case Tags.CALENDAR_RECURRENCE_DAYOFWEEK:
                        dow = getValueInt();
                        break;
                    case Tags.CALENDAR_RECURRENCE_DAYOFMONTH:
                        dom = getValueInt();
                        break;
                    case Tags.CALENDAR_RECURRENCE_WEEKOFMONTH:
                        wom = getValueInt();
                        break;
                    case Tags.CALENDAR_RECURRENCE_MONTHOFYEAR:
                        moy = getValueInt();
                        break;
                    case Tags.CALENDAR_RECURRENCE_UNTIL:
                        until = getValue();
                        break;
                    default:
                       skipTag();
                }
            }

            return CalendarUtilities.rruleFromRecurrence(type, occurrences, interval,
                    dow, dom, wom, moy, until);
        }

        private void exceptionParser(CalendarOperations ops, ContentValues parentCv,
                ArrayList<ContentValues> attendeeValues, int reminderMins, int busyStatus,
                long startTime, long endTime) throws IOException {
            ContentValues cv = new ContentValues();
            cv.put(Events.CALENDAR_ID, mCalendarId);
            cv.put(Events._SYNC_ACCOUNT, mEmailAddress);
            cv.put(Events._SYNC_ACCOUNT_TYPE, Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);

            // It appears that these values have to be copied from the parent if they are to appear
            // Note that they can be overridden below
            cv.put(Events.ORGANIZER, parentCv.getAsString(Events.ORGANIZER));
            cv.put(Events.TITLE, parentCv.getAsString(Events.TITLE));
            cv.put(Events.DESCRIPTION, parentCv.getAsString(Events.DESCRIPTION));
            cv.put(Events.ORIGINAL_ALL_DAY, parentCv.getAsInteger(Events.ALL_DAY));
            cv.put(Events.EVENT_LOCATION, parentCv.getAsString(Events.EVENT_LOCATION));
            cv.put(Events.VISIBILITY, parentCv.getAsString(Events.VISIBILITY));
            cv.put(Events.EVENT_TIMEZONE, parentCv.getAsString(Events.EVENT_TIMEZONE));
            // Exceptions should always have this set to zero, since EAS has no concept of
            // separate attendee lists for exceptions; if we fail to do this, then the UI will
            // allow the user to change attendee data, and this change would never get reflected
            // on the server.
            cv.put(Events.HAS_ATTENDEE_DATA, 0);

            int allDayEvent = 0;

            // This column is the key that links the exception to the serverId
            cv.put(Events.ORIGINAL_EVENT, parentCv.getAsString(Events._SYNC_ID));

            String exceptionStartTime = "_noStartTime";
            while (nextTag(Tags.SYNC_APPLICATION_DATA) != END) {
                switch (tag) {
                    case Tags.CALENDAR_EXCEPTION_START_TIME:
                        exceptionStartTime = getValue();
                        cv.put(Events.ORIGINAL_INSTANCE_TIME,
                                Utility.parseDateTimeToMillis(exceptionStartTime));
                        break;
                    case Tags.CALENDAR_EXCEPTION_IS_DELETED:
                        if (getValueInt() == 1) {
                            cv.put(Events.STATUS, Events.STATUS_CANCELED);
                        }
                        break;
                    case Tags.CALENDAR_ALL_DAY_EVENT:
                        allDayEvent = getValueInt();
                        cv.put(Events.ALL_DAY, allDayEvent);
                        break;
                    case Tags.BASE_BODY:
                        cv.put(Events.DESCRIPTION, bodyParser());
                        break;
                    case Tags.CALENDAR_BODY:
                        cv.put(Events.DESCRIPTION, getValue());
                        break;
                    case Tags.CALENDAR_START_TIME:
                        startTime = Utility.parseDateTimeToMillis(getValue());
                        break;
                    case Tags.CALENDAR_END_TIME:
                        endTime = Utility.parseDateTimeToMillis(getValue());
                        break;
                    case Tags.CALENDAR_LOCATION:
                        cv.put(Events.EVENT_LOCATION, getValue());
                        break;
                    case Tags.CALENDAR_RECURRENCE:
                        String rrule = recurrenceParser(ops);
                        if (rrule != null) {
                            cv.put(Events.RRULE, rrule);
                        }
                        break;
                    case Tags.CALENDAR_SUBJECT:
                        cv.put(Events.TITLE, getValue());
                        break;
                    case Tags.CALENDAR_SENSITIVITY:
                        cv.put(Events.VISIBILITY, encodeVisibility(getValueInt()));
                        break;
                    case Tags.CALENDAR_BUSY_STATUS:
                        busyStatus = getValueInt();
                        // Don't set selfAttendeeStatus or CalendarProvider will create a duplicate
                        // attendee!
                        break;
                        // TODO How to handle these items that are linked to event id!
//                    case Tags.CALENDAR_DTSTAMP:
//                        ops.newExtendedProperty("dtstamp", getValue());
//                        break;
//                    case Tags.CALENDAR_REMINDER_MINS_BEFORE:
//                        ops.newReminder(getValueInt());
//                        break;
                    default:
                        skipTag();
                }
            }

            // We need a _sync_id, but it can't be the parent's id, so we generate one
            cv.put(Events._SYNC_ID, parentCv.getAsString(Events._SYNC_ID) + '_' +
                    exceptionStartTime);

            // Enforce CalendarProvider required properties
            setTimeRelatedValues(cv, startTime, endTime, allDayEvent);

            // Don't insert an invalid exception event
            if (!isValidEventValues(cv)) return;

            // Add the exception insert
            int exceptionStart = ops.mCount;
            ops.newException(cv);
            // Also add the attendees, because they need to be copied over from the parent event
            boolean attendeesRedacted = false;
            if (attendeeValues != null) {
                for (ContentValues attValues: attendeeValues) {
                    // If this is the user, use his busy status for attendee status
                    String attendeeEmail = attValues.getAsString(Attendees.ATTENDEE_EMAIL);
                    // Note that the exception at which we surpass the redaction limit might have
                    // any number of attendees shown; since this is an edge case and a workaround,
                    // it seems to be an acceptable implementation
                    if (mEmailAddress.equalsIgnoreCase(attendeeEmail)) {
                        attValues.put(Attendees.ATTENDEE_STATUS,
                                CalendarUtilities.attendeeStatusFromBusyStatus(busyStatus));
                        ops.newAttendee(attValues, exceptionStart);
                    } else if (ops.size() < MAX_OPS_BEFORE_EXCEPTION_ATTENDEE_REDACTION) {
                        ops.newAttendee(attValues, exceptionStart);
                    } else {
                        attendeesRedacted = true;
                    }
                }
            }
            // And add the parent's reminder value
            if (reminderMins > 0) {
                ops.newReminder(reminderMins, exceptionStart);
            }
            if (attendeesRedacted) {
                mService.userLog("Attendees redacted in this exception");
            }
        }

        private int encodeVisibility(int easVisibility) {
            int visibility = 0;
            switch(easVisibility) {
                case 0:
                    visibility = Events.VISIBILITY_DEFAULT;
                    break;
                case 1:
                    visibility = Events.VISIBILITY_PUBLIC;
                    break;
                case 2:
                    visibility = Events.VISIBILITY_PRIVATE;
                    break;
                case 3:
                    visibility = Events.VISIBILITY_CONFIDENTIAL;
                    break;
            }
            return visibility;
        }

        private void exceptionsParser(CalendarOperations ops, ContentValues cv,
                ArrayList<ContentValues> attendeeValues, int reminderMins, int busyStatus,
                long startTime, long endTime) throws IOException {
            while (nextTag(Tags.CALENDAR_EXCEPTIONS) != END) {
                switch (tag) {
                    case Tags.CALENDAR_EXCEPTION:
                        exceptionParser(ops, cv, attendeeValues, reminderMins, busyStatus,
                                startTime, endTime);
                        break;
                    default:
                        skipTag();
                }
            }
        }

        private String categoriesParser(CalendarOperations ops) throws IOException {
            StringBuilder categories = new StringBuilder();
            while (nextTag(Tags.CALENDAR_CATEGORIES) != END) {
                switch (tag) {
                    case Tags.CALENDAR_CATEGORY:
                        // TODO Handle categories (there's no similar concept for gdata AFAIK)
                        // We need to save them and spit them back when we update the event
                        categories.append(getValue());
                        categories.append(CATEGORY_TOKENIZER_DELIMITER);
                        break;
                    default:
                        skipTag();
                }
            }
            return categories.toString();
        }

        private ArrayList<ContentValues> attendeesParser(CalendarOperations ops, long eventId)
                throws IOException {
            int attendeeCount = 0;
            ArrayList<ContentValues> attendeeValues = new ArrayList<ContentValues>();
            while (nextTag(Tags.CALENDAR_ATTENDEES) != END) {
                switch (tag) {
                    case Tags.CALENDAR_ATTENDEE:
                        ContentValues cv = attendeeParser(ops, eventId);
                        // If we're going to redact these attendees anyway, let's avoid unnecessary
                        // memory pressure, and not keep them around
                        // We still need to parse them all, however
                        attendeeCount++;
                        // Allow one more than MAX_ATTENDEES, so that the check for "too many" will
                        // succeed in addEvent
                        if (attendeeCount <= (MAX_SYNCED_ATTENDEES+1)) {
                            attendeeValues.add(cv);
                        }
                        break;
                    default:
                        skipTag();
                }
            }
            return attendeeValues;
        }

        private ContentValues attendeeParser(CalendarOperations ops, long eventId)
                throws IOException {
            ContentValues cv = new ContentValues();
            while (nextTag(Tags.CALENDAR_ATTENDEE) != END) {
                switch (tag) {
                    case Tags.CALENDAR_ATTENDEE_EMAIL:
                        cv.put(Attendees.ATTENDEE_EMAIL, getValue());
                        break;
                    case Tags.CALENDAR_ATTENDEE_NAME:
                        cv.put(Attendees.ATTENDEE_NAME, getValue());
                        break;
                    // We'll ignore attendee status for now; it's not obvious how to do this
                    // consistently even with Exchange 2007 (with Exchange 2003, attendee status
                    // isn't handled at all).
                    // TODO: Investigate a consistent and accurate method of tracking attendee
                    // status, though it might turn out not to be possible
//                    case Tags.CALENDAR_ATTENDEE_STATUS:
//                        int status = getValueInt();
//                        cv.put(Attendees.ATTENDEE_STATUS,
//                                (status == 2) ? Attendees.ATTENDEE_STATUS_TENTATIVE :
//                                (status == 3) ? Attendees.ATTENDEE_STATUS_ACCEPTED :
//                                (status == 4) ? Attendees.ATTENDEE_STATUS_DECLINED :
//                                (status == 5) ? Attendees.ATTENDEE_STATUS_INVITED :
//                                    Attendees.ATTENDEE_STATUS_NONE);
//                        break;
                    case Tags.CALENDAR_ATTENDEE_TYPE:
                        int type = Attendees.TYPE_NONE;
                        // EAS types: 1 = req'd, 2 = opt, 3 = resource
                        switch (getValueInt()) {
                            case 1:
                                type = Attendees.TYPE_REQUIRED;
                                break;
                            case 2:
                                type = Attendees.TYPE_OPTIONAL;
                                break;
                        }
                        cv.put(Attendees.ATTENDEE_TYPE, type);
                        break;
                    default:
                        skipTag();
                }
            }
            cv.put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ATTENDEE);
            return cv;
        }

        private String bodyParser() throws IOException {
            String body = null;
            while (nextTag(Tags.BASE_BODY) != END) {
                switch (tag) {
                    case Tags.BASE_DATA:
                        body = getValue();
                        break;
                    default:
                        skipTag();
                }
            }

            // Handle null data without error
            if (body == null) return "";
            // Remove \r's from any body text
            return body.replace("\r\n", "\n");
        }

        public void addParser(CalendarOperations ops) throws IOException {
            String serverId = null;
            while (nextTag(Tags.SYNC_ADD) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID: // same as
                        serverId = getValue();
                        break;
                    case Tags.SYNC_APPLICATION_DATA:
                        addEvent(ops, serverId, false);
                        break;
                    default:
                        skipTag();
                }
            }
        }

        private Cursor getServerIdCursor(String serverId) {
            return mContentResolver.query(mAccountUri, ID_PROJECTION, SERVER_ID_AND_CALENDAR_ID,
                    new String[] {serverId, mCalendarIdString}, null);
        }

        private Cursor getClientIdCursor(String clientId) {
            mBindArgument[0] = clientId;
            return mContentResolver.query(mAccountUri, ID_PROJECTION, CLIENT_ID_SELECTION,
                    mBindArgument, null);
        }

        public void deleteParser(CalendarOperations ops) throws IOException {
            while (nextTag(Tags.SYNC_DELETE) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        String serverId = getValue();
                        // Find the event with the given serverId
                        Cursor c = getServerIdCursor(serverId);
                        try {
                            if (c.moveToFirst()) {
                                userLog("Deleting ", serverId);
                                ops.delete(c.getLong(0), serverId);
                            }
                        } finally {
                            c.close();
                        }
                        break;
                    default:
                        skipTag();
                }
            }
        }

        /**
         * A change is handled as a delete (including all exceptions) and an add
         * This isn't as efficient as attempting to traverse the original and all of its exceptions,
         * but changes happen infrequently and this code is both simpler and easier to maintain
         * @param ops the array of pending ContactProviderOperations.
         * @throws IOException
         */
        public void changeParser(CalendarOperations ops) throws IOException {
            String serverId = null;
            while (nextTag(Tags.SYNC_CHANGE) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        serverId = getValue();
                        break;
                    case Tags.SYNC_APPLICATION_DATA:
                        userLog("Changing " + serverId);
                        addEvent(ops, serverId, true);
                        break;
                    default:
                        skipTag();
                }
            }
        }

        @Override
        public void commandsParser() throws IOException {
            while (nextTag(Tags.SYNC_COMMANDS) != END) {
                if (tag == Tags.SYNC_ADD) {
                    addParser(mOps);
                    incrementChangeCount();
                } else if (tag == Tags.SYNC_DELETE) {
                    deleteParser(mOps);
                    incrementChangeCount();
                } else if (tag == Tags.SYNC_CHANGE) {
                    changeParser(mOps);
                    incrementChangeCount();
                } else
                    skipTag();
            }
        }

        @Override
        public void commit() throws IOException {
            userLog("Calendar SyncKey saved as: ", mMailbox.mSyncKey);
            // Save the syncKey here, using the Helper provider by Calendar provider
            mOps.add(SyncStateContract.Helpers.newSetOperation(SyncState.CONTENT_URI,
                    mAccountManagerAccount, mMailbox.mSyncKey.getBytes()));

            // We need to send cancellations now, because the Event won't exist after the commit
            for (long eventId: mSendCancelIdList) {
                EmailContent.Message msg;
                try {
                    msg = CalendarUtilities.createMessageForEventId(mContext, eventId,
                            EmailContent.Message.FLAG_OUTGOING_MEETING_CANCEL, null,
                            mAccount);
                } catch (RemoteException e) {
                    // Nothing to do here; the Event may no longer exist
                    continue;
                }
                if (msg != null) {
                    EasOutboxService.sendMessage(mContext, mAccount.mId, msg);
                }
            }

            // Execute these all at once...
            mOps.execute();

            if (mOps.mResults != null) {
                // Clear dirty and mark flags for updates sent to server
                if (!mUploadedIdList.isEmpty())  {
                    ContentValues cv = new ContentValues();
                    cv.put(Events._SYNC_DIRTY, 0);
                    cv.put(Events._SYNC_MARK, 0);
                    for (long eventId: mUploadedIdList) {
                        mContentResolver.update(ContentUris.withAppendedId(EVENTS_URI, eventId), cv,
                                null, null);
                    }
                }
                // Delete events marked for deletion
                if (!mDeletedIdList.isEmpty()) {
                    for (long eventId: mDeletedIdList) {
                        mContentResolver.delete(ContentUris.withAppendedId(EVENTS_URI, eventId),
                                null, null);
                    }
                }
                // Send any queued up email (invitations replies, etc.)
                for (Message msg: mOutgoingMailList) {
                    EasOutboxService.sendMessage(mContext, mAccount.mId, msg);
                }
            }
        }

        public void addResponsesParser() throws IOException {
            String serverId = null;
            String clientId = null;
            int status = -1;
            ContentValues cv = new ContentValues();
            while (nextTag(Tags.SYNC_ADD) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        serverId = getValue();
                        break;
                    case Tags.SYNC_CLIENT_ID:
                        clientId = getValue();
                        break;
                    case Tags.SYNC_STATUS:
                        status = getValueInt();
                        if (status != 1) {
                            userLog("Attempt to add event failed with status: " + status);
                        }
                        break;
                    default:
                        skipTag();
                }
            }

            if (clientId == null) return;
            if (serverId == null) {
                // TODO Reconsider how to handle this
                serverId = "FAIL:" + status;
            }

            Cursor c = getClientIdCursor(clientId);
            try {
                if (c.moveToFirst()) {
                    cv.put(Events._SYNC_ID, serverId);
                    cv.put(Events._SYNC_DATA, clientId);
                    long id = c.getLong(0);
                    // Write the serverId into the Event
                    mOps.add(ContentProviderOperation.newUpdate(
                            ContentUris.withAppendedId(EVENTS_URI, id))
                                    .withValues(cv)
                                    .build());
                    userLog("New event " + clientId + " was given serverId: " + serverId);
                }
            } finally {
                c.close();
            }
        }

        public void changeResponsesParser() throws IOException {
            String serverId = null;
            String status = null;
            while (nextTag(Tags.SYNC_CHANGE) != END) {
                switch (tag) {
                    case Tags.SYNC_SERVER_ID:
                        serverId = getValue();
                        break;
                    case Tags.SYNC_STATUS:
                        status = getValue();
                        break;
                    default:
                        skipTag();
                }
            }
            if (serverId != null && status != null) {
                userLog("Changed event " + serverId + " failed with status: " + status);
            }
        }


        @Override
        public void responsesParser() throws IOException {
            // Handle server responses here (for Add and Change)
            while (nextTag(Tags.SYNC_RESPONSES) != END) {
                if (tag == Tags.SYNC_ADD) {
                    addResponsesParser();
                } else if (tag == Tags.SYNC_CHANGE) {
                    changeResponsesParser();
                } else
                    skipTag();
            }
        }
    }

    private class CalendarOperations extends ArrayList<ContentProviderOperation> {
        private static final long serialVersionUID = 1L;
        public int mCount = 0;
        private ContentProviderResult[] mResults = null;
        private int mEventStart = 0;

        @Override
        public boolean add(ContentProviderOperation op) {
            super.add(op);
            mCount++;
            return true;
        }

        public int newEvent(ContentProviderOperation op) {
            mEventStart = mCount;
            add(op);
            return mEventStart;
        }

        public int newDelete(long id, String serverId) {
            int offset = mCount;
            delete(id, serverId);
            return offset;
        }

        public void newAttendee(ContentValues cv) {
            newAttendee(cv, mEventStart);
        }

        public void newAttendee(ContentValues cv, int eventStart) {
            add(ContentProviderOperation
                    .newInsert(ATTENDEES_URI)
                    .withValues(cv)
                    .withValueBackReference(Attendees.EVENT_ID, eventStart)
                    .build());
        }

        public void updatedAttendee(ContentValues cv, long id) {
            cv.put(Attendees.EVENT_ID, id);
            add(ContentProviderOperation.newInsert(ATTENDEES_URI).withValues(cv).build());
        }

        public void newException(ContentValues cv) {
            add(ContentProviderOperation.newInsert(EVENTS_URI).withValues(cv).build());
        }

        public void newExtendedProperty(String name, String value) {
            add(ContentProviderOperation
                    .newInsert(EXTENDED_PROPERTIES_URI)
                    .withValue(ExtendedProperties.NAME, name)
                    .withValue(ExtendedProperties.VALUE, value)
                    .withValueBackReference(ExtendedProperties.EVENT_ID, mEventStart)
                    .build());
        }

        public void updatedExtendedProperty(String name, String value, long id) {
            // Find an existing ExtendedProperties row for this event and property name
            Cursor c = mService.mContentResolver.query(ExtendedProperties.CONTENT_URI,
                    EXTENDED_PROPERTY_PROJECTION, EVENT_ID_AND_NAME,
                    new String[] {Long.toString(id), name}, null);
            long extendedPropertyId = -1;
            // If there is one, capture its _id
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        extendedPropertyId = c.getLong(EXTENDED_PROPERTY_ID);
                    }
                } finally {
                    c.close();
                }
            }
            // Either do an update or an insert, depending on whether one already exists
            if (extendedPropertyId >= 0) {
                add(ContentProviderOperation
                        .newUpdate(ContentUris.withAppendedId(EXTENDED_PROPERTIES_URI,
                                extendedPropertyId))
                        .withValue(ExtendedProperties.VALUE, value)
                        .build());
            } else {
                newExtendedProperty(name, value);
            }
        }

        public void newReminder(int mins, int eventStart) {
            add(ContentProviderOperation
                    .newInsert(REMINDERS_URI)
                    .withValue(Reminders.MINUTES, mins)
                    .withValue(Reminders.METHOD, Reminders.METHOD_ALERT)
                    .withValueBackReference(ExtendedProperties.EVENT_ID, eventStart)
                    .build());
        }

        public void newReminder(int mins) {
            newReminder(mins, mEventStart);
        }

        public void delete(long id, String syncId) {
            add(ContentProviderOperation
                    .newDelete(ContentUris.withAppendedId(EVENTS_URI, id)).build());
            // Delete the exceptions for this Event (CalendarProvider doesn't do this)
            add(ContentProviderOperation
                    .newDelete(EVENTS_URI).withSelection(Events.ORIGINAL_EVENT + "=?",
                            new String[] {syncId}).build());
        }

        public void execute() {
            synchronized (mService.getSynchronizer()) {
                if (!mService.isStopped()) {
                    try {
                        if (!isEmpty()) {
                            mService.userLog("Executing ", size(), " CPO's");
                            mResults = mContext.getContentResolver().applyBatch(
                                    Calendar.AUTHORITY, this);
                        }
                    } catch (RemoteException e) {
                        // There is nothing sensible to be done here
                        Log.e(TAG, "problem inserting event during server update", e);
                    } catch (OperationApplicationException e) {
                        // There is nothing sensible to be done here
                        Log.e(TAG, "problem inserting event during server update", e);
                    }
                }
            }
        }
    }

    private String decodeVisibility(int visibility) {
        int easVisibility = 0;
        switch(visibility) {
            case Events.VISIBILITY_DEFAULT:
                easVisibility = 0;
                break;
            case Events.VISIBILITY_PUBLIC:
                easVisibility = 1;
                break;
            case Events.VISIBILITY_PRIVATE:
                easVisibility = 2;
                break;
            case Events.VISIBILITY_CONFIDENTIAL:
                easVisibility = 3;
                break;
        }
        return Integer.toString(easVisibility);
    }

    private int getInt(ContentValues cv, String column) {
        Integer i = cv.getAsInteger(column);
        if (i == null) return 0;
        return i;
    }

    private void sendEvent(Entity entity, String clientId, Serializer s)
            throws IOException {
        // Serialize for EAS here
        // Set uid with the client id we created
        // 1) Serialize the top-level event
        // 2) Serialize attendees and reminders from subvalues
        // 3) Look for exceptions and serialize with the top-level event
        ContentValues entityValues = entity.getEntityValues();
        final boolean isException = (clientId == null);
        boolean hasAttendees = false;
        final boolean isChange = entityValues.containsKey(Events._SYNC_ID);
        final Double version = mService.mProtocolVersionDouble;
        final boolean allDay =
            CalendarUtilities.getIntegerValueAsBoolean(entityValues, Events.ALL_DAY);

        // NOTE: Exchange 2003 (EAS 2.5) seems to require the "exception deleted" and "exception
        // start time" data before other data in exceptions.  Failure to do so results in a
        // status 6 error during sync
        if (isException) {
           // Send exception deleted flag if necessary
            Integer deleted = entityValues.getAsInteger(Calendar.EventsColumns.DELETED);
            boolean isDeleted = deleted != null && deleted == 1;
            Integer eventStatus = entityValues.getAsInteger(Events.STATUS);
            boolean isCanceled = eventStatus != null && eventStatus.equals(Events.STATUS_CANCELED);
            if (isDeleted || isCanceled) {
                s.data(Tags.CALENDAR_EXCEPTION_IS_DELETED, "1");
                // If we're deleted, the UI will continue to show this exception until we mark
                // it canceled, so we'll do that here...
                if (isDeleted && !isCanceled) {
                    final long eventId = entityValues.getAsLong(Events._ID);
                    ContentValues cv = new ContentValues();
                    cv.put(Events.STATUS, Events.STATUS_CANCELED);
                    mService.mContentResolver.update(
                            ContentUris.withAppendedId(EVENTS_URI, eventId), cv, null, null);
                }
            } else {
                s.data(Tags.CALENDAR_EXCEPTION_IS_DELETED, "0");
            }

            // TODO Add reminders to exceptions (allow them to be specified!)
            Long originalTime = entityValues.getAsLong(Events.ORIGINAL_INSTANCE_TIME);
            if (originalTime != null) {
                final boolean originalAllDay =
                    CalendarUtilities.getIntegerValueAsBoolean(entityValues,
                            Events.ORIGINAL_ALL_DAY);
                if (originalAllDay) {
                    // For all day events, we need our local all-day time
                    originalTime =
                        CalendarUtilities.getLocalAllDayCalendarTime(originalTime, mLocalTimeZone);
                }
                s.data(Tags.CALENDAR_EXCEPTION_START_TIME,
                        CalendarUtilities.millisToEasDateTime(originalTime));
            } else {
                // Illegal; what should we do?
            }
        }

        // Get the event's time zone
        String timeZoneName =
            entityValues.getAsString(allDay ? EVENT_TIMEZONE2_COLUMN : Events.EVENT_TIMEZONE);
        if (timeZoneName == null) {
            timeZoneName = mLocalTimeZone.getID();
        }
        TimeZone eventTimeZone = TimeZone.getTimeZone(timeZoneName);

        if (!isException) {
            // A time zone is required in all EAS events; we'll use the default if none is set
            // Exchange 2003 seems to require this first... :-)
            String timeZone = CalendarUtilities.timeZoneToTziString(eventTimeZone);
            s.data(Tags.CALENDAR_TIME_ZONE, timeZone);
        }

        s.data(Tags.CALENDAR_ALL_DAY_EVENT, allDay ? "1" : "0");

        // DTSTART is always supplied
        long startTime = entityValues.getAsLong(Events.DTSTART);
        // Determine endTime; it's either provided as DTEND or we calculate using DURATION
        // If no DURATION is provided, we default to one hour
        long endTime;
        if (entityValues.containsKey(Events.DTEND)) {
            endTime = entityValues.getAsLong(Events.DTEND);
        } else {
            long durationMillis = HOURS;
            if (entityValues.containsKey(Events.DURATION)) {
                Duration duration = new Duration();
                try {
                    duration.parse(entityValues.getAsString(Events.DURATION));
                    durationMillis = duration.getMillis();
                } catch (ParseException e) {
                    // Can't do much about this; use the default (1 hour)
                }
            }
            endTime = startTime + durationMillis;
        }
        if (allDay) {
            TimeZone tz = mLocalTimeZone;
            startTime = CalendarUtilities.getLocalAllDayCalendarTime(startTime, tz);
            endTime = CalendarUtilities.getLocalAllDayCalendarTime(endTime, tz);
        }
        s.data(Tags.CALENDAR_START_TIME, CalendarUtilities.millisToEasDateTime(startTime));
        s.data(Tags.CALENDAR_END_TIME, CalendarUtilities.millisToEasDateTime(endTime));

        s.data(Tags.CALENDAR_DTSTAMP,
                CalendarUtilities.millisToEasDateTime(System.currentTimeMillis()));

        String loc = entityValues.getAsString(Events.EVENT_LOCATION);
        if (!TextUtils.isEmpty(loc)) {
            if (version < Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
                // EAS 2.5 doesn't like bare line feeds
                loc = Utility.replaceBareLfWithCrlf(loc);
            }
            s.data(Tags.CALENDAR_LOCATION, loc);
        }
        s.writeStringValue(entityValues, Events.TITLE, Tags.CALENDAR_SUBJECT);

        String desc = entityValues.getAsString(Events.DESCRIPTION);
        if (desc != null && desc.length() > 0) {
            if (version >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
                s.start(Tags.BASE_BODY);
                s.data(Tags.BASE_TYPE, "1");
                s.data(Tags.BASE_DATA, desc);
                s.end();
            } else {
                // EAS 2.5 doesn't like bare line feeds
                s.data(Tags.CALENDAR_BODY, Utility.replaceBareLfWithCrlf(desc));
            }
        }

        if (!isException) {
            // For Exchange 2003, only upsync if the event is new
            if ((version >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) || !isChange) {
                s.writeStringValue(entityValues, Events.ORGANIZER, Tags.CALENDAR_ORGANIZER_EMAIL);
            }

            String rrule = entityValues.getAsString(Events.RRULE);
            if (rrule != null) {
                CalendarUtilities.recurrenceFromRrule(rrule, startTime, s);
            }

            // Handle associated data EXCEPT for attendees, which have to be grouped
            ArrayList<NamedContentValues> subValues = entity.getSubValues();
            // The earliest of the reminders for this Event; we can only send one reminder...
            int earliestReminder = -1;
            for (NamedContentValues ncv: subValues) {
                Uri ncvUri = ncv.uri;
                ContentValues ncvValues = ncv.values;
                if (ncvUri.equals(ExtendedProperties.CONTENT_URI)) {
                    String propertyName =
                        ncvValues.getAsString(ExtendedProperties.NAME);
                    String propertyValue =
                        ncvValues.getAsString(ExtendedProperties.VALUE);
                    if (TextUtils.isEmpty(propertyValue)) {
                        continue;
                    }
                    if (propertyName.equals(EXTENDED_PROPERTY_CATEGORIES)) {
                        // Send all the categories back to the server
                        // We've saved them as a String of delimited tokens
                        StringTokenizer st =
                            new StringTokenizer(propertyValue, CATEGORY_TOKENIZER_DELIMITER);
                        if (st.countTokens() > 0) {
                            s.start(Tags.CALENDAR_CATEGORIES);
                            while (st.hasMoreTokens()) {
                                String category = st.nextToken();
                                s.data(Tags.CALENDAR_CATEGORY, category);
                            }
                            s.end();
                        }
                    }
                } else if (ncvUri.equals(Reminders.CONTENT_URI)) {
                    Integer mins = ncvValues.getAsInteger(Reminders.MINUTES);
                    if (mins != null) {
                        // -1 means "default", which for Exchange, is 30
                        if (mins < 0) {
                            mins = 30;
                        }
                        // Save this away if it's the earliest reminder (greatest minutes)
                        if (mins > earliestReminder) {
                            earliestReminder = mins;
                        }
                    }
                }
            }

            // If we have a reminder, send it to the server
            if (earliestReminder >= 0) {
                s.data(Tags.CALENDAR_REMINDER_MINS_BEFORE, Integer.toString(earliestReminder));
            }

            // We've got to send a UID, unless this is an exception.  If the event is new, we've
            // generated one; if not, we should have gotten one from extended properties.
            if (clientId != null) {
                s.data(Tags.CALENDAR_UID, clientId);
            }

            // Handle attendee data here; keep track of organizer and stream it afterward
            String organizerName = null;
            String organizerEmail = null;
            for (NamedContentValues ncv: subValues) {
                Uri ncvUri = ncv.uri;
                ContentValues ncvValues = ncv.values;
                if (ncvUri.equals(Attendees.CONTENT_URI)) {
                    Integer relationship = ncvValues.getAsInteger(Attendees.ATTENDEE_RELATIONSHIP);
                    // If there's no relationship, we can't create this for EAS
                    // Similarly, we need an attendee email for each invitee
                    if (relationship != null && ncvValues.containsKey(Attendees.ATTENDEE_EMAIL)) {
                        // Organizer isn't among attendees in EAS
                        if (relationship == Attendees.RELATIONSHIP_ORGANIZER) {
                            organizerName = ncvValues.getAsString(Attendees.ATTENDEE_NAME);
                            organizerEmail = ncvValues.getAsString(Attendees.ATTENDEE_EMAIL);
                            continue;
                        }
                        if (!hasAttendees) {
                            s.start(Tags.CALENDAR_ATTENDEES);
                            hasAttendees = true;
                        }
                        s.start(Tags.CALENDAR_ATTENDEE);
                        String attendeeEmail = ncvValues.getAsString(Attendees.ATTENDEE_EMAIL);
                        String attendeeName = ncvValues.getAsString(Attendees.ATTENDEE_NAME);
                        if (attendeeName == null) {
                            attendeeName = attendeeEmail;
                        }
                        s.data(Tags.CALENDAR_ATTENDEE_NAME, attendeeName);
                        s.data(Tags.CALENDAR_ATTENDEE_EMAIL, attendeeEmail);
                        if (version >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
                            s.data(Tags.CALENDAR_ATTENDEE_TYPE, "1"); // Required
                        }
                        s.end(); // Attendee
                     }
                }
            }
            if (hasAttendees) {
                s.end();  // Attendees
            }

            // Get busy status from Attendees table
            long eventId = entityValues.getAsLong(Events._ID);
            int busyStatus = CalendarUtilities.BUSY_STATUS_TENTATIVE;
            Cursor c = mService.mContentResolver.query(ATTENDEES_URI,
                    ATTENDEE_STATUS_PROJECTION, EVENT_AND_EMAIL,
                    new String[] {Long.toString(eventId), mEmailAddress}, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        busyStatus = CalendarUtilities.busyStatusFromAttendeeStatus(
                                c.getInt(ATTENDEE_STATUS_COLUMN_STATUS));
                    }
                } finally {
                    c.close();
                }
            }
            s.data(Tags.CALENDAR_BUSY_STATUS, Integer.toString(busyStatus));

            // Meeting status, 0 = appointment, 1 = meeting, 3 = attendee
            if (mEmailAddress.equalsIgnoreCase(organizerEmail)) {
                s.data(Tags.CALENDAR_MEETING_STATUS, hasAttendees ? "1" : "0");
            } else {
                s.data(Tags.CALENDAR_MEETING_STATUS, "3");
            }

            // For Exchange 2003, only upsync if the event is new
            if (((version >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) || !isChange) &&
                    organizerName != null) {
                s.data(Tags.CALENDAR_ORGANIZER_NAME, organizerName);
            }

            // NOTE: Sensitivity must NOT be sent to the server for exceptions in Exchange 2003
            // The result will be a status 6 failure during sync
            Integer visibility = entityValues.getAsInteger(Events.VISIBILITY);
            if (visibility != null) {
                s.data(Tags.CALENDAR_SENSITIVITY, decodeVisibility(visibility));
            } else {
                // Default to private if not set
                s.data(Tags.CALENDAR_SENSITIVITY, "1");
            }
        }
    }

    /**
     * Convenience method for sending an email to the organizer declining the meeting
     * @param entity
     * @param clientId
     */
    private void sendDeclinedEmail(Entity entity, String clientId) {
        Message msg =
            CalendarUtilities.createMessageForEntity(mContext, entity,
                    Message.FLAG_OUTGOING_MEETING_DECLINE, clientId, mAccount);
        if (msg != null) {
            userLog("Queueing declined response to " + msg.mTo);
            mOutgoingMailList.add(msg);
        }
    }

    @Override
    public boolean sendLocalChanges(Serializer s) throws IOException {
        ContentResolver cr = mService.mContentResolver;

        if (getSyncKey().equals("0")) {
            return false;
        }

        try {
            // We've got to handle exceptions as part of the parent when changes occur, so we need
            // to find new/changed exceptions and mark the parent dirty
            ArrayList<Long> orphanedExceptions = new ArrayList<Long>();
            Cursor c = cr.query(Events.CONTENT_URI, ORIGINAL_EVENT_PROJECTION,
                    DIRTY_EXCEPTION_IN_CALENDAR, mCalendarIdArgument, null);
            try {
                ContentValues cv = new ContentValues();
                // We use _sync_mark here to distinguish dirty parents from parents with dirty
                // exceptions
                cv.put(Events._SYNC_MARK, 1);
                while (c.moveToNext()) {
                    // Mark the parents of dirty exceptions
                    String serverId = c.getString(0);
                    int cnt = cr.update(EVENTS_URI, cv, SERVER_ID_AND_CALENDAR_ID,
                            new String[] {serverId, mCalendarIdString});
                    // Keep track of any orphaned exceptions
                    if (cnt == 0) {
                        orphanedExceptions.add(c.getLong(1));
                    }
                }
            } finally {
                c.close();
            }

           // Delete any orphaned exceptions
            for (long orphan: orphanedExceptions) {
                userLog(TAG, "Deleted orphaned exception: " + orphan);
                cr.delete(ContentUris.withAppendedId(EVENTS_URI, orphan), null, null);
            }
            orphanedExceptions.clear();

            // Now we can go through dirty/marked top-level events and send them back to the server
            EntityIterator eventIterator = EventsEntity.newEntityIterator(
                    cr.query(EVENTS_URI, null, DIRTY_OR_MARKED_TOP_LEVEL_IN_CALENDAR,
                            mCalendarIdArgument, null), cr);
            ContentValues cidValues = new ContentValues();

            try {
                boolean first = true;
                while (eventIterator.hasNext()) {
                    Entity entity = eventIterator.next();

                    // For each of these entities, create the change commands
                    ContentValues entityValues = entity.getEntityValues();
                    String serverId = entityValues.getAsString(Events._SYNC_ID);

                    // We first need to check whether we can upsync this event; our test for this
                    // is currently the value of EXTENDED_PROPERTY_ATTENDEES_REDACTED
                    // If this is set to "1", we can't upsync the event
                    for (NamedContentValues ncv: entity.getSubValues()) {
                        if (ncv.uri.equals(ExtendedProperties.CONTENT_URI)) {
                            ContentValues ncvValues = ncv.values;
                            if (ncvValues.getAsString(ExtendedProperties.NAME).equals(
                                    EXTENDED_PROPERTY_UPSYNC_PROHIBITED)) {
                                if ("1".equals(ncvValues.getAsString(ExtendedProperties.VALUE))) {
                                    // Make sure we mark this to clear the dirty flag
                                    mUploadedIdList.add(entityValues.getAsLong(Events._ID));
                                    continue;
                                }
                            }
                        }
                    }

                    // Find our uid in the entity; otherwise create one
                    String clientId = entityValues.getAsString(Events._SYNC_DATA);
                    if (clientId == null) {
                        clientId = UUID.randomUUID().toString();
                    }

                    // EAS 2.5 needs: BusyStatus DtStamp EndTime Sensitivity StartTime TimeZone UID
                    // We can generate all but what we're testing for below
                    String organizerEmail = entityValues.getAsString(Events.ORGANIZER);
                    boolean selfOrganizer = organizerEmail.equalsIgnoreCase(mEmailAddress);

                    if (!entityValues.containsKey(Events.DTSTART)
                            || (!entityValues.containsKey(Events.DURATION) &&
                                    !entityValues.containsKey(Events.DTEND))
                                    || organizerEmail == null) {
                        continue;
                    }

                    if (first) {
                        s.start(Tags.SYNC_COMMANDS);
                        userLog("Sending Calendar changes to the server");
                        first = false;
                    }
                    long eventId = entityValues.getAsLong(Events._ID);
                    if (serverId == null) {
                        // This is a new event; create a clientId
                        userLog("Creating new event with clientId: ", clientId);
                        s.start(Tags.SYNC_ADD).data(Tags.SYNC_CLIENT_ID, clientId);
                        // And save it in the Event as the local id
                        cidValues.put(Events._SYNC_DATA, clientId);
                        cidValues.put(Events._SYNC_VERSION, "0");
                        cr.update(ContentUris.withAppendedId(EVENTS_URI, eventId), cidValues,
                                null, null);
                    } else {
                        if (entityValues.getAsInteger(Events.DELETED) == 1) {
                            userLog("Deleting event with serverId: ", serverId);
                            s.start(Tags.SYNC_DELETE).data(Tags.SYNC_SERVER_ID, serverId).end();
                            mDeletedIdList.add(eventId);
                            if (selfOrganizer) {
                                mSendCancelIdList.add(eventId);
                            } else {
                                sendDeclinedEmail(entity, clientId);
                            }
                            continue;
                        }
                        userLog("Upsync change to event with serverId: " + serverId);
                        // Get the current version
                        String version = entityValues.getAsString(Events._SYNC_VERSION);
                        // This should never be null, but catch this error anyway
                        // Version should be "0" when we create the event, so use that
                        if (version == null) {
                            version = "0";
                        } else {
                            // Increment and save
                            try {
                                version = Integer.toString((Integer.parseInt(version) + 1));
                            } catch (Exception e) {
                                // Handle the case in which someone writes a non-integer here;
                                // shouldn't happen, but we don't want to kill the sync for his
                                version = "0";
                            }
                        }
                        cidValues.put(Events._SYNC_VERSION, version);
                        // Also save in entityValues so that we send it this time around
                        entityValues.put(Events._SYNC_VERSION, version);
                        cr.update(ContentUris.withAppendedId(EVENTS_URI, eventId), cidValues,
                                null, null);
                        s.start(Tags.SYNC_CHANGE).data(Tags.SYNC_SERVER_ID, serverId);
                    }
                    s.start(Tags.SYNC_APPLICATION_DATA);

                    sendEvent(entity, clientId, s);

                    // Now, the hard part; find exceptions for this event
                    if (serverId != null) {
                        EntityIterator exIterator = EventsEntity.newEntityIterator(
                                cr.query(EVENTS_URI, null, ORIGINAL_EVENT_AND_CALENDAR,
                                        new String[] {serverId, mCalendarIdString}, null), cr);
                        boolean exFirst = true;
                        while (exIterator.hasNext()) {
                            Entity exEntity = exIterator.next();
                            if (exFirst) {
                                s.start(Tags.CALENDAR_EXCEPTIONS);
                                exFirst = false;
                            }
                            s.start(Tags.CALENDAR_EXCEPTION);
                            sendEvent(exEntity, null, s);
                            ContentValues exValues = exEntity.getEntityValues();
                            if (getInt(exValues, Events._SYNC_DIRTY) == 1) {
                                // This is a new/updated exception, so we've got to notify our
                                // attendees about it
                                long exEventId = exValues.getAsLong(Events._ID);
                                int flag;

                                // Copy subvalues into the exception; otherwise, we won't see the
                                // attendees when preparing the message
                                for (NamedContentValues ncv: entity.getSubValues()) {
                                    exEntity.addSubValue(ncv.uri, ncv.values);
                                }

                                if ((getInt(exValues, Events.DELETED) == 1) ||
                                        (getInt(exValues, Events.STATUS) ==
                                            Events.STATUS_CANCELED)) {
                                    flag = Message.FLAG_OUTGOING_MEETING_CANCEL;
                                    if (!selfOrganizer) {
                                        // Send a cancellation notice to the organizer
                                        // Since CalendarProvider2 sets the organizer of exceptions
                                        // to the user, we have to reset it first to the original
                                        // organizer
                                        exValues.put(Events.ORGANIZER,
                                                entityValues.getAsString(Events.ORGANIZER));
                                        sendDeclinedEmail(exEntity, clientId);
                                    }
                                } else {
                                    flag = Message.FLAG_OUTGOING_MEETING_INVITE;
                                }
                                // Add the eventId of the exception to the uploaded id list, so that
                                // the dirty/mark bits are cleared
                                mUploadedIdList.add(exEventId);

                                // Copy version so the ics attachment shows the proper sequence #
                                exValues.put(Events._SYNC_VERSION,
                                        entityValues.getAsString(Events._SYNC_VERSION));
                                // Copy location so that it's included in the outgoing email
                                if (entityValues.containsKey(Events.EVENT_LOCATION)) {
                                    exValues.put(Events.EVENT_LOCATION,
                                            entityValues.getAsString(Events.EVENT_LOCATION));
                                }

                                if (selfOrganizer) {
                                    Message msg =
                                        CalendarUtilities.createMessageForEntity(mContext,
                                                exEntity, flag, clientId, mAccount);
                                    if (msg != null) {
                                        userLog("Queueing exception update to " + msg.mTo);
                                        mOutgoingMailList.add(msg);
                                    }
                                }
                            }
                            s.end(); // EXCEPTION
                        }
                        if (!exFirst) {
                            s.end(); // EXCEPTIONS
                        }
                    }

                    s.end().end(); // ApplicationData & Change
                    mUploadedIdList.add(eventId);

                    // Go through the extended properties of this Event and pull out our tokenized
                    // attendees list and the user attendee status; we will need them later
                    String attendeeString = null;
                    long attendeeStringId = -1;
                    String userAttendeeStatus = null;
                    long userAttendeeStatusId = -1;
                    for (NamedContentValues ncv: entity.getSubValues()) {
                        if (ncv.uri.equals(ExtendedProperties.CONTENT_URI)) {
                            ContentValues ncvValues = ncv.values;
                            String propertyName =
                                ncvValues.getAsString(ExtendedProperties.NAME);
                            if (propertyName.equals(EXTENDED_PROPERTY_ATTENDEES)) {
                                attendeeString =
                                    ncvValues.getAsString(ExtendedProperties.VALUE);
                                attendeeStringId =
                                    ncvValues.getAsLong(ExtendedProperties._ID);
                            } else if (propertyName.equals(
                                    EXTENDED_PROPERTY_USER_ATTENDEE_STATUS)) {
                                userAttendeeStatus =
                                    ncvValues.getAsString(ExtendedProperties.VALUE);
                                userAttendeeStatusId =
                                    ncvValues.getAsLong(ExtendedProperties._ID);
                            }
                        }
                    }

                    // Send the meeting invite if there are attendees and we're the organizer AND
                    // if the Event itself is dirty (we might be syncing only because an exception
                    // is dirty, in which case we DON'T send email about the Event)
                    if (selfOrganizer &&
                            (getInt(entityValues, Events._SYNC_DIRTY) == 1)) {
                        EmailContent.Message msg =
                            CalendarUtilities.createMessageForEventId(mContext, eventId,
                                    EmailContent.Message.FLAG_OUTGOING_MEETING_INVITE, clientId,
                                    mAccount);
                        if (msg != null) {
                            userLog("Queueing invitation to ", msg.mTo);
                            mOutgoingMailList.add(msg);
                        }
                        // Make a list out of our tokenized attendees, if we have any
                        ArrayList<String> originalAttendeeList = new ArrayList<String>();
                        if (attendeeString != null) {
                            StringTokenizer st =
                                new StringTokenizer(attendeeString, ATTENDEE_TOKENIZER_DELIMITER);
                            while (st.hasMoreTokens()) {
                                originalAttendeeList.add(st.nextToken());
                            }
                        }
                        StringBuilder newTokenizedAttendees = new StringBuilder();
                        // See if any attendees have been dropped and while we're at it, build
                        // an updated String with tokenized attendee addresses
                        for (NamedContentValues ncv: entity.getSubValues()) {
                            if (ncv.uri.equals(Attendees.CONTENT_URI)) {
                                String attendeeEmail =
                                    ncv.values.getAsString(Attendees.ATTENDEE_EMAIL);
                                // Remove all found attendees
                                originalAttendeeList.remove(attendeeEmail);
                                newTokenizedAttendees.append(attendeeEmail);
                                newTokenizedAttendees.append(ATTENDEE_TOKENIZER_DELIMITER);
                            }
                        }
                        // Update extended properties with the new attendee list, if we have one
                        // Otherwise, create one (this would be the case for Events created on
                        // device or "legacy" events (before this code was added)
                        ContentValues cv = new ContentValues();
                        cv.put(ExtendedProperties.VALUE, newTokenizedAttendees.toString());
                        if (attendeeString != null) {
                            cr.update(ContentUris.withAppendedId(ExtendedProperties.CONTENT_URI,
                                    attendeeStringId), cv, null, null);
                        } else {
                            // If there wasn't an "attendees" property, insert one
                            cv.put(ExtendedProperties.NAME, EXTENDED_PROPERTY_ATTENDEES);
                            cv.put(ExtendedProperties.EVENT_ID, eventId);
                            cr.insert(ExtendedProperties.CONTENT_URI, cv);
                        }
                        // Whoever is left has been removed from the attendee list; send them
                        // a cancellation
                        for (String removedAttendee: originalAttendeeList) {
                            // Send a cancellation message to each of them
                            msg = CalendarUtilities.createMessageForEventId(mContext, eventId,
                                    Message.FLAG_OUTGOING_MEETING_CANCEL, clientId, mAccount,
                                    removedAttendee);
                            if (msg != null) {
                                // Just send it to the removed attendee
                                userLog("Queueing cancellation to removed attendee " + msg.mTo);
                                mOutgoingMailList.add(msg);
                            }
                        }
                    } else if (!selfOrganizer) {
                        // If we're not the organizer, see if we've changed our attendee status
                        // Our last synced attendee status is in ExtendedProperties, and we've
                        // retrieved it above as userAttendeeStatus
                        int currentStatus = entityValues.getAsInteger(Events.SELF_ATTENDEE_STATUS);
                        int syncStatus = Attendees.ATTENDEE_STATUS_NONE;
                        if (userAttendeeStatus != null) {
                            try {
                                syncStatus = Integer.parseInt(userAttendeeStatus);
                            } catch (NumberFormatException e) {
                                // Just in case somebody else mucked with this and it's not Integer
                            }
                        }
                        if ((currentStatus != syncStatus) &&
                                (currentStatus != Attendees.ATTENDEE_STATUS_NONE)) {
                            // If so, send a meeting reply
                            int messageFlag = 0;
                            switch (currentStatus) {
                                case Attendees.ATTENDEE_STATUS_ACCEPTED:
                                    messageFlag = Message.FLAG_OUTGOING_MEETING_ACCEPT;
                                    break;
                                case Attendees.ATTENDEE_STATUS_DECLINED:
                                    messageFlag = Message.FLAG_OUTGOING_MEETING_DECLINE;
                                    break;
                                case Attendees.ATTENDEE_STATUS_TENTATIVE:
                                    messageFlag = Message.FLAG_OUTGOING_MEETING_TENTATIVE;
                                    break;
                            }
                            // Make sure we have a valid status (messageFlag should never be zero)
                            if (messageFlag != 0 && userAttendeeStatusId >= 0) {
                                // Save away the new status
                                cidValues.clear();
                                cidValues.put(ExtendedProperties.VALUE,
                                        Integer.toString(currentStatus));
                                cr.update(ContentUris.withAppendedId(ExtendedProperties.CONTENT_URI,
                                        userAttendeeStatusId), cidValues, null, null);
                                // Send mail to the organizer advising of the new status
                                EmailContent.Message msg =
                                    CalendarUtilities.createMessageForEventId(mContext, eventId,
                                            messageFlag, clientId, mAccount);
                                if (msg != null) {
                                    userLog("Queueing invitation reply to " + msg.mTo);
                                    mOutgoingMailList.add(msg);
                                }
                            }
                        }
                    }
                }
                if (!first) {
                    s.end(); // Commands
                }
            } finally {
                eventIterator.close();
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Could not read dirty events.");
        }

        return false;
    }
}
