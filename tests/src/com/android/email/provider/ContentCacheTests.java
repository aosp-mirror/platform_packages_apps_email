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

package com.android.email.provider;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.MatrixCursor;
import android.net.Uri;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.Suppress;

import com.android.email.provider.ContentCache.CacheToken;
import com.android.email.provider.ContentCache.CachedCursor;
import com.android.email.provider.ContentCache.TokenList;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.Mailbox;
import com.android.mail.utils.MatrixCursorWithCachedColumns;

/**
 * Tests of ContentCache
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.provider.ContentCacheTests email
 */
@Suppress
public class ContentCacheTests extends ProviderTestCase2<EmailProvider> {

    EmailProvider mProvider;
    Context mMockContext;

    public ContentCacheTests() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    public void testCounterMap() {
        ContentCache.CounterMap<String> map = new ContentCache.CounterMap<String>(4);
        // Make sure we can find added items
        map.add("1");
        assertTrue(map.contains("1"));
        map.add("2");
        map.add("2");
        // Make sure we can remove once for each add
        map.subtract("2");
        assertTrue(map.contains("2"));
        map.subtract("2");
        // Make sure that over-removing throws an exception
        try {
            map.subtract("2");
            fail("Removing a third time should throw an exception");
        } catch (IllegalStateException e) {
        }
        try {
            map.subtract("3");
            fail("Removing object never added should throw an exception");
        } catch (IllegalStateException e) {
        }
        // There should only be one item in the map ("1")
        assertEquals(1, map.size());
        assertTrue(map.contains("1"));
    }

    public void testTokenList() {
        TokenList list = new TokenList("Name");

        // Add two tokens for "1"
        CacheToken token1a = list.add("1");
        assertTrue(token1a.isValid());
        assertEquals("1", token1a.getId());
        assertEquals(1, list.size());
        CacheToken token1b = list.add("1");
        assertTrue(token1b.isValid());
        assertEquals("1", token1b.getId());
        assertTrue(token1a.equals(token1b));
        assertEquals(2, list.size());

        // Add a token for "2"
        CacheToken token2 = list.add("2");
        assertFalse(token1a.equals(token2));
        assertEquals(3, list.size());

        // Invalidate "1"; there should be two tokens invalidated
        assertEquals(2, list.invalidateTokens("1"));
        assertFalse(token1a.isValid());
        assertFalse(token1b.isValid());
        // Token2 should still be valid
        assertTrue(token2.isValid());
        // Only token2 should be in the list now (invalidation removes tokens)
        assertEquals(1, list.size());
        assertEquals(token2, list.get(0));

        // Add 3 tokens for "3"
        CacheToken token3a = list.add("3");
        CacheToken token3b = list.add("3");
        CacheToken token3c = list.add("3");
        // Remove two of them
        assertTrue(list.remove(token3a));
        assertTrue(list.remove(token3b));
        // Removing tokens doesn't invalidate them
        assertTrue(token3a.isValid());
        assertTrue(token3b.isValid());
        assertTrue(token3c.isValid());
        // There should be two items left "3" and "2"
        assertEquals(2, list.size());
    }

    public void testCachedCursors() {
        final ContentResolver resolver = mMockContext.getContentResolver();
        final Context context = mMockContext;

        // Create account and two mailboxes
        Account acct = ProviderTestUtils.setupAccount("account", true, context);
        ProviderTestUtils.setupMailbox("box1", acct.mId, true, context);
        Mailbox box = ProviderTestUtils.setupMailbox("box2", acct.mId, true, context);

        // We need to test with a query that only returns one row (others can't be put in a
        // CachedCursor)
        Uri uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, box.mId);
        Cursor cursor =
            resolver.query(uri, Mailbox.CONTENT_PROJECTION, null, null, null);
        // ContentResolver gives us back a wrapper
        assertTrue(cursor instanceof CursorWrapper);
        // The wrappedCursor should be a CachedCursor
        Cursor wrappedCursor = ((CursorWrapper)cursor).getWrappedCursor();
        assertTrue(wrappedCursor instanceof CachedCursor);
        CachedCursor cachedCursor = (CachedCursor)wrappedCursor;
        // The cursor wrapped in cachedCursor is the underlying cursor
        Cursor activeCursor = cachedCursor.getWrappedCursor();

        // The cursor should be in active cursors
        int activeCount = ContentCache.sActiveCursors.getCount(activeCursor);
        assertEquals(1, activeCount);

        // Some basic functionality that shouldn't throw exceptions and should otherwise act as the
        // underlying cursor would
        String[] columnNames = cursor.getColumnNames();
        assertEquals(Mailbox.CONTENT_PROJECTION.length, columnNames.length);
        for (int i = 0; i < Mailbox.CONTENT_PROJECTION.length; i++) {
            assertEquals(Mailbox.CONTENT_PROJECTION[i], columnNames[i]);
        }

        assertEquals(1, cursor.getCount());
        cursor.moveToNext();
        assertEquals(0, cursor.getPosition());
        cursor.moveToPosition(0);
        assertEquals(0, cursor.getPosition());
        assertFalse(cursor.moveToPosition(1));

        cursor.close();
        // We've closed the cached cursor; make sure
        assertTrue(cachedCursor.isClosed());
        // The underlying cursor shouldn't be closed because it's in a cache (we'll test
        // that in testContentCache)
        assertFalse(activeCursor.isClosed());
        // Our cursor should no longer be in the active cursors map
        assertFalse(ContentCache.sActiveCursors.contains(activeCursor));

