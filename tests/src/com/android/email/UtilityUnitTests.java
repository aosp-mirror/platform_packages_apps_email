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

package com.android.email;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * This is a series of unit tests for the Utility class.  These tests must be locally
 * complete - no server(s) required.
 */
@SmallTest
public class UtilityUnitTests extends TestCase {

    /**
     * Tests of the IMAP quoting rules function.
     */
    public void testImapQuote() {
        
        // Simple strings should come through with simple quotes
        assertEquals("\"abcd\"", Utility.imapQuoted("abcd"));
        
        // Quoting internal double quotes with \
        assertEquals("\"ab\\\"cd\"", Utility.imapQuoted("ab\"cd"));
        
        // Quoting internal \ with \\
        assertEquals("\"ab\\\\cd\"", Utility.imapQuoted("ab\\cd"));
    }
    
}
