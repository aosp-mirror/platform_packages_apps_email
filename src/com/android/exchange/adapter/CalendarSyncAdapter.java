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

import com.android.email.provider.EmailContent.Mailbox;
import com.android.exchange.Eas;
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
import android.pim.DateException;
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
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.StringTokenizer;
import java.util.TimeZone;

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
    private static final String DIRTY_TOP_LEVEL =
        Events._SYNC_DIRTY + "=1 AND " + Events.ORIGINAL_EVENT + " ISNULL";
    private static final String DIRTY_EXCEPTION =
        Events._SYNC_DIRTY + "=1 AND " + Events.ORIGINAL_EVENT + " NOTNULL";
    private static final String DIRTY_IN_CALENDAR =
        Events._SYNC_DIRTY + "=1 AND " + Events.CALENDAR_ID + "=?";
    private static final String CLIENT_ID_SELECTION = Events._SYNC_LOCAL_ID + "=?";
    private static final String ATTENDEES_EXCEPT_ORGANIZER = Attendees.EVENT_ID + "=? AND " +
        Attendees.ATTENDEE_RELATIONSHIP + "!=" + Attendees.RELATIONSHIP_ORGANIZER;
    private static final String[] ID_PROJECTION = new String[] {Events._ID};
    private static final String[] ORIGINAL_EVENT_PROJECTION = new String[] {Events.ORIGINAL_EVENT};

    private static final String CALENDAR_SELECTION =
        Calendars._SYNC_ACCOUNT + "=? AND " + Calendars._SYNC_ACCOUNT_TYPE + "=?";
    private static final int CALENDAR_SELECTION_ID = 0;

    private static final String CATEGORY_TOKENIZER_DELIMITER = "\\";

    private static final ContentProviderOperation PLACEHOLDER_OPERATION =
        ContentProviderOperation.newInsert(Uri.EMPTY).build();
    
    private static final Uri sEventsUri = asSyncAdapter(Events.CONTENT_URI);
    private static final Uri sAttendeesUri = asSyncAdapter(Attendees.CONTENT_URI);
    private static final Uri sExtendedPropertiesUri = asSyncAdapter(ExtendedProperties.CONTENT_URI);
    private static final Uri sRemindersUri = asSyncAdapter(Reminders.CONTENT_URI);

    private android.accounts.Account mAccountManagerAccount;
    private long mCalendarId = -1;

    private ArrayList<Long> mDeletedIdList = new ArrayList<Long>();
    private ArrayList<Long> mUpdatedIdList = new ArrayList<Long>();

    public CalendarSyncAdapter(Mailbox mailbox, EasSyncService service) {
        super(mailbox, service);

        Cursor c = mService.mContentResolver.query(Calendars.CONTENT_URI,
                new String[] {Calendars._ID}, CALENDAR_SELECTION,
                new String[] {mAccount.mEmailAddress, Eas.ACCOUNT_MANAGER_TYPE}, null);
        try {
            if (c.moveToFirst()) {
                mCalendarId = c.getLong(CALENDAR_SELECTION_ID);
            }
        } finally {
            c.close();
        }

        if (mCalendarId == -1) {
            mCalendarId = Long.parseLong(mailbox.mSyncStatus);
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
        ContentProviderClient client =
            mService.mContentResolver.acquireContentProviderClient(Calendar.CONTENT_URI);
        try {
            byte[] data = SyncStateContract.Helpers.get(client,
                    asSyncAdapter(Calendar.SyncState.CONTENT_URI), getAccountManagerAccount());
            if (data == null || data.length == 0) {
                // Initialize the SyncKey
                setSyncKey("0", false);
                return "0";
            } else {
                return new String(data);
            }
        } catch (RemoteException e) {
            throw new IOException("Can't get SyncKey from ContactsProvider");
        }
    }

    /**
     * We only need to set this when we're forced to make the SyncKey "0" (a reset).  In all other
     * cases, the SyncKey is set within Calendar
     */
    @Override
    public void setSyncKey(String syncKey, boolean inCommands) throws IOException {
        if ("0".equals(syncKey) || !inCommands) {
            ContentProviderClient client =
                mService.mContentResolver
                    .acquireContentProviderClient(Calendar.CONTENT_URI);
            try {
                SyncStateContract.Helpers.set(client, asSyncAdapter(Calendar.SyncState.CONTENT_URI),
                        getAccountManagerAccount(), syncKey.getBytes());
                userLog("SyncKey set to ", syncKey, " in CalendarProvider");
           } catch (RemoteException e) {
                throw new IOException("Can't set SyncKey in CalendarProvider");
            }
        }
        mMailbox.mSyncKey = syncKey;
    }

    public android.accounts.Account getAccountManagerAccount() {
        if (mAccountManagerAccount == null) {
            mAccountManagerAccount =
                new android.accounts.Account(mAccount.mEmailAddress, Eas.ACCOUNT_MANAGER_TYPE);
        }
        return mAccountManagerAccount;
    }

    class EasCalendarSyncParser extends AbstractSyncParser {

        String[] mBindArgument = new String[1];
        String mMailboxIdAsString;
        Uri mAccountUri;
        CalendarOperations mOps = new CalendarOperations();

        public EasCalendarSyncParser(InputStream in, CalendarSyncAdapter adapter)
                throws IOException {
            super(in, adapter);
            setDebug(true);
            setLoggingTag("CalendarParser");
            mAccountUri = Events.CONTENT_URI;
        }

        @Override
        public void wipe() {
            // Delete the calendar associated with this account
            // TODO Make sure the Events, etc. are also deleted
            mContentResolver.delete(Calendars.CONTENT_URI, CALENDAR_SELECTION,
                    new String[] {mAccount.mEmailAddress, Eas.ACCOUNT_MANAGER_TYPE});
        }

        public void addEvent(CalendarOperations ops, String serverId, boolean update)
                throws IOException {
            ContentValues cv = new ContentValues();
            cv.put(Events.CALENDAR_ID, mCalendarId);
            cv.put(Events._SYNC_ACCOUNT, mAccount.mEmailAddress);
            cv.put(Events._SYNC_ACCOUNT_TYPE, Eas.ACCOUNT_MANAGER_TYPE);
            cv.put(Events._SYNC_ID, serverId);

            int allDayEvent = 0;
            String organizerName = null;
            String organizerEmail = null;
            int eventOffset = -1;

            boolean firstTag = true;
            long eventId = -1;
            long startTime = -1;
            long endTime = -1;
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
                            ops.delete(id);
                            // Add a placeholder event so that associated tables can reference
                            // this as a back reference.  We add the event at the end of the method
                            eventOffset = ops.newEvent(PLACEHOLDER_OPERATION);
                        }
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
                        attendeesParser(ops, organizerName, organizerEmail, eventId);
                        break;
                    case Tags.BASE_BODY:
                        cv.put(Events.DESCRIPTION, bodyParser());
                        break;
                    case Tags.CALENDAR_BODY:
                        cv.put(Events.DESCRIPTION, getValue());
                        break;
                    case Tags.CALENDAR_TIME_ZONE:
                        TimeZone tz = CalendarUtilities.parseTimeZone(getValue());
                        if (tz != null) {
                            cv.put(Events.EVENT_TIMEZONE, tz.getID());
                        } else {
                            cv.put(Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
                        }
                        break;
                    case Tags.CALENDAR_START_TIME:
                        startTime = CalendarUtilities.parseDateTimeToMillis(getValue());
                        cv.put(Events.DTSTART, startTime);
                        cv.put(Events.ORIGINAL_INSTANCE_TIME, startTime);
                        break;
                    case Tags.CALENDAR_END_TIME:
                        endTime = CalendarUtilities.parseDateTimeToMillis(getValue());
                        break;
                    case Tags.CALENDAR_EXCEPTIONS:
                        exceptionsParser(ops, cv);
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
                        ops.newReminder(getValueInt());
                        cv.put(Events.HAS_ALARM, 1);
                        break;
                    // The following are fields we should save (for changes), though they don't
                    // relate to data used by CalendarProvider at this point
                    case Tags.CALENDAR_UID:
                        ops.newExtendedProperty("uid", getValue());
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

            // If there's no recurrence, set DTEND to the end time
            if (!cv.containsKey(Events.RRULE)) {
                cv.put(Events.DTEND, endTime);
                cv.put(Events.LAST_DATE, endTime);
            }

            // Set the DURATION using rfc2445
            if (allDayEvent != 0) {
                cv.put(Events.DURATION, "P1D");
            } else {
                cv.put(Events.DURATION, "P" + ((endTime - startTime) / MINUTES) + "M");
            }

            // Put the real event in the proper place in the ops ArrayList
            if (eventOffset >= 0) {
                ops.set(eventOffset, ContentProviderOperation
                        .newInsert(sEventsUri).withValues(cv).build());
            }
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

        private void exceptionParser(CalendarOperations ops, ContentValues parentCv)
                throws IOException {
            ContentValues cv = new ContentValues();
            cv.put(Events.CALENDAR_ID, mCalendarId);
            cv.put(Events._SYNC_ACCOUNT, mAccount.mEmailAddress);
            cv.put(Events._SYNC_ACCOUNT_TYPE, Eas.ACCOUNT_MANAGER_TYPE);

            // It appears that these values have to be copied from the parent if they are to appear
            // Note that they can be overridden below
            cv.put(Events.ORGANIZER, parentCv.getAsString(Events.ORGANIZER));
            cv.put(Events.TITLE, parentCv.getAsString(Events.TITLE));
            cv.put(Events.DESCRIPTION, parentCv.getAsBoolean(Events.DESCRIPTION));
            cv.put(Events.ORIGINAL_ALL_DAY, parentCv.getAsInteger(Events.ALL_DAY));

            // This column is the key that links the exception to the serverId
            // TODO Make sure calendar knows this isn't globally unique!!
            cv.put(Events.ORIGINAL_EVENT, parentCv.getAsString(Events._SYNC_ID));

            while (nextTag(Tags.SYNC_APPLICATION_DATA) != END) {
                switch (tag) {
                    case Tags.CALENDAR_EXCEPTION_START_TIME:
                        cv.put(Events.ORIGINAL_INSTANCE_TIME,
                                CalendarUtilities.parseDateTimeToMillis(getValue()));
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
                        cv.put(Events.DTSTART, CalendarUtilities.parseDateTimeToMillis(getValue()));
                        break;
                    case Tags.CALENDAR_END_TIME:
                        cv.put(Events.DTEND, CalendarUtilities.parseDateTimeToMillis(getValue()));
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

            if (!cv.containsKey(Events.DTSTART)) {
                cv.put(Events.DTSTART, parentCv.getAsLong(Events.DTSTART));
            }
            if (!cv.containsKey(Events.DTEND)) {
                cv.put(Events.DTEND, parentCv.getAsLong(Events.DTEND));
            }
            // TODO See if this is necessary
            //cv.put(Events.LAST_DATE, cv.getAsLong(Events.DTEND));

            ops.newException(cv);
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

        private void exceptionsParser(CalendarOperations ops, ContentValues cv)
                throws IOException {
            while (nextTag(Tags.CALENDAR_EXCEPTIONS) != END) {
                switch (tag) {
                    case Tags.CALENDAR_EXCEPTION:
                        exceptionParser(ops, cv);
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
                    default:
                        skipTag();
                }
            }
            return categories.toString();
        }

        private String attendeesParser(CalendarOperations ops, String organizerName,
                String organizerEmail, long eventId) throws IOException {
            String body = null;
            // First, handle the organizer (who IS an attendee on device, but NOT in EAS)
            if (organizerName != null || organizerEmail != null) {
                ContentValues cv = new ContentValues();
                if (organizerName != null) {
                    cv.put(Attendees.ATTENDEE_NAME, organizerName);
                }
                if (organizerEmail != null) {
                    cv.put(Attendees.ATTENDEE_EMAIL, organizerEmail);
                }
                cv.put(Attendees.ATTENDEE_RELATIONSHIP, Attendees.RELATIONSHIP_ORGANIZER);
                cv.put(Attendees.ATTENDEE_TYPE, Attendees.TYPE_REQUIRED);
                if (eventId < 0) {
                    ops.newAttendee(cv);
                } else {
                    ops.updatedAttendee(cv, eventId);
                }
            }
            while (nextTag(Tags.CALENDAR_ATTENDEES) != END) {
                switch (tag) {
                    case Tags.CALENDAR_ATTENDEE:
                        attendeeParser(ops, eventId);
                        break;
                    default:
                        skipTag();
                }
            }
            return body;
        }

        private void attendeeParser(CalendarOperations ops, long eventId) throws IOException {
            ContentValues cv = new ContentValues();
            while (nextTag(Tags.CALENDAR_ATTENDEE) != END) {
                switch (tag) {
                    case Tags.CALENDAR_ATTENDEE_EMAIL:
                        cv.put(Attendees.ATTENDEE_EMAIL, getValue());
                        break;
                    case Tags.CALENDAR_ATTENDEE_NAME:
                        cv.put(Attendees.ATTENDEE_NAME, getValue());
                        break;
                    case Tags.CALENDAR_ATTENDEE_STATUS:
                        int status = getValueInt();
                        cv.put(Attendees.ATTENDEE_STATUS,
                                (status == 2) ? Attendees.ATTENDEE_STATUS_TENTATIVE :
                                (status == 3) ? Attendees.ATTENDEE_STATUS_ACCEPTED :
                                (status == 4) ? Attendees.ATTENDEE_STATUS_DECLINED :
                                (status == 5) ? Attendees.ATTENDEE_STATUS_INVITED :
                                    Attendees.ATTENDEE_STATUS_NONE);
                        break;
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
                                ops.delete(c.getLong(0));
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
                    getAccountManagerAccount(), mMailbox.mSyncKey.getBytes()));

            // Execute these all at once...
            mOps.execute();

            if (mOps.mResults != null) {
                // Clear dirty flags for Events sent to server
                ContentValues cv = new ContentValues();
                cv.put(Events._SYNC_DIRTY, 0);
                mContentResolver.update(sEventsUri, cv, DIRTY_IN_CALENDAR,
                        new String[] {Long.toString(mCalendarId)});
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
                    mOps.add(ContentProviderOperation.newUpdate(
                            ContentUris.withAppendedId(sEventsUri, c.getLong(0)))
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
        private int mCount = 0;
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

        public void newAttendee(ContentValues cv) {
            add(ContentProviderOperation
                    .newInsert(sAttendeesUri)
                    .withValues(cv)
                    .withValueBackReference(Attendees.EVENT_ID, mEventStart)
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

        public void newReminder(int mins) {
            add(ContentProviderOperation
                    .newInsert(sRemindersUri)
                    .withValue(Reminders.MINUTES, mins)
                    .withValue(Reminders.METHOD, Reminders.METHOD_DEFAULT)
                    .withValueBackReference(ExtendedProperties.EVENT_ID, mEventStart)
                    .build());
        }

        public void delete(long id) {
            add(ContentProviderOperation
                    .newDelete(ContentUris.withAppendedId(sEventsUri, id)).build());
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

    private void sendEvent(Entity entity, String clientId, Serializer s)
            throws IOException {
        // Serialize for EAS here
        // Set uid with the client id we created
        // 1) Serialize the top-level event
        // 2) Serialize attendees and reminders from subvalues
        // 3) Look for exceptions and serialize with the top-level event
        ContentValues entityValues = entity.getEntityValues();
        boolean isException = (clientId == null);

        if (entityValues.containsKey(Events.ALL_DAY)) {
            s.data(Tags.CALENDAR_ALL_DAY_EVENT,
                    entityValues.getAsInteger(Events.ALL_DAY).toString());
        }

        long startTime = entityValues.getAsLong(Events.DTSTART);
        s.data(Tags.CALENDAR_START_TIME,
                CalendarUtilities.millisToEasDateTime(startTime));

        if (!entityValues.containsKey(Events.DURATION)) {
            if (entityValues.containsKey(Events.DTEND)) {
                s.data(Tags.CALENDAR_END_TIME, CalendarUtilities.millisToEasDateTime(
                        entityValues.getAsLong(Events.DTEND)));
            }
        } else {
            // Convert this into millis and add it to DTSTART for DTEND
            // We'll use 1 hour as a default
            long durationMillis = HOURS;
            Duration duration = new Duration();
            try {
                duration.parse(entityValues.getAsString(Events.DURATION));
            } catch (DateException e) {
                // Can't do much about this; use the default (1 hour)
            }
            s.data(Tags.CALENDAR_END_TIME,
                    CalendarUtilities.millisToEasDateTime(startTime + durationMillis));
        }

        s.data(Tags.CALENDAR_DTSTAMP,
                CalendarUtilities.millisToEasDateTime(System.currentTimeMillis()));

        if (entityValues.containsKey(Events.EVENT_LOCATION)) {
            s.data(Tags.CALENDAR_LOCATION,
                    entityValues.getAsString(Events.EVENT_LOCATION));
        }
        if (entityValues.containsKey(Events.TITLE)) {
            s.data(Tags.CALENDAR_SUBJECT, entityValues.getAsString(Events.TITLE));
        }

        if (entityValues.containsKey(Events.VISIBILITY)) {
            s.data(Tags.CALENDAR_SENSITIVITY,
                    decodeVisibility(entityValues.getAsInteger(Events.VISIBILITY)));
        } else {
            // Private if not set
            s.data(Tags.CALENDAR_SENSITIVITY, "1");
        }

        if (!isException) {
            // A time zone is required in all EAS events; we'll use the default if none
            // is set.
            String timeZoneName;
            if (entityValues.containsKey(Events.EVENT_TIMEZONE)) {
                timeZoneName = entityValues.getAsString(Events.EVENT_TIMEZONE);
            } else {
                timeZoneName = TimeZone.getDefault().getID();
            }
            String x = CalendarUtilities.timeZoneToTZIString(timeZoneName);
            s.data(Tags.CALENDAR_TIME_ZONE, x);

            if (entityValues.containsKey(Events.DESCRIPTION)) {
                String desc = entityValues.getAsString(Events.DESCRIPTION);
                if (mService.mProtocolVersionDouble >= 12.0) {
                    s.start(Tags.BASE_BODY);
                    s.data(Tags.BASE_TYPE, "1");
                    s.data(Tags.BASE_DATA, desc);
                    s.end();
                } else {
                    s.data(Tags.CALENDAR_BODY, desc);
                }
            }

            if (entityValues.containsKey(Events.ORGANIZER)) {
                s.data(Tags.CALENDAR_ORGANIZER_EMAIL,
                        entityValues.getAsString(Events.ORGANIZER));
            }

            if (entityValues.containsKey(Events.RRULE)) {
                CalendarUtilities.recurrenceFromRrule(
                        entityValues.getAsString(Events.RRULE), startTime, s);
            }

            // Handle associated data EXCEPT for attendees, which have to be grouped
            ArrayList<NamedContentValues> subValues = entity.getSubValues();
            for (NamedContentValues ncv: subValues) {
                Uri ncvUri = ncv.uri;
                ContentValues ncvValues = ncv.values;
                if (ncvUri.equals(ExtendedProperties.CONTENT_URI)) {
                    if (ncvValues.containsKey("uid")) {
                        clientId = ncvValues.getAsString("uid");
                        s.data(Tags.CALENDAR_UID, clientId);
                    }
                    if (ncvValues.containsKey("dtstamp")) {
                        s.data(Tags.CALENDAR_DTSTAMP, ncvValues.getAsString("dtstamp"));
                    }
                    if (ncvValues.containsKey("categories")) {
                        // Send all the categories back to the server
                        // We've saved them as a String of delimited tokens
                        String categories = ncvValues.getAsString("categories");
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
                    if (ncvValues.containsKey(Reminders.MINUTES)) {
                        s.data(Tags.CALENDAR_REMINDER_MINS_BEFORE,
                                ncvValues.getAsString(Reminders.MINUTES));
                    }
                }
            }

            // We've got to send a UID.  If the event is new, we've generated one; if not,
            // we should have gotten one from extended properties.
            s.data(Tags.CALENDAR_UID, clientId);

            // Handle attendee data here; keep track of organizer and stream it afterward
            boolean hasAttendees = false;
            String organizerName = null;
            for (NamedContentValues ncv: subValues) {
                Uri ncvUri = ncv.uri;
                ContentValues ncvValues = ncv.values;
                if (ncvUri.equals(Attendees.CONTENT_URI)) {
                    if (ncvValues.containsKey(Attendees.ATTENDEE_RELATIONSHIP)) {
                        int relationship =
                            ncvValues.getAsInteger(Attendees.ATTENDEE_RELATIONSHIP);
                        // Organizer isn't among attendees in EAS
                        if (relationship == Attendees.RELATIONSHIP_ORGANIZER) {
                            if (ncvValues.containsKey(Attendees.ATTENDEE_NAME)) {
                                // Remember this; we can't insert it into the stream in
                                // the middle of attendees
                                organizerName =
                                    ncvValues.getAsString(Attendees.ATTENDEE_NAME);
                            }
                            continue;
                        }
                        if (!hasAttendees) {
                            s.start(Tags.CALENDAR_ATTENDEES);
                            hasAttendees = true;
                        }
                        s.start(Tags.CALENDAR_ATTENDEE);
                        if (ncvValues.containsKey(Attendees.ATTENDEE_NAME)) {
                            s.data(Tags.CALENDAR_ATTENDEE_NAME,
                                    ncvValues.getAsString(Attendees.ATTENDEE_NAME));
                        }
                        if (ncvValues.containsKey(Attendees.ATTENDEE_EMAIL)) {
                            s.data(Tags.CALENDAR_ATTENDEE_EMAIL,
                                    ncvValues.getAsString(Attendees.ATTENDEE_EMAIL));
                        }
                        s.data(Tags.CALENDAR_ATTENDEE_TYPE, "1"); // Required
                        s.end(); // Attendee
                    }
                    // If there's no relationship, we can't create this for EAS
                }
            }
            if (hasAttendees) {
                s.end();  // Attendees
            }
            if (organizerName != null) {
                s.data(Tags.CALENDAR_ORGANIZER_NAME, organizerName);
            }
        } else {
            // TODO Add reminders to exceptions (allow them to be specified!)
            if (entityValues.containsKey(Events.ORIGINAL_INSTANCE_TIME)) {
                s.data(Tags.CALENDAR_EXCEPTION_START_TIME,
                        CalendarUtilities.millisToEasDateTime(entityValues.getAsLong(
                                Events.ORIGINAL_INSTANCE_TIME)));
            } else {
                // Illegal; what should we do?
            }
        }
    }

    @Override
    public boolean sendLocalChanges(Serializer s) throws IOException {
        ContentResolver cr = mService.mContentResolver;
        Uri uri = Events.CONTENT_URI.buildUpon()
                .appendQueryParameter(Calendar.CALLER_IS_SYNCADAPTER, "true")
                .build();

        if (getSyncKey().equals("0")) {
            return false;
        }

        try {
            // We've got to handle exceptions as part of the parent when changes occur, so we need
            // to find new/changed exceptions and mark the parent dirty
            Cursor c = cr.query(Events.CONTENT_URI, ORIGINAL_EVENT_PROJECTION, DIRTY_EXCEPTION,
                    null, null);
            try {
                ContentValues cv = new ContentValues();
                cv.put(Events._SYNC_DIRTY, 1);
                // Mark the parent dirty in this loop
                while (c.moveToNext()) {
                    String serverId = c.getString(0);
                    cr.update(asSyncAdapter(Events.CONTENT_URI), cv, SERVER_ID,
                             new String[] {serverId});
                }
            } finally {
                c.close();
            }

            // Now we can go through dirty top-level events and send them back to the server
            EntityIterator eventIterator = EventsEntity.newEntityIterator(
                    cr.query(uri, null, DIRTY_TOP_LEVEL, null, null), cr);
            ContentValues cidValues = new ContentValues();
            try {
                boolean first = true;
                while (eventIterator.hasNext()) {
                    Entity entity = eventIterator.next();
                    String clientId = "uid_" + mMailbox.mId + '_' + System.currentTimeMillis();

                    // For each of these entities, create the change commands
                    ContentValues entityValues = entity.getEntityValues();
                    String serverId = entityValues.getAsString(Events._SYNC_ID);

                    // EAS 2.5 needs: BusyStatus DtStamp EndTime Sensitivity StartTime TimeZone UID
                    // We can generate all but what we're testing for below
                    if (!entityValues.containsKey(Events.DTSTART)
                            || !entityValues.containsKey(Events.DURATION)) {
                        continue;
                    }
                    // TODO Handle BusyStatus for EAS 2.5
                    // What should it be??

                    if (first) {
                        s.start(Tags.SYNC_COMMANDS);
                        userLog("Sending Calendar changes to the server");
                        first = false;
                    }
                    if (serverId == null) {
                        // This is a new event; create a clientId
                        userLog("Creating new event with clientId: ", clientId);
                        s.start(Tags.SYNC_ADD).data(Tags.SYNC_CLIENT_ID, clientId);
                        // And save it in the Event as the local id
                        cidValues.put(Events._SYNC_LOCAL_ID, clientId);
                        cr.update(ContentUris.
                                withAppendedId(uri,
                                        entityValues.getAsLong(Events._ID)),
                                        cidValues, null, null);
                    } else {
                        if (entityValues.getAsInteger(Events.DELETED) == 1) {
                            userLog("Deleting event with serverId: ", serverId);
                            s.start(Tags.SYNC_DELETE).data(Tags.SYNC_SERVER_ID, serverId).end();
                            mDeletedIdList.add(entityValues.getAsLong(Events._ID));
                            continue;
                        }
                        userLog("Upsync change to event with serverId: " + serverId);
                        s.start(Tags.SYNC_CHANGE).data(Tags.SYNC_SERVER_ID, serverId);
                    }
                    s.start(Tags.SYNC_APPLICATION_DATA);
                    sendEvent(entity, clientId, s);

                    // Now, the hard part; find exceptions for this event
                    if (serverId != null) {
                        EntityIterator exceptionIterator = EventsEntity.newEntityIterator(
                                cr.query(uri, null, Events.ORIGINAL_EVENT + "=?",
                                        new String[] {serverId}, null), cr);
                        boolean exFirst = true;
                        while (exceptionIterator.hasNext()) {
                            Entity exceptionEntity = exceptionIterator.next();
                            if (exFirst) {
                                s.start(Tags.CALENDAR_EXCEPTIONS);
                                exFirst = false;
                            }
                            s.start(Tags.CALENDAR_EXCEPTION);
                            sendEvent(exceptionEntity, null, s);
                            s.end(); // EXCEPTION
                        }
                        if (!exFirst) {
                            s.end(); // EXCEPTIONS
                        }
                    }

                    s.end().end(); // ApplicationData & Change
                    mUpdatedIdList.add(entityValues.getAsLong(Events._ID));
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
