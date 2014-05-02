/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.emailcommon.utility;

import android.test.suitebuilder.annotation.SmallTest;
import java.text.ParseException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;
import junit.framework.TestCase;

@SmallTest
public class UtilityTest extends TestCase {
    private void testParseDateTimesHelper(String date, int year, int month,
            int day, int hour, int minute, int second) throws Exception {
        GregorianCalendar cal = Utility.parseDateTimeToCalendar(date);
        assertEquals(year, cal.get(Calendar.YEAR));
        assertEquals(month, cal.get(Calendar.MONTH) + 1);
        assertEquals(day, cal.get(Calendar.DAY_OF_MONTH));
        assertEquals(hour, cal.get(Calendar.HOUR_OF_DAY));
        assertEquals(minute, cal.get(Calendar.MINUTE));
        assertEquals(second, cal.get(Calendar.SECOND));
    }

    @SmallTest
    public void testParseDateTimes() throws Exception {
        testParseDateTimesHelper("20090211T180303Z", 2009, 2, 11, 18, 3, 3);
        testParseDateTimesHelper("20090211", 2009, 2, 11, 0, 0, 0);
        try {
            testParseDateTimesHelper("200902", 0, 0, 0, 0, 0, 0);
            fail("Expected ParseException");
        } catch (ParseException e) {
            // expected
        }
    }

    private void testParseEmailDateTimeHelper(String date, int year, int month,
            int day, int hour, int minute, int second, int millis) throws Exception {
        GregorianCalendar cal = new GregorianCalendar(year, month - 1, day,
                hour, minute, second);
        cal.setTimeZone(TimeZone.getTimeZone("GMT"));
        long timeInMillis = Utility.parseEmailDateTimeToMillis(date);
        assertEquals(cal.getTimeInMillis() + millis, timeInMillis);
    }

    @SmallTest
    public void testParseEmailDateTime() throws Exception {
        testParseEmailDateTimeHelper("2010-02-23T16:01:05.000Z",
                2010, 2, 23, 16, 1, 5, 0);
        testParseEmailDateTimeHelper("2009-02-11T18:03:31.123Z",
                2009, 2, 11, 18, 3, 31, 123);
        testParseEmailDateTimeHelper("2009-02-11",
                2009, 2, 11, 0, 0, 0, 0);
        try {
            testParseEmailDateTimeHelper("2010-02", 1970, 1, 1, 0, 0, 0, 0);
            fail("Expected ParseException");
        } catch (ParseException e) {
            // expected
        }
    }
}
