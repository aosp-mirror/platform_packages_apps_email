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

import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.EmailContent.HostAuth;
import com.android.emailcommon.utility.Utility;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.net.URISyntaxException;

/**
 * Unit tests for the HostAuth inner class.
 * These tests must be locally complete - no server(s) required.
 */
@SmallTest
public class HostAuthTests extends AndroidTestCase {
    /**
     * Test the various combinations of SSL, TLS, and trust-certificates encoded as Uris
     */
    public void testSecurityUri()
            throws URISyntaxException {
        HostAuth ha = ProviderTestUtils.setupHostAuth("uri-security", 1, false, mContext);

        final int MASK =
            HostAuth.FLAG_SSL | HostAuth.FLAG_TLS | HostAuth.FLAG_TRUST_ALL;

        // Set various URIs and check the resulting flags
        Utility.setHostAuthFromString(ha, "protocol://user:password@server:123");
        assertEquals(0, ha.mFlags & MASK);
        Utility.setHostAuthFromString(ha, "protocol+ssl+://user:password@server:123");
        assertEquals(HostAuth.FLAG_SSL, ha.mFlags & MASK);
        Utility.setHostAuthFromString(ha, "protocol+ssl+trustallcerts://user:password@server:123");
        assertEquals(HostAuth.FLAG_SSL | HostAuth.FLAG_TRUST_ALL, ha.mFlags & MASK);
        Utility.setHostAuthFromString(ha, "protocol+tls+://user:password@server:123");
        assertEquals(HostAuth.FLAG_TLS, ha.mFlags & MASK);
        Utility.setHostAuthFromString(ha, "protocol+tls+trustallcerts://user:password@server:123");
        assertEquals(HostAuth.FLAG_TLS | HostAuth.FLAG_TRUST_ALL, ha.mFlags & MASK);

        // Now check the retrival method (building URI from flags)
        ha.mFlags &= ~MASK;
        String uriString = ha.getStoreUri();
        assertTrue(uriString.startsWith("protocol://"));
        ha.mFlags |= HostAuth.FLAG_SSL;
        uriString = ha.getStoreUri();
        assertTrue(uriString.startsWith("protocol+ssl+://"));
        ha.mFlags |= HostAuth.FLAG_TRUST_ALL;
        uriString = ha.getStoreUri();
        assertTrue(uriString.startsWith("protocol+ssl+trustallcerts://"));
        ha.mFlags &= ~MASK;
        ha.mFlags |= HostAuth.FLAG_TLS;
        uriString = ha.getStoreUri();
        assertTrue(uriString.startsWith("protocol+tls+://"));
        ha.mFlags |= HostAuth.FLAG_TRUST_ALL;
        uriString = ha.getStoreUri();
        assertTrue(uriString.startsWith("protocol+tls+trustallcerts://"));
    }

