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

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.Resources;
import android.os.Bundle;

/**
 * Confirmation dialog for deleting messages.
 */
public class DeleteMessageConfirmationDialog extends DialogFragment
        implements DialogInterface.OnClickListener {
    private static final String COUNT_MESSAGES_ARG = "count_messages";

    public interface Callback {
        public void onDeleteMessageConfirmationDialogOkPressed();
    }

    /**
     * Create a new dialog.
     *
     * @param countMessage the number of messages to be deleted
     * @param callbackFragment fragment that implements {@link Callback}.  Or null, in which case
     * the parent activity must implement {@link Callback}.
     */
    public static DeleteMessageConfirmationDialog newInstance(int countMessage,
            Fragment callbackFragment) {
        final DeleteMessageConfirmationDialog dialog = new DeleteMessageConfirmationDialog();
        final Bundle args = new Bundle();
        args.putInt(COUNT_MESSAGES_ARG, countMessage);
        dialog.setArguments(args);
        if (callbackFragment != null) {
            dialog.setTargetFragment(callbackFragment, 0);
        }
        return dialog;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final int countMessages = getArguments().getInt(COUNT_MESSAGES_ARG);

        final Context context = getActivity();
        final Resources res = context.getResources();
        final AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setTitle(res.getString(R.string.message_delete_dialog_title))
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setMessage(res.getQuantityString(R.plurals.message_delete_confirm, countMessages))
                .setPositiveButton(R.string.okay_action, this)
                .setNegativeButton(R.string.cancel_action, null);
        return b.create();
    }

    @Override
    public void onClick(DialogInterface dialog, int which) {
        switch (which) {
            case DialogInterface.BUTTON_POSITIVE:
                getCallback().onDeleteMessageConfirmationDialogOkPressed();
                break;
        }
    }

    private Callback getCallback() {
        Fragment targetFragment = getTargetFragment();
        if (targetFragment != null) {
            // If a target is set, it MUST implement Callback.
            return (Callback) targetFragment;
        }
        // If not the parent activity MUST implement Callback.
        return (Callback) getActivity();
    }
}
