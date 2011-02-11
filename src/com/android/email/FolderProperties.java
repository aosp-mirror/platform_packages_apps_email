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

import com.android.emailcommon.provider.EmailContent.Mailbox;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;


// TODO When the UI is settled, cache all strings/drawables
// TODO When the UI is settled, write up tests
// TODO When the UI is settled, remove backward-compatibility methods
public class FolderProperties {

    private static FolderProperties sInstance;

    private final Context mContext;

    // Caches for frequently accessed resources.
    private final String[] mSpecialMailbox;
    private final TypedArray mSpecialMailboxDrawable;
    private final Drawable mSummaryStarredMailboxDrawable;
    private final Drawable mSummaryCombinedInboxDrawable;

    private FolderProperties(Context context) {
        mContext = context.getApplicationContext();
        mSpecialMailbox = context.getResources().getStringArray(R.array.mailbox_display_names);
        for (int i = 0; i < mSpecialMailbox.length; ++i) {
            if ("".equals(mSpecialMailbox[i])) {
                // there is no localized name, so use the display name from the server
                mSpecialMailbox[i] = null;
            }
        }
        mSpecialMailboxDrawable =
            context.getResources().obtainTypedArray(R.array.mailbox_display_icons);
        mSummaryStarredMailboxDrawable =
            context.getResources().getDrawable(R.drawable.ic_folder_star_holo_light);
        mSummaryCombinedInboxDrawable =
            context.getResources().getDrawable(R.drawable.ic_list_combined_inbox);
    }

    public static synchronized FolderProperties getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new FolderProperties(context);
        }
        return sInstance;
    }

    // For backward compatibility.
    public String getDisplayName(int type) {
        return getDisplayName(type, -1);
    }

    // For backward compatibility.
    public Drawable getSummaryMailboxIconIds(long id) {
        return getIcon(-1, id);
    }

    /**
     * Lookup names of localized special mailboxes
     */
    public String getDisplayName(int type, long mailboxId) {
        // Special combined mailboxes
        int resId = 0;

        // Can't use long for switch!?
        if (mailboxId == Mailbox.QUERY_ALL_INBOXES) {
            resId = R.string.account_folder_list_summary_inbox;
        } else if (mailboxId == Mailbox.QUERY_ALL_FAVORITES) {
            resId = R.string.account_folder_list_summary_starred;
        } else if (mailboxId == Mailbox.QUERY_ALL_DRAFTS) {
            resId = R.string.account_folder_list_summary_drafts;
        } else if (mailboxId == Mailbox.QUERY_ALL_OUTBOX) {
            resId = R.string.account_folder_list_summary_outbox;
        }
        if (resId != 0) {
            return mContext.getString(resId);
        }

        if (type < mSpecialMailbox.length) {
            return mSpecialMailbox[type];
        }
        return null;
    }

    /**
     * Lookup icons of special mailboxes
     */
    public Drawable getIcon(int type, long mailboxId) {
        if (mailboxId == Mailbox.QUERY_ALL_INBOXES) {
            return mSummaryCombinedInboxDrawable;
        } else if (mailboxId == Mailbox.QUERY_ALL_FAVORITES) {
            return mSummaryStarredMailboxDrawable;
        } else if (mailboxId == Mailbox.QUERY_ALL_DRAFTS) {
            return mSpecialMailboxDrawable.getDrawable(Mailbox.TYPE_DRAFTS);
        } else if (mailboxId == Mailbox.QUERY_ALL_OUTBOX) {
            return mSpecialMailboxDrawable.getDrawable(Mailbox.TYPE_OUTBOX);
        }
        if (0 <= type && type < mSpecialMailboxDrawable.length()) {
            final int resId = mSpecialMailboxDrawable.getResourceId(type, -1);
            if (resId != -1) {
                return mContext.getResources().getDrawable(resId);
            }
        }
        return null; // No icon
    }
}

