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

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;

import com.android.email.DBTestHelper;
import com.android.email.MockSharedPreferences;
import com.android.email.MockVendorPolicy;
import com.android.email.mail.store.ImapStore.ImapMessage;
import com.android.email.mail.store.imap.ImapResponse;
import com.android.email.mail.store.imap.ImapTestUtils;
import com.android.email.mail.transport.MockTransport;
import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.VendorPolicyLoader;
import com.android.emailcommon.internet.MimeBodyPart;
import com.android.emailcommon.internet.MimeMultipart;
import com.android.emailcommon.internet.MimeUtility;
import com.android.emailcommon.internet.TextBody;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.Body;
import com.android.emailcommon.mail.FetchProfile;
import com.android.emailcommon.mail.Flag;
import com.android.emailcommon.mail.Folder;
import com.android.emailcommon.mail.Folder.FolderType;
import com.android.emailcommon.mail.Folder.OpenMode;
import com.android.emailcommon.mail.Message;
import com.android.emailcommon.mail.Message.RecipientType;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.mail.Part;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;

import org.apache.commons.io.IOUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Pattern;

/**
 * This is a series of unit tests for the ImapStore class.  These tests must be locally
 * complete - no server(s) required.
 *
 * To run these tests alone, use:
 *   $ runtest -c com.android.email.mail.store.ImapStoreUnitTests email
 *
 * TODO Check if callback is really called
 * TODO test for BAD response in various places?
 * TODO test for BYE response in various places?
 */
@Suppress
@SmallTest
public class ImapStoreUnitTests extends InstrumentationTestCase {
    private final static String[] NO_REPLY = new String[0];

    /** Default folder name.  In order to test for encoding, we use a non-ascii name. */
    private final static String FOLDER_NAME = "\u65E5";
    /** Folder name encoded in UTF-7. */
    private final static String FOLDER_ENCODED = "&ZeU-";
    /**
     * Flag bits to specify whether or not a folder can be selected. This corresponds to
     * {@link Mailbox#FLAG_ACCEPTS_MOVED_MAIL} and {@link Mailbox#FLAG_HOLDS_MAIL}.
     */
    private final static int SELECTABLE_BITS = 0x18;

    private final static ImapResponse CAPABILITY_RESPONSE = ImapTestUtils.parseResponse(
            "* CAPABILITY IMAP4rev1 STARTTLS");

    /* These values are provided by setUp() */
    private ImapStore mStore = null;
    private ImapFolder mFolder = null;
    private Context mTestContext;
    private HostAuth mHostAuth;

    /** The tag for the current IMAP command; used for mock transport responses */
    private int mTag;
    // Fields specific to the CopyMessages tests
    private MockTransport mCopyMock;
    private Folder mCopyToFolder;
    private Message[] mCopyMessages;

    /**
     * A wrapper to provide a wrapper to a Context which has already been mocked.
     * This allows additional methods to delegate to the original, real context, in cases
     * where the mocked behavior is insufficient.
     */
    private class SecondaryMockContext extends ContextWrapper {
        private final Context mUnderlying;

        public SecondaryMockContext(Context mocked, Context underlying) {
            super(mocked);
            mUnderlying = underlying;
        }

        // TODO: eliminate the need for these method.
        @Override
        public Context createPackageContext(String packageName, int flags)
                throws NameNotFoundException {
            return mUnderlying.createPackageContext(packageName, flags);
        }

        @Override
        public SharedPreferences getSharedPreferences(String name, int mode) {
            return new MockSharedPreferences();
        }
    }

    /**
     * Setup code.  We generate a lightweight ImapStore and ImapStore.ImapFolder.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Context realContext = getInstrumentation().getTargetContext();
        ImapStore.sImapId = ImapStore.makeCommonImapId(realContext.getPackageName(),
                        Build.VERSION.RELEASE, Build.VERSION.CODENAME,
                        Build.MODEL, Build.ID, Build.MANUFACTURER,
                        "FakeNetworkOperator");
        mTestContext = new SecondaryMockContext(
                DBTestHelper.ProviderContextSetupHelper.getProviderContext(realContext),
                realContext);
        MockVendorPolicy.inject(mTestContext);

        TempDirectory.setTempDirectory(mTestContext);

        // These are needed so we can get at the inner classes
        HostAuth testAuth = new HostAuth();
        Account testAccount = new Account();

        testAuth.setLogin("user", "password");
        testAuth.setConnection("imap", "server", 999);
        testAccount.mHostAuthRecv = testAuth;
        mStore = (ImapStore) ImapStore.newInstance(testAccount, mTestContext);
        mFolder = (ImapFolder) mStore.getFolder(FOLDER_NAME);
        resetTag();
    }

    public void testJoinMessageUids() throws Exception {
        assertEquals("", ImapStore.joinMessageUids(new Message[] {}));
        assertEquals("a", ImapStore.joinMessageUids(new Message[] {
                mFolder.createMessage("a")
                }));
        assertEquals("a,XX", ImapStore.joinMessageUids(new Message[] {
                mFolder.createMessage("a"),
                mFolder.createMessage("XX"),
                }));
    }

    /**
     * Confirms simple non-SSL non-TLS login
     */
    public void testSimpleLogin() throws MessagingException {

        MockTransport mockTransport = openAndInjectMockTransport();

        // try to open it
        setupOpenFolder(mockTransport);
        mFolder.open(OpenMode.READ_WRITE);

        // TODO: inject specific facts in the initial folder SELECT and check them here
    }

    /**
     * Test simple login with failed authentication
     */
    public void testLoginFailure() throws Exception {
        MockTransport mockTransport = openAndInjectMockTransport();
        expectLogin(mockTransport, false, false, false, new String[] {"* iD nIL", "oK"},
                "nO authentication failed");

        try {
            mStore.getConnection().open();
            fail("Didn't throw AuthenticationFailedException");
        } catch (AuthenticationFailedException expected) {
        }
    }

    /**
     * Test simple TLS open
     */
    public void testTlsOpen() throws MessagingException {

        MockTransport mockTransport = openAndInjectMockTransport(HostAuth.FLAG_TLS,
                false);

        // try to open it, with STARTTLS
        expectLogin(mockTransport, true, false, false,
                new String[] {"* iD nIL", "oK"}, "oK user authenticated (Success)");
        mockTransport.expect(
                getNextTag(false) + " SELECT \"" + FOLDER_ENCODED + "\"", new String[] {
                "* fLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen)",
                "* oK [pERMANENTFLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen \\*)]",
                "* 0 eXISTS",
                "* 0 rECENT",
                "* OK [uNSEEN 0]",
                "* OK [uIDNEXT 1]",
                getNextTag(true) + " oK [" + "rEAD-wRITE" + "] " +
                        FOLDER_ENCODED + " selected. (Success)"});

        mFolder.open(OpenMode.READ_WRITE);
        assertTrue(mockTransport.isTlsStarted());
    }

