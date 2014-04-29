/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.emailcommon.provider;

import android.content.Context;
import android.test.ProviderTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.test.suitebuilder.annotation.Suppress;

import com.android.email.provider.ContentCache;
import com.android.email.provider.EmailProvider;

/**
 * Unit tests for the QuickResponse class
 */
@Suppress
@SmallTest
public class QuickResponseTests extends ProviderTestCase2<EmailProvider> {
    private Context mMockContext;
    private EmailProvider mProvider;

    public QuickResponseTests() {
        super(EmailProvider.class, EmailContent.AUTHORITY);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mMockContext = getMockContext();
        mProvider = getProvider();
        // Invalidate all caches, since we reset the database for each test
        ContentCache.invalidateAllCaches();
    }
}

