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

import com.android.email.AttachmentInfo;
import com.android.email.R;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.provider.Settings;

/**
 * "Info" dialog box
 */
public class AttachmentInfoDialog extends DialogFragment {
    private static final String BUNDLE_TITLE         = "title";
    private static final String BUNDLE_BODY_TEXT     = "body_text";
    private static final String BUNDLE_ACTION_TEXT   = "action_text";
    private static final String BUNDLE_ACTION_INTENT = "action_intent";

    /**
     * Returns a new dialog instance
     */
    public static AttachmentInfoDialog newInstance(Context context, int denyFlags) {
        Resources res = context.getResources();
        String title = res.getString(R.string.attachment_info_dialog_default_title);
        String bodyText = res.getString(R.string.attachment_info_unknown);
        String actionText = null;
        Intent actionIntent = null;

        // NOTE: Order here matters. There can be multiple reasons for denying an attachment,
        // so, we want to show the most important ones first (i.e. it's pointless to tell the
        // user to connect to wi-fi to download a 30mb attachment that is suspected of being
        // malware).
        if ((denyFlags & AttachmentInfo.DENY_MALWARE) != 0) {
            bodyText = res.getString(R.string.attachment_info_malware);
        } else if ((denyFlags & AttachmentInfo.DENY_POLICY) != 0) {
            bodyText = res.getString(R.string.attachment_info_policy);
        } else if ((denyFlags & AttachmentInfo.DENY_NOINTENT) != 0) {
            bodyText = res.getString(R.string.attachment_info_no_intent);
        } else if ((denyFlags & AttachmentInfo.DENY_NOSIDELOAD) != 0) {
            bodyText = res.getString(R.string.attachment_info_sideload_disabled);
            actionText = res.getString(R.string.attachment_info_application_settings);
            actionIntent = new Intent(Settings.ACTION_SECURITY_SETTINGS);
            actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            actionIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        } else if ((denyFlags & AttachmentInfo.DENY_APKINSTALL) != 0) {
            bodyText = res.getString(R.string.attachment_info_apk_install_disabled);
        } else if ((denyFlags & AttachmentInfo.DENY_WIFIONLY) != 0) {
            title = res.getString(R.string.attachment_info_dialog_wifi_title);
            bodyText = res.getString(R.string.attachment_info_wifi_only);
            actionText = res.getString(R.string.attachment_info_wifi_settings);
            actionIntent = new Intent(Settings.ACTION_WIFI_SETTINGS);
            actionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            actionIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        }
        AttachmentInfoDialog dialog = new AttachmentInfoDialog();
        Bundle args = new Bundle();
        args.putString(BUNDLE_TITLE, title);
        args.putString(BUNDLE_BODY_TEXT, bodyText);
        args.putString(BUNDLE_ACTION_TEXT, actionText);
        args.putParcelable(BUNDLE_ACTION_INTENT, actionIntent);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Bundle args = getArguments();
        Context context = getActivity();
        String title = args.getString(BUNDLE_TITLE);
        String infoText = args.getString(BUNDLE_BODY_TEXT);
        String actionText = args.getString(BUNDLE_ACTION_TEXT);
        final Intent actionIntent = args.getParcelable(BUNDLE_ACTION_INTENT);

        OnClickListener onClickListener = new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        startActivity(actionIntent);
                        break;
                    case DialogInterface.BUTTON_NEUTRAL:
                        dialog.dismiss();
                        break;
                }
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(title);
        builder.setMessage(infoText);
        builder.setNeutralButton(R.string.okay_action, onClickListener);
        if (actionText != null && actionIntent != null) {
            builder.setPositiveButton(actionText, onClickListener);
        }
        return builder.show();
    }
}
