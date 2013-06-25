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

import android.content.ContentValues;
import android.database.CrossProcessCursor;
import android.database.Cursor;
import android.database.CursorWindow;
import android.database.CursorWrapper;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.LruCache;

import com.android.email2.ui.MailActivityEmail;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.MatrixCursorWithCachedColumns;
import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * An LRU cache for EmailContent (Account, HostAuth, Mailbox, and Message, thus far).  The intended
 * user of this cache is EmailProvider itself; caching is entirely transparent to users of the
 * provider.
 *
 * Usage examples; id is a String representation of a row id (_id), as it might be retrieved from
 * a uri via getPathSegment
 *
 * To create a cache:
 *    ContentCache cache = new ContentCache(name, projection, max);
 *
 * To (try to) get a cursor from a cache:
 *    Cursor cursor = cache.getCursor(id, projection);
 *
 * To read from a table and cache the resulting cursor:
 * 1. Get a CacheToken: CacheToken token = cache.getToken(id);
 * 2. Get a cursor from the database: Cursor cursor = db.query(....);
 * 3. Put the cursor in the cache: cache.putCursor(cursor, id, token);
 * Only cursors with the projection given in the definition of the cache can be cached
 *
 * To delete one or more rows or update multiple rows from a table that uses cached data:
 * 1. Lock the row in the cache: cache.lock(id);
 * 2. Delete/update the row(s): db.delete(...);
 * 3. Invalidate any other caches that might be affected by the delete/update:
 *      The entire cache: affectedCache.invalidate()*
 *      A specific row in a cache: affectedCache.invalidate(rowId)
 * 4. Unlock the row in the cache: cache.unlock(id);
 *
 * To update a single row from a table that uses cached data:
 * 1. Lock the row in the cache: cache.lock(id);
 * 2. Update the row: db.update(...);
 * 3. Unlock the row in the cache, passing in the new values: cache.unlock(id, values);
 *
 * Synchronization note: All of the public methods in ContentCache are synchronized (i.e. on the
 * cache itself) except for methods that are solely used for debugging and do not modify the cache.
 * All references to ContentCache that are external to the ContentCache class MUST synchronize on
 * the ContentCache instance (e.g. CachedCursor.close())
 */
public final class ContentCache {
    private static final boolean DEBUG_CACHE = false;  // DO NOT CHECK IN TRUE
    private static final boolean DEBUG_TOKENS = false;  // DO NOT CHECK IN TRUE
    private static final boolean DEBUG_NOT_CACHEABLE = false;  // DO NOT CHECK IN TRUE
    private static final boolean DEBUG_STATISTICS = false; // DO NOT CHECK THIS IN TRUE

    // If false, reads will not use the cache; this is intended for debugging only
    private static final boolean READ_CACHE_ENABLED = true;  // DO NOT CHECK IN FALSE

    // Count of non-cacheable queries (debug only)
    private static int sNotCacheable = 0;
    // A map of queries that aren't cacheable (debug only)
    private static final CounterMap<String> sNotCacheableMap = new CounterMap<String>();

    private final LruCache<String, Cursor> mLruCache;

    // All defined caches
    private static final ArrayList<ContentCache> sContentCaches = new ArrayList<ContentCache>();
    // A set of all unclosed, cached cursors; this will typically be a very small set, as cursors
    // tend to be closed quickly after use.  The value, for each cursor, is its reference count
    /*package*/ static final CounterMap<Cursor> sActiveCursors = new CounterMap<Cursor>(24);

    // A set of locked content id's
    private final CounterMap<String> mLockMap = new CounterMap<String>(4);
    // A set of active tokens
    /*package*/ TokenList mTokenList;

    // The name of the cache (used for logging)
    private final String mName;
    // The base projection (only queries in which all columns exist in this projection will be
    // able to avoid a cache miss)
    private final String[] mBaseProjection;
    // The tag used for logging
    private final String mLogTag;
    // Cache statistics
    private final Statistics mStats;
    /** If {@code true}, lock the cache for all writes */
    private static boolean sLockCache;

