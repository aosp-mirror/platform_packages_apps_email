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

package com.android.email;

import android.content.Context;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.Suppress;

import com.android.email.provider.ContentCache;
import com.android.email.provider.EmailProvider;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.Mailbox;

import java.util.Locale;

/**
 * Tests of the Controller class that depend on the underlying provider.
 *
 * NOTE:  It would probably make sense to rewrite this using a MockProvider, instead of the
 * ProviderTestCase (which is a real provider running on a temp database).  This would be more of
 * a true "unit test".
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.ControllerProviderOpsTests email
 */
@Suppress
public class ControllerProviderOpsTests extends ProviderTestCase2<EmailProvider> {
    private Context mProviderContext;
    private Context mContext;

    public ControllerProviderOpsTests() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mProviderContext = getMockContext();
        mContext = getContext();
        // Invalidate all caches, since we reset the database for each test
        ContentCache.invalidateAllCaches();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * These are strings that should not change per locale.
     */
    public void testGetMailboxServerName() {
        try {
            Mailbox.getSystemMailboxName(mContext, -1);
            fail("Mailbox.getSystemMailboxName(mContext, -1) succeeded without an exception");
        } catch (IllegalArgumentException e) {
            // we expect an exception, so do nothing
        }

        assertEquals("Inbox", Mailbox.getSystemMailboxName(mContext, Mailbox.TYPE_INBOX));
        assertEquals("Outbox", Mailbox.getSystemMailboxName(mContext, Mailbox.TYPE_OUTBOX));
        assertEquals("Trash", Mailbox.getSystemMailboxName(mContext, Mailbox.TYPE_TRASH));
        assertEquals("Sent", Mailbox.getSystemMailboxName(mContext, Mailbox.TYPE_SENT));
        assertEquals("Junk", Mailbox.getSystemMailboxName(mContext, Mailbox.TYPE_JUNK));

        // Now try again with translation
        Locale savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.FRANCE);
        assertEquals("Inbox", Mailbox.getSystemMailboxName(mContext, Mailbox.TYPE_INBOX));
        assertEquals("Outbox", Mailbox.getSystemMailboxName(mContext, Mailbox.TYPE_OUTBOX));
        assertEquals("Trash", Mailbox.getSystemMailboxName(mContext, Mailbox.TYPE_TRASH));
        assertEquals("Sent", Mailbox.getSystemMailboxName(mContext, Mailbox.TYPE_SENT));
        assertEquals("Junk", Mailbox.getSystemMailboxName(mContext, Mailbox.TYPE_JUNK));
        Locale.setDefault(savedLocale);
    }

    /**
     * TODO: releasing associated data (e.g. attachments, embedded images)
     */
}
