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

package com.android.email.activity;

import com.google.common.annotations.VisibleForTesting;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import com.android.email.Clock;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.emailcommon.utility.EmailAsyncTask;

import java.util.ArrayList;

/**
 * Manages recent data for mailboxes.
 */
public class RecentMailboxManager {
    @VisibleForTesting
    static Clock sClock = Clock.INSTANCE;

    /** The maximum number of results to retrieve */
    private static final int LIMIT_RESULTS = 5;
    /** Query to find the top most recent mailboxes */
    private static final String RECENT_SELECTION =
            MailboxColumns.ID + " IN " +
            "( SELECT " + MailboxColumns.ID
            + " FROM " + Mailbox.TABLE_NAME
            + " WHERE ( " + MailboxColumns.ACCOUNT_KEY + "=? "
            +     " AND " + MailboxColumns.LAST_TOUCHED_TIME + ">0 )"
            + " ORDER BY " + MailboxColumns.LAST_TOUCHED_TIME + " DESC"
            + " LIMIT ? )";
    /** Similar query to {@link #RECENT_SELECTION}, except, exclude all but user mailboxes */
    private static final String RECENT_SELECTION_WITH_EXCLUSIONS =
            RECENT_SELECTION + " AND " + MailboxColumns.TYPE + "=" + Mailbox.TYPE_MAIL;
    private final Context mContext;
    private static RecentMailboxManager sInstance;

    public static synchronized RecentMailboxManager getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new RecentMailboxManager(context);
        }
        return sInstance;
    }

    /** Hide constructor */
    private RecentMailboxManager(Context context) {
        mContext = context;
    }

    /** Updates the specified mailbox's touch time. Returns an async task for test only. */
    public EmailAsyncTask<Void, Void, Void> touch(long mailboxId) {
        return fireAndForget(mailboxId, sClock.getTime());
    }

    /**
     * Gets the most recently touched mailboxes for the specified account.
     * <p><em>WARNING</em>: This method blocks on the database.
     * @param accountId The ID of the account to load the recent list.
     * @param withExclusions If {@code false}, all mailboxes are eligible for the recent list.
     *          Otherwise, only user defined mailboxes are eligible for the recent list.
     */
    public ArrayList<Long> getMostRecent(long accountId, boolean withExclusions) {
        String selection = withExclusions ? RECENT_SELECTION_WITH_EXCLUSIONS : RECENT_SELECTION;
        ArrayList<Long> returnList = new ArrayList<Long>();
        Cursor cursor = mContext.getContentResolver().query(Mailbox.CONTENT_URI,
            EmailContent.ID_PROJECTION,
            selection,
            new String[] { Long.toString(accountId), Integer.toString(LIMIT_RESULTS) },
            MailboxColumns.DISPLAY_NAME);
        try {
            while (cursor.moveToNext()) {
                returnList.add(cursor.getLong(EmailContent.ID_PROJECTION_COLUMN));
            }
        } finally {
            cursor.close();
        }
        return returnList;
    }

    /** Updates the last touched time for the mailbox in the background */
    private EmailAsyncTask<Void, Void, Void> fireAndForget(final long mailboxId, final long time) {
        return EmailAsyncTask.runAsyncParallel(new Runnable() {
            @Override
            public void run() {
                ContentValues values = new ContentValues();
                values.put(MailboxColumns.LAST_TOUCHED_TIME, time);
                mContext.getContentResolver().update(Mailbox.CONTENT_URI, values,
                    EmailContent.ID_SELECTION,
                    new String[] { Long.toString(mailboxId) });
            }
        });
    }
}
