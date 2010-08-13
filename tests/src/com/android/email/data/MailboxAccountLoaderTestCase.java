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

package com.android.email.data;

import com.android.email.DBTestHelper;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;

import android.content.Context;
import android.test.LoaderTestCase;

public class MailboxAccountLoaderTestCase extends LoaderTestCase {
    // Isolted Context for providers.
    private Context mProviderContext;

    @Override
    protected void setUp() throws Exception {
        mProviderContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(
                getContext(), EmailProvider.class);
    }

    private long createAccount() {
        Account acct = ProviderTestUtils.setupAccount("acct1", true, mProviderContext);
        return acct.mId;
    }

    private long createMailbox(long accountId) {
        Mailbox box = ProviderTestUtils.setupMailbox("name", accountId, true, mProviderContext);
        return box.mId;
    }

    /**
     * Test for {@link MailboxAccountLoader.Result#isFound()}
     */
    public void testIsFound() {
        MailboxAccountLoader.Result result = new MailboxAccountLoader.Result();
        assertFalse(result.isFound());

        result.mAccount = new Account();
        assertFalse(result.isFound());

        result.mMailbox = new Mailbox();
        assertTrue(result.isFound());

        result.mAccount = null;
        assertFalse(result.isFound());
    }

    /**
     * Test for normal case.  (account, mailbox found)
     */
    public void testLoad() {
        final long accountId = createAccount();
        final long mailboxId = createMailbox(accountId);

        MailboxAccountLoader.Result result = getLoaderResultSynchronously(
                new MailboxAccountLoader(mProviderContext, mailboxId));
        assertTrue(result.isFound());
        assertEquals(accountId, result.mAccount.mId);
        assertEquals(mailboxId, result.mMailbox.mId);
    }

    /**
     * Mailbox not found.
     */
    public void testMailboxNotFound() {
        MailboxAccountLoader.Result result = getLoaderResultSynchronously(
                new MailboxAccountLoader(mProviderContext, 123));
        assertFalse(result.isFound());
        assertNull(result.mAccount);
        assertNull(result.mMailbox);
    }

    /**
     * Account not found.
     */
    public void testAccountNotFound() {
        final long mailboxId = createMailbox(1);

        MailboxAccountLoader.Result result = getLoaderResultSynchronously(
                new MailboxAccountLoader(mProviderContext, mailboxId));
        assertFalse(result.isFound());
        assertNull(result.mAccount);
        assertNull(result.mMailbox);
    }
}
