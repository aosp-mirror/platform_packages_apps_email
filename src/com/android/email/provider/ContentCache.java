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

import com.android.email.Email;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
 */
public final class ContentCache extends LinkedHashMap<String, Cursor> {
    private static final long serialVersionUID = 1L;

    private static final boolean DEBUG_CACHE = false;  // DO NOT CHECK IN TRUE
    private static final boolean DEBUG_TOKENS = false;  // DO NOT CHECK IN TRUE
    private static final boolean DEBUG_NOT_CACHEABLE = false;  // DO NOT CHECK IN TRUE

    // If false, reads will not use the cache; this is intended for debugging only
    private static final boolean READ_CACHE_ENABLED = true;  // DO NOT CHECK IN FALSE

    // Count of non-cacheable queries (debug only)
    private static int sNotCacheable = 0;
    // A map of queries that aren't cacheable (debug only)
    private static final CounterMap<String> sNotCacheableMap = new CounterMap<String>();

    // All defined caches
    private static final ArrayList<ContentCache> sContentCaches = new ArrayList<ContentCache>();
    // A set of all unclosed, cached cursors; this will typically be a very small set, as cursors
    // tend to be closed quickly after use.  The value, for each cursor, is its reference count
    /*package*/ static CounterMap<Cursor> sActiveCursors;

    // A set of locked content id's
    private final CounterMap<String> mLockMap = new CounterMap<String>(4);
    // A set of active tokens
    /*package*/ TokenList mTokenList;

    // The name of the cache (used for logging)
    private final String mName;
    // The base projection (only queries in which all columns exist in this projection will be
    // able to avoid a cache miss)
    private final String[] mBaseProjection;
    // The number of items (cursors) to cache
    private final int mMaxSize;
    // The tag used for logging
    private final String mLogTag;
    // Cache statistics
    private final Statistics mStats;

