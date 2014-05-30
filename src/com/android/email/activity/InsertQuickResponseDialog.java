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

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.LoaderManager;
import android.content.Context;
import android.content.CursorLoader;
import android.content.DialogInterface;
import android.content.Loader;
import android.database.Cursor;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;

import com.android.email.R;
import com.android.mail.providers.Account;
import com.android.mail.providers.UIProvider;

/**
 * Dialog which lists QuickResponses for the specified account. On user selection, will call
 * Callback.onQuickResponseSelected() with the selected QuickResponse text.
 */
public class InsertQuickResponseDialog extends DialogFragment {
    // Key for the Account object in the arguments bundle
    private static final String ACCOUNT_KEY = "account";

    /**
     * Callback interface for when user selects a QuickResponse.
     */
    public interface Callback {
        /**
         * Handles the text of the selected QuickResponse.
         */
        public void onQuickResponseSelected(CharSequence quickResponse);
    }

    // Public no-args constructor needed for fragment re-instantiation
    public InsertQuickResponseDialog() {}

    /**
     * Create and returns new dialog.
     *
     * @param callbackFragment fragment that implements {@link Callback}.  Or null, in which case
     * the parent activity must implement {@link Callback}.
     */
    public static InsertQuickResponseDialog
            newInstance(Fragment callbackFragment, Account account) {
        final InsertQuickResponseDialog dialog = new InsertQuickResponseDialog();

        // If a target is set, it MUST implement Callback. Fail-fast if not.
        if (callbackFragment != null) {
            if (!(callbackFragment instanceof Callback)) {
                throw new ClassCastException(callbackFragment.toString()
                        + " must implement Callback");
            }
            dialog.setTargetFragment(callbackFragment, 0);
        }

        Bundle args = new Bundle();
        args.putParcelable(ACCOUNT_KEY, account);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        // If target not set, the parent activity MUST implement Callback. Fail-fast if not.
        final Fragment targetFragment = getTargetFragment();
        if (targetFragment != null) {
            if (!(getActivity() instanceof Callback)) {
                throw new ClassCastException(getActivity().toString() + " must implement Callback");
            }
        }

        // Now that Callback implementation is verified, build the dialog
        final Context context = getActivity();

        final SimpleCursorAdapter adapter = new SimpleCursorAdapter(context,
                R.layout.quick_response_item, null,
                new String[] {UIProvider.QuickResponseColumns.TEXT},
                new int[] {R.id.quick_response_text}, 0);

        final AlertDialog.Builder builder = new AlertDialog.Builder(context);

        // inflate the view to show in the dialog
        final LayoutInflater li = LayoutInflater.from(builder.getContext());
        final View quickResponsesView = li.inflate(R.layout.quick_responses, null);

        // the view contains both a ListView and its associated empty view; wire them together
        final ListView listView = (ListView) quickResponsesView.findViewById(R.id.quick_responses);
        listView.setEmptyView(quickResponsesView.findViewById(R.id.quick_responses_empty_view));
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                final Cursor c = (Cursor) listView.getItemAtPosition(position);
                final String quickResponseText =
                        c.getString(c.getColumnIndex(UIProvider.QuickResponseColumns.TEXT));
                getCallback().onQuickResponseSelected(quickResponseText);
                dismiss();
            }
        });

        final Account account = getArguments().getParcelable(ACCOUNT_KEY);

        getLoaderManager().initLoader(0, null, new LoaderManager.LoaderCallbacks<Cursor>() {
            @Override
            public Loader<Cursor> onCreateLoader(int id, Bundle args) {
                return new CursorLoader(context, account.quickResponseUri,
                        UIProvider.QUICK_RESPONSE_PROJECTION, null, null, null);
            }

            @Override
            public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
                adapter.swapCursor(data);
            }

            @Override
            public void onLoaderReset(Loader<Cursor> loader) {
                adapter.swapCursor(null);
            }
        });

        final String dialogTitle = getResources()
                .getString(R.string.message_compose_insert_quick_response_list_title);

        return builder
                .setTitle(dialogTitle)
                .setView(quickResponsesView)
                .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                })
                .create();
    }

    private Callback getCallback() {
        Fragment targetFragment = getTargetFragment();
        if (targetFragment != null) {
            return (Callback) targetFragment;
        }
        return (Callback) getActivity();
    }
}
