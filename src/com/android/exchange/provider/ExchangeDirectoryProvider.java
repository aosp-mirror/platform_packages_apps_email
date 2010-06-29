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

import com.android.email.provider.EmailContent.Account;
import com.android.exchange.EasSyncService;
import com.android.exchange.SyncManager;
import com.android.exchange.provider.GalResult.GalData;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.RawContacts;

/**
 * ExchangeDirectoryProvider provides real-time data from the Exchange server; at the moment, it is
 * used solely to provide GAL (Global Address Lookup) service to email address adapters
 */
public class ExchangeDirectoryProvider extends ContentProvider {
    public static final String EXCHANGE_GAL_AUTHORITY = "com.android.exchange.directory.provider";

    private static final int GAL_BASE = 0;
    private static final int GAL_FILTER = GAL_BASE;

    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        sURIMatcher.addURI(EXCHANGE_GAL_AUTHORITY, "contacts/filter/*", GAL_FILTER);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        String accountName = uri.getQueryParameter(RawContacts.ACCOUNT_NAME);
        if (accountName == null) {
            return null;
        }

        Account account = SyncManager.getAccountByName(accountName);
        if (account == null) {
            return null;
        }

        int match = sURIMatcher.match(uri);
        switch (match) {
            case GAL_FILTER:
                String filter = uri.getLastPathSegment();
                // We should have at least two characters before doing a GAL search
                if (filter == null || filter.length() < 2) {
                    return null;
                }
                long callingId = Binder.clearCallingIdentity();
                try {
                    // Get results from the Exchange account
                    GalResult galResult = EasSyncService.searchGal(getContext(), account.mId,
                            filter);
                    if (galResult != null) {
                        return buildGalResultCursor(projection, galResult);
                    }
                } finally {
                    Binder.restoreCallingIdentity(callingId);
                }
                break;
        }

        return null;
    }

    /*package*/ Cursor buildGalResultCursor(String[] projection, GalResult galResult) {
        int displayNameIndex = -1;
        int emailIndex = -1;
        boolean alternateDisplayName = false;

        for (int i = 0; i < projection.length; i++) {
            String column = projection[i];
            if (Contacts.DISPLAY_NAME.equals(column) ||
                    Contacts.DISPLAY_NAME_PRIMARY.equals(column)) {
                displayNameIndex = i;
            } else if (Contacts.DISPLAY_NAME_ALTERNATIVE.equals(column)) {
                displayNameIndex = i;
                alternateDisplayName = true;

            } else if (CommonDataKinds.Email.ADDRESS.equals(column)) {
                emailIndex = i;
            }
            // TODO other fields
        }

        Object[] row = new Object[projection.length];

        /*
         * ContactsProvider will ensure that every request has a non-null projection.
         */
        MatrixCursor cursor = new MatrixCursor(projection);
        int count = galResult.galData.size();
        for (int i = 0; i < count; i++) {
            GalData galDataRow = galResult.galData.get(i);
            if (displayNameIndex != -1) {
                row[displayNameIndex] = galDataRow.displayName;
                // TODO Handle alternate display name here
            }
            if (emailIndex != -1) {
                row[emailIndex] = galDataRow.emailAddress;
            }
            cursor.addRow(row);
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        int match = sURIMatcher.match(uri);
        switch (match) {
            case GAL_FILTER:
                return Contacts.CONTENT_ITEM_TYPE;
        }
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
