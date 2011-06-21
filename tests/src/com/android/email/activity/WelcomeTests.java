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

package com.android.email.activity;

import com.android.email.DBTestHelper;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Account;

import android.content.Context;
import android.test.AndroidTestCase;

public class WelcomeTests extends AndroidTestCase {

    private Context mProviderContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mProviderContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(
                getContext());
    }

    public void testResolveAccountId() {
        final Context c = mProviderContext;
        final Account account1 = ProviderTestUtils.setupAccount("account-1", true, c);
        final long id1 = account1.mId;
        final Account account2 = ProviderTestUtils.setupAccount("account-2", true, c);
        final long id2 = account2.mId;
        final Account account3 = ProviderTestUtils.setupAccount("account-3", true, c);
        final long id3 = account3.mId;

        // Make sure the last one created (account 3) is the default.
        assertTrue(Account.getDefaultAccountId(c) == id3);

        // No account specified -- should return the default account.
        assertEquals(id3, Welcome.resolveAccountId(c, -1, null));

        // Invalid account id -- should return the default account.
        assertEquals(id3, Welcome.resolveAccountId(c, 12345, null));

        // Valid ID
        assertEquals(id1, Welcome.resolveAccountId(c, id1, null));

        // Invalid UUID -- should return the default account.
        assertEquals(id3, Welcome.resolveAccountId(c, -1, "xxx"));

        // Valid UUID
        assertEquals(id1, Welcome.resolveAccountId(c, -1, account1.mCompatibilityUuid));
    }
}
