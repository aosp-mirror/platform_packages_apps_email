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

import com.android.email.mail.Address;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Transport;
import com.android.email.mail.Message.RecipientType;
import com.android.email.mail.internet.MimeMessage;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.Date;

/**
 * This is a series of unit tests for the SMTP Sender class.  These tests must be locally
 * complete - no server(s) required.
 */
@SmallTest
public class SmtpSenderUnitTests extends AndroidTestCase {

    /* These values are provided by setUp() */
    private SmtpSender mSender = null;
    
    /**
     * Setup code.  We generate a lightweight SmtpSender for testing.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        // These are needed so we can get at the inner classes
        mSender = new SmtpSender("smtp://user:password@server:999");
    }

    /**
     * Confirms simple non-SSL non-TLS login
     */
    public void testSimpleLogin() throws MessagingException {
        
        MockTransport mockTransport = openAndInjectMockTransport();
        
        // try to open it
        setupOpen(mockTransport, null);
        mSender.open();
    }
    
    /**
     * TODO: Test with SSL negotiation (faked)
     * TODO: Test with SSL required but not supported
     * TODO: Test with TLS negotiation (faked)
     * TODO: Test with TLS required but not supported
     * TODO: Test other capabilities.
     * TODO: Test AUTH LOGIN
     */
    
    /**
     * Test:  Open and send a single message (sunny day)
     * 
     * Note:  The final expect (for the ".") is a bit awkward because SmtpSender transmits the
     * final line as "\r\n." instead of "" and ".".
     */
    public void testSendSingleMessage() throws MessagingException {
        MockTransport mockTransport = openAndInjectMockTransport();
        
        // Since SmtpSender.sendMessage() does a close then open, we need to preset for the open
        mockTransport.expectClose();
        setupOpen(mockTransport, null);

        // prepare and send a really simple message
        MimeMessage message = new MimeMessage();
        // TODO use a fixed date for these tests
        message.setSentDate(new Date());
        Address from = new Address("Jones@Registry.Org", null);
        Address to = new Address("Smith@Registry.Org", null);
        message.setFrom(from);
        message.setRecipients(RecipientType.TO, new Address[] { to });
        
        // prepare for the message traffic we'll see
        // TODO We should have a method to do this for any Message
        mockTransport.expect("MAIL FROM: <Jones@Registry.Org>", 
                "250 2.1.0 <Jones@Registry.Org> sender ok");
        mockTransport.expect("RCPT TO: <Smith@Registry.Org>",
                "250 2.1.5 <Smith@Registry.Org> recipient ok");
        mockTransport.expect("DATA", "354 enter mail, end with . on a line by itself");
        mockTransport.expect("Message-ID: .*", (String)null);
        mockTransport.expect("Date: .*", (String)null);
        mockTransport.expect("From: Jones@Registry.Org", (String)null);
        mockTransport.expect("To: Smith@Registry.Org", (String)null);
        mockTransport.expect("\r\n\\.", "250 2.0.0 kv2f1a00C02Rf8w3Vv mail accepted for delivery");

        // Now trigger the transmission
        mSender.sendMessage(message);
    }
    
    /**
     * Set up a basic MockTransport. open it, and inject it into mStore
     */
    private MockTransport openAndInjectMockTransport() {
        // Create mock transport and inject it into the SmtpSender that's already set up
        MockTransport mockTransport = new MockTransport();
        mockTransport.setSecurity(Transport.CONNECTION_SECURITY_NONE);
        mSender.setTransport(mockTransport);
        return mockTransport;
    }
    
    /**
     * Helper which stuffs the mock with enough strings to satisfy a call to SmtpSender.open()
     * 
     * @param mockTransport the mock transport we're using
     * @param capabilities if non-null, comma-separated list of capabilities
     */
    private void setupOpen(MockTransport mockTransport, String capabilities) {
        mockTransport.expect(null, "220 MockTransport 2000 Ready To Assist You Peewee");
        mockTransport.expect("EHLO .*", "250-10.20.30.40 hello");
        if (capabilities == null) {
            mockTransport.expect(null, "250-HELP");
            mockTransport.expect(null, "250-AUTH LOGIN PLAIN CRAM-MD5");
            mockTransport.expect(null, "250-SIZE 15728640");
            mockTransport.expect(null, "250-ENHANCEDSTATUSCODES");
            mockTransport.expect(null, "250-8BITMIME");
        } else {
            for (String capability : capabilities.split(",")) {
                mockTransport.expect(null, "250-" + capability);
            }
        }
        mockTransport.expect(null, "250+OK");
        mockTransport.expect("AUTH PLAIN .*", "235 2.7.0 ... authentication succeeded");
    }
}
