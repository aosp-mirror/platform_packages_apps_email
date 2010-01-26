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

import com.android.email.Email;
import com.android.email.mail.FetchProfile;
import com.android.email.mail.Flag;
import com.android.email.mail.Folder;
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Part;
import com.android.email.mail.Transport;
import com.android.email.mail.Folder.FolderType;
import com.android.email.mail.Folder.OpenMode;
import com.android.email.mail.internet.BinaryTempFileBody;
import com.android.email.mail.internet.MimeUtility;
import com.android.email.mail.transport.MockTransport;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

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
    
    /* These values are provided by setUp() */
    private ImapStore mStore = null;
    private ImapStore.ImapFolder mFolder = null;
    
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
     *
     * Since this is build-specific, we mostly just ensure that the correct strings
     * are being generated, and non-empty, and (if possible) look for expected formatting.
     */
    public void testImapId() {
        String id = mStore.getImapId(getContext());
        // Instead of a true tokenizer, we'll use double-quote as the split.
        // We can's use " " because there may be spaces inside the values. 
        String[] elements = id.split("\"");
        HashMap<String, String> map = new HashMap<String, String>();
        for (int i = 0; i < elements.length; ) {
            // Because we split at quotes, we expect to find:
            // [i] = null
            // [i+1] = key
            // [i+2] = one or more spaces
            // [i+3] = value
            map.put(elements[i+1], elements[i+3]);
            i += 4;
        }
        
        // Strings we'll expect to find:
        //   name            Android package name of the program
        //   os              "android"
        //   os-version      "version; model; build-id"
        //   vendor          Vendor of the client/server

        String name = map.get("name");
        assertEquals(getContext().getPackageName(), name);
        String os = map.get("os");
        assertEquals("android", os);
        String osversion = map.get("os-version");
        assertNotNull(osversion);
        String vendor = map.get("vendor");
        assertNotNull(vendor);
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
                "1 OK"});
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
                "1 OK [ID] bad-char-%"});
        mFolder.open(OpenMode.READ_WRITE, null);
    }
    
    /**
     * Test BAD response to IMAP ID - also with bad parser chars
     */
    public void testImapIdBad() throws MessagingException {
        MockTransport mockTransport = openAndInjectMockTransport();
        
        // try to open it
        setupOpenFolder(mockTransport, new String[] {
                "1 BAD unknown command bad-char-%"});
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
        ImapResponseParser parser = new ImapResponseParser(null);
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
        mStore.setTransport(mockTransport);
        return mockTransport;
    }
    
    /**
     * Helper which stuffs the mock with enough strings to satisfy a call to ImapFolder.open()
     * 
     * @param mockTransport the mock transport we're using
     */
    private void setupOpenFolder(MockTransport mockTransport) {
        setupOpenFolder(mockTransport, new String[] {
                "* ID NIL", "1 OK"});
    }
    
    /**
     * Helper which stuffs the mock with enough strings to satisfy a call to ImapFolder.open()
     * Also allows setting a custom IMAP ID.
     * 
     * @param mockTransport the mock transport we're using
     */
    private void setupOpenFolder(MockTransport mockTransport, String[] imapIdResponse) {
        mockTransport.expect(null, "* OK Imap 2000 Ready To Assist You");
        mockTransport.expect("1 ID \\(.*\\)", imapIdResponse);
        mockTransport.expect("2 LOGIN user \"password\"", 
                "2 OK user authenticated (Success)");
        mockTransport.expect("3 SELECT \"INBOX\"", new String[] {
                "* FLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen)",
                "* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen \\*)]",
                "* 0 EXISTS",
                "* 0 RECENT",
                "* OK [UNSEEN 0]",
                "* OK [UIDNEXT 1]",
                "3 OK [READ-WRITE] INBOX selected. (Success)"});
    }
    
    /**
     * Test for getUnreadMessageCount with quoted string in the middle of response.
     */
    public void testGetUnreadMessageCountWithQuotedString() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mock.expect("4 STATUS \"INBOX\" \\(UNSEEN\\)", new String[] {
                "* STATUS \"INBOX\" (UNSEEN 2)",
                "4 OK STATUS completed"});
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
        mock.expect("4 STATUS \"INBOX\" \\(UNSEEN\\)", new String[] {
                "* STATUS {5}",
                "INBOX (UNSEEN 10)",
                "4 OK STATUS completed"});
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
        mock.expect("4 UID FETCH 1 \\(UID BODYSTRUCTURE\\)", new String[] {
                "* 1 FETCH (UID 1 BODYSTRUCTURE (TEXT PLAIN NIL NIL NIL 7BIT 0 0 NIL NIL NIL))",
                "4 OK SUCCESS"
        });
        mFolder.fetch(new Message[] { message1 }, fp, null);

        // The expected result for an empty body is:
        //   * 1 FETCH (UID 1 BODY[TEXT] {0})
        // But some servers are returning NIL for the empty body:
        //   * 1 FETCH (UID 1 BODY[TEXT] NIL)
        // Because this breaks our little parser, fetch() skips over empty parts.
        // The rest of this test is confirming that this is the case.

        mock.expect("5 UID FETCH 1 \\(UID BODY.PEEK\\[TEXT\\]\\)", new String[] {
                "* 1 FETCH (UID 1 BODY[TEXT] NIL)",
                "5 OK SUCCESS"
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
}
