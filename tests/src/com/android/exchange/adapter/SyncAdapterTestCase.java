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

package com.android.exchange.adapter;

import com.android.email.provider.EmailProvider;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.exchange.EasSyncService;
import com.android.exchange.adapter.EmailSyncAdapter.EasEmailSyncParser;

import android.content.ContentResolver;
import android.content.Context;
import android.test.ProviderTestCase2;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

public class SyncAdapterTestCase<T extends AbstractSyncAdapter>
        extends ProviderTestCase2<EmailProvider> {
    EmailProvider mProvider;
    Context mMockContext;
    ContentResolver mMockResolver;
    Mailbox mMailbox;
    Account mAccount;
    EmailSyncAdapter mSyncAdapter;
    EasEmailSyncParser mSyncParser;

    public SyncAdapterTestCase() {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
        mMockResolver = mMockContext.getContentResolver();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Create and return a short, simple InputStream that has at least four bytes, which is all
     * that's required to initialize an EasParser (the parent class of EasEmailSyncParser)
     * @return the InputStream
     */
    public InputStream getTestInputStream() {
        return new ByteArrayInputStream(new byte[] {0, 0, 0, 0, 0});
    }

    EasSyncService getTestService() {
        Account account = new Account();
        account.mEmailAddress = "__test__@android.com";
        account.mId = -1;
        Mailbox mailbox = new Mailbox();
        mailbox.mId = -1;
        return getTestService(account, mailbox);
    }

    EasSyncService getTestService(Account account, Mailbox mailbox) {
        EasSyncService service = new EasSyncService();
        service.mContext = mMockContext;
        service.mMailbox = mailbox;
        service.mAccount = account;
        service.mContentResolver = mMockResolver;
        return service;
    }

    T getTestSyncAdapter(Class<T> klass) {
        EasSyncService service = getTestService();
        Constructor<T> c;
        try {
            c = klass.getDeclaredConstructor(new Class[] {Mailbox.class, EasSyncService.class});
            return c.newInstance(service.mMailbox, service);
        } catch (SecurityException e) {
        } catch (NoSuchMethodException e) {
        } catch (IllegalArgumentException e) {
        } catch (InstantiationException e) {
        } catch (IllegalAccessException e) {
        } catch (InvocationTargetException e) {
        }
        return null;
    }

}
