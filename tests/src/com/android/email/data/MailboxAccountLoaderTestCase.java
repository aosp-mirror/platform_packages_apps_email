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
import com.android.email.provider.ContentCache;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.ProviderTestUtils;

import android.content.Context;
import android.test.LoaderTestCase;

public class MailboxAccountLoaderTestCase extends LoaderTestCase {
    // Isolted Context for providers.
    private Context mProviderContext;

    @Override
    protected void setUp() throws Exception {
        mProviderContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(getContext());
    }

    private long createAccount(boolean isEas) {
        Account acct = ProviderTestUtils.setupAccount("acct1", false, mProviderContext);
        String proto = isEas ? "eas" : "non-eas";
        acct.mHostAuthRecv = ProviderTestUtils.setupHostAuth(proto, "hostauth", -1, true,
                mProviderContext);
        acct.mHostAuthKeyRecv = acct.mHostAuthRecv.mId;
        acct.save(mProviderContext);
        return acct.mId;
    }

    private long createMailbox(long accountId, int type) {
        Mailbox box = ProviderTestUtils.setupMailbox("name", accountId, false, mProviderContext);
        box.mType = type;
        box.save(mProviderContext);
        return box.mId;
    }

    /**
     * Test for normal case.  (account, mailbox found)
     */
    public void testLoad() {
        final long accountId = createAccount(false);
        final long mailboxId = createMailbox(accountId, Mailbox.TYPE_MAIL);

        MailboxAccountLoader.Result result = getLoaderResultSynchronously(
                new MailboxAccountLoader(mProviderContext, mailboxId));
        assertTrue(result.mIsFound);
        assertEquals(accountId, result.mAccount.mId);
        assertEquals(mailboxId, result.mMailbox.mId);
        assertFalse(result.mIsEasAccount);
        assertTrue(result.mIsRefreshable);
    }

    /**
     * Load -- isEas = true
     */
    public void testLoadEas() {
        final long accountId = createAccount(true);
        final long mailboxId = createMailbox(accountId, Mailbox.TYPE_MAIL);

        MailboxAccountLoader.Result result = getLoaderResultSynchronously(
                new MailboxAccountLoader(mProviderContext, mailboxId));
        assertTrue(result.mIsFound);
        assertEquals(accountId, result.mAccount.mId);
        assertEquals(mailboxId, result.mMailbox.mId);
        assertTrue(result.mIsEasAccount);
        assertTrue(result.mIsRefreshable);
    }

    /**
     * Load -- drafts, not refreshable.
     */
    public void testLoadNotRefreshable() {
        final long accountId = createAccount(false);
        final long mailboxId = createMailbox(accountId, Mailbox.TYPE_DRAFTS);

        MailboxAccountLoader.Result result = getLoaderResultSynchronously(
                new MailboxAccountLoader(mProviderContext, mailboxId));
        assertTrue(result.mIsFound);
        assertEquals(accountId, result.mAccount.mId);
        assertEquals(mailboxId, result.mMailbox.mId);
        assertFalse(result.mIsEasAccount);
        assertFalse(result.mIsRefreshable);
    }

    /**
     * Mailbox not found.
     */
    public void testMailboxNotFound() {
        MailboxAccountLoader.Result result = getLoaderResultSynchronously(
                new MailboxAccountLoader(mProviderContext, 123));
        assertFalse(result.mIsFound);
        assertNull(result.mAccount);
        assertNull(result.mMailbox);
        assertFalse(result.mIsEasAccount);
        assertFalse(result.mIsRefreshable);
    }

    /**
     * Account not found.
     */
    public void testAccountNotFound() {
        final long mailboxId = createMailbox(1, Mailbox.TYPE_MAIL);

        MailboxAccountLoader.Result result = getLoaderResultSynchronously(
                new MailboxAccountLoader(mProviderContext, mailboxId));
        assertFalse(result.mIsFound);
        assertNull(result.mAccount);
        assertNull(result.mMailbox);
        assertFalse(result.mIsEasAccount);
        assertFalse(result.mIsRefreshable);
    }

    /**
     * Magic mailbox.  (always found)
     */
    public void testMagicMailbox() {
        MailboxAccountLoader.Result result = getLoaderResultSynchronously(
                new MailboxAccountLoader(mProviderContext, Mailbox.QUERY_ALL_INBOXES));
        assertTrue(result.mIsFound);
        assertNull(result.mAccount);
        assertNull(result.mMailbox);
        assertFalse(result.mIsEasAccount);
        assertFalse(result.mIsRefreshable);
    }
}
