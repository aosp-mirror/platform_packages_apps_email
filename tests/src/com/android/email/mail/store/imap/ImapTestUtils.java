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

import com.android.email.FixedLengthInputStream;
import com.android.email.mail.store.imap.ImapElement;
import com.android.email.mail.store.imap.ImapList;
import com.android.email.mail.store.imap.ImapResponse;
import com.android.email.mail.store.imap.ImapSimpleString;
import com.android.email.mail.store.imap.ImapString;
import com.android.email.mail.transport.DiscourseLogger;
import com.android.emailcommon.utility.Utility;

import java.io.ByteArrayInputStream;

import junit.framework.Assert;

/**
 * Utility methods for IMAP tests.
 */
public final class ImapTestUtils {
    private ImapTestUtils() {}

    // Generic constants used by various tests.
    public static final ImapString STRING_1 = new ImapSimpleString("aBc");
    public static final ImapString STRING_2 = new ImapSimpleString("X y z");
    public static final ImapList LIST_1 = buildList(STRING_1);
    public static final ImapList LIST_2 = buildList(STRING_1, STRING_2, LIST_1);

    /** @see #assertElement(String, ImapElement, ImapElement) */
    public static final void assertElement(ImapElement expected, ImapElement actual) {
        assertElement("(no message)", expected, actual);
    }

    /**
     * Compare two {@link ImapElement}s and throws {@link AssertionFailedError} if different.
     *
     * Note this method used {@link ImapElement#equalsForTest} rather than equals().
     */
    public static final void assertElement(String message, ImapElement expected,
            ImapElement actual) {
        if (expected == null && actual == null) {
            return;
        }
        if (expected != null && expected.equalsForTest(actual)) {
            return; // OK
        }
        Assert.fail(String.format("%s expected=%s\nactual=%s", message, expected, actual));
    }

    /** Convenience method to build an {@link ImapList} */
    public static final ImapList buildList(ImapElement... elements) {
        ImapList list = new ImapList();
        for (ImapElement e : elements) {
            list.add(e);
        }
        return list;
    }

    /** Convenience method to build an {@link ImapResponse} */
    public static final ImapResponse buildResponse(String tag, boolean isContinuationRequest,
            ImapElement... elements) {
        ImapResponse res = new ImapResponse(tag, isContinuationRequest);
        for (ImapElement e : elements) {
            res.add(e);
        }
        return res;
    }

    /**
     * Convenience method to build an {@link ImapResponse} from a single response.
     */
    public static final ImapResponse parseResponse(String line) {
        ImapResponseParser p = new ImapResponseParser(
                new ByteArrayInputStream(Utility.toAscii(line + "\r\n")), new DiscourseLogger(4));
        try {
            return p.readResponse();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Convenience method to build an {@link FixedLengthInputStream} from a String, using
     * US-ASCII.
     */
    public static FixedLengthInputStream createFixedLengthInputStream(String content) {
        // Add unnecessary part.  FixedLengthInputStream should cut it.
        ByteArrayInputStream in = new ByteArrayInputStream(Utility.toAscii(content + "#trailing"));
        return new FixedLengthInputStream(in, content.length());
    }
}
