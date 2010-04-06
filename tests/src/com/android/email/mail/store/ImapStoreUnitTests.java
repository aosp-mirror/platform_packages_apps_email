/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.email.mail.store;

import com.android.email.mail.Address;
import com.android.email.mail.FetchProfile;
import com.android.email.mail.Flag;
import com.android.email.mail.Folder;
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Part;
import com.android.email.mail.Transport;
import com.android.email.mail.Folder.FolderType;
import com.android.email.mail.Folder.OpenMode;
import com.android.email.mail.Message.RecipientType;
import com.android.email.mail.internet.BinaryTempFileBody;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.mail.internet.TextBody;
import com.android.email.mail.store.ImapStore.ImapMessage;
import com.android.email.mail.transport.DiscourseLogger;
import com.android.email.mail.transport.MockTransport;

import android.test.AndroidTestCase;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/**
 * This is a series of unit tests for the ImapStore class.  These tests must be locally
 * complete - no server(s) required.
 *
 * To run these tests alone, use:
 *   $ runtest -c com.android.email.mail.store.ImapStoreUnitTests email
 */
@SmallTest
public class ImapStoreUnitTests extends AndroidTestCase {
    private final static String[] NO_REPLY = new String[0];
    
    /* These values are provided by setUp() */
    private ImapStore mStore = null;
    private ImapStore.ImapFolder mFolder = null;

    private int mNextTag;
    
    /**
     * Setup code.  We generate a lightweight ImapStore and ImapStore.ImapFolder.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        // These are needed so we can get at the inner classes
        mStore = (ImapStore) ImapStore.newInstance("imap://user:password@server:999",
                getContext(), null);
        mFolder = (ImapStore.ImapFolder) mStore.getFolder("INBOX");
        
        // This is needed for parsing mime messages
        BinaryTempFileBody.setTempDirectory(this.getContext().getCacheDir());
    }

    /**
     * Confirms simple non-SSL non-TLS login
     */
    public void testSimpleLogin() throws MessagingException {
        
        MockTransport mockTransport = openAndInjectMockTransport();
        
        // try to open it
        setupOpenFolder(mockTransport);
        mFolder.open(OpenMode.READ_WRITE, null);
        
        // TODO: inject specific facts in the initial folder SELECT and check them here
    }
    
    /**
     * TODO: Test with SSL negotiation (faked)
     * TODO: Test with SSL required but not supported
     * TODO: Test with TLS negotiation (faked)
     * TODO: Test with TLS required but not supported
     * TODO: Test calling getMessageCount(), getMessages(), etc.
     */

    /**
     * Test the generation of the IMAP ID keys
     */
    public void testImapIdBasic() {
        // First test looks at operation of the outer API - we don't control any of the
        // values;  Just look for basic results.

        // Strings we'll expect to find:
        //   name            Android package name of the program
        //   os              "android"
        //   os-version      "version; build-id"
        //   vendor          Vendor of the client/server
        //   x-android-device-model Model (Optional, so not tested here)
        //   x-android-net-operator Carrier (Unreliable, so not tested here)
        //   AGUID           A device+account UID
        String id = mStore.getImapId(getContext(), "user-name", "host-name", "IMAP4rev1 STARTTLS");
        HashMap<String, String> map = tokenizeImapId(id);
        assertEquals(getContext().getPackageName(), map.get("name"));
        assertEquals("android", map.get("os"));
        assertNotNull(map.get("os-version"));
        assertNotNull(map.get("vendor"));
        assertNotNull(map.get("AGUID"));

        // Next, use the inner API to confirm operation of a couple of
        // variants for release and non-release devices.

        // simple API check - non-REL codename, non-empty version
        id = mStore.makeCommonImapId("packageName", "version", "codeName",
                "model", "id", "vendor", "network-operator");
        map = tokenizeImapId(id);
        assertEquals("packageName", map.get("name"));
        assertEquals("android", map.get("os"));
        assertEquals("version; id", map.get("os-version"));
        assertEquals("vendor", map.get("vendor"));
        assertEquals(null, map.get("x-android-device-model"));
        assertEquals("network-operator", map.get("x-android-mobile-net-operator"));
        assertEquals(null, map.get("AGUID"));

        // simple API check - codename is REL, so use model name.
        // also test empty version => 1.0 and empty network operator
        id = mStore.makeCommonImapId("packageName", "", "REL",
                "model", "id", "vendor", "");
        map = tokenizeImapId(id);
        assertEquals("packageName", map.get("name"));
        assertEquals("android", map.get("os"));
        assertEquals("1.0; id", map.get("os-version"));
        assertEquals("vendor", map.get("vendor"));
        assertEquals("model", map.get("x-android-device-model"));
        assertEquals(null, map.get("x-android-mobile-net-operator"));
        assertEquals(null, map.get("AGUID"));
    }