    /**
     * A synchronized reference counter for arbitrary objects
     */
    /*package*/ static class CounterMap<T> {
        private HashMap<T, Integer> mMap;

        /*package*/ CounterMap(int maxSize) {
            mMap = new HashMap<T, Integer>(maxSize);
        }

        /*package*/ CounterMap() {
            mMap = new HashMap<T, Integer>();
        }

        /*package*/ synchronized int subtract(T object) {
            Integer refCount = mMap.get(object);
            int newCount;
            if (refCount == null || refCount.intValue() == 0) {
                throw new IllegalStateException();
            }
            if (refCount > 1) {
                newCount = refCount - 1;
                mMap.put(object, newCount);
            } else {
                newCount = 0;
                mMap.remove(object);
            }
            return newCount;
        }

        /*package*/ synchronized void add(T object) {
            Integer refCount = mMap.get(object);
            if (refCount == null) {
                mMap.put(object, 1);
            } else {
                mMap.put(object, refCount + 1);
            }
        }

        /*package*/ synchronized boolean contains(T object) {
            return mMap.containsKey(object);
        }

        /*package*/ synchronized int getCount(T object) {
            Integer refCount = mMap.get(object);
            return (refCount == null) ? 0 : refCount.intValue();
        }

        synchronized int size() {
            return mMap.size();
        }

        /**
         * For Debugging Only - not efficient
         */
        synchronized Set<Map.Entry<T, Integer>> entrySet() {
            return mMap.entrySet();
        }
    }

    /**
     * A list of tokens that are in use at any moment; there can be more than one token for an id
     */
    /*package*/ static class TokenList extends ArrayList<CacheToken> {
        private static final long serialVersionUID = 1L;
        private final String mLogTag;

        /*package*/ TokenList(String name) {
            mLogTag = "TokenList-" + name;
        }

        /*package*/ int invalidateTokens(String id) {
            if (MailActivityEmail.DEBUG && DEBUG_TOKENS) {
                LogUtils.d(mLogTag, "============ Invalidate tokens for: " + id);
            }
            ArrayList<CacheToken> removeList = new ArrayList<CacheToken>();
            int count = 0;
            for (CacheToken token: this) {
                if (token.getId().equals(id)) {
                    token.invalidate();
                    removeList.add(token);
                    count++;
                }
            }
            for (CacheToken token: removeList) {
                remove(token);
            }
            return count;
        }

        /*package*/ void invalidate() {
            if (MailActivityEmail.DEBUG && DEBUG_TOKENS) {
                LogUtils.d(mLogTag, "============ List invalidated");
            }
            for (CacheToken token: this) {
                token.invalidate();
            }
            clear();
        }

        /*package*/ boolean remove(CacheToken token) {
            boolean result = super.remove(token);
            if (MailActivityEmail.DEBUG && DEBUG_TOKENS) {
                if (result) {
                    LogUtils.d(mLogTag, "============ Removing token for: " + token.mId);
                } else {
                    LogUtils.d(mLogTag, "============ No token found for: " + token.mId);
                }
            }
            return result;
        }

        public CacheToken add(String id) {
            CacheToken token = new CacheToken(id);
            super.add(token);
            if (MailActivityEmail.DEBUG && DEBUG_TOKENS) {
                LogUtils.d(mLogTag, "============ Taking token for: " + token.mId);
            }
            return token;
        }
    }

    /**
     * A CacheToken is an opaque object that must be passed into putCursor in order to attempt to
     * write into the cache.  The token becomes invalidated by any intervening write to the cached
     * record.
     */
    public static final class CacheToken {
        private final String mId;
        private boolean mIsValid = READ_CACHE_ENABLED;

        /*package*/ CacheToken(String id) {
            mId = id;
        }

        /*package*/ String getId() {
            return mId;
        }

        /*package*/ boolean isValid() {
            return mIsValid;
        }

        /*package*/ void invalidate() {
            mIsValid = false;
        }

        @Override
        public boolean equals(Object token) {
            return ((token instanceof CacheToken) && ((CacheToken)token).mId.equals(mId));
        }

        @Override
        public int hashCode() {
            return mId.hashCode();
        }
    }

