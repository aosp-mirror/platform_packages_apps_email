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

import com.android.email.Email;
import com.android.email.mail.Address;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.exchange.Eas;
import com.android.exchange.EasSyncService;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Entity;
import android.content.EntityIterator;
import android.content.Entity.NamedContentValues;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.Calendar.Attendees;
import android.provider.Calendar.Calendars;
import android.provider.Calendar.Events;
import android.provider.Calendar.EventsEntity;
import android.provider.Calendar.Reminders;
import android.text.format.Time;
import android.util.Log;
import android.util.base64.Base64;

import java.io.IOException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.TimeZone;

public class CalendarUtilities {
    // NOTE: Most definitions in this class are have package visibility for testing purposes
    private static final String TAG = "CalendarUtility";

    // Time related convenience constants, in milliseconds
    static final int SECONDS = 1000;
    static final int MINUTES = SECONDS*60;
    static final int HOURS = MINUTES*60;
    static final long DAYS = HOURS*24;

    // NOTE All Microsoft data structures are little endian

    // The following constants relate to standard Microsoft data sizes
    // For documentation, see http://msdn.microsoft.com/en-us/library/aa505945.aspx
    static final int MSFT_LONG_SIZE = 4;
    static final int MSFT_WCHAR_SIZE = 2;
    static final int MSFT_WORD_SIZE = 2;

    // The following constants relate to Microsoft's SYSTEMTIME structure
    // For documentation, see: http://msdn.microsoft.com/en-us/library/ms724950(VS.85).aspx?ppud=4

    static final int MSFT_SYSTEMTIME_YEAR = 0 * MSFT_WORD_SIZE;
    static final int MSFT_SYSTEMTIME_MONTH = 1 * MSFT_WORD_SIZE;
    static final int MSFT_SYSTEMTIME_DAY_OF_WEEK = 2 * MSFT_WORD_SIZE;
    static final int MSFT_SYSTEMTIME_DAY = 3 * MSFT_WORD_SIZE;
    static final int MSFT_SYSTEMTIME_HOUR = 4 * MSFT_WORD_SIZE;
    static final int MSFT_SYSTEMTIME_MINUTE = 5 * MSFT_WORD_SIZE;
    //static final int MSFT_SYSTEMTIME_SECONDS = 6 * MSFT_WORD_SIZE;
    //static final int MSFT_SYSTEMTIME_MILLIS = 7 * MSFT_WORD_SIZE;
    static final int MSFT_SYSTEMTIME_SIZE = 8*MSFT_WORD_SIZE;

    // The following constants relate to Microsoft's TIME_ZONE_INFORMATION structure
    // For documentation, see http://msdn.microsoft.com/en-us/library/ms725481(VS.85).aspx
    static final int MSFT_TIME_ZONE_BIAS_OFFSET = 0;
    static final int MSFT_TIME_ZONE_STANDARD_NAME_OFFSET =
        MSFT_TIME_ZONE_BIAS_OFFSET + MSFT_LONG_SIZE;
    static final int MSFT_TIME_ZONE_STANDARD_DATE_OFFSET =
        MSFT_TIME_ZONE_STANDARD_NAME_OFFSET + (MSFT_WCHAR_SIZE*32);
    static final int MSFT_TIME_ZONE_STANDARD_BIAS_OFFSET =
        MSFT_TIME_ZONE_STANDARD_DATE_OFFSET + MSFT_SYSTEMTIME_SIZE;
    static final int MSFT_TIME_ZONE_DAYLIGHT_NAME_OFFSET =
        MSFT_TIME_ZONE_STANDARD_BIAS_OFFSET + MSFT_LONG_SIZE;
    static final int MSFT_TIME_ZONE_DAYLIGHT_DATE_OFFSET =
        MSFT_TIME_ZONE_DAYLIGHT_NAME_OFFSET + (MSFT_WCHAR_SIZE*32);
    static final int MSFT_TIME_ZONE_DAYLIGHT_BIAS_OFFSET =
        MSFT_TIME_ZONE_DAYLIGHT_DATE_OFFSET + MSFT_SYSTEMTIME_SIZE;
    static final int MSFT_TIME_ZONE_SIZE =
        MSFT_TIME_ZONE_DAYLIGHT_BIAS_OFFSET + MSFT_LONG_SIZE;

    // TimeZone cache; we parse/decode as little as possible, because the process is quite slow
    private static HashMap<String, TimeZone> sTimeZoneCache = new HashMap<String, TimeZone>();
    // TZI string cache; we keep around our encoded TimeZoneInformation strings
    private static HashMap<TimeZone, String> sTziStringCache = new HashMap<TimeZone, String>();

    // There is no type 4 (thus, the "")
    static final String[] sTypeToFreq =
        new String[] {"DAILY", "WEEKLY", "MONTHLY", "MONTHLY", "", "YEARLY", "YEARLY"};

    static final String[] sDayTokens =
        new String[] {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};

