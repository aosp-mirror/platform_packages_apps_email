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

import com.android.exchange.adapter.Tags;

import android.test.AndroidTestCase;

import java.util.HashMap;

public class TagsTests extends AndroidTestCase {

    // Make sure there are no duplicates in the tags table
    // This test is no longer required - tags can be duplicated
    public void disable_testNoDuplicates() {
        String[][] allTags = Tags.pages;
        HashMap<String, Boolean> map = new HashMap<String, Boolean>();
        for (String[] page: allTags) {
            for (String tag: page) {
                assertTrue(tag, !map.containsKey(tag));
                map.put(tag, true);
            }
        }
    }
}
