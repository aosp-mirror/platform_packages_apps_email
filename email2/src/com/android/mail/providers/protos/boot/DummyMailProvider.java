/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.mail.providers.protos.boot;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

import com.android.mail.providers.protos.boot.AccountReceiver;

/**
 * The sole purpose of this provider is to leverage on the fact that the system will create any
 * listed content providers when the app starts up.  We use this fact to initiate the load of the
 * Gmail accounts, to cache them in the AccountCacheProvider.
 *
 * Ideally, this wouldn't be necessary, and instead the code that registers the Gmail accounts could
 * use static initialization to kick off the process to cache the accounts.  Unfortunately
 * at that time, there is not a valid context
 */
public final class DummyMailProvider extends ContentProvider {

    @Override
    public boolean onCreate() {

        final Intent intent = new Intent(AccountReceiver.ACTION_PROVIDER_CREATED);
        getContext().sendBroadcast(intent);

        // TODO(pwestbro): consider putting the retrieval of the account list here. This would
        // allow us to handle queries for the account uris
        return true;
    }

    @Override
    public Cursor query(Uri url, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        throw new IllegalArgumentException("Don't Use this provider");
    }

    @Override
    public Uri insert(Uri url, ContentValues values) {
        throw new IllegalArgumentException("Don't Use this provider");
    }

    @Override
    public int update(Uri url, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new IllegalArgumentException("Don't Use this provider");
    }

    @Override
    public int delete(Uri url, String selection, String[] selectionArgs) {
        throw new IllegalArgumentException("Don't Use this provider");
    }

    @Override
    public String getType(Uri uri) {
        throw new IllegalArgumentException("Don't Use this provider");
    }
}
