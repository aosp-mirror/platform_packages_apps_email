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

import static com.android.email.mail.store.imap.ImapTestUtils.*;

import com.android.email.mail.store.imap.ImapConstants;
import com.android.email.mail.store.imap.ImapResponse;
import com.android.email.mail.store.imap.ImapSimpleString;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

@SmallTest
public class ImapResponseTest extends TestCase {

    public void testIsTagged() {
        assertTrue(buildResponse("a", false).isTagged());
        assertFalse(buildResponse(null, false).isTagged());
    }

    public void testIsOk() {
        assertTrue(buildResponse(null, false, new ImapSimpleString("OK")).isOk());
        assertFalse(buildResponse(null, false, new ImapSimpleString("NO")).isOk());
    }

    public void testIsDataResponse() {
        final ImapResponse OK = buildResponse("tag", false, new ImapSimpleString("OK"));
        final ImapResponse SEARCH = buildResponse(null, false, new ImapSimpleString("SEARCH"),
                new ImapSimpleString("1"));
        final ImapResponse EXISTS = buildResponse(null, false, new ImapSimpleString("3"),
                new ImapSimpleString("EXISTS"));

        final ImapResponse TAGGED_EXISTS = buildResponse("tag", false, new ImapSimpleString("1"),
                new ImapSimpleString("EXISTS"));

        assertTrue(SEARCH.isDataResponse(0, ImapConstants.SEARCH));
        assertTrue(EXISTS.isDataResponse(1, ImapConstants.EXISTS));

        // Falses...
        assertFalse(SEARCH.isDataResponse(1, ImapConstants.SEARCH));
        assertFalse(EXISTS.isDataResponse(0, ImapConstants.EXISTS));

        assertFalse(EXISTS.isDataResponse(1, ImapConstants.FETCH));

        // It's tagged, so can't be a data response
        assertFalse(TAGGED_EXISTS.isDataResponse(1, ImapConstants.EXISTS));
    }

    public void testGetResponseCodeOrEmpty() {
        assertEquals(
                "rescode",
                buildResponse("tag", false,
                        new ImapSimpleString("OK"),
                        buildList(new ImapSimpleString("rescode"))
                        ).getResponseCodeOrEmpty().getString()
                );

        assertEquals(
                "",
                buildResponse("tag", false,
                        new ImapSimpleString("STATUS"), // Not a status response
                        buildList(new ImapSimpleString("rescode"))
                        ).getResponseCodeOrEmpty().getString()
                );

        assertEquals(
                "",
                buildResponse("tag", false,
                        new ImapSimpleString("OK"),
                        new ImapSimpleString("XXX"), // Second element not a list.
                        buildList(new ImapSimpleString("rescode"))
                        ).getResponseCodeOrEmpty().getString()
                );
    }

    public void testGetAlertTextOrEmpty() {
        assertEquals(
                "alert text",
                buildResponse("tag", false,
                        new ImapSimpleString("OK"),
                        buildList(new ImapSimpleString("ALERT")),
                        new ImapSimpleString("alert text")
                        ).getAlertTextOrEmpty().getString()
                );

        // Not alert
        assertEquals(
                "",
                buildResponse("tag", false,
                        new ImapSimpleString("OK"),
                        buildList(new ImapSimpleString("X")),
                        new ImapSimpleString("alert text")
                        ).getAlertTextOrEmpty().getString()
                );
    }

    public void testGetStatusResponseTextOrEmpty() {
        // Not a status response
        assertEquals(
                "",
                buildResponse("tag", false,
                        new ImapSimpleString("XXX"),
                        new ImapSimpleString("!text!")
                        ).getStatusResponseTextOrEmpty().getString()
                );

        // Second element isn't a list.
        assertEquals(
                "!text!",
                buildResponse("tag", false,
                        new ImapSimpleString("OK"),
                        new ImapSimpleString("!text!")
                        ).getStatusResponseTextOrEmpty().getString()
                );

        // Second element is a list.
        assertEquals(
                "!text!",
                buildResponse("tag", false,
                        new ImapSimpleString("OK"),
                        buildList(new ImapSimpleString("XXX")),
                        new ImapSimpleString("!text!")
                        ).getStatusResponseTextOrEmpty().getString()
                );
    }
}
