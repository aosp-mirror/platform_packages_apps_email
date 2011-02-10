/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.emailcommon.internet;

import com.android.emailcommon.internet.MimeBodyPart;
import com.android.emailcommon.internet.MimeHeader;
import com.android.emailcommon.mail.MessagingException;

import junit.framework.TestCase;

import android.test.suitebuilder.annotation.SmallTest;

/**
 * This is a series of unit tests for the MimeBodyPart class.  These tests must be locally
 * complete - no server(s) required.
 */
@SmallTest
public class MimeBodyPartTest extends TestCase {

    // TODO: more tests.
    
    /*
     * Confirm getContentID() correctly works.
     */
    public void testGetContentId() throws MessagingException {
        MimeBodyPart bp = new MimeBodyPart();

        // no content-id
        assertNull(bp.getContentId());

        // normal case
        final String cid1 = "cid.1@android.com";
        bp.setHeader(MimeHeader.HEADER_CONTENT_ID, cid1);
        assertEquals(cid1, bp.getContentId());

        // surrounded by optional bracket
        bp.setHeader(MimeHeader.HEADER_CONTENT_ID, "<" + cid1 + ">");
        assertEquals(cid1, bp.getContentId());
    }
}
