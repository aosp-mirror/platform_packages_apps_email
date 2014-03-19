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

/**
 * The "security required" error dialog.  This is presented whenever an exchange account
 * reports that it will require security policy control, and provide the user with the
 * opportunity to accept or deny this.
 *
 * If the user clicks OK, calls onSecurityRequiredDialogResultOk(true) which reports back
 * to the target as if the settings check was "ok".  If the user clicks "cancel", calls
 * onSecurityRequiredDialogResultOk(false) which simply closes the checker (this is the
 * same as any other failed check.)
 */

public class SecurityRequiredDialogFragment extends DialogFragment {
    public final static String TAG = "SecurityRequiredDialog";

    // Bundle keys for arguments
    private final static String ARGS_HOST_NAME = "SecurityRequiredDialog.HostName";

    public interface Callback {

        /**
         * Callback for the result of this dialog fragment
         * @param ok True for OK pressed, false for cancel
         */
        void onSecurityRequiredDialogResult(boolean ok);
    }

    // Public no-args constructor needed for fragment re-instantiation
    public SecurityRequiredDialogFragment() {}

    public static SecurityRequiredDialogFragment newInstance(String hostName) {
        final SecurityRequiredDialogFragment fragment = new SecurityRequiredDialogFragment();
        final Bundle arguments = new Bundle(1);
        arguments.putString(ARGS_HOST_NAME, hostName);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Context context = getActivity();
        final Bundle arguments = getArguments();
        final String hostName = arguments.getString(ARGS_HOST_NAME);

        setCancelable(true);

        return new AlertDialog.Builder(context)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(context.getString(R.string.account_setup_security_required_title))
                .setMessage(context.getString(
                        R.string.account_setup_security_policies_required_fmt, hostName))
                .setPositiveButton(
                        context.getString(android.R.string.ok),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dismiss();
                                final Callback callback = (Callback) getActivity();
                                callback.onSecurityRequiredDialogResult(true);
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
        final Callback callback = (Callback) getActivity();
        if (callback != null) {
            callback.onSecurityRequiredDialogResult(false);
        }
    }
}
