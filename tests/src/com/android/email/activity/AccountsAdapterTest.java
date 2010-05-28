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

package com.android.email.activity;

import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.database.MatrixCursor.RowBuilder;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Basic unit tests of AccountsAdapter
 */
@SmallTest
public class AccountsAdapterTest extends AndroidTestCase {

    private Cursor mUpperCursor = null;
    private Cursor mLowerCursor = null;

    /**
     * Make an empty set of magic mailboxes in mUpperCursor
     */
    private void setupUpperCursor() {
        MatrixCursor childCursor = new MatrixCursor(AccountsAdapter.MAILBOX_PROJECTION);
        mUpperCursor = childCursor;
    }

    /**
     * Make a simple set of magic mailboxes in mUpperCursor
     */
    private void populateUpperCursor() {
        MatrixCursor childCursor = (MatrixCursor) mUpperCursor;

        int count;
        RowBuilder row;
        // TYPE_INBOX
        count = 10;
        row = childCursor.newRow();
        row.add(Long.valueOf(Mailbox.QUERY_ALL_INBOXES));       // MAILBOX_COLUMN_ID = 0;
        row.add("Inbox");                                       // MAILBOX_DISPLAY_NAME
        row.add(null);                                          // MAILBOX_ACCOUNT_KEY = 2;
        row.add(Integer.valueOf(Mailbox.TYPE_INBOX));           // MAILBOX_TYPE = 3;
        row.add(Integer.valueOf(count));                        // MAILBOX_UNREAD_COUNT = 4;
        // TYPE_MAIL (FAVORITES)
        count = 20;
        row = childCursor.newRow();
        row.add(Long.valueOf(Mailbox.QUERY_ALL_FAVORITES));     // MAILBOX_COLUMN_ID = 0;
        row.add("Favorites");                                   // MAILBOX_DISPLAY_NAME
        row.add(null);                                          // MAILBOX_ACCOUNT_KEY = 2;
        row.add(Integer.valueOf(Mailbox.TYPE_MAIL));            // MAILBOX_TYPE = 3;
        row.add(Integer.valueOf(count));                        // MAILBOX_UNREAD_COUNT = 4;
        // TYPE_DRAFTS
        count = 30;
        row = childCursor.newRow();
        row.add(Long.valueOf(Mailbox.QUERY_ALL_DRAFTS));        // MAILBOX_COLUMN_ID = 0;
        row.add("Drafts");                                      // MAILBOX_DISPLAY_NAME
        row.add(null);                                          // MAILBOX_ACCOUNT_KEY = 2;
        row.add(Integer.valueOf(Mailbox.TYPE_DRAFTS));          // MAILBOX_TYPE = 3;
        row.add(Integer.valueOf(count));                        // MAILBOX_UNREAD_COUNT = 4;
        // TYPE_OUTBOX
        count = 40;
        row = childCursor.newRow();
        row.add(Long.valueOf(Mailbox.QUERY_ALL_OUTBOX));        // MAILBOX_COLUMN_ID = 0;
        row.add("Outbox");                                      // MAILBOX_DISPLAY_NAME
        row.add(null);                                          // MAILBOX_ACCOUNT_KEY = 2;
        row.add(Integer.valueOf(Mailbox.TYPE_OUTBOX));          // MAILBOX_TYPE = 3;
        row.add(Integer.valueOf(count));                        // MAILBOX_UNREAD_COUNT = 4;
    }

    /**
     * Make an empty set of accounts in mLowerCursor
     */
    private void setupLowerCursor() {
        MatrixCursor childCursor = new MatrixCursor(Account.CONTENT_PROJECTION);
        mLowerCursor = childCursor;
    }

    /**
     * Make a simple set of "accounts".
     * Note:  We don't fill in the entire width of the projection because the accounts adapter
     * only looks at a few of the columns anyway.
     */
    private void populateLowerCursor() {
        MatrixCursor childCursor = (MatrixCursor) mLowerCursor;

        RowBuilder row;
        // Account #1
        row = childCursor.newRow();
        row.add(Long.valueOf(1));                               // CONTENT_ID_COLUMN = 0;
        row.add("Account 1");                                   // CONTENT_DISPLAY_NAME_COLUMN = 1;
        row.add("account1@android.com");                        // CONTENT_EMAIL_ADDRESS_COLUMN = 2;
        // Account #2
        row = childCursor.newRow();
        row.add(Long.valueOf(2));                               // CONTENT_ID_COLUMN = 0;
        row.add("Account 2");                                   // CONTENT_DISPLAY_NAME_COLUMN = 1;
        row.add("account2@android.com");                        // CONTENT_EMAIL_ADDRESS_COLUMN = 2;
    }