    static final String[] sTwoCharacterNumbers =
        new String[] {"00", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"};

    static final int sCurrentYear = new GregorianCalendar().get(Calendar.YEAR);
    static final TimeZone sGmtTimeZone = TimeZone.getTimeZone("GMT");

    private static final String ICALENDAR_ATTENDEE_INVITE =
        "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=NEEDS-ACTION;RSVP=TRUE";
    private static final String ICALENDAR_ATTENDEE_ACCEPT =
        "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED";
    private static final String ICALENDAR_ATTENDEE_DECLINE =
        "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=DECLINED";
    private static final String ICALENDAR_ATTENDEE_TENTATIVE =
        "ATTENDEE;ROLE=REQ-PARTICIPANT;PARTSTAT=TENTATIVE";

    // Return a 4-byte long from a byte array (little endian)
    static int getLong(byte[] bytes, int offset) {
        return (bytes[offset++] & 0xFF) | ((bytes[offset++] & 0xFF) << 8) |
        ((bytes[offset++] & 0xFF) << 16) | ((bytes[offset] & 0xFF) << 24);
    }

    // Put a 4-byte long into a byte array (little endian)
    static void setLong(byte[] bytes, int offset, int value) {
        bytes[offset++] = (byte) (value & 0xFF);
        bytes[offset++] = (byte) ((value >> 8) & 0xFF);
        bytes[offset++] = (byte) ((value >> 16) & 0xFF);
        bytes[offset] = (byte) ((value >> 24) & 0xFF);
    }

    // Return a 2-byte word from a byte array (little endian)
    static int getWord(byte[] bytes, int offset) {
        return (bytes[offset++] & 0xFF) | ((bytes[offset] & 0xFF) << 8);
    }

    // Put a 2-byte word into a byte array (little endian)
    static void setWord(byte[] bytes, int offset, int value) {
        bytes[offset++] = (byte) (value & 0xFF);
        bytes[offset] = (byte) ((value >> 8) & 0xFF);
    }

    // Internal structure for storing a time zone date from a SYSTEMTIME structure
    // This date represents either the start or the end time for DST
    static class TimeZoneDate {
        String year;
        int month;
        int dayOfWeek;
        int day;
        int time;
        int hour;
        int minute;
    }

    // Write SYSTEMTIME data into a byte array (this will either be for the standard or daylight
    // transition)
    static void putTimeInMillisIntoSystemTime(byte[] bytes, int offset, long millis) {
        GregorianCalendar cal = new GregorianCalendar(TimeZone.getDefault());
        // Round to the next highest minute; we always write seconds as zero
        cal.setTimeInMillis(millis + 30*SECONDS);

        // MSFT months are 1 based; TimeZone is 0 based
        setWord(bytes, offset + MSFT_SYSTEMTIME_MONTH, cal.get(Calendar.MONTH) + 1);
        // MSFT day of week starts w/ Sunday = 0; TimeZone starts w/ Sunday = 1
        setWord(bytes, offset + MSFT_SYSTEMTIME_DAY_OF_WEEK, cal.get(Calendar.DAY_OF_WEEK) - 1);

        // Get the "day" in TimeZone format
        int wom = cal.get(Calendar.DAY_OF_WEEK_IN_MONTH);
        // 5 means "last" in MSFT land; for TimeZone, it's -1
        setWord(bytes, offset + MSFT_SYSTEMTIME_DAY, wom < 0 ? 5 : wom);

        // Turn hours/minutes into ms from midnight (per TimeZone)
        setWord(bytes, offset + MSFT_SYSTEMTIME_HOUR, cal.get(Calendar.HOUR));
        setWord(bytes, offset + MSFT_SYSTEMTIME_MINUTE, cal.get(Calendar.MINUTE));
     }

    // Build a TimeZoneDate structure from a SYSTEMTIME within a byte array at a given offset
    static TimeZoneDate getTimeZoneDateFromSystemTime(byte[] bytes, int offset) {
        TimeZoneDate tzd = new TimeZoneDate();

        // MSFT year is an int; TimeZone is a String
        int num = getWord(bytes, offset + MSFT_SYSTEMTIME_YEAR);
        tzd.year = Integer.toString(num);

        // MSFT month = 0 means no daylight time
        // MSFT months are 1 based; TimeZone is 0 based
        num = getWord(bytes, offset + MSFT_SYSTEMTIME_MONTH);
        if (num == 0) {
            return null;
        } else {
            tzd.month = num -1;
        }

        // MSFT day of week starts w/ Sunday = 0; TimeZone starts w/ Sunday = 1
        tzd.dayOfWeek = getWord(bytes, offset + MSFT_SYSTEMTIME_DAY_OF_WEEK) + 1;

        // Get the "day" in TimeZone format
        num = getWord(bytes, offset + MSFT_SYSTEMTIME_DAY);
        // 5 means "last" in MSFT land; for TimeZone, it's -1
        if (num == 5) {
            tzd.day = -1;
        } else {
            tzd.day = num;
        }

        // Turn hours/minutes into ms from midnight (per TimeZone)
        int hour = getWord(bytes, offset + MSFT_SYSTEMTIME_HOUR);
        tzd.hour = hour;
        int minute = getWord(bytes, offset + MSFT_SYSTEMTIME_MINUTE);
        tzd.minute = minute;
        tzd.time = (hour*HOURS) + (minute*MINUTES);

        return tzd;
    }

    /**
     * Build a GregorianCalendar, based on a time zone and TimeZoneDate.
     * @param timeZone the time zone we're checking
     * @param tzd the TimeZoneDate we're interested in
     * @return a GregorianCalendar with the given time zone and date
     */
    static GregorianCalendar getCheckCalendar(TimeZone timeZone, TimeZoneDate tzd) {
        GregorianCalendar testCalendar = new GregorianCalendar(timeZone);
        testCalendar.set(GregorianCalendar.YEAR, sCurrentYear);
        testCalendar.set(GregorianCalendar.MONTH, tzd.month);
        testCalendar.set(GregorianCalendar.DAY_OF_WEEK, tzd.dayOfWeek);
        testCalendar.set(GregorianCalendar.DAY_OF_WEEK_IN_MONTH, tzd.day);
        testCalendar.set(GregorianCalendar.HOUR_OF_DAY, tzd.hour);
        testCalendar.set(GregorianCalendar.MINUTE, tzd.minute);
        return testCalendar;
    }

    /**
     * Find a standard/daylight transition between a start time and an end time
     * @param tz a TimeZone
     * @param startTime the start time for the test
     * @param endTime the end time for the test
     * @param startInDaylightTime whether daylight time is in effect at the startTime
     * @return the time in millis of the first transition, or 0 if none
     */
    static private long findTransition(TimeZone tz, long startTime, long endTime,
            boolean startInDaylightTime) {
        long startingEndTime = endTime;
        Date date = null;
        while ((endTime - startTime) > MINUTES) {
            long checkTime = ((startTime + endTime) / 2) + 1;
            date = new Date(checkTime);
            if (tz.inDaylightTime(date) != startInDaylightTime) {
                endTime = checkTime;
            } else {
                startTime = checkTime;
            }
        }
        if (endTime == startingEndTime) {
            // Really, this shouldn't happen
            return 0;
        }
        return startTime;
    }

    /**
     * Return a Base64 representation of a MSFT TIME_ZONE_INFORMATION structure from a TimeZone
     * that might be found in an Event; use cached result, if possible
     * @param tz the TimeZone
     * @return the Base64 String representing a Microsoft TIME_ZONE_INFORMATION element
     */
    static public String timeZoneToTziString(TimeZone tz) {
        String tziString = sTziStringCache.get(tz);
        if (tziString != null) {
            if (Eas.USER_LOG) {
                Log.d(TAG, "TZI string for " + tz.getDisplayName() + " found in cache.");
            }
            return tziString;
        }
        tziString = timeZoneToTziStringImpl(tz);
        sTziStringCache.put(tz, tziString);
        return tziString;
    }

    /**
     * Calculate the Base64 representation of a MSFT TIME_ZONE_INFORMATION structure from a TimeZone
     * that might be found in an Event.  Since the internal representation of the TimeZone is hidden
     * from us we'll find the DST transitions and build the structure from that information
     * @param tz the TimeZone
     * @return the Base64 String representing a Microsoft TIME_ZONE_INFORMATION element
     */
    static public String timeZoneToTziStringImpl(TimeZone tz) {
        String tziString;
        long time = System.currentTimeMillis();
        byte[] tziBytes = new byte[MSFT_TIME_ZONE_SIZE];
        int standardBias = - tz.getRawOffset();
        standardBias /= 60*SECONDS;
        setLong(tziBytes, MSFT_TIME_ZONE_BIAS_OFFSET, standardBias);
        // If this time zone has daylight savings time, we need to do a bunch more work
        if (tz.useDaylightTime()) {
            long standardTransition = 0;
            long daylightTransition = 0;
            GregorianCalendar cal = new GregorianCalendar();
            cal.set(sCurrentYear, Calendar.JANUARY, 1, 0, 0, 0);
            cal.setTimeZone(tz);
            long startTime = cal.getTimeInMillis();
            // Calculate rough end of year; no need to do the calculation
            long endOfYearTime = startTime + 365*DAYS;
            Date date = new Date(startTime);
            boolean startInDaylightTime = tz.inDaylightTime(date);
            // Find the first transition, and store
            startTime = findTransition(tz, startTime, endOfYearTime, startInDaylightTime);
            if (startInDaylightTime) {
                standardTransition = startTime;
            } else {
                daylightTransition = startTime;
            }
            // Find the second transition, and store
            startTime = findTransition(tz, startTime, endOfYearTime, !startInDaylightTime);
            if (startInDaylightTime) {
                daylightTransition = startTime;
            } else {
                standardTransition = startTime;
            }
            if (standardTransition != 0 && daylightTransition != 0) {
                putTimeInMillisIntoSystemTime(tziBytes, MSFT_TIME_ZONE_STANDARD_DATE_OFFSET,
                        standardTransition);
                putTimeInMillisIntoSystemTime(tziBytes, MSFT_TIME_ZONE_DAYLIGHT_DATE_OFFSET,
                        daylightTransition);
                int dstOffset = tz.getDSTSavings();
                setLong(tziBytes, MSFT_TIME_ZONE_DAYLIGHT_BIAS_OFFSET, - dstOffset / MINUTES);
            }
        }
        byte[] tziEncodedBytes = Base64.encode(tziBytes, Base64.NO_WRAP);
        tziString = new String(tziEncodedBytes);
        if (Eas.USER_LOG) {
            Log.d(TAG, "Calculated TZI String for " + tz.getDisplayName() + " in " +
                    (System.currentTimeMillis() - time) + "ms");
        }
        return tziString;
    }

    /**
     * Given a String as directly read from EAS, returns a TimeZone corresponding to that String
     * @param timeZoneString the String read from the server
     * @return the TimeZone, or TimeZone.getDefault() if not found
     */
    static public TimeZone tziStringToTimeZone(String timeZoneString) {
        // If we have this time zone cached, use that value and return
        TimeZone timeZone = sTimeZoneCache.get(timeZoneString);
        if (timeZone != null) {
            if (Eas.USER_LOG) {
                Log.d(TAG, " Using cached TimeZone " + timeZone.getDisplayName());
            }
        } else {
            timeZone = tziStringToTimeZoneImpl(timeZoneString);
            if (timeZone == null) {
                // If we don't find a match, we just return the current TimeZone.  In theory, this
                // shouldn't be happening...
                Log.w(TAG, "TimeZone not found using default: " + timeZoneString);
                timeZone = TimeZone.getDefault();
            }
            sTimeZoneCache.put(timeZoneString, timeZone);
        }
        return timeZone;
    }

    /**
     * Given a String as directly read from EAS, tries to find a TimeZone in the database of all
     * time zones that corresponds to that String.
     * @param timeZoneString the String read from the server
     * @return the TimeZone, or TimeZone.getDefault() if not found
     */
    static public TimeZone tziStringToTimeZoneImpl(String timeZoneString) {
        TimeZone timeZone = null;
        // TODO Remove after we're comfortable with performance
        long time = System.currentTimeMillis();
        // First, we need to decode the base64 string
        byte[] timeZoneBytes = Base64.decode(timeZoneString, Base64.DEFAULT);

        // Then, we get the bias (similar to a rawOffset); for TimeZone, we need ms
        // but EAS gives us minutes, so do the conversion.  Note that EAS is the bias that's added
        // to the time zone to reach UTC; our library uses the time from UTC to our time zone, so
        // we need to change the sign
        int bias = -1 * getLong(timeZoneBytes, MSFT_TIME_ZONE_BIAS_OFFSET) * MINUTES;

        // Get all of the time zones with the bias as a rawOffset; if there aren't any, we return
        // the default time zone
        String[] zoneIds = TimeZone.getAvailableIDs(bias);
        if (zoneIds.length > 0) {
            // Try to find an existing TimeZone from the data provided by EAS
            // We start by pulling out the date that standard time begins
            TimeZoneDate dstEnd =
                getTimeZoneDateFromSystemTime(timeZoneBytes, MSFT_TIME_ZONE_STANDARD_DATE_OFFSET);
            if (dstEnd == null) {
                // In this case, there is no daylight savings time, so the only interesting data
                // is the offset, and we know that all of the zoneId's match; we'll take the first
                timeZone = TimeZone.getTimeZone(zoneIds[0]);
                String dn = timeZone.getDisplayName();
                sTimeZoneCache.put(timeZoneString, timeZone);
                if (Eas.USER_LOG) {
                    Log.d(TAG, "TimeZone without DST found by offset: " + dn);
                }
            } else {
                TimeZoneDate dstStart = getTimeZoneDateFromSystemTime(timeZoneBytes,
                        MSFT_TIME_ZONE_DAYLIGHT_DATE_OFFSET);
                // See comment above for bias...
                long dstSavings =
                    -1 * getLong(timeZoneBytes, MSFT_TIME_ZONE_DAYLIGHT_BIAS_OFFSET) * MINUTES;

                // We'll go through each time zone to find one with the same DST transitions and
                // savings length
                for (String zoneId: zoneIds) {
                    // Get the TimeZone using the zoneId
                    timeZone = TimeZone.getTimeZone(zoneId);

                    // Our strategy here is to check just before and just after the transitions
                    // and see whether the check for daylight time matches the expectation
                    // If both transitions match, then we have a match for the offset and start/end
                    // of dst.  That's the best we can do for now, since there's no other info
                    // provided by EAS (i.e. we can't get dynamic transitions, etc.)

                    int testSavingsMinutes = timeZone.getDSTSavings() / MINUTES;
                    int errorBoundsMinutes = (testSavingsMinutes * 2) + 1;

                    // Check start DST transition
                    GregorianCalendar testCalendar = getCheckCalendar(timeZone, dstStart);
                    testCalendar.add(GregorianCalendar.MINUTE, - errorBoundsMinutes);
                    Date before = testCalendar.getTime();
                    testCalendar.add(GregorianCalendar.MINUTE, 2*errorBoundsMinutes);
                    Date after = testCalendar.getTime();
                    if (timeZone.inDaylightTime(before)) continue;
                    if (!timeZone.inDaylightTime(after)) continue;

                    // Check end DST transition
                    testCalendar = getCheckCalendar(timeZone, dstEnd);
                    testCalendar.add(GregorianCalendar.MINUTE, - errorBoundsMinutes);
                    before = testCalendar.getTime();
                    testCalendar.add(GregorianCalendar.MINUTE, 2*errorBoundsMinutes);
                    after = testCalendar.getTime();
                    if (!timeZone.inDaylightTime(before)) continue;
                    if (timeZone.inDaylightTime(after)) continue;

                    // Check that the savings are the same
                    if (dstSavings != timeZone.getDSTSavings()) continue;

                    // If we're here, it's the right time zone, modulo dynamic DST
                    String dn = timeZone.getDisplayName();
                    // TODO Remove timing when we're comfortable with performance
                    if (Eas.USER_LOG) {
                        Log.d(TAG, "TimeZone found by rules: " + dn + " in " +
                                (System.currentTimeMillis() - time) + "ms");
                    }
                    break;
                }
            }
        }
        return timeZone;
    }

    /**
     * Generate a time in milliseconds from an email date string that represents a date/time in GMT
     * @param Email style DateTime string from Exchange server
     * @return the time in milliseconds (since Jan 1, 1970)
     */
    static public long parseEmailDateTimeToMillis(String date) {
        // Format for email date strings is 2010-02-23T16:00:00.000Z
        GregorianCalendar cal = new GregorianCalendar(Integer.parseInt(date.substring(0, 4)),
                Integer.parseInt(date.substring(5, 7)) - 1, Integer.parseInt(date.substring(8, 10)),
                Integer.parseInt(date.substring(11, 13)), Integer.parseInt(date.substring(14, 16)),
                Integer.parseInt(date.substring(17, 19)));
        cal.setTimeZone(TimeZone.getTimeZone("GMT"));
        return cal.getTimeInMillis();
    }

    /**
     * Generate a time in milliseconds from a date string that represents a date/time in GMT
     * @param DateTime string from Exchange server
     * @return the time in milliseconds (since Jan 1, 1970)
     */
    static public long parseDateTimeToMillis(String date) {
        // Format for calendar date strings is 20090211T180303Z
        GregorianCalendar cal = new GregorianCalendar(Integer.parseInt(date.substring(0, 4)),
                Integer.parseInt(date.substring(4, 6)) - 1, Integer.parseInt(date.substring(6, 8)),
                Integer.parseInt(date.substring(9, 11)), Integer.parseInt(date.substring(11, 13)),
                Integer.parseInt(date.substring(13, 15)));
        cal.setTimeZone(TimeZone.getTimeZone("GMT"));
        return cal.getTimeInMillis();
    }

    /**
     * Generate a GregorianCalendar from a date string that represents a date/time in GMT
     * @param DateTime string from Exchange server
     * @return the GregorianCalendar
     */
    static public GregorianCalendar parseDateTimeToCalendar(String date) {
        // Format for calendar date strings is 20090211T180303Z
        GregorianCalendar cal = new GregorianCalendar(Integer.parseInt(date.substring(0, 4)),
                Integer.parseInt(date.substring(4, 6)) - 1, Integer.parseInt(date.substring(6, 8)),
                Integer.parseInt(date.substring(9, 11)), Integer.parseInt(date.substring(11, 13)),
                Integer.parseInt(date.substring(13, 15)));
        cal.setTimeZone(TimeZone.getTimeZone("GMT"));
        return cal;
    }

    static String formatTwo(int num) {
        if (num <= 12) {
            return sTwoCharacterNumbers[num];
        } else
            return Integer.toString(num);
    }

    /**
     * Generate an EAS formatted date/time string based on GMT. See below for details.
     */
    static public String millisToEasDateTime(long millis) {
        return millisToEasDateTime(millis, sGmtTimeZone);
    }

    /**
     * Generate an EAS formatted local date/time string from a time and a time zone
     * @param millis a time in milliseconds
     * @param tz a time zone
     * @return an EAS formatted string indicating the date/time in the given time zone
     */
    static public String millisToEasDateTime(long millis, TimeZone tz) {
        StringBuilder sb = new StringBuilder();
        GregorianCalendar cal = new GregorianCalendar(tz);
        cal.setTimeInMillis(millis);
        sb.append(cal.get(Calendar.YEAR));
        sb.append(formatTwo(cal.get(Calendar.MONTH) + 1));
        sb.append(formatTwo(cal.get(Calendar.DAY_OF_MONTH)));
        sb.append('T');
        sb.append(formatTwo(cal.get(Calendar.HOUR_OF_DAY)));
        sb.append(formatTwo(cal.get(Calendar.MINUTE)));
        sb.append(formatTwo(cal.get(Calendar.SECOND)));
        sb.append('Z');
        return sb.toString();
    }

    static void addByDay(StringBuilder rrule, int dow, int wom) {
        rrule.append(";BYDAY=");
        boolean addComma = false;
        for (int i = 0; i < 7; i++) {
            if ((dow & 1) == 1) {
                if (addComma) {
                    rrule.append(',');
                }
                if (wom > 0) {
                    // 5 = last week -> -1
                    // So -1SU = last sunday
                    rrule.append(wom == 5 ? -1 : wom);
                }
                rrule.append(sDayTokens[i]);
                addComma = true;
            }
            dow >>= 1;
        }
    }

    static void addByMonthDay(StringBuilder rrule, int dom) {
        // 127 means last day of the month
        if (dom == 127) {
            dom = -1;
        }
        rrule.append(";BYMONTHDAY=" + dom);
    }

    /**
     * Generate the String version of the EAS integer for a given BYDAY value in an rrule
     * @param dow the BYDAY value of the rrule
     * @return the String version of the EAS value of these days
     */
    static String generateEasDayOfWeek(String dow) {
        int bits = 0;
        int bit = 1;
        for (String token: sDayTokens) {
            // If we can find the day in the dow String, add the bit to our bits value
            if (dow.indexOf(token) >= 0) {
                bits |= bit;
            }
            bit <<= 1;
        }
        return Integer.toString(bits);
    }

    /**
     * Extract the value of a token in an RRULE string
     * @param rrule an RRULE string
     * @param token a token to look for in the RRULE
     * @return the value of that token
     */
    static String tokenFromRrule(String rrule, String token) {
        int start = rrule.indexOf(token);
        if (start < 0) return null;
        int len = rrule.length();
        start += token.length();
        int end = start;
        char c;
        do {
            c = rrule.charAt(end++);
            if (!Character.isLetterOrDigit(c) || (end == len)) {
                if (end == len) end++;
                return rrule.substring(start, end -1);
            }
        } while (true);
    }

    /**
     * Reformat an RRULE style UNTIL to an EAS style until
     */
    static String recurrenceUntilToEasUntil(String until) {
        StringBuilder sb = new StringBuilder();
        sb.append(until.substring(0, 4));
        sb.append('-');
        sb.append(until.substring(4, 6));
        sb.append('-');
        if (until.length() == 8) {
            sb.append(until.substring(6, 8));
            sb.append("T00:00:00");

        } else {
            sb.append(until.substring(6, 11));
            sb.append(':');
            sb.append(until.substring(11, 13));
            sb.append(':');
            sb.append(until.substring(13, 15));
        }
        sb.append(".000Z");
        return sb.toString();
    }

    /**
     * Convenience method to add "until" to an EAS calendar stream
     */
    static void addUntil(String rrule, Serializer s) throws IOException {
        String until = tokenFromRrule(rrule, "UNTIL=");
        if (until != null) {
            s.data(Tags.CALENDAR_RECURRENCE_UNTIL, recurrenceUntilToEasUntil(until));
        }
    }

    /**
     * Write recurrence information to EAS based on the RRULE in CalendarProvider
     * @param rrule the RRULE, from CalendarProvider
     * @param startTime, the DTSTART of this Event
     * @param s the Serializer we're using to write WBXML data
     * @throws IOException
     */
    // NOTE: For the moment, we're only parsing recurrence types that are supported by the
    // Calendar app UI, which is a subset of possible recurrence types
    // This code must be updated when the Calendar adds new functionality
    static public void recurrenceFromRrule(String rrule, long startTime, Serializer s)
    throws IOException {
        Log.d("RRULE", "rule: " + rrule);
        String freq = tokenFromRrule(rrule, "FREQ=");
        // If there's no FREQ=X, then we don't write a recurrence
        // Note that we duplicate s.start(Tags.CALENDAR_RECURRENCE); s.end(); to prevent the
        // possibility of writing out a partial recurrence stanza
        if (freq != null) {
            if (freq.equals("DAILY")) {
                s.start(Tags.CALENDAR_RECURRENCE);
                s.data(Tags.CALENDAR_RECURRENCE_TYPE, "0");
                s.data(Tags.CALENDAR_RECURRENCE_INTERVAL, "1");
                s.end();
            } else if (freq.equals("WEEKLY")) {
                s.start(Tags.CALENDAR_RECURRENCE);
                s.data(Tags.CALENDAR_RECURRENCE_TYPE, "1");
                s.data(Tags.CALENDAR_RECURRENCE_INTERVAL, "1");
                // Requires a day of week (whereas RRULE does not)
                String byDay = tokenFromRrule(rrule, "BYDAY=");
                if (byDay != null) {
                    s.data(Tags.CALENDAR_RECURRENCE_DAYOFWEEK, generateEasDayOfWeek(byDay));
                }
                addUntil(rrule, s);
                s.end();
            } else if (freq.equals("MONTHLY")) {
                String byMonthDay = tokenFromRrule(rrule, "BYMONTHDAY=");
                if (byMonthDay != null) {
                    // The nth day of the month
                    s.start(Tags.CALENDAR_RECURRENCE);
                    s.data(Tags.CALENDAR_RECURRENCE_TYPE, "2");
                    s.data(Tags.CALENDAR_RECURRENCE_DAYOFMONTH, byMonthDay);
                    addUntil(rrule, s);
                    s.end();
                } else {
                    String byDay = tokenFromRrule(rrule, "BYDAY=");
                    String bareByDay;
                    if (byDay != null) {
                        // This can be 1WE (1st Wednesday) or -1FR (last Friday)
                        int wom = byDay.charAt(0);
                        if (wom == '-') {
                            // -1 is the only legal case (last week) Use "5" for EAS
                            wom = 5;
                            bareByDay = byDay.substring(2);
                        } else {
                            wom = wom - '0';
                            bareByDay = byDay.substring(1);
                        }
                        s.start(Tags.CALENDAR_RECURRENCE);
                        s.data(Tags.CALENDAR_RECURRENCE_TYPE, "3");
                        s.data(Tags.CALENDAR_RECURRENCE_WEEKOFMONTH, Integer.toString(wom));
                        s.data(Tags.CALENDAR_RECURRENCE_DAYOFWEEK, generateEasDayOfWeek(bareByDay));
                        addUntil(rrule, s);
                        s.end();
                    }
                }
            } else if (freq.equals("YEARLY")) {
                String byMonth = tokenFromRrule(rrule, "BYMONTH=");
                String byMonthDay = tokenFromRrule(rrule, "BYMONTHDAY=");
                if (byMonth == null || byMonthDay == null) {
                    // Calculate the month and day from the startDate
                    GregorianCalendar cal = new GregorianCalendar();
                    cal.setTimeInMillis(startTime);
                    cal.setTimeZone(TimeZone.getDefault());
                    byMonth = Integer.toString(cal.get(Calendar.MONTH) + 1);
                    byMonthDay = Integer.toString(cal.get(Calendar.DAY_OF_MONTH));
                }
                s.start(Tags.CALENDAR_RECURRENCE);
                s.data(Tags.CALENDAR_RECURRENCE_TYPE, "5");
                s.data(Tags.CALENDAR_RECURRENCE_DAYOFMONTH, byMonthDay);
                s.data(Tags.CALENDAR_RECURRENCE_MONTHOFYEAR, byMonth);
                addUntil(rrule, s);
                s.end();
            }
        }
    }

    /**
     * Build an RRULE String from EAS recurrence information
     * @param type the type of recurrence
     * @param occurrences how many recurrences (instances)
     * @param interval the interval between recurrences
     * @param dow day of the week
     * @param dom day of the month
     * @param wom week of the month
     * @param moy month of the year
     * @param until the last recurrence time
     * @return a valid RRULE String
     */
    static public String rruleFromRecurrence(int type, int occurrences, int interval, int dow,
            int dom, int wom, int moy, String until) {
        StringBuilder rrule = new StringBuilder("FREQ=" + sTypeToFreq[type]);

        // INTERVAL and COUNT
        if (interval > 0) {
            rrule.append(";INTERVAL=" + interval);
        }
        if (occurrences > 0) {
            rrule.append(";COUNT=" + occurrences);
        }

        // Days, weeks, months, etc.
        switch(type) {
            case 0: // DAILY
            case 1: // WEEKLY
                if (dow > 0) addByDay(rrule, dow, -1);
                break;
            case 2: // MONTHLY
                if (dom > 0) addByMonthDay(rrule, dom);
                break;
            case 3: // MONTHLY (on the nth day)
                if (dow > 0) addByDay(rrule, dow, wom);
                break;
            case 5: // YEARLY
                if (dom > 0) addByMonthDay(rrule, dom);
                if (moy > 0) {
                    // TODO MAKE SURE WE'RE 1 BASED
                    rrule.append(";BYMONTH=" + moy);
                }
                break;
            case 6: // YEARLY (on the nth day)
                if (dow > 0) addByDay(rrule, dow, wom);
                if (moy > 0) addByMonthDay(rrule, dow);
                break;
            default:
                break;
        }

        // UNTIL comes last
        if (until != null) {
            rrule.append(";UNTIL=" + until);
        }

        return rrule.toString();
    }

    /**
     * Create a Calendar in CalendarProvider to which synced Events will be linked
     * @param service the sync service requesting Calendar creation
     * @param account the account being synced
     * @param mailbox the Exchange mailbox for the calendar
     * @return the unique id of the Calendar
     */
    static public long createCalendar(EasSyncService service, Account account, Mailbox mailbox) {
        // Create a Calendar object
        ContentValues cv = new ContentValues();
        // TODO How will this change if the user changes his account display name?
        cv.put(Calendars.DISPLAY_NAME, account.mDisplayName);
        cv.put(Calendars._SYNC_ACCOUNT, account.mEmailAddress);
        cv.put(Calendars._SYNC_ACCOUNT_TYPE, Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
        cv.put(Calendars.SYNC_EVENTS, 1);
        cv.put(Calendars.SELECTED, 1);
        cv.put(Calendars.HIDDEN, 0);
        // TODO Coordinate account colors w/ Calendar, if possible
        // Make Email account color opaque
        cv.put(Calendars.COLOR, 0xFF000000 | Email.getAccountColor(account.mId));
        cv.put(Calendars.TIMEZONE, Time.getCurrentTimezone());
        cv.put(Calendars.ACCESS_LEVEL, Calendars.OWNER_ACCESS);
        cv.put(Calendars.OWNER_ACCOUNT, account.mEmailAddress);

        Uri uri = service.mContentResolver.insert(Calendars.CONTENT_URI, cv);
        // We save the id of the calendar into mSyncStatus
        if (uri != null) {
            String stringId = uri.getPathSegments().get(1);
            mailbox.mSyncStatus = stringId;
            return Long.parseLong(stringId);
        }
        return -1;
    }

    /**
     * Create a Message for an (Event) Entity
     * @param entity the Entity for the Event (as might be retrieved by CalendarProvider)
     * @param messageFlag the Message.FLAG_XXX constant indicating the type of email to be sent
     * @param the unique id of this Event, or null if it can be retrieved from the Event
     * @param the user's account
     * @return a Message with many fields pre-filled (more later)
     */
    static public EmailContent.Message createMessageForEntity(Entity entity, int messageFlag,
            String uid, Account account) {
        // TODO Handle exceptions; will be a nightmare
        // TODO Cries out for unit test
        ContentValues entityValues = entity.getEntityValues();

        EmailContent.Message msg = new EmailContent.Message();
        msg.mFlags = messageFlag;
        msg.mTimeStamp = System.currentTimeMillis();

        String method;
        if (messageFlag == EmailContent.Message.FLAG_OUTGOING_MEETING_INVITE) {
            method = "REQUEST";
        } else {
            method = "REPLY";
        }

        // Create our iCalendar writer and start generating tags
        SimpleIcsWriter ics;
        try {
            ics = new SimpleIcsWriter();
            ics.writeTag("BEGIN", "VCALENDAR");
            ics.writeTag("METHOD", method);
            ics.writeTag("PRODID", "AndroidEmail");
            ics.writeTag("VERSION", "2.0");
            ics.writeTag("BEGIN", "VEVENT");
            ics.writeTag("CLASS", "PUBLIC");
            ics.writeTag("STATUS", "CONFIRMED");
            ics.writeTag("TRANSP", "OPAQUE"); // What Exchange uses
            ics.writeTag("PRIORITY", "5");  // 1 to 9, 5 = medium
            ics.writeTag("SEQUENCE", "0");
            if (uid == null) {
                uid = entityValues.getAsString(Events._SYNC_LOCAL_ID);
            }
            if (uid != null) {
                ics.writeTag("UID", uid);
            }

            if (entityValues.containsKey(Events.ALL_DAY)) {
                Integer ade = entityValues.getAsInteger(Events.ALL_DAY);
                ics.writeTag("X-MICROSOFT-CDO-ALLDAYEVENT", ade == 0 ? "FALSE" : "TRUE");
            }

            long startTime = entityValues.getAsLong(Events.DTSTART);
            ics.writeTag("DTSTART", CalendarUtilities.millisToEasDateTime(startTime));

            if (!entityValues.containsKey(Events.DURATION)) {
                if (entityValues.containsKey(Events.DTEND)) {
                    ics.writeTag("DTEND", CalendarUtilities.millisToEasDateTime(
                            entityValues.getAsLong(Events.DTEND)));
                }
            } else {
                // Convert this into millis and add it to DTSTART for DTEND
                // We'll use 1 hour as a default
                long durationMillis = HOURS;
                Duration duration = new Duration();
                try {
                    duration.parse(entityValues.getAsString(Events.DURATION));
                } catch (ParseException e) {
                    // We'll use the default in this case
                }
                ics.writeTag("DTEND",
                        CalendarUtilities.millisToEasDateTime(startTime + durationMillis));
            }

            ics.writeTag("DTSTAMP",
                    CalendarUtilities.millisToEasDateTime(System.currentTimeMillis()));

            if (entityValues.containsKey(Events.EVENT_LOCATION)) {
                ics.writeTag("LOCATION", entityValues.getAsString(Events.EVENT_LOCATION));
            }
            String title = entityValues.getAsString(Events.TITLE);
            if (title != null) {
                ics.writeTag("SUMMARY", title);
                // TODO Add to strings.xml
                msg.mSubject = "Invitation" + ": " +  title;
            } else {
                msg.mSubject = "Invitation";
            }

            // TODO Handle time zone

            String desc = entityValues.getAsString(Events.DESCRIPTION);
            if (desc != null) {
                // TODO Do we need to create something (like we'll do with the email)?
                ics.writeTag("DESCRIPTION", desc);
                msg.mText = "Boilerplate" + "\n\n" + desc;
            } else {
                msg.mText = "Boilerplate";
            }

            String rrule = entityValues.getAsString(Events.RRULE);
            if (rrule != null) {
                ics.writeTag("RRULE", rrule);
            }

            // Handle associated data EXCEPT for attendees, which have to be grouped
            ArrayList<NamedContentValues> subValues = entity.getSubValues();
            for (NamedContentValues ncv: subValues) {
                Uri ncvUri = ncv.uri;
                if (ncvUri.equals(Reminders.CONTENT_URI)) {
                    // TODO Consider sending out alarm information in the meeting request, although
                    // it's not obviously appropriate (i.e. telling the user what alarm to use)
                    // This should be for REQUEST only
                    // Here's what the VALARM would look like:
                    //                  BEGIN:VALARM
                    //                  ACTION:DISPLAY
                    //                  DESCRIPTION:REMINDER
                    //                  TRIGGER;RELATED=START:-PT15M
                    //                  END:VALARM
                }
            }

            // Handle attendee data here; keep track of organizer and stream it afterward
            String organizerName = null;
            String organizerEmail = null;
            ArrayList<Address> toList = new ArrayList<Address>();
            for (NamedContentValues ncv: subValues) {
                Uri ncvUri = ncv.uri;
                ContentValues ncvValues = ncv.values;
                if (ncvUri.equals(Attendees.CONTENT_URI)) {
                    Integer relationship =
                        ncvValues.getAsInteger(Attendees.ATTENDEE_RELATIONSHIP);
                    // If there's no relationship, we can't create this for EAS
                    // Similarly, we need an attendee email for each invitee
                    if (relationship != null &&
                            ncvValues.containsKey(Attendees.ATTENDEE_EMAIL)) {
                        // Organizer isn't among attendees in EAS
                        if (relationship == Attendees.RELATIONSHIP_ORGANIZER) {
                            organizerName = ncvValues.getAsString(Attendees.ATTENDEE_NAME);
                            organizerEmail = ncvValues.getAsString(Attendees.ATTENDEE_EMAIL);
                            continue;
                        }
                        String attendeeEmail = ncvValues.getAsString(Attendees.ATTENDEE_EMAIL);
                        String attendeeName = ncvValues.getAsString(Attendees.ATTENDEE_NAME);
                        // This shouldn't be possible, but allow for it
                        if (attendeeEmail == null) continue;

                        if (messageFlag == Message.FLAG_OUTGOING_MEETING_INVITE) {
                            String icalTag = ICALENDAR_ATTENDEE_INVITE;
                            if (attendeeName != null) {
                                icalTag += ";CN=" + attendeeName;
                            }
                            ics.writeTag(icalTag, "MAILTO:" + attendeeEmail);
                            toList.add(attendeeName == null ? new Address(attendeeEmail) :
                                new Address(attendeeEmail, attendeeName));
                        } else if (attendeeEmail.equalsIgnoreCase(account.mEmailAddress)) {
                            String icalTag = null;
                            switch (messageFlag) {
                                case Message.FLAG_OUTGOING_MEETING_ACCEPT:
                                    icalTag = ICALENDAR_ATTENDEE_ACCEPT;
                                    break;
                                case Message.FLAG_OUTGOING_MEETING_DECLINE:
                                    icalTag = ICALENDAR_ATTENDEE_DECLINE;
                                    break;
                                case Message.FLAG_OUTGOING_MEETING_TENTATIVE:
                                    icalTag = ICALENDAR_ATTENDEE_TENTATIVE;
                                    break;
                            }
                            if (icalTag != null) {
                                if (attendeeName != null) {
                                    icalTag += ";CN=" + attendeeName;
                                }
                                ics.writeTag(icalTag, "MAILTO:" + attendeeEmail);
                            }
                        }
                    }
                }
            }

            // Create the organizer tag for ical
            if (organizerEmail != null) {
                String icalTag = "ORGANIZER";
                // We should be able to find this, assuming the Email is the user's email
                // TODO Find this in the account
                if (organizerName != null) {
                    icalTag += ";CN=" + organizerName;
                }
                ics.writeTag(icalTag, "MAILTO:" + organizerEmail);
                if (method.equals("REPLY")) {
                    toList.add(organizerName == null ? new Address(organizerEmail) :
                        new Address(organizerEmail, organizerName));
                }
            }

            // If we have no "to" list, we're done
            if (toList.isEmpty()) return null;
            // Write out the "to" list
            Address[] toArray = new Address[toList.size()];
            int i = 0;
            for (Address address: toList) {
                toArray[i++] = address;
            }
            msg.mTo = Address.pack(toArray);

            ics.writeTag("END", "VEVENT");
            ics.writeTag("END", "VCALENDAR");
            ics.flush();
            ics.close();
        } catch (IOException e) {;;
            Log.w(TAG, "IOException creating message for Event");
            return null;
        }

        // Create the ics attachment using the "content" field
        Attachment att = new Attachment();
        att.mContent = ics.toString();
        att.mMimeType = "text/calendar; method=" + method;
        att.mFileName = "invite.ics";
        att.mSize = att.mContent.length();
        // We don't send content-disposition with this attachment
        att.mFlags = Attachment.FLAG_SUPPRESS_DISPOSITION;

        // Add the attachment to the message
        msg.mAttachments = new ArrayList<Attachment>();
        msg.mAttachments.add(att);

        // Return the new Message to caller
        return msg;
    }

    /**
     * Create a Message for an Event that can be retrieved from CalendarProvider by its unique id
     * @param cr a content resolver that can be used to query for the Event
     * @param eventId the unique id of the Event
     * @param messageFlag the Message.FLAG_XXX constant indicating the type of email to be sent
     * @param the unique id of this Event, or null if it can be retrieved from the Event
     * @param the user's account
     * @return a Message with many fields pre-filled (more later)
     * @throws RemoteException if there is an issue retrieving the Event from CalendarProvider
     */
    static public EmailContent.Message createMessageForEventId(ContentResolver cr, long eventId,
            int messageFlag, String uid, Account account) throws RemoteException {
        EntityIterator eventIterator =
            EventsEntity.newEntityIterator(
                    cr.query(ContentUris.withAppendedId(Events.CONTENT_URI.buildUpon()
                            .appendQueryParameter(android.provider.Calendar.CALLER_IS_SYNCADAPTER,
                            "true").build(), eventId), null, null, null, null), cr);
        try {
            while (eventIterator.hasNext()) {
                Entity entity = eventIterator.next();
                return createMessageForEntity(entity, messageFlag, uid, account);
            }
        } finally {
            eventIterator.close();
        }
        return null;
    }
}