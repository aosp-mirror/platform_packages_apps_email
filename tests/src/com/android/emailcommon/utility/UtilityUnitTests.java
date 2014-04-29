/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.content.Context;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.os.Environment;
import android.test.AndroidTestCase;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.widget.TextView;

import com.android.email.DBTestHelper;
import com.android.email.TestUtils;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility.NewFileCreator;
import com.android.mail.utils.MatrixCursorWithCachedColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * This is a series of unit tests for the Utility class.  These tests must be locally
 * complete - no server(s) required.
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.UtilityUnitTests email
 */
@Suppress
@SmallTest
public class UtilityUnitTests extends AndroidTestCase {

    private static byte[] b(int... array) {
        return TestUtils.b(array);
    }

    public void testToUtf8() {
        assertNull(Utility.toUtf8(null));
        MoreAsserts.assertEquals(new byte[] {}, Utility.toUtf8(""));
        MoreAsserts.assertEquals(b('a'), Utility.toUtf8("a"));
        MoreAsserts.assertEquals(b('A', 'B', 'C'), Utility.toUtf8("ABC"));
        MoreAsserts.assertEquals(b(0xE6, 0x97, 0xA5, 0xE6, 0x9C, 0xAC, 0xE8, 0xAA, 0x9E),
                Utility.toUtf8("\u65E5\u672C\u8A9E"));
    }

    public void testFromUtf8() {
        assertNull(Utility.fromUtf8(null));
        assertEquals("", Utility.fromUtf8(new byte[] {}));
        assertEquals("a", Utility.fromUtf8(b('a')));
        assertEquals("ABC", Utility.fromUtf8(b('A', 'B', 'C')));
        assertEquals("\u65E5\u672C\u8A9E",
                Utility.fromUtf8(b(0xE6, 0x97, 0xA5, 0xE6, 0x9C, 0xAC, 0xE8, 0xAA, 0x9E)));
    }

    public void testIsFirstUtf8Byte() {
        // 1 byte in UTF-8.
        checkIsFirstUtf8Byte("0"); // First 2 bits: 00
        checkIsFirstUtf8Byte("A"); // First 2 bits: 01

        checkIsFirstUtf8Byte("\u00A2"); // 2 bytes in UTF-8.
        checkIsFirstUtf8Byte("\u20AC"); // 3 bytes in UTF-8.
        checkIsFirstUtf8Byte("\uD852\uDF62"); // 4 bytes in UTF-8.  (surrogate pair)
    }

    private void checkIsFirstUtf8Byte(String aChar) {
        byte[] bytes = Utility.toUtf8(aChar);
        assertTrue("0", Utility.isFirstUtf8Byte(bytes[0]));
        for (int i = 1; i < bytes.length; i++) {
            assertFalse(Integer.toString(i), Utility.isFirstUtf8Byte(bytes[i]));
        }
    }

    public void testByteToHex() {
        for (int i = 0; i <= 0xFF; i++) {
            String hex = Utility.byteToHex((byte) i);
            assertEquals("val=" + i, 2, hex.length());
            assertEquals("val=" + i, i, Integer.parseInt(hex, 16));
        }
    }

    public void testReplaceBareLfWithCrlf() {
        assertEquals("", Utility.replaceBareLfWithCrlf(""));
        assertEquals("", Utility.replaceBareLfWithCrlf("\r"));
        assertEquals("\r\n", Utility.replaceBareLfWithCrlf("\r\n"));
        assertEquals("\r\n", Utility.replaceBareLfWithCrlf("\n"));
        assertEquals("\r\n\r\n\r\n", Utility.replaceBareLfWithCrlf("\n\n\n"));
        assertEquals("A\r\nB\r\nC\r\nD", Utility.replaceBareLfWithCrlf("A\nB\r\nC\nD"));
    }

    public void testGetSmallHash() {
        assertEquals("1438642069", Utility.getSmallHash(""));
        assertEquals("1354919068", Utility.getSmallHash("abc"));
    }