    /**
     * The cached cursor is simply a CursorWrapper whose underlying cursor contains zero or one
     * rows.  We handle simple movement (moveToFirst(), moveToNext(), etc.), and override close()
     * to keep the underlying cursor alive (unless it's no longer cached due to an invalidation).
     * Multiple CachedCursor's can use the same underlying cursor, so we override the various
     * moveX methods such that each CachedCursor can have its own position information
     */
    public static final class CachedCursor extends CursorWrapper implements CrossProcessCursor {
        // The cursor we're wrapping
        private final Cursor mCursor;
        // The cache which generated this cursor
        private final ContentCache mCache;
        private final String mId;
        // The current position of the cursor (can only be 0 or 1)
        private int mPosition = -1;
        // The number of rows in this cursor (-1 = not determined)
        private int mCount = -1;
        private boolean isClosed = false;

        public CachedCursor(Cursor cursor, ContentCache cache, String id) {
            super(cursor);
            mCursor = cursor;
            mCache = cache;
            mId = id;
            // Add this to our set of active cursors
            sActiveCursors.add(cursor);
        }

        /**
         * Close this cursor; if the cursor's cache no longer contains the underlying cursor, and
         * there are no other users of that cursor, we'll close it here. In any event,
         * we'll remove the cursor from our set of active cursors.
         */
        @Override
        public void close() {
            synchronized(mCache) {
                int count = sActiveCursors.subtract(mCursor);
                if ((count == 0) && mCache.mLruCache.get(mId) != (mCursor)) {
                    super.close();
                }
            }
            isClosed = true;
        }

        @Override
        public boolean isClosed() {
            return isClosed;
        }

        @Override
        public int getCount() {
            if (mCount < 0) {
                mCount = super.getCount();
            }
            return mCount;
        }

        /**
         * We'll be happy to move to position 0 or -1
         */
        @Override
        public boolean moveToPosition(int pos) {
            if (pos >= getCount() || pos < -1) {
                return false;
            }
            mPosition = pos;
            return true;
        }

        @Override
        public boolean moveToFirst() {
            return moveToPosition(0);
        }

        @Override
        public boolean moveToNext() {
            return moveToPosition(mPosition + 1);
        }

        @Override
        public boolean moveToPrevious() {
            return moveToPosition(mPosition - 1);
        }

        @Override
        public int getPosition() {
            return mPosition;
        }

        @Override
        public final boolean move(int offset) {
            return moveToPosition(mPosition + offset);
        }

        @Override
        public final boolean moveToLast() {
            return moveToPosition(getCount() - 1);
        }

        @Override
        public final boolean isLast() {
            return mPosition == (getCount() - 1);
        }

        @Override
        public final boolean isBeforeFirst() {
            return mPosition == -1;
        }

        @Override
        public final boolean isAfterLast() {
            return mPosition == 1;
        }

        @Override
        public CursorWindow getWindow() {
           return ((CrossProcessCursor)mCursor).getWindow();
        }

        @Override
        public void fillWindow(int pos, CursorWindow window) {
            ((CrossProcessCursor)mCursor).fillWindow(pos, window);
        }

        @Override
        public boolean onMove(int oldPosition, int newPosition) {
            return true;
        }
    }

    /**
     * Public constructor
     * @param name the name of the cache (used for logging)
     * @param baseProjection the projection used for cached cursors; queries whose columns are not
     *  included in baseProjection will always generate a cache miss
     * @param maxSize the maximum number of content cursors to cache
     */
    public ContentCache(String name, String[] baseProjection, int maxSize) {
        mName = name;
        mLruCache = new LruCache<String, Cursor>(maxSize) {
            @Override
            protected void entryRemoved(
                    boolean evicted, String key, Cursor oldValue, Cursor newValue) {
                // Close this cursor if it's no longer being used
                if (evicted && !sActiveCursors.contains(oldValue)) {
                    oldValue.close();
                }
            }
        };
        mBaseProjection = baseProjection;
        mLogTag = "ContentCache-" + name;
        sContentCaches.add(this);
        mTokenList = new TokenList(mName);
        mStats = new Statistics(this);
    }

