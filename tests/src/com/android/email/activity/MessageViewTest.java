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

package com.android.email.activity;

import com.android.email.DBTestHelper;
import com.android.email.TestUtils;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;

import android.content.Context;
import android.content.Intent;
import android.test.ActivityInstrumentationTestCase2;

/**
 * Test case for {@link MessageView}.
 *
 * TODO Add more tests.  Any good way to test fragment??
 */
public class MessageViewTest extends ActivityInstrumentationTestCase2<MessageView> {
    private static final String EXTRA_MESSAGE_ID = "com.android.email.MessageView_message_id";
    private static final String EXTRA_DISABLE_REPLY =
            "com.android.email.MessageView_disable_reply";
    private static final String EXTRA_MAILBOX_ID = "com.android.email.MessageView_mailbox_id";

    private static int TIMEOUT = 10; // in seconds

    private Context mProviderContext;

    public MessageViewTest() {
        super(MessageView.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mProviderContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(
                getInstrumentation().getTargetContext(), EmailProvider.class);
    }

    private void setUpIntent(long messageId, long mailboxId, boolean disableReply) {
        final Intent i = new Intent(getInstrumentation().getTargetContext(), MessageView.class);
        i.putExtra(EXTRA_MESSAGE_ID, messageId);
        i.putExtra(EXTRA_MAILBOX_ID, mailboxId);
        if (disableReply) {
            i.putExtra(EXTRA_DISABLE_REPLY, true);
        }
        setActivityIntent(i);
    }

    /**
     * Open the activity without setting an Intent.
     *
     * Expected: Activity will close itself.
     */
    public void testCreateWithoutParamter() throws Throwable {
        // No intent parameters specified.  The activity will close itself.
        final MessageView activity = getActivity();

        TestUtils.waitUntil("", new TestUtils.Condition() {
            @Override
            public boolean isMet() {
                return activity.isFinishing();
            }
        }, TIMEOUT);
    }

    /**
     * Set up account/mailbox/message, and open the activity.
     *
     * Expected: Message opens.
     */
    public void testOpenMessage() throws Exception {
        final Context c = mProviderContext;
        final Account acct1 = ProviderTestUtils.setupAccount("test1", true, c);
        final Account acct2 = ProviderTestUtils.setupAccount("test2", true, c);
        final Mailbox acct2inbox = ProviderTestUtils.setupMailbox("inbox", acct2.mId, true, c);
        final Message msg1 = ProviderTestUtils.setupMessage("message1", acct2.mId, acct2inbox.mId,
                true, true, c);
        final Message msg2 = ProviderTestUtils.setupMessage("message2", acct2.mId, acct2inbox.mId,
                true, true, c);

        setUpIntent(msg2.mId, msg2.mMailboxKey, false);

        final MessageView activity = getActivity();

        TestUtils.waitUntil(new TestUtils.Condition() {
            @Override
            public boolean isMet() {
                MessageViewFragment2 f = activity.getFragment();
                return f != null && f.isMessageLoadedForTest();
            }
        }, TIMEOUT);

        // TODO Check UI elements, once our UI is settled.
    }
}
