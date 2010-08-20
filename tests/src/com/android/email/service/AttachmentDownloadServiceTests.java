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

package com.android.email.service;

import com.android.email.AccountTestCase;
import com.android.email.ExchangeUtils.NullEmailService;
import com.android.email.provider.ProviderTestUtils;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.service.AttachmentDownloadService.DownloadRequest;
import com.android.email.service.AttachmentDownloadService.DownloadSet;

import android.content.Context;

import java.util.Iterator;

/**
 * Tests of the AttachmentDownloadService
 *
 * You can run this entire test case with:
 *   runtest -c com.android.email.service.AttachmentDownloadServiceTests email
 */
public class AttachmentDownloadServiceTests extends AccountTestCase {
    private AttachmentDownloadService mService;
    private Context mMockContext;
    private Account mAccount;
    private Mailbox mMailbox;
    private long mAccountId;
    private long mMailboxId;
    private DownloadSet mDownloadSet;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();

        // Set up an account and mailbox
        mAccount = ProviderTestUtils.setupAccount("account", false, mMockContext);
        mAccount.save(mMockContext);
        mAccountId = mAccount.mId;
        mMailbox = ProviderTestUtils.setupMailbox("mailbox", mAccountId, true, mMockContext);
        mMailboxId = mMailbox.mId;

        // Set up our download service to simulate a running environment
        // Use the NullEmailService so that the loadAttachment calls become no-ops
        mService = new AttachmentDownloadService();
        mService.mContext = mMockContext;
        mService.addServiceClass(mAccountId, NullEmailService.class);
        mDownloadSet = mService.mDownloadSet;
    }

    @Override
    public void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * This test creates attachments and places them in the DownloadSet; we then do various checks
     * that exercise its functionality.
     */
    public void testDownloadSet() {
        // TODO: Make sure that this doesn't interfere with the "real" ADS that might be running
        // on device
        Message message = ProviderTestUtils.setupMessage("message", mAccountId, mMailboxId, false,
                true, mMockContext);
        Attachment att1 = ProviderTestUtils.setupAttachment(message.mId, "filename1", 1000,
                Attachment.FLAG_DOWNLOAD_USER_REQUEST, true, mMockContext);
        Attachment att2 = ProviderTestUtils.setupAttachment(message.mId, "filename2", 1000,
                Attachment.FLAG_DOWNLOAD_FORWARD, true, mMockContext);
        Attachment att3 = ProviderTestUtils.setupAttachment(message.mId, "filename3", 1000,
                Attachment.FLAG_DOWNLOAD_FORWARD, true, mMockContext);
        Attachment att4 = ProviderTestUtils.setupAttachment(message.mId, "filename4", 1000,
                Attachment.FLAG_DOWNLOAD_USER_REQUEST, true, mMockContext);
        // Indicate that these attachments have changed; they will be added to the queue
        mDownloadSet.onChange(att1);
        mDownloadSet.onChange(att2);
        mDownloadSet.onChange(att3);
        mDownloadSet.onChange(att4);
        Iterator<DownloadRequest> iterator = mDownloadSet.descendingIterator();
        // Check the expected ordering; 1 & 4 are higher priority than 2 & 3
        // 1 and 3 were created earlier than their priority equals
        long[] expectedAttachmentIds = new long[] {att1.mId, att4.mId, att2.mId, att3.mId};
        for (int i = 0; i < expectedAttachmentIds.length; i++) {
            assertTrue(iterator.hasNext());
            DownloadRequest req = iterator.next();
            assertEquals(expectedAttachmentIds[i], req.attachmentId);
        }

        // Process the queue; attachment 1 should be marked "in progress", and should be in
        // the in-progress map
        mDownloadSet.processQueue();
        DownloadRequest req = mDownloadSet.findDownloadRequest(att1.mId);
        assertNotNull(req);
        assertTrue(req.inProgress);
        assertTrue(mDownloadSet.mDownloadsInProgress.containsKey(att1.mId));
        // There should also be only one download in progress (testing the per-account limitation)
        assertEquals(1, mDownloadSet.mDownloadsInProgress.size());
        // End the "download" with a connection error; we should still have this in the queue,
        // but it should no longer be in-progress
        mDownloadSet.endDownload(att1.mId, EmailServiceStatus.CONNECTION_ERROR);
        assertFalse(req.inProgress);
        assertEquals(0, mDownloadSet.mDownloadsInProgress.size());

        mDownloadSet.processQueue();
        // Things should be as they were earlier; att1 should be an in-progress download
        req = mDownloadSet.findDownloadRequest(att1.mId);
        assertNotNull(req);
        assertTrue(req.inProgress);
        assertTrue(mDownloadSet.mDownloadsInProgress.containsKey(att1.mId));
        // Successfully download the attachment; there should be no downloads in progress, and
        // att1 should no longer be in the queue
        mDownloadSet.endDownload(att1.mId, EmailServiceStatus.SUCCESS);
        assertEquals(0, mDownloadSet.mDownloadsInProgress.size());
        assertNull(mDownloadSet.findDownloadRequest(att1.mId));

        // Test dequeue and isQueued
        assertEquals(3, mDownloadSet.size());
        mService.dequeue(att2.mId);
        assertEquals(2, mDownloadSet.size());
        assertTrue(mService.isQueued(att4.mId));
        assertTrue(mService.isQueued(att3.mId));

        mDownloadSet.processQueue();
        // att4 should be the download in progress
        req = mDownloadSet.findDownloadRequest(att4.mId);
        assertNotNull(req);
        assertTrue(req.inProgress);
        assertTrue(mDownloadSet.mDownloadsInProgress.containsKey(att4.mId));
    }
}
