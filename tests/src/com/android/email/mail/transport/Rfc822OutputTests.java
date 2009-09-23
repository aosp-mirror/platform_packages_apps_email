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

package com.android.email.mail.transport;

import com.android.email.provider.EmailContent.Message;

import android.test.AndroidTestCase;

/**
 * Tests of the Rfc822Output (used for sending mail)
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.mail.transport.Rfc822OutputTests email
 */
public class Rfc822OutputTests extends AndroidTestCase {
    private static final String SENDER = "sender@android.com";
    private static final String REPLYTO = "replyto@android.com";
    private static final String RECIPIENT_TO = "recipient-to@android.com";
    private static final String RECIPIENT_CC = "recipient-cc@android.com";
    private static final String RECIPIENT_BCC = "recipient-bcc@android.com";
    private static final String SUBJECT = "This is the subject";
    private static final String BODY = "This is the body.  This is also the body.";
    private static final String TEXT = "Here is some new text.";
    private static final String REPLY_BODY_SHORT = "\n\n" + SENDER + " wrote:\n\n";
    private static final String REPLY_BODY = REPLY_BODY_SHORT + ">" + BODY;

    // TODO Create more tests here.  Specifically, we should test to make sure that forward works
    // properly instead of just reply

    // TODO Write test that ensures that bcc is handled properly (i.e. sent/not send depending
    // on the flag passed to writeTo

    // TODO Localize the following test, which will not work properly in other than English
    // speaking locales!

    /**
     * Test for buildBodyText().
     * Compare with expected values.
     * Also test the situation where the message has no body.
     *
     * WARNING: This test is NOT localized, so it will fail if run on a device in a
     * non-English speaking locale!
     */
    public void testBuildBodyTextWithReply() {
        // Create the least necessary; sender, flags, and the body of the reply
        Message msg = new Message();
        msg.mText = "";
        msg.mFrom = SENDER;
        msg.mFlags = Message.FLAG_TYPE_REPLY;
        msg.mTextReply = BODY;
        msg.mIntroText = REPLY_BODY_SHORT;
        msg.save(getContext());

        String body = Rfc822Output.buildBodyText(getContext(), msg, true);
        assertEquals(REPLY_BODY, body);

        // Save a different message with no reply body (so we reset the id)
        msg.mId = -1;
        msg.mTextReply = null;
        msg.save(getContext());
        body = Rfc822Output.buildBodyText(getContext(), msg, true);
        assertEquals(REPLY_BODY_SHORT, body);
    }

    /**
     * Test for buildBodyText().
     * Compare with expected values.
     * Also test the situation where the message has no body.
     */
    public void testBuildBodyTextWithoutReply() {
        // Create the least necessary; sender, flags, and the body of the reply
        Message msg = new Message();
        msg.mText = TEXT;
        msg.mFrom = SENDER;
        msg.mFlags = Message.FLAG_TYPE_REPLY;
        msg.mTextReply = BODY;
        msg.mIntroText = REPLY_BODY_SHORT;
        msg.save(getContext());

        String body = Rfc822Output.buildBodyText(getContext(), msg, false);
        assertEquals(TEXT + REPLY_BODY_SHORT, body);

        // Save a different message with no reply body (so we reset the id)
        msg.mId = -1;
        msg.mTextReply = null;
        msg.save(getContext());
        body = Rfc822Output.buildBodyText(getContext(), msg, false);
        assertEquals(TEXT + REPLY_BODY_SHORT, body);
    }
 }
