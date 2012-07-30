/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.imap2;

import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.service.SearchParams;
import com.android.emailsync.Request;

/**
 * SearchRequest is the wrapper for server search requests.
 */
public class SearchRequest extends Request {
    public final SearchParams mParams;

    public SearchRequest(SearchParams _params) {
        super(Message.NO_MESSAGE);
        mParams = _params;
    }

    // SearchRequests are unique by their mailboxId
    public boolean equals(Object o) {
        if (!(o instanceof SearchRequest)) return false;
        return ((SearchRequest)o).mParams.mMailboxId == mParams.mMailboxId;
    }

    public int hashCode() {
        return (int)mParams.mMailboxId;
    }
}
