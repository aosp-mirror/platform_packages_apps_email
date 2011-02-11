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

package com.android.email.mail.store.imap;

import static com.android.email.mail.store.imap.ImapTestUtils.createFixedLengthInputStream;

import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.utility.Utility;

import org.apache.commons.io.IOUtils;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.IOException;
import java.util.Date;
import java.util.Locale;


/**
 * Test for {@link ImapString} and its subclasses.
 */
@SmallTest
public class ImapStringTest extends AndroidTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        TempDirectory.setTempDirectory(getContext());
    }

    public void testEmpty() throws Exception {
        assertTrue(ImapString.EMPTY.isEmpty());
        assertEquals("", ImapString.EMPTY.getString());
        assertEquals("", Utility.fromAscii(IOUtils.toByteArray(ImapString.EMPTY.getAsStream())));
        assertFalse(ImapString.EMPTY.isNumber());
        assertFalse(ImapString.EMPTY.isDate());

        assertTrue(ImapString.EMPTY.is(""));
        assertTrue(ImapString.EMPTY.startsWith(""));
        assertFalse(ImapString.EMPTY.is("a"));
        assertFalse(ImapString.EMPTY.startsWith("a"));

        assertTrue(new ImapSimpleString(null).isEmpty());
    }

    public void testBasics() throws Exception {
        final ImapSimpleString s = new ImapSimpleString("AbcD");
        assertFalse(s.isEmpty());
        assertEquals("AbcD", s.getString());
        assertEquals("AbcD", Utility.fromAscii(IOUtils.toByteArray(s.getAsStream())));

        assertFalse(s.isNumber());
        assertFalse(s.isDate());

        assertFalse(s.is(null));
        assertFalse(s.is(""));
        assertTrue(s.is("abcd"));
        assertFalse(s.is("abc"));

        assertFalse(s.startsWith(null));
        assertTrue(s.startsWith(""));
        assertTrue(s.startsWith("a"));
        assertTrue(s.startsWith("abcd"));
        assertFalse(s.startsWith("Z"));
        assertFalse(s.startsWith("abcde"));
    }

    public void testGetNumberOrZero() {
        assertEquals(1234, new ImapSimpleString("1234").getNumberOrZero());
        assertEquals(-1, new ImapSimpleString("-1").getNumberOrZero());
        assertEquals(0, new ImapSimpleString("").getNumberOrZero());
        assertEquals(0, new ImapSimpleString("X").getNumberOrZero());
        assertEquals(0, new ImapSimpleString("1234E").getNumberOrZero());

        // Too large for 32 bit int
        assertEquals(0, new ImapSimpleString("99999999999999999999").getNumberOrZero());
    }

    public void testGetDateOrNull() {
        final ImapString date = new ImapSimpleString("01-Jan-2009 11:34:56 -0100");

        assertTrue(date.isDate());
        Date d = date.getDateOrNull();
        assertNotNull(d);
        assertEquals("1 Jan 2009 12:34:56 GMT", d.toGMTString());

        final ImapString nonDate = new ImapSimpleString("1234");
        assertFalse(nonDate.isDate());
        assertNull(nonDate.getDateOrNull());
    }

    /**
     * Confirms that getDateOrNull() works fine regardless of the current locale.
     */
    public void testGetDateOrNullOnDifferentLocales() throws Exception {
        Locale savedLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            checkGetDateOrNullOnDifferentLocales();
            Locale.setDefault(Locale.JAPAN);
            checkGetDateOrNullOnDifferentLocales();
        } finally {
            Locale.setDefault(savedLocale);
        }
    }

    private static void checkGetDateOrNullOnDifferentLocales() throws Exception {
        ImapSimpleString s =  new ImapSimpleString("01-Jan-2009 11:34:56 -0100");
        assertEquals("1 Jan 2009 12:34:56 GMT", s.getDateOrNull().toGMTString());
    }

    /** Test for ImapMemoryLiteral */
    public void testImapMemoryLiteral() throws Exception {
        final String CONTENT = "abc";
        doLiteralTest(new ImapMemoryLiteral(createFixedLengthInputStream(CONTENT)), CONTENT);
    }

    /** Test for ImapTempFileLiteral */
    public void testImapTempFileLiteral() throws Exception {
        final String CONTENT = "def";
        ImapTempFileLiteral l = new ImapTempFileLiteral(createFixedLengthInputStream(CONTENT));
        doLiteralTest(l, CONTENT);

        // destroy() should remove the temp file.
        assertTrue(l.tempFileExistsForTest());
        l.destroy();
        assertFalse(l.tempFileExistsForTest());
    }

    private static void doLiteralTest(ImapString s, String content) throws IOException {
        assertEquals(content, s.getString());
        assertEquals(content, Utility.fromAscii(IOUtils.toByteArray(s.getAsStream())));
    }
}
