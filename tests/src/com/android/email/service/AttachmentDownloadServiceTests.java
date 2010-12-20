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
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.email.provider.EmailContent.Message;
import com.android.email.provider.ProviderTestUtils;
import com.android.email.service.AttachmentDownloadService.DownloadRequest;
import com.android.email.service.AttachmentDownloadService.DownloadSet;

import android.content.Context;

import java.io.File;
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
    private AttachmentDownloadService.AccountManagerStub mAccountManagerStub;
    private MockDirectory mMockDirectory;

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
        mAccountManagerStub = new AttachmentDownloadService.AccountManagerStub(null);
        mService.mAccountManagerStub = mAccountManagerStub;
        mDownloadSet = mService.mDownloadSet;
        mMockDirectory =
            new MockDirectory(mService.mContext.getCacheDir().getAbsolutePath());
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

    /**
     * A mock file directory containing a single (Mock)File.  The total space, usable space, and
     * length of the single file can be set
     */
    static class MockDirectory extends File {
        private static final long serialVersionUID = 1L;
        private long mTotalSpace;
        private long mUsableSpace;
        private MockFile[] mFiles;
        private final MockFile mMockFile = new MockFile();


        public MockDirectory(String path) {
            super(path);
            mFiles = new MockFile[1];
            mFiles[0] = mMockFile;
        }

        private void setTotalAndUsableSpace(long total, long usable) {
            mTotalSpace = total;
            mUsableSpace = usable;
        }

        public long getTotalSpace() {
            return mTotalSpace;
        }

        public long getUsableSpace() {
            return mUsableSpace;
        }

        public void setFileLength(long length) {
            mMockFile.mLength = length;
        }

        public File[] listFiles() {
            return mFiles;
        }
    }

    /**
     * A mock file that reports back a pre-set length
     */
    static class MockFile extends File {
        private static final long serialVersionUID = 1L;
        private long mLength = 0;

        public MockFile() {
            super("_mock");
        }

        public long length() {
            return mLength;
        }
    }

    public void testCanPrefetchForAccount() {
        // First, test our "global" limits (based on free storage)
        // Mock storage @ 100 total and 26 available
        // Note that all file lengths in this test are in arbitrary units
        mMockDirectory.setTotalAndUsableSpace(100L, 26L);
        // Mock 2 accounts in total
        mAccountManagerStub.setNumberOfAccounts(2);
        // With 26% available, we should be ok to prefetch
        assertTrue(mService.canPrefetchForAccount(1, mMockDirectory));
        // Now change to 24 available
        mMockDirectory.setTotalAndUsableSpace(100L, 24L);
        // With 24% available, we should NOT be ok to prefetch
        assertFalse(mService.canPrefetchForAccount(1, mMockDirectory));

        // Now, test per-account storage
        // Mock storage @ 100 total and 50 available
        mMockDirectory.setTotalAndUsableSpace(100L, 50L);
        // Mock a file of length 12, but need to uncache previous amount first
        mService.mAttachmentStorageMap.remove(1L);
        mMockDirectory.setFileLength(11);
        // We can prefetch since 11 < 50/4
        assertTrue(mService.canPrefetchForAccount(1, mMockDirectory));
        // Mock a file of length 13, but need to uncache previous amount first
        mService.mAttachmentStorageMap.remove(1L);
        mMockDirectory.setFileLength(13);
        // We can't prefetch since 13 > 50/4
        assertFalse(mService.canPrefetchForAccount(1, mMockDirectory));
    }
}
