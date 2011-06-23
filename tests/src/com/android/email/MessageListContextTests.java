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

package com.android.email;

import android.os.Parcel;
import android.test.AndroidTestCase;

import com.android.emailcommon.service.SearchParams;

public class MessageListContextTests extends AndroidTestCase {

    public void testParcellingMailboxes() {
        long accountId = 123;
        long mailboxId = 456;
        MessageListContext original = MessageListContext.forMailbox(accountId, mailboxId);
        Parcel parcel = Parcel.obtain();

        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        MessageListContext read = MessageListContext.CREATOR.createFromParcel(parcel);
        assertEquals(original, read);
        parcel.recycle();
    }

    public void testParcellingSearches() {
        long accountId = 123;
        long mailboxId = 456;
        SearchParams params = new SearchParams(mailboxId, "search terms");
        MessageListContext original = MessageListContext.forSearch(accountId, mailboxId, params);
        Parcel parcel = Parcel.obtain();

        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        MessageListContext read = MessageListContext.CREATOR.createFromParcel(parcel);
        assertEquals(original, read);
        parcel.recycle();
    }
}
