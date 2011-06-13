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
import android.content.Intent;
import android.test.AndroidTestCase;

public class MessageListTests extends AndroidTestCase  {

    private Context mMockContext;

    public MessageListTests() {
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        // ProviderTestCase2 can't be used.  It creates a mock context that doesn't support
        // some methods we need here, such as getPackageName.
        mMockContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(
                getContext());
    }

    public void testGetAccountFromIntent() {
        final Context c = mMockContext;
        final Account a1 = ProviderTestUtils.setupAccount("a1", true, c);
        final Account a2 = ProviderTestUtils.setupAccount("a2", true, c);

        assertEquals(a1.mId, MessageList.getAccountFromIntent(c,
                MessageList.createFroyoIntent(c, a1)));
        assertEquals(a2.mId, MessageList.getAccountFromIntent(c,
                MessageList.createFroyoIntent(c, a2)));

        // Mixed -- UUID in the URI doesn't match the account ID in extra.
        // It's a test for shortcuts for restored accounts.
        final Intent i = MessageList.createFroyoIntent(c, a2);
        i.putExtra(MessageList.EXTRA_ACCOUNT_ID, 12345);
        assertEquals(a2.mId, MessageList.getAccountFromIntent(c, i));

        // Invalid intent -- no extra, no URI.
        assertEquals(Account.NO_ACCOUNT, MessageList.getAccountFromIntent(c,
                new Intent(c, MessageList.class)));
    }
}
