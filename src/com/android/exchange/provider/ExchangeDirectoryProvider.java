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

import com.android.email.R;
import com.android.email.VendorPolicyLoader;
import com.android.email.mail.PackedString;
import com.android.email.provider.EmailContent.Account;
import com.android.exchange.EasSyncService;
import com.android.exchange.SyncManager;
import com.android.exchange.provider.GalResult.GalData;

import android.accounts.AccountManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Contacts.Data;
import android.provider.ContactsContract.Directory;
import android.provider.ContactsContract.RawContacts;
import android.text.TextUtils;

import java.util.HashMap;
import java.util.List;

/**
 * ExchangeDirectoryProvider provides real-time data from the Exchange server; at the moment, it is
 * used solely to provide GAL (Global Address Lookup) service to email address adapters
 */
public class ExchangeDirectoryProvider extends ContentProvider {
    public static final String EXCHANGE_GAL_AUTHORITY = "com.android.exchange.directory.provider";

    private static final int DEFAULT_CONTACT_ID = 1;

    private static final int GAL_BASE = 0;
    private static final int GAL_DIRECTORIES = GAL_BASE;
    private static final int GAL_FILTER = GAL_BASE + 1;
    private static final int GAL_CONTACT = GAL_BASE + 2;
    private static final int GAL_CONTACT_WITH_ID = GAL_BASE + 3;