    /**
     * Test port assignments made from Uris
     */
    public void testPortAssignments()
            throws URISyntaxException {
        HostAuth ha = ProviderTestUtils.setupHostAuth("uri-port", 1, false, mContext);

        // Set various URIs and check the resulting flags
        // Hardwired port
        Utility.setHostAuthFromString(ha, "imap://user:password@server:123");
        assertEquals(123, ha.mPort);
        // Auto-assigned ports
        Utility.setHostAuthFromString(ha, "imap://user:password@server");
        assertEquals(143, ha.mPort);
        Utility.setHostAuthFromString(ha, "imap+ssl://user:password@server");
        assertEquals(993, ha.mPort);
        Utility.setHostAuthFromString(ha, "imap+ssl+trustallcerts://user:password@server");
        assertEquals(993, ha.mPort);
        Utility.setHostAuthFromString(ha, "imap+tls://user:password@server");
        assertEquals(143, ha.mPort);
        Utility.setHostAuthFromString(ha, "imap+tls+trustallcerts://user:password@server");
        assertEquals(143, ha.mPort);

        // Hardwired port
        Utility.setHostAuthFromString(ha, "pop3://user:password@server:123");
        assertEquals(123, ha.mPort);
        // Auto-assigned ports
        Utility.setHostAuthFromString(ha, "pop3://user:password@server");
        assertEquals(110, ha.mPort);
        Utility.setHostAuthFromString(ha, "pop3+ssl://user:password@server");
        assertEquals(995, ha.mPort);
        Utility.setHostAuthFromString(ha, "pop3+ssl+trustallcerts://user:password@server");
        assertEquals(995, ha.mPort);
        Utility.setHostAuthFromString(ha, "pop3+tls://user:password@server");
        assertEquals(110, ha.mPort);
        Utility.setHostAuthFromString(ha, "pop3+tls+trustallcerts://user:password@server");
        assertEquals(110, ha.mPort);

        // Hardwired port
        Utility.setHostAuthFromString(ha, "eas://user:password@server:123");
        assertEquals(123, ha.mPort);
        // Auto-assigned ports
        Utility.setHostAuthFromString(ha, "eas://user:password@server");
        assertEquals(80, ha.mPort);
        Utility.setHostAuthFromString(ha, "eas+ssl://user:password@server");
        assertEquals(443, ha.mPort);
        Utility.setHostAuthFromString(ha, "eas+ssl+trustallcerts://user:password@server");
        assertEquals(443, ha.mPort);

        // Hardwired port
        Utility.setHostAuthFromString(ha, "smtp://user:password@server:123");
        assertEquals(123, ha.mPort);
        // Auto-assigned ports
        Utility.setHostAuthFromString(ha, "smtp://user:password@server");
        assertEquals(587, ha.mPort);
        Utility.setHostAuthFromString(ha, "smtp+ssl://user:password@server");
        assertEquals(465, ha.mPort);
        Utility.setHostAuthFromString(ha, "smtp+ssl+trustallcerts://user:password@server");
        assertEquals(465, ha.mPort);
        Utility.setHostAuthFromString(ha, "smtp+tls://user:password@server");
        assertEquals(587, ha.mPort);
        Utility.setHostAuthFromString(ha, "smtp+tls+trustallcerts://user:password@server");
        assertEquals(587, ha.mPort);
    }

    /**
     * Test preservation of username & password in URI
     */
    public void testGetStoreUri()
            throws URISyntaxException {
        HostAuth ha = new HostAuth();
        Utility.setHostAuthFromString(ha, "protocol://user:password@server:123");
        String getUri = ha.getStoreUri();
        assertEquals("protocol://user:password@server:123", getUri);

        // Now put spaces in/around username (they are trimmed)
        Utility.setHostAuthFromString(ha, "protocol://%20us%20er%20:password@server:123");
        getUri = ha.getStoreUri();
        assertEquals("protocol://us%20er:password@server:123", getUri);

        // Now put spaces around password (should not be trimmed)
        Utility.setHostAuthFromString(ha, "protocol://user:%20pass%20word%20@server:123");
        getUri = ha.getStoreUri();
        assertEquals("protocol://user:%20pass%20word%20@server:123", getUri);
    }

    /**
     * Test user name and password are set correctly
     */
    public void testSetLogin() {
        HostAuth ha = new HostAuth();
        ha.setLogin("user:password");
        assertEquals("user", ha.mLogin);
        assertEquals("password", ha.mPassword);

        // special characters are not removed during insertion
        ha.setLogin("%20us%20er%20:password");
        assertEquals("%20us%20er%20", ha.mLogin);
        assertEquals("password", ha.mPassword);

        // special characters are not removed during insertion
        ha.setLogin("user:%20pass%20word%20");
        assertEquals("user", ha.mLogin);
        assertEquals("%20pass%20word%20", ha.mPassword);

        ha.setLogin("user:");
        assertEquals("user", ha.mLogin);
        assertEquals("", ha.mPassword);

        ha.setLogin(":password");
        assertEquals("", ha.mLogin);
        assertEquals("password", ha.mPassword);

        ha.setLogin("");
        assertNull(ha.mLogin);
        assertNull(ha.mPassword);

        ha.setLogin(null);
        assertNull(ha.mLogin);
        assertNull(ha.mPassword);

        ha.setLogin("userpassword");
        assertEquals("userpassword", ha.mLogin);
        assertNull(ha.mPassword);
    }

