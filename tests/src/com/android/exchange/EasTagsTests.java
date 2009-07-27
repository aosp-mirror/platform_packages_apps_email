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

package com.android.exchange;

import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.exchange.adapter.EasEmailSyncAdapter;
import com.android.exchange.adapter.EasTags;
import com.android.exchange.adapter.EasEmailSyncAdapter.EasEmailSyncParser;

import android.test.AndroidTestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

public class EasTagsTests extends AndroidTestCase {

    // Make sure there are no duplicates in the tags table
    public void testNoDuplicates() {
        String[][] allTags = EasTags.pages;
        HashMap<String, Boolean> map = new HashMap<String, Boolean>();
        for (String[] page: allTags) {
            for (String tag: page) {
                assertTrue(!map.containsKey(tag));
                map.put(tag, true);
            }
        }
    }
}
