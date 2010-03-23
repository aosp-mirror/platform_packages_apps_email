/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.email.activity;

import com.android.email.Email;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailContent.MessageColumns;

import android.content.Context;
import android.content.Intent;
import android.database.CursorIndexOutOfBoundsException;
import android.database.AbstractCursor;
import android.database.SQLException;
import android.os.Bundle;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.widget.CursorAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Various instrumentation tests for MessageList.
 *
 * It might be possible to convert these to ActivityUnitTest, which would be faster.
 */
@LargeTest
public class MessageListUnitTests
        extends ActivityInstrumentationTestCase2<MessageList> {

    private static final String EXTRA_ACCOUNT_ID = "com.android.email.activity._ACCOUNT_ID";
    private static final String EXTRA_MAILBOX_TYPE = "com.android.email.activity.MAILBOX_TYPE";
    private static final String EXTRA_MAILBOX_ID = "com.android.email.activity.MAILBOX_ID";
    private static final String STATE_CHECKED_ITEMS =
        "com.android.email.activity.MessageList.checkedItems";
    private Context mContext;
    private MessageList mMessageList;
    private CursorAdapter mListAdapter;
    private HashMap<Long, Map<String, Object>> mRowsMap;
    private ArrayList<Long> mIDarray;

    public MessageListUnitTests() {
        super(MessageList.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mContext = getInstrumentation().getTargetContext();
        Email.setServicesEnabled(mContext);

        Intent i = new Intent()
            .putExtra(EXTRA_ACCOUNT_ID, Long.MIN_VALUE)
            .putExtra(EXTRA_MAILBOX_TYPE, Long.MIN_VALUE)
            .putExtra(EXTRA_MAILBOX_ID, Long.MIN_VALUE);
        this.setActivityIntent(i);
        mMessageList = getActivity();
    }

    /**
     * Add a dummy message to the data map
     */
    private void addElement(long id, long mailboxKey, long accountKey, String displayName,
            String subject, long timestamp, int flagRead, int flagFavorite, int flagAttachment,
            int flags) {
        HashMap<String, Object> emap = new HashMap<String, Object>();
        emap.put(EmailContent.RECORD_ID, id);
        emap.put(MessageColumns.MAILBOX_KEY, mailboxKey);
        emap.put(MessageColumns.ACCOUNT_KEY, accountKey);
        emap.put(MessageColumns.DISPLAY_NAME, displayName);
        emap.put(MessageColumns.SUBJECT, subject);
        emap.put(MessageColumns.TIMESTAMP, timestamp);
        emap.put(MessageColumns.FLAG_READ, flagRead);
        emap.put(MessageColumns.FLAG_FAVORITE, flagFavorite);
        emap.put(MessageColumns.FLAG_ATTACHMENT, flagAttachment);
        emap.put(MessageColumns.FLAGS, flags);
        mRowsMap.put(id, emap);
        mIDarray.add(id);
    }

    /**
     * Create dummy messages
     */
    private void setUpCustomCursor() throws Throwable {
        runTestOnUiThread(new Runnable() {
            public void run() {
                mListAdapter = (CursorAdapter)mMessageList.getListAdapter();
                mRowsMap = new HashMap<Long, Map<String, Object>>(0);
                mIDarray = new ArrayList<Long>(0);
                final int FIMI = Message.FLAG_INCOMING_MEETING_INVITE;
                addElement(0, Long.MIN_VALUE, Long.MIN_VALUE, "a", "A", 0, 0, 0, 0, 0);
                addElement(1, Long.MIN_VALUE, Long.MIN_VALUE, "b", "B", 0, 0, 0, 0, 0);
                addElement(2, Long.MIN_VALUE, Long.MIN_VALUE, "c", "C", 0, 0, 0, 0, 0);
                addElement(3, Long.MIN_VALUE, Long.MIN_VALUE, "d", "D", 0, 0, 0, 0, FIMI);
                addElement(4, Long.MIN_VALUE, Long.MIN_VALUE, "e", "E", 0, 0, 0, 0, 0);
                addElement(5, Long.MIN_VALUE, Long.MIN_VALUE, "f", "F", 0, 0, 0, 0, 0);
                addElement(6, Long.MIN_VALUE, Long.MIN_VALUE, "g", "G", 0, 0, 0, 0, 0);
                addElement(7, Long.MIN_VALUE, Long.MIN_VALUE, "h", "H", 0, 0, 0, 0, 0);
                addElement(8, Long.MIN_VALUE, Long.MIN_VALUE, "i", "I", 0, 0, 0, 0, 0);
                addElement(9, Long.MIN_VALUE, Long.MIN_VALUE, "j", "J", 0, 0, 0, 0, 0);
                CustomCursor cc = new CustomCursor(mIDarray, MessageList.MESSAGE_PROJECTION,
                        mRowsMap);
                mListAdapter.changeCursor(cc);
            }
        });
    }

    public void testRestoreAndSaveInstanceState() throws Throwable {
        setUpCustomCursor();
        Bundle bundle = new Bundle();
        mMessageList.onSaveInstanceState(bundle);
        long[] checkedarray = bundle.getLongArray(STATE_CHECKED_ITEMS);
        assertEquals(0, checkedarray.length);
        Set<Long> checkedset = ((MessageList.MessageListAdapter)mListAdapter).getSelectedSet();
        checkedset.add(1L);
        checkedset.add(3L);
        checkedset.add(5L);
        mMessageList.onSaveInstanceState(bundle);
        checkedarray = bundle.getLongArray(STATE_CHECKED_ITEMS);
        java.util.Arrays.sort(checkedarray);
        assertEquals(3, checkedarray.length);
        assertEquals(1, checkedarray[0]);
        assertEquals(3, checkedarray[1]);
        assertEquals(5, checkedarray[2]);
    }

    public void testRestoreInstanceState() throws Throwable {
        setUpCustomCursor();
        Bundle bundle = new Bundle();
        long[] checkedarray = new long[3];
        checkedarray[0] = 1;
        checkedarray[1] = 3;
        checkedarray[2] = 5;
        Set<Long> checkedset = ((MessageList.MessageListAdapter)mListAdapter).getSelectedSet();
        assertEquals(0, checkedset.size());
        bundle.putLongArray(STATE_CHECKED_ITEMS, checkedarray);
        mMessageList.onRestoreInstanceState(bundle);
        checkedset = ((MessageList.MessageListAdapter)mListAdapter).getSelectedSet();
        assertEquals(3, checkedset.size());
        assertTrue(checkedset.contains(1L));
        assertTrue(checkedset.contains(3L));
        assertTrue(checkedset.contains(5L));
    }

    /**
     * Mock Cursor for MessageList
     */
    static class CustomCursor extends AbstractCursor {
        private final ArrayList<Long> mSortedIdList;
        private final String[] mColumnNames;

        public CustomCursor(ArrayList<Long> sortedIdList,
                String[] columnNames,
                HashMap<Long, Map<String, Object>> rows) {
            mSortedIdList = sortedIdList;
            mColumnNames = columnNames;
            mUpdatedRows = rows;
        }

        @Override
        public void close() {
            super.close();
        }

        @Override
        public String[] getColumnNames() {
            return mColumnNames;
        }

        private Object getObject(int columnIndex) {
            if (isClosed()) {
                throw new SQLException("Already closed.");
            }
            int size = mSortedIdList.size();
            if (mPos < 0 || mPos >= size) {
                throw new CursorIndexOutOfBoundsException(mPos, size);
            }
            if (columnIndex < 0 || columnIndex >= getColumnCount()) {
                return null;
            }
            return mUpdatedRows.get(mSortedIdList.get(mPos)).get(mColumnNames[columnIndex]);
        }

        @Override
        public float getFloat(int columnIndex) {
            return Float.valueOf(getObject(columnIndex).toString());
        }

        @Override
        public double getDouble(int columnIndex) {
            return Double.valueOf(getObject(columnIndex).toString());
        }

        @Override
        public int getInt(int columnIndex) {
            return Integer.valueOf(getObject(columnIndex).toString());
        }

        @Override
        public long getLong(int columnIndex) {
            return Long.valueOf(getObject(columnIndex).toString());
        }

        @Override
        public short getShort(int columnIndex) {
            return Short.valueOf(getObject(columnIndex).toString());
        }

        @Override
        public String getString(int columnIndex) {
            return String.valueOf(getObject(columnIndex));
        }

        @Override
        public boolean isNull(int columnIndex) {
            return getObject(columnIndex) == null;
        }

        @Override
        public int getCount() {
            return mSortedIdList.size();
        }
    }
}

