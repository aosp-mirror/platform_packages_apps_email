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
import android.database.MatrixCursor;

import com.android.mail.utils.MatrixCursorWithCachedColumns;

/**
 * {@link MatrixCursor} which takes an extra {@link Cursor} to the constructor, and close
 * it when self is closed.
 */
public class ClosingMatrixCursor extends MatrixCursorWithCachedColumns {
    private final Cursor mInnerCursor;

    public ClosingMatrixCursor(String[] columnNames, Cursor innerCursor) {
        super(columnNames);
        mInnerCursor = innerCursor;
    }

    @Override
    public void close() {
        if (mInnerCursor != null) {
            mInnerCursor.close();
        }
        super.close();
    }
}
