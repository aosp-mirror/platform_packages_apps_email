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

package com.android.email.activity.setup;

import com.android.email.R;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;

/**
 * Dialog fragment to show "duplicate account" dialog
 */
public class DuplicateAccountDialogFragment extends DialogFragment {
    public final static String TAG = "DuplicateAccountDialogFragment";

    // Argument bundle keys
    private final static String BUNDLE_KEY_ACCOUNT_NAME = "NoteDialogFragment.AccountName";

    /**
     * Create the dialog with parameters
     */
    public static DuplicateAccountDialogFragment newInstance(String note) {
        DuplicateAccountDialogFragment f = new DuplicateAccountDialogFragment();
        Bundle b = new Bundle();
        b.putString(BUNDLE_KEY_ACCOUNT_NAME, note);
        f.setArguments(b);
        return f;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Context context = getActivity();
        String accountName = getArguments().getString(BUNDLE_KEY_ACCOUNT_NAME);

        return new AlertDialog.Builder(context)
            .setIconAttribute(android.R.attr.alertDialogIcon)
            .setTitle(R.string.account_duplicate_dlg_title)
            .setMessage(context.getString(
                    R.string.account_duplicate_dlg_message_fmt, accountName))
            .setPositiveButton(
                    R.string.okay_action,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dismiss();
                        }
                    })
            .create();
    }
}
