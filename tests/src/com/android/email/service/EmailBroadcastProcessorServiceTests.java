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

package com.android.email.service;

import android.content.ContentUris;
import android.content.Context;
import android.net.Uri;
import android.test.suitebuilder.annotation.Suppress;

import com.android.email.AccountTestCase;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.AccountColumns;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.Utility;

import java.util.NoSuchElementException;

/**
 * Tests of the Email provider.
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.service.EmailBroadcastProcessorServiceTests email
 */
@Suppress
public class EmailBroadcastProcessorServiceTests extends AccountTestCase {

    Context mMockContext;

    public EmailBroadcastProcessorServiceTests() {
        super();
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Create a simple HostAuth with protocol
     */
    private HostAuth setupSimpleHostAuth(String protocol) {
        HostAuth hostAuth = ProviderTestUtils.setupHostAuth(protocol, "name", false, mContext);
        hostAuth.mProtocol = protocol;
        return hostAuth;
    }

    /**
     * Returns the flags for the specified account. Throws an exception if the account cannot
     * be found.
     */
    private int getAccountFlags(long accountId) throws NoSuchElementException {
        Uri uri = ContentUris.withAppendedId(Account.CONTENT_URI, accountId);
        Integer flags = Utility.getFirstRowInt(mMockContext, uri,
                new String[] { AccountColumns.FLAGS }, null, null, null, 0);
        if (flags == null) {
            throw new NoSuchElementException("No cursor");
        }
        return flags;
    }

    /**
     * Initial testing on setupSyncReportsLocked, making sure that EAS accounts aren't scheduled
     */
    public void testSetImapDeletePolicy() {
        // Setup accounts of each type, all with manual sync at different intervals
        Account account1 = ProviderTestUtils.setupAccount("eas-account1", false, mMockContext);
        account1.mHostAuthRecv = setupSimpleHostAuth("eas");
        account1.mHostAuthSend = account1.mHostAuthRecv;
        account1.save(mMockContext);
        long accountId1 = account1.mId;
        Account account2 = ProviderTestUtils.setupAccount("pop-account1", false, mMockContext);
        account2.mHostAuthRecv = setupSimpleHostAuth("pop3");
        account2.mHostAuthSend = setupSimpleHostAuth("smtp");
        account2.mFlags = 0x08;       // set delete policy
        account2.save(mMockContext);
        long accountId2 = account2.mId;
        Account account3 = ProviderTestUtils.setupAccount("pop-account2", false, mMockContext);
        account3.mHostAuthRecv = setupSimpleHostAuth("pop3");
        account3.mHostAuthSend = setupSimpleHostAuth("smtp");
        account3.save(mMockContext);
        long accountId3 = account3.mId;
        Account account4 = ProviderTestUtils.setupAccount("imap-account1", false, mMockContext);
        account4.mHostAuthRecv = setupSimpleHostAuth("imap");
        account4.mHostAuthSend = setupSimpleHostAuth("smtp");
        account4.mFlags = 0xa5a5a5a5; // Alternating bits; includes bad delete policy
        account4.save(mMockContext);
        long accountId4 = account4.mId;
        Account account5 = ProviderTestUtils.setupAccount("imap-account2", false, mMockContext);
        account5.mHostAuthRecv = setupSimpleHostAuth("imap");
        account5.mHostAuthSend = setupSimpleHostAuth("smtp");
        account5.mFlags = 0x0c;       // All delete policy bits set
        account5.save(mMockContext);
        long accountId5 = account5.mId;
        Account account6 = ProviderTestUtils.setupAccount("imap-account3", false, mMockContext);
        account6.mHostAuthRecv = setupSimpleHostAuth("imap");
        account6.mHostAuthSend = setupSimpleHostAuth("smtp");
        account6.mFlags = 0;         // No delete policy bits set
        account6.save(mMockContext);
        long accountId6 = account6.mId;

        // Run the account migration
        EmailBroadcastProcessorService.setImapDeletePolicy(mMockContext);

        // Test the results
        int accountFlags1 = getAccountFlags(accountId1);
        assertEquals(4, accountFlags1);           // not IMAP; no changes
        int accountFlags2 = getAccountFlags(accountId2);
        assertEquals(8, accountFlags2);           // not IMAP; no changes
        int accountFlags3 = getAccountFlags(accountId3);
        assertEquals(4, accountFlags3);           // not IMAP; no changes
        int accountFlags4 = getAccountFlags(accountId4);
        assertEquals(0xa5a5a5a9, accountFlags4);  // Only update delete policy bits
        int accountFlags5 = getAccountFlags(accountId5);
        assertEquals(0x00000008, accountFlags5);
        int accountFlags6 = getAccountFlags(accountId6);
        assertEquals(0x00000008, accountFlags6);
    }

}
