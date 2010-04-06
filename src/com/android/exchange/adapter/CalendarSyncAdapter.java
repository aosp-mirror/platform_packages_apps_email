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
import com.android.exchange.utility.CalendarUtilities;
import com.android.exchange.utility.Duration;

import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Entity;
import android.content.EntityIterator;
import android.content.OperationApplicationException;
import android.content.Entity.NamedContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.Calendar;
import android.provider.SyncStateContract;
import android.provider.Calendar.Attendees;
import android.provider.Calendar.Calendars;
import android.provider.Calendar.Events;
import android.provider.Calendar.EventsEntity;
import android.provider.Calendar.ExtendedProperties;
import android.provider.Calendar.Reminders;
import android.provider.Calendar.SyncState;
import android.provider.ContactsContract.RawContacts;
import android.text.TextUtils;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.UUID;
import java.util.Map.Entry;

/**
 * Sync adapter class for EAS calendars
 *
 */
public class CalendarSyncAdapter extends AbstractSyncAdapter {

    private static final String TAG = "EasCalendarSyncAdapter";
    // Since exceptions will have the same _SYNC_ID as the original event we have to check that
    // there's no original event when finding an item by _SYNC_ID
    private static final String SERVER_ID = Events._SYNC_ID + "=? AND " +
        Events.ORIGINAL_EVENT + " ISNULL";
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

    public static final String CALENDAR_SELECTION =
        Calendars._SYNC_ACCOUNT + "=? AND " + Calendars._SYNC_ACCOUNT_TYPE + "=?";
    private static final int CALENDAR_SELECTION_ID = 0;

    private static final String CATEGORY_TOKENIZER_DELIMITER = "\\";
    private static final String ATTENDEE_TOKENIZER_DELIMITER = CATEGORY_TOKENIZER_DELIMITER;

    private static final String FREE_BUSY_BUSY = "2";

    private static final ContentProviderOperation PLACEHOLDER_OPERATION =
        ContentProviderOperation.newInsert(Uri.EMPTY).build();

    private static final Uri sEventsUri = asSyncAdapter(Events.CONTENT_URI);
    private static final Uri sAttendeesUri = asSyncAdapter(Attendees.CONTENT_URI);
    private static final Uri sExtendedPropertiesUri = asSyncAdapter(ExtendedProperties.CONTENT_URI);
    private static final Uri sRemindersUri = asSyncAdapter(Reminders.CONTENT_URI);

    private static final Object sSyncKeyLock = new Object();

    private long mCalendarId = -1;

    private ArrayList<Long> mDeletedIdList = new ArrayList<Long>();
    private ArrayList<Long> mUploadedIdList = new ArrayList<Long>();
    private ArrayList<Long> mSendCancelIdList = new ArrayList<Long>();
    private ArrayList<Message> mOutgoingMailList = new ArrayList<Message>();

    private String[] mCalendarIdArgument;

