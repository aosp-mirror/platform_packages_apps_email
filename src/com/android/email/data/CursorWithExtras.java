/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.database.Cursor;
import android.database.CursorWrapper;
import android.os.Bundle;

/**
 * A special cursor that contains additional data.
 *
 * <p>TODO Use this instead of EmailWidgetLoader$CursorWithCounts.
 */
public class CursorWithExtras extends CursorWrapper {
    /** The number of children in a mailbox. If set, must be a positive value. */
    public final static String EXTRA_MAILBOX_CHILD_COUNT = "mailboxChildCount";
    /** The ID of the next, immediate parent mailbox */
    public final static String EXTRA_MAILBOX_PARENT_ID = "mailboxParentId";
    /** The ID of the next mailbox in the hierarchy with at least one child */
    public final static String EXTRA_MAILBOX_NEXT_PARENT_ID = "mailboxNextParentId";

    private final Bundle mExtras;

    public CursorWithExtras(Cursor cursor, Bundle extras) {
        super(cursor);
        mExtras = extras;
    }

    public int getInt(String key) {
        return getInt(key, 0);
    }

    public int getInt(String key, int defaultValue) {
        return mExtras == null ? defaultValue : mExtras.getInt(key);
    }

    public long getLong(String key) {
        return getLong(key, 0L);
    }

    public long getLong(String key, long defaultValue) {
        return mExtras == null ? defaultValue : mExtras.getLong(key);
    }
}
