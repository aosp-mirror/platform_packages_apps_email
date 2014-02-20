/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.email.activity;

import com.android.emailcommon.utility.IntentUtilities;

import android.content.Intent;
import android.net.Uri;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

@SmallTest
public class IntentUtilitiesTests extends AndroidTestCase {
    public void brokentestSimple() {
        final Uri.Builder b = IntentUtilities.createActivityIntentUrlBuilder("/abc");
        IntentUtilities.setAccountId(b, 10);
        IntentUtilities.setMailboxId(b, 20);
        IntentUtilities.setMessageId(b, 30);
        IntentUtilities.setAccountUuid(b, "*uuid*");

        final Uri u = b.build();
        assertEquals("content", u.getScheme());
        assertEquals("ui.email.android.com", u.getAuthority());
        assertEquals("/abc", u.getPath());

        final Intent i = new Intent(Intent.ACTION_MAIN, u);
        assertEquals(10, IntentUtilities.getAccountIdFromIntent(i));
        assertEquals(20, IntentUtilities.getMailboxIdFromIntent(i));
        assertEquals(30, IntentUtilities.getMessageIdFromIntent(i));
        assertEquals("*uuid*", IntentUtilities.getAccountUuidFromIntent(i));
    }

    public void brokentestGetIdFromIntent() {
        Intent i;

        // No URL in intent
        i = new Intent(getContext(), getClass());
        assertEquals(-1, IntentUtilities.getAccountIdFromIntent(i));
        assertEquals(-1, IntentUtilities.getMailboxIdFromIntent(i));
        assertEquals(-1, IntentUtilities.getMessageIdFromIntent(i));

        // No param
        checkGetIdFromIntent("content://s/", -1);

        // No value
        checkGetIdFromIntent("content://s/?ID=", -1);

        // Value not integer
        checkGetIdFromIntent("content://s/?ID=x", -1);

        // Negative value
        checkGetIdFromIntent("content://s/?ID=-100", -100);

        // Normal value
        checkGetIdFromIntent("content://s/?ID=200", 200);

        // With all 3 params
        i = new Intent(Intent.ACTION_VIEW, Uri.parse(
                "content://s/?ACCOUNT_ID=5&MAILBOX_ID=6&MESSAGE_ID=7"));
        assertEquals(5, IntentUtilities.getAccountIdFromIntent(i));
        assertEquals(6, IntentUtilities.getMailboxIdFromIntent(i));
        assertEquals(7, IntentUtilities.getMessageIdFromIntent(i));
    }

    public void checkGetIdFromIntent(String uri, long expected) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri.replace("ID", "ACCOUNT_ID")));
        assertEquals(expected, IntentUtilities.getAccountIdFromIntent(i));

        i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri.replace("ID", "MAILBOX_ID")));
        assertEquals(expected, IntentUtilities.getMailboxIdFromIntent(i));

        i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri.replace("ID", "MESSAGE_ID")));
        assertEquals(expected, IntentUtilities.getMessageIdFromIntent(i));
    }

    public void brokentestGetAccountUuidFromIntent() {
        Intent i;

        // No URL in intent
        i = new Intent(getContext(), getClass());
        assertEquals(null, IntentUtilities.getAccountUuidFromIntent(i));

        // No param
        i = new Intent(Intent.ACTION_VIEW, Uri.parse("content://s/"));
        assertEquals(null, IntentUtilities.getAccountUuidFromIntent(i));

        // No value
        i = new Intent(Intent.ACTION_VIEW, Uri.parse("content://s/?ACCOUNT_UUID="));
        assertEquals(null, IntentUtilities.getAccountUuidFromIntent(i));

        // With valid UUID
        i = new Intent(Intent.ACTION_VIEW, Uri.parse("content://s/?ACCOUNT_UUID=xyz"));
        assertEquals("xyz", IntentUtilities.getAccountUuidFromIntent(i));
    }
}
