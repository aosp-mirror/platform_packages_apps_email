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

package com.android.email.activity;

import com.android.email.R;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.LoaderManager;
import android.content.DialogInterface;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;

/**
 * "Move (messages) to" dialog.
 *
 * TODO Make callback mechanism better  (don't use getActivity--use setTargetFragment instead.)
 * TODO Fix the text color in mailbox_list_item.xml.
 * TODO Don't show unread counts.
 */
public class MoveMessageToDialog extends DialogFragment implements DialogInterface.OnClickListener {
    private static final String BUNDLE_ACCOUNT_ID = "account_id";
    private static final String BUNDLE_MESSAGE_IDS = "message_ids";
    private long mAccountId;
    private MailboxesAdapter mAdapter;

    public interface Callback {
        public void onMoveToMailboxSelected(long newMailboxId, long[] messageIds);
    }

    public static MoveMessageToDialog newInstance(Activity parent, long accountId,
            long[] messageIds) {
        MoveMessageToDialog dialog = new MoveMessageToDialog();
        Bundle args = new Bundle();
        args.putLong(BUNDLE_ACCOUNT_ID, accountId);
        args.putLongArray(BUNDLE_MESSAGE_IDS, messageIds);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mAdapter = new MailboxesAdapter(getActivity().getApplicationContext());
        mAccountId = getArguments().getLong(BUNDLE_ACCOUNT_ID);
        setStyle(STYLE_NORMAL, android.R.style.Theme_Light_Holo);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Activity a = getActivity();
        final String title = a.getResources().getString(R.string.move_to_folder_dialog_title);

        a.getLoaderManager().initLoader(ActivityHelper.GLOBAL_LOADER_ID_MOVE_TO_DIALOG_LOADER,
                getArguments(), new MyLoaderCallbacks());

        return new AlertDialog.Builder(a)
                .setTitle(title)
                .setSingleChoiceItems(mAdapter, -1, this)
                .show();
    }

    @Override
    public void onClick(DialogInterface dialog, int position) {
        final long mailboxId = mAdapter.getItemId(position);
        final long[] massageIds = getArguments().getLongArray(BUNDLE_MESSAGE_IDS);

        // TODO Fix it. It's not flexible
        ((Callback) getActivity()).onMoveToMailboxSelected(mailboxId, massageIds);
        dismiss();
    }

    private class MyLoaderCallbacks implements LoaderManager.LoaderCallbacks<Cursor> {
        @Override
        public Loader<Cursor> onCreateLoader(int id, Bundle args) {
            return MailboxesAdapter.createLoader(getActivity().getApplicationContext(), mAccountId,
                    MailboxesAdapter.MODE_MOVE_TO_TARGET);
        }

        @Override
        public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
            mAdapter.changeCursor(data);
        }
    }
}
