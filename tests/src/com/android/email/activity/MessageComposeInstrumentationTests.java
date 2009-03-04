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

package com.android.email.activity;

import com.android.email.Account;
import com.android.email.Email;
import com.android.email.Preferences;
import com.android.email.R;
import com.android.email.activity.MessageCompose;
import com.android.email.mail.Address;
import com.android.email.mail.Message;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Message.RecipientType;
import com.android.email.mail.internet.MimeMessage;
import com.android.email.mail.internet.TextBody;

import android.content.Intent;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.MediumTest;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.content.Context;

/**
 * Various instrumentation tests for MessageCompose.  
 * 
 * It might be possible to convert these to ActivityUnitTest, which would be faster.
 */
@MediumTest
public class MessageComposeInstrumentationTests 
        extends ActivityInstrumentationTestCase2<MessageCompose> {
    
    private EditText mToView;
    private EditText mSubjectView;
    private EditText mMessageView;
    
    private static final String SENDER = "sender@android.com";
    private static final String REPLYTO = "replyto@android.com";
    private static final String RECIPIENT_TO = "recipient-to@android.com";
    private static final String RECIPIENT_CC = "recipient-cc@android.com";
    private static final String RECIPIENT_BCC = "recipient-bcc@android.com";
    private static final String SUBJECT = "This is the subject";
    private static final String BODY = "This is the body.  This is also the body.";
   
    /** Note - these are copied from private strings in MessageCompose.  Make them package? */
    private static final String ACTION_REPLY = "com.android.email.intent.action.REPLY";
    private static final String ACTION_REPLY_ALL = "com.android.email.intent.action.REPLY_ALL";
    private static final String ACTION_FORWARD = "com.android.email.intent.action.FORWARD";
    private static final String ACTION_EDIT_DRAFT = "com.android.email.intent.action.EDIT_DRAFT";
    
    public MessageComposeInstrumentationTests() {
        super("com.android.email", MessageCompose.class);
    }

    /*
     * The Message Composer activity is only enabled if one or more accounts
     * are configured on the device and a default account has been specified,
     * so we do that here before every test.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Context context = getInstrumentation().getTargetContext();
        Account[] accounts = Preferences.getPreferences(context).getAccounts();
        if (accounts.length > 0)
        {
            // This depends on getDefaultAccount() to auto-assign the default account, if necessary
            Preferences.getPreferences(context).getDefaultAccount();
            Email.setServicesEnabled(context);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        setActivityIntent(intent);
        final MessageCompose a = getActivity();
        mToView = (EditText) a.findViewById(R.id.to);
        mSubjectView = (EditText) a.findViewById(R.id.subject);
        mMessageView = (EditText) a.findViewById(R.id.message_content);
    }

    /**
     * The name 'test preconditions' is a convention to signal that if this
     * test doesn't pass, the test case was not set up properly and it might
     * explain any and all failures in other tests.  This is not guaranteed
     * to run before other tests, as junit uses reflection to find the tests.
     */
    public void testPreconditions() {
        assertNotNull(mToView);
        assertEquals(0, mToView.length());
        assertNotNull(mSubjectView);
        assertEquals(0, mSubjectView.length());
        assertNotNull(mMessageView);
        assertEquals(0, mMessageView.length());
    }
    
    /**
     * Test a couple of variations of processSourceMessage() for REPLY
     *   To = Reply-To or From:  (if REPLY)
     *   To = (Reply-To or From:) + To: + Cc:   (if REPLY_ALL)
     *   Subject = Re: Subject
     *   Body = empty  (and has cursor)
     *   
     *   TODO test REPLY_ALL
     */
    public void testProcessSourceMessageReply() throws MessagingException, Throwable {
        
        final Message message = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        Intent intent = new Intent(ACTION_REPLY);
        final MessageCompose a = getActivity();
        a.setIntent(intent);
        
        runTestOnUiThread(new Runnable() {
            public void run() {
                a.processSourceMessage(message);
                checkFields(SENDER + ", ", null, null, "Re: " + SUBJECT, null);
                checkFocused(mMessageView);
            }
        });
        
        message.setFrom(null);
        message.setReplyTo(Address.parse(REPLYTO));
        
        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                a.processSourceMessage(message);
                checkFields(REPLYTO + ", ", null, null, "Re: " + SUBJECT, null);
                checkFocused(mMessageView);
            }
        });
    }
    
    /**
     * Test processSourceMessage() for FORWARD
     *   To = empty  (and has cursor)
     *   Subject = Fwd: Subject
     *   Body = empty
     */
    public void testProcessSourceMessageForward() throws MessagingException, Throwable {
        
        final Message message = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        Intent intent = new Intent(ACTION_FORWARD);
        final MessageCompose a = getActivity();
        a.setIntent(intent);
        
        runTestOnUiThread(new Runnable() {
            public void run() {
                a.processSourceMessage(message);
                checkFields(null, null, null, "Fwd: " + SUBJECT, null);
                checkFocused(mToView);
            }
        });
    }
    
    /**
     * Test processSourceMessage() for EDIT_DRAFT
     * Reply and ReplyAll should map:
     *   To = to
     *   Subject = Subject
     *   Body = body (has cursor)
     *   
     * TODO check CC and BCC handling too
     */
    public void testProcessSourceMessageDraft() throws MessagingException, Throwable {
        
        final Message message = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        Intent intent = new Intent(ACTION_EDIT_DRAFT);
        final MessageCompose a = getActivity();
        a.setIntent(intent);
        
        runTestOnUiThread(new Runnable() {
            public void run() {
                a.processSourceMessage(message);
                checkFields(RECIPIENT_TO + ", ", null, null, SUBJECT, BODY);
                checkFocused(mMessageView);
            }
        });
        
        // if subject is null, then cursor should be there instead
        
        message.setSubject("");
        
        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                a.processSourceMessage(message);
                checkFields(RECIPIENT_TO + ", ", null, null, null, BODY);
                checkFocused(mSubjectView);
            }
        });
        
    }
    
    /**
     * Test for processing of Intent EXTRA_* fields that impact the headers:
     *   Intent.EXTRA_EMAIL, Intent.EXTRA_CC, Intent.EXTRA_BCC, Intent.EXTRA_SUBJECT
     */
    public void testIntentHeaderExtras() throws MessagingException, Throwable {
        
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtra(Intent.EXTRA_EMAIL, new String[] { RECIPIENT_TO });
        intent.putExtra(Intent.EXTRA_CC, new String[] { RECIPIENT_CC });
        intent.putExtra(Intent.EXTRA_BCC, new String[] { RECIPIENT_BCC });
        intent.putExtra(Intent.EXTRA_SUBJECT, SUBJECT);
        
        final MessageCompose a = getActivity();
        final Intent i2 = new Intent(intent);
        
        runTestOnUiThread(new Runnable() {
            public void run() {
                a.initFromIntent(i2);
                checkFields(RECIPIENT_TO + ", ", RECIPIENT_CC, RECIPIENT_BCC, SUBJECT, null);
                checkFocused(mMessageView);
            }
        });
    }
    
    /**
     * Test for processing of a typical browser "share" intent, e.g.
     * type="text/plain", EXTRA_TEXT="http:link.server.com"
     */
    public void testIntentSendPlainText() throws MessagingException, Throwable {
        
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, BODY);
        
        final MessageCompose a = getActivity();
        final Intent i2 = new Intent(intent);
        
        runTestOnUiThread(new Runnable() {
            public void run() {
                a.initFromIntent(i2);
                checkFields(null, null, null, null, BODY);
                checkFocused(mToView);
            }
        });
    }
    
    /**
     * Test for processing of a typical browser Mailto intent, e.g.
     * action=android.intent.action.VIEW
     * categories={android.intent.category.BROWSABLE}
     * data=mailto:user@domain.com?subject=This%20is%20%the%subject
     */
    public void testBrowserMailToIntent() throws MessagingException, Throwable {
        
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri uri = Uri.parse("mailto:" + RECIPIENT_TO + "?subject=This%20is%20the%20subject");
        intent.setData(uri);
        
        final MessageCompose a = getActivity();
        final Intent i2 = new Intent(intent);
        
        runTestOnUiThread(new Runnable() {
            public void run() {
                a.initFromIntent(i2);
                checkFields(RECIPIENT_TO + ", ", null, null, "This is the subject", null);
                checkFocused(mMessageView);
            }
        });
    }
    
    /**
     * TODO: test mailto: with simple encoding mode
     * TODO: test mailto: URI with all optional fields
     * TODO: come up with a way to add a very small attachment
     * TODO: confirm the various details between handling of SEND, VIEW, SENDTO
     */
    
    /**
     * Helper method to quickly check (and assert) on the to, subject, and content views.
     * 
     * @param to expected value (null = it must be empty)
     * @param cc expected value (null = it must be empty)
     * @param bcc expected value (null = it must be empty)
     * @param subject expected value (null = it must be empty)
     * @param content expected value (null = it must be empty)
     */
    private void checkFields(String to, String cc, String bcc, String subject, String content) {
        String toText = mToView.getText().toString();
        if (to == null) {
            assertEquals(0, toText.length());
        } else {
            assertEquals(to, toText);
        }

        String subjectText = mSubjectView.getText().toString();
        if (subject == null) {
            assertEquals(0, subjectText.length());
        } else {
            assertEquals(subject, subjectText);
        }

        String contentText = mMessageView.getText().toString();
        if (content == null) {
            assertEquals(0, contentText.length());
        } else {
            assertEquals(content, contentText);
        }
    }
    
    /**
     * Helper method to verify which field has the focus
     * @param focused The view that should be focused (all others should not have focus)
     */
    private void checkFocused(View focused) {
        assertEquals(focused == mToView, mToView.isFocused());
        assertEquals(focused == mSubjectView, mSubjectView.isFocused());
        assertEquals(focused == mMessageView, mMessageView.isFocused());
    }
    
    /**
     * Helper used when running multiple calls to processSourceMessage within a test method.
     * Simply clears out the views, so that we get fresh data and not appended data.
     * 
     * Must call from UI thread.
     */
    private void resetViews() {
        mToView.setText(null);
        mSubjectView.setText(null);
        mMessageView.setText(null);
    }
    
    /**
     * Build a test message that can be used as input to processSourceMessage
     * 
     * @param to Recipient(s) of the message
     * @param sender Sender(s) of the message
     * @param subject Subject of the message
     * @param content Content of the message
     * @return a complete Message object
     */
    private Message buildTestMessage(String to, String sender, String subject, String content) 
            throws MessagingException {
        Message message = new MimeMessage();
        
        if (to != null) {
            Address[] addresses = Address.parse(to);
            message.setRecipients(RecipientType.TO, addresses);
        }
        
        if (sender != null) {
            Address[] addresses = Address.parse(sender);
            message.setFrom(Address.parse(sender)[0]);
        }
        
        if (subject != null) {
            message.setSubject(subject);
        }
        
        if (content != null) {
            TextBody body = new TextBody(content);
            message.setBody(body);
        }
        
        return message;
    }
    
    /**
     * Tests for the comma-inserting logic.  The logic is applied equally to To: Cc: and Bcc:
     * but we only run the full set on To:
     */
    public void testCommaInserting() throws Throwable {
        // simple appending cases
        checkCommaInsert("a", "", false);
        checkCommaInsert("a@", "", false);
        checkCommaInsert("a@b", "", false);
        checkCommaInsert("a@b.", "", true); // non-optimal, but matches current implementation
        checkCommaInsert("a@b.c", "", true);
        
        // confirm works properly for internal editing
        checkCommaInsert("me@foo.com, you", " they@bar.com", false);
        checkCommaInsert("me@foo.com, you@", "they@bar.com", false);
        checkCommaInsert("me@foo.com, you@bar", " they@bar.com", false);
        checkCommaInsert("me@foo.com, you@bar.", " they@bar.com", true); // non-optimal
        checkCommaInsert("me@foo.com, you@bar.com", " they@bar.com", true);
        
        // check a couple of multi-period cases
        checkCommaInsert("me.myself@foo", "", false);
        checkCommaInsert("me.myself@foo.com", "", true);
        checkCommaInsert("me@foo.co.uk", "", true);
        
        // cases that should not append because there's already a comma
        checkCommaInsert("a@b.c,", "", false);
        checkCommaInsert("me@foo.com, you@bar.com,", " they@bar.com", false);
        checkCommaInsert("me.myself@foo.com,", "", false);
        checkCommaInsert("me@foo.co.uk,", "", false);
    }

    /**
     * Check comma insertion logic for a single try on the To: field
     */
    private void checkCommaInsert(final String before, final String after, boolean expectComma)
            throws Throwable {
        String expect = new String(before + (expectComma ? ", " : " ") + after);

        runTestOnUiThread(new Runnable() {
            public void run() {
                mToView.setText(before + after);
                mToView.setSelection(before.length());
            }
        });
        getInstrumentation().sendStringSync(" ");
        String result = mToView.getText().toString();
        assertEquals(expect, result);
      
     }

}
