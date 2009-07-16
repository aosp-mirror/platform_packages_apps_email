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

import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;

import android.content.Context;

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
        account.mSyncFrequency = EmailContent.Account.CHECK_INTERVAL_NEVER;
        account.mHostAuthKeyRecv = 2;
        account.mHostAuthKeySend = 3;
        account.mFlags = 4;
        account.mIsDefault = true;
        account.mCompatibilityUuid = "test-uid-" + name;
        account.mSenderName = name;
        account.mRingtoneUri = "content://ringtone-" + name;

        if (saveIt) {
            account.saveOrUpdate(context);
        }
        return account;
    }

    /**
     * Create a mailbox for test purposes
     */
    public static Mailbox setupMailbox(String name, long accountId, boolean saveIt,
            Context context) {
        Mailbox box = new Mailbox();

        box.mDisplayName = name;
        box.mServerId = "serverid-" + name;
        box.mParentServerId = "parent-serverid-" + name;
        box.mAccountKey = accountId;
        box.mType = Mailbox.TYPE_MAIL;
        box.mDelimiter = 1;
        box.mSyncKey = "sync-key-" + name;
        box.mSyncLookback = 2;
        box.mSyncFrequency = EmailContent.Account.CHECK_INTERVAL_NEVER;
        box.mSyncTime = 3;
        box.mUnreadCount = 4;
        box.mFlagVisible = true;
        box.mFlags = 5;
        box.mVisibleLimit = 6;

        if (saveIt) {
            box.saveOrUpdate(context);
        }
        return box;
    }

    /**
     * Create a message for test purposes
     * 
     * TODO: body
     * TODO: attachments
     */
    public static Message setupMessage(String name, long accountId, long mailboxId,
            boolean addBody, boolean saveIt, Context context) {
        Message message = new Message();

        message.mDisplayName = name;
        message.mTimeStamp = 1;
        message.mSubject = "subject " + name;
        message.mPreview = "preview " + name;
        message.mFlagRead = true;
        message.mFlagLoaded = Message.NOT_LOADED;
        message.mFlagFavorite = true;
        message.mFlagAttachment = true;
        message.mFlags = 2;

        message.mTextInfo = "textinfo " + name;
        message.mHtmlInfo = "htmlinfo " + name;

        message.mServerId = "serverid " + name;
        message.mServerIntId = 0;
        message.mClientId = "clientid " + name;
        message.mMessageId = "messageid " + name;
        message.mThreadId = "threadid " + name;

        message.mMailboxKey = mailboxId;
        message.mAccountKey = accountId;
        message.mReferenceKey = 4;

        message.mSender = "sender " + name;
        message.mFrom = "from " + name;
        message.mTo = "to " + name;
        message.mCc = "cc " + name;
        message.mBcc = "bcc " + name;
        message.mReplyTo = "replyto " + name;

        message.mServerVersion = "serverversion " + name;

        if (addBody) {
            message.mText = "body text " + name;
            message.mHtml = "body html " + name;
        }

        if (saveIt) {
            message.saveOrUpdate(context);
        }
        return message;
    }

    /**
     * Create a test attachment.  A few fields are specified by params, and all other fields
     * are generated using pseudo-unique values.
     * 
     * @param messageId the message to attach to
     * @param fileName the "file" to indicate in the attachment
     * @param length the "length" of the attachment
     * @param saveIt if true, write the new attachment directly to the DB
     * @param context use this context
     */
    public static Attachment setupAttachment(long messageId, String fileName, long length,
            boolean saveIt, Context context) {
        Attachment att = new Attachment();
        att.mSize = length;
        att.mFileName = fileName;
        att.mContentId = "contentId " + fileName;
        att.mContentUri = "contentUri " + fileName;
        att.mMessageKey = messageId;
        att.mMimeType = "mimeType " + fileName;
        att.mLocation = "location " + fileName;
        att.mEncoding = "encoding " + fileName;
        if (saveIt) {
            att.saveOrUpdate(context);
        }
        return att;
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

        assertEquals(caller + " mDisplayName", expect.mDisplayName, actual.mDisplayName);
        assertEquals(caller + " mEmailAddress", expect.mEmailAddress, actual.mEmailAddress);
        assertEquals(caller + " mSyncKey", expect.mSyncKey, actual.mSyncKey);

        assertEquals(caller + " mSyncLookback", expect.mSyncLookback, actual.mSyncLookback);
        assertEquals(caller + " mSyncFrequency", expect.mSyncFrequency, actual.mSyncFrequency);
        assertEquals(caller + " mHostAuthKeyRecv", expect.mHostAuthKeyRecv,
                actual.mHostAuthKeyRecv);
        assertEquals(caller + " mHostAuthKeySend", expect.mHostAuthKeySend,
                actual.mHostAuthKeySend);
        assertEquals(caller + " mFlags", expect.mFlags, actual.mFlags);
        assertEquals(caller + " mIsDefault", expect.mIsDefault, actual.mIsDefault);
        assertEquals(caller + " mCompatibilityUuid", expect.mCompatibilityUuid,
                actual.mCompatibilityUuid);
        assertEquals(caller + " mSenderName", expect.mSenderName, actual.mSenderName);
        assertEquals(caller + " mRingtoneUri", expect.mRingtoneUri, actual.mRingtoneUri);
    }

    /**
     * Compare two mailboxes for equality
     */
    public static void assertMailboxEqual(String caller, Mailbox expect, Mailbox actual) {
        if (expect == actual) {
            return;
        }

        assertEquals(caller + " mDisplayName", expect.mDisplayName, actual.mDisplayName);
        assertEquals(caller + " mServerId", expect.mServerId, actual.mServerId);
        assertEquals(caller + " mParentServerId", expect.mParentServerId, actual.mParentServerId);
        assertEquals(caller + " mAccountKey", expect.mAccountKey, actual.mAccountKey);
        assertEquals(caller + " mType", expect.mType, actual.mType);
        assertEquals(caller + " mDelimiter", expect.mDelimiter, actual.mDelimiter);
        assertEquals(caller + " mSyncKey", expect.mSyncKey, actual.mSyncKey);
        assertEquals(caller + " mSyncLookback", expect.mSyncLookback, actual.mSyncLookback);
        assertEquals(caller + " mSyncFrequency", expect.mSyncFrequency, actual.mSyncFrequency);
        assertEquals(caller + " mSyncTime", expect.mSyncTime, actual.mSyncTime);
        assertEquals(caller + " mUnreadCount", expect.mUnreadCount, actual.mUnreadCount);
        assertEquals(caller + " mFlagVisible", expect.mFlagVisible, actual.mFlagVisible);
        assertEquals(caller + " mFlags", expect.mFlags, actual.mFlags);
        assertEquals(caller + " mVisibleLimit", expect.mVisibleLimit, actual.mVisibleLimit);
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

        assertEquals(caller + " mDisplayName", expect.mDisplayName, actual.mDisplayName);
        assertEquals(caller + " mTimeStamp", expect.mTimeStamp, actual.mTimeStamp);
        assertEquals(caller + " mSubject", expect.mSubject, actual.mSubject);
        assertEquals(caller + " mPreview", expect.mPreview, actual.mPreview);
        assertEquals(caller + " mFlagRead = false", expect.mFlagRead, actual.mFlagRead);
        assertEquals(caller + " mFlagLoaded", expect.mFlagLoaded, actual.mFlagLoaded);
        assertEquals(caller + " mFlagFavorite", expect.mFlagFavorite, actual.mFlagFavorite);
        assertEquals(caller + " mFlagAttachment", expect.mFlagAttachment, actual.mFlagAttachment);
        assertEquals(caller + " mFlags", expect.mFlags, actual.mFlags);

        assertEquals(caller + " mTextInfo", expect.mTextInfo, actual.mTextInfo);
        assertEquals(caller + " mHtmlInfo", expect.mHtmlInfo, actual.mHtmlInfo);

        assertEquals(caller + " mServerId", expect.mServerId, actual.mServerId);
        assertEquals(caller + " mServerIntId", expect.mServerIntId, actual.mServerIntId);
        assertEquals(caller + " mClientId", expect.mClientId, actual.mClientId);
        assertEquals(caller + " mMessageId", expect.mMessageId, actual.mMessageId);
        assertEquals(caller + " mThreadId", expect.mThreadId, actual.mThreadId);

        assertEquals(caller + " mMailboxKey", expect.mMailboxKey, actual.mMailboxKey);
        assertEquals(caller + " mAccountKey", expect.mAccountKey, actual.mAccountKey);
        assertEquals(caller + " mReferenceKey", expect.mReferenceKey, actual.mReferenceKey);

        assertEquals(caller + " mSender", expect.mSender, actual.mSender);
        assertEquals(caller + " mFrom", expect.mFrom, actual.mFrom);
        assertEquals(caller + " mTo", expect.mTo, actual.mTo);
        assertEquals(caller + " mCc", expect.mCc, actual.mCc);
        assertEquals(caller + " mBcc", expect.mBcc, actual.mBcc);
        assertEquals(caller + " mReplyTo", expect.mReplyTo, actual.mReplyTo);

        assertEquals(caller + " mServerVersion", expect.mServerVersion, actual.mServerVersion);

        assertEquals(caller + " mText", expect.mText, actual.mText);
        assertEquals(caller + " mHtml", expect.mHtml, actual.mHtml);
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

        assertEquals(caller + " mSize", expect.mSize, actual.mSize);
        assertEquals(caller + " mFileName", expect.mFileName, actual.mFileName);
        assertEquals(caller + " mContentId", expect.mContentId, actual.mContentId);
        assertEquals(caller + " mContentUri", expect.mContentUri, actual.mContentUri);
        assertEquals(caller + " mMessageKey", expect.mMessageKey, actual.mMessageKey);
        assertEquals(caller + " mMimeType", expect.mMimeType, actual.mMimeType);
        assertEquals(caller + " mLocation", expect.mLocation, actual.mLocation);
        assertEquals(caller + " mEncoding", expect.mEncoding, actual.mEncoding);
    }
}