    /**
     * Return the base projection for cached rows
     * Get the projection used for cached rows (typically, the largest possible projection)
     * @return
     */
    public String[] getProjection() {
        return mBaseProjection;
    }


    /**
     * Get a CacheToken for a row as specified by its id (_id column)
     * @param id the id of the record
     * @return a CacheToken needed in order to write data for the record back to the cache
     */
    public synchronized CacheToken getCacheToken(String id) {
        // If another thread is already writing the data, return an invalid token
        CacheToken token = mTokenList.add(id);
        if (mLockMap.contains(id)) {
            token.invalidate();
        }
        return token;
    }

    public int size() {
        return mLruCache.size();
    }

    @VisibleForTesting
    Cursor get(String id) {
        return mLruCache.get(id);
    }

    protected Map<String, Cursor> getSnapshot() {
        return mLruCache.snapshot();
    }
    /**
     * Try to cache a cursor for the given id and projection; returns a valid cursor, either a
     * cached cursor (if caching was successful) or the original cursor
     *
     * @param c the cursor to be cached
     * @param id the record id (_id) of the content
     * @param projection the projection represented by the cursor
     * @return whether or not the cursor was cached
     */
    public Cursor putCursor(Cursor c, String id, String[] projection, CacheToken token) {
        // Make sure the underlying cursor is at the first row, and do this without synchronizing,
        // to prevent deadlock with a writing thread (which might, for example, be calling into
        // CachedCursor.invalidate)
        c.moveToPosition(0);
        return putCursorImpl(c, id, projection, token);
    }
    public synchronized Cursor putCursorImpl(Cursor c, String id, String[] projection,
            CacheToken token) {
        try {
            if (!token.isValid()) {
                if (MailActivityEmail.DEBUG && DEBUG_CACHE) {
                    LogUtils.d(mLogTag, "============ Stale token for " + id);
                }
                mStats.mStaleCount++;
                return c;
            }
            if (c != null && Arrays.equals(projection, mBaseProjection) && !sLockCache) {
                if (MailActivityEmail.DEBUG && DEBUG_CACHE) {
                    LogUtils.d(mLogTag, "============ Caching cursor for: " + id);
                }
                // If we've already cached this cursor, invalidate the older one
                Cursor existingCursor = get(id);
                if (existingCursor != null) {
                   unlockImpl(id, null, false);
                }
                mLruCache.put(id, c);
                return new CachedCursor(c, this, id);
            }
            return c;
        } finally {
            mTokenList.remove(token);
        }
    }

    /**
     * Find and, if found, return a cursor, based on cached values, for the supplied id
     * @param id the _id column of the desired row
     * @param projection the requested projection for a query
     * @return a cursor based on cached values, or null if the row is not cached
     */
    public synchronized Cursor getCachedCursor(String id, String[] projection) {
        if (MailActivityEmail.DEBUG && DEBUG_STATISTICS) {
            // Every 200 calls to getCursor, report cache statistics
            dumpOnCount(200);
        }
        if (projection == mBaseProjection) {
            return getCachedCursorImpl(id);
        } else {
            return getMatrixCursor(id, projection);
        }
    }

    private CachedCursor getCachedCursorImpl(String id) {
        Cursor c = get(id);
        if (c != null) {
            mStats.mHitCount++;
            return new CachedCursor(c, this, id);
        }
        mStats.mMissCount++;
        return null;
    }

    private MatrixCursor getMatrixCursor(String id, String[] projection) {
        return getMatrixCursor(id, projection, null);
    }

    private MatrixCursor getMatrixCursor(String id, String[] projection,
            ContentValues values) {
        Cursor c = get(id);
        if (c != null) {
            // Make a new MatrixCursor with the requested columns
            MatrixCursor mc = new MatrixCursorWithCachedColumns(projection, 1);
            if (c.getCount() == 0) {
                return mc;
            }
            Object[] row = new Object[projection.length];
            if (values != null) {
                // Make a copy; we don't want to change the original
                values = new ContentValues(values);
            }
            int i = 0;
            for (String column: projection) {
                int columnIndex = c.getColumnIndex(column);
                if (columnIndex < 0) {
                    mStats.mProjectionMissCount++;
                    return null;
                } else {
                    String value;
                    if (values != null && values.containsKey(column)) {
                        Object val = values.get(column);
                        if (val instanceof Boolean) {
                            value = (val == Boolean.TRUE) ? "1" : "0";
                        } else {
                            value = values.getAsString(column);
                        }
                        values.remove(column);
                    } else {
                        value = c.getString(columnIndex);
                    }
                    row[i++] = value;
                }
            }
            if (values != null && values.size() != 0) {
                return null;
            }
            mc.addRow(row);
            mStats.mHitCount++;
            return mc;
        }
        mStats.mMissCount++;
        return null;
    }

