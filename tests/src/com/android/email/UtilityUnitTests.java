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

import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.R;
import com.android.email.Utility;

import android.content.Context;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * This is a series of unit tests for the Utility class.  These tests must be locally
 * complete - no server(s) required.
 */
@SmallTest
public class UtilityUnitTests extends AndroidTestCase {

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
    /**
     * Tests of the syncronization of array and types of the display folder names
     */
    public void testGetDisplayName() {
        Context context = getContext();
        String expect, name;
        expect = context.getString(R.string.special_mailbox_display_name_inbox);
        name = Utility.FolderProperties.getInstance(context).getDisplayName(Mailbox.TYPE_INBOX);
        assertEquals(expect, name);
        expect = null;
        name = Utility.FolderProperties.getInstance(context).getDisplayName(Mailbox.TYPE_MAIL);
        assertEquals(expect, name);
        expect = null;
        name = Utility.FolderProperties.getInstance(context).getDisplayName(Mailbox.TYPE_PARENT);
        assertEquals(expect, name);
        expect = context.getString(R.string.special_mailbox_display_name_drafts);
        name = Utility.FolderProperties.getInstance(context).getDisplayName(Mailbox.TYPE_DRAFTS);
        assertEquals(expect, name);
        expect = context.getString(R.string.special_mailbox_display_name_outbox);
        name = Utility.FolderProperties.getInstance(context).getDisplayName(Mailbox.TYPE_OUTBOX);
        assertEquals(expect, name);
        expect = context.getString(R.string.special_mailbox_display_name_sent);
        name = Utility.FolderProperties.getInstance(context).getDisplayName(Mailbox.TYPE_SENT);
        assertEquals(expect, name);
        expect = context.getString(R.string.special_mailbox_display_name_trash);
        name = Utility.FolderProperties.getInstance(context).getDisplayName(Mailbox.TYPE_TRASH);
        assertEquals(expect, name);
        expect = context.getString(R.string.special_mailbox_display_name_junk);
        name = Utility.FolderProperties.getInstance(context).getDisplayName(Mailbox.TYPE_JUNK);
        assertEquals(expect, name);
        // Testing illegal index
        expect = null;
        name = Utility.FolderProperties.getInstance(context).getDisplayName(8);
        assertEquals(expect, name);
    }
}
