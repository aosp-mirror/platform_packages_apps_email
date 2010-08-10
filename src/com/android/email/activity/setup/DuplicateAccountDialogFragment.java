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
 *
 * NOTE:  There is some duplication in the DialogFragments, because this area of
 * the framework is going to get some new features (to better handle callbacks to the "owner"
 * of the dialog) and I'll wait for that before I combine the duplicate code.
 *
 * TODO: Since there is no callback, the parent fragment id is unused here.
 */
public class DuplicateAccountDialogFragment extends DialogFragment {
    public final static String TAG = "DuplicateAccountDialogFragment";
    private String mAccountName;
    private int mParentFragmentId;

    // Note: Linkage back to parent fragment is TBD, due to upcoming framework changes
    // Until then, we'll implement in each dialog
    private final static String BUNDLE_KEY_PARENT_ID = "NoteDialogFragment.ParentId";
    private final static String BUNDLE_KEY_ACCOUNT_NAME = "NoteDialogFragment.AccountName";

    /**
     * This is required because the non-default constructor hides it, preventing auto-creation
     */
    public DuplicateAccountDialogFragment() {
        super();
    }

    /**
     * Create the dialog with parameters
     */
    public DuplicateAccountDialogFragment(String note, int parentFragmentId) {
        super();
        mAccountName = note;
        mParentFragmentId = parentFragmentId;
    }

    /**
     * If created automatically (e.g. after orientation change) restore parameters
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mParentFragmentId = savedInstanceState.getInt(BUNDLE_KEY_PARENT_ID);
            mAccountName = savedInstanceState.getString(BUNDLE_KEY_ACCOUNT_NAME);
        }
    }

    /**
     * Save parameters to support auto-recreation
     */
    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(BUNDLE_KEY_PARENT_ID, mParentFragmentId);
        outState.putString(BUNDLE_KEY_ACCOUNT_NAME, mAccountName);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Context context = getActivity();
        return new AlertDialog.Builder(context)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(R.string.account_duplicate_dlg_title)
            .setMessage(context.getString(
                    R.string.account_duplicate_dlg_message_fmt, mAccountName))
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
