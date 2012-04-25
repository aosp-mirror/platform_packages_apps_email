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

import android.content.Context;
import android.test.InstrumentationTestCase;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.LargeTest;

import com.android.email.Controller;
import com.android.email.DBTestHelper;
import com.android.email.Email;
import com.android.email.TestUtils;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Mailbox;

/**
 * Test case for {@link MailboxFinder}.
 *
 * We need to use {@link InstrumentationTestCase} so that we can create AsyncTasks on the UI thread
 * using {@link InstrumentationTestCase#runTestOnUiThread}.  This class also needs an isolated
 * context, which is provided by {@link ProviderTestCase2}.  We can't derive from two classes,
 * so we just copy the code for an isolate context to here.
 */
@LargeTest
public class MailboxFinderTest extends InstrumentationTestCase {
    private static final int TIMEOUT = 10; // in seconds

    // Test target
    private MailboxFinder mMailboxFinder;

    // Isolted Context for providers.
    private Context mProviderContext;

    // Mock to track callback invocations.
    private MockController mMockController;
    private MockCallback mCallback;

    private Context getContext() {
        return getInstrumentation().getTargetContext();
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mProviderContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(
                getInstrumentation().getTargetContext());
        mCallback = new MockCallback();
        mMockController = new MockController(getContext());
        Controller.injectMockControllerForTest(mMockController);
        assertEquals(0, mMockController.getResultCallbacksForTest().size());
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (mMailboxFinder != null) {
            mMailboxFinder.cancel();

            // MailboxFinder should unregister its listener when closed.
            checkControllerResultRemoved(mMockController);
        }
        mMockController.cleanupForTest();
        Controller.injectMockControllerForTest(null);
    }

    /**
     * Make sure no {@link MailboxFinder.Callback} is left registered to the controller.
     */
    private static void checkControllerResultRemoved(Controller controller) {
        for (Controller.Result callback : controller.getResultCallbacksForTest()) {
            assertFalse(callback instanceof MailboxFinder.Callback);
        }
    }

    /**
     * Create an account and returns the ID.
     */
    private long createAccount(boolean securityHold) {
        Account acct = ProviderTestUtils.setupAccount("acct1", false, mProviderContext);
        if (securityHold) {
            acct.mFlags |= Account.FLAGS_SECURITY_HOLD;
        }
        acct.save(mProviderContext);
        return acct.mId;
    }

    /**
     * Create a mailbox and return the ID.
     */
    private long createMailbox(long accountId, int mailboxType) {
        Mailbox box = new Mailbox();
        box.mServerId = box.mDisplayName = "mailbox";
        box.mAccountKey = accountId;
        box.mType = mailboxType;
        box.mFlagVisible = true;
        box.mVisibleLimit = Email.VISIBLE_LIMIT_DEFAULT;
        box.save(mProviderContext);
        return box.mId;
    }

