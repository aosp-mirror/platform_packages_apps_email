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

package com.android.email.service;

import com.android.email.Controller;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.HostAuth;
import com.android.email.service.MailService.AccountSyncReport;

import android.content.ContentValues;
import android.content.Context;
import android.test.ProviderTestCase2;

import java.util.HashMap;

/**
 * Tests of the Email provider.
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.service.MailServiceTests email
 */
public class MailServiceTests extends ProviderTestCase2<EmailProvider> {

    EmailProvider mProvider;
    Context mMockContext;

    public MailServiceTests() {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
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
     * Lightweight subclass of the Controller class allows injection of mock context
     */
    public static class TestController extends Controller {

        protected TestController(Context providerContext, Context systemContext) {
            super(systemContext);
            setProviderContext(providerContext);
        }
    }

    /**
     * Create a simple HostAuth with protocol
     */
    private HostAuth setupSimpleHostAuth(String protocol) {
        HostAuth hostAuth = new HostAuth();
        hostAuth.mProtocol = protocol;
        return hostAuth;
    }

    /**
     * Initial testing on setupSyncReportsLocked, making sure that EAS accounts aren't scheduled
     */
    public void testSetupSyncReportsLocked() {
        // TODO Test other functionality within setupSyncReportsLocked
        // Setup accounts of each type, all with manual sync at different intervals
        Account easAccount = ProviderTestUtils.setupAccount("account1", false, mMockContext);
        easAccount.mHostAuthRecv = setupSimpleHostAuth("eas");
        easAccount.mSyncInterval = 30;
        easAccount.save(mMockContext);
        Account imapAccount = ProviderTestUtils.setupAccount("account2", false, mMockContext);
        imapAccount.mHostAuthRecv = setupSimpleHostAuth("imap");
        imapAccount.mSyncInterval = 60;
        imapAccount.save(mMockContext);
        Account pop3Account = ProviderTestUtils.setupAccount("account3", false, mMockContext);
        pop3Account.mHostAuthRecv = setupSimpleHostAuth("pop3");
        pop3Account.mSyncInterval = 90;
        pop3Account.save(mMockContext);

        // Setup the SyncReport's for these Accounts
        MailService mailService = new MailService();
        mailService.mController = new TestController(mMockContext, getContext());
        mailService.setupSyncReportsLocked(-1, mMockContext.getContentResolver());

        // Get back the map created by MailService
        HashMap<Long, AccountSyncReport> syncReportMap = MailService.mSyncReports;
        // Check the SyncReport's for correctness of sync interval
        AccountSyncReport syncReport = syncReportMap.get(easAccount.mId);
        assertNotNull(syncReport);
        // EAS sync interval should have been changed to "never"
        assertEquals(Account.CHECK_INTERVAL_NEVER, syncReport.syncInterval);
        syncReport = syncReportMap.get(imapAccount.mId);
        assertNotNull(syncReport);
        assertEquals(60, syncReport.syncInterval);
        syncReport = syncReportMap.get(pop3Account.mId);
        assertNotNull(syncReport);
        assertEquals(90, syncReport.syncInterval);

        // Change the EAS account to push
        ContentValues cv = new ContentValues();
        cv.put(Account.SYNC_INTERVAL, Account.CHECK_INTERVAL_PUSH);
        easAccount.update(mMockContext, cv);
        syncReportMap.clear();
        mailService.setupSyncReportsLocked(easAccount.mId, mMockContext.getContentResolver());
        syncReport = syncReportMap.get(easAccount.mId);
        assertNotNull(syncReport);
        // EAS sync interval should be "never" in this case as well
        assertEquals(Account.CHECK_INTERVAL_NEVER, syncReport.syncInterval);
    }
}