    /**
     * Test the authentication flag is set correctly when setting user name and password
     */
    public void testSetLoginAuthenticate() {
        HostAuth ha = new HostAuth();

        ha.mFlags = 0x00000000;
        ha.setLogin("user", "password");
        assertEquals(HostAuth.FLAG_AUTHENTICATE, ha.mFlags);

        ha.mFlags = 0x00000000;
        ha.setLogin("user", "");
        assertEquals(HostAuth.FLAG_AUTHENTICATE, ha.mFlags);

        ha.mFlags = 0x00000000;
        ha.setLogin("", "password");
        assertEquals(HostAuth.FLAG_AUTHENTICATE, ha.mFlags);

        ha.mFlags = 0x00000000;
        ha.setLogin("user", null);
        assertEquals(HostAuth.FLAG_AUTHENTICATE, ha.mFlags);

        ha.mFlags = 0xffffffff;
        ha.setLogin(null, "password");
        assertEquals(~HostAuth.FLAG_AUTHENTICATE, ha.mFlags);

        ha.mFlags = 0xffffffff;
        ha.setLogin(null, null);
        assertEquals(~HostAuth.FLAG_AUTHENTICATE, ha.mFlags);
    }

    /**
     * Test setting the connection using a URI scheme
     */
    public void testSetConnectionScheme() {
        HostAuth ha = new HostAuth();

        // Set URIs for IMAP
        // Hardwired port
        ha.setConnection("imap", "server", 123);
        assertEquals(0, ha.mFlags);
        assertEquals(123, ha.mPort);

        // Auto-assigned ports
        ha.setConnection("imap", "server", -1);
        assertEquals(0, ha.mFlags);
        assertEquals(143, ha.mPort);

        ha.setConnection("imap+ssl", "server", -1);
        assertEquals(HostAuth.FLAG_SSL, ha.mFlags);
        assertEquals(993, ha.mPort);

        ha.setConnection("imap+ssl+trustallcerts", "server", -1);
        assertEquals(HostAuth.FLAG_SSL|HostAuth.FLAG_TRUST_ALL, ha.mFlags);
        assertEquals(993, ha.mPort);

        ha.setConnection("imap+tls", "server", -1);
        assertEquals(HostAuth.FLAG_TLS, ha.mFlags);
        assertEquals(143, ha.mPort);

        ha.setConnection("imap+tls+trustallcerts", "server", -1);
        assertEquals(HostAuth.FLAG_TLS|HostAuth.FLAG_TRUST_ALL, ha.mFlags);
        assertEquals(143, ha.mPort);

        // Set URIs for POP3
        // Hardwired port
        ha.setConnection("pop3", "server", 123);
        assertEquals(0, ha.mFlags);
        assertEquals(123, ha.mPort);

        // Auto-assigned ports
        ha.setConnection("pop3", "server", -1);
        assertEquals(0, ha.mFlags);
        assertEquals(110, ha.mPort);

        ha.setConnection("pop3+ssl", "server", -1);
        assertEquals(HostAuth.FLAG_SSL, ha.mFlags);
        assertEquals(995, ha.mPort);

        ha.setConnection("pop3+ssl+trustallcerts", "server", -1);
        assertEquals(HostAuth.FLAG_SSL|HostAuth.FLAG_TRUST_ALL, ha.mFlags);
        assertEquals(995, ha.mPort);

        ha.setConnection("pop3+tls", "server", -1);
        assertEquals(HostAuth.FLAG_TLS, ha.mFlags);
        assertEquals(110, ha.mPort);

        ha.setConnection("pop3+tls+trustallcerts", "server", -1);
        assertEquals(HostAuth.FLAG_TLS|HostAuth.FLAG_TRUST_ALL, ha.mFlags);
        assertEquals(110, ha.mPort);

        // Set URIs for Exchange
        // Hardwired port
        ha.setConnection("eas", "server", 123);
        assertEquals(0, ha.mFlags);
        assertEquals(123, ha.mPort);

        // Auto-assigned ports
        ha.setConnection("eas", "server", -1);
        assertEquals(0, ha.mFlags);
        assertEquals(80, ha.mPort);

        ha.setConnection("eas+ssl", "server", -1);
        assertEquals(HostAuth.FLAG_SSL, ha.mFlags);
        assertEquals(443, ha.mPort);

        ha.setConnection("eas+ssl+trustallcerts", "server", -1);
        assertEquals(HostAuth.FLAG_SSL|HostAuth.FLAG_TRUST_ALL, ha.mFlags);
        assertEquals(443, ha.mPort);

        // Set URIs for SMTP
        // Hardwired port
        ha.setConnection("smtp", "server", 123);
        assertEquals(0, ha.mFlags);
        assertEquals(123, ha.mPort);

        // Auto-assigned ports
        ha.setConnection("smtp", "server", -1);
        assertEquals(0, ha.mFlags);
        assertEquals(587, ha.mPort);

        ha.setConnection("smtp+ssl", "server", -1);
        assertEquals(HostAuth.FLAG_SSL, ha.mFlags);
        assertEquals(465, ha.mPort);

        ha.setConnection("smtp+ssl+trustallcerts", "server", -1);
        assertEquals(HostAuth.FLAG_SSL|HostAuth.FLAG_TRUST_ALL, ha.mFlags);
        assertEquals(465, ha.mPort);

        ha.setConnection("smtp+tls", "server", -1);
        assertEquals(HostAuth.FLAG_TLS, ha.mFlags);
        assertEquals(587, ha.mPort);

        ha.setConnection("smtp+tls+trustallcerts", "server", -1);
        assertEquals(HostAuth.FLAG_TLS|HostAuth.FLAG_TRUST_ALL, ha.mFlags);
        assertEquals(587, ha.mPort);
    }