    public CalendarSyncAdapter(Mailbox mailbox, EasSyncService service) {
        super(mailbox, service);

        Cursor c = mService.mContentResolver.query(Calendars.CONTENT_URI,
                new String[] {Calendars._ID}, CALENDAR_SELECTION,
                new String[] {mAccount.mEmailAddress, Email.EXCHANGE_ACCOUNT_MANAGER_TYPE}, null);
        try {
            if (c.moveToFirst()) {
                mCalendarId = c.getLong(CALENDAR_SELECTION_ID);
            } else {
                mCalendarId = CalendarUtilities.createCalendar(mService, mAccount, mMailbox);
            }
            mCalendarIdArgument = new String[] {Long.toString(mCalendarId)};
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

    class EasCalendarSyncParser extends AbstractSyncParser {

        String[] mBindArgument = new String[1];
        String mMailboxIdAsString;
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
            // TODO Make sure the Events, etc. are also deleted
            mContentResolver.delete(Calendars.CONTENT_URI, CALENDAR_SELECTION,
                    new String[] {mAccount.mEmailAddress, Email.EXCHANGE_ACCOUNT_MANAGER_TYPE});
        }

        public void addEvent(CalendarOperations ops, String serverId, boolean update)
                throws IOException {
            ContentValues cv = new ContentValues();
            cv.put(Events.CALENDAR_ID, mCalendarId);
            cv.put(Events._SYNC_ACCOUNT, mAccount.mEmailAddress);
            cv.put(Events._SYNC_ACCOUNT_TYPE, Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            cv.put(Events._SYNC_ID, serverId);
            cv.put(Events.HAS_ATTENDEE_DATA, 1);

            int allDayEvent = 0;
            String organizerName = null;
            String organizerEmail = null;
            int eventOffset = -1;
            int deleteOffset = -1;

            boolean firstTag = true;
            long eventId = -1;
            long startTime = -1;
            long endTime = -1;

            // Keep track of the attendees; exceptions will need them
            ArrayList<ContentValues> attendeeValues = new ArrayList<ContentValues>();
            int reminderMins = -1;

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
                        if (tag == Tags.CALENDAR_ATTENDEES) {
                            // This is an attendees-only update; just delete/re-add attendees
                            mBindArgument[0] = Long.toString(id);
                            ops.add(ContentProviderOperation.newDelete(Attendees.CONTENT_URI)
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
                        TimeZone tz = CalendarUtilities.tziStringToTimeZone(getValue());
                        if (tz != null) {
                            cv.put(Events.EVENT_TIMEZONE, tz.getID());
                        } else {
                            cv.put(Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
                        }
                        break;
                    case Tags.CALENDAR_START_TIME:
                        startTime = Utility.parseDateTimeToMillis(getValue());
                        cv.put(Events.DTSTART, startTime);
                        cv.put(Events.ORIGINAL_INSTANCE_TIME, startTime);
                        break;
                    case Tags.CALENDAR_END_TIME:
                        endTime = Utility.parseDateTimeToMillis(getValue());
                        break;
                    case Tags.CALENDAR_EXCEPTIONS:
                        exceptionsParser(ops, cv, attendeeValues, reminderMins);
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
                        ops.newExtendedProperty("dtstamp", getValue());
                        break;
                    case Tags.CALENDAR_MEETING_STATUS:
                        ops.newExtendedProperty("meeting_status", getValue());
                        break;
                    case Tags.CALENDAR_BUSY_STATUS:
                        ops.newExtendedProperty("busy_status", getValue());
                        break;
                    case Tags.CALENDAR_CATEGORIES:
                        String categories = categoriesParser(ops);
                        if (categories.length() > 0) {
                            ops.newExtendedProperty("categories", categories);
                        }
                        break;
                    default:
                        skipTag();
                }
            }

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
                if (eventId < 0) {
                    ops.newAttendee(attendeeCv);
                } else {
                    ops.updatedAttendee(attendeeCv, eventId);
                }
            }

            // Store email addresses of attendees (in a tokenizable string) in ExtendedProperties
            if (attendeeValues.size() > 0) {
                StringBuilder sb = new StringBuilder();
                for (ContentValues attendee: attendeeValues) {
                    sb.append(attendee.getAsString(Attendees.ATTENDEE_EMAIL));
                    sb.append(ATTENDEE_TOKENIZER_DELIMITER);
                }
                ops.newExtendedProperty("attendees", sb.toString());
            }

            // If there's no recurrence, set DTEND to the end time
            if (!cv.containsKey(Events.RRULE)) {
                cv.put(Events.DTEND, endTime);
                cv.put(Events.LAST_DATE, endTime);
            }
            // Set the DURATION using rfc2445
            // For all day events, make sure hour, minute, and second are zero for DTSTART
            if (allDayEvent != 0) {
                cv.put(Events.DURATION, "P1D");
                GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
                cal.setTimeInMillis(startTime);
                cal.set(GregorianCalendar.HOUR_OF_DAY, 0);
                cal.set(GregorianCalendar.MINUTE, 0);
                cal.set(GregorianCalendar.SECOND, 0);
                cv.put(Events.DTSTART, cal.getTimeInMillis());
                cv.put(Events.ORIGINAL_INSTANCE_TIME, cal.getTimeInMillis());
            } else {
                cv.put(Events.DURATION, "P" + ((endTime - startTime) / MINUTES) + "M");
            }

            // Put the real event in the proper place in the ops ArrayList
            if (eventOffset >= 0) {
                if (isValidEventValues(cv, update)) {
                    ops.set(eventOffset, ContentProviderOperation
                            .newInsert(sEventsUri).withValues(cv).build());
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

        private boolean isValidEventValues(ContentValues cv, boolean update) {
            // Do a sanity check on this set of values
            // At the very least, we must get DTSTART and _SYNC_DATA (uid)
            // If it's invalid, log the columns we've got (will help debugging)
            if (!cv.containsKey(Events.DTSTART) || !cv.containsKey(Events._SYNC_DATA)) {
                userLog(TAG, (update ? "Changed" : "New") + " event invalid; skipping");
                StringBuilder sb = new StringBuilder("Columns: ");
                for (Entry<String, Object> entry: cv.valueSet()) {
                    sb.append(entry.getKey());
                    sb.append(' ');
                }
                userLog(TAG, sb.toString());
                return false;
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
                ArrayList<ContentValues> attendeeValues, int reminderMins) throws IOException {
            ContentValues cv = new ContentValues();
            cv.put(Events.CALENDAR_ID, mCalendarId);
            cv.put(Events._SYNC_ACCOUNT, mAccount.mEmailAddress);
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

            // This column is the key that links the exception to the serverId
            // TODO Make sure calendar knows this isn't globally unique!!
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
                        cv.put(Events.ALL_DAY, getValueInt());
                        break;
                    case Tags.BASE_BODY:
                        cv.put(Events.DESCRIPTION, bodyParser());
                        break;
                    case Tags.CALENDAR_BODY:
                        cv.put(Events.DESCRIPTION, getValue());
                        break;
                    case Tags.CALENDAR_START_TIME:
                        cv.put(Events.DTSTART, Utility.parseDateTimeToMillis(getValue()));
                        break;
                    case Tags.CALENDAR_END_TIME:
                        cv.put(Events.DTEND, Utility.parseDateTimeToMillis(getValue()));
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

                        // TODO How to handle these items that are linked to event id!

//                    case Tags.CALENDAR_DTSTAMP:
//                        ops.newExtendedProperty("dtstamp", getValue());
//                        break;
//                    case Tags.CALENDAR_BUSY_STATUS:
//                        // TODO Try to fit this into Calendar scheme
//                        ops.newExtendedProperty("busy_status", getValue());
//                        break;
//                    case Tags.CALENDAR_REMINDER_MINS_BEFORE:
//                        ops.newReminder(getValueInt());
//                        break;

                        // Not yet handled
                    default:
                        skipTag();
                }
            }

            // We need a _sync_id, but it can't be the parent's id, so we generate one
            cv.put(Events._SYNC_ID, parentCv.getAsString(Events._SYNC_ID) + '_' +
                    exceptionStartTime);

            if (!cv.containsKey(Events.DTSTART)) {
                cv.put(Events.DTSTART, parentCv.getAsLong(Events.DTSTART));
            }
            if (!cv.containsKey(Events.DTEND)) {
                cv.put(Events.DTEND, parentCv.getAsLong(Events.DTEND));
            }

            // Add the exception insert
            int exceptionStart = ops.mCount;
            ops.newException(cv);
            // Also add the attendees, because they need to be copied over from the parent event
            if (attendeeValues != null) {
                for (ContentValues attValues: attendeeValues) {
                    ops.newAttendee(attValues, exceptionStart);
                }
            }
            // And add the parent's reminder value
            if (reminderMins > 0) {
                ops.newReminder(reminderMins, exceptionStart);
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
                ArrayList<ContentValues> attendeeValues, int reminderMins) throws IOException {
            while (nextTag(Tags.CALENDAR_EXCEPTIONS) != END) {
                switch (tag) {
                    case Tags.CALENDAR_EXCEPTION:
                        exceptionParser(ops, cv, attendeeValues, reminderMins);
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
            ArrayList<ContentValues> attendeeValues = new ArrayList<ContentValues>();
            while (nextTag(Tags.CALENDAR_ATTENDEES) != END) {
                switch (tag) {
                    case Tags.CALENDAR_ATTENDEE:
                        attendeeValues.add(attendeeParser(ops, eventId));
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
            if (eventId < 0) {
                ops.newAttendee(cv);
            } else {
                ops.updatedAttendee(cv, eventId);
            }
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
            mBindArgument[0] = serverId;
            return mContentResolver.query(mAccountUri, ID_PROJECTION, SERVER_ID,
                    mBindArgument, null);
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
                        mContentResolver.update(ContentUris.withAppendedId(sEventsUri, eventId), cv,
                                null, null);
                    }
                }
                // Delete events marked for deletion
                if (!mDeletedIdList.isEmpty()) {
                    for (long eventId: mDeletedIdList) {
                        mContentResolver.delete(ContentUris.withAppendedId(sEventsUri, eventId),
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
                            ContentUris.withAppendedId(sEventsUri, id))
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
                    .newInsert(sAttendeesUri)
                    .withValues(cv)
                    .withValueBackReference(Attendees.EVENT_ID, eventStart)
                    .build());
        }

        public void updatedAttendee(ContentValues cv, long id) {
            cv.put(Attendees.EVENT_ID, id);
            add(ContentProviderOperation.newInsert(sAttendeesUri).withValues(cv).build());
        }

        public void newException(ContentValues cv) {
            add(ContentProviderOperation.newInsert(sEventsUri).withValues(cv).build());
        }

        public void newExtendedProperty(String name, String value) {
            add(ContentProviderOperation
                    .newInsert(sExtendedPropertiesUri)
                    .withValue(ExtendedProperties.NAME, name)
                    .withValue(ExtendedProperties.VALUE, value)
                    .withValueBackReference(ExtendedProperties.EVENT_ID, mEventStart)
                    .build());
        }

        public void newReminder(int mins, int eventStart) {
            add(ContentProviderOperation
                    .newInsert(sRemindersUri)
                    .withValue(Reminders.MINUTES, mins)
                    .withValue(Reminders.METHOD, Reminders.METHOD_DEFAULT)
                    .withValueBackReference(ExtendedProperties.EVENT_ID, eventStart)
                    .build());
        }

        public void newReminder(int mins) {
            newReminder(mins, mEventStart);
        }

        public void delete(long id, String syncId) {
            add(ContentProviderOperation
                    .newDelete(ContentUris.withAppendedId(sEventsUri, id)).build());
            // Delete the exceptions for this Event (CalendarProvider doesn't do this)
            add(ContentProviderOperation
                    .newDelete(sEventsUri).withSelection(Events.ORIGINAL_EVENT + "=?",
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
        boolean isException = (clientId == null);
        boolean hasAttendees = false;
        boolean isChange = entityValues.containsKey(Events._SYNC_ID);

        // Get the event's time zone
        String timeZoneName = entityValues.getAsString(Events.EVENT_TIMEZONE);
        if (timeZoneName == null) {
            timeZoneName = TimeZone.getDefault().getID();
        }
        TimeZone eventTimeZone = TimeZone.getTimeZone(timeZoneName);

        if (!isException) {
            // A time zone is required in all EAS events; we'll use the default if none is set
            // Exchange 2003 seems to require this first... :-)
            String timeZone = CalendarUtilities.timeZoneToTziString(eventTimeZone);
            s.data(Tags.CALENDAR_TIME_ZONE, timeZone);
        }

        // Busy status is only required for 2.5, but we need to send it with later
        // versions as well, because if we don't, the server will clear it.
        s.data(Tags.CALENDAR_BUSY_STATUS, FREE_BUSY_BUSY);

        boolean allDay = false;
        if (entityValues.containsKey(Events.ALL_DAY)) {
            Integer ade = entityValues.getAsInteger(Events.ALL_DAY);
            if (ade != 0) {
                allDay = true;
            }
            s.data(Tags.CALENDAR_ALL_DAY_EVENT, ade.toString());
        }

        long startTime = entityValues.getAsLong(Events.DTSTART);
        if (allDay) {
            // Calendar uses GMT for all day events
            GregorianCalendar gmtCal = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
            gmtCal.setTimeInMillis(startTime);
            // This calendar must be in the event's time zone
            GregorianCalendar allDayCal = new GregorianCalendar(eventTimeZone);
            // Set a new calendar with correct y/m/d, but h/m/s set to zero
            allDayCal.set(gmtCal.get(GregorianCalendar.YEAR), gmtCal.get(GregorianCalendar.MONTH),
                    gmtCal.get(GregorianCalendar.DATE), 0, 0, 0);
            // Use this as the start time
            s.data(Tags.CALENDAR_START_TIME,
                    CalendarUtilities.millisToEasDateTime(allDayCal.getTimeInMillis()));
            // Use a day later as the end time
            allDayCal.add(GregorianCalendar.DATE, 1);
            s.data(Tags.CALENDAR_END_TIME,
                    CalendarUtilities.millisToEasDateTime(allDayCal.getTimeInMillis()));
         } else {
            s.data(Tags.CALENDAR_START_TIME, CalendarUtilities.millisToEasDateTime(startTime));
            // If we've got DTEND, we use it
            if (entityValues.containsKey(Events.DTEND)) {
                s.data(Tags.CALENDAR_END_TIME, CalendarUtilities.millisToEasDateTime(
                        entityValues.getAsLong(Events.DTEND)));
            } else {
                // Convert duration into millis and add it to DTSTART for DTEND
                // We'll use 1 hour as a default (if there's no duration specified or if the parse
                // of the duration fails)
                long durationMillis = HOURS;
                if (entityValues.containsKey(Events.DURATION)) {
                    Duration duration = new Duration();
                    try {
                        duration.parse(entityValues.getAsString(Events.DURATION));
                    } catch (ParseException e) {
                        // Can't do much about this; use the default (1 hour)
                    }
                }
                s.data(Tags.CALENDAR_END_TIME,
                        CalendarUtilities.millisToEasDateTime(startTime + durationMillis));
            }
        }

        s.data(Tags.CALENDAR_DTSTAMP,
                CalendarUtilities.millisToEasDateTime(System.currentTimeMillis()));

        String loc = entityValues.getAsString(Events.EVENT_LOCATION);
        if (!TextUtils.isEmpty(loc)) {
            if (mService.mProtocolVersionDouble < Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
                // EAS 2.5 doesn't like bare line feeds
                loc = Utility.replaceBareLfWithCrlf(loc);
            }
            s.data(Tags.CALENDAR_LOCATION, loc);
        }
        s.writeStringValue(entityValues, Events.TITLE, Tags.CALENDAR_SUBJECT);

        Integer visibility = entityValues.getAsInteger(Events.VISIBILITY);
        if (visibility != null) {
            s.data(Tags.CALENDAR_SENSITIVITY, decodeVisibility(visibility));
        } else {
            // Default to private if not set
            s.data(Tags.CALENDAR_SENSITIVITY, "1");
        }

        if (!isException) {
            String desc = entityValues.getAsString(Events.DESCRIPTION);
            if (desc != null && desc.length() > 0) {
                if (mService.mProtocolVersionDouble >= Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
                    s.start(Tags.BASE_BODY);
                    s.data(Tags.BASE_TYPE, "1");
                    s.data(Tags.BASE_DATA, desc);
                    s.end();
                } else {
                    // EAS 2.5 doesn't like bare line feeds
                    s.data(Tags.CALENDAR_BODY, Utility.replaceBareLfWithCrlf(desc));
                }
            }

            // We only write organizer email if the event is new (not a change)
            // Exchange 2003 will reject upsyncs of changed events with organizer email
            if (!isChange) {
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
                    s.writeStringValue(ncvValues, "dtstamp", Tags.CALENDAR_DTSTAMP);
                    String categories = ncvValues.getAsString("categories");
                    if (categories != null) {
                        // Send all the categories back to the server
                        // We've saved them as a String of delimited tokens
                        StringTokenizer st =
                            new StringTokenizer(categories, CATEGORY_TOKENIZER_DELIMITER);
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
                        if (mService.mProtocolVersionDouble >=
                                Eas.SUPPORTED_PROTOCOL_EX2007_DOUBLE) {
                            s.data(Tags.CALENDAR_ATTENDEE_TYPE, "1"); // Required
                        }
                        s.end(); // Attendee
                     }
                }
            }
            if (hasAttendees) {
                s.end();  // Attendees
            }

            // We only write organizer name if the event is new (not a change)
            // Exchange 2003 will reject upsyncs of changed events with organizer name
            if (!isChange && organizerName != null) {
                s.data(Tags.CALENDAR_ORGANIZER_NAME, organizerName);
            }
        } else {
            // TODO Add reminders to exceptions (allow them to be specified!)
            Long originalTime = entityValues.getAsLong(Events.ORIGINAL_INSTANCE_TIME);
            if (originalTime != null) {
                s.data(Tags.CALENDAR_EXCEPTION_START_TIME,
                        CalendarUtilities.millisToEasDateTime(originalTime));
            } else {
                // Illegal; what should we do?
            }

            // Send exception deleted flag if necessary
            Integer eventStatus = entityValues.getAsInteger(Events.STATUS);
            if (eventStatus != null && eventStatus.equals(Events.STATUS_CANCELED)) {
                s.data(Tags.CALENDAR_EXCEPTION_IS_DELETED, "1");
            }
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
                    int cnt = cr.update(sEventsUri, cv, SERVER_ID, new String[] {serverId});
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
                cr.delete(ContentUris.withAppendedId(sEventsUri, orphan), null, null);
            }
            orphanedExceptions.clear();

            // Now we can go through dirty/marked top-level events and send them back to the server
            EntityIterator eventIterator = EventsEntity.newEntityIterator(
                    cr.query(sEventsUri, null, DIRTY_OR_MARKED_TOP_LEVEL_IN_CALENDAR,
                            mCalendarIdArgument, null), cr);
            ContentValues cidValues = new ContentValues();
            try {
                boolean first = true;
                while (eventIterator.hasNext()) {
                    Entity entity = eventIterator.next();

                    // For each of these entities, create the change commands
                    ContentValues entityValues = entity.getEntityValues();
                    String serverId = entityValues.getAsString(Events._SYNC_ID);

                    // Find our uid in the entity; otherwise create one
                    String clientId = entityValues.getAsString(Events._SYNC_DATA);
                    if (clientId == null) {
                        clientId = UUID.randomUUID().toString();
                    }

                    // EAS 2.5 needs: BusyStatus DtStamp EndTime Sensitivity StartTime TimeZone UID
                    // We can generate all but what we're testing for below
                    String organizerEmail = entityValues.getAsString(Events.ORGANIZER);
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
                        cr.update(ContentUris.withAppendedId(sEventsUri, eventId), cidValues,
                                null, null);
                    } else {
                        if (entityValues.getAsInteger(Events.DELETED) == 1) {
                            userLog("Deleting event with serverId: ", serverId);
                            s.start(Tags.SYNC_DELETE).data(Tags.SYNC_SERVER_ID, serverId).end();
                            mDeletedIdList.add(eventId);
                            if (entityValues.getAsString(Events.ORGANIZER)
                                    .equalsIgnoreCase(mAccount.mEmailAddress)) {
                                mSendCancelIdList.add(eventId);
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
                        cr.update(ContentUris.withAppendedId(sEventsUri, eventId), cidValues,
                                null, null);
                        s.start(Tags.SYNC_CHANGE).data(Tags.SYNC_SERVER_ID, serverId);
                    }
                    s.start(Tags.SYNC_APPLICATION_DATA);

                    sendEvent(entity, clientId, s);

                    // Now, the hard part; find exceptions for this event
                    if (serverId != null) {
                        String calendarId = Long.toString(mCalendarId);
                        EntityIterator exIterator = EventsEntity.newEntityIterator(
                                cr.query(sEventsUri, null, ORIGINAL_EVENT_AND_CALENDAR,
                                        new String[] {serverId, calendarId}, null), cr);
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
                                if (getInt(exValues, Events.STATUS) == Events.STATUS_CANCELED) {
                                    // Add the eventId of the exception to the proper list, so that
                                    // the dirty bit is cleared or the event is deleted after the
                                    // sync has completed
                                    mDeletedIdList.add(exEventId);
                                    flag = Message.FLAG_OUTGOING_MEETING_CANCEL;
                                } else {
                                    mUploadedIdList.add(exEventId);
                                    flag = Message.FLAG_OUTGOING_MEETING_INVITE;
                                }

                                // Copy subvalues into the exception; otherwise, we won't see the
                                // attendees when preparing the message
                                for (NamedContentValues ncv: entity.getSubValues()) {
                                    exEntity.addSubValue(ncv.uri, ncv.values);
                                }
                                // Copy version so the ics attachment shows the proper sequence #
                                exValues.put(Events._SYNC_VERSION,
                                        entityValues.getAsString(Events._SYNC_VERSION));
                                // Copy location so that it's included in the outgoing email
                                if (entityValues.containsKey(Events.EVENT_LOCATION)) {
                                    exValues.put(Events.EVENT_LOCATION,
                                        entityValues.getAsString(Events.EVENT_LOCATION));
                                }

                                Message msg =
                                    CalendarUtilities.createMessageForEntity(mContext,
                                            exEntity, flag, clientId, mAccount);
                                if (msg != null) {
                                    userLog("Queueing exception update to " + msg.mTo);
                                    mOutgoingMailList.add(msg);
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

                    // Send the meeting invite if there are attendees and we're the organizer AND
                    // if the Event itself is dirty (we might be syncing only because an exception
                    // is dirty, in which case we DON'T send email about the Event)
                    boolean selfOrganizer = organizerEmail.equalsIgnoreCase(mAccount.mEmailAddress);

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
                        // Retrieve our tokenized string of attendee email addresses
                        String attendeeString = null;
                        for (NamedContentValues ncv: entity.getSubValues()) {
                            if (ncv.uri.equals(ExtendedProperties.CONTENT_URI)) {
                                String propertyName =
                                    ncv.values.getAsString(ExtendedProperties.NAME);
                                if (propertyName.equals("attendees")) {
                                    attendeeString =
                                        ncv.values.getAsString(ExtendedProperties.VALUE);
                                    break;
                                }
                            }
                        }
                        // Make a list out of them, if we have any
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
                            // Now, we have to find the id of this row in ExtendedProperties
                            Cursor epCursor = cr.query(ExtendedProperties.CONTENT_URI,
                                    new String[] {ExtendedProperties._ID},
                                    ExtendedProperties.EVENT_ID + "=? AND name=\"attendees\"",
                                    new String[] {Long.toString(eventId)}, null);
                            if (epCursor != null) {
                                try {
                                    // There's no reason this should fail (after all, we found it
                                    // in the subValues; if it's gone, though, that's fine
                                    if (epCursor.moveToFirst()) {
                                        cr.update(ContentUris.withAppendedId(
                                                ExtendedProperties.CONTENT_URI,
                                                epCursor.getLong(0)), cv, null, null);
                                    }
                                } finally {
                                    epCursor.close();
                                }
                            }
                        } else {
                            // If there wasn't an "attendees" property, insert one
                            cv.put(ExtendedProperties.NAME, "attendees");
                            cv.put(ExtendedProperties.EVENT_ID, eventId);
                            cr.insert(ExtendedProperties.CONTENT_URI, cv);
                        }
                        // Whoever is left has been removed from the attendee list; send them
                        // a cancellation
                        for (String removedAttendee: originalAttendeeList) {
                            // Send a cancellation message to each of them
                            msg = CalendarUtilities.createMessageForEventId(mContext, eventId,
                                    Message.FLAG_OUTGOING_MEETING_CANCEL, clientId, mAccount,
                                    false);
                            if (msg != null) {
                                // Just send it to the removed attendee
                                msg.mTo = removedAttendee;
                                userLog("Queueing cancellation to removed attendee " + msg.mTo);
                                mOutgoingMailList.add(msg);
                            }
                        }
                    } else if (!selfOrganizer) {
                        // If we're not the organizer, see if we've changed our attendee status
                        int currentStatus = entityValues.getAsInteger(Events.SELF_ATTENDEE_STATUS);
                        String adapterData = entityValues.getAsString(Events.SYNC_ADAPTER_DATA);
                        int syncStatus = Attendees.ATTENDEE_STATUS_NONE;
                        if (adapterData != null) {
                            syncStatus = Integer.parseInt(adapterData);
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
                            if (messageFlag != 0) {
                                // Save away the new status
                                cidValues.clear();
                                cidValues.put(Events.SYNC_ADAPTER_DATA,
                                        Integer.toString(currentStatus));
                                cr.update(ContentUris.withAppendedId(sEventsUri, eventId),
                                        cidValues, null, null);
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