    /**
     * Test of the internal generator for IMAP ID strings, specifically looking for proper
     * filtering of illegal values.  This is required because we cannot necessarily trust
     * the external sources of some of this data (e.g. release labels).
     *
     * The (somewhat arbitrary) legal values are:  a-z A-Z 0-9 - _ + = ; : . , / <space>
     * The most important goal of the filters is to keep out control chars, (, ), and "
     */
    public void testImapIdFiltering() {
        String id = mStore.makeCommonImapId("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
                "0123456789", "codeName",
                "model", "-_+=;:.,// ",
                "v(e)n\"d\ro\nr",           // look for bad chars stripped out, leaving OK chars
                "()\"");                    // look for bad chars stripped out, leaving nothing
        HashMap<String, String> map = tokenizeImapId(id);

        assertEquals("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ", map.get("name"));
        assertEquals("0123456789; -_+=;:.,// ", map.get("os-version"));
        assertEquals("vendor", map.get("vendor"));
        assertNull(map.get("x-android-mobile-net-operator"));
    }

    /**
     * Test that IMAP ID uid's are per-username
     */
    public void testImapIdDeviceId() throws MessagingException {
        ImapStore store1a = (ImapStore) ImapStore.newInstance("imap://user1:password@server:999",
                getContext(), null);
        ImapStore store1b = (ImapStore) ImapStore.newInstance("imap://user1:password@server:999",
                getContext(), null);
        ImapStore store2 = (ImapStore) ImapStore.newInstance("imap://user2:password@server:999",
                getContext(), null);

        String id1a = mStore.getImapId(getContext(), "user1", "host-name", "IMAP4rev1");
        String id1b = mStore.getImapId(getContext(), "user1", "host-name", "IMAP4rev1");
        String id2 = mStore.getImapId(getContext(), "user2", "host-name", "IMAP4rev1");

        String uid1a = tokenizeImapId(id1a).get("AGUID");
        String uid1b = tokenizeImapId(id1b).get("AGUID");
        String uid2 = tokenizeImapId(id2).get("AGUID");

        assertEquals(uid1a, uid1b);
        MoreAsserts.assertNotEqual(uid1a, uid2);
    }

    /**
     * Helper to break an IMAP ID string into keys & values
     * @param id the IMAP Id string (the part inside the parens)
     * @return a map of key/value pairs
     */
    private HashMap<String, String> tokenizeImapId(String id) {
        // Instead of a true tokenizer, we'll use double-quote as the split.
        // We can's use " " because there may be spaces inside the values. 
        String[] elements = id.split("\"");
        HashMap<String, String> map = new HashMap<String, String>();
        for (int i = 0; i < elements.length; ) {
            // Because we split at quotes, we expect to find:
            // [i] = null or one or more spaces
            // [i+1] = key
            // [i+2] = one or more spaces
            // [i+3] = value
            map.put(elements[i+1], elements[i+3]);
            i += 4;
        }
        return map;
    }

