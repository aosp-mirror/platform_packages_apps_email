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

package com.android.email.mail.internet;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;

/**
 * Tests of the Email HTML utils.
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.mail.internet.EmailHtmlUtilTest email
 */
@Suppress
@SmallTest
public class EmailHtmlUtilTest extends AndroidTestCase {

    private static final String textTags = "<b>Plain</b> &";
    private static final String textSpaces = "3 spaces   end.";
    private static final String textNewlines = "ab \r\n  \n   \n\r\n";

    /**
     * Test for escapeCharacterToDisplay in plain text mode.
     */
    public void brokentestEscapeCharacterToDisplayPlainText() {
        String plainTags = EmailHtmlUtil.escapeCharacterToDisplay(textTags);
        assertEquals("plain tag", "&lt;b&gt;Plain&lt;/b&gt; &amp;", plainTags);

        // Successive spaces will be escaped as "&nbsp;"
        String plainSpaces = EmailHtmlUtil.escapeCharacterToDisplay(textSpaces);
        assertEquals("plain spaces", "3 spaces&nbsp;&nbsp; end.", plainSpaces);

        // Newlines will be escaped as "<br>"
        String plainNewlines = EmailHtmlUtil.escapeCharacterToDisplay(textNewlines);
        assertEquals("plain spaces", "ab <br>&nbsp; <br>&nbsp;&nbsp; <br><br>", plainNewlines);

        // All combinations.
        String textAll = textTags + "\n" + textSpaces + "\n" + textNewlines;
        String plainAll = EmailHtmlUtil.escapeCharacterToDisplay(textAll);
        assertEquals("plain all",
                "&lt;b&gt;Plain&lt;/b&gt; &amp;<br>" +
                "3 spaces&nbsp;&nbsp; end.<br>" +
                "ab <br>&nbsp; <br>&nbsp;&nbsp; <br><br>",
                plainAll);
     }
}
