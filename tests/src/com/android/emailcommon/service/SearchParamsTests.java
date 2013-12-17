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

package com.android.emailcommon.service;

import android.os.Parcel;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.Suppress;

@Suppress
public class SearchParamsTests extends AndroidTestCase {
    public void brokentestParcel() {
        SearchParams params = new SearchParams(1, "query");
        params.mIncludeChildren = true;
        params.mLimit = 66;
        params.mOffset = 99;
        Parcel parcel = Parcel.obtain();
        params.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        SearchParams readParams = SearchParams.CREATOR.createFromParcel(parcel);
        assertEquals(params.mFilter, readParams.mFilter);
        assertEquals(params.mIncludeChildren, readParams.mIncludeChildren);
        assertEquals(params.mLimit, readParams.mLimit);
        assertEquals(params.mOffset, readParams.mOffset);
        assertEquals(params.mMailboxId, readParams.mMailboxId);
    }
}