    /**
     * A synchronized reference counter for arbitrary objects
     */
    /*package*/ static class CounterMap<T> {
        private static final long serialVersionUID = 1L;
        private HashMap<T, Integer> mMap;

        /*package*/ CounterMap(int maxSize) {
            mMap = new HashMap<T, Integer>(maxSize);
        }

        /*package*/ CounterMap() {
            mMap = new HashMap<T, Integer>();
        }

        /*package*/ synchronized void subtract(T object) {
            Integer refCount = mMap.get(object);
            if (refCount == null || refCount.intValue() == 0) {
                throw new IllegalStateException();
            }
            if (refCount > 1) {
                mMap.put(object, refCount - 1);
            } else {
                mMap.remove(object);
            }
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
        synchronized Set<HashMap.Entry<T, Integer>> entrySet() {
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
            if (DEBUG_TOKENS) {
                Log.d(mLogTag, "============ Invalidate tokens for: " + id);
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
            if (DEBUG_TOKENS) {
                Log.d(mLogTag, "============ List invalidated");
            }
            for (CacheToken token: this) {
                token.invalidate();
            }
            clear();
        }

        /*package*/ boolean remove(CacheToken token) {
            boolean result = super.remove(token);
            if (DEBUG_TOKENS) {
                if (result) {
                    Log.d(mLogTag, "============ Removing token for: " + token.mId);
                } else {
                    Log.d(mLogTag, "============ No token found for: " + token.mId);
                }
            }
            return result;
        }

        public CacheToken add(String id) {
            CacheToken token = new CacheToken(id);
            super.add(token);
            if (DEBUG_TOKENS) {
                Log.d(mLogTag, "============ Taking token for: " + token.mId);
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
        private static final long serialVersionUID = 1L;
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
     * to keep the underlying cursor alive (unless it's no longer cached due to an invalidation)
     */
    public static final class CachedCursor extends CursorWrapper {
        // The cursor we're wrapping
        private final Cursor mCursor;
        // The cache which generated this cursor
        private final ContentCache mCache;
        // The current position of the cursor (can only be 0 or 1)
        private int mPosition = -1;
        // The number of rows in this cursor (-1 = not determined)
        private int mCount = -1;
        private boolean isClosed = false;

        public CachedCursor(Cursor cursor, ContentCache cache, String name) {
            super(cursor);
            // The underlying cursor must always be at position 0
            cursor.moveToPosition(0);
            mCursor = cursor;
            mCache = cache;
            // Add this to our set of active cursors
            sActiveCursors.add(cursor);
        }

        /**
         * Close this cursor; if the cursor's cache no longer contains the cursor, we'll close the
         * underlying cursor.  In any event we'll remove the cursor from our set of active cursors
         */
        @Override
        public void close() {
            if (!mCache.containsValue(mCursor)) {
                super.close();
            }
            sActiveCursors.subtract(mCursor);
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
         * We'll be happy to move to position 0 or -1; others are illegal
         */
        @Override
        public boolean moveToPosition(int pos) {
            if (pos > 0) {
                throw new IllegalArgumentException();
            }
            if (pos >= getCount()) {
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
            if (mPosition == 0) {
                mPosition--;
                return true;
            }
            return false;
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
    }

    /**
     * Public constructor
     * @param name the name of the cache (used for logging)
     * @param baseProjection the projection used for cached cursors; queries whose columns are not
     *  included in baseProjection will always generate a cache miss
     * @param maxSize the maximum number of content cursors to cache
     */
    public ContentCache(String name, String[] baseProjection, int maxSize) {
        super();
        mName = name;
        mMaxSize = maxSize;
        mBaseProjection = baseProjection;
        mLogTag = "ContentCache-" + name;
        sContentCaches.add(this);
        mTokenList = new TokenList(mName);
        sActiveCursors = new CounterMap<Cursor>(maxSize);
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

    /* (non-Javadoc)
     * @see java.util.LinkedHashMap#removeEldestEntry(java.util.Map.Entry)
     */
    @Override
    public synchronized boolean removeEldestEntry(Map.Entry<String,Cursor> entry) {
        // If we're above the maximum size for this cache, remove the LRU cache entry
        if (size() > mMaxSize) {
            Cursor cursor = entry.getValue();
            // Close this cursor if it's no longer being used
            if (!sActiveCursors.contains(cursor)) {
                cursor.close();
            }
            return true;
        }
        return false;
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
    public synchronized Cursor putCursor(Cursor c, String id, String[] projection,
            CacheToken token) {
        try {
            if (!token.isValid()) {
                if (DEBUG_CACHE) {
                    Log.d(mLogTag, "============ Stale token for " + id);
                }
                mStats.mStaleCount++;
                return c;
            }
            if (c != null && projection == mBaseProjection) {
                if (DEBUG_CACHE) {
                    Log.d(mLogTag, "============ Caching cursor for: " + id);
                }
                // If we've already cached this cursor, invalidate the older one
                Cursor existingCursor = get(id);
                if (existingCursor != null) {
                   unlockImpl(id, null, false);
                }
                put(id, c);
                c.moveToFirst();
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
        if (Email.DEBUG) {
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
            MatrixCursor mc = new MatrixCursor(projection, 1);
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
        if (DEBUG_TOKENS) {
            Log.d(mTokenList.mLogTag, "============ Lock invalidated " + count +
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
    public void unlockImpl(String id, ContentValues values, boolean wasLocked) {
        Cursor c = get(id);
        if (c != null) {
            if (DEBUG_CACHE) {
                Log.d(mLogTag, "=========== Unlocking cache for: " + id);
            }
            if (values != null) {
                MatrixCursor cursor = getMatrixCursor(id, mBaseProjection, values);
                if (cursor != null) {
                    if (DEBUG_CACHE) {
                        Log.d(mLogTag, "=========== Recaching with new values: " + id);
                    }
                    cursor.moveToFirst();
                    put(id, cursor);
                } else {
                    remove(id);
                }
            } else {
                remove(id);
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
            Log.d(mLogTag, "============ INVALIDATED BY " + operation + ": " + uri +
                    ", SELECTION: " + selection);
        }
        mStats.mInvalidateCount++;
        // Close all cached cursors that are no longer in use
        for (String id: keySet()) {
            Cursor c = get(id);
            if (!sActiveCursors.contains(c)) {
                c.close();
            }
        }
        clear();
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

    private static class CacheCounter implements Comparable<Object> {
        String uri;
        Integer count;

        CacheCounter(String _uri, Integer _count) {
            uri = _uri;
            count = _count;
        }

        @Override
        public int compareTo(Object another) {
            CacheCounter x = (CacheCounter)another;
            return x.count > count ? 1 : x.count == count ? 0 : -1;
        }
    }

    public static void dumpNotCacheableQueries() {
        int size = sNotCacheableMap.size();
        CacheCounter[] array = new CacheCounter[size];

        int i = 0;
        for (Entry<String, Integer> entry: sNotCacheableMap.entrySet()) {
            array[i++] = new CacheCounter(entry.getKey(), entry.getValue());
        }
        Arrays.sort(array);
        for (CacheCounter cc: array) {
            Log.d("NotCacheable", cc.count + ": " + cc.uri);
        }
    }

    // For use with unit tests
    public static void invalidateAllCachesForTest() {
        for (ContentCache cache: sContentCaches) {
            cache.invalidate();
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

        private void append(StringBuilder sb, String name, Object value) {
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
                Log.d(cache.mName, cache.mStats.toString());
                totals.addCacheStatistics(cache);
            }
        }
        Log.d(totals.mName, totals.toString());
    }
}
