/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.android.email.activity.setup;

import com.android.email.R;
import com.android.mail.providers.UIProvider;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;

/**
 * Dialog to edit the text of a given or new quick response
 */
public class EditQuickResponseDialog extends DialogFragment {
    private EditText mQuickResponseEditText;
    private AlertDialog mDialog;

    private static final String QUICK_RESPONSE_STRING = "quick_response_edited_string";
    private static final String QUICK_RESPONSE_CONTENT_URI = "quick_response_content_uri";
    private static final String QUICK_RESPONSE_CREATE = "quick_response_create";

    // Public no-args constructor needed for fragment re-instantiation
    public EditQuickResponseDialog() {}

    /**
     * Creates a new dialog to edit an existing QuickResponse or create a new
     * one.
     *
     * @param baseUri - The content Uri QuickResponse which the user is editing
     *                or the content Uri for creating a new QuickResponse
     * @param create - True if this is a new QuickResponse
     */
    public static EditQuickResponseDialog newInstance(String text,
            Uri baseUri, boolean create) {
        final EditQuickResponseDialog dialog = new EditQuickResponseDialog();

        Bundle args = new Bundle(4);
        args.putString(QUICK_RESPONSE_STRING, text);
        args.putParcelable(QUICK_RESPONSE_CONTENT_URI, baseUri);
        args.putBoolean(QUICK_RESPONSE_CREATE, create);

        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Uri uri = getArguments().getParcelable(QUICK_RESPONSE_CONTENT_URI);
        final boolean create = getArguments().getBoolean(QUICK_RESPONSE_CREATE);

        String quickResponseSavedString = null;
        if (savedInstanceState != null) {
            quickResponseSavedString =
                    savedInstanceState.getString(QUICK_RESPONSE_STRING);
        }
        if (quickResponseSavedString == null) {
            quickResponseSavedString = getArguments().getString(QUICK_RESPONSE_STRING);
        }

        final View wrapper = LayoutInflater.from(getActivity())
                .inflate(R.layout.quick_response_edit_dialog, null);
        mQuickResponseEditText = (EditText) wrapper.findViewById(R.id.quick_response_text);

        if (quickResponseSavedString != null) {
            mQuickResponseEditText.setText(quickResponseSavedString);
        }

        mQuickResponseEditText.setSelection(mQuickResponseEditText.length());
        mQuickResponseEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(s.length() > 0);
            }
        });

        DialogInterface.OnClickListener saveClickListener =
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        final String text = mQuickResponseEditText.getText().toString();
                        final ContentValues values = new ContentValues(1);
                        values.put(UIProvider.QuickResponseColumns.TEXT, text);

                        if (create) {
                            getActivity().getContentResolver().insert(uri, values);
                        } else {
                            getActivity().getContentResolver().update(uri, values, null, null);
                        }
                    }
                };
        DialogInterface.OnClickListener deleteClickListener =
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        getActivity().getContentResolver().delete(uri, null, null);
                    }
                };

        final AlertDialog.Builder b = new AlertDialog.Builder(getActivity());
        b.setTitle(getResources().getString(R.string.edit_quick_response_dialog))
                .setView(wrapper)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save_action, saveClickListener);
        if (!create) {
            b.setNeutralButton(R.string.delete, deleteClickListener);
        }
        mDialog = b.create();
        return mDialog;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mQuickResponseEditText.length() == 0) {
            mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        }
    }

    // Saves contents during orientation change
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(
                QUICK_RESPONSE_STRING, mQuickResponseEditText.getText().toString());
    }
}