    /**
     * Test non-NIL server response to IMAP ID.  We should simply ignore it.
     */
    public void testServerId() throws MessagingException {
        MockTransport mockTransport = openAndInjectMockTransport();
        
        // try to open it
        setupOpenFolder(mockTransport, new String[] {
                "* ID (\"name\" \"Cyrus\" \"version\" \"1.5\"" +
                " \"os\" \"sunos\" \"os-version\" \"5.5\"" +
                " \"support-url\" \"mailto:cyrus-bugs+@andrew.cmu.edu\")",
                "OK"}, "READ-WRITE");
        mFolder.open(OpenMode.READ_WRITE, null);
    }

    /**
     * Test OK response to IMAP ID with crummy text afterwards too.
     */
    public void testImapIdOkParsing() throws MessagingException {
        MockTransport mockTransport = openAndInjectMockTransport();
        
        // try to open it
        setupOpenFolder(mockTransport, new String[] {
                "* ID NIL",
                "OK [ID] bad-char-%"}, "READ-WRITE");
        mFolder.open(OpenMode.READ_WRITE, null);
    }
    
    /**
     * Test BAD response to IMAP ID - also with bad parser chars
     */
    public void testImapIdBad() throws MessagingException {
        MockTransport mockTransport = openAndInjectMockTransport();
        
        // try to open it
        setupOpenFolder(mockTransport, new String[] {
                "BAD unknown command bad-char-%"}, "READ-WRITE");
        mFolder.open(OpenMode.READ_WRITE, null);
    }
    
    /** 
     * Confirms that ImapList object correctly returns an appropriate Date object
     * without throwning MessagingException when getKeyedDate() is called.
     *
     * Here, we try a same test twice using two locales, Locale.US and the other.
     * ImapList uses Locale class internally, and as a result, there's a
     * possibility in which it may throw a MessageException when Locale is
     * not Locale.US. Locale.JAPAN is a typical locale which emits different
     * date formats, which had caused a bug before.
     * @throws MessagingException
     */
    public void testImapListWithUsLocale() throws MessagingException {
        Locale savedLocale = Locale.getDefault();
        Locale.setDefault(Locale.US);
        doTestImapList();
        Locale.setDefault(Locale.JAPAN);
        doTestImapList();
        Locale.setDefault(savedLocale);
    }
    
    private void doTestImapList() throws MessagingException {
        ImapResponseParser parser = new ImapResponseParser(null, new DiscourseLogger(4));
        ImapResponseParser.ImapList list = parser.new ImapList();
        String key = "key";
        String date = "01-Jan-2009 01:00:00 -0800";
        list.add(key);
        list.add(date);
        Date result = list.getKeyedDate(key);
        // "01-Jan-2009 09:00:00 +0000" => 1230800400000L 
        assertEquals(1230800400000L, result.getTime());
    }
    
    /**
     * TODO: Test the operation of checkSettings()
     * TODO: Test small Store & Folder functions that manage folders & namespace
     */   

    /**
     * Test small Folder functions that don't really do anything in Imap
     * TODO: Test all of the small Folder functions.
     */
    public void testSmallFolderFunctions() throws MessagingException {
        // getPermanentFlags() returns { Flag.DELETED, Flag.SEEN, Flag.FLAGGED }
        Flag[] flags = mFolder.getPermanentFlags();
        assertEquals(3, flags.length);
        // TODO: Write flags into hashset and compare them to a hashset and compare them
        assertEquals(Flag.DELETED, flags[0]);
        assertEquals(Flag.SEEN, flags[1]);
        assertEquals(Flag.FLAGGED, flags[2]);

        // canCreate() returns true
        assertTrue(mFolder.canCreate(FolderType.HOLDS_FOLDERS));
        assertTrue(mFolder.canCreate(FolderType.HOLDS_MESSAGES));
    }

