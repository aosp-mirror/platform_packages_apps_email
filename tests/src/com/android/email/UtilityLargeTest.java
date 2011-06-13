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

package com.android.email;

import com.android.email.provider.ProviderTestUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.utility.Utility;

import android.content.Context;
import android.test.InstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Large tests for {@link Utility}.
 */
@LargeTest
public class UtilityLargeTest extends InstrumentationTestCase {
    private static final int WAIT_UNTIL_TIMEOUT_SECONDS = 10;

    // Isolted Context for providers.
    private Context mProviderContext;


    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mProviderContext = DBTestHelper.ProviderContextSetupHelper.getProviderContext(
                getInstrumentation().getTargetContext());
    }


    public void testForEachAccount() throws Throwable {
        // Create some accounts...
        Account acct1 = ProviderTestUtils.setupAccount("acct1", true, mProviderContext);
        Account acct2 = ProviderTestUtils.setupAccount("acct2", true, mProviderContext);

        final ArrayList<Long> ids = new ArrayList<Long>(); // Account id array.
        final AtomicBoolean done = new AtomicBoolean(false);

        // Kick ForEachAccount and collect IDs...
        // AsyncTask needs to be created on the UI thread.
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                new Utility.ForEachAccount(mProviderContext) {
                    @Override
                    protected void performAction(long accountId) {
                        synchronized (ids) {
                            ids.add(accountId);
                        }
                    }

                    @Override
                    protected void onFinished() {
                        done.set(true);
                    }
                }.execute();
            }
        });

        // Wait until it's done...
        TestUtils.waitUntil(new TestUtils.Condition() {
            @Override
            public boolean isMet() {
                return done.get();
            }
        }, WAIT_UNTIL_TIMEOUT_SECONDS);

        // Check the collected IDs.
        synchronized (ids) {
            assertEquals(2, ids.size());
            // ids may not be sorted, so...
            assertTrue(ids.contains(acct1.mId));
            assertTrue(ids.contains(acct2.mId));
        }
    }
}
