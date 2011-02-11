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

package com.android.email.mail.store.imap;

import static com.android.email.mail.store.imap.ImapTestUtils.assertElement;
import static com.android.email.mail.store.imap.ImapTestUtils.buildList;
import static com.android.email.mail.store.imap.ImapTestUtils.buildResponse;
import static com.android.email.mail.store.imap.ImapTestUtils.createFixedLengthInputStream;

import com.android.email.mail.store.imap.ImapResponseParser.ByeException;
import com.android.email.mail.transport.DiscourseLogger;
import com.android.emailcommon.TempDirectory;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.utility.Utility;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;

@SmallTest
public class ImapResponseParserTest extends AndroidTestCase {
    private static ImapResponseParser generateParser(int literalKeepInMemoryThreshold,
            String responses) {
        return new ImapResponseParser(new ByteArrayInputStream(Utility.toAscii(responses)),
                new DiscourseLogger(4), literalKeepInMemoryThreshold);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        TempDirectory.setTempDirectory(getContext());
    }

    public void testExpect() throws Exception {
        final ImapResponseParser p = generateParser(100000, "abc");
        p.expect('a');
        p.expect('b');
        try {
            p.expect('C');
            fail();
        } catch (IOException e) {
            // OK
        }
    }

    public void testreadUntil() throws Exception {
        final ImapResponseParser p = generateParser(100000, "!ab!c!!def!");
        assertEquals("", p.readUntil('!'));
        assertEquals("ab", p.readUntil('!'));
        assertEquals("c", p.readUntil('!'));
        assertEquals("", p.readUntil('!'));
        assertEquals("def", p.readUntil('!'));
    }

    public void testBasic() throws Exception {
        ImapResponse r;
        final ImapResponseParser p = generateParser(100000,
                "* STATUS \"INBOX\" (UNSEEN 2)\r\n" +
                "100 OK STATUS completed\r\n" +
                "+ continuation request+(\r\n" +
                "* STATUS {5}\r\n" +
                "IN%OX (UNSEEN 10) \"a b c\"\r\n" +
                "101 OK STATUS completed %!(\r\n" +
                "102 OK 1\r\n" +
                "* 1 FETCH\r\n" +
                "103 OK\r\n" + // shortest OK
                "* a\r\n" // shortest response
                );
        r = p.readResponse();
        assertElement(buildResponse(null, false,
                new ImapSimpleString("STATUS"),
                new ImapSimpleString("INBOX"),
                buildList(
                        new ImapSimpleString("UNSEEN"),
                        new ImapSimpleString("2")
                        )
                ), r);

        r = p.readResponse();
        assertElement(buildResponse("100", false,
                new ImapSimpleString("OK"),
                new ImapSimpleString("STATUS completed") // one string
                ), r);

        r = p.readResponse();
        assertElement(buildResponse(null, true,
                new ImapSimpleString("continuation request+(") // one string
                ), r);

        r = p.readResponse();
        assertElement(buildResponse(null, false,
                new ImapSimpleString("STATUS"),
                new ImapMemoryLiteral(createFixedLengthInputStream("IN%OX")),
                buildList(
                        new ImapSimpleString("UNSEEN"),
                        new ImapSimpleString("10")
                        ),
                new ImapSimpleString("a b c")
                ), r);

        r = p.readResponse();
        assertElement(buildResponse("101", false,
                new ImapSimpleString("OK"),
                new ImapSimpleString("STATUS completed %!(") // one string
                ), r);

        r = p.readResponse();
        assertElement(buildResponse("102", false,
                new ImapSimpleString("OK"),
                new ImapSimpleString("1")
                ), r);

        r = p.readResponse();
        assertElement(buildResponse(null, false,
                new ImapSimpleString("1"),
                new ImapSimpleString("FETCH")
                ), r);

        r = p.readResponse();
        assertElement(buildResponse("103", false,
                new ImapSimpleString("OK")
                ), r);

        r = p.readResponse();
        assertElement(buildResponse(null, false,
                new ImapSimpleString("a")
                ), r);
    }