    /**
     * Lock a given row, such that no new valid CacheTokens can be created for the passed-in id.
     * @param id the id of the row to lock
     */
    public synchronized void lock(String id) {
        // Prevent new valid tokens from being created
        mLockMap.add(id);
        // Invalidate current tokens
        int count = mTokenList.invalidateTokens(id);
        if (MailActivityEmail.DEBUG && DEBUG_TOKENS) {
            LogUtils.d(mTokenList.mLogTag, "============ Lock invalidated " + count +
                    " tokens for: " + id);
        }
    }

    /**
     * Unlock a given row, allowing new valid CacheTokens to be created for the passed-in id.
     * @param id the id of the item whose cursor is cached
     */
    public synchronized void unlock(String id) {
        unlockImpl(id, null, true);
    }

    /**
     * If the row with id is currently cached, replaces the cached values with the supplied
     * ContentValues.  Then, unlock the row, so that new valid CacheTokens can be created.
     *
     * @param id the id of the item whose cursor is cached
     * @param values updated values for this row
     */
    public synchronized void unlock(String id, ContentValues values) {
        unlockImpl(id, values, true);
    }

    /**
     * If values are passed in, replaces any cached cursor with one containing new values, and
     * then closes the previously cached one (if any, and if not in use)
     * If values are not passed in, removes the row from cache
     * If the row was locked, unlock it
     * @param id the id of the row
     * @param values new ContentValues for the row (or null if row should simply be removed)
     * @param wasLocked whether or not the row was locked; if so, the lock will be removed
     */
    private void unlockImpl(String id, ContentValues values, boolean wasLocked) {
        Cursor c = get(id);
        if (c != null) {
            if (MailActivityEmail.DEBUG && DEBUG_CACHE) {
                LogUtils.d(mLogTag, "=========== Unlocking cache for: " + id);
            }
            if (values != null && !sLockCache) {
                MatrixCursor cursor = getMatrixCursor(id, mBaseProjection, values);
                if (cursor != null) {
                    if (MailActivityEmail.DEBUG && DEBUG_CACHE) {
                        LogUtils.d(mLogTag, "=========== Recaching with new values: " + id);
                    }
                    cursor.moveToFirst();
                    mLruCache.put(id, cursor);
                } else {
                    mLruCache.remove(id);
                }
            } else {
                mLruCache.remove(id);
            }
            // If there are no cursors using the old cached cursor, close it
            if (!sActiveCursors.contains(c)) {
                c.close();
            }
        }
        if (wasLocked) {
            mLockMap.subtract(id);
        }
    }

    /**
     * Invalidate the entire cache, without logging
     */
    public synchronized void invalidate() {
        invalidate(null, null, null);
    }

    /**
     * Invalidate the entire cache; the arguments are used for logging only, and indicate the
     * write operation that caused the invalidation
     *
     * @param operation a string describing the operation causing the invalidate (or null)
     * @param uri the uri causing the invalidate (or null)
     * @param selection the selection used with the uri (or null)
     */
    public synchronized void invalidate(String operation, Uri uri, String selection) {
        if (DEBUG_CACHE && (operation != null)) {
            LogUtils.d(mLogTag, "============ INVALIDATED BY " + operation + ": " + uri +
                    ", SELECTION: " + selection);
        }
        mStats.mInvalidateCount++;
        // Close all cached cursors that are no longer in use
        mLruCache.evictAll();
        // Invalidate all current tokens
        mTokenList.invalidate();
    }

    // Debugging code below

