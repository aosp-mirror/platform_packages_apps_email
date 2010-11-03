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

package com.android.email.activity.setup;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * This is a series of unit tests for the AccountSettingsUtils class.
 *
 * To run these tests,
 *  runtest -c com.android.email.activity.setup.AccountSettingsUtilsTests email
 */
@SmallTest
public class AccountSettingsUtilsTests extends AndroidTestCase {

    /**
     * Test server name inferences
     *
     * Incoming: Prepend "imap" or "pop3" to domain, unless "pop", "pop3",
     *          "imap", or "mail" are found.
     * Outgoing: Prepend "smtp" if "pop", "pop3", "imap" are found.
     *          Leave "mail" as-is.
     * TBD: Are there any useful defaults for exchange?
     */
    public void testGuessServerName() {
        assertEquals("foo.x.y.z", AccountSettingsUtils.inferServerName("x.y.z", "foo", null));
        assertEquals("Pop.y.z", AccountSettingsUtils.inferServerName("Pop.y.z", "foo", null));
        assertEquals("poP3.y.z", AccountSettingsUtils.inferServerName("poP3.y.z", "foo", null));
        assertEquals("iMAp.y.z", AccountSettingsUtils.inferServerName("iMAp.y.z", "foo", null));
        assertEquals("MaiL.y.z", AccountSettingsUtils.inferServerName("MaiL.y.z", "foo", null));

        assertEquals("bar.x.y.z", AccountSettingsUtils.inferServerName("x.y.z", null, "bar"));
        assertEquals("bar.y.z", AccountSettingsUtils.inferServerName("Pop.y.z", null, "bar"));
        assertEquals("bar.y.z", AccountSettingsUtils.inferServerName("poP3.y.z", null, "bar"));
        assertEquals("bar.y.z", AccountSettingsUtils.inferServerName("iMAp.y.z", null, "bar"));
        assertEquals("MaiL.y.z", AccountSettingsUtils.inferServerName("MaiL.y.z", null, "bar"));
    }

}
