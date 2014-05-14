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

package com.android.email.provider;

import com.android.emailcommon.internet.Rfc822Output;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Attachment;
import com.android.emailcommon.provider.EmailContent.Body;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.utility.Utility;

import android.content.ContentUris;
import android.content.Context;
import android.net.Uri;
import android.test.MoreAsserts;

import java.io.File;
import java.io.FileOutputStream;

import junit.framework.Assert;

public class ProviderTestUtils extends Assert {

    /**
     * No constructor - statics only
     */
    private ProviderTestUtils() {
    }

    /**
     * Create an account for test purposes
     */
    public static Account setupAccount(String name, boolean saveIt, Context context) {
        Account account = new Account();

        account.mDisplayName = name;
        account.mEmailAddress = name + "@android.com";
        account.mSyncKey = "sync-key-" + name;
        account.mSyncLookback = 1;
        account.mSyncInterval = Account.CHECK_INTERVAL_NEVER;
        account.mHostAuthKeyRecv = 0;
        account.mHostAuthKeySend = 0;
        account.mFlags = 4;
        account.mSenderName = name;
        account.mProtocolVersion = "2.5" + name;
        account.mPolicyKey = 0;
        account.mSecuritySyncKey = "sec-sync-key-" + name;
        account.mSignature = "signature-" + name;
        if (saveIt) {
            account.save(context);
        }
        return account;
    }

    /**
     * Lightweight way of deleting an account for testing.
     */
    public static void deleteAccount(Context context, long accountId) {
        context.getContentResolver().delete(ContentUris.withAppendedId(
                Account.CONTENT_URI, accountId), null, null);
    }

    /**
     * Create a hostauth record for test purposes
     */
    public static HostAuth setupHostAuth(String name, long accountId, boolean saveIt,
            Context context) {
        return setupHostAuth("protocol", name, saveIt, context);
    }

    /**
     * Create a hostauth record for test purposes
     */
    public static HostAuth setupHostAuth(String protocol, String name, boolean saveIt,
            Context context) {
        HostAuth hostAuth = new HostAuth();

        hostAuth.mProtocol = protocol;
        hostAuth.mAddress = "address-" + name;
        hostAuth.mPort = 100;
        hostAuth.mFlags = 200;
        hostAuth.mLogin = "login-" + name;
        hostAuth.mPassword = "password-" + name;
        hostAuth.mDomain = "domain-" + name;

        if (saveIt) {
            hostAuth.save(context);
        }
        return hostAuth;
    }

    /**
     * Create a mailbox for test purposes
     */
    public static Mailbox setupMailbox(String name, long accountId, boolean saveIt,
            Context context) {
        return setupMailbox(name, accountId, saveIt, context, Mailbox.TYPE_MAIL);
    }
    public static Mailbox setupMailbox(String name, long accountId, boolean saveIt,
            Context context, int type) {
        return setupMailbox(name, accountId, saveIt, context, type, '/');
    }
    public static Mailbox setupMailbox(String name, long accountId, boolean saveIt,
            Context context, int type, char delimiter) {
        Mailbox box = new Mailbox();

        int delimiterIndex = name.lastIndexOf(delimiter);
        String displayName = name;
        if (delimiterIndex > 0) {
            displayName = name.substring(delimiterIndex + 1);
        }
        box.mDisplayName = displayName;
        box.mServerId = name;
        box.mParentServerId = "parent-serverid-" + name;
        box.mParentKey = 4;
        box.mAccountKey = accountId;
        box.mType = type;
        box.mDelimiter = delimiter;
        box.mSyncKey = "sync-key-" + name;
        box.mSyncLookback = 2;
        box.mSyncInterval = Account.CHECK_INTERVAL_NEVER;
        box.mSyncTime = 3;
        box.mFlagVisible = true;
        box.mFlags = 5;

        if (saveIt) {
            box.save(context);
        }
        return box;
    }

    /**
     * Create a message for test purposes
     */
    public static Message setupMessage(String name, long accountId, long mailboxId,
            boolean addBody, boolean saveIt, Context context) {
        // Default starred, read,  (backword compatibility)
        return setupMessage(name, accountId, mailboxId, addBody, saveIt, context, true, true);
    }

