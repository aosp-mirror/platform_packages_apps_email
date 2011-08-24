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

import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.MultiAutoCompleteTextView;

import com.android.email.Email;
import com.android.email.EmailAddressValidator;
import com.android.email.R;
import com.android.email.TestUtils;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Message;
import com.google.android.collect.Lists;

import java.util.ArrayList;


/**
 * Various instrumentation tests for MessageCompose.
 *
 * It might be possible to convert these to ActivityUnitTest, which would be faster.
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.activity.MessageComposeTests email
 */
@LargeTest
public class MessageComposeTests
        extends ActivityInstrumentationTestCase2<MessageCompose> {

    private Context mContext;

    private MultiAutoCompleteTextView mToView;
    private MultiAutoCompleteTextView mCcView;
    private MultiAutoCompleteTextView mBccView;
    private EditText mSubjectView;
    private EditText mMessageView;
    private long mCreatedAccountId = -1;
    private String mSignature;

    private static final String ACCOUNT = "account@android.com";
    private static final String SENDER = "sender@android.com";
    private static final String REPLYTO = "replyto@android.com";
    private static final String RECIPIENT_TO = "recipient-to@android.com";
    private static final String RECIPIENT_CC = "recipient-cc@android.com";
    private static final String RECIPIENT_BCC = "recipient-bcc@android.com";
    private static final String SUBJECT = "This is the subject";
    private static final String BODY = "This is the body.  This is also the body.";
    private static final String SIGNATURE = "signature";

    private static final String FROM = "Fred From <from@google.com>";
    private static final String TO1 = "First To <first.to@google.com>";
    private static final String TO2 = "Second To <second.to@google.com>";
    private static final String TO3 = "CopyFirst Cc <first.cc@google.com>";
    private static final String CC1 = "First Cc <first.cc@google.com>";
    private static final String CC2 = "Second Cc <second.cc@google.com>";
    private static final String CC3 = "Third Cc <third.cc@google.com>";
    private static final String CC4 = "CopySecond To <second.to@google.com>";

    private static final String UTF16_SENDER =
            "\u3042\u3044\u3046 \u3048\u304A <sender@android.com>";
    private static final String UTF16_REPLYTO =
            "\u3042\u3044\u3046\u3048\u304A <replyto@android.com>";
    private static final String UTF16_RECIPIENT_TO =
            "\"\u3042\u3044\u3046,\u3048\u304A\" <recipient-to@android.com>";
    private static final String UTF16_RECIPIENT_CC =
            "\u30A2\u30AB \u30B5\u30BF\u30CA <recipient-cc@android.com>";
    private static final String UTF16_RECIPIENT_BCC =
            "\"\u30A2\u30AB,\u30B5\u30BF\u30CA\" <recipient-bcc@android.com>";
    private static final String UTF16_SUBJECT = "\u304A\u5BFF\u53F8\u306B\u3059\u308B\uFF1F";
    private static final String UTF16_BODY = "\u65E5\u672C\u8A9E\u306E\u6587\u7AE0";

    private static final String UTF32_SENDER =
            "\uD834\uDF01\uD834\uDF46 \uD834\uDF22 <sender@android.com>";
    private static final String UTF32_REPLYTO =
            "\uD834\uDF01\uD834\uDF46\uD834\uDF22 <replyto@android.com>";
    private static final String UTF32_RECIPIENT_TO =
            "\"\uD834\uDF01\uD834\uDF46,\uD834\uDF22\" <recipient-to@android.com>";
    private static final String UTF32_RECIPIENT_CC =
            "\uD834\uDF22 \uD834\uDF01\uD834\uDF46 <recipient-cc@android.com>";
    private static final String UTF32_RECIPIENT_BCC =
            "\"\uD834\uDF22,\uD834\uDF01\uD834\uDF46\" <recipient-bcc@android.com>";
    private static final String UTF32_SUBJECT = "\uD834\uDF01\uD834\uDF46";
    private static final String UTF32_BODY = "\uD834\uDF01\uD834\uDF46";

    /*
     * The following action definitions are purposefully copied from MessageCompose, so that
     * any changes to the action strings will break these tests. Changes to the actions should
     * be done consciously to think about existing shortcuts and clients.
     */

    private static final String ACTION_REPLY = "com.android.email.intent.action.REPLY";
    private static final String ACTION_REPLY_ALL = "com.android.email.intent.action.REPLY_ALL";
    private static final String ACTION_FORWARD = "com.android.email.intent.action.FORWARD";
    private static final String ACTION_EDIT_DRAFT = "com.android.email.intent.action.EDIT_DRAFT";

    public MessageComposeTests() {
        super(MessageCompose.class);
    }

    /*
     * The Message Composer activity is only enabled if one or more accounts
     * are configured on the device and a default account has been specified,
     * so we do that here before every test.
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mContext = getInstrumentation().getTargetContext();

        // Force assignment of a default account
        long accountId = Account.getDefaultAccountId(mContext);
        if (accountId == -1) {
            Account account = new Account();
            account.mSenderName = "Bob Sender";
            account.mEmailAddress = "bob@sender.com";
            account.mSignature = SIGNATURE;
            account.save(mContext);
            accountId = account.mId;
            mCreatedAccountId = accountId;
        }
        Account account = Account.restoreAccountWithId(mContext, accountId);
        mSignature = account.getSignature();
        Email.setServicesEnabledSync(mContext);

        Intent intent = new Intent(Intent.ACTION_VIEW);
        setActivityIntent(intent);
        final MessageCompose a = getActivity();
        mToView = (MultiAutoCompleteTextView) a.findViewById(R.id.to);
        mCcView = (MultiAutoCompleteTextView) a.findViewById(R.id.cc);
        mBccView = (MultiAutoCompleteTextView) a.findViewById(R.id.bcc);
        mSubjectView = (EditText) a.findViewById(R.id.subject);
        mMessageView = (EditText) a.findViewById(R.id.message_content);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        Context context = getInstrumentation().getTargetContext();
        // If we created an account, delete it here
        if (mCreatedAccountId > -1) {
            context.getContentResolver().delete(
                    ContentUris.withAppendedId(Account.CONTENT_URI, mCreatedAccountId), null, null);
        }
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

        // Note that the signature is always preceeded with a newline.
        int sigLength = (mSignature == null) ? 0 : (1 + mSignature.length());
        assertEquals(sigLength, mMessageView.length());
    }

     /**
     * Test a couple of variations of processSourceMessage() for REPLY.
     *   To = Reply-To or From:  (if REPLY)
     *   To = (Reply-To or From:), Cc = + To: + Cc:   (if REPLY_ALL)
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
        final Account account = new Account();
        account.mEmailAddress = ACCOUNT;

        runTestOnUiThread(new Runnable() {
            public void run() {
                a.processSourceMessage(message, account);
                a.setInitialComposeText(null, null);
                checkFields(SENDER + ", ", null, null, "Re: " + SUBJECT, null, null);
                checkFocused(mMessageView);
            }
        });

        message.mFrom = null;
        message.mReplyTo = Address.parseAndPack(REPLYTO);

        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                a.processSourceMessage(message, account);
                a.setInitialComposeText(null, null);
                checkFields(REPLYTO + ", ", null, null, "Re: " + SUBJECT, null, null);
                checkFocused(mMessageView);
            }
        });
    }

    /**
     * Tests similar cases as testProcessSourceMessageReply, but when sender is in From: (or
     * Reply-to: if applicable) field.
     *
     * To = To (if REPLY)
     * To = To, Cc = Cc (if REPLY_ALL)
     * Subject = Re: Subject
     * Body = empty  (and has cursor)
     *
     * @throws MessagingException
     * @throws Throwable
     */
    public void testRepliesWithREplyToFields() throws MessagingException, Throwable {
        final Message message = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        message.mCc = RECIPIENT_CC;
        Intent intent = new Intent(ACTION_REPLY);
        final MessageCompose a = getActivity();
        a.setIntent(intent);
        final Account account = new Account();
        account.mEmailAddress = SENDER;

        runTestOnUiThread(new Runnable() {
            public void run() {
                a.processSourceMessage(message, account);
                a.setInitialComposeText(null, null);
                checkFields(RECIPIENT_TO + ", ", null, null, "Re: " + SUBJECT, null, null);
                checkFocused(mMessageView);
            }
        });

        message.mFrom = null;
        message.mReplyTo = Address.parseAndPack(SENDER);

        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                a.processSourceMessage(message, account);
                a.setInitialComposeText(null, null);
                checkFields(RECIPIENT_TO + ", ", null, null, "Re: " + SUBJECT, null, null);
                checkFocused(mMessageView);
            }
        });

        a.setIntent(new Intent(ACTION_REPLY_ALL));
        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                a.processSourceMessage(message, account);
                a.setInitialComposeText(null, null);
                checkFields(RECIPIENT_TO + ", ", RECIPIENT_CC + ", ", null,
                        "Re: " + SUBJECT, null, null);
                checkFocused(mMessageView);
            }
        });
    }

    public void testProcessSourceMessageReplyWithSignature() throws MessagingException, Throwable {
        final Message message = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        Intent intent = new Intent(ACTION_REPLY);
        final MessageCompose a = getActivity();
        a.setIntent(intent);
        final Account account = new Account();
        account.mEmailAddress = ACCOUNT;
        account.mSignature = SIGNATURE;
        runTestOnUiThread(new Runnable() {
            public void run() {
                a.processSourceMessage(message, account);
                a.setInitialComposeText(null, SIGNATURE);
                checkFields(SENDER + ", ", null, null, "Re: " + SUBJECT, null, SIGNATURE);
                checkFocused(mMessageView);
            }
        });

        message.mFrom = null;
        message.mReplyTo = Address.parseAndPack(REPLYTO);

        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                a.processSourceMessage(message, account);
                a.setInitialComposeText(null, SIGNATURE);
                checkFields(REPLYTO + ", ", null, null, "Re: " + SUBJECT, null, SIGNATURE);
                checkFocused(mMessageView);
            }
        });
    }

    public void testProcessSourceMessageForwardWithSignature()
            throws MessagingException, Throwable {
        final Message message = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        Intent intent = new Intent(ACTION_FORWARD);
        final MessageCompose a = getActivity();
        a.setIntent(intent);
        final Account account = new Account();
        account.mSignature = SIGNATURE;
        runTestOnUiThread(new Runnable() {
            public void run() {
                a.processSourceMessage(message, account);
                a.setInitialComposeText(null, SIGNATURE);
                checkFields(null, null, null, "Fwd: " + SUBJECT, null, SIGNATURE);
                checkFocused(mToView);
           }
        });
    }

    /**
     * Test reply to utf-16 name and address
     */
    public void testProcessSourceMessageReplyUtf16() throws MessagingException, Throwable {
        final Message message = buildTestMessage(UTF16_RECIPIENT_TO, UTF16_SENDER,
                UTF16_SUBJECT, UTF16_BODY);
        Intent intent = new Intent(ACTION_REPLY);
        final MessageCompose a = getActivity();
        a.setIntent(intent);
        final Account account = new Account();
        account.mEmailAddress = ACCOUNT;

        runTestOnUiThread(new Runnable() {
            public void run() {
                a.processSourceMessage(message, account);
                a.setInitialComposeText(null, null);
                checkFields(UTF16_SENDER + ", ", null, null, "Re: " + UTF16_SUBJECT, null, null);
                checkFocused(mMessageView);
            }
        });

        message.mFrom = null;
        message.mReplyTo = Address.parseAndPack(UTF16_REPLYTO);

        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                a.processSourceMessage(message, account);
                a.setInitialComposeText(null, null);
                checkFields(UTF16_REPLYTO + ", ", null, null, "Re: " + UTF16_SUBJECT, null, null);
                checkFocused(mMessageView);
            }
        });
    }

    /**
     * Test reply to utf-32 name and address
     */
    public void testProcessSourceMessageReplyUtf32() throws MessagingException, Throwable {
        final Message message = buildTestMessage(UTF32_RECIPIENT_TO, UTF32_SENDER,
                UTF32_SUBJECT, UTF32_BODY);
        Intent intent = new Intent(ACTION_REPLY);
        final MessageCompose a = getActivity();
        a.setIntent(intent);
        final Account account = new Account();
        account.mEmailAddress = ACCOUNT;

        runTestOnUiThread(new Runnable() {
            public void run() {
                a.processSourceMessage(message, account);
                a.setInitialComposeText(null, null);
                checkFields(UTF32_SENDER + ", ", null, null, "Re: " + UTF32_SUBJECT, null, null);
                checkFocused(mMessageView);
            }
        });

        message.mFrom = null;
        message.mReplyTo = Address.parseAndPack(UTF32_REPLYTO);

        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                a.processSourceMessage(message, account);
                a.setInitialComposeText(null, null);
                checkFields(UTF32_REPLYTO + ", ", null, null, "Re: " + UTF32_SUBJECT, null, null);
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
                a.processSourceMessage(message, null);
                a.setInitialComposeText(null, null);
                checkFields(null, null, null, "Fwd: " + SUBJECT, null, null);
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
    public void testProcessDraftMessage() throws MessagingException, Throwable {

        final Message message = buildTestMessage(RECIPIENT_TO, SENDER, SUBJECT, BODY);
        Intent intent = new Intent(ACTION_EDIT_DRAFT);
        final MessageCompose a = getActivity();
        a.setIntent(intent);

        runTestOnUiThread(new Runnable() {
            public void run() {
                a.processDraftMessage(message, true);
                checkFields(RECIPIENT_TO + ", ", null, null, SUBJECT, BODY, null);
                checkFocused(mMessageView);
            }
        });

        // if subject is null, then cursor should be there instead

        message.mSubject = "";

        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                a.processDraftMessage(message, true);
                checkFields(RECIPIENT_TO + ", ", null, null, null, BODY, null);
                checkFocused(mSubjectView);
            }
        });

    }

    /**
     * Test processDraftMessage() for EDIT_DRAFT with utf-16 name and address
     * TODO check CC and BCC handling too
     */
    public void testProcessDraftMessageWithUtf16() throws MessagingException, Throwable {

        final Message message = buildTestMessage(UTF16_RECIPIENT_TO, UTF16_SENDER,
                UTF16_SUBJECT, UTF16_BODY);
        Intent intent = new Intent(ACTION_EDIT_DRAFT);
        final MessageCompose a = getActivity();
        a.setIntent(intent);

        runTestOnUiThread(new Runnable() {
            public void run() {
                a.processDraftMessage(message, true);
                checkFields(UTF16_RECIPIENT_TO + ", ",
                        null, null, UTF16_SUBJECT, UTF16_BODY, null);
                checkFocused(mMessageView);
            }
        });

        // if subject is null, then cursor should be there instead

        message.mSubject = "";

        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                a.processDraftMessage(message, true);
                checkFields(UTF16_RECIPIENT_TO + ", ", null, null, null, UTF16_BODY, null);
                checkFocused(mSubjectView);
            }
        });

    }

    /**
     * Test processDraftMessage() for EDIT_DRAFT with utf-32 name and address
     * TODO check CC and BCC handling too
     */
    public void testProcessDraftMessageWithUtf32() throws MessagingException, Throwable {

        final Message message = buildTestMessage(UTF32_RECIPIENT_TO, UTF32_SENDER,
                UTF32_SUBJECT, UTF32_BODY);
        Intent intent = new Intent(ACTION_EDIT_DRAFT);
        final MessageCompose a = getActivity();
        a.setIntent(intent);

        runTestOnUiThread(new Runnable() {
            public void run() {
                a.processDraftMessage(message, true);
                checkFields(UTF32_RECIPIENT_TO + ", ",
                        null, null, UTF32_SUBJECT, UTF32_BODY, null);
                checkFocused(mMessageView);
            }
        });

        // if subject is null, then cursor should be there instead

        message.mSubject = "";

        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                a.processDraftMessage(message, true);
                checkFields(UTF32_RECIPIENT_TO + ", ", null, null, null, UTF32_BODY, null);
                checkFocused(mSubjectView);
            }
        });

    }

    /**
     * Check that we create the proper to and cc addressees in reply and reply-all, making sure
     * to reject duplicate addressees AND the email address of the sending account
     *
     * In this case, we're doing a "reply"
     * The user is TO1 (a "to" recipient)
     * The to should be: FROM
     * The cc should be empty
     */
    public void testReplyAddresses() throws Throwable {
        final MessageCompose a = getActivity();
        // Doesn't matter what Intent we use here
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        Message msg = new Message();
        final Account account = new Account();

        msg.mFrom = Address.parseAndPack(FROM);
        msg.mTo = Address.parseAndPack(TO1 + ',' + TO2);
        msg.mCc = Address.parseAndPack(CC1 + ',' + CC2 + ',' + CC3);
        final Message message = msg;
        account.mEmailAddress = "FiRsT.tO@gOoGlE.cOm";

        runTestOnUiThread(new Runnable() {
            public void run() {
                a.initFromIntent(intent);
                a.setupAddressViews(message, account, false);
                assertEquals("", mCcView.getText().toString());
                String result = Address.parseAndPack(mToView.getText().toString());
                String expected = Address.parseAndPack(FROM);
                assertEquals(expected, result);

                // It doesn't harm even if the CC view is visible.
            }
        });
    }

    /**
     * Check that we create the proper to and cc addressees in reply and reply-all, making sure
     * to reject duplicate addressees AND the email address of the sending account
     *
     * In this case, we're doing a "reply all"
     * The user is TO1 (a "to" recipient)
     * The to should be: FROM
     * The cc should be: TO2, CC1, CC2, and CC3
     */
    public void testReplyAllAddresses1() throws Throwable {
        final MessageCompose a = getActivity();
        // Doesn't matter what Intent we use here
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        Message msg = new Message();
        final Account account = new Account();

        msg.mFrom = Address.parseAndPack(FROM);
        msg.mTo = Address.parseAndPack(TO1 + ',' + TO2);
        msg.mCc = Address.parseAndPack(CC1 + ',' + CC2 + ',' + CC3);
        final Message message = msg;
        account.mEmailAddress = "FiRsT.tO@gOoGlE.cOm";

        runTestOnUiThread(new Runnable() {
            public void run() {
                a.initFromIntent(intent);
                a.setupAddressViews(message, account, true);
                String result = Address.parseAndPack(mToView.getText().toString());
                String expected = Address.parseAndPack(FROM);
                assertEquals(expected, result);
                result = Address.parseAndPack(mCcView.getText().toString());
                expected = Address.parseAndPack(TO2 + ',' + CC1 + ',' + CC2 + ',' + CC3);
                assertEquals(expected, result);
                TestUtils.assertViewVisible(mCcView);
            }
        });
    }

    /**
     * Check that we create the proper to and cc addressees in reply and reply-all, making sure
     * to reject duplicate addressees AND the email address of the sending account
     *
     * In this case, we're doing a "reply all"
     * The user is CC2 (a "cc" recipient)
     * The to should be: FROM,
     * The cc should be: TO1, TO2, CC1 and CC3 (CC2 is our account's email address)
     */
    public void testReplyAllAddresses2() throws Throwable {
        final MessageCompose a = getActivity();
        // Doesn't matter what Intent we use here
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        Message msg = new Message();
        final Account account = new Account();

        msg.mFrom = Address.parseAndPack(FROM);
        msg.mTo = Address.parseAndPack(TO1 + ',' + TO2);
        msg.mCc = Address.parseAndPack(CC1 + ',' + CC2 + ',' + CC3);
        final Message message = msg;
        account.mEmailAddress = "sEcOnD.cC@gOoGlE.cOm";

        runTestOnUiThread(new Runnable() {
            public void run() {
                a.initFromIntent(intent);
                a.setupAddressViews(message, account, true);
                String result = Address.parseAndPack(mToView.getText().toString());
                String expected = Address.parseAndPack(FROM);
                assertEquals(expected, result);
                result = Address.parseAndPack(mCcView.getText().toString());
                expected = Address.parseAndPack(TO1 + ',' + TO2 + ',' + CC1 + ',' + CC3);
                assertEquals(expected, result);
                TestUtils.assertViewVisible(mCcView);
            }
        });
    }

    /**
     * Check that we create the proper to and cc addressees in reply and reply-all, making sure
     * to reject duplicate addressees AND the email address of the sending account
     *
     * In this case, we're doing a "reply all"
     * The user is CC2 (a "cc" recipient)
     * The to should be: FROM
     * The cc should be: TO1, TO2, ,TO3, and CC3 (CC1/CC4 are duplicates; CC2 is the our
     * account's email address)
     */
    public void testReplyAllAddresses3() throws Throwable {
        final MessageCompose a = getActivity();
        // Doesn't matter what Intent we use here
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        Message msg = new Message();
        final Account account = new Account();

        msg.mFrom = Address.parseAndPack(FROM);
        msg.mTo = Address.parseAndPack(TO1 + ',' + TO2 + ',' + TO3);
        msg.mCc = Address.parseAndPack(CC1 + ',' + CC2 + ',' + CC3 + ',' + CC4);
        final Message message = msg;
        account.mEmailAddress = "sEcOnD.cC@gOoGlE.cOm";

        runTestOnUiThread(new Runnable() {
            public void run() {
                a.initFromIntent(intent);
                a.setupAddressViews(message, account, true);
                String result = Address.parseAndPack(mToView.getText().toString());
                String expected = Address.parseAndPack(FROM);
                assertEquals(expected, result);
                result = Address.parseAndPack(mCcView.getText().toString());
                expected = Address.parseAndPack(TO1 + ',' + TO2 + ',' + TO3+ ',' + CC3);
                assertEquals(expected, result);
                TestUtils.assertViewVisible(mCcView);
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
                checkFields(RECIPIENT_TO + ", ", RECIPIENT_CC + ", ", RECIPIENT_BCC + ", ", SUBJECT,
                        null, mSignature);
                checkFocused(mMessageView);
            }
        });
    }

    /**
     * Test for processing of Intent EXTRA_* fields that impact the headers with utf-16.
     */
    public void testIntentHeaderExtrasWithUtf16() throws MessagingException, Throwable {

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtra(Intent.EXTRA_EMAIL, new String[] { UTF16_RECIPIENT_TO });
        intent.putExtra(Intent.EXTRA_CC, new String[] { UTF16_RECIPIENT_CC });
        intent.putExtra(Intent.EXTRA_BCC, new String[] { UTF16_RECIPIENT_BCC });
        intent.putExtra(Intent.EXTRA_SUBJECT, UTF16_SUBJECT);

        final MessageCompose a = getActivity();
        final Intent i2 = new Intent(intent);

        runTestOnUiThread(new Runnable() {
            public void run() {
                a.initFromIntent(i2);
                checkFields(UTF16_RECIPIENT_TO + ", ", UTF16_RECIPIENT_CC + ", ",
                        UTF16_RECIPIENT_BCC + ", ", UTF16_SUBJECT, null, mSignature);
                checkFocused(mMessageView);
            }
        });
    }

    /**
     * Test for processing of Intent EXTRA_* fields that impact the headers with utf-32.
     */
    public void testIntentHeaderExtrasWithUtf32() throws MessagingException, Throwable {

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.putExtra(Intent.EXTRA_EMAIL, new String[] { UTF32_RECIPIENT_TO });
        intent.putExtra(Intent.EXTRA_CC, new String[] { UTF32_RECIPIENT_CC });
        intent.putExtra(Intent.EXTRA_BCC, new String[] { UTF32_RECIPIENT_BCC });
        intent.putExtra(Intent.EXTRA_SUBJECT, UTF32_SUBJECT);

        final MessageCompose a = getActivity();
        final Intent i2 = new Intent(intent);

        runTestOnUiThread(new Runnable() {
            public void run() {
                a.initFromIntent(i2);
                checkFields(UTF32_RECIPIENT_TO + ", ", UTF32_RECIPIENT_CC + ", ",
                        UTF32_RECIPIENT_BCC + ", ", UTF32_SUBJECT, null, mSignature);
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
                checkFields(null, null, null, null, BODY, mSignature);
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
                checkFields(
                        RECIPIENT_TO + ", ", null, null, "This is the subject", null, mSignature);
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
     * @param signature expected value (null = it must be empty)
     */
    private void checkFields(String to, String cc, String bcc, String subject, String content,
            String signature) {
        String toText = mToView.getText().toString();
        if (to == null) {
            assertEquals(0, toText.length());
        } else {
            assertEquals(to, toText);
            TestUtils.assertViewVisible(mToView);
        }

        String ccText = mCcView.getText().toString();
        if (cc == null) {
            assertEquals(0, ccText.length());
        } else {
            assertEquals(cc, ccText);
            TestUtils.assertViewVisible(mCcView);
        }

        String bccText = mBccView.getText().toString();
        if (bcc == null) {
            assertEquals(0, bccText.length());
        } else {
            assertEquals(bcc, bccText);
            TestUtils.assertViewVisible(mBccView);
        }

        String subjectText = mSubjectView.getText().toString();
        if (subject == null) {
            assertEquals(0, subjectText.length());
        } else {
            assertEquals(subject, subjectText);
        }

        String contentText = mMessageView.getText().toString();
        if (content == null && signature == null) {
            assertEquals(0, contentText.length());
        } else {
            if (content == null) content = "";
            if (signature != null) {
                int textLength = content.length();
                if (textLength == 0 || content.charAt(textLength - 1) != '\n') {
                    content += "\n";
                }
                content += signature;
            }
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
    private Message buildTestMessage(String to, String sender, String subject, String content) {
        Message message = new Message();

        if (to != null) {
            message.mTo = Address.parseAndPack(to);
        }

        if (sender != null) {
            Address[] addresses = Address.parse(sender);
            assertTrue("from address", addresses.length > 0);
            message.mFrom = addresses[0].pack();
        }

        message.mSubject = subject;

        if (content != null) {
            message.mText = content;
        }

        return message;
    }

    /**
     * Check AddressTextView email address validation.
     */
    @UiThreadTest
    public void testAddressTextView() {
        MessageCompose messageCompose = getActivity();

        mToView.setValidator(new EmailAddressValidator());
        mToView.setText("foo");
        mToView.performValidation();

        // address is validated as errorneous
        assertFalse(messageCompose.isAddressAllValid());

        // the wrong address is preserved by validation
        assertEquals("foo", mToView.getText().toString());

        mToView.setText("a@b.c");
        mToView.performValidation();

        // address is validated as correct
        assertTrue(messageCompose.isAddressAllValid());

        mToView.setText("a@b.c, foo");
        mToView.performValidation();

        assertFalse(messageCompose.isAddressAllValid());
        assertEquals("a@b.c, foo", mToView.getText().toString());
    }

    /**
     * Check message and selection with/without signature.
     */
    public void testSetInitialComposeTextAndSelection() throws MessagingException, Throwable {
        final Message msg = buildTestMessage(null, null, null, BODY);
        final Intent intent = new Intent(ACTION_EDIT_DRAFT);
        final Account account = new Account();
        final MessageCompose a = getActivity();
        a.setIntent(intent);

        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                a.setInitialComposeText(BODY, SIGNATURE);
                checkFields(null, null, null, null, BODY, SIGNATURE);
                a.setMessageContentSelection(SIGNATURE);
                assertEquals(BODY.length(), mMessageView.getSelectionStart());
            }
        });

        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                a.setInitialComposeText(BODY, null);
                checkFields(null, null, null, null, BODY, null);
                a.setMessageContentSelection(null);
                assertEquals(BODY.length(), mMessageView.getSelectionStart());
            }
        });

        runTestOnUiThread(new Runnable() {
            public void run() {
                resetViews();
                final String body2 = BODY + "\n\na\n\n";
                a.setInitialComposeText(body2, SIGNATURE);
                checkFields(null, null, null, null, body2, SIGNATURE);
                a.setMessageContentSelection(SIGNATURE);
                assertEquals(BODY.length() + 3, mMessageView.getSelectionStart());
            }
        });

    }

    private static int sAttachmentId = 1;
    private Attachment makeAttachment(String filename) {
        Attachment a = new Attachment();
        a.mId = sAttachmentId++;
        a.mFileName = filename;
        return a;
    }

    @SmallTest
    public void testSourceAttachmentsProcessing() {
        // Attachments currently in the draft.
        ArrayList<Attachment> currentAttachments = Lists.newArrayList(
                makeAttachment("a.png"), makeAttachment("b.png"));

        // Attachments in the message being forwarded.
        Attachment c = makeAttachment("c.png");
        Attachment d = makeAttachment("d.png");
        ArrayList<Attachment> sourceAttachments = Lists.newArrayList(c, d);

        // Ensure the source attachments gets added.
        final MessageCompose a = getActivity();
        a.processSourceMessageAttachments(currentAttachments, sourceAttachments, true /*include*/);

        assertEquals(4, currentAttachments.size());
        assertTrue(currentAttachments.contains(c));
        assertTrue(currentAttachments.contains(d));

        // Now ensure they can be removed (e.g. in the case of switching from forward to reply).
        a.processSourceMessageAttachments(currentAttachments, sourceAttachments, false /*include*/);
        assertEquals(2, currentAttachments.size());
        assertFalse(currentAttachments.contains(c));
        assertFalse(currentAttachments.contains(d));
    }
}
