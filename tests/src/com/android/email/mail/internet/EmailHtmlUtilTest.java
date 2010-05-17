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

import com.android.email.Email;
import com.android.email.mail.Message;
import com.android.email.mail.MessageTestUtils;
import com.android.email.mail.MessagingException;
import com.android.email.mail.MessageTestUtils.MessageBuilder;
import com.android.email.mail.MessageTestUtils.MultipartBuilder;
import com.android.email.mail.MessageTestUtils.TextBuilder;
import com.android.email.mail.store.LocalStore;
import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;

import android.content.ContentUris;
import android.content.Context;
import android.net.Uri;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

import java.io.IOException;

/**
 * Tests of the Email HTML utils.
 * 
 * You can run this entire test case with:
 *   runtest -c com.android.email.mail.internet.EmailHtmlUtilTest email
 */
@MediumTest
public class EmailHtmlUtilTest extends AndroidTestCase {
    private EmailContent.Account mAccount;
    private long mCreatedAccountId = -1;

    private static final String textTags = "<b>Plain</b> &";
    private static final String textSpaces = "3 spaces   end.";
    private static final String textNewlines = "ab \r\n  \n   \n\r\n";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        // Force assignment of a default account, and retrieve it
        Context context = getContext();
        Email.setTempDirectory(context);

        // Force assignment of a default account
        long accountId = Account.getDefaultAccountId(context);
        if (accountId == -1) {
            Account account = new Account();
            account.mSenderName = "Bob Sender";
            account.mEmailAddress = "bob@sender.com";
            account.save(context);
            accountId = account.mId;
            mCreatedAccountId = accountId;
        }
        Account.restoreAccountWithId(context, accountId);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        Context context = getContext();
        // If we created an account, delete it here
        if (mCreatedAccountId > -1) {
            context.getContentResolver().delete(
                    ContentUris.withAppendedId(Account.CONTENT_URI, mCreatedAccountId), null, null);
        }
    }

    /**
     * Tests for resolving inline image src cid: reference to content uri.
     * 
     * TODO: These need to be completely rewritten to not use LocalStore messages.
     */
    public void disable_testResolveInlineImage() throws MessagingException, IOException {
        final LocalStore store = (LocalStore) LocalStore.newInstance(
                mAccount.getLocalStoreUri(getContext()), mContext, null);
        // Single cid case.
        final String cid1 = "cid.1@android.com";
        final long aid1 = 10;
        final Uri uri1 = MessageTestUtils.contentUri(aid1, mAccount);
        final String text1     = new TextBuilder("text1 > ").addCidImg(cid1).build(" <.");
        final String expected1 = new TextBuilder("text1 > ").addUidImg(uri1).build(" <.");
        
        // message with cid1
        final Message msg1 = new MessageBuilder()
            .setBody(new MultipartBuilder("multipart/related")
                .addBodyPart(MessageTestUtils.textPart("text/html", text1))
                .addBodyPart(MessageTestUtils.imagePart("image/jpeg", "<"+cid1+">", aid1, store))
                .build())
            .build();
        // Simple case.
        final String actual1 = EmailHtmlUtil.resolveInlineImage(
                getContext().getContentResolver(), mAccount.mId, text1, msg1, 0);
        assertEquals("one content id reference is not resolved",
                    expected1, actual1);

        // Exceed recursive limit.
        final String actual0 = EmailHtmlUtil.resolveInlineImage(
                getContext().getContentResolver(), mAccount.mId, text1, msg1, 10);
        assertEquals("recursive call limit may exceeded",
                    text1, actual0);

        // Multiple cids case.
        final String cid2 = "cid.2@android.com";
        final long aid2 = 20;
        final Uri uri2 = MessageTestUtils.contentUri(aid2, mAccount);
        final String text2     = new TextBuilder("text2 ").addCidImg(cid2).build(".");
        final String expected2 = new TextBuilder("text2 ").addUidImg(uri2).build(".");
        
        // message with only cid2
        final Message msg2 = new MessageBuilder()
            .setBody(new MultipartBuilder("multipart/related")
                .addBodyPart(MessageTestUtils.textPart("text/html", text1 + text2))
                .addBodyPart(MessageTestUtils.imagePart("image/gif", cid2, aid2, store))
                .build())
            .build();
        // cid1 is not replaced
        final String actual2 = EmailHtmlUtil.resolveInlineImage(
                getContext().getContentResolver(), mAccount.mId, text1 + text2, msg2, 0);
        assertEquals("only one of two content id is resolved",
                text1 + expected2, actual2);

        // message with cid1 and cid2
        final Message msg3 = new MessageBuilder()
            .setBody(new MultipartBuilder("multipart/related")
                .addBodyPart(MessageTestUtils.textPart("text/html", text2 + text1))
                .addBodyPart(MessageTestUtils.imagePart("image/jpeg", cid1, aid1, store))
                .addBodyPart(MessageTestUtils.imagePart("image/gif", cid2, aid2, store))
                .build())
            .build();
        // cid1 and cid2 are replaced
        final String actual3 = EmailHtmlUtil.resolveInlineImage(
                getContext().getContentResolver(), mAccount.mId, text2 + text1, msg3, 0);
        assertEquals("two content ids are resolved correctly",
                expected2 + expected1, actual3);

        // message with many cids and normal attachments
        final Message msg4 = new MessageBuilder()
            .setBody(new MultipartBuilder("multipart/mixed")
                .addBodyPart(MessageTestUtils.imagePart("image/jpeg", null, 30, store))
                .addBodyPart(MessageTestUtils.imagePart("application/pdf", cid1, aid1, store))
                .addBodyPart(new MultipartBuilder("multipart/related")
                    .addBodyPart(MessageTestUtils.textPart("text/html", text2 + text1))
                    .addBodyPart(MessageTestUtils.imagePart("image/jpg", cid1, aid1, store))
                    .addBodyPart(MessageTestUtils.imagePart("image/gif", cid2, aid2, store))
                    .buildBodyPart())
                .addBodyPart(MessageTestUtils.imagePart("application/pdf", cid2, aid2, store))
                .build())
            .build();
        // cid1 and cid2 are replaced
        final String actual4 = EmailHtmlUtil.resolveInlineImage(
                getContext().getContentResolver(), mAccount.mId, text2 + text1, msg4, 0);
        assertEquals("two content ids in deep multipart level are resolved",
                expected2 + expected1, actual4);

        // No crash on null text
        final String actual5 = EmailHtmlUtil.resolveInlineImage(getContext().getContentResolver(),
                                                                mAccount.mId, null, msg4, 0);
        assertNull(actual5);
    }

    /**
     * Test for escapeCharacterToDisplay in plain text mode.
     */
    public void testEscapeCharacterToDisplayPlainText() {
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
