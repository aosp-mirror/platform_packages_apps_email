/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.email.mail;

import com.android.email.Email;
import com.android.email.mail.Store.StoreInfo;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.EmailContent.Account;
import com.android.emailcommon.provider.EmailContent.HostAuth;
import com.android.emailcommon.provider.EmailContent.Mailbox;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Tests of StoreInfo & Store lookup in the Store abstract class
 */
@MediumTest
public class StoreTests extends AndroidTestCase {

    @Override
    public void setUp() {
        Store.sStores.clear();
    }

    public void testGetStoreKey() throws MessagingException {
        HostAuth testAuth = new HostAuth();
        Account testAccount = new Account();
        String testKey;

        // Make sure to set the host auth; otherwise we create entries in the hostauth db
        testAccount.mHostAuthRecv = testAuth;

        // No address defined; throws an exception
        try {
            testKey = Store.getStoreKey(mContext, testAccount);
            fail("MesasginException not thrown for missing address");
        } catch (MessagingException expected) {
        }

        // Empty address defined; throws an exception
        testAuth.mAddress = " \t ";
        try {
            testKey = Store.getStoreKey(mContext, testAccount);
            fail("MesasginException not thrown for empty address");
        } catch (MessagingException expected) {
        }

        // Address defined, no login
        testAuth.mAddress = "a.valid.address.com";
        testKey = Store.getStoreKey(mContext, testAccount);
        assertEquals("a.valid.address.com", testKey);

        // Address & login defined
        testAuth.mAddress = "address.org";
        testAuth.mLogin = "auser";
        testKey = Store.getStoreKey(mContext, testAccount);
        assertEquals("address.orgauser", testKey);
    }

    public void testGetStoreInfo() {
        StoreInfo testInfo;

        // POP3
        testInfo = Store.StoreInfo.getStoreInfo("pop3", mContext);
        assertNotNull(testInfo);
        assertNotNull(testInfo.mScheme);
        assertNotNull(testInfo.mClassName);
        assertFalse(testInfo.mPushSupported);
        assertEquals(Email.VISIBLE_LIMIT_DEFAULT, testInfo.mVisibleLimitDefault);
        assertEquals(Email.VISIBLE_LIMIT_INCREMENT, testInfo.mVisibleLimitIncrement);

        // IMAP
        testInfo = Store.StoreInfo.getStoreInfo("imap", mContext);
        assertNotNull(testInfo);
        assertNotNull(testInfo.mScheme);
        assertNotNull(testInfo.mClassName);
        assertFalse(testInfo.mPushSupported);
        assertEquals(Email.VISIBLE_LIMIT_DEFAULT, testInfo.mVisibleLimitDefault);
        assertEquals(Email.VISIBLE_LIMIT_INCREMENT, testInfo.mVisibleLimitIncrement);

        // Unknown
        testInfo = Store.StoreInfo.getStoreInfo("unknownscheme", mContext);
        assertNull(testInfo);
    }

    public void testGetInstance() throws MessagingException {
        HostAuth testAuth = new HostAuth();
        Account testAccount = new Account();
        Store testStore;

        // Make sure to set the host auth; otherwise we create entries in the hostauth db
        testAccount.mHostAuthRecv = testAuth;

        // POP3
        testAuth.mAddress = "pop3.google.com";
        testAuth.mProtocol = "pop3";
        testStore = Store.getInstance(testAccount, getContext(), null);
        assertEquals(1, Store.sStores.size());
        assertSame(testStore, Store.sStores.get("pop3.google.com"));
        Store.sStores.clear();

        // IMAP
        testAuth.mAddress = "imap.google.com";
        testAuth.mProtocol = "imap";
        testStore = Store.getInstance(testAccount, getContext(), null);
        assertEquals(1, Store.sStores.size());
        assertSame(testStore, Store.sStores.get("imap.google.com"));
        Store.sStores.clear();

        // Unknown
        testAuth.mAddress = "unknown.google.com";
        testAuth.mProtocol = "unknown";
        try {
            testStore = Store.getInstance(testAccount, getContext(), null);
            fail("Store#getInstance() should have thrown an exception");
        } catch (MessagingException expected) {
        }
        assertEquals(0, Store.sStores.size());
    }

    public void testUpdateMailbox() {
        Mailbox testMailbox = new Mailbox();

        Store.updateMailbox(testMailbox, 1L, "inbox", '/', Mailbox.TYPE_MAIL);
        assertEquals(1L, testMailbox.mAccountKey);
        assertEquals("inbox", testMailbox.mDisplayName);
        assertEquals("inbox", testMailbox.mServerId);
        assertEquals('/', testMailbox.mDelimiter);

        Store.updateMailbox(testMailbox, 2L, "inbox/a", '/', Mailbox.TYPE_MAIL);
        assertEquals(2L, testMailbox.mAccountKey);
        assertEquals("a", testMailbox.mDisplayName);
        assertEquals("inbox/a", testMailbox.mServerId);
        assertEquals('/', testMailbox.mDelimiter);

        Store.updateMailbox(testMailbox, 3L, "inbox/a/b/c/d", '/', Mailbox.TYPE_MAIL);
        assertEquals(3L, testMailbox.mAccountKey);
        assertEquals("d", testMailbox.mDisplayName);
        assertEquals("inbox/a/b/c/d", testMailbox.mServerId);
        assertEquals('/', testMailbox.mDelimiter);

        Store.updateMailbox(testMailbox, 4L, "inbox/a/b/c", '\0', Mailbox.TYPE_MAIL);
        assertEquals(4L, testMailbox.mAccountKey);
        assertEquals("inbox/a/b/c", testMailbox.mDisplayName);
        assertEquals("inbox/a/b/c", testMailbox.mServerId);
        assertEquals('\0', testMailbox.mDelimiter);
    }
}
