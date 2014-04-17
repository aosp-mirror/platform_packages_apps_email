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
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;

import com.android.email.R;
import com.android.mail.utils.LogUtils;

/**
 * Simple dialog that shows progress as we work through the settings checks.
 * This is stateless except for its UI (e.g. current strings) and can be torn down or
 * recreated at any time without affecting the account checking progress.
 */
public class CheckSettingsProgressDialogFragment extends DialogFragment {
    public final static String TAG = "CheckProgressDialog";

    // Extras for saved instance state
    private final static String ARGS_PROGRESS_STRING = "CheckProgressDialog.Progress";
    private final static String ARGS_MODE_INT = "CheckProgressDialog.Mode";

    // UI
    private String mProgressString;

    public interface Callback {
        void onCheckSettingsProgressDialogCancel();
    }

    // Public no-args constructor needed for fragment re-instantiation
    public CheckSettingsProgressDialogFragment() {}

    /**
     * Create a dialog that reports progress
     * @param checkMode check settings mode
     */
    public static CheckSettingsProgressDialogFragment newInstance(int checkMode) {
        final CheckSettingsProgressDialogFragment f = new CheckSettingsProgressDialogFragment();
        final Bundle b = new Bundle(1);
        b.putInt(ARGS_MODE_INT, checkMode);
        f.setArguments(b);
        return f;
    }

    /**
     * Update the progress of an existing dialog
     * @param progress latest progress to be displayed
     */
    protected void updateProgress(int progress) {
        mProgressString = AccountCheckSettingsFragment.getProgressString(getActivity(), progress);
        final AlertDialog dialog = (AlertDialog) getDialog();
        if (dialog != null && mProgressString != null) {
            dialog.setMessage(mProgressString);
        }
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Context context = getActivity();
        if (savedInstanceState != null) {
            mProgressString = savedInstanceState.getString(ARGS_PROGRESS_STRING);
        }
        if (mProgressString == null) {
            final int checkMode = getArguments().getInt(ARGS_MODE_INT);
            final int progress = AccountCheckSettingsFragment.getProgressForMode(checkMode);
            mProgressString = AccountCheckSettingsFragment.getProgressString(getActivity(),
                    progress);
        }

        // Don't bail out if the user taps outside the progress window
        setCancelable(false);

        final ProgressDialog dialog = new ProgressDialog(context);
        dialog.setIndeterminate(true);
        dialog.setMessage(mProgressString);
        dialog.setButton(DialogInterface.BUTTON_NEGATIVE,
                context.getString(android.R.string.cancel),
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.cancel();
                    }
                });
        return dialog;
    }

    /**
     * Listen for cancellation, which can happen from places other than the
     * negative button (e.g. touching outside the dialog), and stop the checker
     */
    @Override
    public void onCancel(DialogInterface dialog) {
        super.onCancel(dialog);
        final Callback callback = (Callback) getActivity();
        if (callback != null) {
            callback.onCheckSettingsProgressDialogCancel();
        } else {
            LogUtils.d(LogUtils.TAG, "Null callback in CheckSettings dialog onCancel");
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(ARGS_PROGRESS_STRING, mProgressString);
    }
}