    public void testNil() throws Exception {
        ImapResponse r;
        final ImapResponseParser p = generateParser(100000,
                "* nil nil NIL \"NIL\" {3}\r\n" +
                "NIL\r\n"
                );

        r = p.readResponse();
        assertElement(buildResponse(null, false,
                ImapString.EMPTY,
                ImapString.EMPTY,
                ImapString.EMPTY,
                new ImapSimpleString("NIL"),
                new ImapMemoryLiteral(createFixedLengthInputStream("NIL"))
                ), r);
    }

    public void testBareLf() throws Exception {
        ImapResponse r;

        // Threshold = 3 bytes: use in memory literal.
        ImapResponseParser p = generateParser(3,
                "* a b\n" + // Bare LF -- should be treated like CRLF
                "* x y\r\n"
                );
        r = p.readResponse();
        assertElement(buildResponse(null, false,
                new ImapSimpleString("a"),
                new ImapSimpleString("b")
                ), r);

        r = p.readResponse();
        assertElement(buildResponse(null, false,
                new ImapSimpleString("x"),
                new ImapSimpleString("y")
                ), r);
    }

    public void testLiteral() throws Exception {
        ImapResponse r;

        // Threshold = 3 bytes: use in memory literal.
        ImapResponseParser p = generateParser(3,
                "* test {3}\r\n" +
                "ABC\r\n"
                );
        r = p.readResponse();
        assertElement(buildResponse(null, false,
                new ImapSimpleString("test"),
                new ImapMemoryLiteral(createFixedLengthInputStream("ABC"))
                ), r);

        // Threshold = 2 bytes: use temp file literal.
        p = generateParser(2,
                "* test {3}\r\n" +
                "ABC\r\n"
                );
        r = p.readResponse();
        assertElement(buildResponse(null, false,
                new ImapSimpleString("test"),
                new ImapTempFileLiteral(createFixedLengthInputStream("ABC"))
                ), r);

        // 2 literals in a line
        p = generateParser(0,
                "* test {3}\r\n" +
                "ABC {4}\r\n" +
                "wxyz\r\n"
                );
        r = p.readResponse();
        assertElement(buildResponse(null, false,
                new ImapSimpleString("test"),
                new ImapTempFileLiteral(createFixedLengthInputStream("ABC")),
                new ImapTempFileLiteral(createFixedLengthInputStream("wxyz"))
                ), r);
    }

    public void testAlert() throws Exception {
        ImapResponse r;
        final ImapResponseParser p = generateParser(100000,
                "* OK [ALERT]\r\n" + // No message
                "* OK [ALERT] alert ( message ) %*\r\n" +
                "* OK [ABC] not alert\r\n"
                );
        r = p.readResponse();
        assertTrue(r.isOk());
        assertTrue(r.getAlertTextOrEmpty().isEmpty());

        r = p.readResponse();
        assertTrue(r.isOk());
        assertEquals("alert ( message ) %*", r.getAlertTextOrEmpty().getString());

        r = p.readResponse();
        assertTrue(r.isOk());
        assertTrue(r.getAlertTextOrEmpty().isEmpty());
    }

    /**
     * If a [ appears in the middle of a string, the following string until the next ']' will
     * be considered a part of the string.
     */
    public void testBracket() throws Exception {
        ImapResponse r;
        final ImapResponseParser p = generateParser(100000,
                "* AAA BODY[HEADER.FIELDS (\"DATE\" \"SUBJECT\")]\r\n" +
                "* BBB B[a b c]d e f\r\n"
                );
        r = p.readResponse();
        assertEquals("BODY[HEADER.FIELDS (\"DATE\" \"SUBJECT\")]",
                r.getStringOrEmpty(1).getString());

        r = p.readResponse();
        assertEquals("B[a b c]d", r.getStringOrEmpty(1).getString());
    }

