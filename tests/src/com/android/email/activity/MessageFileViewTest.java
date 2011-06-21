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

package com.android.email.activity;

import com.android.email.DBTestHelper;
import com.android.email.TestUtils;
import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.EmailContent.Message;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;

/**
 * Test case for {@link MessageFileView}.
 *
 * TODO Add more tests.  Any good way to test fragment??
 */
public class MessageFileViewTest extends ActivityInstrumentationTestCase2<MessageFileView> {

    private static int TIMEOUT = 10; // in seconds

    private Context mProviderContext;

    public MessageFileViewTest() {
        super(MessageFileView.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mProviderContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(
                getInstrumentation().getTargetContext());
    }

    private void setUpIntent(Uri uri) {
        final Intent i = new Intent(getInstrumentation().getTargetContext(), MessageFileView.class);
        i.setData(uri);
        setActivityIntent(i);
    }

    private  Uri createEmlFile() throws Exception {
        // Create a simple message
        Message msg = new Message();
        String text = "This is some text";
        msg.mText = text;
        String sender = "sender@host.com";
        msg.mFrom = sender;
        // Save this away
        msg.save(mProviderContext);

        return ProviderTestUtils.createTempEmlFile(mProviderContext, msg,
                getInstrumentation().getContext().getFilesDir());
    }

    /**
     * Set up an EML file, and open it in the activity.
     *
     * Expected: Message opens.
     */
    public void testOpenMessage() throws Exception {
        setUpIntent(createEmlFile());

        final MessageFileView activity = getActivity();

        TestUtils.waitUntil(new TestUtils.Condition() {
            @Override
            public boolean isMet() {
                MessageFileViewFragment f = activity.getFragment();
                return f != null && f.isMessageLoadedForTest();
            }
        }, TIMEOUT);

        // TODO Check UI elements, once our UI is settled.
    }

}
