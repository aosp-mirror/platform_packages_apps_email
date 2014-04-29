/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.email.mail;

import android.content.Context;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.Suppress;

import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;

/**
 * Tests of StoreInfo & Store lookup in the Store abstract class
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.mail.store.StoreTests email
 *
 */
@Suppress
@MediumTest
public class StoreTests extends ProviderTestCase2<EmailProvider> {

    private Context mMockContext;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
        Store.sStores.clear();
    }

    public StoreTests() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    public StoreTests(Class<EmailProvider> providerClass, String providerAuthority) {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    public void testGetInstance() throws MessagingException {
        Store testStore;

        // POP3
        Account testAccount = ProviderTestUtils.setupAccount("pop", false, mMockContext);
        HostAuth testAuth = new HostAuth();
        testAccount.mHostAuthRecv = testAuth;
        testAuth.mAddress = "pop3.google.com";
        testAuth.mProtocol = "pop3";
        testAccount.save(mMockContext);

        testStore = Store.getInstance(testAccount, getContext());
        assertEquals(1, Store.sStores.size());
        assertSame(testStore, Store.sStores.get(testAccount.mId));
        Store.sStores.clear();

        // IMAP
        testAccount = ProviderTestUtils.setupAccount("pop", false, mMockContext);
        testAuth = new HostAuth();
        testAccount.mHostAuthRecv = testAuth;
        testAuth.mAddress = "imap.google.com";
        testAuth.mProtocol = "imap";
        testAccount.save(mMockContext);
        testStore = Store.getInstance(testAccount, getContext());
        assertEquals(1, Store.sStores.size());
        assertSame(testStore, Store.sStores.get(testAccount.mId));
        Store.sStores.clear();

        // Unknown
        testAccount = ProviderTestUtils.setupAccount("unknown", false, mMockContext);
        testAuth = new HostAuth();
        testAuth.mAddress = "unknown.google.com";
        testAuth.mProtocol = "unknown";
        try {
            testStore = Store.getInstance(testAccount, getContext());
            fail("Store#getInstance() should have thrown an exception");
        } catch (MessagingException expected) {
        }
        assertEquals(0, Store.sStores.size());
    }

    public void testUpdateMailbox() {
        Mailbox testMailbox = new Mailbox();

        Store.updateMailbox(testMailbox, 1L, "inbox", '/', true, Mailbox.TYPE_MAIL);
        assertEquals(1L, testMailbox.mAccountKey);
        assertEquals("inbox", testMailbox.mDisplayName);
        assertEquals("inbox", testMailbox.mServerId);
        assertEquals('/', testMailbox.mDelimiter);

        Store.updateMailbox(testMailbox, 2L, "inbox/a", '/', true, Mailbox.TYPE_MAIL);
        assertEquals(2L, testMailbox.mAccountKey);
        assertEquals("a", testMailbox.mDisplayName);
        assertEquals("inbox/a", testMailbox.mServerId);
        assertEquals('/', testMailbox.mDelimiter);

        Store.updateMailbox(testMailbox, 3L, "inbox/a/b/c/d", '/', true, Mailbox.TYPE_MAIL);
        assertEquals(3L, testMailbox.mAccountKey);
        assertEquals("d", testMailbox.mDisplayName);
        assertEquals("inbox/a/b/c/d", testMailbox.mServerId);
        assertEquals('/', testMailbox.mDelimiter);

        Store.updateMailbox(testMailbox, 4L, "inbox/a/b/c", '\0', true, Mailbox.TYPE_MAIL);
        assertEquals(4L, testMailbox.mAccountKey);
        assertEquals("inbox/a/b/c", testMailbox.mDisplayName);
        assertEquals("inbox/a/b/c", testMailbox.mServerId);
        assertEquals('\0', testMailbox.mDelimiter);
    }
}
