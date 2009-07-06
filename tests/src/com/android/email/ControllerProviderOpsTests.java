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

package com.android.email;

import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;

import android.content.Context;
import android.test.ProviderTestCase2;

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
public class ControllerProviderOpsTests extends ProviderTestCase2<EmailProvider> {

    EmailProvider mProvider;
    Context mProviderContext;
    Context mContext;

    public ControllerProviderOpsTests() {
        super(EmailProvider.class, EmailProvider.EMAIL_AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mProviderContext = getMockContext();
        mContext = getContext();
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
     * Test the "delete message" function.  Sunny day:
     *    - message/mailbox/account all exist
     *    - trash mailbox exists
     */
    public void testDeleteMessage() {
        Account account1 = ProviderTestUtils.setupAccount("message-delete", true, mProviderContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mProviderContext);
        long box1Id = box1.mId;
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", account1Id, false, mProviderContext);
        box2.mType = EmailContent.Mailbox.TYPE_TRASH;
        box2.save(mProviderContext);
        long box2Id = box2.mId;

        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mProviderContext);
        long message1Id = message1.mId;

        Controller ct = new TestController(mProviderContext, mContext);

        ct.deleteMessage(message1Id, account1Id);

        // now read back a fresh copy and confirm it's in the trash
        Message message1get = EmailContent.Message.restoreMessageWithId(mProviderContext,
                message1Id);
        assertEquals(box2Id, message1get.mMailboxKey);

        // Now repeat test with accountId "unknown"
        Message message2 = ProviderTestUtils.setupMessage("message2", account1Id, box1Id, false,
                true, mProviderContext);
        long message2Id = message2.mId;

        ct.deleteMessage(message2Id, -1);

        // now read back a fresh copy and confirm it's in the trash
        Message message2get = EmailContent.Message.restoreMessageWithId(mProviderContext,
                message2Id);
        assertEquals(box2Id, message2get.mMailboxKey);
    }

    /**
     * Test deleting message when there is no trash mailbox
     */
    public void testDeleteMessageNoTrash() {
        Account account1 =
                ProviderTestUtils.setupAccount("message-delete-notrash", true, mProviderContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mProviderContext);
        long box1Id = box1.mId;

        Message message1 =
                ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false, true,
                        mProviderContext);
        long message1Id = message1.mId;

        Controller ct = new TestController(mProviderContext, mContext);

        ct.deleteMessage(message1Id, account1Id);

        // now read back a fresh copy and confirm it's in the trash
        Message message1get =
                EmailContent.Message.restoreMessageWithId(mProviderContext, message1Id);

        // check the new mailbox and see if it looks right
        assertFalse(-1 == message1get.mMailboxKey);
        assertFalse(box1Id == message1get.mMailboxKey);
        Mailbox mailbox2get = EmailContent.Mailbox.restoreMailboxWithId(mProviderContext,
                message1get.mMailboxKey);
        assertEquals(EmailContent.Mailbox.TYPE_TRASH, mailbox2get.mType);
    }

    /**
     * TODO: releasing associated data (e.g. attachments, embedded images)
     */
}
