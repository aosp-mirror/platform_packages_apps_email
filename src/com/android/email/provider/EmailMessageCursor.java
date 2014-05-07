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
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.ParcelFileDescriptor.AutoCloseOutputStream;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.SparseArray;

import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.BodyColumns;
import com.android.mail.utils.LogUtils;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
 *
 * The fromUiQuery param indicates that this EmailMessageCursor object was created from uiQuery().
 * This is significant because we know that the body content fields will be retrieved within
 * the same process as the provider so we can proceed w/o having to worry about any cross
 * process marshalling issues.  Secondly, if the request is made from a uiQuery, the _id column
 * of the cursor will be a Message._id. If this call is made outside if the uiQuery(), than the
 * _id column is actually Body._id so we need to proceed accordingly.
 */
public class EmailMessageCursor extends CursorWrapper {

    private static final BlockingQueue<Runnable> sPoolWorkQueue =
            new LinkedBlockingQueue<Runnable>(128);

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        public Thread newThread(Runnable r) {
            return new Thread(r, "EmailMessageCursor #" + mCount.getAndIncrement());
        }
    };

    /**
     * An {@link Executor} that executes tasks which feed text and html email bodies into streams.
     *
     * It is important that this Executor is private to this class since we don't want to risk
     * sharing a common Executor with Threads that *read* from the stream. If that were to happen
     * it is possible for all Threads in the Executor to be blocked reads and thus starvation
     * occurs.
     */
    private static final Executor THREAD_POOL_EXECUTOR
            = new ThreadPoolExecutor(1, 5, 1, TimeUnit.SECONDS, sPoolWorkQueue, sThreadFactory);

    private final SparseArray<String> mTextParts;
    private final SparseArray<String> mHtmlParts;
    private final int mTextColumnIndex;
    private final int mHtmlColumnIndex;
    private final boolean mFromUiQuery;

    public EmailMessageCursor(final Cursor cursor, final SQLiteDatabase db, final String htmlColumn,
            final String textColumn, final boolean fromUiQuery) {
        super(cursor);
        mFromUiQuery = fromUiQuery;
        mHtmlColumnIndex = cursor.getColumnIndex(htmlColumn);
        mTextColumnIndex = cursor.getColumnIndex(textColumn);
        final int cursorSize = cursor.getCount();
        mHtmlParts = new SparseArray<String>(cursorSize);
        mTextParts = new SparseArray<String>(cursorSize);

        final String rowIdColumn;
        if (fromUiQuery) {
            // In the UI query, the _id column is the id in the message table so it is
            // messageKey in the Body table.
            rowIdColumn = BodyColumns.MESSAGE_KEY;
        } else {
            // In the non-UI query, the _id column is the id in the Body table.
            rowIdColumn = BaseColumns._ID;
        }

        final SQLiteStatement htmlSql = db.compileStatement(
                "SELECT " + BodyColumns.HTML_CONTENT +
                        " FROM " + Body.TABLE_NAME +
                        " WHERE " + rowIdColumn + "=?"
        );

        final SQLiteStatement textSql = db.compileStatement(
                "SELECT " + BodyColumns.TEXT_CONTENT +
                        " FROM " + Body.TABLE_NAME +
                        " WHERE " + rowIdColumn + "=?"
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
        if (mFromUiQuery) {
            if (columnIndex == mHtmlColumnIndex) {
                return mHtmlParts.get(getPosition());
            } else if (columnIndex == mTextColumnIndex) {
                return mTextParts.get(getPosition());
            }
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

    private static ParcelFileDescriptor createPipeAndFillAsync(final String contents) {
        try {
            final ParcelFileDescriptor descriptors[] = ParcelFileDescriptor.createPipe();
            final ParcelFileDescriptor readDescriptor = descriptors[0];
            final ParcelFileDescriptor writeDescriptor = descriptors[1];
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    final AutoCloseOutputStream outStream =
                            new AutoCloseOutputStream(writeDescriptor);
                    try {
                        outStream.write(contents.getBytes("utf8"));
                    } catch (final IOException e) {
                        LogUtils.e(LogUtils.TAG, e, "IOException while writing to body pipe");
                    } finally {
                        try {
                            outStream.close();
                        } catch (final IOException e) {
                            LogUtils.e(LogUtils.TAG, e, "IOException while closing body pipe");
                        }
                    }
                    return null;
                }
            }.executeOnExecutor(THREAD_POOL_EXECUTOR);
            return readDescriptor;
        } catch (final IOException e) {
            LogUtils.e(LogUtils.TAG, e, "IOException while creating body pipe");
            return null;
        }
    }

    @Override
    public Bundle respond(Bundle extras) {
        final int htmlRow = extras.getInt(Body.RESPOND_COMMAND_GET_HTML_PIPE, -1);
        final int textRow = extras.getInt(Body.RESPOND_COMMAND_GET_TEXT_PIPE, -1);

        final Bundle b = new Bundle(2);

        if (htmlRow >= 0 && !TextUtils.isEmpty(mHtmlParts.get(htmlRow))) {
            b.putParcelable(Body.RESPOND_RESULT_HTML_PIPE_KEY,
                    createPipeAndFillAsync(mHtmlParts.get(htmlRow)));
        }
        if (textRow >= 0 && !TextUtils.isEmpty(mTextParts.get(textRow))) {
            b.putParcelable(Body.RESPOND_RESULT_TEXT_PIPE_KEY,
                    createPipeAndFillAsync(mTextParts.get(textRow)));
        }
        return b;
    }
}
