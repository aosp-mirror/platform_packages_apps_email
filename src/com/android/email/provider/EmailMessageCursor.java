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

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.database.CursorWrapper;
import android.net.Uri;
import android.provider.BaseColumns;
import android.util.SparseArray;

import com.android.emailcommon.provider.EmailContent.Body;
import com.android.mail.utils.HtmlSanitizer;
import com.android.mail.utils.LogUtils;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;

/**
 * This class wraps a cursor for the purpose of bypassing the CursorWindow object for the
 * potentially over-sized body content fields. The CursorWindow has a hard limit of 2MB and so a
 * large email message can exceed that limit and cause the cursor to fail to load.
 *
 * To get around this, we load null values in those columns, and then in this wrapper we directly
 * load the content from the provider, skipping the cursor window.
 *
 * This will still potentially blow up if this cursor gets wrapped in a CrossProcessCursorWrapper
 * which uses a CursorWindow to shuffle results between processes. Since we're only using this for
 * passing a cursor back to UnifiedEmail this shouldn't be an issue.
 */
public class EmailMessageCursor extends CursorWrapper {

    private final SparseArray<String> mTextParts;
    private final SparseArray<String> mHtmlParts;
    private final int mTextColumnIndex;
    private final int mHtmlColumnIndex;

    public EmailMessageCursor(final Context c, final Cursor cursor, final String htmlColumn,
            final String textColumn) {
        super(cursor);
        mHtmlColumnIndex = cursor.getColumnIndex(htmlColumn);
        mTextColumnIndex = cursor.getColumnIndex(textColumn);
        final int cursorSize = cursor.getCount();
        mHtmlParts = new SparseArray<String>(cursorSize);
        mTextParts = new SparseArray<String>(cursorSize);

        final ContentResolver cr = c.getContentResolver();

        while (cursor.moveToNext()) {
            final int position = cursor.getPosition();
            final long messageId = cursor.getLong(cursor.getColumnIndex(BaseColumns._ID));
            try {
                if (mHtmlColumnIndex != -1) {
                    final Uri htmlUri = Body.getBodyHtmlUriForMessageWithId(messageId);
                    final InputStream in = cr.openInputStream(htmlUri);
                    final String underlyingHtmlString;
                    try {
                        underlyingHtmlString = IOUtils.toString(in);
                    } finally {
                        in.close();
                    }
                    final String sanitizedHtml = HtmlSanitizer.sanitizeHtml(underlyingHtmlString);
                    mHtmlParts.put(position, sanitizedHtml);
                }
            } catch (final IOException e) {
                LogUtils.v(LogUtils.TAG, e, "Did not find html body for message %d", messageId);
            }
            try {
                if (mTextColumnIndex != -1) {
                    final Uri textUri = Body.getBodyTextUriForMessageWithId(messageId);
                    final InputStream in = cr.openInputStream(textUri);
                    final String underlyingTextString;
                    try {
                        underlyingTextString = IOUtils.toString(in);
                    } finally {
                        in.close();
                    }
                    mTextParts.put(position, underlyingTextString);
                }
            } catch (final IOException e) {
                LogUtils.v(LogUtils.TAG, e, "Did not find text body for message %d", messageId);
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
