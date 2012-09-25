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
import com.android.emailcommon.provider.QuickResponse;
import com.android.emailcommon.provider.EmailContent.QuickResponseColumns;
import com.android.emailcommon.utility.EmailAsyncTask;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.widget.EditText;

/**
 * Dialog to edit the text of a given or new quick response
 */
public class EditQuickResponseDialog extends DialogFragment
        implements DialogInterface.OnClickListener, TextWatcher {
    private EditText mQuickResponseEditText;
    private QuickResponse mQuickResponse;
    private AlertDialog mDialog;

    private static final String QUICK_RESPONSE_EDITED_STRING = "quick_response_edited_string";
    private static final String QUICK_RESPONSE = "quick_response";

    /**
     * Creates a new dialog to edit an existing QuickResponse or create a new
     * one.
     *
     * @param quickResponse - The QuickResponse fwhich the user is editing;
     *        null if user is creating a new QuickResponse.
     * @param accountId - The accountId for the account which holds this QuickResponse
     */
    public static EditQuickResponseDialog newInstance(
            QuickResponse quickResponse, long accountId) {
        final EditQuickResponseDialog dialog = new EditQuickResponseDialog();

        Bundle args = new Bundle();
        args.putLong("accountId", accountId);
        if (quickResponse != null) {
            args.putParcelable(QUICK_RESPONSE, quickResponse);
        }

        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Context context = getActivity();
        mQuickResponse = (QuickResponse) getArguments().getParcelable(QUICK_RESPONSE);

        mQuickResponseEditText = new EditText(context);
        if (savedInstanceState != null) {
            String quickResponseSavedString =
                    savedInstanceState.getString(QUICK_RESPONSE_EDITED_STRING);
            if (quickResponseSavedString != null) {
                mQuickResponseEditText.setText(quickResponseSavedString);
            }
        } else if (mQuickResponse != null) {
            mQuickResponseEditText.setText(mQuickResponse.toString());
        }
        mQuickResponseEditText.setSelection(mQuickResponseEditText.length());
        mQuickResponseEditText.addTextChangedListener(this);

        final AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setTitle(getResources().getString(R.string.edit_quick_response_dialog))
                .setView(mQuickResponseEditText)
                .setNegativeButton(R.string.cancel_action, this)
                .setPositiveButton(R.string.save_action, this);
        mDialog = b.create();
        return mDialog;
    }

    @Override
    public void onResume() {
        super.onResume();
        mDialog.getWindow()
                .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        if (mQuickResponseEditText.length() == 0) {
            mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        }
    }

    // implements TextWatcher
    @Override
    public void afterTextChanged(Editable s) {
            mDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(s.length() > 0);
    }

    // implements TextWatcher
    @Override
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

    // implements TextWatcher
    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {}

    // Saves contents during orientation change
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(
                QUICK_RESPONSE_EDITED_STRING, mQuickResponseEditText.getText().toString());
    }

    /**
     * Implements DialogInterface.OnClickListener
     */
    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_NEGATIVE:
                dialog.cancel();
                break;
            case DialogInterface.BUTTON_POSITIVE:
                final long accountId = getArguments().getLong("accountId");
                final String text = mQuickResponseEditText.getText().toString();
                final Context context = getActivity();
                if (mQuickResponse == null) {
                    mQuickResponse = new QuickResponse(accountId, text);
                }

                // Insert the new QuickResponse into the database. Content watchers used to
                // update the ListView of QuickResponses upon insertion.
                EmailAsyncTask.runAsyncParallel(new Runnable() {
                    @Override
                    public void run() {
                        if (!mQuickResponse.isSaved()) {
                            mQuickResponse.save(context);
                        } else {
                            ContentValues values = new ContentValues();
                            values.put(QuickResponseColumns.TEXT, text);
                            mQuickResponse.update(context, values);
                        }
                    }

                });
                break;
        }
    }
}
