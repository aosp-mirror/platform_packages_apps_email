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

import com.android.email.Email;
import com.android.email.data.ThrottlingCursorLoader;
import com.android.emailcommon.Logging;
import com.android.emailcommon.provider.EmailContent;
import com.android.emailcommon.provider.EmailContent.Mailbox;
import com.android.emailcommon.utility.Utility;

import android.content.Context;
import android.content.Loader;
import android.database.Cursor;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * Cursor adapter for the "move to mailbox" dialog.
 */
/*package*/ class MailboxMoveToAdapter extends MailboxesAdapter {
    private static final String MAILBOX_SELECTION_MOVE_TO_FOLDER =
        MAILBOX_SELECTION + " AND " + Mailbox.MOVE_TO_TARGET_MAILBOX_SELECTION;

    public MailboxMoveToAdapter(Context context, Callback callback) {
        super(context, callback);
    }

    @Override
    public void bindView(View view, Context context, Cursor cursor) {
        TextView t = (TextView) view;
        t.setText(getDisplayName(context, cursor));
    }

    @Override
    public View newView(Context context, Cursor cursor, ViewGroup parent) {
        return mInflater.inflate(android.R.layout.simple_list_item_1, parent, false);
    }

    public static Loader<Cursor> createLoader(Context context, long accountId) {
        if (Email.DEBUG_LIFECYCLE && Email.DEBUG) {
            Log.d(Logging.LOG_TAG, "MailboxDialogAdapter#createLoader accountId=" + accountId);
        }
        return new MailboxMoveToLoader(context, accountId);
    }

    /**
     * Loader for the "move to mailbox" dialog.
     */
    private static class MailboxMoveToLoader extends ThrottlingCursorLoader {
        public MailboxMoveToLoader(Context context, long accountId) {
            super(context, EmailContent.Mailbox.CONTENT_URI,
                    MailboxesAdapter.PROJECTION, MAILBOX_SELECTION_MOVE_TO_FOLDER,
                    new String[] { String.valueOf(accountId) }, MAILBOX_ORDER_BY);
        }

        @Override
        public void onContentChanged() {
            if (sEnableUpdate) {
                super.onContentChanged();
            }
        }

        @Override
        public Cursor loadInBackground() {
            final Cursor mailboxesCursor = super.loadInBackground();
            return Utility.CloseTraceCursorWrapper.get(mailboxesCursor);
        }
    }
}
