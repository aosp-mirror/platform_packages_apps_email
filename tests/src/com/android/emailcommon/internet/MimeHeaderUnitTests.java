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

import com.android.emailcommon.internet.MimeHeader;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * This is a series of unit tests for the MimeHeader class.  These tests must be locally
 * complete - no server(s) required.
 */
@SmallTest
public class MimeHeaderUnitTests extends TestCase {

    // TODO more test
    
    /**
     * Test for writeToString()
     */
    public void testWriteToString() throws Exception {
        MimeHeader header = new MimeHeader();
        
        // empty header
        String actual1 = header.writeToString();
        assertEquals("empty header", actual1, null);
        
        // single header
        header.setHeader("Header1", "value1");
        String actual2 = header.writeToString();
        assertEquals("single header", actual2, "Header1: value1\r\n");
        
        // multiple headers
        header.setHeader("Header2", "value2");
        String actual3 = header.writeToString();
        assertEquals("multiple headers", actual3,
                "Header1: value1\r\n"
                + "Header2: value2\r\n");
        
        // omit header
        header.setHeader(MimeHeader.HEADER_ANDROID_ATTACHMENT_STORE_DATA, "value3");
        String actual4 = header.writeToString();
        assertEquals("multiple headers", actual4,
                "Header1: value1\r\n"
                + "Header2: value2\r\n");
    }
}
