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
import android.text.TextUtils;

import com.android.email.R;
import com.android.emailcommon.mail.MessagingException;
import com.android.mail.utils.LogUtils;

public class CheckSettingsErrorDialogFragment extends DialogFragment{
    public final static String TAG = "CheckSettingsErrorDialog";

    public final static int REASON_OTHER = 0;
    public final static int REASON_AUTHENTICATION_FAILED = 1;
    public final static int REASON_CERTIFICATE_REQUIRED = 2;

    // Bundle keys for arguments
    private final static String ARGS_MESSAGE = "CheckSettingsErrorDialog.Message";
    private final static String ARGS_REASON = "CheckSettingsErrorDialog.ExceptionId";

    public interface Callback {
        /**
         * Called to indicate the user wants to resolve the error by changing the client certificate
         */
        void onCheckSettingsErrorDialogEditCertificate();

        /**
         * Called to indicate the user wants to resolve the error by editing the server settings
         */
        void onCheckSettingsErrorDialogEditSettings();
    }

    public CheckSettingsErrorDialogFragment() {}

    /**
     * @param reason see REASON_* constants
     * @param message from {@link #getErrorString(Context, MessagingException)}
     * @return new instance
     */
    public static CheckSettingsErrorDialogFragment newInstance(int reason, String message) {
        final CheckSettingsErrorDialogFragment fragment = new CheckSettingsErrorDialogFragment();
        final Bundle arguments = new Bundle(2);
        arguments.putString(ARGS_MESSAGE, message);
        arguments.putInt(ARGS_REASON, reason);
        fragment.setArguments(arguments);
        return fragment;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Context context = getActivity();
        final Bundle arguments = getArguments();
        final String message = arguments.getString(ARGS_MESSAGE);
        final int reason = arguments.getInt(ARGS_REASON);

        setCancelable(true);

        final AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setMessage(message);

        // Use a different title when we get
        // MessagingException.AUTODISCOVER_AUTHENTICATION_FAILED
        if (reason == REASON_AUTHENTICATION_FAILED) {
            builder.setTitle(R.string.account_setup_autodiscover_dlg_authfail_title);
        } else {
            builder.setIconAttribute(android.R.attr.alertDialogIcon)
                    .setTitle(context.getString(R.string.account_setup_failed_dlg_title));
        }

        if (reason == REASON_CERTIFICATE_REQUIRED) {
            // Certificate error - show two buttons so the host fragment can auto pop
            // into the appropriate flow.
            builder.setPositiveButton(
                    context.getString(android.R.string.ok),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dismiss();
                            final Callback callback = (Callback) getActivity();
                            callback.onCheckSettingsErrorDialogEditCertificate();
                        }
                    });
            builder.setNegativeButton(
                    context.getString(android.R.string.cancel),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.cancel();
                        }
                    });

        } else {
            // "Normal" error - just use a single "Edit details" button.
            builder.setPositiveButton(
                    context.getString(R.string.account_setup_failed_dlg_edit_details_action),
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.cancel();
                        }
                    });
        }
        return builder.create();
    }

    @Override
    public void onCancel(DialogInterface dialog) {
        super.onCancel(dialog);
        final Callback callback = (Callback) getActivity();
        callback.onCheckSettingsErrorDialogEditSettings();
    }

    public static int getReasonFromException (MessagingException ex) {
        final int exceptionCode = ex.getExceptionType();
        switch (exceptionCode) {
            case MessagingException.AUTODISCOVER_AUTHENTICATION_FAILED:
            case MessagingException.AUTHENTICATION_FAILED:
                return REASON_AUTHENTICATION_FAILED;
            case MessagingException.CLIENT_CERTIFICATE_REQUIRED:
                return REASON_CERTIFICATE_REQUIRED;
        }
        return REASON_OTHER;
    }

    public static String getErrorString(Context context, MessagingException ex) {
        final int id;
        String message = ex.getMessage();
        if (message != null) {
            message = message.trim();
        }
        switch (ex.getExceptionType()) {
            // The remaining exception types are handled by setting the state to
            // STATE_CHECK_ERROR (above, default) and conversion to specific error strings.
            case MessagingException.CERTIFICATE_VALIDATION_ERROR:
                id = TextUtils.isEmpty(message)
                        ? R.string.account_setup_failed_dlg_certificate_message
                        : R.string.account_setup_failed_dlg_certificate_message_fmt;
                break;
            case MessagingException.AUTHENTICATION_FAILED:
                id = R.string.account_setup_failed_dlg_auth_message;
                break;
            case MessagingException.AUTODISCOVER_AUTHENTICATION_FAILED:
                id = R.string.account_setup_autodiscover_dlg_authfail_message;
                break;
            case MessagingException.AUTHENTICATION_FAILED_OR_SERVER_ERROR:
                id = R.string.account_setup_failed_check_credentials_message;
                break;
            case MessagingException.IOERROR:
                id = R.string.account_setup_failed_ioerror;
                break;
            case MessagingException.TLS_REQUIRED:
                id = R.string.account_setup_failed_tls_required;
                break;
            case MessagingException.AUTH_REQUIRED:
                id = R.string.account_setup_failed_auth_required;
                break;
            case MessagingException.SECURITY_POLICIES_UNSUPPORTED:
                id = R.string.account_setup_failed_security_policies_unsupported;
                // Belt and suspenders here; there should always be a non-empty array here
                String[] unsupportedPolicies = (String[]) ex.getExceptionData();
                if (unsupportedPolicies == null) {
                    LogUtils.w(LogUtils.TAG, "No data for unsupported policies?");
                    break;
                }
                // Build a string, concatenating policies we don't support
                final StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (String policyName: unsupportedPolicies) {
                    if (first) {
                        first = false;
                    } else {
                        sb.append(", ");
                    }
                    sb.append(policyName);
                }
                message = sb.toString();
                break;
            case MessagingException.ACCESS_DENIED:
                id = R.string.account_setup_failed_access_denied;
                break;
            case MessagingException.PROTOCOL_VERSION_UNSUPPORTED:
                id = R.string.account_setup_failed_protocol_unsupported;
                break;
            case MessagingException.GENERAL_SECURITY:
                id = R.string.account_setup_failed_security;
                break;
            case MessagingException.CLIENT_CERTIFICATE_REQUIRED:
                id = R.string.account_setup_failed_certificate_required;
                break;
            case MessagingException.CLIENT_CERTIFICATE_ERROR:
                id = R.string.account_setup_failed_certificate_inaccessible;
                break;
            default:
                id = TextUtils.isEmpty(message)
                        ? R.string.account_setup_failed_dlg_server_message
                        : R.string.account_setup_failed_dlg_server_message_fmt;
                break;
        }
        return TextUtils.isEmpty(message)
                ? context.getString(id)
                : context.getString(id, message);
    }
}