    /**
     * Create a message for test purposes
     */
    public static Message setupMessage(String name, long accountId, long mailboxId,
            boolean addBody, boolean saveIt, Context context, boolean starred, boolean read) {
        Message message = new Message();

        message.mDisplayName = name;
        message.mTimeStamp = 100 + name.length();
        message.mSubject = "subject " + name;
        message.mFlagRead = read;
        message.mFlagSeen = read;
        message.mFlagLoaded = Message.FLAG_LOADED_UNLOADED;
        message.mFlagFavorite = starred;
        message.mFlagAttachment = true;
        message.mFlags = 0;

        message.mServerId = "serverid " + name;
        message.mServerTimeStamp = 300 + name.length();
        message.mMessageId = "messageid " + name;

        message.mMailboxKey = mailboxId;
        message.mAccountKey = accountId;

        message.mFrom = "from " + name;
        message.mTo = "to " + name;
        message.mCc = "cc " + name;
        message.mBcc = "bcc " + name;
        message.mReplyTo = "replyto " + name;

        message.mMeetingInfo = "123" + accountId + mailboxId + name.length();

        if (addBody) {
            message.mText = "body text " + name;
            message.mHtml = "body html " + name;
            message.mSourceKey = 400 + name.length();
        }

        if (saveIt) {
            message.save(context);
        }
        return message;
    }

    /**
     * Create a test body
     *
     * @param messageId the message this body belongs to
     * @param textContent the plain text for the body
     * @param htmlContent the html text for the body
     * @param saveIt if true, write the new attachment directly to the DB
     * @param context use this context
     */
    public static Body setupBody(long messageId, String textContent, String htmlContent,
            boolean saveIt, Context context) {
        Body body = new Body();
        body.mMessageKey = messageId;
        body.mHtmlContent = htmlContent;
        body.mTextContent = textContent;
        body.mSourceKey = messageId + 0x1000;
        if (saveIt) {
            body.save(context);
        }
        return body;
    }

    /**
     * Create a test attachment.  A few fields are specified by params, and all other fields
     * are generated using pseudo-unique values.
     *
     * @param messageId the message to attach to
     * @param fileName the "file" to indicate in the attachment
     * @param length the "length" of the attachment
     * @param flags the flags to set in the attachment
     * @param saveIt if true, write the new attachment directly to the DB
     * @param context use this context
     */
    public static Attachment setupAttachment(long messageId, String fileName, long length,
            int flags, boolean saveIt, Context context) {
        Attachment att = new Attachment();
        att.mSize = length;
        att.mFileName = fileName;
        att.mContentId = "contentId " + fileName;
        att.setContentUri("contentUri " + fileName);
        att.mMessageKey = messageId;
        att.mMimeType = "mimeType " + fileName;
        att.mLocation = "location " + fileName;
        att.mEncoding = "encoding " + fileName;
        att.mContent = "content " + fileName;
        att.mFlags = flags;
        att.mContentBytes = Utility.toUtf8("content " + fileName);
        att.mAccountKey = messageId + 0x1000;
        if (saveIt) {
            att.save(context);
        }
        return att;
    }

    /**
     * Create a test attachment with flags = 0 (see above)
     *
     * @param messageId the message to attach to
     * @param fileName the "file" to indicate in the attachment
     * @param length the "length" of the attachment
     * @param saveIt if true, write the new attachment directly to the DB
     * @param context use this context
     */
    public static Attachment setupAttachment(long messageId, String fileName, long length,
            boolean saveIt, Context context) {
        return setupAttachment(messageId, fileName, length, 0, saveIt, context);
    }

    private static void assertEmailContentEqual(String caller, EmailContent expect,
            EmailContent actual) {
        if (expect == actual) {
            return;
        }

        assertEquals(caller + " mId", expect.mId, actual.mId);
        assertEquals(caller + " mBaseUri", expect.mBaseUri, actual.mBaseUri);
    }

    /**
     * Compare two accounts for equality
     *
     * TODO: check host auth?
     */
    public static void assertAccountEqual(String caller, Account expect, Account actual) {
        if (expect == actual) {
            return;
        }

        assertEmailContentEqual(caller, expect, actual);
        assertEquals(caller + " mDisplayName", expect.mDisplayName, actual.mDisplayName);
        assertEquals(caller + " mEmailAddress", expect.mEmailAddress, actual.mEmailAddress);
        assertEquals(caller + " mSyncKey", expect.mSyncKey, actual.mSyncKey);

        assertEquals(caller + " mSyncLookback", expect.mSyncLookback, actual.mSyncLookback);
        assertEquals(caller + " mSyncInterval", expect.mSyncInterval, actual.mSyncInterval);
        assertEquals(caller + " mHostAuthKeyRecv", expect.mHostAuthKeyRecv,
                actual.mHostAuthKeyRecv);
        assertEquals(caller + " mHostAuthKeySend", expect.mHostAuthKeySend,
                actual.mHostAuthKeySend);
        assertEquals(caller + " mFlags", expect.mFlags, actual.mFlags);
        assertEquals(caller + " mSenderName", expect.mSenderName, actual.mSenderName);
        assertEquals(caller + " mProtocolVersion", expect.mProtocolVersion,
                actual.mProtocolVersion);
        assertEquals(caller + " mSecuritySyncKey", expect.mSecuritySyncKey,
                actual.mSecuritySyncKey);
        assertEquals(caller + " mSignature", expect.mSignature, actual.mSignature);
        assertEquals(caller + " mPolicyKey", expect.mPolicyKey, actual.mPolicyKey);
        assertEquals(caller + " mPingDuration", expect.mPingDuration, actual.mPingDuration);
    }