    /**
     * Test setting the connection using a protocol and flags
     */
    public void testSetConnectionFlags() {
        HostAuth ha = new HostAuth();

        // Different port types don't affect flags
        ha.setConnection("imap", "server", 123, 0);
        assertEquals(0, ha.mFlags);
        ha.setConnection("imap", "server", -1, 0);
        assertEquals(0, ha.mFlags);

        // Different protocol types don't affect flags
        ha.setConnection("pop3", "server", 123, 0);
        assertEquals(0, ha.mFlags);
        ha.setConnection("pop3", "server", -1, 0);
        assertEquals(0, ha.mFlags);
        ha.setConnection("eas", "server", 123, 0);
        assertEquals(0, ha.mFlags);
        ha.setConnection("eas", "server", -1, 0);
        assertEquals(0, ha.mFlags);
        ha.setConnection("smtp", "server", 123, 0);
        assertEquals(0, ha.mFlags);
        ha.setConnection("smtp", "server", -1, 0);
        assertEquals(0, ha.mFlags);

        // Sets SSL flag
        ha.setConnection("imap", "server", -1, HostAuth.FLAG_SSL);
        assertEquals(HostAuth.FLAG_SSL, ha.mFlags);

        // Sets SSL+Trusted flags
        ha.setConnection("imap", "server", -1, HostAuth.FLAG_SSL | HostAuth.FLAG_TRUST_ALL);
        assertEquals(HostAuth.FLAG_SSL | HostAuth.FLAG_TRUST_ALL, ha.mFlags);

        // Sets TLS flag
        ha.setConnection("imap", "server", -1, HostAuth.FLAG_TLS);
        assertEquals(HostAuth.FLAG_TLS, ha.mFlags);

        // Sets TLS+Trusted flags
        ha.setConnection("imap", "server", -1, HostAuth.FLAG_TLS | HostAuth.FLAG_TRUST_ALL);
        assertEquals(HostAuth.FLAG_TLS | HostAuth.FLAG_TRUST_ALL, ha.mFlags);

        // Test other defined flags; should not affect mFlags
        ha.setConnection("imap", "server", -1, HostAuth.FLAG_AUTHENTICATE);
        assertEquals(0, ha.mFlags);

        // Test every other bit; should not affect mFlags
        ha.setConnection("imap", "server", -1, 0xfffffff4);
        assertEquals(0, ha.mFlags);
    }