    private static final UriMatcher sURIMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    static {
        sURIMatcher.addURI(EXCHANGE_GAL_AUTHORITY, "directories", GAL_DIRECTORIES);
        sURIMatcher.addURI(EXCHANGE_GAL_AUTHORITY, "contacts/filter/*", GAL_FILTER);
        sURIMatcher.addURI(EXCHANGE_GAL_AUTHORITY, "contacts/lookup/*/entities", GAL_CONTACT);
        sURIMatcher.addURI(EXCHANGE_GAL_AUTHORITY, "contacts/lookup/*/#/entities",
                GAL_CONTACT_WITH_ID);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    static class GalProjection {
        final int size;
        final HashMap<String, Integer> columnMap = new HashMap<String, Integer>();

        GalProjection(String[] projection) {
            size = projection.length;
            for (int i = 0; i < projection.length; i++) {
                columnMap.put(projection[i], i);
            }
        }
    }

    static class GalContactRow {
        private final GalProjection mProjection;
        private Object[] row;
        static long dataId = 1;

        GalContactRow(GalProjection projection, long contactId, String lookupKey,
                String accountName, String displayName) {
            this.mProjection = projection;
            row = new Object[projection.size];

            put(Contacts.Entity.CONTACT_ID, contactId);

            // We only have one raw contact per aggregate, so they can have the same ID
            put(Contacts.Entity.RAW_CONTACT_ID, contactId);
            put(Contacts.Entity.DATA_ID, dataId++);

            put(Contacts.DISPLAY_NAME, displayName);

            // TODO alternative display name
            put(Contacts.DISPLAY_NAME_ALTERNATIVE, displayName);

            put(RawContacts.ACCOUNT_TYPE, com.android.email.Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
            put(RawContacts.ACCOUNT_NAME, accountName);
            put(RawContacts.RAW_CONTACT_IS_READ_ONLY, 1);
            put(Data.IS_READ_ONLY, 1);
        }

        Object[] getRow () {
            return row;
        }

        void put(String columnName, Object value) {
            Integer integer = mProjection.columnMap.get(columnName);
            if (integer != null) {
                row[integer] = value;
            } else {
                System.out.println("Unsupported column: " + columnName);
            }
        }

        static void addEmailAddress(MatrixCursor cursor, GalProjection galProjection,
                long contactId, String lookupKey, String accountName, String displayName,
                String address) {
            if (!TextUtils.isEmpty(address)) {
                GalContactRow r = new GalContactRow(
                        galProjection, contactId, lookupKey, accountName, displayName);
                r.put(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE);
                r.put(Email.TYPE, Email.TYPE_WORK);
                r.put(Email.ADDRESS, address);
                cursor.addRow(r.getRow());
            }
        }

        static void addPhoneRow(MatrixCursor cursor, GalProjection projection, long contactId,
                String lookupKey, String accountName, String displayName, int type, String number) {
            if (!TextUtils.isEmpty(number)) {
                GalContactRow r = new GalContactRow(
                        projection, contactId, lookupKey, accountName, displayName);
                r.put(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
                r.put(Phone.TYPE, type);
                r.put(Phone.NUMBER, number);
                cursor.addRow(r.getRow());
            }
        }

        public static void addNameRow(MatrixCursor cursor, GalProjection galProjection,
                long contactId, String lookupKey, String accountName, String displayName,
                String firstName, String lastName) {
            GalContactRow r = new GalContactRow(
                    galProjection, contactId, lookupKey, accountName, displayName);
            r.put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
            r.put(StructuredName.GIVEN_NAME, firstName);
            r.put(StructuredName.FAMILY_NAME, lastName);
            r.put(StructuredName.DISPLAY_NAME, displayName);
            cursor.addRow(r.getRow());
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        int match = sURIMatcher.match(uri);
        MatrixCursor cursor;
        Object[] row;
        PackedString ps;
        String lookupKey;

        switch (match) {
            case GAL_DIRECTORIES: {
                // Assuming that GAL can be used with all exchange accounts
                android.accounts.Account[] accounts = AccountManager.get(getContext())
                        .getAccountsByType(com.android.email.Email.EXCHANGE_ACCOUNT_MANAGER_TYPE);
                cursor = new MatrixCursor(projection);
                if (accounts != null) {
                    for (android.accounts.Account account : accounts) {
                        row = new Object[projection.length];

                        for (int i = 0; i < projection.length; i++) {
                            String column = projection[i];
                            if (column.equals(Directory.ACCOUNT_NAME)) {
                                row[i] = account.name;
                            } else if (column.equals(Directory.ACCOUNT_TYPE)) {
                                row[i] = account.type;
                            } else if (column.equals(Directory.TYPE_RESOURCE_ID)) {
                                if (VendorPolicyLoader.getInstance(getContext())
                                        .useAlternateExchangeStrings()) {
                                    row[i] = R.string.exchange_name_alternate;
                                } else {
                                    row[i] = R.string.exchange_name;
                                }
                            } else if (column.equals(Directory.DISPLAY_NAME)) {
                                row[i] = account.name;
                            } else if (column.equals(Directory.EXPORT_SUPPORT)) {
                                row[i] = Directory.EXPORT_SUPPORT_SAME_ACCOUNT_ONLY;
                            } else if (column.equals(Directory.SHORTCUT_SUPPORT)) {
                                row[i] = Directory.SHORTCUT_SUPPORT_FULL;
                            }
                        }
                        cursor.addRow(row);
                    }
                }
                return cursor;
            }

            case GAL_FILTER: {
                String filter = uri.getLastPathSegment();
                // We should have at least two characters before doing a GAL search
                if (filter == null || filter.length() < 2) {
                    return null;
                }

                String accountName = uri.getQueryParameter(RawContacts.ACCOUNT_NAME);
                if (accountName == null) {
                    return null;
                }

                Account account = SyncManager.getAccountByName(accountName);
                if (account == null) {
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

            case GAL_CONTACT:
            case GAL_CONTACT_WITH_ID: {
                String accountName = uri.getQueryParameter(RawContacts.ACCOUNT_NAME);
                if (accountName == null) {
                    return null;
                }

                GalProjection galProjection = new GalProjection(projection);
                cursor = new MatrixCursor(projection);
                // Handle the decomposition of the key into rows suitable for CP2
                List<String> pathSegments = uri.getPathSegments();
                lookupKey = pathSegments.get(2);
                long contactId = (match == GAL_CONTACT_WITH_ID)
                        ? Long.parseLong(pathSegments.get(3))
                        : DEFAULT_CONTACT_ID;
                ps = new PackedString(lookupKey);
                String displayName = ps.get(GalData.DISPLAY_NAME);
                GalContactRow.addEmailAddress(cursor, galProjection, contactId, lookupKey,
                        accountName, displayName, ps.get(GalData.EMAIL_ADDRESS));
                GalContactRow.addPhoneRow(cursor, galProjection, contactId, accountName,
                        displayName, displayName, Phone.TYPE_HOME, ps.get(GalData.HOME_PHONE));
                GalContactRow.addPhoneRow(cursor, galProjection, contactId, accountName,
                        displayName, displayName, Phone.TYPE_WORK, ps.get(GalData.WORK_PHONE));
                GalContactRow.addPhoneRow(cursor, galProjection, contactId, accountName,
                        displayName, displayName, Phone.TYPE_MOBILE, ps.get(GalData.MOBILE_PHONE));
                GalContactRow.addNameRow(cursor, galProjection, contactId, accountName, displayName,
                        ps.get(GalData.FIRST_NAME), ps.get(GalData.LAST_NAME), displayName);
                return cursor;
            }
        }

        return null;
    }

    /*package*/ Cursor buildGalResultCursor(String[] projection, GalResult galResult) {
        int displayNameIndex = -1;
        int alternateDisplayNameIndex = -1;;
        int emailIndex = -1;
        int idIndex = -1;
        int lookupIndex = -1;

        for (int i = 0; i < projection.length; i++) {
            String column = projection[i];
            if (Contacts.DISPLAY_NAME.equals(column) ||
                    Contacts.DISPLAY_NAME_PRIMARY.equals(column)) {
                displayNameIndex = i;
            } else if (Contacts.DISPLAY_NAME_ALTERNATIVE.equals(column)) {
                alternateDisplayNameIndex = i;
            } else if (CommonDataKinds.Email.ADDRESS.equals(column)) {
                emailIndex = i;
            } else if (Contacts._ID.equals(column)) {
                idIndex = i;
            } else if (Contacts.LOOKUP_KEY.equals(column)) {
                lookupIndex = i;
            }
        }

        Object[] row = new Object[projection.length];

        /*
         * ContactsProvider will ensure that every request has a non-null projection.
         */
        MatrixCursor cursor = new MatrixCursor(projection);
        int count = galResult.galData.size();
        for (int i = 0; i < count; i++) {
            GalData galDataRow = galResult.galData.get(i);
            String firstName = galDataRow.get(GalData.FIRST_NAME);
            String lastName = galDataRow.get(GalData.LAST_NAME);
            String displayName = galDataRow.get(GalData.DISPLAY_NAME);
            // If we don't have a display name, try to create one using first and last name
            if (displayName == null) {
                if (firstName != null && lastName != null) {
                    displayName = firstName + " " + lastName;
                } else if (firstName != null) {
                    displayName = firstName;
                } else if (lastName != null) {
                    displayName = lastName;
                }
            }
            galDataRow.put(GalData.DISPLAY_NAME, displayName);

            if (displayNameIndex != -1) {
                row[displayNameIndex] = displayName;
            }
            if (alternateDisplayNameIndex != -1) {
                // Try to create an alternate display name, using first and last name
                // TODO: Check with Contacts team to make sure we're using this properly
                if (firstName != null && lastName != null) {
                    row[alternateDisplayNameIndex] = lastName + " " + firstName;
                } else {
                    row[alternateDisplayNameIndex] = displayName;
                }
            }
            if (emailIndex != -1) {
                row[emailIndex] = galDataRow.get(GalData.EMAIL_ADDRESS);
            }
            if (idIndex != -1) {
                row[idIndex] = i + 1;  // Let's be 1 based
            }
            if (lookupIndex != -1) {
                // We use the packed string as our lookup key; it contains ALL of the gal data
                // We do this because we are not able to provide a stable id to ContactsProvider
                row[lookupIndex] = Uri.encode(galDataRow.toPackedString());
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
