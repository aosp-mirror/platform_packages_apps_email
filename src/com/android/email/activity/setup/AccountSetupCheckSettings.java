/*
 * Copyright (C) 2008 The Android Open Source Project
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

import com.android.email.Account;
import com.android.email.R;
import com.android.email.mail.AuthenticationFailedException;
import com.android.email.mail.CertificateValidationException;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Store;
import com.android.email.mail.Sender;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Checks the given settings to make sure that they can be used to send and
 * receive mail.
 *
 * XXX NOTE: The manifest for this app has it ignore config changes, because
 * it doesn't correctly deal with restarting while its thread is running.
 */
public class AccountSetupCheckSettings extends Activity implements OnClickListener {
    private static final String EXTRA_ACCOUNT = "account";

    private static final String EXTRA_CHECK_INCOMING = "checkIncoming";

    private static final String EXTRA_CHECK_OUTGOING = "checkOutgoing";

    private Handler mHandler = new Handler();

    private ProgressBar mProgressBar;

    private TextView mMessageView;

    private Account mAccount;

    private boolean mCheckIncoming;

    private boolean mCheckOutgoing;

    private boolean mCanceled;

    private boolean mDestroyed;

    public static void actionCheckSettings(Activity fromActivity, Account account,
            boolean checkIncoming, boolean checkOutgoing) {
        Intent i = new Intent(fromActivity, AccountSetupCheckSettings.class);
        i.putExtra(EXTRA_ACCOUNT, account);
        i.putExtra(EXTRA_CHECK_INCOMING, checkIncoming);
        i.putExtra(EXTRA_CHECK_OUTGOING, checkOutgoing);
        fromActivity.startActivityForResult(i, 1);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.account_setup_check_settings);
        mMessageView = (TextView)findViewById(R.id.message);
        mProgressBar = (ProgressBar)findViewById(R.id.progress);
        ((Button)findViewById(R.id.cancel)).setOnClickListener(this);

        setMessage(R.string.account_setup_check_settings_retr_info_msg);
        mProgressBar.setIndeterminate(true);

        mAccount = (Account)getIntent().getSerializableExtra(EXTRA_ACCOUNT);
        mCheckIncoming = (boolean)getIntent().getBooleanExtra(EXTRA_CHECK_INCOMING, false);
        mCheckOutgoing = (boolean)getIntent().getBooleanExtra(EXTRA_CHECK_OUTGOING, false);

        new Thread() {
            public void run() {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                try {
                    if (mDestroyed) {
                        return;
                    }
                    if (mCanceled) {
                        finish();
                        return;
                    }
                    if (mCheckIncoming) {
                        setMessage(R.string.account_setup_check_settings_check_incoming_msg);
                        Store store = Store.getInstance(mAccount.getStoreUri(), getApplication(), 
                                null);
                        store.checkSettings();
                    }
                    if (mDestroyed) {
                        return;
                    }
                    if (mCanceled) {
                        finish();
                        return;
                    }
                    if (mCheckOutgoing) {
                        setMessage(R.string.account_setup_check_settings_check_outgoing_msg);
                        Sender sender = Sender.getInstance(mAccount.getSenderUri(),
                                getApplication());
                        sender.close();
                        sender.open();
                        sender.close();
                    }
                    if (mDestroyed) {
                        return;
                    }
                    if (mCanceled) {
                        finish();
                        return;
                    }
                    setResult(RESULT_OK);
                    finish();
                } catch (final AuthenticationFailedException afe) {
                    String message = afe.getMessage();
                    int id = (message == null) 
                            ? R.string.account_setup_failed_dlg_auth_message
                            : R.string.account_setup_failed_dlg_auth_message_fmt;
                    showErrorDialog(id, message);
                } catch (final CertificateValidationException cve) {
                    String message = cve.getMessage();
                    int id = (message == null) 
                        ? R.string.account_setup_failed_dlg_certificate_message
                        : R.string.account_setup_failed_dlg_certificate_message_fmt;
                    showErrorDialog(id, message);
                } catch (final MessagingException me) {
                    int id;
                    String message = me.getMessage();
                    switch (me.getExceptionType()) {
                        case MessagingException.IOERROR:
                            id = R.string.account_setup_failed_ioerror;
                            break;
                        case MessagingException.TLS_REQUIRED:
                            id = R.string.account_setup_failed_tls_required;
                            break;
                        case MessagingException.AUTH_REQUIRED:
                            id = R.string.account_setup_failed_auth_required;
                            break;
                        case MessagingException.GENERAL_SECURITY:
                            id = R.string.account_setup_failed_security;
                            break;
                        default:
                            id = (message == null) 
                                    ? R.string.account_setup_failed_dlg_server_message
                                    : R.string.account_setup_failed_dlg_server_message_fmt;  
                            break;
                    }
                    showErrorDialog(id, message);
                }
            }
        }.start();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mDestroyed = true;
        mCanceled = true;
    }

    private void setMessage(final int resId) {
        mHandler.post(new Runnable() {
            public void run() {
                if (mDestroyed) {
                    return;
                }
                mMessageView.setText(getString(resId));
            }
        });
    }

    private void showErrorDialog(final int msgResId, final Object... args) {
        mHandler.post(new Runnable() {
            public void run() {
                if (mDestroyed) {
                    return;
                }
                mProgressBar.setIndeterminate(false);
                new AlertDialog.Builder(AccountSetupCheckSettings.this)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(getString(R.string.account_setup_failed_dlg_title))
                        .setMessage(getString(msgResId, args))
                        .setCancelable(true)
                        .setPositiveButton(
                                getString(R.string.account_setup_failed_dlg_edit_details_action),
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        finish();
                                    }
                                })
                        .show();
            }
        });
    }

    private void onCancel() {
        mCanceled = true;
        setMessage(R.string.account_setup_check_settings_canceling_msg);
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.cancel:
                onCancel();
                break;
        }
    }
}