    public void testGetSchemeString() {
        String scheme;

        scheme = HostAuth.getSchemeString("foo", 0);
        assertEquals("foo", scheme);
        scheme = HostAuth.getSchemeString("foo", HostAuth.FLAG_SSL);
        assertEquals("foo+ssl+", scheme);
        scheme = HostAuth.getSchemeString("foo", HostAuth.FLAG_SSL | HostAuth.FLAG_TRUST_ALL);
        assertEquals("foo+ssl+trustallcerts", scheme);
        scheme = HostAuth.getSchemeString("foo", HostAuth.FLAG_TLS);
        assertEquals("foo+tls+", scheme);
        scheme = HostAuth.getSchemeString("foo", HostAuth.FLAG_TLS | HostAuth.FLAG_TRUST_ALL);
        assertEquals("foo+tls+trustallcerts", scheme);
        // error cases; no security string appended to protocol
        scheme = HostAuth.getSchemeString("foo", HostAuth.FLAG_TRUST_ALL);
        assertEquals("foo", scheme);
        scheme = HostAuth.getSchemeString("foo", HostAuth.FLAG_SSL | HostAuth.FLAG_TLS);
        assertEquals("foo", scheme);
        scheme = HostAuth.getSchemeString("foo",
                HostAuth.FLAG_SSL | HostAuth.FLAG_TLS | HostAuth.FLAG_TRUST_ALL);
        assertEquals("foo", scheme);
        scheme = HostAuth.getSchemeString("foo", 0xfffffff4);
        assertEquals("foo", scheme);
    }

    public void testGetSchemeFlags() {
        int flags;

        flags = HostAuth.getSchemeFlags("");
        assertEquals(0, flags);
        flags = HostAuth.getSchemeFlags("+");
        assertEquals(0, flags);
        flags = HostAuth.getSchemeFlags("foo+");
        assertEquals(0, flags);
        flags = HostAuth.getSchemeFlags("foo+ssl");
        assertEquals(HostAuth.FLAG_SSL, flags);
        flags = HostAuth.getSchemeFlags("foo+ssl+");
        assertEquals(HostAuth.FLAG_SSL, flags);
        flags = HostAuth.getSchemeFlags("foo+ssl+trustallcerts");
        assertEquals(HostAuth.FLAG_SSL | HostAuth.FLAG_TRUST_ALL, flags);
        flags = HostAuth.getSchemeFlags("foo+tls+");
        assertEquals(HostAuth.FLAG_TLS, flags);
        flags = HostAuth.getSchemeFlags("foo+tls+trustallcerts");
        assertEquals(HostAuth.FLAG_TLS | HostAuth.FLAG_TRUST_ALL, flags);
        flags = HostAuth.getSchemeFlags("foo+bogus");
        assertEquals(0, flags);
        flags = HostAuth.getSchemeFlags("foo+bogus+trustallcerts");
        assertEquals(HostAuth.FLAG_TRUST_ALL, flags);
        flags = HostAuth.getSchemeFlags("foo+ssl+bogus");
        assertEquals(HostAuth.FLAG_SSL, flags);
        flags = HostAuth.getSchemeFlags("foo+ssl+trustallcerts+bogus");
        assertEquals(HostAuth.FLAG_SSL | HostAuth.FLAG_TRUST_ALL, flags);
        flags = HostAuth.getSchemeFlags("foo+bogus+bogus");
        assertEquals(0, flags);
        flags = HostAuth.getSchemeFlags("foo+bogus+bogus+bogus");
        assertEquals(0, flags);
    }

