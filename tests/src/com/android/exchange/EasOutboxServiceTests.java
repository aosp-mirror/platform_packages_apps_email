/*
 * Copyright (C) 2010 The Android Open Source Project.
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

package com.android.exchange;

import com.android.email.provider.EmailContent.Mailbox;

import android.content.Context;
import android.test.AndroidTestCase;

/**
 * You can run this entire test case with:
 *   runtest -c com.android.exchange.EasOutboxServiceTests email
 */

public class EasOutboxServiceTests extends AndroidTestCase {

    Context mMockContext;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getContext();
    }

    public void testGenerateSmartSendCmd() {
        EasOutboxService svc = new EasOutboxService(mMockContext, new Mailbox());
        // Test encoding of collection id; colon should be preserved
        String cmd = svc.generateSmartSendCmd(true, "1339085683659694034", "Mail:^f");
        assertEquals("SmartReply&ItemId=1339085683659694034&CollectionId=Mail:%5Ef", cmd);
        // Test encoding of item id
        cmd = svc.generateSmartSendCmd(false, "14:&3", "6");
        assertEquals("SmartForward&ItemId=14:%263&CollectionId=6", cmd);
    }
}
