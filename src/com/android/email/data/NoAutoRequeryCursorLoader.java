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

package com.android.email.data;

import android.content.Context;
import android.content.CursorLoader;
import android.net.Uri;

/**
 * Same as {@link CursorLoader} but it doesn't do auto-requery when it gets content-changed
 * notifications.
 */
public class NoAutoRequeryCursorLoader extends CursorLoader {
    public NoAutoRequeryCursorLoader(Context context, Uri uri, String[] projection,
            String selection, String[] selectionArgs, String sortOrder) {
        super(context, uri, projection, selection, selectionArgs, sortOrder);
    }

    @Override
    public void onContentChanged() {
        // Don't reload.
    }
}