    /**
     * Create a {@link MailboxFinder} and kick it.
     */
    private void createAndStartFinder(final long accountId, final int mailboxType)
            throws Throwable {
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mMailboxFinder = new MailboxFinder(mProviderContext, accountId, mailboxType,
                        mCallback);
                mMailboxFinder.startLookup();
                assertTrue(mMailboxFinder.isStartedForTest());
            }
        });
    }

    /**
     * Wait until any of the {@link MailboxFinder.Callback} method or
     * {@link Controller#updateMailboxList} is called.
     */
    private void waitUntilCallbackCalled() {
        TestUtils.waitUntil("", new TestUtils.Condition() {
            @Override
            public boolean isMet() {
                return mCallback.isAnyMethodCalled() || mMockController.mCalledUpdateMailboxList;
            }
        }, TIMEOUT);
    }

    /**
     * Test: Account is on security hold.
     */
    public void testSecurityHold() throws Throwable {
        final long accountId = createAccount(true);

        createAndStartFinder(accountId, Mailbox.TYPE_INBOX);
        waitUntilCallbackCalled();

        assertFalse(mCallback.mCalledAccountNotFound);
        assertTrue(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);
        assertFalse(mMockController.mCalledUpdateMailboxList);

        assertTrue(mMailboxFinder.isReallyClosedForTest());
    }

    /**
     * Test: Account does not exist.
     */
    public void testAccountNotFound() throws Throwable {
        createAndStartFinder(123456, Mailbox.TYPE_INBOX); // No such account.
        waitUntilCallbackCalled();

        assertTrue(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);
        assertFalse(mMockController.mCalledUpdateMailboxList);

        assertTrue(mMailboxFinder.isReallyClosedForTest());
    }

    /**
     * Test: Mailbox found
     */
    public void testMailboxFound() throws Throwable {
        final long accountId = createAccount(false);
        final long mailboxId = createMailbox(accountId, Mailbox.TYPE_INBOX);

        createAndStartFinder(accountId, Mailbox.TYPE_INBOX);
        waitUntilCallbackCalled();

        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertTrue(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);
        assertFalse(mMockController.mCalledUpdateMailboxList);

        assertEquals(accountId, mCallback.mAccountId);
        assertEquals(mailboxId, mCallback.mMailboxId);

        assertTrue(mMailboxFinder.isReallyClosedForTest());
    }

    /**
     * Common initialization for tests that involves network-lookup.
     */
    private void prepareForNetworkLookupTest(final long accountId) throws Throwable {
        // Look for non-existing mailbox.
        createAndStartFinder(accountId, Mailbox.TYPE_INBOX);
        waitUntilCallbackCalled();

        // Mailbox not found, so the finder should try network-looking up.
        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);

        // Controller.updateMailboxList() should have been called, with the account id.
        assertTrue(mMockController.mCalledUpdateMailboxList);
        assertEquals(accountId, mMockController.mPassedAccountId);

        mMockController.reset();

        assertFalse(mMailboxFinder.isReallyClosedForTest()); // Not closed yet
    }

    /**
     * Test: Account exists, but mailbox doesn't -> Get {@link Controller} to update the mailbox
     * list -> mailbox still doesn't exist.
     */
    public void testMailboxNotFound() throws Throwable {
        final long accountId = createAccount(false);

        prepareForNetworkLookupTest(accountId);

        // Imitate the mCallback...
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mMailboxFinder.getControllerResultsForTest().updateMailboxListCallback(
                        null, accountId, 100);
            }
        });

        // Task should have started, so wait for the response...
        waitUntilCallbackCalled();

        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertTrue(mCallback.mCalledMailboxNotFound);
        assertFalse(mMockController.mCalledUpdateMailboxList);

        assertTrue(mMailboxFinder.isReallyClosedForTest());
    }

    /**
     * Test: Account exists, but mailbox doesn't -> Get {@link Controller} to update the mailbox
     * list -> found mailbox this time.
     */
    public void testMailboxFoundOnNetwork() throws Throwable {
        final long accountId = createAccount(false);

        prepareForNetworkLookupTest(accountId);

        // Create mailbox at this point.
        final long mailboxId = createMailbox(accountId, Mailbox.TYPE_INBOX);

        // Imitate the mCallback...
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                mMailboxFinder.getControllerResultsForTest().updateMailboxListCallback(
                        null, accountId, 100);
            }
        });

        // Task should have started, so wait for the response...
        waitUntilCallbackCalled();

        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertTrue(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);
        assertFalse(mMockController.mCalledUpdateMailboxList);

        assertEquals(accountId, mCallback.mAccountId);
        assertEquals(mailboxId, mCallback.mMailboxId);

        assertTrue(mMailboxFinder.isReallyClosedForTest());
    }

    /**
     * Test: Account exists, but mailbox doesn't -> Get {@link Controller} to update the mailbox
     * list -> network error.
     */
    public void testMailboxNotFoundNetworkError() throws Throwable {
        final long accountId = createAccount(false);

        prepareForNetworkLookupTest(accountId);

        // Imitate the mCallback...
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                // network error.
                mMailboxFinder.getControllerResultsForTest().updateMailboxListCallback(
                        new MessagingException("Network error"), accountId, 0);
            }
        });

        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertTrue(mCallback.mCalledMailboxNotFound);
        assertFalse(mMockController.mCalledUpdateMailboxList);

        assertTrue(mMailboxFinder.isReallyClosedForTest());
    }

    /**
     * Test: updateMailboxListCallback won't respond to update of a non-target account.
     */
    public void testUpdateMailboxListCallbackNonTarget() throws Throwable {
        final long accountId = createAccount(false);

        prepareForNetworkLookupTest(accountId);

        // Callback from Controller, but for a different account.
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                long nonTargetAccountId = accountId + 1;
                mMailboxFinder.getControllerResultsForTest().updateMailboxListCallback(
                        new MessagingException("Network error"), nonTargetAccountId, 0);
            }
        });

        // Nothing happened.
        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);
        assertFalse(mMockController.mCalledUpdateMailboxList);

        assertFalse(mMailboxFinder.isReallyClosedForTest()); // Not closed yet
    }

    /**
     * Test: Mailbox not found (mailbox of different type exists)
     */
    public void testMailboxNotFound2() throws Throwable {
        final long accountId = createAccount(false);
        final long mailboxId = createMailbox(accountId, Mailbox.TYPE_DRAFTS);

        createAndStartFinder(accountId, Mailbox.TYPE_INBOX);
        waitUntilCallbackCalled();

        assertFalse(mCallback.mCalledAccountNotFound);
        assertFalse(mCallback.mCalledAccountSecurityHold);
        assertFalse(mCallback.mCalledMailboxFound);
        assertFalse(mCallback.mCalledMailboxNotFound);
        assertTrue(mMockController.mCalledUpdateMailboxList);

        assertFalse(mMailboxFinder.isReallyClosedForTest()); // Not closed yet -- network lookup.
    }

    /**
     * Test: Call {@link MailboxFinder#startLookup()} twice, which should throw an ISE.
     */
    public void testRunTwice() throws Throwable {
        final long accountId = createAccount(true);

        createAndStartFinder(accountId, Mailbox.TYPE_INBOX);
        try {
            mMailboxFinder.startLookup();
            fail("Expected exception not thrown");
        } catch (IllegalStateException ok) {
        }
    }

    public void testCancel() throws Throwable {
        final long accountId = createAccount(true);

        createAndStartFinder(accountId, Mailbox.TYPE_INBOX);
        mMailboxFinder.cancel();
        assertTrue(mMailboxFinder.isReallyClosedForTest());
    }

    /**
     * A {@link Controller} that remembers if updateMailboxList has been called.
     */
    private static class MockController extends Controller {
        public volatile long mPassedAccountId;
        public volatile boolean mCalledUpdateMailboxList;

        public void reset() {
            mPassedAccountId = -1;
            mCalledUpdateMailboxList = false;
        }

        protected MockController(Context context) {
            super(context);
        }

        @Override
        public void updateMailboxList(long accountId) {
            mCalledUpdateMailboxList = true;
            mPassedAccountId = accountId;
        }
    }

    /**
     * Callback that logs what method is called with what arguments.
     */
    private static class MockCallback implements MailboxFinder.Callback {
        public volatile boolean mCalledAccountNotFound;
        public volatile boolean mCalledAccountSecurityHold;
        public volatile boolean mCalledMailboxFound;
        public volatile boolean mCalledMailboxNotFound;

        public volatile long mAccountId = -1;
        public volatile long mMailboxId = -1;

        public boolean isAnyMethodCalled() {
            return mCalledAccountNotFound || mCalledAccountSecurityHold || mCalledMailboxFound
                    || mCalledMailboxNotFound;
        }

        @Override
        public void onAccountNotFound() {
            mCalledAccountNotFound = true;
        }

        @Override
        public void onAccountSecurityHold(long accountId) {
            mCalledAccountSecurityHold = true;
            mAccountId = accountId;
        }

        @Override
        public void onMailboxFound(long accountId, long mailboxId) {
            mCalledMailboxFound = true;
            mAccountId = accountId;
            mMailboxId = mailboxId;
        }

        @Override
        public void onMailboxNotFound(long accountId) {
            mCalledMailboxNotFound = true;
            mAccountId = accountId;
        }
    }
}
