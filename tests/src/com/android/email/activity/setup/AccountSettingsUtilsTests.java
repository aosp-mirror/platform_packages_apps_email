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

package com.android.email.activity.setup;

import com.android.email.tests.R;
import com.android.email.activity.setup.AccountSettingsUtils.Provider;

import android.content.Context;
import android.test.AndroidTestCase;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * This is a series of unit tests for the AccountSettingsUtils class.
 *
 * To run these tests,
 *  runtest -c com.android.email.activity.setup.AccountSettingsUtilsTests email
 */
@SmallTest
public class AccountSettingsUtilsTests extends InstrumentationTestCase {

    private Context mTestContext;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTestContext = getInstrumentation().getContext();
    }

    /**
     * Test server name inferences
     *
     * Incoming: Prepend "imap" or "pop3" to domain, unless "pop", "pop3",
     *          "imap", or "mail" are found.
     * Outgoing: Prepend "smtp" if "pop", "pop3", "imap" are found.
     *          Leave "mail" as-is.
     * TBD: Are there any useful defaults for exchange?
     */
    public void testGuessServerName() {
        assertEquals("foo.x.y.z", AccountSettingsUtils.inferServerName("x.y.z", "foo", null));
        assertEquals("Pop.y.z", AccountSettingsUtils.inferServerName("Pop.y.z", "foo", null));
        assertEquals("poP3.y.z", AccountSettingsUtils.inferServerName("poP3.y.z", "foo", null));
        assertEquals("iMAp.y.z", AccountSettingsUtils.inferServerName("iMAp.y.z", "foo", null));
        assertEquals("MaiL.y.z", AccountSettingsUtils.inferServerName("MaiL.y.z", "foo", null));

        assertEquals("bar.x.y.z", AccountSettingsUtils.inferServerName("x.y.z", null, "bar"));
        assertEquals("bar.y.z", AccountSettingsUtils.inferServerName("Pop.y.z", null, "bar"));
        assertEquals("bar.y.z", AccountSettingsUtils.inferServerName("poP3.y.z", null, "bar"));
        assertEquals("bar.y.z", AccountSettingsUtils.inferServerName("iMAp.y.z", null, "bar"));
        assertEquals("MaiL.y.z", AccountSettingsUtils.inferServerName("MaiL.y.z", null, "bar"));
    }

    public void testFindProviderForDomain() {
        Provider testProvider;
        // <provider id="gmail" label="Gmail" domain="gmail.com">
        //   <incoming uri="imap+ssl+://imap.gmail.com" username="$email" />
        //   <outgoing uri="smtp+ssl+://smtp.gmail.com" username="$email" />
        // </provider>
        testProvider = AccountSettingsUtils.findProviderForDomain(
                mTestContext, "gmail.com", R.xml.test_providers);
        assertNotNull(testProvider);
        assertEquals("imap+ssl+://imap.gmail.com", testProvider.incomingUriTemplate);
        assertEquals("smtp+ssl+://smtp.gmail.com", testProvider.outgoingUriTemplate);
        assertEquals("gmail.com", testProvider.domain);

        // <provider id="rr-generic" label="RoadRunner" domain="*.rr.com">
        //   <incoming uri="pop3://pop-server.$domain" username="$email" />
        //   <outgoing uri="smtp://mobile-smtp.roadrunner.com" username="$email" />
        // </provider>
        testProvider = AccountSettingsUtils.findProviderForDomain(
                mTestContext, "elmore.rr.com", R.xml.test_providers);
        assertNotNull(testProvider);
        assertEquals("pop3://pop-server.$domain", testProvider.incomingUriTemplate);
        assertEquals("smtp://mobile-smtp.roadrunner.com", testProvider.outgoingUriTemplate);
        assertEquals("elmore.rr.com", testProvider.domain);

        // Domain matches 2 providers; first one wins
        testProvider = AccountSettingsUtils.findProviderForDomain(
                mTestContext, "leonard.rr.com", R.xml.test_providers);
        assertNotNull(testProvider);
        assertEquals("pop3://pop-server.firstonewins.com", testProvider.incomingUriTemplate);

        // Domains that don't exist
        testProvider = AccountSettingsUtils.findProviderForDomain(
                mTestContext, "nonexist.com", R.xml.test_providers);
        assertNull(testProvider);
    }

    public void testGlobEndsWithIgnoreCase() {
        assertTrue(AccountSettingsUtils.wildEndsWithIgnoreCase(
                "yahoo.com.tw", ".??"));
        assertTrue(AccountSettingsUtils.wildEndsWithIgnoreCase(
                "abcd", "a??d"));
        assertFalse(AccountSettingsUtils.wildEndsWithIgnoreCase(
                "yahoo.com.tw.foo.com", ".??"));
        assertFalse(AccountSettingsUtils.wildEndsWithIgnoreCase(
                "abc", "a??d"));
    }

    public void testGlobStartsWithIgnoreCase() {
        assertTrue(AccountSettingsUtils.wildStartsWithIgnoreCase(
                "tw.yahoo.com", "??."));
        assertTrue(AccountSettingsUtils.wildStartsWithIgnoreCase(
                "abcdxyz", "a??d"));
        assertFalse(AccountSettingsUtils.wildStartsWithIgnoreCase(
                "abc", "a??d"));
    }

    public void testGlobEqualsIgnoreCase() {
        assertTrue(AccountSettingsUtils.wildEqualsIgnoreCase(
                "tw.yahoo.com", "??.yahoo.com"));
        assertTrue(AccountSettingsUtils.wildEqualsIgnoreCase(
                "yahoo.com.tw", "yahoo.com.??"));
        assertTrue(AccountSettingsUtils.wildEqualsIgnoreCase(
                "abcdxyz", "a??dxyz"));
        assertFalse(AccountSettingsUtils.wildEqualsIgnoreCase(
                "abc", "a??d"));
        assertFalse(AccountSettingsUtils.wildEqualsIgnoreCase(
                "abccxyz", "a??d"));
    }

    public void testGlobMatchIgnoreCase() {
        assertTrue(AccountSettingsUtils.globMatchIgnoreCase(
                "mail.yahoo.com", "mail*yahoo.com"));
        assertTrue(AccountSettingsUtils.globMatchIgnoreCase(
                "mail.foo.bar.yahoo.com", "mail*yahoo.com"));
        assertTrue(AccountSettingsUtils.globMatchIgnoreCase(
                "mail.notwhatyouwant.myyahoo.com", "mail*yahoo.com"));

        // Test other combinations
        assertTrue(AccountSettingsUtils.globMatchIgnoreCase(
                "yahoo.com", "yahoo.com"));
        assertFalse(AccountSettingsUtils.globMatchIgnoreCase(
                "yahoo.com.au", "yahoo.com"));
        assertFalse(AccountSettingsUtils.globMatchIgnoreCase(
                "yahoo.com", "yahoo.com.au"));

        // Try mixed case in the domain name
        assertTrue(AccountSettingsUtils.globMatchIgnoreCase(
                "GmAiL.cOm", "gMaIl.CoM"));

        assertFalse(AccountSettingsUtils.globMatchIgnoreCase(
                "nonexist.frr.com", "*.rr.com"));
        assertFalse(AccountSettingsUtils.globMatchIgnoreCase(
                "rr.com", "*.rr.com"));
        assertTrue(AccountSettingsUtils.globMatchIgnoreCase(
                "abbc.com", "ab*bc.com"));
        assertFalse(AccountSettingsUtils.globMatchIgnoreCase(
                "abc.com", "ab*bc.com"));

        try {
            AccountSettingsUtils.globMatchIgnoreCase(
                    "abc.com", "ab*bc*.com");
            fail("Should have thrown an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
        }
        try {
            AccountSettingsUtils.globMatchIgnoreCase(
                    null, "ab*bc*.com");
            fail("Should have thrown an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
        }
        try {
            AccountSettingsUtils.globMatchIgnoreCase(
                    "abc.com", null);
            fail("Should have thrown an IllegalArgumentException");
        } catch (IllegalArgumentException e) {
        }
    }

    public void testExpandTemplates() {
        Provider testProvider;
        // <provider id="cox" label="Cox" domain="cox.net">
        //   <incoming uri="pop3+ssl+://pop.east.cox.net" username="$user" />
        //   <outgoing uri="smtp+ssl+://smtp.east.cox.net" username="$user" />
        // </provider>
        testProvider = new Provider();
        testProvider.domain = "cox.net";
        testProvider.incomingUriTemplate = "pop3+ssl+://pop.east.$domain";
        testProvider.outgoingUriTemplate = "smtp+ssl+://smtp.east.$domain";
        testProvider.incomingUsernameTemplate = "$user";
        testProvider.outgoingUsernameTemplate = "$user";
        testProvider.expandTemplates("replUser@cox.net");
        assertEquals("replUser", testProvider.incomingUsername);
        assertEquals("replUser", testProvider.outgoingUsername);
        assertEquals("pop3+ssl+://pop.east.cox.net", testProvider.incomingUri);
        assertEquals("smtp+ssl+://smtp.east.cox.net", testProvider.outgoingUri);

        // <provider id="earthlink" label="Earthlink" domain="earthlink.net">
        //   <incoming uri="pop3://pop.earthlink.net" username="$email" />
        //   <outgoing uri="smtp://smtpauth.earthlink.net:587" username="$email" />
        // </provider>
        testProvider = new Provider();
        testProvider.domain = "earthlink.net";
        testProvider.incomingUriTemplate = "pop3://pop.earthlink.net";
        testProvider.outgoingUriTemplate = "smtp://smtpauth.earthlink.net:587";
        testProvider.incomingUsernameTemplate = "$email";
        testProvider.outgoingUsernameTemplate = "$email";
        testProvider.expandTemplates("replUser@earthlink.net");
        assertEquals("replUser@earthlink.net", testProvider.incomingUsername);
        assertEquals("replUser@earthlink.net", testProvider.outgoingUsername);
        assertEquals("pop3://pop.earthlink.net", testProvider.incomingUri);
        assertEquals("smtp://smtpauth.earthlink.net:587", testProvider.outgoingUri);

        // <provider id="tuffmail" label="Tuffmail" domain="tuffmail.com">
        //   <incoming uri="imap+ssl+://mail.mxes.net" username="$user_$domain" />
        //   <outgoing uri="smtp+ssl+://smtp.mxes.net" username="$user_$domain" />
        // </provider>
        testProvider = new Provider();
        testProvider.domain = "tuffmail.com";
        testProvider.incomingUriTemplate = "imap+ssl+://mail.mxes.net";
        testProvider.outgoingUriTemplate = "smtp+ssl+://smtp.mxes.net";
        testProvider.incomingUsernameTemplate = "$user_$domain";
        testProvider.outgoingUsernameTemplate = "$user_$domain";
        testProvider.expandTemplates("replUser@tuffmail.com");
        assertEquals("replUser_tuffmail.com", testProvider.incomingUsername);
        assertEquals("replUser_tuffmail.com", testProvider.outgoingUsername);
        assertEquals("imap+ssl+://mail.mxes.net", testProvider.incomingUri);
        assertEquals("smtp+ssl+://smtp.mxes.net", testProvider.outgoingUri);

        // Everything hardcoded; not effective in the wild
        testProvider = new Provider();
        testProvider.domain = "yahoo.com";
        testProvider.incomingUriTemplate = "imap+ssl+://pop.yahoo.com";
        testProvider.outgoingUriTemplate = "smtp+ssl+://smtp.yahoo.com";
        testProvider.incomingUsernameTemplate = "joe_smith";
        testProvider.outgoingUsernameTemplate = "joe_smith";
        testProvider.expandTemplates("replUser@yahoo.com");
        assertEquals("joe_smith", testProvider.incomingUsername);
        assertEquals("joe_smith", testProvider.outgoingUsername);
        assertEquals("imap+ssl+://pop.yahoo.com", testProvider.incomingUri);
        assertEquals("smtp+ssl+://smtp.yahoo.com", testProvider.outgoingUri);
    }
}