    public void testGetSmallSha1() {
        byte[] sha1 = new byte[20];

        // White box test.  Not so great, but to make sure it may detect careless mistakes...
        assertEquals(0, Utility.getSmallHashFromSha1(sha1));

        for (int i = 0; i < sha1.length; i++) {
            sha1[i] = (byte) 0xFF;
        }
        assertEquals(Integer.MAX_VALUE, Utility.getSmallHashFromSha1(sha1));

        // Boundary check
        for (int i = 0; i < 16; i++) {
            sha1[19] = (byte) i;
            Utility.getSmallHashFromSha1(sha1);
        }
    }

    public void brokentestCleanUpMimeDate() {
        assertNull(Utility.cleanUpMimeDate(null));
        assertEquals("", Utility.cleanUpMimeDate(""));
        assertEquals("abc", Utility.cleanUpMimeDate("abc"));
        assertEquals("GMT", Utility.cleanUpMimeDate("GMT"));
        assertEquals("0000", Utility.cleanUpMimeDate("0000"));
        assertEquals("-0000", Utility.cleanUpMimeDate("-0000"));
        assertEquals("+1234", Utility.cleanUpMimeDate("GMT+1234"));
        assertEquals("-1234", Utility.cleanUpMimeDate("GMT-1234"));
        assertEquals("gmt-1234", Utility.cleanUpMimeDate("gmt-1234"));
        assertEquals("GMT-123", Utility.cleanUpMimeDate("GMT-123"));

        assertEquals("Thu, 10 Dec 09 15:08:08 -0700",
                Utility.cleanUpMimeDate("Thu, 10 Dec 09 15:08:08 GMT-0700"));
        assertEquals("Thu, 10 Dec 09 15:08:08 -0700",
                Utility.cleanUpMimeDate("Thu, 10 Dec 09 15:08:08 -0700"));
    }

    private static class MyNewFileCreator implements NewFileCreator {
        private final HashSet<String> mExistingFileNames;

        public MyNewFileCreator(String... fileNames) {
            mExistingFileNames = new HashSet<String>();
            for (String f : fileNames) {
                mExistingFileNames.add(f);
            }
        }

        @Override public boolean createNewFile(File f) {
            return !mExistingFileNames.contains(f.getAbsolutePath());
        }
    }

    public void testCreateUniqueFile() throws Exception {
        final MyNewFileCreator noFiles = new MyNewFileCreator();

        // Case 1: Files don't exist.
        checkCreateUniqueFile("/a", noFiles, "/", "a");
        checkCreateUniqueFile("/a.txt", noFiles, "/", "a.txt");

        checkCreateUniqueFile("/a/b/a", noFiles, "/a/b", "a");
        checkCreateUniqueFile("/a/b/a.txt", noFiles, "/a/b", "a.txt");

        // Case 2: Files exist already.
        final MyNewFileCreator files = new MyNewFileCreator(
                "/a", "/a.txt", "/a/b/a", "/a/b/a.txt",
                "/a-2.txt",
                "/a/b/a-2", "/a/b/a-3",
                "/a/b/a-2.txt", "/a/b/a-3.txt", "/a/b/a-4.txt"
                );

        checkCreateUniqueFile("/a-2", files, "/", "a");
        checkCreateUniqueFile("/a-3.txt", files, "/", "a.txt");

        checkCreateUniqueFile("/a/b/a-4", files, "/a/b", "a");
        checkCreateUniqueFile("/a/b/a-5.txt", files, "/a/b", "a.txt");
    }

    private void checkCreateUniqueFile(String expectedFileName, NewFileCreator nfc,
            String dir, String fileName) throws Exception {
        assertEquals(expectedFileName,
                Utility.createUniqueFileInternal(nfc, new File(dir), fileName).toString());
    }