    public void testEquals() throws URISyntaxException {
        HostAuth ha1;
        HostAuth ha2;

        ha1 = new HostAuth();
        ha2 = new HostAuth();
        assertTrue(ha1.equals(ha2));
        assertTrue(ha2.equals(ha1));

        Utility.setHostAuthFromString(ha1, "smtp+tls+://user:password@server/domain");
        Utility.setHostAuthFromString(ha2, "smtp+tls+://user:password@server/domain");
        assertTrue(ha1.equals(ha2));
        assertTrue(ha2.equals(ha1));

        // Different protocol
        Utility.setHostAuthFromString(ha2, "imap+tls+://user:password@server/domain");
        assertFalse(ha1.equals(ha2));
        assertFalse(ha2.equals(ha1));
        // Different domain
        Utility.setHostAuthFromString(ha2, "smtp+tls+://user:password@server/domain2");
        assertFalse(ha1.equals(ha2));
        assertFalse(ha2.equals(ha1));
        // Missing server
        Utility.setHostAuthFromString(ha2, "smtp+tls+://user:password/domain");
        assertFalse(ha1.equals(ha2));
        assertFalse(ha2.equals(ha1));
        // Missing domain
        Utility.setHostAuthFromString(ha2, "smtp+tls+://user:password@server");
        assertFalse(ha1.equals(ha2));
        assertFalse(ha2.equals(ha1));
        // Different server
        Utility.setHostAuthFromString(ha2, "smtp+tls+://user:password@server2/domain");
        assertFalse(ha1.equals(ha2));
        assertFalse(ha2.equals(ha1));
        // Different password
        Utility.setHostAuthFromString(ha2, "smtp+tls+://user:password2@server/domain");
        assertFalse(ha1.equals(ha2));
        assertFalse(ha2.equals(ha1));
        // Different user name
        Utility.setHostAuthFromString(ha2, "smtp+tls+://user2:password@server/domain");
        assertFalse(ha1.equals(ha2));
        assertFalse(ha2.equals(ha1));
        // Missing password
        Utility.setHostAuthFromString(ha2, "smtp+tls+://user@server/domain");
        assertFalse(ha1.equals(ha2));
        assertFalse(ha2.equals(ha1));
        // Missing user name
        Utility.setHostAuthFromString(ha2, "smtp+tls+://password@server/domain");
        assertFalse(ha1.equals(ha2));
        assertFalse(ha2.equals(ha1));
        // Missing user name & password
        Utility.setHostAuthFromString(ha2, "smtp+tls+://server/domain");
        assertFalse(ha1.equals(ha2));
        assertFalse(ha2.equals(ha1));
        // Added "trustallcerts"
        Utility.setHostAuthFromString(ha2, "smtp+tls+trustallcerts://user:password@server/domain");
        assertFalse(ha1.equals(ha2));
        assertFalse(ha2.equals(ha1));
        // Different authentication
        Utility.setHostAuthFromString(ha2, "smtp+ssl+://user:password@server/domain");
        assertFalse(ha1.equals(ha2));
        assertFalse(ha2.equals(ha1));
        // Missing authentication
        Utility.setHostAuthFromString(ha2, "smtp+://user:password@server/domain");
        assertFalse(ha1.equals(ha2));
        assertFalse(ha2.equals(ha1));
        ha2 = null;
        assertFalse(ha1.equals(ha2));
    }
}

