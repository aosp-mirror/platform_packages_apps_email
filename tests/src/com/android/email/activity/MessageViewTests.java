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

// Disabled for now, as these tests will probably not make sense with the upcoming UI change.
// TODO Revive them in some way.

//package com.android.email.activity;
//
//import com.android.email.Email;
//import com.android.email.MessagingController;
//import com.android.email.R;
//
//import android.app.Application;
//import android.content.Context;
//import android.content.Intent;
//import android.test.ActivityInstrumentationTestCase2;
//import android.test.UiThreadTest;
//import android.test.suitebuilder.annotation.LargeTest;
//import android.test.suitebuilder.annotation.Suppress;
//import android.view.View;
//import android.webkit.WebView;
//import android.widget.TextView;
//
///**
// * Various instrumentation tests for MessageCompose.
// *
// * It might be possible to convert these to ActivityUnitTest, which would be faster.
// */
//@LargeTest
//public class MessageViewTests
//        extends ActivityInstrumentationTestCase2<MessageView> {
//
//    // copied from MessageView (could be package class)
//    private static final String EXTRA_MESSAGE_ID = "com.android.email.MessageView_message_id";
//    private static final String EXTRA_MAILBOX_ID = "com.android.email.MessageView_mailbox_id";
//
//    private TextView mToView;
//    private TextView mSubjectView;
//    private WebView mMessageContentView;
//    private Context mContext;
//
//    public MessageViewTests() {
//        super(MessageView.class);
//    }
//
//    @Override
//    protected void setUp() throws Exception {
//        super.setUp();
//
//        mContext = getInstrumentation().getTargetContext();
//        Email.setTempDirectory(mContext);
//        Email.setServicesEnabled(mContext);
//
//        // setup an intent to spin up this activity with something useful
//        // Long.MIN_VALUE are sentinels to command MessageView to skip loading
//        Intent i = new Intent()
//            .putExtra(EXTRA_MESSAGE_ID, Long.MIN_VALUE)
//            .putExtra(EXTRA_MAILBOX_ID, Long.MIN_VALUE);
//        this.setActivityIntent(i);
//
//        // configure a mock controller
//        MessagingController mockController =
//            new MockMessagingController(getActivity().getApplication());
//        MessagingController.injectMockController(mockController);
//
//        final MessageView a = getActivity();
//        mToView = (TextView) a.findViewById(R.id.to);
//        mSubjectView = (TextView) a.findViewById(R.id.subject);
//        mMessageContentView = (WebView) a.findViewById(R.id.message_content);
//    }
//
//    /**
//     * The name 'test preconditions' is a convention to signal that if this
//     * test doesn't pass, the test case was not set up properly and it might
//     * explain any and all failures in other tests.  This is not guaranteed
//     * to run before other tests, as junit uses reflection to find the tests.
//     */
//    public void testPreconditions() {
//        assertNotNull(mToView);
//        assertEquals(0, mToView.length());
//        assertNotNull(mSubjectView);
//        assertEquals(0, mSubjectView.length());
//        assertNotNull(mMessageContentView);
//    }
//
//    /**
//     * Tests that various UI calls can be made safely even before the messaging controller
//     * has completed loading the message.  This catches various race conditions.
//     */
//    @Suppress
//    public void testUiRaceConditions() {
//
//        MessageView a = getActivity();
//
//        // on-streen controls
//        a.onClick(a.findViewById(R.id.reply));
//        a.onClick(a.findViewById(R.id.reply_all));
//        a.onClick(a.findViewById(R.id.delete));
//        a.onClick(a.findViewById(R.id.moveToOlder));
//        a.onClick(a.findViewById(R.id.moveToNewer));
////      a.onClick(a.findViewById(R.id.download));    // not revealed yet, so unfair test
////      a.onClick(a.findViewById(R.id.view));        // not revealed yet, so unfair test
//        a.onClick(a.findViewById(R.id.show_pictures));
//
//        // menus
//        a.handleMenuItem(R.id.delete);
//        a.handleMenuItem(R.id.reply);
//        a.handleMenuItem(R.id.reply_all);
//        a.handleMenuItem(R.id.forward);
//        a.handleMenuItem(R.id.mark_as_unread);
//    }
//
//    /**
//     * Sets EXTRA_DISABLE_REPLY on the intent to true/false, and
//     * checks change in replyButton.isEnabled().
//     */
//    @UiThreadTest
//    public void testDisableReply() {
//        MessageView a = getActivity();
//        View replyButton = a.findViewById(R.id.reply);
//
//        Intent i = new Intent();
//        a.setIntent(i);
//        a.initFromIntent();
//        assertTrue(replyButton.isEnabled());
//
//        i.putExtra(MessageView.EXTRA_DISABLE_REPLY, true);
//        a.setIntent(i);
//        a.initFromIntent();
//        assertFalse(replyButton.isEnabled());
//    }
//
//    /**
//     * Mock Messaging controller, so we can drive its callbacks.  This probably should be
//     * generalized since we're likely to use for other tests eventually.
//     */
//    private static class MockMessagingController extends MessagingController {
//
//        private MockMessagingController(Application application) {
//            super(application);
//        }
//    }
//}