    /**
     * Compare two hostauth records for equality
     */
    public static void assertHostAuthEqual(String caller, HostAuth expect, HostAuth actual) {
        assertHostAuthEqual(caller, expect, actual, true);
    }

    public static void assertHostAuthEqual(String caller, HostAuth expect, HostAuth actual,
            boolean testEmailContent) {
        if (expect == actual) {
            return;
        }

        if (testEmailContent) {
            assertEmailContentEqual(caller, expect, actual);
        }
        assertEquals(caller + " mProtocol", expect.mProtocol, actual.mProtocol);
        assertEquals(caller + " mAddress", expect.mAddress, actual.mAddress);
        assertEquals(caller + " mPort", expect.mPort, actual.mPort);
        assertEquals(caller + " mFlags", expect.mFlags, actual.mFlags);
        assertEquals(caller + " mLogin", expect.mLogin, actual.mLogin);
        assertEquals(caller + " mPassword", expect.mPassword, actual.mPassword);
        assertEquals(caller + " mDomain", expect.mDomain, actual.mDomain);
        // This field is dead and is not checked
//      assertEquals(caller + " mAccountKey", expect.mAccountKey, actual.mAccountKey);
    }

    /**
     * Compare two mailboxes for equality
     */
    public static void assertMailboxEqual(String caller, Mailbox expect, Mailbox actual) {
        if (expect == actual) {
            return;
        }

        assertEmailContentEqual(caller, expect, actual);
        assertEquals(caller + " mDisplayName", expect.mDisplayName, actual.mDisplayName);
        assertEquals(caller + " mServerId", expect.mServerId, actual.mServerId);
        assertEquals(caller + " mParentServerId", expect.mParentServerId, actual.mParentServerId);
        assertEquals(caller + " mParentKey", expect.mParentKey, actual.mParentKey);
        assertEquals(caller + " mAccountKey", expect.mAccountKey, actual.mAccountKey);
        assertEquals(caller + " mType", expect.mType, actual.mType);
        assertEquals(caller + " mDelimiter", expect.mDelimiter, actual.mDelimiter);
        assertEquals(caller + " mSyncKey", expect.mSyncKey, actual.mSyncKey);
        assertEquals(caller + " mSyncLookback", expect.mSyncLookback, actual.mSyncLookback);
        assertEquals(caller + " mSyncInterval", expect.mSyncInterval, actual.mSyncInterval);
        assertEquals(caller + " mSyncTime", expect.mSyncTime, actual.mSyncTime);
        assertEquals(caller + " mFlagVisible", expect.mFlagVisible, actual.mFlagVisible);
        assertEquals(caller + " mSyncStatus", expect.mSyncStatus, actual.mSyncStatus);
        assertEquals(caller + " mLastTouchedTime", expect.mLastTouchedTime, actual.mLastTouchedTime);
        assertEquals(caller + " mUiSyncStatus", expect.mUiSyncStatus, actual.mUiSyncStatus);
        assertEquals(caller + " mUiLastSyncResult", expect.mUiLastSyncResult, actual.mUiLastSyncResult);
        assertEquals(caller + " mTotalCount", expect.mTotalCount, actual.mTotalCount);
        assertEquals(caller + " mHierarchicalName", expect.mHierarchicalName, actual.mHierarchicalName);
        assertEquals(caller + " mLastFullSyncTime", expect.mLastFullSyncTime, actual.mLastFullSyncTime);
    }