    /**
     * Test: General handling of separator
     */
    public void testSeparator() {
        // Test with fully populated upper and lower sections
        setupUpperCursor();
        populateUpperCursor();
        setupLowerCursor();
        populateLowerCursor();
        AccountsAdapter adapter = AccountsAdapter.getInstance(mUpperCursor, mLowerCursor,
                getContext(), -1, null);
        checkAdapter("fully populated", adapter, mUpperCursor, mLowerCursor);

        // Test with empty upper and populated lower
        setupUpperCursor();
        setupLowerCursor();
        populateLowerCursor();
        adapter = AccountsAdapter.getInstance(mUpperCursor, mLowerCursor, getContext(), -1, null);
        checkAdapter("lower populated", adapter, mUpperCursor, mLowerCursor);

        // Test with both empty
        setupUpperCursor();
        setupLowerCursor();
        adapter = AccountsAdapter.getInstance(mUpperCursor, mLowerCursor, getContext(), -1, null);
        checkAdapter("both empty", adapter, mUpperCursor, mLowerCursor);
    }

    /**
     * Helper to check the various APIs related to the upper/lower separator
     */
    private void checkAdapter(String tag, AccountsAdapter adapter, Cursor upper, Cursor lower) {
        // Check total count
        int expectedCount = 0;
        if (upper != null) expectedCount += upper.getCount();
        if (lower != null) expectedCount += lower.getCount();
        // If one or more items are shown, the adapter inserts the separator as well
        if (expectedCount > 0) expectedCount++;
        assertEquals(tag, expectedCount, adapter.getCount());

        // Check separator-related APIs
        int separatorIndex = -1;
        if (upper != null) {
            separatorIndex = upper.getCount();
        }

        // Get the MergeCursor that the adapter created
        MergeCursor mc = (MergeCursor) adapter.getCursor();

        // Check APIs for the position above the separator index
        // This will be the last entry in the "upper" cursor
        if (separatorIndex > 0) {
            int checkIndex = separatorIndex - 1;
            assertTrue(tag, adapter.isMailbox(checkIndex));
            assertFalse(tag, adapter.isAccount(checkIndex));
            assertTrue(tag, adapter.isEnabled(checkIndex));
            Cursor c = (Cursor) adapter.getItem(checkIndex);
            assertEquals(tag, mc, c);
            assertEquals(tag, checkIndex, c.getPosition());
            upper.moveToLast();
            long id = upper.getLong(0);
            assertEquals(tag, id, adapter.getItemId(checkIndex));
        }

        // Check APIs for position at the separator index
        if (separatorIndex >= 0) {
            int checkIndex = separatorIndex;
            assertFalse(tag, adapter.isMailbox(checkIndex));
            assertFalse(tag, adapter.isAccount(checkIndex));
            assertFalse(tag, adapter.isEnabled(checkIndex));
            // getItem and getItemId should never be called because it should not be enabled
        }

        // Check APIs for the position below the separator index
        // This will be the first entry in the "lower" cursor
        if (lower != null && lower.getCount() > 0) {
            int checkIndex = separatorIndex + 1;
            assertFalse(tag, adapter.isMailbox(checkIndex));
            assertTrue(tag, adapter.isAccount(checkIndex));
            assertTrue(tag, adapter.isEnabled(checkIndex));
            Cursor c = (Cursor) adapter.getItem(checkIndex);
            assertEquals(tag, mc, c);
            assertEquals(tag, separatorIndex, c.getPosition());
            lower.moveToFirst();
            long id = lower.getLong(0);
            assertEquals(tag, id, adapter.getItemId(checkIndex));
        }
    }

    /**
     * Test: isOnDeletingAccountView
     */
    public void testDeletingAccount() {
        // Test with fully populated upper and lower sections
        setupUpperCursor();
        populateUpperCursor();
        setupLowerCursor();
        populateLowerCursor();
        AccountsAdapter adapter = AccountsAdapter.getInstance(mUpperCursor, mLowerCursor,
                getContext(), -1, null);

        // Check enabled state - all should be enabled except for separator
        for (int i = 0; i < adapter.getCount(); i++) {
            boolean expectEnabled = adapter.isMailbox(i) || adapter.isAccount(i);
            assertEquals(expectEnabled, adapter.isEnabled(i));
        }

        // "Delete" the first account
        adapter.addOnDeletingAccount(1);                    // account Id of 1st account
        int account1Position = mUpperCursor.getCount() + 1; // first entry after separator
        for (int i = 0; i < adapter.getCount(); i++) {
            boolean isNotSeparator = adapter.isMailbox(i) || adapter.isAccount(i);
            boolean expectEnabled = isNotSeparator && (i != account1Position);
            assertEquals(expectEnabled, adapter.isEnabled(i));
        }
    }

    /**
     * Test: callback(s)
     */
    public void testCallbacks() {
        // Test with fully populated upper and lower sections
        setupUpperCursor();
        populateUpperCursor();
        setupLowerCursor();
        populateLowerCursor();
        Callback cb = new Callback();
        AccountsAdapter adapter = AccountsAdapter.getInstance(mUpperCursor, mLowerCursor,
                getContext(), -1, cb);

        AccountFolderListItem itemView = new AccountFolderListItem(mContext);
        itemView.mAccountId = 1;
        adapter.onClickFolder(itemView);
        assertTrue(cb.called);
        assertEquals(1, cb.id);
    }

    private static class Callback implements AccountsAdapter.Callback {
        public boolean called = false;
        public long id = -1;

        public void onClickAccountFolders(long accountId) {
            called = true;
            id = accountId;
        }
    }
}