    /**
     * TODO: Test with SSL negotiation (faked)
     * TODO: Test with SSL required but not supported
     * TODO: Test with TLS required but not supported
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
        String id = ImapStore.getImapId(mTestContext, "user-name", "host-name",
                CAPABILITY_RESPONSE.flatten());
        HashMap<String, String> map = tokenizeImapId(id);
        assertEquals(mTestContext.getPackageName(), map.get("name"));
        assertEquals("android", map.get("os"));
        assertNotNull(map.get("os-version"));
        assertNotNull(map.get("vendor"));
        assertNotNull(map.get("AGUID"));

        // Next, use the inner API to confirm operation of a couple of
        // variants for release and non-release devices.

        // simple API check - non-REL codename, non-empty version
        id = ImapStore.makeCommonImapId("packageName", "version", "codeName",
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
        id = ImapStore.makeCommonImapId("packageName", "", "REL",
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
     * Test for the interaction between {@link ImapStore#getImapId} and a vendor policy.
     */
    public void testImapIdWithVendorPolicy() {
        try {
            MockVendorPolicy.inject(mTestContext);

            // Prepare mock result
            Bundle result = new Bundle();
            result.putString("getImapId", "\"test-key\" \"test-value\"");
            MockVendorPolicy.mockResult = result;

            // Invoke
            String id = ImapStore.getImapId(mTestContext, "user-name", "host-name",
                    ImapTestUtils.parseResponse("* CAPABILITY IMAP4rev1 XXX YYY Z").flatten());

            // Check the result
            assertEquals("test-value", tokenizeImapId(id).get("test-key"));

            // Verify what's passed to the policy
            assertEquals("getImapId", MockVendorPolicy.passedPolicy);
            assertEquals("user-name", MockVendorPolicy.passedBundle.getString("getImapId.user"));
            assertEquals("host-name", MockVendorPolicy.passedBundle.getString("getImapId.host"));
            assertEquals("[CAPABILITY,IMAP4rev1,XXX,YYY,Z]",
                    MockVendorPolicy.passedBundle.getString("getImapId.capabilities"));
        } finally {
            VendorPolicyLoader.clearInstanceForTest();
        }
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
        String id = ImapStore.makeCommonImapId(
                "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ",
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
        HostAuth testAuth;
        Account testAccount;

        // store 1a
        testAuth = new HostAuth();
        testAuth.setLogin("user1", "password");
        testAuth.setConnection("imap", "server", 999);
        testAccount = new Account();
        testAccount.mHostAuthRecv = testAuth;
        ImapStore testStore1A = (ImapStore) ImapStore.newInstance(testAccount, mTestContext);

        // store 1b
        testAuth = new HostAuth();
        testAuth.setLogin("user1", "password");
        testAuth.setConnection("imap", "server", 999);
        testAccount = new Account();
        testAccount.mHostAuthRecv = testAuth;
        ImapStore testStore1B = (ImapStore) ImapStore.newInstance(testAccount, mTestContext);

        // store 2
        testAuth = new HostAuth();
        testAuth.setLogin("user2", "password");
        testAuth.setConnection("imap", "server", 999);
        testAccount = new Account();
        testAccount.mHostAuthRecv = testAuth;
        ImapStore testStore2 = (ImapStore) ImapStore.newInstance(testAccount, mTestContext);

        String capabilities = CAPABILITY_RESPONSE.flatten();
        String id1a = ImapStore.getImapId(mTestContext, "user1", "host-name", capabilities);
        String id1b = ImapStore.getImapId(mTestContext, "user1", "host-name", capabilities);
        String id2 = ImapStore.getImapId(mTestContext, "user2", "host-name", capabilities);

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
                "oK"}, "rEAD-wRITE");
        mFolder.open(OpenMode.READ_WRITE);
    }

    /**
     * Test OK response to IMAP ID with crummy text afterwards too.
     */
    public void testImapIdOkParsing() throws MessagingException {
        MockTransport mockTransport = openAndInjectMockTransport();

        // try to open it
        setupOpenFolder(mockTransport, new String[] {
                "* iD nIL",
                "oK [iD] bad-char-%"}, "rEAD-wRITE");
        mFolder.open(OpenMode.READ_WRITE);
    }

    /**
     * Test BAD response to IMAP ID - also with bad parser chars
     */
    public void testImapIdBad() throws MessagingException {
        MockTransport mockTransport = openAndInjectMockTransport();

        // try to open it
        setupOpenFolder(mockTransport, new String[] {
                "bAD unknown command bad-char-%"}, "rEAD-wRITE");
        mFolder.open(OpenMode.READ_WRITE);
    }

    /**
     * Confirm that when IMAP ID is not in capability, it is not sent/received.
     * This supports RFC 2971 section 3, and is important because certain servers
     * (e.g. imap.vodafone.net.nz) do not process the unexpected ID command properly.
     */
    public void testImapIdNotSupported() throws MessagingException {
        MockTransport mockTransport = openAndInjectMockTransport();

        // try to open it
        setupOpenFolder(mockTransport, null, "rEAD-wRITE");
        mFolder.open(OpenMode.READ_WRITE);
    }

    /**
     * Confirm that the non-conformant IMAP ID result seen on imap.secureserver.net fails
     * to properly parse.
     *   2 ID ("name" "com.google.android.email")
     *   * ID( "name" "Godaddy IMAP" ... "version" "3.1.0")
     *   2 OK ID completed
     */
    public void testImapIdSecureServerParseFail() {
        MockTransport mockTransport = openAndInjectMockTransport();

        // configure mock server to return malformed ID response
        setupOpenFolder(mockTransport, new String[] {
                "* ID( \"name\" \"Godaddy IMAP\" \"version\" \"3.1.0\")",
                "oK"}, "rEAD-wRITE");
        try {
            mFolder.open(OpenMode.READ_WRITE);
            fail("Expected MessagingException");
        } catch (MessagingException expected) {
        }
    }

    /**
     * Confirm that the connections to *.secureserver.net never send IMAP ID (see
     * testImapIdSecureServerParseFail() for the reason why.)
     */
    public void testImapIdSecureServerNotSent() throws MessagingException {
        // Note, this is injected into mStore (which we don't use for this test)
        MockTransport mockTransport = openAndInjectMockTransport();
        mockTransport.setHost("eMail.sEcurEserVer.nEt");

        // Prime the expects pump as if the server wants IMAP ID, but we should not actually expect
        // to send it, because the login code in the store should never actually send it (to this
        // particular server).  This sequence is a minimized version of expectLogin().

        // Respond to the initial connection
        mockTransport.expect(null, "* oK Imap 2000 Ready To Assist You");
        // Return "ID" in the capability
        expectCapability(mockTransport, true, false);
        // No TLS
        // No ID (the special case for this server)
        // LOGIN
        mockTransport.expect(getNextTag(false) + " LOGIN user \"password\"",
                getNextTag(true) + " " + "oK user authenticated (Success)");
        // SELECT
        expectSelect(mockTransport, FOLDER_ENCODED, "rEAD-wRITE");

        // Now open the folder.  Although the server indicates ID in the capabilities,
        // we are not expecting the store to send the ID command (to this particular server).
        mFolder.open(OpenMode.READ_WRITE);
    }

    /**
     * Test small Folder functions that don't really do anything in Imap
     */
    public void testSmallFolderFunctions() {
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
        return openAndInjectMockTransport(HostAuth.FLAG_NONE, false);
    }

    /**
     * Set up a MockTransport with security settings
     */
    private MockTransport openAndInjectMockTransport(int connectionSecurity,
            boolean trustAllCertificates) {
        // Create mock transport and inject it into the ImapStore that's already set up
        MockTransport mockTransport = MockTransport.createMockTransport(mTestContext);
        mockTransport.setSecurity(connectionSecurity, trustAllCertificates);
        mockTransport.setHost("mock.server.com");
        mStore.setTransportForTest(mockTransport);
        return mockTransport;
    }

    /**
     * Helper which stuffs the mock with enough strings to satisfy a call to ImapFolder.open()
     *
     * @param mockTransport the mock transport we're using
     */
    private void setupOpenFolder(MockTransport mockTransport) {
        setupOpenFolder(mockTransport, "rEAD-wRITE");
    }

    /**
     * Helper which stuffs the mock with enough strings to satisfy a call to ImapFolder.open()
     *
     * @param mockTransport the mock transport we're using
     */
    private void setupOpenFolder(MockTransport mockTransport, String readWriteMode) {
        setupOpenFolder(mockTransport, new String[] {
                "* iD nIL", "oK"}, readWriteMode, false);
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
     *      tag will be added at runtime).  Pass "null" to test w/o IMAP ID.
     * @param readWriteMode "READ-WRITE" or "READ-ONLY"
     */
    private void setupOpenFolder(MockTransport mockTransport, String[] imapIdResponse,
            String readWriteMode) {
        setupOpenFolder(mockTransport, imapIdResponse, readWriteMode, false);
    }

    private void setupOpenFolder(MockTransport mockTransport, String[] imapIdResponse,
            String readWriteMode, boolean withUidPlus) {
        expectLogin(mockTransport, imapIdResponse, withUidPlus);
        expectSelect(mockTransport, FOLDER_ENCODED, readWriteMode);
    }

    /**
     * Helper which stuffs the mock with the strings to satisfy a typical SELECT.
     * @param mockTransport the mock transport we're using
     * @param readWriteMode "READ-WRITE" or "READ-ONLY"
     */
    private void expectSelect(MockTransport mockTransport, String folder, String readWriteMode) {
        mockTransport.expect(
                getNextTag(false) + " SELECT \"" + folder + "\"", new String[] {
                "* fLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen)",
                "* oK [pERMANENTFLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen \\*)]",
                "* 0 eXISTS",
                "* 0 rECENT",
                "* OK [uNSEEN 0]",
                "* OK [uIDNEXT 1]",
                getNextTag(true) + " oK [" + readWriteMode + "] " +
                        folder + " selected. (Success)"});
    }

    private void expectLogin(MockTransport mockTransport) {
        expectLogin(mockTransport, new String[] {"* iD nIL", "oK"}, false);
    }

    private void expectLogin(MockTransport mockTransport, String[] imapIdResponse,
            boolean withUidPlus) {
        expectLogin(mockTransport, false, (imapIdResponse != null), withUidPlus, imapIdResponse,
                "oK user authenticated (Success)");
    }

    private void expectLogin(MockTransport mockTransport, boolean startTls, boolean withId,
            boolean withUidPlus, String[] imapIdResponse, String loginResponse) {
        // inject boilerplate commands that match our typical login
        mockTransport.expect(null, "* oK Imap 2000 Ready To Assist You");

        expectCapability(mockTransport, withId, withUidPlus);

        // TLS (if expected)
        if (startTls) {
            mockTransport.expect(getNextTag(false) + " STARTTLS",
                getNextTag(true) + " Ok starting TLS");
            mockTransport.expectStartTls();
            // After switching to TLS the client must re-query for capability
            expectCapability(mockTransport, withId, withUidPlus);
        }

        // ID
        if (withId) {
            String expectedNextTag = getNextTag(false);
            // Fix the tag # of the ID response
            String last = imapIdResponse[imapIdResponse.length-1];
            last = expectedNextTag + " " + last;
            imapIdResponse[imapIdResponse.length-1] = last;
            mockTransport.expect(getNextTag(false) + " ID \\(.*\\)", imapIdResponse);
            getNextTag(true); // Advance the tag for ID response.
        }

        // LOGIN
        mockTransport.expect(getNextTag(false) + " LOGIN user \"password\"",
                getNextTag(true) + " " + loginResponse);
    }

    private void expectCapability(MockTransport mockTransport, boolean withId,
            boolean withUidPlus) {
        String capabilityList = "* cAPABILITY iMAP4rev1 sTARTTLS aUTH=gSSAPI lOGINDISABLED";
        capabilityList += withId ? " iD" : "";
        capabilityList += withUidPlus ? " UiDPlUs" : "";

        mockTransport.expect(getNextTag(false) + " CAPABILITY", new String[] {
            capabilityList,
            getNextTag(true) + " oK CAPABILITY completed"});
    }

    private void expectNoop(MockTransport mockTransport, boolean ok) {
        String response = ok ? " oK success" : " nO timeout";
        mockTransport.expect(getNextTag(false) + " NOOP",
                new String[] {getNextTag(true) + response});
    }

    /**
     * Return a tag for use in setting up expect strings.  Typically this is called in pairs,
     * first as getNextTag(false) when emitting the command, then as getNextTag(true) when
     * emitting the final line of the expected response.
     * @param advance true to increment mNextTag for the subsequence command
     * @return a string containing the current tag
     */
    public String getNextTag(boolean advance)  {
        if (advance) ++mTag;
        return Integer.toString(mTag);
    }

    /**
     * Resets the tag back to it's starting value. Do this after the test connection has been
     * closed.
     */
    private int resetTag() {
        return resetTag(1);
    }

    private int resetTag(int tag) {
        int oldTag = mTag;
        mTag = tag;
        return oldTag;
    }

    /**
     * Test that servers reporting READ-WRITE mode are parsed properly
     * Note: the READ_WRITE mode passed to folder.open() does not affect the test
     */
    public void testReadWrite() throws MessagingException {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock, "rEAD-WRITE");
        mFolder.open(OpenMode.READ_WRITE);
        assertEquals(OpenMode.READ_WRITE, mFolder.getMode());
    }

    /**
     * Test that servers reporting READ-ONLY mode are parsed properly
     * Note: the READ_ONLY mode passed to folder.open() does not affect the test
     */
    public void testReadOnly() throws MessagingException {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock, "rEAD-ONLY");
        mFolder.open(OpenMode.READ_ONLY);
        assertEquals(OpenMode.READ_ONLY, mFolder.getMode());
    }

    /**
     * Test for getUnreadMessageCount with quoted string in the middle of response.
     */
    public void testGetUnreadMessageCountWithQuotedString() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mock.expect(
                getNextTag(false) + " STATUS \"" + FOLDER_ENCODED + "\" \\(UNSEEN\\)",
                new String[] {
                "* sTATUS \"" + FOLDER_ENCODED + "\" (uNSEEN 2)",
                getNextTag(true) + " oK STATUS completed"});
        mFolder.open(OpenMode.READ_WRITE);
        int unreadCount = mFolder.getUnreadMessageCount();
        assertEquals("getUnreadMessageCount with quoted string", 2, unreadCount);
    }

    /**
     * Test for getUnreadMessageCount with literal string in the middle of response.
     */
    public void testGetUnreadMessageCountWithLiteralString() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mock.expect(
                getNextTag(false) + " STATUS \"" + FOLDER_ENCODED + "\" \\(UNSEEN\\)",
                new String[] {
                "* sTATUS {5}",
                FOLDER_ENCODED + " (uNSEEN 10)",
                getNextTag(true) + " oK STATUS completed"});
        mFolder.open(OpenMode.READ_WRITE);
        int unreadCount = mFolder.getUnreadMessageCount();
        assertEquals("getUnreadMessageCount with literal string", 10, unreadCount);
    }

    public void testFetchFlagEnvelope() throws MessagingException {
        final MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);
        final Message message = mFolder.createMessage("1");

        final FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.FLAGS);
        fp.add(FetchProfile.Item.ENVELOPE);
        mock.expect(getNextTag(false) +
                " UID FETCH 1 \\(UID FLAGS INTERNALDATE RFC822\\.SIZE BODY\\.PEEK\\[HEADER.FIELDS" +
                        " \\(date subject from content-type to cc message-id\\)\\]\\)",
                new String[] {
                "* 9 fETCH (uID 1 rFC822.sIZE 120626 iNTERNALDATE \"17-may-2010 22:00:15 +0000\"" +
                        "fLAGS (\\Seen) bODY[hEADER.FIELDS (dAte sUbject fRom cOntent-type tO cC" +
                        " mEssage-id)]" +
                        " {279}",
                "From: Xxxxxx Yyyyy <userxx@android.com>",
                "Date: Mon, 17 May 2010 14:59:52 -0700",
                "Message-ID: <x0000000000000000000000000000000000000000000000y@android.com>",
                "Subject: ssubject",
                "To: android.test01@android.com",
                "Content-Type: multipart/mixed; boundary=a00000000000000000000000000b",
                "",
                ")",
                getNextTag(true) + " oK SUCCESS"
        });
        mFolder.fetch(new Message[] { message }, fp, null);

        assertEquals("android.test01@android.com", message.getHeader("to")[0]);
        assertEquals("Xxxxxx Yyyyy <userxx@android.com>", message.getHeader("from")[0]);
        assertEquals("multipart/mixed; boundary=a00000000000000000000000000b",
                message.getHeader("Content-Type")[0]);
        assertTrue(message.isSet(Flag.SEEN));

        // TODO: Test NO response.
    }

    /**
     * Test for fetching simple BODYSTRUCTURE.
     */
    public void testFetchBodyStructureSimple() throws Exception {
        final MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);
        final Message message = mFolder.createMessage("1");

        final FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.STRUCTURE);
        mock.expect(getNextTag(false) + " UID FETCH 1 \\(UID BODYSTRUCTURE\\)",
                new String[] {
                "* 9 fETCH (uID 1 bODYSTRUCTURE (\"tEXT\" \"pLAIN\" nIL" +
                        " nIL nIL nIL 18 3 nIL nIL nIL))",
                getNextTag(true) + " oK sUCCESS"
        });
        mFolder.fetch(new Message[] { message }, fp, null);

        // Check mime structure...
        MoreAsserts.assertEquals(
                new String[] {"text/plain"},
                message.getHeader("Content-Type")
                );
        assertNull(message.getHeader("Content-Transfer-Encoding"));
        assertNull(message.getHeader("Content-ID"));
        MoreAsserts.assertEquals(
                new String[] {";\n size=18"},
                message.getHeader("Content-Disposition")
                );

        MoreAsserts.assertEquals(
                new String[] {"TEXT"},
                message.getHeader("X-Android-Attachment-StoreData")
                );

        // TODO: Test NO response.
    }

    /**
     * Test for fetching complex muiltipart BODYSTRUCTURE.
     */
    public void testFetchBodyStructureMultipart() throws Exception {
        final MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);
        final Message message = mFolder.createMessage("1");

        final FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.STRUCTURE);
        mock.expect(getNextTag(false) + " UID FETCH 1 \\(UID BODYSTRUCTURE\\)",
                new String[] {
                "* 9 fETCH (uID 1 bODYSTRUCTURE ((\"tEXT\" \"pLAIN\" () {20}",
                "long content id#@!@#" +
                    " NIL \"7BIT\" 18 3 NIL NIL NIL)" +
                    "(\"IMAGE\" \"PNG\" (\"NAME\" {10}",
                "device.png) NIL NIL \"BASE64\" {6}",
                "117840 NIL (\"aTTACHMENT\" (\"fILENAME\" \"device.png\")) NIL)" +
                    "(\"TEXT\" \"HTML\" () NIL NIL \"7BIT\" 100 NIL 123 (\"aTTACHMENT\"" +
                    "(\"fILENAME\" {15}",
                "attachment.html \"SIZE\" 555)) NIL)" +
                    "((\"TEXT\" \"HTML\" NIL NIL \"BASE64\")(\"XXX\" \"YYY\"))" + // Nested
                    "\"mIXED\" (\"bOUNDARY\" \"00032556278a7005e40486d159ca\") NIL NIL))",
                getNextTag(true) + " oK SUCCESS"
        });
        mFolder.fetch(new Message[] { message }, fp, null);

        // Check mime structure...
        final Body body = message.getBody();
        assertTrue(body instanceof MimeMultipart);
        MimeMultipart mimeMultipart = (MimeMultipart) body;
        assertEquals(4, mimeMultipart.getCount());
        assertEquals("mixed", mimeMultipart.getSubTypeForTest());

        final Part part1 = mimeMultipart.getBodyPart(0);
        final Part part2 = mimeMultipart.getBodyPart(1);
        final Part part3 = mimeMultipart.getBodyPart(2);
        final Part part4 = mimeMultipart.getBodyPart(3);
        assertTrue(part1 instanceof MimeBodyPart);
        assertTrue(part2 instanceof MimeBodyPart);
        assertTrue(part3 instanceof MimeBodyPart);
        assertTrue(part4 instanceof MimeBodyPart);

        final MimeBodyPart mimePart1 = (MimeBodyPart) part1; // text/plain
        final MimeBodyPart mimePart2 = (MimeBodyPart) part2; // image/png
        final MimeBodyPart mimePart3 = (MimeBodyPart) part3; // text/html
        final MimeBodyPart mimePart4 = (MimeBodyPart) part4; // Nested

        MoreAsserts.assertEquals(
                new String[] {"1"},
                part1.getHeader("X-Android-Attachment-StoreData")
                );
        MoreAsserts.assertEquals(
                new String[] {"2"},
                part2.getHeader("X-Android-Attachment-StoreData")
                );
        MoreAsserts.assertEquals(
                new String[] {"3"},
                part3.getHeader("X-Android-Attachment-StoreData")
                );

        MoreAsserts.assertEquals(
                new String[] {"text/plain"},
                part1.getHeader("Content-Type")
                );
        MoreAsserts.assertEquals(
                new String[] {"image/png;\n NAME=\"device.png\""},
                part2.getHeader("Content-Type")
                );
        MoreAsserts.assertEquals(
                new String[] {"text/html"},
                part3.getHeader("Content-Type")
                );

        MoreAsserts.assertEquals(
                new String[] {"long content id#@!@#"},
                part1.getHeader("Content-ID")
                );
        assertNull(part2.getHeader("Content-ID"));
        assertNull(part3.getHeader("Content-ID"));

        MoreAsserts.assertEquals(
                new String[] {"7BIT"},
                part1.getHeader("Content-Transfer-Encoding")
                );
        MoreAsserts.assertEquals(
                new String[] {"BASE64"},
                part2.getHeader("Content-Transfer-Encoding")
                );
        MoreAsserts.assertEquals(
                new String[] {"7BIT"},
                part3.getHeader("Content-Transfer-Encoding")
                );

        MoreAsserts.assertEquals(
                new String[] {";\n size=18"},
                part1.getHeader("Content-Disposition")
                );
        MoreAsserts.assertEquals(
                new String[] {"attachment;\n filename=\"device.png\";\n size=117840"},
                part2.getHeader("Content-Disposition")
                );
        MoreAsserts.assertEquals(
                new String[] {"attachment;\n filename=\"attachment.html\";\n size=\"555\""},
                part3.getHeader("Content-Disposition")
                );

        // Check the nested parts.
        final Body part4body = part4.getBody();
        assertTrue(part4body instanceof MimeMultipart);
        MimeMultipart mimeMultipartPart4 = (MimeMultipart) part4body;
        assertEquals(2, mimeMultipartPart4.getCount());

        final MimeBodyPart mimePart41 = (MimeBodyPart) mimeMultipartPart4.getBodyPart(0);
        final MimeBodyPart mimePart42 = (MimeBodyPart) mimeMultipartPart4.getBodyPart(1);

        MoreAsserts.assertEquals(new String[] {"4.1"},
                mimePart41.getHeader("X-Android-Attachment-StoreData")
                );
        MoreAsserts.assertEquals(new String[] {"4.2"},
                mimePart42.getHeader("X-Android-Attachment-StoreData")
                );
    }

    public void testFetchBodySane() throws MessagingException {
        final MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);
        final Message message = mFolder.createMessage("1");

        final FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.BODY_SANE);
        mock.expect(getNextTag(false) + " UID FETCH 1 \\(UID BODY.PEEK\\[\\]<0.51200>\\)",
                new String[] {
                "* 9 fETCH (uID 1 bODY[] {23}",
                "from: a@b.com", // 15 bytes
                "", // 2
                "test", // 6
                ")",
                getNextTag(true) + " oK SUCCESS"
        });
        mFolder.fetch(new Message[] { message }, fp, null);
        assertEquals("a@b.com", message.getHeader("from")[0]);

        // TODO: Test NO response.
    }

    public void testFetchBody() throws MessagingException {
        final MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);
        final Message message = mFolder.createMessage("1");

        final FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.BODY);
        mock.expect(getNextTag(false) + " UID FETCH 1 \\(UID BODY.PEEK\\[\\]\\)",
                new String[] {
                "* 9 fETCH (uID 1 bODY[] {23}",
                "from: a@b.com", // 15 bytes
                "", // 2
                "test", // 6
                ")",
                getNextTag(true) + " oK SUCCESS"
        });
        mFolder.fetch(new Message[] { message }, fp, null);
        assertEquals("a@b.com", message.getHeader("from")[0]);

        // TODO: Test NO response.
    }

    public void testFetchAttachment() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);
        final Message message = mFolder.createMessage("1");

        final FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.STRUCTURE);
        mock.expect(getNextTag(false) + " UID FETCH 1 \\(UID BODYSTRUCTURE\\)",
                new String[] {
                "* 9 fETCH (uID 1 bODYSTRUCTURE ((\"tEXT\" \"PLAIN\" (\"cHARSET\" \"iSO-8859-1\")" +
                        " CID nIL \"7bIT\" 18 3 NIL NIL NIL)" +
                        "(\"IMAGE\" \"PNG\"" +
                        " (\"nAME\" \"device.png\") NIL NIL \"bASE64\" 117840 NIL (\"aTTACHMENT\"" +
                        "(\"fILENAME\" \"device.png\")) NIL)" +
                        "\"mIXED\"))",
                getNextTag(true) + " OK SUCCESS"
        });
        mFolder.fetch(new Message[] { message }, fp, null);

        // Check mime structure, and get the second part.
        Body body = message.getBody();
        assertTrue(body instanceof MimeMultipart);
        MimeMultipart mimeMultipart = (MimeMultipart) body;
        assertEquals(2, mimeMultipart.getCount());

        Part part1 = mimeMultipart.getBodyPart(1);
        assertTrue(part1 instanceof MimeBodyPart);
        MimeBodyPart mimePart1 = (MimeBodyPart) part1;

        // Fetch the second part
        fp.clear();
        fp.add(mimePart1);
        mock.expect(getNextTag(false) + " UID FETCH 1 \\(UID BODY.PEEK\\[2\\]\\)",
                new String[] {
                "* 9 fETCH (uID 1 bODY[2] {4}",
                "YWJj)", // abc in base64
                getNextTag(true) + " oK SUCCESS"
        });
        mFolder.fetch(new Message[] { message }, fp, null);

        assertEquals("abc",
                Utility.fromUtf8(IOUtils.toByteArray(mimePart1.getBody().getInputStream())));

        // TODO: Test NO response.
    }

    /**
     * Test for proper operations on servers that return "NIL" for empty message bodies.
     */
    public void testNilMessage() throws MessagingException {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);

        // Prepare to pull structure and peek body text - this is like the "large message"
        // loop in MessagingController.synchronizeMailboxGeneric()
        FetchProfile fp = new FetchProfile();fp.clear();
        fp.add(FetchProfile.Item.STRUCTURE);
        Message message1 = mFolder.createMessage("1");
        mock.expect(getNextTag(false) + " UID FETCH 1 \\(UID BODYSTRUCTURE\\)", new String[] {
                "* 1 fETCH (uID 1 bODYSTRUCTURE (tEXT pLAIN nIL nIL nIL 7bIT 0 0 nIL nIL nIL))",
                getNextTag(true) + " oK SUCCESS"
        });
        mFolder.fetch(new Message[] { message1 }, fp, null);

        // The expected result for an empty body is:
        //   * 1 FETCH (UID 1 BODY[TEXT] {0})
        // But some servers are returning NIL for the empty body:
        //   * 1 FETCH (UID 1 BODY[TEXT] NIL)
        // Because this breaks our little parser, fetch() skips over empty parts.
        // The rest of this test is confirming that this is the case.

        mock.expect(getNextTag(false) + " UID FETCH 1 \\(UID BODY.PEEK\\[TEXT\\]\\)", new String[] {
                "* 1 fETCH (uID 1 bODY[tEXT] nIL)",
                getNextTag(true) + " oK SUCCESS"
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
        mFolder.open(OpenMode.READ_WRITE);

        // Create a message, and make sure it's not "SEEN".
        Message message1 = mFolder.createMessage("1");
        assertFalse(message1.isSet(Flag.SEEN));

        FetchProfile fp = new FetchProfile();
        fp.clear();
        fp.add(FetchProfile.Item.FLAGS);
        mock.expect(getNextTag(false) + " UID FETCH 1 \\(UID FLAGS\\)",
                new String[] {
                "* 1 fETCH (uID 1 fLAGS (\\Seen))",
                "* 2 fETCH (fLAGS (\\Seen))",
                getNextTag(true) + " oK SUCCESS"
        });

        // Shouldn't crash
        mFolder.fetch(new Message[] { message1 }, fp, null);

        // And the message is "SEEN".
        assertTrue(message1.isSet(Flag.SEEN));
    }


    private ImapMessage prepareForAppendTest(MockTransport mock, String response) throws Exception {
        ImapMessage message = (ImapMessage) mFolder.createMessage("initial uid");
        message.setFrom(new Address("me@test.com"));
        message.setRecipient(RecipientType.TO, new Address("you@test.com"));
        message.setMessageId("<message.id@test.com>");
        message.setFlagDirectlyForTest(Flag.SEEN, true);
        message.setBody(new TextBody("Test Body"));

        // + go ahead
        // * 12345 EXISTS
        // OK [APPENDUID 627684530 17] (Success)

        mock.expect(getNextTag(false) +
                " APPEND \\\"" + FOLDER_ENCODED + "\\\" \\(\\\\SEEN\\) \\{166\\}",
                new String[] {"+ gO aHead"});

        mock.expectLiterally("From: me@test.com", NO_REPLY);
        mock.expectLiterally("To: you@test.com", NO_REPLY);
        mock.expectLiterally("Message-ID: <message.id@test.com>", NO_REPLY);
        mock.expectLiterally("Content-Type: text/plain;", NO_REPLY);
        mock.expectLiterally(" charset=utf-8", NO_REPLY);
        mock.expectLiterally("Content-Transfer-Encoding: base64", NO_REPLY);
        mock.expectLiterally("", NO_REPLY);
        mock.expectLiterally("VGVzdCBCb2R5", NO_REPLY);
        mock.expectLiterally("", new String[] {
                "* 7 eXISTS",
                getNextTag(true) + " " + response
                });
        return message;
    }

    /**
     * Test for APPEND when the response has APPENDUID.
     */
    public void testAppendMessages() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);

        ImapMessage message = prepareForAppendTest(mock, "oK [aPPENDUID 1234567 13] (Success)");

        mFolder.appendMessage(getInstrumentation().getTargetContext(), message, false);

        assertEquals("13", message.getUid());
        assertEquals(7, mFolder.getMessageCount());
    }

    /**
     * Test for APPEND when the response doesn't have APPENDUID.
     */
    public void testAppendMessagesNoAppendUid() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);

        ImapMessage message = prepareForAppendTest(mock, "OK Success");

        // First try w/o parenthesis
        mock.expectLiterally(
                getNextTag(false) + " UID SEARCH HEADER MESSAGE-ID <message.id@test.com>",
                new String[] {
                    "* sEARCH 321",
                    getNextTag(true) + " oK success"
                });
        // If that fails, then try w/ parenthesis
        mock.expectLiterally(
                getNextTag(false) + " UID SEARCH (HEADER MESSAGE-ID <message.id@test.com>)",
                new String[] {
                    "* sEARCH 321",
                    getNextTag(true) + " oK success"
                });

        mFolder.appendMessage(getInstrumentation().getTargetContext(), message, false);

        assertEquals("321", message.getUid());
    }

    /**
     * Test for append failure.
     *
     * We don't check the response for APPEND.  We just SEARCH for the message-id to get the UID.
     * If append has failed, the SEARCH command returns no UID, and the UID of the message is left
     * unset.
     */
    public void testAppendFailure() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);

        ImapMessage message = prepareForAppendTest(mock, "NO No space left on the server.");
        assertEquals("initial uid", message.getUid());
        // First try w/o parenthesis
        mock.expectLiterally(
                getNextTag(false) + " UID SEARCH HEADER MESSAGE-ID <message.id@test.com>",
                new String[] {
                    "* sEARCH", // not found
                    getNextTag(true) + " oK Search completed."
                });
        // If that fails, then try w/ parenthesis
        mock.expectLiterally(
                getNextTag(false) + " UID SEARCH (HEADER MESSAGE-ID <message.id@test.com>)",
                new String[] {
                    "* sEARCH", // not found
                    getNextTag(true) + " oK Search completed."
                });

        mFolder.appendMessage(getInstrumentation().getTargetContext(), message, false);

        // Shouldn't have changed
        assertEquals("initial uid", message.getUid());
    }

    public void testGetAllFolders() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        expectLogin(mock);

        expectNoop(mock, true);
        mock.expect(getNextTag(false) + " LIST \"\" \"\\*\"",
                new String[] {
                "* lIST (\\HAsNoChildren) \"/\" \"inbox\"",
                "* lIST (\\hAsnochildren) \"/\" \"Drafts\"",
                "* lIST (\\nOselect) \"/\" \"no select\"",
                "* lIST (\\HAsNoChildren) \"/\" \"&ZeVnLIqe-\"", // Japanese folder name
                getNextTag(true) + " oK SUCCESS"
                });
        Folder[] folders = mStore.updateFolders();
        ImapFolder testFolder;

        testFolder = (ImapFolder) folders[0];
        assertEquals("INBOX", testFolder.getName());
        assertEquals(SELECTABLE_BITS, testFolder.mMailbox.mFlags & SELECTABLE_BITS);

        testFolder = (ImapFolder) folders[1];
        assertEquals("no select", testFolder.getName());
        assertEquals(0, testFolder.mMailbox.mFlags & SELECTABLE_BITS);

        testFolder = (ImapFolder) folders[2];
        assertEquals("\u65E5\u672C\u8A9E", testFolder.getName());
        assertEquals(SELECTABLE_BITS, testFolder.mMailbox.mFlags & SELECTABLE_BITS);

        testFolder = (ImapFolder) folders[3];
        assertEquals("Drafts", testFolder.getName());
        assertEquals(SELECTABLE_BITS, testFolder.mMailbox.mFlags & SELECTABLE_BITS);
        // TODO test with path prefix
        // TODO: Test NO response.
    }

    public void testEncodeFolderName() {
        // null prefix
        assertEquals("",
                ImapStore.encodeFolderName("", null));
        assertEquals("a",
                ImapStore.encodeFolderName("a", null));
        assertEquals("XYZ",
                ImapStore.encodeFolderName("XYZ", null));
        assertEquals("&ZeVnLIqe-",
                ImapStore.encodeFolderName("\u65E5\u672C\u8A9E", null));
        assertEquals("!&ZeVnLIqe-!",
                ImapStore.encodeFolderName("!\u65E5\u672C\u8A9E!", null));
        // empty prefix (same as a null prefix)
        assertEquals("",
                ImapStore.encodeFolderName("", ""));
        assertEquals("a",
                ImapStore.encodeFolderName("a", ""));
        assertEquals("XYZ",
                ImapStore.encodeFolderName("XYZ", ""));
        assertEquals("&ZeVnLIqe-",
                ImapStore.encodeFolderName("\u65E5\u672C\u8A9E", ""));
        assertEquals("!&ZeVnLIqe-!",
                ImapStore.encodeFolderName("!\u65E5\u672C\u8A9E!", ""));
        // defined prefix
        assertEquals("[Gmail]/",
                ImapStore.encodeFolderName("", "[Gmail]/"));
        assertEquals("[Gmail]/a",
                ImapStore.encodeFolderName("a", "[Gmail]/"));
        assertEquals("[Gmail]/XYZ",
                ImapStore.encodeFolderName("XYZ", "[Gmail]/"));
        assertEquals("[Gmail]/&ZeVnLIqe-",
                ImapStore.encodeFolderName("\u65E5\u672C\u8A9E", "[Gmail]/"));
        assertEquals("[Gmail]/!&ZeVnLIqe-!",
                ImapStore.encodeFolderName("!\u65E5\u672C\u8A9E!", "[Gmail]/"));
        // Add prefix to special mailbox "INBOX" [case insensitive), no affect
        assertEquals("INBOX",
                ImapStore.encodeFolderName("INBOX", "[Gmail]/"));
        assertEquals("inbox",
                ImapStore.encodeFolderName("inbox", "[Gmail]/"));
        assertEquals("InBoX",
                ImapStore.encodeFolderName("InBoX", "[Gmail]/"));
    }

    public void testDecodeFolderName() {
        // null prefix
        assertEquals("",
                ImapStore.decodeFolderName("", null));
        assertEquals("a",
                ImapStore.decodeFolderName("a", null));
        assertEquals("XYZ",
                ImapStore.decodeFolderName("XYZ", null));
        assertEquals("\u65E5\u672C\u8A9E",
                ImapStore.decodeFolderName("&ZeVnLIqe-", null));
        assertEquals("!\u65E5\u672C\u8A9E!",
                ImapStore.decodeFolderName("!&ZeVnLIqe-!", null));
        // empty prefix (same as a null prefix)
        assertEquals("",
                ImapStore.decodeFolderName("", ""));
        assertEquals("a",
                ImapStore.decodeFolderName("a", ""));
        assertEquals("XYZ",
                ImapStore.decodeFolderName("XYZ", ""));
        assertEquals("\u65E5\u672C\u8A9E",
                ImapStore.decodeFolderName("&ZeVnLIqe-", ""));
        assertEquals("!\u65E5\u672C\u8A9E!",
                ImapStore.decodeFolderName("!&ZeVnLIqe-!", ""));
        // defined prefix; prefix found, prefix removed
        assertEquals("",
                ImapStore.decodeFolderName("[Gmail]/", "[Gmail]/"));
        assertEquals("a",
                ImapStore.decodeFolderName("[Gmail]/a", "[Gmail]/"));
        assertEquals("XYZ",
                ImapStore.decodeFolderName("[Gmail]/XYZ", "[Gmail]/"));
        assertEquals("\u65E5\u672C\u8A9E",
                ImapStore.decodeFolderName("[Gmail]/&ZeVnLIqe-", "[Gmail]/"));
        assertEquals("!\u65E5\u672C\u8A9E!",
                ImapStore.decodeFolderName("[Gmail]/!&ZeVnLIqe-!", "[Gmail]/"));
        // defined prefix; prefix not found, no affect
        assertEquals("INBOX/",
                ImapStore.decodeFolderName("INBOX/", "[Gmail]/"));
        assertEquals("INBOX/a",
                ImapStore.decodeFolderName("INBOX/a", "[Gmail]/"));
        assertEquals("INBOX/XYZ",
                ImapStore.decodeFolderName("INBOX/XYZ", "[Gmail]/"));
        assertEquals("INBOX/\u65E5\u672C\u8A9E",
                ImapStore.decodeFolderName("INBOX/&ZeVnLIqe-", "[Gmail]/"));
        assertEquals("INBOX/!\u65E5\u672C\u8A9E!",
                ImapStore.decodeFolderName("INBOX/!&ZeVnLIqe-!", "[Gmail]/"));
    }

    public void testEnsurePrefixIsValid() {
        // Test mPathSeparator == null
        mStore.mPathSeparator = null;
        mStore.mPathPrefix = null;
        mStore.ensurePrefixIsValid();
        assertNull(mStore.mPathPrefix);

        mStore.mPathPrefix = "";
        mStore.ensurePrefixIsValid();
        assertEquals("", mStore.mPathPrefix);

        mStore.mPathPrefix = "foo";
        mStore.ensurePrefixIsValid();
        assertEquals("foo", mStore.mPathPrefix);

        mStore.mPathPrefix = "foo.";
        mStore.ensurePrefixIsValid();
        assertEquals("foo.", mStore.mPathPrefix);

        // Test mPathSeparator == ""
        mStore.mPathSeparator = "";
        mStore.mPathPrefix = null;
        mStore.ensurePrefixIsValid();
        assertNull(mStore.mPathPrefix);

        mStore.mPathPrefix = "";
        mStore.ensurePrefixIsValid();
        assertEquals("", mStore.mPathPrefix);

        mStore.mPathPrefix = "foo";
        mStore.ensurePrefixIsValid();
        assertEquals("foo", mStore.mPathPrefix);

        mStore.mPathPrefix = "foo.";
        mStore.ensurePrefixIsValid();
        assertEquals("foo.", mStore.mPathPrefix);

        // Test mPathSeparator is non-empty
        mStore.mPathSeparator = ".";
        mStore.mPathPrefix = null;
        mStore.ensurePrefixIsValid();
        assertNull(mStore.mPathPrefix);

        mStore.mPathPrefix = "";
        mStore.ensurePrefixIsValid();
        assertEquals("", mStore.mPathPrefix);

        mStore.mPathPrefix = "foo";
        mStore.ensurePrefixIsValid();
        assertEquals("foo.", mStore.mPathPrefix);

        // Trailing separator; path separator NOT appended
        mStore.mPathPrefix = "foo.";
        mStore.ensurePrefixIsValid();
        assertEquals("foo.", mStore.mPathPrefix);

        // Trailing punctuation has no affect; path separator still appended
        mStore.mPathPrefix = "foo/";
        mStore.ensurePrefixIsValid();
        assertEquals("foo/.", mStore.mPathPrefix);
    }

    public void testOpen() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        expectLogin(mock);

        final Folder folder = mStore.getFolder("test");

        // Not exist
        mock.expect(getNextTag(false) + " SELECT \\\"test\\\"",
                new String[] {
                getNextTag(true) + " nO no such mailbox"
                });
        try {
            folder.open(OpenMode.READ_WRITE);
            fail();
        } catch (MessagingException expected) {
        }

        // READ-WRITE
        expectNoop(mock, true); // Need it because we reuse the connection.
        mock.expect(getNextTag(false) + " SELECT \\\"test\\\"",
                new String[] {
                "* 1 eXISTS",
                getNextTag(true) + " oK [rEAD-wRITE]"
                });

        folder.open(OpenMode.READ_WRITE);
        assertTrue(folder.exists());
        assertEquals(1, folder.getMessageCount());
        assertEquals(OpenMode.READ_WRITE, folder.getMode());

        assertTrue(folder.isOpen());
        folder.close(false);
        assertFalse(folder.isOpen());

        // READ-ONLY
        expectNoop(mock, true); // Need it because we reuse the connection.
        mock.expect(getNextTag(false) + " SELECT \\\"test\\\"",
                new String[] {
                "* 2 eXISTS",
                getNextTag(true) + " oK [rEAD-oNLY]"
                });

        folder.open(OpenMode.READ_WRITE);
        assertTrue(folder.exists());
        assertEquals(2, folder.getMessageCount());
        assertEquals(OpenMode.READ_ONLY, folder.getMode());

        // Try to re-open as read-write.  Should send SELECT again.
        expectNoop(mock, true); // Need it because we reuse the connection.
        mock.expect(getNextTag(false) + " SELECT \\\"test\\\"",
                new String[] {
                "* 15 eXISTS",
                getNextTag(true) + " oK selected"
                });

        folder.open(OpenMode.READ_WRITE);
        assertTrue(folder.exists());
        assertEquals(15, folder.getMessageCount());
        assertEquals(OpenMode.READ_WRITE, folder.getMode());
    }

    public void testExists() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        expectLogin(mock);

        // Folder exists
        Folder folder = mStore.getFolder("\u65E5\u672C\u8A9E");
        mock.expect(getNextTag(false) + " STATUS \\\"&ZeVnLIqe-\\\" \\(UIDVALIDITY\\)",
                new String[] {
                "* sTATUS \"&ZeVnLIqe-\" (mESSAGES 10)",
                getNextTag(true) + " oK SUCCESS"
                });

        assertTrue(folder.exists());

        // Connection verification
        expectNoop(mock, true);

        // Doesn't exist
        folder = mStore.getFolder("no such folder");
        mock.expect(getNextTag(false) + " STATUS \\\"no such folder\\\" \\(UIDVALIDITY\\)",
                new String[] {
                getNextTag(true) + " NO No such folder!"
                });

        assertFalse(folder.exists());
    }

    public void testCreate() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        expectLogin(mock);

        // Success
        Folder folder = mStore.getFolder("\u65E5\u672C\u8A9E");

        assertTrue(folder.canCreate(FolderType.HOLDS_MESSAGES));

        mock.expect(getNextTag(false) + " CREATE \\\"&ZeVnLIqe-\\\"",
                new String[] {
                getNextTag(true) + " oK Success"
                });

        assertTrue(folder.create(FolderType.HOLDS_MESSAGES));

        // Connection verification
        expectNoop(mock, true);

        // Failure
        mock.expect(getNextTag(false) + " CREATE \\\"&ZeVnLIqe-\\\"",
                new String[] {
                getNextTag(true) + " nO Can't create folder"
                });

        assertFalse(folder.create(FolderType.HOLDS_MESSAGES));
    }

    private void setupCopyMessages(boolean withUidPlus) throws Exception {
        mCopyMock = openAndInjectMockTransport();
        setupOpenFolder(mCopyMock, new String[] {"* iD nIL", "oK"}, "rEAD-wRITE", withUidPlus);
        mFolder.open(OpenMode.READ_WRITE);

        mCopyToFolder = mStore.getFolder("\u65E5\u672C\u8A9E");
        Message m1 = mFolder.createMessage("11");
        m1.setMessageId("<4D8978AE.0000005D@m58.foo.com>");
        Message m2 = mFolder.createMessage("12");
        m2.setMessageId("<549373104MSOSI1:145OSIMS@bar.com>");
        mCopyMessages = new Message[] { m1, m2 };
    }

    /**
     * Returns the pattern for the IMAP request to copy messages.
     */
    private String getCopyMessagesPattern() {
        return getNextTag(false) + " UID COPY 11\\,12 \\\"&ZeVnLIqe-\\\"";
    }

    /**
     * Returns the pattern for the IMAP request to search for messages based on Message-Id.
     */
    private String getSearchMessagesPattern(String messageId) {
        return getNextTag(false) + " UID SEARCH HEADER Message-Id \"" + messageId + "\"";
    }

    /**
     * Counts the number of times the callback methods are invoked.
     */
    private static class MessageUpdateCallbackCounter implements Folder.MessageUpdateCallbacks {
        int messageNotFoundCalled;
        int messageUidChangeCalled;

        @Override
        public void onMessageNotFound(Message message) {
            ++messageNotFoundCalled;
        }
        @Override
        public void onMessageUidChange(Message message, String newUid) {
            ++messageUidChangeCalled;
        }
    }

    // TODO Test additional degenerate cases; src msg not found, ...
    // Golden case; successful copy with UIDCOPY result
    public void testCopyMessages1() throws Exception {
        setupCopyMessages(true);
        mCopyMock.expect(getCopyMessagesPattern(),
                new String[] {
                    "* Ok COPY in progress",
                    getNextTag(true) + " oK [COPYUID 777 11,12 45,46] UID COPY completed"
                });

        MessageUpdateCallbackCounter cb = new MessageUpdateCallbackCounter();
        mFolder.copyMessages(mCopyMessages, mCopyToFolder, cb);

        assertEquals(0, cb.messageNotFoundCalled);
        assertEquals(2, cb.messageUidChangeCalled);
    }

    // Degenerate case; NO, un-tagged response works
    public void testCopyMessages2() throws Exception {
        setupCopyMessages(true);
        mCopyMock.expect(getCopyMessagesPattern(),
                new String[] {
                    "* No Some error occured during the copy",
                    getNextTag(true) + " oK [COPYUID 777 11,12 45,46] UID COPY completed"
                });

        MessageUpdateCallbackCounter cb = new MessageUpdateCallbackCounter();
        mFolder.copyMessages(mCopyMessages, mCopyToFolder, cb);

        assertEquals(0, cb.messageNotFoundCalled);
        assertEquals(2, cb.messageUidChangeCalled);
    }

    // Degenerate case; NO, tagged response throws MessagingException
    public void testCopyMessages3() throws Exception {
        try {
            setupCopyMessages(false);
            mCopyMock.expect(getCopyMessagesPattern(),
                    new String[] {
                        getNextTag(true) + " No copy did not finish"
                    });

            mFolder.copyMessages(mCopyMessages, mCopyToFolder, null);

            fail("MessagingException expected.");
        } catch (MessagingException expected) {
        }
    }

    // Degenerate case; BAD, un-tagged response throws MessagingException
    public void testCopyMessages4() throws Exception {
        try {
            setupCopyMessages(true);
            mCopyMock.expect(getCopyMessagesPattern(),
                    new String[] {
                        "* BAD failed for some reason",
                        getNextTag(true) + " Ok copy completed"
                    });

            mFolder.copyMessages(mCopyMessages, mCopyToFolder, null);

            fail("MessagingException expected.");
        } catch (MessagingException expected) {
        }
    }

    // Degenerate case; BAD, tagged response throws MessagingException
    public void testCopyMessages5() throws Exception {
        try {
            setupCopyMessages(false);
            mCopyMock.expect(getCopyMessagesPattern(),
                    new String[] {
                        getNextTag(true) + " BaD copy completed"
                    });

            mFolder.copyMessages(mCopyMessages, mCopyToFolder, null);

            fail("MessagingException expected.");
        } catch (MessagingException expected) {
        }
    }

    // Golden case; successful copy getting UIDs via search
    public void testCopyMessages6() throws Exception {
        setupCopyMessages(false);
        mCopyMock.expect(getCopyMessagesPattern(),
                new String[] {
                    getNextTag(true) + " oK UID COPY completed",
                });
        // New connection, so, we need to login again & the tag count gets reset
        int saveTag = resetTag();
        expectLogin(mCopyMock, new String[] {"* iD nIL", "oK"}, false);
        // Select destination folder
        expectSelect(mCopyMock, "&ZeVnLIqe-", "rEAD-wRITE");
        // Perform searches
        mCopyMock.expect(getSearchMessagesPattern("<4D8978AE.0000005D@m58.foo.com>"),
                new String[] {
                    "* SeArCh 777",
                    getNextTag(true) + " oK UID SEARCH completed (1 msgs in 3.14159 secs)",
                });
        mCopyMock.expect(getSearchMessagesPattern("<549373104MSOSI1:145OSIMS@bar.com>"),
                new String[] {
                    "* sEaRcH 1818",
                    getNextTag(true) + " oK UID SEARCH completed (1 msgs in 2.71828 secs)",
                });
        // Resume commands on the initial connection
        resetTag(saveTag);
        // Select the original folder
        expectSelect(mCopyMock, FOLDER_ENCODED, "rEAD-wRITE");

        MessageUpdateCallbackCounter cb = new MessageUpdateCallbackCounter();
        mFolder.copyMessages(mCopyMessages, mCopyToFolder, cb);

        assertEquals(0, cb.messageNotFoundCalled);
        assertEquals(2, cb.messageUidChangeCalled);
    }

    // Degenerate case; searches turn up nothing
    public void testCopyMessages7() throws Exception {
        setupCopyMessages(false);
        mCopyMock.expect(getCopyMessagesPattern(),
                new String[] {
                    getNextTag(true) + " oK UID COPY completed",
                });
        // New connection, so, we need to login again & the tag count gets reset
        int saveTag = resetTag();
        expectLogin(mCopyMock, new String[] {"* iD nIL", "oK"}, false);
        // Select destination folder
        expectSelect(mCopyMock, "&ZeVnLIqe-", "rEAD-wRITE");
        // Perform searches
        mCopyMock.expect(getSearchMessagesPattern("<4D8978AE.0000005D@m58.foo.com>"),
                new String[] {
                    "* SeArCh",
                    getNextTag(true) + " oK UID SEARCH completed (0 msgs in 6.02214 secs)",
                });
        mCopyMock.expect(getSearchMessagesPattern("<549373104MSOSI1:145OSIMS@bar.com>"),
                new String[] {
                    "* sEaRcH",
                    getNextTag(true) + " oK UID SEARCH completed (0 msgs in 2.99792 secs)",
                });
        // Resume commands on the initial connection
        resetTag(saveTag);
        // Select the original folder
        expectSelect(mCopyMock, FOLDER_ENCODED, "rEAD-wRITE");

        MessageUpdateCallbackCounter cb = new MessageUpdateCallbackCounter();
        mFolder.copyMessages(mCopyMessages, mCopyToFolder, cb);

        assertEquals(0, cb.messageNotFoundCalled);
        assertEquals(0, cb.messageUidChangeCalled);
    }

    // Degenerate case; search causes an exception; must be eaten
    public void testCopyMessages8() throws Exception {
        setupCopyMessages(false);
        mCopyMock.expect(getCopyMessagesPattern(),
                new String[] {
                    getNextTag(true) + " oK UID COPY completed",
                });
        // New connection, so, we need to login again & the tag count gets reset
        int saveTag = resetTag();
        expectLogin(mCopyMock, new String[] {"* iD nIL", "oK"}, false);
        // Select destination folder
        expectSelect(mCopyMock, "&ZeVnLIqe-", "rEAD-wRITE");
        // Perform searches
        mCopyMock.expect(getSearchMessagesPattern("<4D8978AE.0000005D@m58.foo.com>"),
                new String[] {
                    getNextTag(true) + " BaD search failed"
                });
        mCopyMock.expect(getSearchMessagesPattern("<549373104MSOSI1:145OSIMS@bar.com>"),
                new String[] {
                    getNextTag(true) + " BaD search failed"
                });
        // Resume commands on the initial connection
        resetTag(saveTag);
        // Select the original folder
        expectSelect(mCopyMock, FOLDER_ENCODED, "rEAD-wRITE");

        MessageUpdateCallbackCounter cb = new MessageUpdateCallbackCounter();
        mFolder.copyMessages(mCopyMessages, mCopyToFolder, cb);

        assertEquals(0, cb.messageNotFoundCalled);
        assertEquals(0, cb.messageUidChangeCalled);
    }

    public void testGetUnreadMessageCount() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);

        mock.expect(getNextTag(false) + " STATUS \\\"" + FOLDER_ENCODED + "\\\" \\(UNSEEN\\)",
                new String[] {
                "* sTATUS \"" + FOLDER_ENCODED + "\" (X 1 uNSEEN 123)",
                getNextTag(true) + " oK copy completed"
                });

        assertEquals(123, mFolder.getUnreadMessageCount());
    }

    public void testExpunge() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);

        mock.expect(getNextTag(false) + " EXPUNGE",
                new String[] {
                getNextTag(true) + " oK success"
                });

        mFolder.expunge();

        // TODO: Test NO response. (permission denied)
    }

    public void testSetFlags() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);

        Message[] messages = new Message[] {
                mFolder.createMessage("11"),
                mFolder.createMessage("12"),
                };

        // Set
        mock.expect(
                getNextTag(false) + " UID STORE 11\\,12 \\+FLAGS.SILENT \\(\\\\FLAGGED \\\\SEEN\\)",
                new String[] {
                getNextTag(true) + " oK success"
                });
        mFolder.setFlags(messages, new Flag[] {Flag.FLAGGED, Flag.SEEN}, true);

        // Clear
        mock.expect(
                getNextTag(false) + " UID STORE 11\\,12 \\-FLAGS.SILENT \\(\\\\DELETED\\)",
                new String[] {
                getNextTag(true) + " oK success"
                });
        mFolder.setFlags(messages, new Flag[] {Flag.DELETED}, false);

        // TODO: Test NO response. (src message not found)
    }

    public void testSearchForUids() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);

        // Single results
        mock.expect(
                getNextTag(false) + " UID SEARCH X",
                new String[] {
                        "* sEARCH 1",
                        getNextTag(true) + " oK success"
                });
        MoreAsserts.assertEquals(new String[] {
                "1"
                }, mFolder.searchForUids("X"));

        // Multiple results, including SEARCH with no UIDs.
        mock.expect(
                getNextTag(false) + " UID SEARCH UID 123",
                new String[] {
                        "* sEARCH 123 4 567",
                        "* search",
                        "* sEARCH 0",
                        "* SEARCH",
                        "* sEARCH 100 200 300",
                        getNextTag(true) + " oK success"
                });
        MoreAsserts.assertEquals(new String[] {
                "123", "4", "567", "0", "100", "200", "300"
                }, mFolder.searchForUids("UID 123"));

        // NO result
        mock.expect(
                getNextTag(false) + " UID SEARCH SOME CRITERIA",
                new String[] {
                        getNextTag(true) + " nO not found"
                });
        MoreAsserts.assertEquals(new String[] {
                }, mFolder.searchForUids("SOME CRITERIA"));

        // OK result, but result is empty. (Probably against RFC)
        mock.expect(
                getNextTag(false) + " UID SEARCH SOME CRITERIA",
                new String[] {
                        getNextTag(true) + " oK success"
                });
        MoreAsserts.assertEquals(new String[] {
                }, mFolder.searchForUids("SOME CRITERIA"));

        // OK result with empty search response.
        mock.expect(
                getNextTag(false) + " UID SEARCH SOME CRITERIA",
                new String[] {
                        "* search",
                        getNextTag(true) + " oK success"
                });
        MoreAsserts.assertEquals(new String[] {
                }, mFolder.searchForUids("SOME CRITERIA"));
    }


    public void testGetMessage() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);

        // Found
        mock.expect(
                getNextTag(false) + " UID SEARCH UID 123",
                new String[] {
                    "* sEARCH 123",
                getNextTag(true) + " oK success"
                });
        assertEquals("123", mFolder.getMessage("123").getUid());

        // Not found
        mock.expect(
                getNextTag(false) + " UID SEARCH UID 123",
                new String[] {
                getNextTag(true) + " nO not found"
                });
        assertNull(mFolder.getMessage("123"));
    }

    /** Test for getMessages(int, int, MessageRetrievalListener) */
    public void testGetMessages1() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);

        // Found
        mock.expect(
                getNextTag(false) + " UID SEARCH 3:5 NOT DELETED",
                new String[] {
                    "* sEARCH 3 4",
                getNextTag(true) + " oK success"
                });

        checkMessageUids(new String[] {"3", "4"}, mFolder.getMessages(3, 5, null));

        // Not found
        mock.expect(
                getNextTag(false) + " UID SEARCH 3:5 NOT DELETED",
                new String[] {
                getNextTag(true) + " nO not found"
                });

        checkMessageUids(new String[] {}, mFolder.getMessages(3, 5, null));
    }

    /**
     * Test for getMessages(String[] uids, MessageRetrievalListener) where uids != null.
     * (testGetMessages3() covers the case where uids == null.)
     */
    public void testGetMessages2() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);

        // No command will be sent
        checkMessageUids(new String[] {"3", "4", "5"},
                mFolder.getMessages(new String[] {"3", "4", "5"}, null));

        checkMessageUids(new String[] {},
                mFolder.getMessages(new String[] {}, null));
    }

    private static void checkMessageUids(String[] expectedUids, Message[] actualMessages) {
        ArrayList<String> list = new ArrayList<String>();
        for (Message m : actualMessages) {
            list.add(m.getUid());
        }
        MoreAsserts.assertEquals(expectedUids, list.toArray(new String[0]) );
    }

    /**
     * Test for {@link ImapStore#getConnection}
     */
    public void testGetConnection() throws Exception {
        MockTransport mock = openAndInjectMockTransport();

        // Start: No pooled connections.
        assertEquals(0, mStore.getConnectionPoolForTest().size());

        // Get 1st connection.
        final ImapConnection con1 = mStore.getConnection();
        assertNotNull(con1);
        assertEquals(0, mStore.getConnectionPoolForTest().size()); // Pool size not changed.
        assertFalse(con1.isTransportOpenForTest()); // Transport not open yet.

        // Open con1
        expectLogin(mock);
        con1.open();
        assertTrue(con1.isTransportOpenForTest());

        // Get 2nd connection.
        final ImapConnection con2 = mStore.getConnection();
        assertNotNull(con2);
        assertEquals(0, mStore.getConnectionPoolForTest().size()); // Pool size not changed.
        assertFalse(con2.isTransportOpenForTest()); // Transport not open yet.

        // con1 != con2
        assertNotSame(con1, con2);

        // New connection, so, we need to login again & the tag count gets reset
        int saveTag = resetTag();

        // Open con2
        expectLogin(mock);
        con2.open();
        assertTrue(con1.isTransportOpenForTest());

        // Now we have two open connections: con1 and con2

        // Save con1 in the pool.
        mStore.poolConnection(con1);
        assertEquals(1, mStore.getConnectionPoolForTest().size());

        // Get another connection.  Should get con1, after verifying the connection.
        saveTag = resetTag(saveTag);
        mock.expect(getNextTag(false) + " NOOP", new String[] {getNextTag(true) + " oK success"});

        final ImapConnection con1b = mStore.getConnection();
        assertEquals(0, mStore.getConnectionPoolForTest().size()); // No connections left in pool
        assertSame(con1, con1b);
        assertTrue(con1.isTransportOpenForTest()); // We opened it.

        // Save con2.
        mStore.poolConnection(con2);
        assertEquals(1, mStore.getConnectionPoolForTest().size());

        // Resume con2 tags ...
        resetTag(saveTag);

        // Try to get connection, but this time, connection gets closed.
        mock.expect(getNextTag(false) + " NOOP", new String[] {getNextTag(true) + "* bYE bye"});
        final ImapConnection con3 = mStore.getConnection();
        assertNotNull(con3);
        assertEquals(0, mStore.getConnectionPoolForTest().size()); // No connections left in pool

        // It should be a new connection.
        assertNotSame(con1, con3);
        assertNotSame(con2, con3);
    }

    public void testCheckSettings() throws Exception {
        MockTransport mock = openAndInjectMockTransport();

        expectLogin(mock);
        mStore.checkSettings();

        resetTag();
        expectLogin(mock, false, false, false,
                new String[] {"* iD nIL", "oK"}, "nO authentication failed");
        try {
            mStore.checkSettings();
            fail();
        } catch (MessagingException expected) {
        }
    }

    // Compatibility tests...

    /**
     * Getting an ALERT with a % mark in the message, which crashed the old parser.
     */
    public void testQuotaAlert() throws Exception {
        MockTransport mock = openAndInjectMockTransport();
        expectLogin(mock);

        // Success
        Folder folder = mStore.getFolder("INBOX");

        // The following response was copied from an actual bug...
        mock.expect(getNextTag(false) + " SELECT \"INBOX\"", new String[] {
            "* FLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen NonJunk $Forwarded Junk" +
                    " $Label4 $Label1 $Label2 $Label3 $Label5 $MDNSent Old)",
            "* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen NonJunk" +
                    " $Forwarded Junk $Label4 $Label1 $Label2 $Label3 $Label5 $MDNSent Old \\*)]",
            "* 6406 EXISTS",
            "* 0 RECENT",
            "* OK [UNSEEN 5338]",
            "* OK [UIDVALIDITY 1055957975]",
            "* OK [UIDNEXT 449625]",
            "* NO [ALERT] Mailbox is at 98% of quota",
            getNextTag(true) + " OK [READ-WRITE] Completed"});
        folder.open(OpenMode.READ_WRITE); // shouldn't crash.
        assertEquals(6406, folder.getMessageCount());
    }

    /**
     * Apparently some servers send a size in the wrong format. e.g. 123E
     */
    public void testFetchBodyStructureMalformed() throws Exception {
        final MockTransport mock = openAndInjectMockTransport();
        setupOpenFolder(mock);
        mFolder.open(OpenMode.READ_WRITE);
        final Message message = mFolder.createMessage("1");

        final FetchProfile fp = new FetchProfile();
        fp.add(FetchProfile.Item.STRUCTURE);
        mock.expect(getNextTag(false) + " UID FETCH 1 \\(UID BODYSTRUCTURE\\)",
                new String[] {
                "* 9 FETCH (UID 1 BODYSTRUCTURE (\"TEXT\" \"PLAIN\" ()" +
                        " NIL NIL NIL 123E 3))", // 123E isn't a number!
                getNextTag(true) + " OK SUCCESS"
        });
        mFolder.fetch(new Message[] { message }, fp, null);

        // Check mime structure...
        MoreAsserts.assertEquals(
                new String[] {"text/plain"},
                message.getHeader("Content-Type")
                );
        assertNull(message.getHeader("Content-Transfer-Encoding"));
        assertNull(message.getHeader("Content-ID"));

        // Doesn't have size=xxx
        assertNull(message.getHeader("Content-Disposition"));
    }

    /**
     * Folder name with special chars in it.
     *
     * Gmail puts the folder name in the OK response, which crashed the old parser if there's a
     * special char in the folder name.
     */
    public void testFolderNameWithSpecialChars() throws Exception {
        final String FOLDER_1 = "@u88**%_St";
        final String FOLDER_1_QUOTED = Pattern.quote(FOLDER_1);
        final String FOLDER_2 = "folder test_06";

        MockTransport mock = openAndInjectMockTransport();
        expectLogin(mock);

        // List folders.
        expectNoop(mock, true);
        mock.expect(getNextTag(false) + " LIST \"\" \"\\*\"",
                new String[] {
            "* LIST () \"/\" \"" + FOLDER_1 + "\"",
            "* LIST () \"/\" \"" + FOLDER_2 + "\"",
            getNextTag(true) + " OK SUCCESS"
        });
        final Folder[] folders = mStore.updateFolders();

        ArrayList<String> list = new ArrayList<String>();
        for (Folder f : folders) {
            list.add(f.getName());
        }
        MoreAsserts.assertEquals(
                new String[] {"INBOX", FOLDER_2, FOLDER_1},
                list.toArray(new String[0])
                );

        // Try to open the folders.
        expectNoop(mock, true);
        mock.expect(getNextTag(false) + " SELECT \"" + FOLDER_1_QUOTED + "\"", new String[] {
            "* FLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen)",
            "* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen \\*)]",
            "* 0 EXISTS",
            "* 0 RECENT",
            "* OK [UNSEEN 0]",
            "* OK [UIDNEXT 1]",
            getNextTag(true) + " OK [READ-WRITE] " + FOLDER_1});
        folders[2].open(OpenMode.READ_WRITE);
        folders[2].close(false);

        expectNoop(mock, true);
        mock.expect(getNextTag(false) + " SELECT \"" + FOLDER_2 + "\"", new String[] {
            "* FLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen)",
            "* OK [PERMANENTFLAGS (\\Answered \\Flagged \\Draft \\Deleted \\Seen \\*)]",
            "* 0 EXISTS",
            "* 0 RECENT",
            "* OK [UNSEEN 0]",
            "* OK [UIDNEXT 1]",
            getNextTag(true) + " OK [READ-WRITE] " + FOLDER_2});
        folders[1].open(OpenMode.READ_WRITE);
        folders[1].close(false);
    }

    /**
     * Callback for {@link #runAndExpectMessagingException}.
     */
    private interface RunAndExpectMessagingExceptionTarget {
        public void run(MockTransport mockTransport) throws Exception;
    }

    /**
     * Set up the usual mock transport, open the folder,
     * run {@link RunAndExpectMessagingExceptionTarget} and make sure a {@link MessagingException}
     * is thrown.
     */
    private void runAndExpectMessagingException(RunAndExpectMessagingExceptionTarget target)
            throws Exception {
        try {
            final MockTransport mockTransport = openAndInjectMockTransport();
            setupOpenFolder(mockTransport);
            mFolder.open(OpenMode.READ_WRITE);

            target.run(mockTransport);

            fail("MessagingException expected.");
        } catch (MessagingException expected) {
        }
    }

    /**
     * Make sure that IOExceptions are always converted to MessagingException.
     */
    public void testFetchIOException() throws Exception {
        runAndExpectMessagingException(new RunAndExpectMessagingExceptionTarget() {
            @Override
            public void run(MockTransport mockTransport) throws Exception {
                mockTransport.expectIOException();

                final Message message = mFolder.createMessage("1");
                final FetchProfile fp = new FetchProfile();
                fp.add(FetchProfile.Item.STRUCTURE);

                mFolder.fetch(new Message[] { message }, fp, null);
            }
        });
    }

    /**
     * Make sure that IOExceptions are always converted to MessagingException.
     */
    public void testUnreadMessageCountIOException() throws Exception {
        runAndExpectMessagingException(new RunAndExpectMessagingExceptionTarget() {
            @Override
            public void run(MockTransport mockTransport) throws Exception {
                mockTransport.expectIOException();

                mFolder.getUnreadMessageCount();
            }
        });
    }

    /**
     * Make sure that IOExceptions are always converted to MessagingException.
     */
    public void testCopyMessagesIOException() throws Exception {
        runAndExpectMessagingException(new RunAndExpectMessagingExceptionTarget() {
            @Override
            public void run(MockTransport mockTransport) throws Exception {
                mockTransport.expectIOException();

                final Message message = mFolder.createMessage("1");
                final Folder folder = mStore.getFolder("test");

                mFolder.copyMessages(new Message[] { message }, folder, null);
            }
        });
    }

    /**
     * Make sure that IOExceptions are always converted to MessagingException.
     */
    public void testSearchForUidsIOException() throws Exception {
        runAndExpectMessagingException(new RunAndExpectMessagingExceptionTarget() {
            @Override
            public void run(MockTransport mockTransport) throws Exception {
                mockTransport.expectIOException();

                mFolder.getMessage("uid");
            }
        });
    }

    /**
     * Make sure that IOExceptions are always converted to MessagingException.
     */
    public void testExpungeIOException() throws Exception {
        runAndExpectMessagingException(new RunAndExpectMessagingExceptionTarget() {
            @Override
            public void run(MockTransport mockTransport) throws Exception {
                mockTransport.expectIOException();

                mFolder.expunge();
            }
        });
    }

    /**
     * Make sure that IOExceptions are always converted to MessagingException.
     */
    public void testOpenIOException() throws Exception {
        runAndExpectMessagingException(new RunAndExpectMessagingExceptionTarget() {
            @Override
            public void run(MockTransport mockTransport) throws Exception {
                mockTransport.expectIOException();
                final Folder folder = mStore.getFolder("test");
                folder.open(OpenMode.READ_WRITE);
            }
        });
    }

    /** Creates a folder & mailbox */
    private ImapFolder createFolder(long id, String displayName, String serverId, char delimiter) {
        ImapFolder folder = new ImapFolder(null, serverId);
        Mailbox mailbox = new Mailbox();
        mailbox.mId = id;
        mailbox.mDisplayName = displayName;
        mailbox.mServerId = serverId;
        mailbox.mDelimiter = delimiter;
        mailbox.mFlags = 0xAAAAAAA8;
        folder.mMailbox = mailbox;
        return folder;
    }

    /** Tests creating folder hierarchies */
    public void testCreateHierarchy() {
        HashMap<String, ImapFolder> testMap = new HashMap<String, ImapFolder>();

        // Create hierarchy
        //   |-INBOX
        //   |  +-b
        //   |-a
        //   |  |-b
        //   |  |-c
        //   |  +-d
        //   |    +-b
        //   |      +-b
        //   +-g
        ImapFolder[] folders = {
            createFolder(1L, "INBOX", "INBOX", '/'),
            createFolder(2L, "b", "INBOX/b", '/'),
            createFolder(3L, "a", "a", '/'),
            createFolder(4L, "b", "a/b", '/'),
            createFolder(5L, "c", "a/c", '/'),
            createFolder(6L, "d", "a/d", '/'),
            createFolder(7L, "b", "a/d/b", '/'),
            createFolder(8L, "b", "a/d/b/b", '/'),
            createFolder(9L, "g", "g", '/'),
        };
        for (ImapFolder folder : folders) {
            testMap.put(folder.getName(), folder);
        }

        ImapStore.createHierarchy(testMap);
        // 'INBOX'
        assertEquals(-1L, folders[0].mMailbox.mParentKey);
        assertEquals(0xAAAAAAAB, folders[0].mMailbox.mFlags);
        // 'INBOX/b'
        assertEquals(1L, folders[1].mMailbox.mParentKey);
        assertEquals(0xAAAAAAA8, folders[1].mMailbox.mFlags);
        // 'a'
        assertEquals(-1L, folders[2].mMailbox.mParentKey);
        assertEquals(0xAAAAAAAB, folders[2].mMailbox.mFlags);
        // 'a/b'
        assertEquals(3L, folders[3].mMailbox.mParentKey);
        assertEquals(0xAAAAAAA8, folders[3].mMailbox.mFlags);
        // 'a/c'
        assertEquals(3L, folders[4].mMailbox.mParentKey);
        assertEquals(0xAAAAAAA8, folders[4].mMailbox.mFlags);
        // 'a/d'
        assertEquals(3L, folders[5].mMailbox.mParentKey);
        assertEquals(0xAAAAAAAB, folders[5].mMailbox.mFlags);
        // 'a/d/b'
        assertEquals(6L, folders[6].mMailbox.mParentKey);
        assertEquals(0xAAAAAAAB, folders[6].mMailbox.mFlags);
        // 'a/d/b/b'
        assertEquals(7L, folders[7].mMailbox.mParentKey);
        assertEquals(0xAAAAAAA8, folders[7].mMailbox.mFlags);
        // 'g'
        assertEquals(-1L, folders[8].mMailbox.mParentKey);
        assertEquals(0xAAAAAAA8, folders[8].mMailbox.mFlags);
    }
}
