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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

/**
 * "Message Details" dialog box.
 */
public class MessageViewMessageDetailsDialog  extends DialogFragment {
    private static final String BUNDLE_SUBJECT = "subject";
    private static final String BUNDLE_DATE = "date";
    private static final String BUNDLE_FROM = "from";
    private static final String BUNDLE_TO = "to";
    private static final String BUNDLE_CC = "cc";
    private static final String BUNDLE_BCC = "bcc";

    public static MessageViewMessageDetailsDialog newInstance(Activity parent, String subject,
            String date, String from, String to, String cc, String bcc) {
        MessageViewMessageDetailsDialog dialog = new MessageViewMessageDetailsDialog();
        Bundle args = new Bundle();
        args.putString(BUNDLE_SUBJECT, subject);
        args.putString(BUNDLE_DATE, date);
        args.putString(BUNDLE_FROM, from);
        args.putString(BUNDLE_TO, to);
        args.putString(BUNDLE_CC, cc);
        args.putString(BUNDLE_BCC, bcc);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        final Activity activity = getActivity();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(
                activity.getResources().getString(
                        R.string.message_view_message_details_dialog_title));
        builder.setNegativeButton(R.string.close_action, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dismiss();
            }
        });
        builder.setView(initView());

        return builder.show();
    }

    private View initView() {
        View root = getActivity().getLayoutInflater().inflate(R.layout.message_view_details, null);
        Bundle args = getArguments();

        setText(root, args.getString(BUNDLE_SUBJECT), R.id.subject, R.id.subject_row);
        setText(root, args.getString(BUNDLE_DATE), R.id.date, R.id.date_row);
        setText(root, args.getString(BUNDLE_FROM), R.id.from, R.id.from_row);
        setText(root, args.getString(BUNDLE_TO), R.id.to, R.id.to_row);
        setText(root, args.getString(BUNDLE_CC), R.id.cc, R.id.cc_row);
        setText(root, args.getString(BUNDLE_BCC), R.id.bcc, R.id.bcc_row);
        setText(root, args.getString(BUNDLE_BCC), R.id.bcc, R.id.bcc_row);

        return root;
    }

    private static void setText(View root, String text, int textViewId, int rowViewId) {
        if (TextUtils.isEmpty(text)) {
            root.findViewById(rowViewId).setVisibility(View.GONE);
            return;
        }
        ((TextView) root.findViewById(textViewId)).setText(text);
    }
}