    /**
     * Lightweight test to confirm that IMAP hasn't implemented any folder roles yet.
     * 
     * TODO: Test this with multiple folders provided by mock server
     * TODO: Implement XLIST and then support this
     */
    public void testNoFolderRolesYet() {
        assertEquals(Folder.FolderRole.UNKNOWN, mFolder.getRole()); 
    }
    
    /**
     * Lightweight test to confirm that IMAP isn't requesting structure prefetch.
     */
    public void testNoStructurePrefetch() {
        assertFalse(mStore.requireStructurePrefetch()); 
    }
    
    /**
     * Lightweight test to confirm that IMAP is requesting sent-message-upload.
     * TODO: Implement Gmail-specific cases and handle this server-side
     */
    public void testSentUploadRequested() {
        assertTrue(mStore.requireCopyMessageToSentFolder()); 
    }

    /**
     * TODO: Test the process of opening and indexing a mailbox with one unread message in it.
     */

    /**
     * TODO: Test the scenario where the transport is "open" but not really (e.g. server closed).     
    /**
     * Set up a basic MockTransport. open it, and inject it into mStore
     */
    private MockTransport openAndInjectMockTransport() {
        // Create mock transport and inject it into the ImapStore that's already set up
        MockTransport mockTransport = new MockTransport();
        mockTransport.setSecurity(Transport.CONNECTION_SECURITY_NONE, false);
        mockTransport.setMockHost("mock.server.com");
        mStore.setTransport(mockTransport);
        return mockTransport;
    }

    /**
     * Helper which stuffs the mock with enough strings to satisfy a call to ImapFolder.open()
     *
     * @param mockTransport the mock transport we're using
     */
    private void setupOpenFolder(MockTransport mockTransport) {
        setupOpenFolder(mockTransport, "READ-WRITE");
    }

    /**
     * Helper which stuffs the mock with enough strings to satisfy a call to ImapFolder.open()
     *
     * @param mockTransport the mock transport we're using
     */
    private void setupOpenFolder(MockTransport mockTransport, String readWriteMode) {
        setupOpenFolder(mockTransport, new String[] {
                "* ID NIL", "OK"}, readWriteMode);
    }

    /**
     * Helper which stuffs the mock with enough strings to satisfy a call to ImapFolder.open()
     * Also allows setting a custom IMAP ID.
     *
     * Also sets mNextTag, an int, which is useful if there are additional commands to inject.
     *
     * @param mockTransport the mock transport we're using
     * @param imapIdResponse the expected series of responses to the IMAP ID command.  Non-final
     *      lines should be tagged with *.  The final response should be untagged (the correct
     *      tag will be added at runtime).
     * @param "READ-WRITE" or "READ-ONLY"
     * @return the next tag# to use
     */
    private void setupOpenFolder(MockTransport mockTransport, String[] imapIdResponse,
            String readWriteMode) {
        // Fix the tag # of the ID response
        String last = imapIdResponse[imapIdResponse.length-1];
        last = "2 " + last;
        imapIdResponse[imapIdResponse.length-1] = last;
        // inject boilerplate commands that match our typical login
        mockTransport.expect(null, "* OK Imap 2000 Ready To Assist You");
        mockTransport.expect("1 CAPABILITY", new String[] {
                "* CAPABILITY IMAP4rev1 STARTTLS AUTH=GSSAPI LOGINDISABLED",
                "1 OK CAPABILITY completed"});
        mockTransport.expect("2 ID \\(.*\\)", imapIdResponse);
        mockTransport.expect("3 LOGIN user \"password\"", 
                "3 OK user authenticated (Success)");
        mockTransport.expect("4 SELECT \"INBOX\"", new String[] {
                "* FLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen)",
                "* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen \\*)]",
                "* 0 EXISTS",
                "* 0 RECENT",
                "* OK [UNSEEN 0]",
                "* OK [UIDNEXT 1]",
                "4 OK [" + readWriteMode + "] INBOX selected. (Success)"});
        mNextTag = 5;
    }

