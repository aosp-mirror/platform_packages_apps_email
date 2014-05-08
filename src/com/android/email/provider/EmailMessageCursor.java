/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;
import android.provider.BaseColumns;
import android.util.SparseArray;

import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.BodyColumns;
import com.android.mail.utils.LogUtils;

/**
 * This class wraps a cursor for the purpose of bypassing the CursorWindow object for the
 * potentially over-sized body content fields. The CursorWindow has a hard limit of 2MB and so a
 * large email message can exceed that limit and cause the cursor to fail to load.
 *
 * To get around this, we load null values in those columns, and then in this wrapper we directly
 * load the content from the DB, skipping the cursor window.
 *
 * This will still potentially blow up if this cursor gets wrapped in a CrossProcessCursorWrapper
 * which uses a CursorWindow to shuffle results between processes. This is currently only done in
 * Exchange, and only for outgoing mail, so hopefully users never type more than 2MB of email on
 * their device.
 *
 * If we want to address that issue fully, we need to return the body through a
 * ParcelFileDescriptor or some other mechanism that doesn't involve passing the data through a
 * CursorWindow.
 */
public class EmailMessageCursor extends CursorWrapper {

    private final SparseArray<String> mTextParts;
    private final SparseArray<String> mHtmlParts;
    private final int mTextColumnIndex;
    private final int mHtmlColumnIndex;

    public EmailMessageCursor(final Cursor cursor, final SQLiteDatabase db, final String htmlColumn,
            final String textColumn) {
        super(cursor);
        mHtmlColumnIndex = cursor.getColumnIndex(htmlColumn);
        mTextColumnIndex = cursor.getColumnIndex(textColumn);
        final int cursorSize = cursor.getCount();
        mHtmlParts = new SparseArray<String>(cursorSize);
        mTextParts = new SparseArray<String>(cursorSize);

        // TODO: Load this from the provider instead of duplicating the loading code here
        final SQLiteStatement htmlSql = db.compileStatement(
                "SELECT " + BodyColumns.HTML_CONTENT +
                        " FROM " + Body.TABLE_NAME +
                        " WHERE " + BodyColumns.MESSAGE_KEY + "=?"
        );

        final SQLiteStatement textSql = db.compileStatement(
                "SELECT " + BodyColumns.TEXT_CONTENT +
                        " FROM " + Body.TABLE_NAME +
                        " WHERE " + BodyColumns.MESSAGE_KEY + "=?"
        );

        while (cursor.moveToNext()) {
            final int position = cursor.getPosition();
            final long rowId = cursor.getLong(cursor.getColumnIndex(BaseColumns._ID));
            htmlSql.bindLong(1, rowId);
            try {
                if (mHtmlColumnIndex != -1) {
                    final String underlyingHtmlString = htmlSql.simpleQueryForString();
                    mHtmlParts.put(position, underlyingHtmlString);
                }
            } catch (final SQLiteDoneException e) {
                LogUtils.d(LogUtils.TAG, e, "Done with the HTML column");
            }
            textSql.bindLong(1, rowId);
            try {
                if (mTextColumnIndex != -1) {
                    final String underlyingTextString = textSql.simpleQueryForString();
                    mTextParts.put(position, underlyingTextString);
                }
            } catch (final SQLiteDoneException e) {
                LogUtils.d(LogUtils.TAG, e, "Done with the text column");
            }
        }
        cursor.moveToPosition(-1);
    }

    @Override
    public String getString(final int columnIndex) {
        if (columnIndex == mHtmlColumnIndex) {
            return mHtmlParts.get(getPosition());
        } else if (columnIndex == mTextColumnIndex) {
            return mTextParts.get(getPosition());
        }
        return super.getString(columnIndex);
    }

    @Override
    public int getType(int columnIndex) {
        if (columnIndex == mHtmlColumnIndex || columnIndex == mTextColumnIndex) {
            // Need to force this, otherwise we might fall through to some other get*() method
            // instead of getString() if the underlying cursor has other ideas about this content
            return FIELD_TYPE_STRING;
        } else {
            return super.getType(columnIndex);
        }
    }
}
