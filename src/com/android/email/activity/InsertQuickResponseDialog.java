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

import com.android.email.R;
import com.android.email.activity.setup.
        AccountSettingsEditQuickResponsesFragment.QuickResponseFinder;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.utility.EmailAsyncTask;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ListView;

/**
 * Dialog which lists QuickResponses for the specified account. On user selection, will call
 * Callback.onQuickResponseSelected() with the selected QuickResponse text.
 */
public class InsertQuickResponseDialog extends DialogFragment
        implements DialogInterface.OnClickListener, OnItemClickListener {
    private ListView mQuickResponsesView;
    private EmailAsyncTask.Tracker mTaskTracker;

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
        final Callback callback;
        if (callbackFragment != null) {
            try {
                callback = (Callback) callbackFragment;
            } catch (ClassCastException e) {
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
            final Callback callback;
            try {
                callback = (Callback) getActivity();
            } catch (ClassCastException e) {
                throw new ClassCastException(getActivity().toString() + " must implement Callback");
            }
        }

        // Now that Callback implementation is verified, build the dialog
        final Context context = getActivity();
        final AlertDialog.Builder b = new AlertDialog.Builder(context);

        mQuickResponsesView = new ListView(context);

        Account account = (Account) getArguments().getParcelable(ACCOUNT_KEY);
        mTaskTracker = new EmailAsyncTask.Tracker();
        new QuickResponseFinder(mTaskTracker, account.mId, mQuickResponsesView,
                context, null, this, false).executeParallel();

        b.setTitle(getResources()
                .getString(R.string.message_compose_insert_quick_response_list_title))
                .setView(mQuickResponsesView)
                .setNegativeButton(R.string.cancel_action, this);
        return b.create();
    }

    @Override
    public void onDestroy() {
        mTaskTracker.cancellAllInterrupt();
        super.onDestroy();
    }

    /**
     * Implements OnItemClickListener.
     */
    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        getCallback().onQuickResponseSelected(
                mQuickResponsesView.getItemAtPosition(position).toString());
        dismiss();
    }

    /**
     * Implements DialogInterface.OnClickListener
     */
    @Override
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON_NEGATIVE) {
            dialog.cancel();
        }
    }

    private Callback getCallback() {
        Fragment targetFragment = getTargetFragment();
        if (targetFragment != null) {
            return (Callback) targetFragment;
        }
        return (Callback) getActivity();
    }
}
