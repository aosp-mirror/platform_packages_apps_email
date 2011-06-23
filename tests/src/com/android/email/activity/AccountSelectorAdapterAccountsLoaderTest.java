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
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Account;

import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.test.LoaderTestCase;

/**
 * Tests for {@link AccountSelectorAdapter.AccountsLoader}.
 *
 * TODO add more tests.
 */
public class AccountSelectorAdapterAccountsLoaderTest extends LoaderTestCase {
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
                    true);
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
                    true);
            AccountSelectorAdapter.CursorWithExtras result =
                    (AccountSelectorAdapter.CursorWithExtras) getLoaderResultSynchronously(l);
            assertEquals(3, result.getAccountCount());
            assertEquals(4, result.getCount()); // +1 as the cursor has the header row
            assertEquals(0, result.getRecentMailboxCount()); // No recent on two-pane.
        }
    }
}
