/*
 * Copyright (C) 2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

package com.android.exchange;

import com.android.email.AccountTestCase;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.exchange.ExchangeService.SyncError;

import android.content.Context;

import java.util.concurrent.ConcurrentHashMap;

/**
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.ExchangeServiceAccountTests email
 */
public class ExchangeServiceAccountTests extends AccountTestCase {

    EmailProvider mProvider;
    Context mMockContext;

    public ExchangeServiceAccountTests() {
        super();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
    }

    public void testReleaseSyncHolds() {
        Context context = mMockContext;
        ExchangeService exchangeService = new ExchangeService();
        SyncError securityErrorAccount1 =
            exchangeService.new SyncError(AbstractSyncService.EXIT_SECURITY_FAILURE, false);
        SyncError ioError =
            exchangeService.new SyncError(AbstractSyncService.EXIT_IO_ERROR, false);
        SyncError securityErrorAccount2 =
            exchangeService.new SyncError(AbstractSyncService.EXIT_SECURITY_FAILURE, false);
        // Create account and two mailboxes
        Account acct1 = ProviderTestUtils.setupAccount("acct1", true, context);
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", acct1.mId, true, context);
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", acct1.mId, true, context);
        Account acct2 = ProviderTestUtils.setupAccount("acct2", true, context);
        Mailbox box3 = ProviderTestUtils.setupMailbox("box3", acct2.mId, true, context);
        Mailbox box4 = ProviderTestUtils.setupMailbox("box4", acct2.mId, true, context);

        ConcurrentHashMap<Long, SyncError> errorMap = exchangeService.mSyncErrorMap;
        // Add errors into the map
        errorMap.put(box1.mId, securityErrorAccount1);
        errorMap.put(box2.mId, ioError);
        errorMap.put(box3.mId, securityErrorAccount2);
        errorMap.put(box4.mId, securityErrorAccount2);
        // We should have 4
        assertEquals(4, errorMap.keySet().size());
        // Release the holds on acct2 (there are two of them)
        assertTrue(exchangeService.releaseSyncHolds(context,
                AbstractSyncService.EXIT_SECURITY_FAILURE, acct2));
        // There should be two left
        assertEquals(2, errorMap.keySet().size());
        // And these are the two...
        assertNotNull(errorMap.get(box2.mId));
        assertNotNull(errorMap.get(box1.mId));

        // Put the two back
        errorMap.put(box3.mId, securityErrorAccount2);
        errorMap.put(box4.mId, securityErrorAccount2);
        // We should have 4 again
        assertEquals(4, errorMap.keySet().size());
        // Release all of the security holds
        assertTrue(exchangeService.releaseSyncHolds(context,
                AbstractSyncService.EXIT_SECURITY_FAILURE, null));
        // There should be one left
        assertEquals(1, errorMap.keySet().size());
        // And this is the one
        assertNotNull(errorMap.get(box2.mId));

        // Release the i/o holds on account 2 (there aren't any)
        assertFalse(exchangeService.releaseSyncHolds(context,
                AbstractSyncService.EXIT_IO_ERROR, acct2));
        // There should still be one left
        assertEquals(1, errorMap.keySet().size());

        // Release the i/o holds on account 1 (there's one)
        assertTrue(exchangeService.releaseSyncHolds(context,
                AbstractSyncService.EXIT_IO_ERROR, acct1));
        // There should still be one left
        assertEquals(0, errorMap.keySet().size());
    }

    public void testIsSyncable() {
        Context context = mMockContext;
        Account acct1 = ProviderTestUtils.setupAccount("acct1", true, context);
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", acct1.mId, true, context,
                Mailbox.TYPE_DRAFTS);
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", acct1.mId, true, context,
                Mailbox.TYPE_OUTBOX);
        Mailbox box3 = ProviderTestUtils.setupMailbox("box2", acct1.mId, true, context,
                Mailbox.TYPE_ATTACHMENT);
        Mailbox box4 = ProviderTestUtils.setupMailbox("box2", acct1.mId, true, context,
                Mailbox.TYPE_NOT_SYNCABLE + 64);
        Mailbox box5 = ProviderTestUtils.setupMailbox("box2", acct1.mId, true, context,
                Mailbox.TYPE_MAIL);
        assertFalse(ExchangeService.isSyncable(null));
        assertFalse(ExchangeService.isSyncable(box1));
        assertFalse(ExchangeService.isSyncable(box2));
        assertFalse(ExchangeService.isSyncable(box3));
        assertFalse(ExchangeService.isSyncable(box4));
        assertTrue(ExchangeService.isSyncable(box5));
    }
}
