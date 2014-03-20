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

package com.android.emailcommon.provider;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Parcel;
import android.test.MoreAsserts;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;

import com.android.email.provider.ContentCache;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.EmailContent.MessageColumns;
import com.android.emailcommon.utility.Utility;

import java.util.Arrays;

/**
 * Unit tests for the Mailbox inner class.
 * These tests must be locally complete - no server(s) required.
 */
@Suppress
@SmallTest
public class MailboxTests extends ProviderTestCase2<EmailProvider> {
    private static final String TEST_DISPLAY_NAME = "display-name";
    private static final String TEST_PARENT_SERVER_ID = "parent-server-id";
    private static final String TEST_SERVER_ID = "server-id";
    private static final String TEST_SYNC_KEY = "sync-key";
    private static final String TEST_SYNC_STATUS = "sync-status";

    private Context mMockContext;
    private EmailProvider mProvider;

    public MailboxTests() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
        mProvider = getProvider();
        // Invalidate all caches, since we reset the database for each test
        ContentCache.invalidateAllCaches();
    }

    //////////////////////////////////////////////////////////
    ////// Utility methods
    //////////////////////////////////////////////////////////

    /** Returns the number of messages in a mailbox. */
    private int getMessageCount(long mailboxId) {
        return Utility.getFirstRowInt(mMockContext,
                ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailboxId),
                new String[] {MailboxColumns.MESSAGE_COUNT}, null, null, null, 0);
    }

    /** Creates a new message. */
    private static Message createMessage(Context c, Mailbox b, boolean starred, boolean read,
            int flagLoaded) {
        Message message = ProviderTestUtils.setupMessage(
                "1", b.mAccountKey, b.mId, true, false, c, starred, read);
        message.mFlagLoaded = flagLoaded;
        message.save(c);
        return message;
    }

    //////////////////////////////////////////////////////////
    ////// The tests
    //////////////////////////////////////////////////////////

    /**
     * Test simple mailbox save/retrieve
     */
    public void testSave() {
        final Context c = mMockContext;

        Account account1 = ProviderTestUtils.setupAccount("mailbox-save", true, c);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, c);
        long box1Id = box1.mId;

        Mailbox box2 = Mailbox.restoreMailboxWithId(c, box1Id);

        ProviderTestUtils.assertMailboxEqual("testMailboxSave", box1, box2);
    }

    /**
     * Test delete mailbox
     */
    public void testDelete() {
        final Context c = mMockContext;

        Account account1 = ProviderTestUtils.setupAccount("mailbox-delete", true, c);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, c);
        long box1Id = box1.mId;
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", account1Id, true, c);
        long box2Id = box2.mId;

        String selection = EmailContent.MailboxColumns.ACCOUNT_KEY + "=?";
        String[] selArgs = new String[] { String.valueOf(account1Id) };

        // make sure there are two mailboxes
        int numBoxes = EmailContent.count(c, Mailbox.CONTENT_URI, selection, selArgs);
        assertEquals(2, numBoxes);

        // now delete one of them
        Uri uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, box1Id);
        c.getContentResolver().delete(uri, null, null);

        // make sure there's only one mailbox now
        numBoxes = EmailContent.count(c, Mailbox.CONTENT_URI, selection, selArgs);
        assertEquals(1, numBoxes);

        // now delete the other one
        uri = ContentUris.withAppendedId(Mailbox.CONTENT_URI, box2Id);
        c.getContentResolver().delete(uri, null, null);

        // make sure there are no mailboxes now
        numBoxes = EmailContent.count(c, Mailbox.CONTENT_URI, selection, selArgs);
        assertEquals(0, numBoxes);
    }

    public void testGetMailboxType() {
        final Context c = mMockContext;

        Account a = ProviderTestUtils.setupAccount("acct1", true, c);
        Mailbox bi = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox bm = ProviderTestUtils.setupMailbox("b2", a.mId, true, c, Mailbox.TYPE_MAIL);

        assertEquals(Mailbox.TYPE_INBOX, Mailbox.getMailboxType(c, bi.mId));
        assertEquals(Mailbox.TYPE_MAIL, Mailbox.getMailboxType(c, bm.mId));
        assertEquals(-1, Mailbox.getMailboxType(c, 999999)); // mailbox not found
    }

    public void testGetDisplayName() {
        final Context c = mMockContext;

        Account a = ProviderTestUtils.setupAccount("acct1", true, c);
        Mailbox bi = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox bm = ProviderTestUtils.setupMailbox("b2", a.mId, true, c, Mailbox.TYPE_MAIL);

        assertEquals("b1", Mailbox.getDisplayName(c, bi.mId));
        assertEquals("b2", Mailbox.getDisplayName(c, bm.mId));
        assertEquals(null, Mailbox.getDisplayName(c, 999999)); // mailbox not found
    }

    public void testIsRefreshable() {
        final Context c = mMockContext;

        Account a = ProviderTestUtils.setupAccount("acct1", true, c);
        Mailbox bi = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox bm = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_MAIL);
        Mailbox bd = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_DRAFTS);
        Mailbox bo = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_OUTBOX);

        assertTrue(Mailbox.isRefreshable(c, bi.mId));
        assertTrue(Mailbox.isRefreshable(c, bm.mId));
        assertFalse(Mailbox.isRefreshable(c, bd.mId));
        assertFalse(Mailbox.isRefreshable(c, bo.mId));

        // No such mailbox
        assertFalse(Mailbox.isRefreshable(c, 9999999));

        // Magic mailboxes can't be refreshed.
        assertFalse(Mailbox.isRefreshable(c, Mailbox.QUERY_ALL_DRAFTS));
        assertFalse(Mailbox.isRefreshable(c, Mailbox.QUERY_ALL_INBOXES));
    }

    public void testCanMoveFrom() {
        final Context c = mMockContext;

        Account a = ProviderTestUtils.setupAccount("acct1", true, c);
        Mailbox bi = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox bm = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_MAIL);
        Mailbox bd = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_DRAFTS);
        Mailbox bo = ProviderTestUtils.setupMailbox("b1", a.mId, true, c, Mailbox.TYPE_OUTBOX);

        assertTrue(bi.canHaveMessagesMoved());
        assertTrue(bm.canHaveMessagesMoved());
        assertFalse(bd.canHaveMessagesMoved());
        assertFalse(bo.canHaveMessagesMoved());
    }

    public void testGetMailboxForMessageId() {
        final Context c = mMockContext;
        Mailbox b1 = ProviderTestUtils.setupMailbox("box1", 1, true, c, Mailbox.TYPE_MAIL);
        Mailbox b2 = ProviderTestUtils.setupMailbox("box2", 1, true, c, Mailbox.TYPE_MAIL);
        Message m1 = ProviderTestUtils.setupMessage("1", b1.mAccountKey, b1.mId,
                true, true, c, false, false);
        Message m2 = ProviderTestUtils.setupMessage("1", b2.mAccountKey, b2.mId,
                true, true, c, false, false);
        ProviderTestUtils.assertMailboxEqual("x", b1, Mailbox.getMailboxForMessageId(c, m1.mId));
        ProviderTestUtils.assertMailboxEqual("x", b2, Mailbox.getMailboxForMessageId(c, m2.mId));
    }

    public void testRestoreMailboxWithId() {
        final Context c = mMockContext;
        Mailbox testMailbox;

        testMailbox = ProviderTestUtils.setupMailbox("box1", 1, true, c, Mailbox.TYPE_MAIL);
        ProviderTestUtils.assertMailboxEqual(
                "x", testMailbox, Mailbox.restoreMailboxWithId(c, testMailbox.mId));
        testMailbox = ProviderTestUtils.setupMailbox("box2", 1, true, c, Mailbox.TYPE_MAIL);
        ProviderTestUtils.assertMailboxEqual(
                "x", testMailbox, Mailbox.restoreMailboxWithId(c, testMailbox.mId));
        // Unknown IDs
        assertNull(Mailbox.restoreMailboxWithId(c, 8));
        assertNull(Mailbox.restoreMailboxWithId(c, -1));
        assertNull(Mailbox.restoreMailboxWithId(c, Long.MAX_VALUE));
    }

    public void testRestoreMailboxForPath() {
        final Context c = mMockContext;
        Mailbox testMailbox;
        testMailbox = ProviderTestUtils.setupMailbox("a/b/c/box", 1, true, c, Mailbox.TYPE_MAIL);
        ProviderTestUtils.assertMailboxEqual(
                "x", testMailbox, Mailbox.restoreMailboxForPath(c, 1, "a/b/c/box"));
        // Same name, different account; no match
        assertNull(Mailbox.restoreMailboxForPath(c, 2, "a/b/c/box"));
        // Substring; no match
        assertNull(Mailbox.restoreMailboxForPath(c, 1, "a/b/c"));
        // Wild cards not supported; no match
        assertNull(Mailbox.restoreMailboxForPath(c, 1, "a/b/c/%"));
    }

    public void testFindMailboxOfType() {
        final Context context = mMockContext;

        // Create two accounts and a variety of mailbox types
        Account acct1 = ProviderTestUtils.setupAccount("acct1", true, context);
        Mailbox acct1Inbox =
            ProviderTestUtils.setupMailbox("Inbox1", acct1.mId, true, context, Mailbox.TYPE_INBOX);
        Mailbox acct1Calendar =
            ProviderTestUtils.setupMailbox("Cal1", acct1.mId, true, context, Mailbox.TYPE_CALENDAR);
        Mailbox acct1Contacts =
            ProviderTestUtils.setupMailbox("Con1", acct1.mId, true, context, Mailbox.TYPE_CONTACTS);
        Account acct2 = ProviderTestUtils.setupAccount("acct1", true, context);
        Mailbox acct2Inbox =
            ProviderTestUtils.setupMailbox("Inbox2", acct2.mId, true, context, Mailbox.TYPE_INBOX);
        Mailbox acct2Calendar =
            ProviderTestUtils.setupMailbox("Cal2", acct2.mId, true, context, Mailbox.TYPE_CALENDAR);
        Mailbox acct2Contacts =
            ProviderTestUtils.setupMailbox("Con2", acct2.mId, true, context, Mailbox.TYPE_CONTACTS);

        // Check that we can find them by type
        assertEquals(acct1Inbox.mId,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_INBOX));
        assertEquals(acct2Inbox.mId,
                Mailbox.findMailboxOfType(context, acct2.mId, Mailbox.TYPE_INBOX));
        assertEquals(acct1Calendar.mId,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_CALENDAR));
        assertEquals(acct2Calendar.mId,
                Mailbox.findMailboxOfType(context, acct2.mId, Mailbox.TYPE_CALENDAR));
        assertEquals(acct1Contacts.mId,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_CONTACTS));
        assertEquals(acct2Contacts.mId,
                Mailbox.findMailboxOfType(context, acct2.mId, Mailbox.TYPE_CONTACTS));

        // Check that nonexistent mailboxes are not returned
        assertEquals(Mailbox.NO_MAILBOX,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_DRAFTS));
        assertEquals(Mailbox.NO_MAILBOX,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_OUTBOX));

        // delete account 1 and confirm no mailboxes are returned
        context.getContentResolver().delete(
                ContentUris.withAppendedId(Account.CONTENT_URI, acct1.mId), null, null);
        assertEquals(Mailbox.NO_MAILBOX,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_INBOX));
        assertEquals(Mailbox.NO_MAILBOX,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_CALENDAR));
        assertEquals(Mailbox.NO_MAILBOX,
                Mailbox.findMailboxOfType(context, acct1.mId, Mailbox.TYPE_CONTACTS));
    }

    public void testRestoreMailboxOfType() {
        final Context context = getMockContext();

        // Create two accounts and a variety of mailbox types
        Account acct1 = ProviderTestUtils.setupAccount("acct1", true, context);
        Mailbox acct1Inbox =
            ProviderTestUtils.setupMailbox("Inbox1", acct1.mId, true, context, Mailbox.TYPE_INBOX);
        Mailbox acct1Calendar =
            ProviderTestUtils.setupMailbox("Cal1", acct1.mId, true, context, Mailbox.TYPE_CALENDAR);
        Mailbox acct1Contacts =
            ProviderTestUtils.setupMailbox("Con1", acct1.mId, true, context, Mailbox.TYPE_CONTACTS);
        Account acct2 =ProviderTestUtils.setupAccount("acct1", true, context);
        Mailbox acct2Inbox =
            ProviderTestUtils.setupMailbox("Inbox2", acct2.mId, true, context, Mailbox.TYPE_INBOX);
        Mailbox acct2Calendar =
            ProviderTestUtils.setupMailbox("Cal2", acct2.mId, true, context, Mailbox.TYPE_CALENDAR);
        Mailbox acct2Contacts =
            ProviderTestUtils.setupMailbox("Con2", acct2.mId, true, context, Mailbox.TYPE_CONTACTS);

        // Check that we can find them by type
        ProviderTestUtils.assertMailboxEqual("testRestoreMailboxOfType", acct1Inbox,
                Mailbox.restoreMailboxOfType(context, acct1.mId, Mailbox.TYPE_INBOX));
        ProviderTestUtils.assertMailboxEqual("testRestoreMailboxOfType", acct2Inbox,
                Mailbox.restoreMailboxOfType(context, acct2.mId, Mailbox.TYPE_INBOX));
        ProviderTestUtils.assertMailboxEqual("testRestoreMailboxOfType", acct1Calendar,
                Mailbox.restoreMailboxOfType(context, acct1.mId, Mailbox.TYPE_CALENDAR));
        ProviderTestUtils.assertMailboxEqual("testRestoreMailboxOfType", acct2Calendar,
                Mailbox.restoreMailboxOfType(context, acct2.mId, Mailbox.TYPE_CALENDAR));
        ProviderTestUtils.assertMailboxEqual("testRestoreMailboxOfType", acct1Contacts,
                Mailbox.restoreMailboxOfType(context, acct1.mId, Mailbox.TYPE_CONTACTS));
        ProviderTestUtils.assertMailboxEqual("testRestoreMailboxOfType", acct2Contacts,
                Mailbox.restoreMailboxOfType(context, acct2.mId, Mailbox.TYPE_CONTACTS));
    }

    /**
     * Test for the message count triggers (insert/delete/move mailbox), and also
     * {@link EmailProvider#recalculateMessageCount}.
     *
     * It also covers:
     * - {@link Message#getFavoriteMessageCount(Context)}
     * - {@link Message#getFavoriteMessageCount(Context, long)}
     */
    public void testMessageCount() {
        final Context c = mMockContext;

        // Create 2 accounts
        Account a1 = ProviderTestUtils.setupAccount("holdflag-1", true, c);
        Account a2 = ProviderTestUtils.setupAccount("holdflag-2", true, c);

        // Create 2 mailboxes for each account
        Mailbox b1 = ProviderTestUtils.setupMailbox("box1", a1.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox b2 = ProviderTestUtils.setupMailbox("box2", a1.mId, true, c, Mailbox.TYPE_OUTBOX);
        Mailbox b3 = ProviderTestUtils.setupMailbox("box3", a2.mId, true, c, Mailbox.TYPE_INBOX);
        Mailbox b4 = ProviderTestUtils.setupMailbox("box4", a2.mId, true, c, Mailbox.TYPE_OUTBOX);
        Mailbox bt = ProviderTestUtils.setupMailbox("boxT", a2.mId, true, c, Mailbox.TYPE_TRASH);

        // 0. Check the initial values, just in case.

        assertEquals(0, getMessageCount(b1.mId));
        assertEquals(0, getMessageCount(b2.mId));
        assertEquals(0, getMessageCount(b3.mId));
        assertEquals(0, getMessageCount(b4.mId));
        assertEquals(0, getMessageCount(bt.mId));

        assertEquals(0, Message.getFavoriteMessageCount(c));
        assertEquals(0, Message.getFavoriteMessageCount(c, a1.mId));
        assertEquals(0, Message.getFavoriteMessageCount(c, a2.mId));

        // 1. Test for insert triggers.

        // Create some messages
        // b1 (account 1, inbox): 1 message, including 1 starred
        Message m11 = createMessage(c, b1, true, false, Message.FLAG_LOADED_COMPLETE);

        // b2 (account 1, outbox): 2 message, including 1 starred
        Message m21 = createMessage(c, b2, false, false, Message.FLAG_LOADED_COMPLETE);
        Message m22 = createMessage(c, b2, true, true, Message.FLAG_LOADED_COMPLETE);

        // b3 (account 2, inbox): 3 message, including 1 starred
        Message m31 = createMessage(c, b3, false, false, Message.FLAG_LOADED_COMPLETE);
        Message m32 = createMessage(c, b3, false, false, Message.FLAG_LOADED_COMPLETE);
        Message m33 = createMessage(c, b3, true, true, Message.FLAG_LOADED_COMPLETE);

        // b4 (account 2, outbox) has no messages.

        // bt (account 2, trash) has 3 messages, including 2 starred
        Message mt1 = createMessage(c, bt, true, false, Message.FLAG_LOADED_COMPLETE);
        Message mt2 = createMessage(c, bt, true, false, Message.FLAG_LOADED_COMPLETE);
        Message mt3 = createMessage(c, bt, false, false, Message.FLAG_LOADED_COMPLETE);

        // Check message counts
        assertEquals(1, getMessageCount(b1.mId));
        assertEquals(2, getMessageCount(b2.mId));
        assertEquals(3, getMessageCount(b3.mId));
        assertEquals(0, getMessageCount(b4.mId));
        assertEquals(3, getMessageCount(bt.mId));

        // Check the simple counting methods.
        assertEquals(3, Message.getFavoriteMessageCount(c)); // excludes starred in trash
        assertEquals(2, Message.getFavoriteMessageCount(c, a1.mId));
        assertEquals(1, Message.getFavoriteMessageCount(c, a2.mId)); // excludes starred in trash

        // 2. Check the "move mailbox" trigger.

        // Move m32 (in mailbox 3) to mailbox 4.
        ContentValues values = new ContentValues();
        values.put(MessageColumns.MAILBOX_KEY, b4.mId);

        getProvider().update(Message.CONTENT_URI, values, EmailContent.ID_SELECTION,
                new String[] {"" + m32.mId});

        // Check message counts
        assertEquals(1, getMessageCount(b1.mId));
        assertEquals(2, getMessageCount(b2.mId));
        assertEquals(2, getMessageCount(b3.mId));
        assertEquals(1, getMessageCount(b4.mId));

        // 3. Check the delete trigger.

        // Delete m11 (in mailbox 1)
        getProvider().delete(Message.CONTENT_URI, EmailContent.ID_SELECTION,
                new String[] {"" + m11.mId});
        // Delete m21 (in mailbox 2)
        getProvider().delete(Message.CONTENT_URI, EmailContent.ID_SELECTION,
                new String[] {"" + m21.mId});

        // Check message counts
        assertEquals(0, getMessageCount(b1.mId));
        assertEquals(1, getMessageCount(b2.mId));
        assertEquals(2, getMessageCount(b3.mId));
        assertEquals(1, getMessageCount(b4.mId));
    }

    private Mailbox buildTestMailbox(String serverId) {
        return buildTestMailbox(serverId, null);
    }

    private Mailbox buildTestMailbox(String serverId, String name) {
        Mailbox testMailbox = new Mailbox();
        testMailbox.mServerId = serverId;
        testMailbox.mDisplayName = (name == null) ? TEST_DISPLAY_NAME : name;
        testMailbox.mParentServerId = TEST_PARENT_SERVER_ID;
        testMailbox.mSyncKey = TEST_SYNC_KEY;
        testMailbox.mSyncStatus = TEST_SYNC_STATUS;
        testMailbox.mAccountKey = 1L;
        testMailbox.mDelimiter = '/';
        testMailbox.mFlags = 2;
        testMailbox.mFlagVisible = true;
        testMailbox.mParentKey = 3L;
        testMailbox.mSyncInterval = 4;
        testMailbox.mSyncLookback = 5;
        testMailbox.mSyncTime = 6L;
        testMailbox.mType = 7;
        testMailbox.mLastTouchedTime = 10L;

        return testMailbox;
    }

    public void testGetHashes() {
        final Context c = mMockContext;
        Mailbox testMailbox = buildTestMailbox(TEST_SERVER_ID);
        testMailbox.save(c);

        Object[] testHash;
        testHash = new Object[] {
                testMailbox.mId, TEST_DISPLAY_NAME, TEST_SERVER_ID,
                TEST_PARENT_SERVER_ID, 1L /*mAccountKey*/, 7 /*mType */,
                (int)'/' /*mDelimiter */, TEST_SYNC_KEY, 5 /*mSyncLookback*/,
                4 /*mSyncInterval*/,  6L /*mSyncTime*/, true /*mFlagVisible*/, 2 /*mFlags*/,
                8 /*mVisibleLimit*/, TEST_SYNC_STATUS, 3L /*mParentKey*/, 9L /*mLastSeen*/,
                10L /*mLastTouchedTime*/,
        };
        MoreAsserts.assertEquals(testHash, testMailbox.getHashes());

        // Verify null checks happen correctly
        testMailbox.mDisplayName = null;
        testMailbox.mParentServerId = null;
        testMailbox.mServerId = null;
        testMailbox.mSyncKey = null;
        testMailbox.mSyncStatus = null;
        testMailbox.mFlagVisible = false;

        testHash = new Object[] {
                testMailbox.mId, null /*mDisplayname*/, null /*mServerId*/,
                null /*mParentServerId*/, 1L /*mAccountKey*/, 7 /*mType */,
                (int)'/' /*mDelimiter */, null /*mSyncKey*/, 5 /*mSyncLookback*/,
                4 /*mSyncInterval*/,  6L /*mSyncTime*/, false /*mFlagVisible*/, 2 /*mFlags*/,
                8 /*mVisibleLimit*/, null /*mSyncStatus*/, 3L /*mParentKey*/, 9L /*mLastSeen*/,
                10L /*mLastTouchedTime*/,
        };
        MoreAsserts.assertEquals(testHash, testMailbox.getHashes());
    }

    public void testParcelling() {
        Mailbox original = buildTestMailbox("serverId", "display name for mailbox");

        Parcel p = Parcel.obtain();
        original.writeToParcel(p, 0 /* flags */);

        // Reset.
        p.setDataPosition(0);

        Mailbox unparcelled = Mailbox.CREATOR.createFromParcel(p);
        MoreAsserts.assertEquals(original.getHashes(), unparcelled.getHashes());

        Mailbox phony = buildTestMailbox("different ID", "display name for mailbox");
        assertFalse(Arrays.equals(phony.getHashes(), unparcelled.getHashes()));

        p.recycle();
    }

    public void testParcellingWithPartialMailbox() {
        Mailbox unpopulated = new Mailbox();
        unpopulated.mDisplayName = "the only thing filled in for some reason";

        Parcel p = Parcel.obtain();
        unpopulated.writeToParcel(p, 0 /* flags */);

        // Reset.
        p.setDataPosition(0);

        Mailbox unparcelled = Mailbox.CREATOR.createFromParcel(p);
        MoreAsserts.assertEquals(unpopulated.getHashes(), unparcelled.getHashes());

        p.recycle();
    }
}