    /**
     * Return a tag for use in setting up expect strings.  Typically this is called in pairs,
     * first as getNextTag(false) when emitting the command, then as getNextTag(true) when
     * emitting the final line of the expected response.
     * @param advance true to increment mNextTag for the subsequence command
     * @return a string containing the current tag
     */
    public String getNextTag(boolean advance)  {
        if (advance) ++mNextTag;
        return Integer.toString(mNextTag);
    }

    /**
     * Test that servers reporting READ-WRITE mode are parsed properly
     * Note: the READ_WRITE mode passed to folder.open() does not affect the test
     */
    public void testReadWrite() throws MessagingException {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock, "READ-WRITE");
        mFolder.open(OpenMode.READ_WRITE, null);
        assertEquals(OpenMode.READ_WRITE, mFolder.getMode());
    }

    /**
     * Test that servers reporting READ-ONLY mode are parsed properly
     * Note: the READ_ONLY mode passed to folder.open() does not affect the test
     */
    public void testReadOnly() throws MessagingException {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock, "READ-ONLY");
        mFolder.open(OpenMode.READ_ONLY, null);
        assertEquals(OpenMode.READ_ONLY, mFolder.getMode());
    }

    /**
     * Test for getUnreadMessageCount with quoted string in the middle of response.
     */
    public void testGetUnreadMessageCountWithQuotedString() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mock.expect(getNextTag(false) + " STATUS \"INBOX\" \\(UNSEEN\\)", new String[] {
                "* STATUS \"INBOX\" (UNSEEN 2)",
                getNextTag(true) + " OK STATUS completed"});
        mFolder.open(OpenMode.READ_WRITE, null);
        int unreadCount = mFolder.getUnreadMessageCount();
        assertEquals("getUnreadMessageCount with quoted string", 2, unreadCount);
    }

    /**
     * Test for getUnreadMessageCount with literal string in the middle of response.
     */
    public void testGetUnreadMessageCountWithLiteralString() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mock.expect(getNextTag(false) + " STATUS \"INBOX\" \\(UNSEEN\\)", new String[] {
                "* STATUS {5}",
                "INBOX (UNSEEN 10)",
                getNextTag(true) + " OK STATUS completed"});
        mFolder.open(OpenMode.READ_WRITE, null);
        int unreadCount = mFolder.getUnreadMessageCount();
        assertEquals("getUnreadMessageCount with literal string", 10, unreadCount);
    }

    /**
     * Test for proper operations on servers that return "NIL" for empty message bodies.
     */
    public void testNilMessage() throws MessagingException {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE, null);

        // Prepare to pull structure and peek body text - this is like the "large message"
        // loop in MessagingController.synchronizeMailboxGeneric()
        FetchProfile fp = new FetchProfile();fp.clear();
        fp.add(FetchProfile.Item.STRUCTURE);
        Message message1 = mFolder.createMessage("1");
        mock.expect(getNextTag(false) + " UID FETCH 1 \\(UID BODYSTRUCTURE\\)", new String[] {
                "* 1 FETCH (UID 1 BODYSTRUCTURE (TEXT PLAIN NIL NIL NIL 7BIT 0 0 NIL NIL NIL))",
                getNextTag(true) + " OK SUCCESS"
        });
        mFolder.fetch(new Message[] { message1 }, fp, null);

        // The expected result for an empty body is:
        //   * 1 FETCH (UID 1 BODY[TEXT] {0})
        // But some servers are returning NIL for the empty body:
        //   * 1 FETCH (UID 1 BODY[TEXT] NIL)
        // Because this breaks our little parser, fetch() skips over empty parts.
        // The rest of this test is confirming that this is the case.

        mock.expect(getNextTag(false) + " UID FETCH 1 \\(UID BODY.PEEK\\[TEXT\\]\\)", new String[] {
                "* 1 FETCH (UID 1 BODY[TEXT] NIL)",
                getNextTag(true) + " OK SUCCESS"
        });
        ArrayList<Part> viewables = new ArrayList<Part>();
        ArrayList<Part> attachments = new ArrayList<Part>();
        MimeUtility.collectParts(message1, viewables, attachments);
        assertTrue(viewables.size() == 1);
        Part emptyBodyPart = viewables.get(0);
        fp.clear();
        fp.add(emptyBodyPart);
        mFolder.fetch(new Message[] { message1 }, fp, null);

        // If this wasn't working properly, there would be an attempted interpretation
        // of the empty part's NIL and possibly a crash.

        // If this worked properly, the "empty" body can now be retrieved
        viewables = new ArrayList<Part>();
        attachments = new ArrayList<Part>();
        MimeUtility.collectParts(message1, viewables, attachments);
        assertTrue(viewables.size() == 1);
        emptyBodyPart = viewables.get(0);
        String text = MimeUtility.getTextFromPart(emptyBodyPart);
        assertNull(text);
    }

    /**
     * Confirm the IMAP parser won't crash when seeing an excess FETCH response line without UID.
     *
     * <p>We've observed that the secure.emailsrvr.com email server returns an excess FETCH response
     * for a UID FETCH command.  These excess responses doesn't have the UID field in it, even
     * though we request, which led the response parser to crash.  We fixed it by ignoring response
     * lines that don't have UID.  This test is to make sure this case.
     */
    public void testExcessFetchResult() throws MessagingException {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE, null);

        // Create a message, and make sure it's not "SEEN".
        Message message1 = mFolder.createMessage("1");
        assertFalse(message1.isSet(Flag.SEEN));

        FetchProfile fp = new FetchProfile();
        fp.clear();
        fp.add(FetchProfile.Item.FLAGS);
        mock.expect(getNextTag(false) + " UID FETCH 1 \\(UID FLAGS\\)",
                new String[] {
                "* 1 FETCH (UID 1 FLAGS (\\Seen))",
                "* 2 FETCH (FLAGS (\\Seen))",
                getNextTag(true) + " OK SUCCESS"
        });

        // Shouldn't crash
        mFolder.fetch(new Message[] { message1 }, fp, null);

        // And the message is "SEEN".
        assertTrue(message1.isSet(Flag.SEEN));
    }

    public void testAppendMessages() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE, null);

        ImapMessage message = (ImapMessage) mFolder.createMessage("1");
        message.setFrom(new Address("me@test.com"));
        message.setRecipient(RecipientType.TO, new Address("you@test.com"));
        message.setMessageId("<message.id@test.com>");
        message.setFlagDirectlyForTest(Flag.SEEN, true);
        message.setBody(new TextBody("Test Body"));

        // + go ahead
        // * 12345 EXISTS
        // OK [APPENDUID 627684530 17] (Success)

        mock.expect(getNextTag(false) + " APPEND \\\"INBOX\\\" \\(\\\\Seen\\) \\{166\\}",
                new String[] {"+ go ahead"});

        mock.expectLiterally("From: me@test.com", NO_REPLY);
        mock.expectLiterally("To: you@test.com", NO_REPLY);
        mock.expectLiterally("Message-ID: <message.id@test.com>", NO_REPLY);
        mock.expectLiterally("Content-Type: text/plain;", NO_REPLY);
        mock.expectLiterally(" charset=utf-8", NO_REPLY);
        mock.expectLiterally("Content-Transfer-Encoding: base64", NO_REPLY);
        mock.expectLiterally("", NO_REPLY);
        mock.expectLiterally("VGVzdCBCb2R5", NO_REPLY);
        mock.expectLiterally("", new String[] {
                "* 7 EXISTS",
                getNextTag(true) + " OK [APPENDUID 1234567 13] (Success)"
                });

        mFolder.appendMessages(new Message[] {message});
        mock.close();

        assertEquals("13", message.getUid());
        assertEquals(7, mFolder.getMessageCount());
    }
}
