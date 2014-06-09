/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.emailcommon.provider;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import org.json.JSONException;
import org.json.JSONObject;

@SmallTest
public class AccountTest extends AndroidTestCase {

    public void testDeserializeFromJSON() throws JSONException {
        final JSONObject json = new JSONObject();
        json.put(EmailContent.AccountColumns.DISPLAY_NAME, "David Hasselhoff");
        json.put(EmailContent.AccountColumns.EMAIL_ADDRESS, "dhoff@example.com");
        json.put(EmailContent.AccountColumns.SYNC_LOOKBACK, 42);
        json.put(EmailContent.AccountColumns.SYNC_INTERVAL, 99);
        json.put(Account.JSON_TAG_HOST_AUTH_RECV, getHostAuthJSON("receiver", "recpass").toJson());
        json.put(Account.JSON_TAG_HOST_AUTH_SEND, getHostAuthJSON("send", "sendpass").toJson());
        json.put(EmailContent.AccountColumns.FLAGS, 22);
        json.put(EmailContent.AccountColumns.SENDER_NAME, "Friend of Kitt");
        json.put(EmailContent.AccountColumns.PROTOCOL_VERSION, "protocol version 3.14");
        json.put(EmailContent.AccountColumns.SIGNATURE, "David with a heart over the i");
        json.put(EmailContent.AccountColumns.PING_DURATION, 77);

        // deserialize the json
        final Account a = Account.fromJson(json);

        // verify that all fields deserialized as expected
        assertEquals("David Hasselhoff", a.getDisplayName());
        assertEquals("dhoff@example.com", a.getEmailAddress());
        assertEquals(42, a.getSyncLookback());
        assertEquals(99, a.getSyncInterval());
        assertEquals("receiver", a.mHostAuthRecv.mLogin);
        assertEquals("recpass", a.mHostAuthRecv.mPassword);
        assertEquals("send", a.mHostAuthSend.mLogin);
        assertEquals("sendpass", a.mHostAuthSend.mPassword);
        assertEquals(22, a.getFlags());
        assertEquals("Friend of Kitt", a.getSenderName());
        assertEquals("protocol version 3.14", a.mProtocolVersion);
        assertEquals("David with a heart over the i", a.getSignature());
        assertEquals(77, a.mPingDuration);
    }

    /**
     * A factory method to generate HostAuth values that allow us to serialize the Account object.
     * See {@link HostAuthTests} for tests that exercise serialization of HostAuth.
     *
     * @param username a given username
     * @param password a given password
     * @return a HostAuth that includes the given username and password
     */
    private static HostAuth getHostAuthJSON(String username, String password) {
        final HostAuth ha = new HostAuth();
        ha.setLogin(username, password);
        ha.mProtocol = "IMAP";
        ha.mAddress = "dhoff@example.com";
        ha.mPort = 543;
        ha.mFlags = 777;
        return ha;
    }

    public void testSerializeAndDeserializeWithJSON() {
        // build an Account object with all fields set
        final Account before = new Account();
        before.setDisplayName("David Hasselhoff");
        before.setEmailAddress("dhoff@example.com");
        before.mSyncKey = "syncKey";
        before.setSyncLookback(42);
        before.setSyncInterval(99);
        before.setFlags(1 << 5);
        before.setSenderName("Friend of Kitt");
        before.mProtocolVersion = "protocol version 3.14";
        before.mSecuritySyncKey = "securitySyncKey";
        before.setSignature("David with a heart over the i");
        before.mPolicyKey = 66;
        before.mPingDuration = 77;
        before.mHostAuthRecv = getHostAuthJSON("receiver", "recpass");

        // this must be called before serialization occurs
        before.ensureLoaded(getContext());

        // serialize and deserialize
        final Account after = Account.fromJson(before.toJson());

        assertEquals(before.getDisplayName(), after.getDisplayName());
        assertEquals(before.getEmailAddress(), after.getEmailAddress());
        assertEquals(before.getSyncLookback(), after.getSyncLookback());
        assertEquals(before.getSyncInterval(), after.getSyncInterval());
        assertEquals(before.mHostAuthSend, after.mHostAuthSend);
        assertEquals(before.mHostAuthKeySend, after.mHostAuthKeySend);
        assertEquals(before.mHostAuthKeyRecv, after.mHostAuthKeyRecv);
        assertEquals(before.getFlags(), after.getFlags());
        assertEquals(before.getSenderName(), after.getSenderName());
        assertEquals(before.mProtocolVersion, after.mProtocolVersion);
        assertEquals(before.getSignature(), after.getSignature());
        assertEquals(before.mPingDuration, after.mPingDuration);

        assertNull(after.mSyncKey); // sync key is not serialized; field defaults to null
        assertEquals(0, after.mPolicyKey); // policy key is not serialized; field defaults to 0
    }
}