    /**
     * Test that we have the necessary permissions to write to external storage.
     */
    public void testExternalStoragePermissions() throws FileNotFoundException, IOException {
        File file = null;
        try {
            // If there's no storage available, this test is moot
            if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
                return;
            }
            file = Utility.createUniqueFile(Environment.getExternalStorageDirectory(),
                    "write-test");
            OutputStream out = new FileOutputStream(file);
            out.write(1);
            out.close();
        } finally {
            try {
                if (file != null) {
                    if (file.exists()) {
                        file.delete();
                    }
                }
            } catch (Exception e) {
                // ignore cleanup error - it still throws the original
            }
        }
    }

    public void testIsPortFieldValid() {
        TextView view = new TextView(getContext());
        // null, empty, negative, and non integer strings aren't valid
        view.setText(null);
        assertFalse(Utility.isPortFieldValid(view));
        view.setText("");
        assertFalse(Utility.isPortFieldValid(view));
        view.setText("-1");
        assertFalse(Utility.isPortFieldValid(view));
        view.setText("1403.75");
        assertFalse(Utility.isPortFieldValid(view));
        view.setText("0");
        assertFalse(Utility.isPortFieldValid(view));
        view.setText("65536");
        assertFalse(Utility.isPortFieldValid(view));
        view.setText("i'm not valid");
        assertFalse(Utility.isPortFieldValid(view));
        // These next values are valid
        view.setText("1");
        assertTrue(Utility.isPortFieldValid(view));
        view.setText("65535");
        assertTrue(Utility.isPortFieldValid(view));
    }

    public void testToPrimitiveLongArray() {
        assertEquals(0, Utility.toPrimitiveLongArray(createLongCollection()).length);

        final long[] one = Utility.toPrimitiveLongArray(createLongCollection(1));
        assertEquals(1, one.length);
        assertEquals(1, one[0]);

        final long[] two = Utility.toPrimitiveLongArray(createLongCollection(3, 4));
        assertEquals(2, two.length);
        assertEquals(3, two[0]);
        assertEquals(4, two[1]);
    }

    public void testToLongSet() {
        assertEquals(0, Utility.toLongSet(new long[] {}).size());

        final Set<Long> one = Utility.toLongSet(new long[] {1});
        assertEquals(1, one.size());
        assertTrue(one.contains(1L));

        final Set<Long> two = Utility.toLongSet(new long[] {1, 2});
        assertEquals(2, two.size());
        assertTrue(two.contains(1L));
        assertTrue(two.contains(2L));
    }

    public void testGetContentFileName() throws Exception {
        Context providerContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(
                mContext);

        final long ACCOUNT_ID = 1;
        final long MESSAGE_ID = 10;

        Account account = ProviderTestUtils.setupAccount("account", true, providerContext);
        Mailbox mailbox = ProviderTestUtils.setupMailbox("box", account.mId, true, providerContext);

        // Set up an attachment.
        Attachment att = ProviderTestUtils.setupAttachment(mailbox.mId, "name", 123, true,
                providerContext);
        long attachmentId = att.mId;
        Uri uri = AttachmentUtilities.getAttachmentUri(account.mId, attachmentId);

        // Case 1: exists in the provider.
        assertEquals("name", Utility.getContentFileName(providerContext, uri));

        // Case 2: doesn't exist in the provider
        Uri notExistUri = AttachmentUtilities.getAttachmentUri(account.mId, 123456789);
        String lastPathSegment = notExistUri.getLastPathSegment();
        assertEquals(lastPathSegment, Utility.getContentFileName(providerContext, notExistUri));
    }

    // used by testToPrimitiveLongArray
    private static Collection<Long> createLongCollection(long... values) {
        ArrayList<Long> ret = new ArrayList<Long>();
        for (long value : values) {
            ret.add(value);
        }
        return ret;
    }

    public void testDumpCursor() {
        // Just make sure the method won't crash and returns non-empty string.
        final Cursor c1 = new MatrixCursorWithCachedColumns(new String[] {"col"});
        final Cursor c2 = new CursorWrapper(c1);

        // Note it's a subclass of CursorWrapper.
        final Cursor c3 = new CursorWrapper(c2) {
        };

        assertFalse(TextUtils.isEmpty(Utility.dumpCursor(c1)));
        assertFalse(TextUtils.isEmpty(Utility.dumpCursor(c2)));
        assertFalse(TextUtils.isEmpty(Utility.dumpCursor(c3)));
        assertFalse(TextUtils.isEmpty(Utility.dumpCursor(null)));

        // Test again with closed cursor.
        c1.close();
        assertFalse(TextUtils.isEmpty(Utility.dumpCursor(c1)));
        assertFalse(TextUtils.isEmpty(Utility.dumpCursor(c2)));
        assertFalse(TextUtils.isEmpty(Utility.dumpCursor(c3)));
        assertFalse(TextUtils.isEmpty(Utility.dumpCursor(null)));
    }

    public void testCloseTraceCursorWrapper() {
        final Cursor org = new MatrixCursorWithCachedColumns(new String[] {"col"});
        final Utility.CloseTraceCursorWrapper c =
                Utility.CloseTraceCursorWrapper.alwaysCreateForTest(org);

        // Not closed -- no stack trace
        assertNull(Utility.CloseTraceCursorWrapper.getTraceIfAvailable(c));
        Utility.CloseTraceCursorWrapper.log(c); // shouldn't crash

        // Close, now stack trace should be available
        c.close();
        assertNotNull(Utility.CloseTraceCursorWrapper.getTraceIfAvailable(c));
        Utility.CloseTraceCursorWrapper.log(c);

        // shouldn't crash
        Utility.CloseTraceCursorWrapper.log(null);
    }

    public void brokentestAppendBold() {
        SpannableStringBuilder ssb = new SpannableStringBuilder();
        ssb.append("no");

        assertEquals(ssb, Utility.appendBold(ssb, "BO"));

        assertEquals("noBO", ssb.toString());
        // TODO check style -- but how?
    }

    public void testAreStringsEqual() {
        String s1;
        String s2;

        s1 = new String("Foo");
        s2 = s1;
        assertTrue(Utility.areStringsEqual(s1, s2));

        s2 = new String("Foo");
        assertTrue(Utility.areStringsEqual(s1, s2));

        s2 = "Bar";
        assertFalse(Utility.areStringsEqual(s1, s2));

        s2 = null;
        assertFalse(Utility.areStringsEqual(s1, s2));

        s1 = null;
        s2 = "Bar";
        assertFalse(Utility.areStringsEqual(s1, s2));

        s1 = null;
        s2 = null;
        assertTrue(Utility.areStringsEqual(s1, s2));
    }

    public void testIsServerNameValid() {
        assertTrue(Utility.isServerNameValid("a"));
        assertTrue(Utility.isServerNameValid("gmail"));
        assertTrue(Utility.isServerNameValid("gmail.com"));
        assertTrue(Utility.isServerNameValid("gmail.com.x.y.z"));
        assertTrue(Utility.isServerNameValid("  gmail.com.x.y.z  "));

        assertFalse(Utility.isServerNameValid(""));
        assertFalse(Utility.isServerNameValid("$"));
        assertFalse(Utility.isServerNameValid("  "));
    }

    private static Collection<Long> toColleciton(long... values) {
        ArrayList<Long> ret = new ArrayList<Long>();
        for (long v : values) {
            ret.add(v);
        }
        return ret;
    }

    public void brokentestBuildInSelection() {
        assertEquals("", Utility.buildInSelection("c", null));
        assertEquals("", Utility.buildInSelection("c", toColleciton()));
        assertEquals("c in (1)", Utility.buildInSelection("c", toColleciton(1)));
        assertEquals("c in (1,2)", Utility.buildInSelection("c", toColleciton(1, 2)));
        assertEquals("c in (1,2,-500)", Utility.buildInSelection("c", toColleciton(1, 2, -500)));
    }
}