        // TODO - change the code or the test to enforce the assertion that a cached cursor
        // should have only zero or one rows.  We cannot test this in the constructor, however,
        // due to potential for deadlock.
//        // Make sure that we won't accept cursors with multiple rows
//        cursor = resolver.query(Mailbox.CONTENT_URI, Mailbox.CONTENT_PROJECTION, null, null, null);
//        try {
//            cursor = new CachedCursor(cursor, null, "Foo");
//            fail("Mustn't accept cursor with more than one row");
//        } catch (IllegalArgumentException e) {
//            // Correct
//        }
    }

    private static final String[] SIMPLE_PROJECTION = new String[] {"Foo"};
    private static final Object[] SIMPLE_ROW = new Object[] {"Bar"};
    private Cursor getOneRowCursor() {
        MatrixCursor cursor = new MatrixCursorWithCachedColumns(SIMPLE_PROJECTION, 1);
        cursor.addRow(SIMPLE_ROW);
        return cursor;
    }

    public void testContentCacheRemoveEldestEntry() {
        // Create a cache of size 2
        ContentCache cache = new ContentCache("Name", SIMPLE_PROJECTION, 2);
        // Random cursor; what's in it doesn't matter
        Cursor cursor1 = getOneRowCursor();
        // Get a token for arbitrary object named "1"
        CacheToken token = cache.getCacheToken("1");
        // Put the cursor in the cache
        cache.putCursor(cursor1, "1", SIMPLE_PROJECTION, token);
        assertEquals(1, cache.size());

        // Add another random cursor; what's in it doesn't matter
        Cursor cursor2 = getOneRowCursor();
        // Get a token for arbitrary object named "2"
        token = cache.getCacheToken("2");
        // Put the cursor in the cache
        cache.putCursor(cursor1, "2", SIMPLE_PROJECTION, token);
        assertEquals(2, cache.size());

        // We should be able to find both now in the cache
        Cursor cachedCursor = cache.getCachedCursor("1", SIMPLE_PROJECTION);
        assertNotNull(cachedCursor);
        assertTrue(cachedCursor instanceof CachedCursor);
        cachedCursor = cache.getCachedCursor("2", SIMPLE_PROJECTION);
        assertNotNull(cachedCursor);
        assertTrue(cachedCursor instanceof CachedCursor);

        // Both cursors should be open
        assertFalse(cursor1.isClosed());
        assertFalse(cursor2.isClosed());

        // Add another random cursor; what's in it doesn't matter
        Cursor cursor3 = getOneRowCursor();
        // Get a token for arbitrary object named "3"
        token = cache.getCacheToken("3");
        // Put the cursor in the cache
        cache.putCursor(cursor1, "3", SIMPLE_PROJECTION, token);
        // We should never have more than 2 entries in the cache
        assertEquals(2, cache.size());

        // The first cursor we added should no longer be in the cache (it's the eldest)
        cachedCursor = cache.getCachedCursor("1", SIMPLE_PROJECTION);
        assertNull(cachedCursor);
        // The cursors for 2 and 3 should be cached
        cachedCursor = cache.getCachedCursor("2", SIMPLE_PROJECTION);
        assertNotNull(cachedCursor);
        assertTrue(cachedCursor instanceof CachedCursor);
        cachedCursor = cache.getCachedCursor("3", SIMPLE_PROJECTION);
        assertNotNull(cachedCursor);
        assertTrue(cachedCursor instanceof CachedCursor);

        // Even cursor1 should be open, since all cached cursors are in mActiveCursors until closed
        assertFalse(cursor1.isClosed());
        assertFalse(cursor2.isClosed());
        assertFalse(cursor3.isClosed());
    }

    public void testCloseCachedCursor() {
        // Create a cache of size 2
        ContentCache cache = new ContentCache("Name", SIMPLE_PROJECTION, 2);
        // Random cursor; what's in it doesn't matter
        Cursor underlyingCursor = getOneRowCursor();
        Cursor cachedCursor1 = new CachedCursor(underlyingCursor, cache, "1");
        Cursor cachedCursor2 = new CachedCursor(underlyingCursor, cache, "1");
        assertEquals(2, ContentCache.sActiveCursors.getCount(underlyingCursor));
        cachedCursor1.close();
        assertTrue(cachedCursor1.isClosed());
        // Underlying cursor should be open (still one cached cursor open)
        assertFalse(underlyingCursor.isClosed());
        cachedCursor2.close();
        assertTrue(cachedCursor2.isClosed());
        assertEquals(0, ContentCache.sActiveCursors.getCount(underlyingCursor));
        // Underlying cursor should be closed (no cached cursors open)
        assertTrue(underlyingCursor.isClosed());

        underlyingCursor = getOneRowCursor();
        cachedCursor1 = cache.putCursor(
                underlyingCursor, "2", SIMPLE_PROJECTION, cache.getCacheToken("2"));
        cachedCursor2 = new CachedCursor(underlyingCursor, cache, "2");
        assertEquals(2, ContentCache.sActiveCursors.getCount(underlyingCursor));
        cachedCursor1.close();
        cachedCursor2.close();
        assertEquals(0, ContentCache.sActiveCursors.getCount(underlyingCursor));
        // Underlying cursor should still be open; it's in the cache
        assertFalse(underlyingCursor.isClosed());
        // Cache a new cursor
        cachedCursor2 = new CachedCursor(underlyingCursor, cache, "2");
        assertEquals(1, ContentCache.sActiveCursors.getCount(underlyingCursor));
        // Remove "2" from the cache and close the cursor
        cache.invalidate();
        cachedCursor2.close();
        // The underlying cursor should now be closed (not in the cache and no cached cursors)
        assertEquals(0, ContentCache.sActiveCursors.getCount(underlyingCursor));
        assertTrue(underlyingCursor.isClosed());
    }
}