    /**
     * Compare two messages for equality
     *
     * TODO: body?
     * TODO: attachments?
     */
    public static void assertMessageEqual(String caller, Message expect, Message actual) {
        if (expect == actual) {
            return;
        }

        assertEmailContentEqual(caller, expect, actual);
        assertEquals(caller + " mDisplayName", expect.mDisplayName, actual.mDisplayName);
        assertEquals(caller + " mTimeStamp", expect.mTimeStamp, actual.mTimeStamp);
        assertEquals(caller + " mSubject", expect.mSubject, actual.mSubject);
        assertEquals(caller + " mFlagRead = false", expect.mFlagRead, actual.mFlagRead);
        assertEquals(caller + " mFlagRead = false", expect.mFlagSeen, actual.mFlagSeen);
        assertEquals(caller + " mFlagLoaded", expect.mFlagLoaded, actual.mFlagLoaded);
        assertEquals(caller + " mFlagFavorite", expect.mFlagFavorite, actual.mFlagFavorite);
        assertEquals(caller + " mFlagAttachment", expect.mFlagAttachment, actual.mFlagAttachment);
        assertEquals(caller + " mFlags", expect.mFlags, actual.mFlags);

        assertEquals(caller + " mServerId", expect.mServerId, actual.mServerId);
        assertEquals(caller + " mServerTimeStamp", expect.mServerTimeStamp,actual.mServerTimeStamp);
        assertEquals(caller + " mDraftInfo", expect.mDraftInfo,actual.mDraftInfo);
        assertEquals(caller + " mMessageId", expect.mMessageId, actual.mMessageId);

        assertEquals(caller + " mMailboxKey", expect.mMailboxKey, actual.mMailboxKey);
        assertEquals(caller + " mAccountKey", expect.mAccountKey, actual.mAccountKey);
        assertEquals(caller + " mMainMailboxKey", expect.mMainMailboxKey, actual.mMainMailboxKey);

        assertEquals(caller + " mFrom", expect.mFrom, actual.mFrom);
        assertEquals(caller + " mTo", expect.mTo, actual.mTo);
        assertEquals(caller + " mCc", expect.mCc, actual.mCc);
        assertEquals(caller + " mBcc", expect.mBcc, actual.mBcc);
        assertEquals(caller + " mReplyTo", expect.mReplyTo, actual.mReplyTo);

        assertEquals(caller + " mMeetingInfo", expect.mMeetingInfo, actual.mMeetingInfo);

        assertEquals(caller + " mSnippet", expect.mSnippet, actual.mSnippet);

        assertEquals(caller + " mProtocolSearchInfo", expect.mProtocolSearchInfo, actual.mProtocolSearchInfo);

        assertEquals(caller + " mThreadTopic", expect.mThreadTopic, actual.mThreadTopic);

        assertEquals(caller + " mSyncData", expect.mSyncData, actual.mSyncData);

        assertEquals(caller + " mSyncData", expect.mServerConversationId, actual.mServerConversationId);

        assertEquals(caller + " mText", expect.mText, actual.mText);
        assertEquals(caller + " mHtml", expect.mHtml, actual.mHtml);
        assertEquals(caller + " mSourceKey", expect.mSourceKey, actual.mSourceKey);
        assertEquals(caller + " mQuotedTextStartPos", expect.mQuotedTextStartPos, actual.mQuotedTextStartPos);
    }

    /**
     * Compare to attachments for equality
     *
     * TODO: file / content URI mapping?  Compare the actual files?
     */
    public static void assertAttachmentEqual(String caller, Attachment expect, Attachment actual) {
        if (expect == actual) {
            return;
        }

        assertEmailContentEqual(caller, expect, actual);
        assertEquals(caller + " mFileName", expect.mFileName, actual.mFileName);
        assertEquals(caller + " mMimeType", expect.mMimeType, actual.mMimeType);
        assertEquals(caller + " mSize", expect.mSize, actual.mSize);
        assertEquals(caller + " mContentId", expect.mContentId, actual.mContentId);
        assertEquals(caller + " mContentUri", expect.getContentUri(), actual.getContentUri());
        assertEquals(caller + " mCachedFileUri", expect.getCachedFileUri(), actual.getCachedFileUri());
        assertEquals(caller + " mMessageKey", expect.mMessageKey, actual.mMessageKey);
        assertEquals(caller + " mLocation", expect.mLocation, actual.mLocation);
        assertEquals(caller + " mEncoding", expect.mEncoding, actual.mEncoding);
        assertEquals(caller + " mContent", expect.mContent, actual.mContent);
        assertEquals(caller + " mFlags", expect.mFlags, actual.mFlags);
        MoreAsserts.assertEquals(caller + " mContentBytes",
                expect.mContentBytes, actual.mContentBytes);
        assertEquals(caller + " mAccountKey", expect.mAccountKey, actual.mAccountKey);
    }

    /**
     * Create a temporary EML file based on {@code msg} in the directory {@code directory}.
     */
    public static Uri createTempEmlFile(Context context, Message msg, File directory)
            throws Exception {
        // Write out the message in rfc822 format
        File outputFile = File.createTempFile("message", "tmp", directory);
        assertNotNull(outputFile);
        FileOutputStream outputStream = new FileOutputStream(outputFile);
        Rfc822Output.writeTo(context, msg, outputStream, true, false, null);
        outputStream.close();

        return Uri.fromFile(outputFile);
    }
}