    private void dumpOnCount(int num) {
        mStats.mOpCount++;
        if ((mStats.mOpCount % num) == 0) {
            dumpStats();
        }
    }

    /*package*/ void recordQueryTime(Cursor c, long nanoTime) {
        if (c instanceof CachedCursor) {
            mStats.hitTimes += nanoTime;
            mStats.hits++;
        } else {
            if (c.getCount() == 1) {
                mStats.missTimes += nanoTime;
                mStats.miss++;
            }
        }
    }

    public static synchronized void notCacheable(Uri uri, String selection) {
        if (DEBUG_NOT_CACHEABLE) {
            sNotCacheable++;
            String str = uri.toString() + "$" + selection;
            sNotCacheableMap.add(str);
        }
    }

    // For use with unit tests
    public static void invalidateAllCaches() {
        for (ContentCache cache: sContentCaches) {
            cache.invalidate();
        }
    }

    /** Sets the cache lock. If the lock is {@code true}, also invalidates all cached items. */
    public static void setLockCacheForTest(boolean lock) {
        sLockCache = lock;
        if (sLockCache) {
            invalidateAllCaches();
        }
    }

    static class Statistics {
        private final ContentCache mCache;
        private final String mName;

        // Cache statistics
        // The item is in the cache AND is used to create a cursor
        private int mHitCount = 0;
        // Basic cache miss (the item is not cached)
        private int mMissCount = 0;
        // Incremented when a cachePut is invalid due to an intervening write
        private int mStaleCount = 0;
        // A projection miss occurs when the item is cached, but not all requested columns are
        // available in the base projection
        private int mProjectionMissCount = 0;
        // Incremented whenever the entire cache is invalidated
        private int mInvalidateCount = 0;
        // Count of operations put/get
        private int mOpCount = 0;
        // The following are for timing statistics
        private long hits = 0;
        private long hitTimes = 0;
        private long miss = 0;
        private long missTimes = 0;

        // Used in toString() and addCacheStatistics()
        private int mCursorCount = 0;
        private int mTokenCount = 0;

        Statistics(ContentCache cache) {
            mCache = cache;
            mName = mCache.mName;
        }

        Statistics(String name) {
            mCache = null;
            mName = name;
        }

        private void addCacheStatistics(ContentCache cache) {
            if (cache != null) {
                mHitCount += cache.mStats.mHitCount;
                mMissCount += cache.mStats.mMissCount;
                mProjectionMissCount += cache.mStats.mProjectionMissCount;
                mStaleCount += cache.mStats.mStaleCount;
                hitTimes += cache.mStats.hitTimes;
                missTimes += cache.mStats.missTimes;
                hits += cache.mStats.hits;
                miss += cache.mStats.miss;
                mCursorCount += cache.size();
                mTokenCount += cache.mTokenList.size();
            }
        }

        private static void append(StringBuilder sb, String name, Object value) {
            sb.append(", ");
            sb.append(name);
            sb.append(": ");
            sb.append(value);
        }

        @Override
        public String toString() {
            if (mHitCount + mMissCount == 0) return "No cache";
            int totalTries = mMissCount + mProjectionMissCount + mHitCount;
            StringBuilder sb = new StringBuilder();
            sb.append("Cache " + mName);
            append(sb, "Cursors", mCache == null ? mCursorCount : mCache.size());
            append(sb, "Hits", mHitCount);
            append(sb, "Misses", mMissCount + mProjectionMissCount);
            append(sb, "Inval", mInvalidateCount);
            append(sb, "Tokens", mCache == null ? mTokenCount : mCache.mTokenList.size());
            append(sb, "Hit%", mHitCount * 100 / totalTries);
            append(sb, "\nHit time", hitTimes / 1000000.0 / hits);
            append(sb, "Miss time", missTimes / 1000000.0 / miss);
            return sb.toString();
        }
    }

    public static void dumpStats() {
        Statistics totals = new Statistics("Totals");

        for (ContentCache cache: sContentCaches) {
            if (cache != null) {
                LogUtils.d(cache.mName, cache.mStats.toString());
                totals.addCacheStatistics(cache);
            }
        }
        LogUtils.d(totals.mName, totals.toString());
    }
}
