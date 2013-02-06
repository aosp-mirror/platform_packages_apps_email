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

import com.android.email.DBTestHelper;
import com.android.email.FolderProperties;
import com.android.email.R;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;

import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.test.LoaderTestCase;

/**
 * Tests for {@link AccountSelectorAdapter.AccountsLoader}.
 *
 * TODO add more tests.
 */
public class AccountSelectorAdapterTest extends LoaderTestCase {
    private Context mProviderContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mProviderContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(mContext);
    }

    /**
     * - Confirm that AccountsLoader adds the combined view row, iif there is more than 1 account.
     * - Confirm that AccountsLoader doesn't add recent mailboxes.
     *
     * two-pane version.
     *
     * TODO add one-pane version
     */
    public void testCombinedViewRow_twoPane() {
        final Account a1 = ProviderTestUtils.setupAccount("a1", true, mProviderContext);
        {
            // Only 1 account -- no combined view row.
            Loader<Cursor> l = new AccountSelectorAdapter.AccountsLoader(mProviderContext, 0L,
                    0L, true);
            AccountSelectorAdapter.CursorWithExtras result =
                    (AccountSelectorAdapter.CursorWithExtras) getLoaderResultSynchronously(l);
            assertEquals(1, result.getAccountCount());
            assertEquals(2, result.getCount()); // +1 as the cursor has the header row
            assertEquals(0, result.getRecentMailboxCount()); // No recent on two-pane.
        }

        final Account a2 = ProviderTestUtils.setupAccount("a2", true, mProviderContext);
        {
            // 2 accounts -- with combined view row, so returns 3 account rows.
            Loader<Cursor> l = new AccountSelectorAdapter.AccountsLoader(mProviderContext, 0L,
                    0L, true);
            AccountSelectorAdapter.CursorWithExtras result =
                    (AccountSelectorAdapter.CursorWithExtras) getLoaderResultSynchronously(l);
            assertEquals(3, result.getAccountCount());
            assertEquals(4, result.getCount()); // +1 as the cursor has the header row
            assertEquals(0, result.getRecentMailboxCount()); // No recent on two-pane.
        }
    }

    private static AccountSelectorAdapter.CursorWithExtras createCursorWithExtras() {
        final MatrixCursor m = new MatrixCursorWithCachedColumns(new String[] {"column"});
        return new AccountSelectorAdapter.CursorWithExtras(m.getColumnNames(), m);
    }

    public void testCursorWithExtras_setAccountMailboxInfo() {
        final Context context = mProviderContext;
        final Account a1 = ProviderTestUtils.setupAccount("a1", true, context);
        final Account a2 = ProviderTestUtils.setupAccount("a2", true, context);
        final Mailbox m1 = ProviderTestUtils.setupMailbox("Inbox", a1.mId, true, context,
                Mailbox.TYPE_INBOX);
        final Mailbox m2 = ProviderTestUtils.setupMailbox("box2", a2.mId, true, context,
                Mailbox.TYPE_MAIL);
        addMessage(m1, true, false);
        addMessage(m2, false, false);
        addMessage(m2, false, false);
        addMessage(m2, true, true);

        // Account 1 - no mailbox
        AccountSelectorAdapter.CursorWithExtras c = createCursorWithExtras();
        c.setAccountMailboxInfo(context, a1.mId, Mailbox.NO_MAILBOX);

        assertTrue(c.accountExists());
        assertEquals(a1.mId, c.getAccountId());
        assertEquals("a1", c.getAccountDisplayName());
        assertEquals(Mailbox.NO_MAILBOX, c.getMailboxId());
        assertNull(c.getMailboxDisplayName());
        assertEquals(0, c.getMailboxMessageCount());

        // Account 1 - inbox
        c = createCursorWithExtras();
        c.setAccountMailboxInfo(context, a1.mId, m1.mId);

        assertTrue(c.accountExists());
        assertEquals(a1.mId, c.getAccountId());
        assertEquals("a1", c.getAccountDisplayName());
        assertEquals(m1.mId, c.getMailboxId());
        assertEquals("Inbox", c.getMailboxDisplayName());
        assertEquals(1, c.getMailboxMessageCount());

        // Account 2 - regular mailbox
        c = createCursorWithExtras();
        c.setAccountMailboxInfo(context, a2.mId, m2.mId);

        assertTrue(c.accountExists());
        assertEquals(a2.mId, c.getAccountId());
        assertEquals("a2", c.getAccountDisplayName());
        assertEquals(m2.mId, c.getMailboxId());
        assertEquals("box2", c.getMailboxDisplayName());
        assertEquals(2, c.getMailboxMessageCount());

        // combined - no mailbox
        c = createCursorWithExtras();
        c.setAccountMailboxInfo(context, Account.ACCOUNT_ID_COMBINED_VIEW, Mailbox.NO_MAILBOX);

        assertTrue(c.accountExists());
        assertEquals(Account.ACCOUNT_ID_COMBINED_VIEW, c.getAccountId());
        assertEquals(getContext().getString(R.string.mailbox_list_account_selector_combined_view),
                c.getAccountDisplayName());
        assertEquals(Mailbox.NO_MAILBOX, c.getMailboxId());
        assertNull(c.getMailboxDisplayName());
        assertEquals(0, c.getMailboxMessageCount());

        // combined - all inbox
        c = createCursorWithExtras();
        c.setAccountMailboxInfo(context, Account.ACCOUNT_ID_COMBINED_VIEW,
                Mailbox.QUERY_ALL_INBOXES);

        assertTrue(c.accountExists());
        assertEquals(Account.ACCOUNT_ID_COMBINED_VIEW, c.getAccountId());
        assertEquals(getContext().getString(R.string.mailbox_list_account_selector_combined_view),
                c.getAccountDisplayName());
        assertEquals(Mailbox.QUERY_ALL_INBOXES, c.getMailboxId());
        assertEquals(getContext().getString(R.string.account_folder_list_summary_inbox),
                c.getMailboxDisplayName());
        // (message count = 1, because account 2 doesn't have inbox)

// TODO For some reason getMailboxMessageCount returns 0 in tests.  Investigate it.
//        assertEquals(1, c.getMailboxMessageCount());

        // Account 1 - all starred
        // Special case; it happens when you open "starred" on a normal account's mailbox list
        // on two-pane.
        c = createCursorWithExtras();
        c.setAccountMailboxInfo(context, a1.mId, Mailbox.QUERY_ALL_FAVORITES);

        assertTrue(c.accountExists());
        assertEquals(a1.mId, c.getAccountId());
        assertEquals("a1", c.getAccountDisplayName());
        assertEquals(Mailbox.QUERY_ALL_FAVORITES, c.getMailboxId());
        assertEquals(getContext().getString(R.string.account_folder_list_summary_starred),
                c.getMailboxDisplayName());
//        assertEquals(2, c.getMailboxMessageCount());

        // Invalid id
        c = createCursorWithExtras();
        c.setAccountMailboxInfo(context, 123456, 1232456); // no such account / mailbox

        assertFalse(c.accountExists());
    }

    private void addMessage(Mailbox m, boolean starred, boolean read) {
        ProviderTestUtils.setupMessage("a", m.mAccountKey, m.mId, false, true, mProviderContext,
                starred, read);
    }
}