    public void testNest() throws Exception {
        ImapResponse r;
        final ImapResponseParser p = generateParser(100000,
                "* A (a B () DEF) (a (ab)) ((() ())) ((a) ab) ((x y ZZ) () [] [A B] (A B C))" +
                " ([abc] a[abc])\r\n"
                );
        r = p.readResponse();
        assertElement(buildResponse(null, false,
                new ImapSimpleString("A"),
                buildList(
                        new ImapSimpleString("a"),
                        new ImapSimpleString("B"),
                        buildList(),
                        new ImapSimpleString("DEF")
                        ),
                buildList(
                        new ImapSimpleString("a"),
                        buildList(
                                new ImapSimpleString("ab")
                                )
                        ),
                buildList(
                        buildList(
                                buildList(),
                                buildList()
                                )
                        ),
                buildList(
                        buildList(
                                new ImapSimpleString("a")
                                ),
                        new ImapSimpleString("ab")
                        ),
                buildList(
                        buildList(
                                new ImapSimpleString("x"),
                                new ImapSimpleString("y"),
                                new ImapSimpleString("ZZ")
                                ),
                        buildList(),
                        buildList(),
                        buildList(
                                new ImapSimpleString("A"),
                                new ImapSimpleString("B")
                                ),
                        buildList(
                                new ImapSimpleString("A"),
                                new ImapSimpleString("B"),
                                new ImapSimpleString("C")
                                )
                        ),
                buildList(
                        buildList(
                                new ImapSimpleString("abc")
                                ),
                        new ImapSimpleString("a[abc]")
                        )
                ), r);
    }

    /**
     * Parser shouldn't crash for any response.  Should just throw IO/MessagingException.
     */
    public void testMalformedResponse() throws Exception {
        expectMessagingException("");
        expectMessagingException("\r");
        expectMessagingException("\r\n");

        expectMessagingException("*\r\n");
        expectMessagingException("1\r\n");

        expectMessagingException("* \r\n");
        expectMessagingException("1 \r\n");

        expectMessagingException("* A (\r\n");
        expectMessagingException("* A )\r\n");
        expectMessagingException("* A (()\r\n");
        expectMessagingException("* A ())\r\n");
        expectMessagingException("* A [\r\n");
        expectMessagingException("* A ]\r\n");
        expectMessagingException("* A [[]\r\n");
        expectMessagingException("* A []]\r\n");

        expectMessagingException("* A ([)]\r\n");

        expectMessagingException("* A");
        expectMessagingException("* {3}");
        expectMessagingException("* {3}\r\nab");
    }

    private static void expectMessagingException(String response) throws Exception {
        final ImapResponseParser p = generateParser(100000, response);
        try {
            p.readResponse();
            fail("Didn't throw Exception: response='" + response + "'");
        } catch (MessagingException ok) {
            return;
        } catch (IOException ok) {
            return;
        }
    }

    // Compatibility tests...

    /**
     * OK response with a long message that contains special chars. (including tabs)
     */
    public void testOkWithLongMessage() throws Exception {
        ImapResponse r;
        final ImapResponseParser p = generateParser(100000,
                "* OK [CAPABILITY IMAP4 IMAP4rev1 LITERAL+ ID STARTTLS AUTH=PLAIN AUTH=LOGIN" +
                "AUTH=CRAM-MD5] server.domain.tld\tCyrus IMAP4 v2.3.8-OS X Server 10.5:"
                +"  \t\t\t9F33 server ready %%\r\n");
        assertTrue(p.readResponse().isOk());
    }

    /** Make sure literals and strings are interchangeable. */
    public void testLiteralStringConversion() throws Exception {
        ImapResponse r;
        final ImapResponseParser p = generateParser(100000,
                "* XXX {5}\r\n" +
                "a b c\r\n");
        assertEquals("a b c", p.readResponse().getStringOrEmpty(1).getString());
    }

    public void testByeReceived() throws Exception {
        final ImapResponseParser p = generateParser(100000,
                "* BYE Autologout timer; idle for too long\r\n");
        try {
            p.readResponse();
            fail("Didn't throw ByeException");
        } catch (ByeException ok) {
        }
    }
}
