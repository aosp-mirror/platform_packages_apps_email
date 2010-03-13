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

package com.android.exchange.provider;

import com.android.exchange.EasSyncService;
import com.android.exchange.provider.GalResult.GalData;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

/**
 * ExchangeProvider provides real-time data from the Exchange server; at the moment, it is used
 * solely to provide GAL (Global Address Lookup) service to email address adapters
 */
public class ExchangeProvider extends ContentProvider {
    public static final String TAG = "ExchangeProvider";

    public static final String EXCHANGE_AUTHORITY = "com.android.exchange.provider";
    public static final Uri GAL_URI = Uri.parse("content://" + EXCHANGE_AUTHORITY + "/gal/");

    public static final long GAL_START_ID = 0x1000000L;

    private static final int GAL_BASE = 0;
    private static final int GAL_FILTER = GAL_BASE;
    public static final String[] GAL_PROJECTION = new String[] {"_id", "displayName", "data"};

    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);
    // We use the time stamp to suppress GAL results for queries that have been superceded by newer
    // ones (i.e. we only respond to the most recent query)
    public static long sQueryTimeStamp = 0;

    static {
        // Exchange URI matching table
        UriMatcher matcher = sURIMatcher;
        // The URI for GAL lookup contains three user-supplied parameters in the path:
        // 1) the account id of the Exchange account
        // 2) the constraint (filter) text
        // 3) a time stamp for the request
        matcher.addURI(EXCHANGE_AUTHORITY, "gal/*/*/*", GAL_FILTER);
    }

    private static void addGalDataRow(MatrixCursor mc, long id, String name, String address) {
        mc.newRow().add(id).add(name).add(address);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        int match = sURIMatcher.match(uri);
        switch (match) {
            case GAL_FILTER:
                long accountId = -1;
                // Pull out our parameters
                MatrixCursor c = new MatrixCursor(GAL_PROJECTION);
                String accountIdString = uri.getPathSegments().get(1);
                String filter = uri.getPathSegments().get(2);
                String time = uri.getPathSegments().get(3);
                // Make sure we get a valid time; otherwise throw an exception
                try {
                    accountId = Long.parseLong(accountIdString);
                    sQueryTimeStamp = Long.parseLong(time);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Illegal value in URI");
                }
                // Get results from the Exchange account
                long timeStamp = sQueryTimeStamp;
                GalResult galResult = EasSyncService.searchGal(getContext(), accountId, filter);
                // If we have a more recent query in process, ignore the result
                if (timeStamp != sQueryTimeStamp) {
                    Log.d(TAG, "Ignoring result from query: " + uri);
                    return null;
                } else if (galResult != null) {
                    // TODO: None of the UI row should be communicated here- use
                    // cursor metadata or other method.
                    int count = galResult.galData.size();
                    Log.d(TAG, "Query returned " + count + " result(s)");
                    String header = (count == 0) ? "No results" : (count == 1) ? "1 result" :
                        count + " results";
                    if (galResult.total != count) {
                        header += " (of " + galResult.total + ")";
                    }
                    addGalDataRow(c, GAL_START_ID, header, "");
                    int i = 1;
                    for (GalData data : galResult.galData) {
                        // TODO Don't get the constant from Email app...
                        addGalDataRow(c, data._id | GAL_START_ID + i, data.displayName,
                                data.emailAddress);
                        i++;
                    }
                }
                return c;
            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return -1;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/gal-entry";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public boolean onCreate() {
        return false;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return -1;
    }
}
