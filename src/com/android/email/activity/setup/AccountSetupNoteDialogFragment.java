/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;

import com.android.email.R;

public class AccountSetupNoteDialogFragment extends DialogFragment {
    public static final String TAG = "NoteDialogFragment";

    // Argument bundle keys
    private static final String BUNDLE_KEY_NOTE = "NoteDialogFragment.Note";

    public static interface Callback {
        void onNoteDialogComplete();
        void onNoteDialogCancel();
    }

    // Public no-args constructor needed for fragment re-instantiation
    public AccountSetupNoteDialogFragment() {}

    /**
     * Create the dialog with parameters
     */
    public static AccountSetupNoteDialogFragment newInstance(String note) {
        final AccountSetupNoteDialogFragment f = new AccountSetupNoteDialogFragment();
        final Bundle b = new Bundle(1);
        b.putString(BUNDLE_KEY_NOTE, note);
        f.setArguments(b);
        return f;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Context context = getActivity();
        final String note = getArguments().getString(BUNDLE_KEY_NOTE);

        setCancelable(true);

        return new AlertDialog.Builder(context)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(android.R.string.dialog_alert_title)
                .setMessage(note)
                .setPositiveButton(
                        android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                final Callback a = (Callback) getActivity();
                                a.onNoteDialogComplete();
                                dismiss();
                            }
                        })
                .setNegativeButton(
                        context.getString(android.R.string.cancel),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.cancel();
                            }
                        })
                .create();
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        super.onCancel(dialog);
        final Callback a = (Callback) getActivity();
        a.onNoteDialogCancel();
    }
}
