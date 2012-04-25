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

import android.content.Context;
import android.net.Uri;
import android.test.ProviderTestCase2;

import com.android.email.provider.ContentCache;
import com.android.email.provider.EmailProvider;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;

import java.util.Locale;
import java.util.concurrent.ExecutionException;

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

    private Context mProviderContext;
    private Context mContext;
    private TestController mTestController;


    public ControllerProviderOpsTests() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mProviderContext = getMockContext();
        mContext = getContext();
        mTestController = new TestController(mProviderContext, mContext);
        // Invalidate all caches, since we reset the database for each test
        ContentCache.invalidateAllCaches();
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
        mTestController.cleanupForTest();
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
     * These are strings that should not change per locale.
     */
    public void testGetMailboxServerName() {
        assertEquals("", Controller.getMailboxServerName(mContext, -1));

        assertEquals("Inbox", Controller.getMailboxServerName(mContext, Mailbox.TYPE_INBOX));
        assertEquals("Outbox", Controller.getMailboxServerName(mContext, Mailbox.TYPE_OUTBOX));
        assertEquals("Trash", Controller.getMailboxServerName(mContext, Mailbox.TYPE_TRASH));
        assertEquals("Sent", Controller.getMailboxServerName(mContext, Mailbox.TYPE_SENT));
        assertEquals("Junk", Controller.getMailboxServerName(mContext, Mailbox.TYPE_JUNK));

        // Now try again with translation
        Locale savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.FRANCE);
        assertEquals("Inbox", Controller.getMailboxServerName(mContext, Mailbox.TYPE_INBOX));
        assertEquals("Outbox", Controller.getMailboxServerName(mContext, Mailbox.TYPE_OUTBOX));
        assertEquals("Trash", Controller.getMailboxServerName(mContext, Mailbox.TYPE_TRASH));
        assertEquals("Sent", Controller.getMailboxServerName(mContext, Mailbox.TYPE_SENT));
        assertEquals("Junk", Controller.getMailboxServerName(mContext, Mailbox.TYPE_JUNK));
        Locale.setDefault(savedLocale);
    }

    /**
     * Test of Controller.createMailbox().
     * Sunny day test only - creates a mailbox that does not exist.
     * Does not test duplication, bad accountID, or any other bad input.
     */
    public void testCreateMailbox() {
        // safety check that system mailboxes don't exist ...
        assertEquals(Mailbox.NO_MAILBOX,
                Mailbox.findMailboxOfType(mProviderContext, 1L, Mailbox.TYPE_DRAFTS));
        assertEquals(Mailbox.NO_MAILBOX,
                Mailbox.findMailboxOfType(mProviderContext, 1L, Mailbox.TYPE_SENT));

        long testMailboxId;
        Mailbox testMailbox;

        // Test creating "drafts" mailbox
        mTestController.createMailbox(1L, Mailbox.TYPE_DRAFTS);
        testMailboxId = Mailbox.findMailboxOfType(mProviderContext, 1L, Mailbox.TYPE_DRAFTS);
        assertTrue(testMailboxId != Mailbox.NO_MAILBOX);
        testMailbox = Mailbox.restoreMailboxWithId(mProviderContext, testMailboxId);
        assertNotNull(testMailbox);
        assertEquals(8, testMailbox.mFlags);        // Flags should be "holds mail"
        assertEquals(-1L, testMailbox.mParentKey);  // Parent is off the top-level

        // Test creating "sent" mailbox; same as drafts
        mTestController.createMailbox(1L, Mailbox.TYPE_SENT);
        testMailboxId = Mailbox.findMailboxOfType(mProviderContext, 1L, Mailbox.TYPE_SENT);
        assertTrue(testMailboxId != Mailbox.NO_MAILBOX);
        testMailbox = Mailbox.restoreMailboxWithId(mProviderContext, testMailboxId);
        assertNotNull(testMailbox);
        assertEquals(8, testMailbox.mFlags);        // Flags should be "holds mail"
        assertEquals(-1L, testMailbox.mParentKey);  // Parent is off the top-level
    }

    /**
     * Test of Controller.findOrCreateMailboxOfType().
     * Checks:
     * - finds correctly the ID of existing mailbox
     * - creates non-existing mailbox
     * - creates only once a new mailbox
     * - when accountId or mailboxType are -1, returns NO_MAILBOX
     */
    public void testFindOrCreateMailboxOfType() {
        Account account = ProviderTestUtils.setupAccount("mailboxid", true, mProviderContext);
        long accountId = account.mId;
        Mailbox box = ProviderTestUtils.setupMailbox("box", accountId, false, mProviderContext);
        final int boxType = Mailbox.TYPE_TRASH;
        box.mType = boxType;
        box.save(mProviderContext);
        long boxId = box.mId;

        long testBoxId = mTestController.findOrCreateMailboxOfType(accountId, boxType);

        // check it found the right mailbox id
        assertEquals(boxId, testBoxId);

        long boxId2 = mTestController.findOrCreateMailboxOfType(accountId, Mailbox.TYPE_DRAFTS);
        assertTrue("mailbox created", boxId2 != Mailbox.NO_MAILBOX);
        assertTrue("with different id", testBoxId != boxId2);

        // check it doesn't create twice when existing
        long boxId3 = mTestController.findOrCreateMailboxOfType(accountId, Mailbox.TYPE_DRAFTS);
        assertEquals("don't create if exists", boxId3, boxId2);

        // check invalid aruments
        assertEquals(Mailbox.NO_MAILBOX,
                mTestController.findOrCreateMailboxOfType(-1, Mailbox.TYPE_DRAFTS));
        assertEquals(Mailbox.NO_MAILBOX, mTestController.findOrCreateMailboxOfType(accountId, -1));
    }

    /**
     * Test the "move message" function.
     */
    public void testMoveMessage() throws InterruptedException, ExecutionException {
        Account account1 = ProviderTestUtils.setupAccount("message-move", true, mProviderContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, true, mProviderContext);
        long box1Id = box1.mId;
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", account1Id, true, mProviderContext);
        long box2Id = box2.mId;
        Mailbox boxDest = ProviderTestUtils.setupMailbox("d", account1Id, true, mProviderContext);
        long boxDestId = boxDest.mId;

        Message message1 = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mProviderContext);
        Message message2 = ProviderTestUtils.setupMessage("message2", account1Id, box2Id, false,
                true, mProviderContext);
        long message1Id = message1.mId;
        long message2Id = message2.mId;

        // Because moveMessage runs asynchronously, call get() to force it to complete
        mTestController.moveMessages(new long[] { message1Id, message2Id }, boxDestId).get();

        // now read back a fresh copy and confirm it's in the trash
        assertEquals(boxDestId, EmailContent.Message.restoreMessageWithId(mProviderContext,
                message1Id).mMailboxKey);
        assertEquals(boxDestId, EmailContent.Message.restoreMessageWithId(mProviderContext,
                message2Id).mMailboxKey);
    }

    /**
     * Test the "delete message" function.  Sunny day:
     *    - message/mailbox/account all exist
     *    - trash mailbox exists
     */
    public void testDeleteMessage() {
        Account account1 = ProviderTestUtils.setupAccount("message-delete", true, mProviderContext);
        long account1Id = account1.mId;
        Mailbox box = ProviderTestUtils.setupMailbox("box1", account1Id, true, mProviderContext);
        long boxId = box.mId;

        Mailbox trashBox = ProviderTestUtils.setupMailbox("box2", account1Id, false,
                mProviderContext);
        trashBox.mType = Mailbox.TYPE_TRASH;
        trashBox.save(mProviderContext);
        long trashBoxId = trashBox.mId;

        Mailbox draftBox = ProviderTestUtils.setupMailbox("box3", account1Id, false,
                mProviderContext);
        draftBox.mType = Mailbox.TYPE_DRAFTS;
        draftBox.save(mProviderContext);
        long draftBoxId = draftBox.mId;

        {
            // Case 1: Message in a regular mailbox, account known.
            Message message = ProviderTestUtils.setupMessage("message1", account1Id, boxId, false,
                    true, mProviderContext);
            long messageId = message.mId;

            mTestController.deleteMessageSync(messageId);

            // now read back a fresh copy and confirm it's in the trash
            Message restored = EmailContent.Message.restoreMessageWithId(mProviderContext,
                    messageId);
            assertEquals(trashBoxId, restored.mMailboxKey);
        }

        {
            // Case 2: Already in trash
            Message message = ProviderTestUtils.setupMessage("message3", account1Id, trashBoxId,
                    false, true, mProviderContext);
            long messageId = message.mId;

            mTestController.deleteMessageSync(messageId);

            // Message should be deleted.
            assertNull(EmailContent.Message.restoreMessageWithId(mProviderContext, messageId));
        }

        {
            // Case 3: Draft
            Message message = ProviderTestUtils.setupMessage("message3", account1Id, draftBoxId,
                    false, true, mProviderContext);
            long messageId = message.mId;

            mTestController.deleteMessageSync(messageId);

            // Message should be deleted.
            assertNull(EmailContent.Message.restoreMessageWithId(mProviderContext, messageId));
        }
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

        mTestController.deleteMessageSync(message1Id);

        // now read back a fresh copy and confirm it's in the trash
        Message message1get =
                EmailContent.Message.restoreMessageWithId(mProviderContext, message1Id);

        // check the new mailbox and see if it looks right
        assertFalse(-1 == message1get.mMailboxKey);
        assertFalse(box1Id == message1get.mMailboxKey);
        Mailbox mailbox2get = Mailbox.restoreMailboxWithId(mProviderContext,
                message1get.mMailboxKey);
        assertEquals(Mailbox.TYPE_TRASH, mailbox2get.mType);
    }

    /**
     * Test read/unread flag
     */
    public void testReadUnread() throws InterruptedException, ExecutionException {
        Account account1 = ProviderTestUtils.setupAccount("read-unread", false, mProviderContext);
        account1.mHostAuthRecv
                = ProviderTestUtils.setupHostAuth("read-unread", 0, false, mProviderContext);
        account1.save(mProviderContext);
        long account1Id = account1.mId;
        long box1Id = 2;

        Message message1 =
                ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false, true,
                        mProviderContext);
        long message1Id = message1.mId;

        // test setting to "read"
        mTestController.setMessageRead(message1Id, true).get();
        Message message1get = Message.restoreMessageWithId(mProviderContext, message1Id);
        assertTrue(message1get.mFlagRead);

        // test setting to "unread"
        mTestController.setMessageRead(message1Id, false).get();
        message1get = Message.restoreMessageWithId(mProviderContext, message1Id);
        assertFalse(message1get.mFlagRead);

        // test setting to "read"
        mTestController.setMessageRead(message1Id, true).get();
        message1get = Message.restoreMessageWithId(mProviderContext, message1Id);
        assertTrue(message1get.mFlagRead);
    }

    /**
     * Test favorites flag
     */
    public void testFavorites() throws InterruptedException, ExecutionException {
        Account account1 = ProviderTestUtils.setupAccount("favorites", false, mProviderContext);
        account1.mHostAuthRecv
                = ProviderTestUtils.setupHostAuth("favorites", 0, false, mProviderContext);
        account1.save(mProviderContext);
        long account1Id = account1.mId;
        long box1Id = 2;

        Message message1 =
                ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false, true,
                        mProviderContext);
        long message1Id = message1.mId;

        // test setting to "favorite"
        mTestController.setMessageFavorite(message1Id, true).get();
        Message message1get = Message.restoreMessageWithId(mProviderContext, message1Id);
        assertTrue(message1get.mFlagFavorite);

        // test setting to "not favorite"
        mTestController.setMessageFavorite(message1Id, false).get();
        message1get = Message.restoreMessageWithId(mProviderContext, message1Id);
        assertFalse(message1get.mFlagFavorite);

        // test setting to "favorite"
        mTestController.setMessageFavorite(message1Id, true).get();
        message1get = Message.restoreMessageWithId(mProviderContext, message1Id);
        assertTrue(message1get.mFlagFavorite);
    }

    public void testGetAndDeleteAttachmentMailbox() {
        Mailbox box = mTestController.getAttachmentMailbox();
        assertNotNull(box);
        Mailbox anotherBox = mTestController.getAttachmentMailbox();
        assertNotNull(anotherBox);
        // We should always get back the same Mailbox row
        assertEquals(box.mId, anotherBox.mId);
        // Add two messages to this mailbox
        ProviderTestUtils.setupMessage("message1", 0, box.mId, false, true,
                mProviderContext);
        ProviderTestUtils.setupMessage("message2", 0, box.mId, false, true,
                mProviderContext);
        // Make sure we can find them where they are expected
        assertEquals(2, EmailContent.count(mProviderContext, Message.CONTENT_URI,
                Message.MAILBOX_KEY + "=?", new String[] {Long.toString(box.mId)}));
        // Delete them
        mTestController.deleteAttachmentMessages();
        // Make sure they're gone
        assertEquals(0, EmailContent.count(mProviderContext, Message.CONTENT_URI,
                Message.MAILBOX_KEY + "=?", new String[] {Long.toString(box.mId)}));
    }

    /**
     * Test wiping an account's synced data.  Everything should go, but account & empty inbox.
     * Also ensures that the remaining account and the remaining inbox have cleared their
     * server sync keys, to force refresh eventually.
     */
    public void testWipeSyncedData() {
        Account account1 = ProviderTestUtils.setupAccount("wipe-synced-1", false, mProviderContext);
        account1.mSyncKey = "account-1-sync-key";
        account1.save(mProviderContext);
        long account1Id = account1.mId;
        Mailbox box1 = ProviderTestUtils.setupMailbox("box1", account1Id, false, mProviderContext);
        box1.mType = Mailbox.TYPE_INBOX;
        box1.mSyncKey = "box-1-sync-key";
        box1.save(mProviderContext);
        long box1Id = box1.mId;
        Mailbox box2 = ProviderTestUtils.setupMailbox("box2", account1Id, true, mProviderContext);
        long box2Id = box2.mId;
        // An EAS account mailbox
        Mailbox eas = ProviderTestUtils.setupMailbox("eas", account1Id, false, mProviderContext);
        eas.mType = Mailbox.TYPE_EAS_ACCOUNT_MAILBOX;
        eas.save(mProviderContext);

        Account account2 = ProviderTestUtils.setupAccount("wipe-synced-2", false, mProviderContext);
        account2.mSyncKey = "account-2-sync-key";
        account2.save(mProviderContext);
        long account2Id = account2.mId;
        Mailbox box3 = ProviderTestUtils.setupMailbox("box3", account2Id, false, mProviderContext);
        box3.mSyncKey = "box-3-sync-key";
        box3.mType = Mailbox.TYPE_INBOX;
        box3.save(mProviderContext);
        long box3Id = box3.mId;
        Mailbox box4 = ProviderTestUtils.setupMailbox("box4", account2Id, true, mProviderContext);
        long box4Id = box4.mId;

        // Now populate the 4 non-account boxes with messages
        Message message = ProviderTestUtils.setupMessage("message1", account1Id, box1Id, false,
                true, mProviderContext);
        long message1Id = message.mId;
        message = ProviderTestUtils.setupMessage("message2", account1Id, box2Id, false,
                true, mProviderContext);
        long message2Id = message.mId;
        message = ProviderTestUtils.setupMessage("message3", account2Id, box3Id, false,
                true, mProviderContext);
        long message3Id = message.mId;
        message = ProviderTestUtils.setupMessage("message4", account2Id, box4Id, false,
                true, mProviderContext);
        long message4Id = message.mId;

        // Now wipe account 1's data
        mTestController.deleteSyncedDataSync(account1Id);

        // Confirm:  Mailboxes gone (except account box), all messages gone, account survives
        assertNull(Mailbox.restoreMailboxWithId(mProviderContext, box1Id));
        assertNull(Mailbox.restoreMailboxWithId(mProviderContext, box2Id));
        assertNotNull(Mailbox.restoreMailboxWithId(mProviderContext, eas.mId));
        assertNull(Message.restoreMessageWithId(mProviderContext, message1Id));
        assertNull(Message.restoreMessageWithId(mProviderContext, message2Id));
        account1 = Account.restoreAccountWithId(mProviderContext, account1Id);
        assertNotNull(account1);
        assertNull(account1.mSyncKey);

        // Confirm:  Other account survived
        assertNotNull(Mailbox.restoreMailboxWithId(mProviderContext, box3Id));
        assertNotNull(Mailbox.restoreMailboxWithId(mProviderContext, box4Id));
        assertNotNull(Message.restoreMessageWithId(mProviderContext, message3Id));
        assertNotNull(Message.restoreMessageWithId(mProviderContext, message4Id));
        assertNotNull(Account.restoreAccountWithId(mProviderContext, account2Id));
    }

    public void testLoadMessageFromUri() throws Exception {
        // Create a simple message
        Message msg = new Message();
        String text = "This is some text";
        msg.mText = text;
        String sender = "sender@host.com";
        msg.mFrom = sender;
        // Save this away
        msg.save(mProviderContext);

        Uri fileUri = ProviderTestUtils.createTempEmlFile(mProviderContext, msg,
                mContext.getFilesDir());

        // Load the message via Controller and a Uri
        Message loadedMsg = mTestController.loadMessageFromUri(fileUri);

        // Check server id, mailbox key, account key, and from
        assertNotNull(loadedMsg);
        assertTrue(loadedMsg.mServerId.startsWith(Controller.ATTACHMENT_MESSAGE_UID_PREFIX));
        Mailbox box = mTestController.getAttachmentMailbox();
        assertNotNull(box);
        assertEquals(box.mId, loadedMsg.mMailboxKey);
        assertEquals(0, loadedMsg.mAccountKey);
        assertEquals(loadedMsg.mFrom, sender);
        // Check body text
        String loadedMsgText = Body.restoreBodyTextWithMessageId(mProviderContext, loadedMsg.mId);
        assertEquals(text, loadedMsgText);
    }

    /**
     * Create a simple HostAuth with protocol
     */
    private HostAuth setupSimpleHostAuth(String protocol) {
        HostAuth hostAuth = new HostAuth();
        hostAuth.mProtocol = protocol;
        return hostAuth;
    }

    public void testIsMessagingController() {
        Account account1 = ProviderTestUtils.setupAccount("account1", false,
                mProviderContext);
        account1.mHostAuthRecv = setupSimpleHostAuth("eas");
        account1.save(mProviderContext);
        assertFalse(mTestController.isMessagingController(account1));
        Account account2 = ProviderTestUtils.setupAccount("account2", false,
                mProviderContext);
        account2.mHostAuthRecv = setupSimpleHostAuth("imap");
        account2.save(mProviderContext);
        assertTrue(mTestController.isMessagingController(account2));
        Account account3 = ProviderTestUtils.setupAccount("account3", false,
                mProviderContext);
        account3.mHostAuthRecv = setupSimpleHostAuth("pop3");
        account3.save(mProviderContext);
        assertTrue(mTestController.isMessagingController(account3));
        Account account4 = ProviderTestUtils.setupAccount("account4", false,
                mProviderContext);
        account4.mHostAuthRecv = setupSimpleHostAuth("smtp");
        account4.save(mProviderContext);
        assertFalse(mTestController.isMessagingController(account4));
        // There should be values for all of these accounts in the legacy map
        assertNotNull(mTestController.mLegacyControllerMap.get(account1.mId));
        assertNotNull(mTestController.mLegacyControllerMap.get(account2.mId));
        assertNotNull(mTestController.mLegacyControllerMap.get(account3.mId));
        assertNotNull(mTestController.mLegacyControllerMap.get(account4.mId));
        // The map should have the expected values
        assertFalse(mTestController.mLegacyControllerMap.get(account1.mId));
        assertTrue(mTestController.mLegacyControllerMap.get(account2.mId));
        assertTrue(mTestController.mLegacyControllerMap.get(account3.mId));
        assertFalse(mTestController.mLegacyControllerMap.get(account4.mId));
        // This second pass should pull values from the cache
        assertFalse(mTestController.isMessagingController(account1));
        assertTrue(mTestController.isMessagingController(account2));
        assertTrue(mTestController.isMessagingController(account3));
        assertFalse(mTestController.isMessagingController(account4));
    }

    /**
     * TODO: releasing associated data (e.g. attachments, embedded images)
     */
}
