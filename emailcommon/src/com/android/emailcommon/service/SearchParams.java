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
import android.os.Parcelable;

import com.android.emailcommon.provider.Mailbox;
import com.google.common.base.Objects;

public class SearchParams implements Parcelable {
    public static final long ALL_MAILBOXES = Mailbox.NO_MAILBOX;

    private static final int DEFAULT_LIMIT = 10; // Need input on what this number should be
    private static final int DEFAULT_OFFSET = 0;

    // The id of the mailbox to be searched; if -1, all mailboxes MUST be searched
    public final long mMailboxId;
    // If true, all subfolders of the specified mailbox MUST be searched
    public boolean mIncludeChildren = true;
    // The search terms (the search MUST only select messages whose contents include all of the
    // search terms in the query)
    public final String mFilter;
    // The maximum number of results to be created by this search
    public int mLimit = DEFAULT_LIMIT;
    // If zero, specifies a "new" search; otherwise, asks for a continuation of the previous
    // query(ies) starting with the mOffset'th match (0 based)
    public int mOffset = DEFAULT_OFFSET;

    /**
     * Error codes returned by the searchMessages API
     */
    public static class SearchParamsError {
        public static final int CANT_SEARCH_ALL_MAILBOXES = -1;
        public static final int CANT_SEARCH_CHILDREN = -2;
    }

    public SearchParams(long mailboxId, String filter) {
        mMailboxId = mailboxId;
        mFilter = filter;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if ((o == null) || !(o instanceof SearchParams)) {
            return false;
        }

        SearchParams os = (SearchParams) o;
        return mMailboxId == os.mMailboxId
                && mIncludeChildren == os.mIncludeChildren
                && mFilter.equals(os.mFilter)
                && mLimit == os.mLimit
                && mOffset == os.mOffset;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mMailboxId, mFilter, mOffset);
    }

    @Override
    public String toString() {
        return "[SearchParams " + mMailboxId + ":" + mFilter + " (" + mOffset + ", " + mLimit + "]";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Supports Parcelable
     */
    public static final Parcelable.Creator<SearchParams> CREATOR
        = new Parcelable.Creator<SearchParams>() {
        @Override
        public SearchParams createFromParcel(Parcel in) {
            return new SearchParams(in);
        }

        @Override
        public SearchParams[] newArray(int size) {
            return new SearchParams[size];
        }
    };

    /**
     * Supports Parcelable
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mMailboxId);
        dest.writeInt(mIncludeChildren ? 1 : 0);
        dest.writeString(mFilter);
        dest.writeInt(mLimit);
        dest.writeInt(mOffset);
    }

    /**
     * Supports Parcelable
     */
    public SearchParams(Parcel in) {
        mMailboxId = in.readLong();
        mIncludeChildren = in.readInt() == 1;
        mFilter = in.readString();
        mLimit = in.readInt();
        mOffset = in.readInt();
    }
}
