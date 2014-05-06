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

import android.test.AndroidTestCase;
import android.test.IsolatedContext;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.TextUtils;

import com.android.emailcommon.utility.Utility.NewFileCreator;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

@SmallTest
public class UtilityTest extends AndroidTestCase {
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

    private static NewFileCreator getCountdownFileCreator() {
        return new NewFileCreator() {
            private int mCountdown = 5;
            @Override
            public boolean createNewFile(File f) throws IOException {
                return mCountdown-- <= 0;
            }
        };
    }

    private static NewFileCreator getTrueFileCreator() {
        return new NewFileCreator() {
            @Override
            public boolean createNewFile(File f) throws IOException {
                return true;
            }
        };
    }

    @SmallTest
    public void testCreateUniqueFileCompare() throws Exception {
        final File directory =
                new IsolatedContext(new MockContentResolver(), getContext()).getFilesDir();

        final File created1 =
                Utility.createUniqueFileInternal(getCountdownFileCreator(), directory, "file");
        assertNotNull(created1);
        assertFalse(TextUtils.equals(created1.getName(), "file"));

        final File created2 =
                Utility.createUniqueFileInternal(getTrueFileCreator(), directory, "file");
        assertNotNull(created2);
        assertTrue(TextUtils.equals(created2.getName(), "file"));

        final File created3 =
                Utility.createUniqueFileInternal(getCountdownFileCreator(), directory, "file.ext");
        assertNotNull(created3);
        assertFalse(TextUtils.equals(created3.getName(), "file.ext"));

        final File created4 =
                Utility.createUniqueFileInternal(getTrueFileCreator(), directory, "file.ext");
        assertNotNull(created4);
        assertTrue(TextUtils.equals(created4.getName(), "file.ext"));
    }

    @SmallTest
    public void testCreateUniqueFileWithPercent() throws Exception {
        final File directory =
                new IsolatedContext(new MockContentResolver(), getContext()).getFilesDir();

        final File created1 =
                Utility.createUniqueFileInternal(getTrueFileCreator(), directory, "file%s");
        assertNotNull(created1);

        final File created2 =
                Utility.createUniqueFileInternal(getTrueFileCreator(), directory, "file%s.ext");
        assertNotNull(created2);
    }

    @SmallTest
    public void testCreateUniqueFile() throws Exception {
        final File directory =
                new IsolatedContext(new MockContentResolver(), getContext()).getFilesDir();

        final File created1 =
                Utility.createUniqueFileInternal(getTrueFileCreator(), directory, "file");
        assertNotNull(created1);

        final File created2 =
                Utility.createUniqueFileInternal(getCountdownFileCreator(), directory, "file");
        assertNotNull(created2);

        final File created3 =
                Utility.createUniqueFileInternal(getTrueFileCreator(), directory, "file.ext");
        assertNotNull(created3);

        final File created4 =
                Utility.createUniqueFileInternal(getCountdownFileCreator(), directory, "file.ext");
        assertNotNull(created4);

        final File created5 =
                Utility.createUniqueFileInternal(getTrueFileCreator(), directory, ".ext");
        assertNotNull(created5);

        final File created6 =
                Utility.createUniqueFileInternal(getCountdownFileCreator(), directory, ".ext");
        assertNotNull(created6);

        final File created7 =
                Utility.createUniqueFileInternal(getTrueFileCreator(), directory, ".");
        assertNotNull(created7);

        final File created8 =
                Utility.createUniqueFileInternal(getCountdownFileCreator(), directory, ".");
        assertNotNull(created8);
    }

    @SmallTest
    public void testCreateUniqueFileExtensions() throws Exception {
        final File directory =
                new IsolatedContext(new MockContentResolver(), getContext()).getFilesDir();

        final File created1 =
                Utility.createUniqueFileInternal(getTrueFileCreator(), directory, "file");
        assertNotNull(created1);
        assertEquals(created1.getName().indexOf('.'), -1);

        final File created2 =
                Utility.createUniqueFileInternal(getCountdownFileCreator(), directory, "file");
        assertNotNull(created2);
        assertEquals(created2.getName().indexOf('.'), -1);

        final File created3 =
                Utility.createUniqueFileInternal(getTrueFileCreator(), directory, "file.ext");
        assertNotNull(created3);
        assertEquals(created3.getName().length() - created3.getName().lastIndexOf('.'), 4);

        final File created4 =
                Utility.createUniqueFileInternal(getCountdownFileCreator(), directory, "file.ext");
        assertNotNull(created4);
        assertEquals(created4.getName().length() - created4.getName().lastIndexOf('.'), 4);
    }
}
