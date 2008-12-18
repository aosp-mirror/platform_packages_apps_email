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

package com.android.email.mail.transport;

import com.android.email.mail.transport.MailTransport;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Simple unit tests for MailSender.  Tests here should not attempt any actual connections.
 */
@SmallTest
public class MailTransportUnitTests extends AndroidTestCase {

    /**
     * Tests of the Uri parsing logic
     */
    public void testUriParsing() throws URISyntaxException {

        // Parse with everything in the Uri
        URI uri = new URI("smtp://user:password@server.com:999");
        MailTransport transport = new MailTransport("SMTP");
        transport.setUri(uri, 888);
        assertEquals("server.com", transport.getHost());
        assertEquals(999, transport.getPort());
        String[] userInfoParts = transport.getUserInfoParts();
        assertNotNull(userInfoParts);
        assertEquals("user", userInfoParts[0]);
        assertEquals("password", userInfoParts[1]);

        // Parse with no user/password (e.g. anonymous SMTP)
        uri = new URI("smtp://server.com:999");
        transport = new MailTransport("SMTP");
        transport.setUri(uri, 888);
        assertEquals("server.com", transport.getHost());
        assertEquals(999, transport.getPort());
        assertNull(transport.getUserInfoParts());
    }
}
