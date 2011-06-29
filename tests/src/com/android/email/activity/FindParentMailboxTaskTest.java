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

package com.android.email.activity;

import com.android.email.DBTestHelper;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;

import android.content.Context;
import android.test.AndroidTestCase;

/**
 * Unit tests for {@link MailboxListFragment.FindParentMailboxTask}.
 */
public class FindParentMailboxTaskTest extends AndroidTestCase {
    private Context mProviderContext;

    /** ID of the account created by {@link #setUpMailboxes}. */
    private long mAccountId;

    /**
     * IDs for the mailboxes created by {@link #setUpMailboxes}.
     *
     * Mailbox hierarchy:
     * <pre>
     * |-Inbox
     * |-Parent
     *   |-Child1
     *   |-Child2
     *     |-GrandChild1
     *     |-GrandChild2
     * </pre>
     */
    private long mIdInbox;
    private long mIdParent;
    private long mIdChild1;
    private long mIdChild2;
    private long mIdGrandChild1;
    private long mIdGrandChild2;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mProviderContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(
                getContext());
        setUpMailboxes();
    }

    /**
     * Set up a test account and mailboxes.
     */
    private void setUpMailboxes() {
        Account a = ProviderTestUtils.setupAccount("a", true, mProviderContext);
        mAccountId = a.mId;

        mIdInbox = createMailboxAndGetId("Inbox", a, Mailbox.TYPE_INBOX, Mailbox.NO_MAILBOX);
        mIdParent = createMailboxAndGetId("P", a, Mailbox.TYPE_MAIL, Mailbox.NO_MAILBOX);
        mIdChild1 = createMailboxAndGetId("C1", a, Mailbox.TYPE_MAIL, mIdParent);
        mIdChild2 = createMailboxAndGetId("C2", a, Mailbox.TYPE_MAIL, mIdParent);
        mIdGrandChild1 = createMailboxAndGetId("G1", a, Mailbox.TYPE_MAIL, mIdChild2);
        mIdGrandChild2 = createMailboxAndGetId("G2", a, Mailbox.TYPE_MAIL, mIdChild2);
    }

    private long createMailboxAndGetId(String name, Account account, int type,
            long parentMailboxId) {
        Mailbox m = ProviderTestUtils.setupMailbox(name, account.mId, false, mProviderContext,
                type);
        m.mParentKey = parentMailboxId;
        m.save(mProviderContext);
        return m.mId;
    }

    /**
     * Tests for two-pane.  (highlighting is enabled)
     */
    public void testWithHighlight() {
        /*
         * In the comments below, [MAILBOX] indicates "highlighted", and MAILBOX* indicates
         * "selected".
         */
        /*
         * from:
         * - [Child2]
         *   - GChild1
         *   - GChild2
         *
         * to:
         * - Parent
         *   - Child1
         *   - [Child2]*
         */
        doCheckWithHighlight(
                mIdChild2, // Current parent
                mIdChild2, // Current highlighted

                mIdParent, // Next root
                mIdChild2, // Next highlighted
                mIdChild2 // Next selected
                );

        /*
         * from:
         * - Child2
         *   - [GChild1]
         *   - GChild2
         *
         * to:
         * - [Parent]*
         *   - Child1
         *   - Child2
         */
        doCheckWithHighlight(
                mIdChild2, // Current parent
                mIdGrandChild1, // Current highlighted

                mIdParent, // Next root
                mIdParent, // Next highlighted
                mIdParent // Next selected
                );

        /*
         * from:
         * - [Parent]
         *   - Child1
         *   - Child2
         *
         * to:
         * - Inbox
         * - [Parent]*
         */
        doCheckWithHighlight(
                mIdParent, // Current parent
                mIdParent, // Current highlighted

                Mailbox.NO_MAILBOX, // Next root
                mIdParent, // Next highlighted
                mIdParent // Next selected
                );

        /*
         * from:
         * - Parent
         *   - [Child1]
         *   - Child2
         *
         * to:
         * - [Inbox]*
         * - Parent
         */
        doCheckWithHighlight(
                mIdParent, // Current parent
                mIdChild1, // Current highlighted

                Mailbox.NO_MAILBOX, // Next root
                mIdInbox, // Next highlighted
                mIdInbox // Next selected
                );

        /*
         * Special case.
         * Up from root view, with "Parent" highlighted.  "Up" will be disabled in this case, but
         * if we were to run the task, it'd work as if the current parent mailbox is gone.
         * i.e. just show the top level mailboxes, with Inbox highlighted.
         *
         * from:
         * - Inbox
         * - [Parent]
         *
         * to:
         * - [Inbox]
         * - Parent
         */
        doCheckWithHighlight(
                Mailbox.NO_MAILBOX, // Current parent
                mIdParent, // Current highlighted

                Mailbox.NO_MAILBOX, // Next root
                mIdInbox, // Next highlighted
                mIdInbox // Next selected
                );

        /*
         * Special case.
         * Current parent mailbox is gone.  The result should be same as the above.
         *
         * from:
         *  (current mailbox just removed)
         *
         * to:
         * - [Inbox]
         * - Parent
         */
        doCheckWithHighlight(
                12312234234L, // Current parent
                mIdParent, // Current highlighted

                Mailbox.NO_MAILBOX, // Next root
                mIdInbox, // Next highlighted
                mIdInbox // Next selected
                );
    }

    private void doCheckWithHighlight(
            long parentMailboxId, long highlightedMailboxId,
            long expectedNextParent, long expectedNextHighlighted, long expectedNextSelected) {
        doCheck(true, parentMailboxId, highlightedMailboxId,
                expectedNextParent, expectedNextHighlighted, expectedNextSelected);
    }

    /**
     * Tests for one-pane.  (highlighting is disable)
     */
    public void testWithNoHighlight() {
        /*
         * from:
         * - Child2
         *   - GChild1
         *   - GChild2
         *
         * to:
         * - Parent
         *   - Child1
         *   - Child2
         */
        doCheckWithNoHighlight(
                mIdChild2, // Current parent
                mIdParent // Next root
                );
        /*
         * from:
         * - Parent
         *   - Child1
         *   - Child2
         *
         * to:
         * - Inbox
         * - Parent
         */
        doCheckWithNoHighlight(
                mIdParent, // Current parent
                Mailbox.NO_MAILBOX // Next root
                );

        /*
         * Special case.
         * Current parent mailbox is gone.  The top-level mailboxes should be shown.
         *
         * from:
         *  (current mailbox just removed)
         *
         * to:
         * - Inbox
         * - Parent
         */
        doCheckWithNoHighlight(
                12312234234L, // Current parent
                Mailbox.NO_MAILBOX // Next root
                );
    }

    private void doCheckWithNoHighlight(long parentMailboxId, long expectedNextParent) {
        doCheck(false, parentMailboxId, Mailbox.NO_MAILBOX,
                expectedNextParent, Mailbox.NO_MAILBOX,
                expectedNextParent /* parent should always be selected */);
    }

    private void doCheck(boolean enableHighlight,
            long parentMailboxId, long highlightedMailboxId,
            long expectedNextParent, long expectedNextHighlighted, long expectedNextSelected) {
        ResultCallback result = new ResultCallback();

        MailboxListFragment.FindParentMailboxTask task
                = new MailboxListFragment.FindParentMailboxTask(
                mProviderContext, null, mAccountId, enableHighlight, parentMailboxId,
                highlightedMailboxId, result);

        // Can't execute an async task on the test thread, so emulate execution...
        task.onSuccess(task.doInBackground((Void[]) null));

        assertEquals("parent", expectedNextParent, result.mNextParentMailboxId);
        assertEquals("highlighted", expectedNextHighlighted, result.mNextHighlightedMailboxId);
        assertEquals("selected", expectedNextSelected, result.mNextSelectedMailboxId);
    }

    private static class ResultCallback
            implements MailboxListFragment.FindParentMailboxTask.ResultCallback {
        public long mNextParentMailboxId;
        public long mNextHighlightedMailboxId;
        public long mNextSelectedMailboxId;

        @Override
        public void onResult(long nextParentMailboxId, long nextHighlightedMailboxId,
                long nextSelectedMailboxId) {
            mNextParentMailboxId = nextParentMailboxId;
            mNextHighlightedMailboxId = nextHighlightedMailboxId;
            mNextSelectedMailboxId = nextSelectedMailboxId;
        }
    }
}
