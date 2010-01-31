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

import com.android.exchange.Eas;
import com.android.exchange.adapter.Serializer;
import com.android.exchange.adapter.Tags;

import org.bouncycastle.util.encoders.Base64;

import android.util.Log;

import java.io.IOException;
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

    // There is no type 4 (thus, the "")
    static final String[] sTypeToFreq =
        new String[] {"DAILY", "WEEKLY", "MONTHLY", "MONTHLY", "", "YEARLY", "YEARLY"};

    static final String[] sDayTokens =
        new String[] {"SU", "MO", "TU", "WE", "TH", "FR", "SA"};

    static final String[] sTwoCharacterNumbers =
        new String[] {"00", "01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12"};

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

    // Return a String from within a byte array at the given offset with max characters
    // Unused for now, but might be helpful for debugging
    //    String getString(byte[] bytes, int offset, int max) {
    //    	StringBuilder sb = new StringBuilder();
    //    	while (max-- > 0) {
    //    		int b = bytes[offset];
    //    		if (b == 0) break;
    //    		sb.append((char)b);
    //    		offset += 2;
    //    	}
    //    	return sb.toString();
    //    }

    /**
     * Build a GregorianCalendar, based on a time zone and TimeZoneDate.
     * @param timeZone the time zone we're checking
     * @param tzd the TimeZoneDate we're interested in
     * @return a GregorianCalendar with the given time zone and date
     */
    static GregorianCalendar getCheckCalendar(TimeZone timeZone, TimeZoneDate tzd) {
        GregorianCalendar testCalendar = new GregorianCalendar(timeZone);
        testCalendar.set(GregorianCalendar.YEAR, 2009);
        testCalendar.set(GregorianCalendar.MONTH, tzd.month);
        testCalendar.set(GregorianCalendar.DAY_OF_WEEK, tzd.dayOfWeek);
        testCalendar.set(GregorianCalendar.DAY_OF_WEEK_IN_MONTH, tzd.day);
        testCalendar.set(GregorianCalendar.HOUR_OF_DAY, tzd.hour);
        testCalendar.set(GregorianCalendar.MINUTE, tzd.minute);
        return testCalendar;
    }

    /**
     * Given a String as directly read from EAS, returns a TimeZone corresponding to that String
     * @param timeZoneString the String read from the server
     * @return the TimeZone, or TimeZone.getDefault() if not found
     */
    static public TimeZone parseTimeZone(String timeZoneString) {
        // If we have this time zone cached, use that value and return
        TimeZone timeZone = sTimeZoneCache.get(timeZoneString);
        if (timeZone != null) {
            if (Eas.USER_LOG) {
                Log.d(TAG, "TimeZone " + timeZone.getID() + " in cache: " + timeZone.getDisplayName());
            }
            return timeZone;
        }

        // First, we need to decode the base64 string
        byte[] timeZoneBytes = Base64.decode(timeZoneString);

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
                return timeZone;
            } else {
                TimeZoneDate dstStart =	getTimeZoneDateFromSystemTime(timeZoneBytes,
                        MSFT_TIME_ZONE_DAYLIGHT_DATE_OFFSET);
                // See comment above for bias...
                long dstSavings =
                    -1 * getLong(timeZoneBytes, MSFT_TIME_ZONE_DAYLIGHT_BIAS_OFFSET) * 60*SECONDS;

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

                    // Check start DST transition
                    GregorianCalendar testCalendar = getCheckCalendar(timeZone, dstStart);
                    testCalendar.add(GregorianCalendar.MINUTE, -1);
                    Date before = testCalendar.getTime();
                    testCalendar.add(GregorianCalendar.MINUTE, 2);
                    Date after = testCalendar.getTime();
                    if (timeZone.inDaylightTime(before)) continue;
                    if (!timeZone.inDaylightTime(after)) continue;

                    // Check end DST transition
                    testCalendar = getCheckCalendar(timeZone, dstEnd);
                    testCalendar.add(GregorianCalendar.HOUR, -2);
                    before = testCalendar.getTime();
                    testCalendar.add(GregorianCalendar.HOUR, 2);
                    after = testCalendar.getTime();
                    if (!timeZone.inDaylightTime(before)) continue;
                    if (timeZone.inDaylightTime(after)) continue;

                    // Check that the savings are the same
                    if (dstSavings != timeZone.getDSTSavings()) continue;

                    // If we're here, it's the right time zone, modulo dynamic DST
                    String dn = timeZone.getDisplayName();
                    sTimeZoneCache.put(timeZoneString, timeZone);
                    if (Eas.USER_LOG) {
                        Log.d(TAG, "TimeZone found by rules: " + dn);
                    }
                    return timeZone;
                }
            }
        }
        // If we don't find a match, we just return the current TimeZone.  In theory, this
        // shouldn't be happening...
        Log.w(TAG, "TimeZone not found with bias = " + bias + ", using default.");
        return TimeZone.getDefault();
    }

    /**
     * Generate a Base64 representation of a MSFT TIME_ZONE_INFORMATION structure from a TimeZone
     * ID that might be found in an Event.  For now, we'll just use the standard bias, and we'll
     * tackle DST later
     * @param name the name of the TimeZone
     * @return the Base64 String representing a Microsoft TIME_ZONE_INFORMATION element
     */
    static public String timeZoneToTZIString(String name) {
        // TODO Handle DST (ugh)
        TimeZone tz = TimeZone.getTimeZone(name);
        byte[] tziBytes = new byte[MSFT_TIME_ZONE_SIZE];

        int standardBias = - tz.getRawOffset();
        standardBias /= 60*SECONDS;
        setLong(tziBytes, MSFT_TIME_ZONE_BIAS_OFFSET, standardBias);

        byte[] tziEncodedBytes = Base64.encode(tziBytes);
        return new String(tziEncodedBytes);
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

     static public String millisToEasDateTime(long millis) {
         StringBuilder sb = new StringBuilder();
         GregorianCalendar cal = new GregorianCalendar(TimeZone.getTimeZone("GMT"));
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

    static String generateEasDayOfWeek(String dow) {
        int bit = 1;
        for (String token: sDayTokens) {
            if (dow.equals(token)) {
                break;
            } else {
                bit <<= 1;
            }
        }
        return Integer.toString(bit);
    }

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
     * Write recurrence information to EAS based on the RRULE in CalendarProvider
     * @param rrule the RRULE, from CalendarProvider
     * @param startTime, the DTSTART of this Event
     * @param s the Serializer we're using to write WBXML data
     * @throws IOException
     */
    // NOTE: For the moment, we're only parsing recurrence types that are supported by the
    // Calendar app UI, which is a small subset of possible recurrence types
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
                s.end();
            } else if (freq.equals("MONTHLY")) {
                String byMonthDay = tokenFromRrule(rrule, "BYMONTHDAY=");
                if (byMonthDay != null) {
                    // The nth day of the month
                    s.start(Tags.CALENDAR_RECURRENCE);
                    s.data(Tags.CALENDAR_RECURRENCE_TYPE, "2");
                    s.data(Tags.CALENDAR_RECURRENCE_DAYOFMONTH, byMonthDay);
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
                s.end();
             }
        }
    }

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
        // TODO Add UNTIL code
        if (until != null) {
            // *** until probably needs reformatting
            //rrule.append(";UNTIL=" + until);
        }

        return rrule.toString();
    }
}