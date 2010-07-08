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

import com.android.email.Email;
import com.android.email.R;
import com.android.email.SecurityPolicy.PolicySet;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.email.provider.EmailContent.Account;
import com.android.email.service.EmailServiceProxy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * Checks the given settings to make sure that they can be used to send and
 * receive mail.
 *
 * XXX NOTE: The manifest for this activity has it ignore config changes, because
 * it doesn't correctly deal with restarting while its thread is running.
 * Do not attempt to define orientation-specific resources, they won't be loaded.
 */
public class AccountSetupCheckSettings extends AccountSetupActivity implements OnClickListener {
    
    // If true, returns immediately as if account was OK
    private static final boolean DBG_SKIP_CHECK_OK = false;     // DO NOT CHECK IN WHILE TRUE
    // If true, returns immediately as if account was not OK
    private static final boolean DBG_SKIP_CHECK_ERR = false;    // DO NOT CHECK IN WHILE TRUE
    // If true, performs real check(s), but always returns fixed OK result
    private static final boolean DBG_FORCE_RESULT_OK = false;   // DO NOT CHECK IN WHILE TRUE

    public static final int REQUEST_CODE_VALIDATE = 1;
    public static final int REQUEST_CODE_AUTO_DISCOVER = 2;

    // We'll define special result codes for certain types of connection results
    public static final int RESULT_AUTO_DISCOVER_AUTH_FAILED = Activity.RESULT_FIRST_USER;
    public static final int RESULT_SECURITY_REQUIRED_USER_CANCEL = Activity.RESULT_FIRST_USER + 1;

    private final Handler mHandler = new Handler();
    private ProgressBar mProgressBar;
    private TextView mMessageView;

    private boolean mCanceled;
    private boolean mDestroyed;

    public static void actionCheckSettings(Activity fromActivity, int mode) {
        SetupData.setCheckSettingsMode(mode);
        fromActivity.startActivityForResult(
                new Intent(fromActivity, AccountSetupCheckSettings.class), REQUEST_CODE_VALIDATE);
    }

    public static void actionAutoDiscover(Activity fromActivity, String email, String password) {
        SetupData.setUsername(email);
        SetupData.setPassword(password);
        SetupData.setCheckSettingsMode(SetupData.CHECK_AUTODISCOVER);
        fromActivity.startActivityForResult(
                new Intent(fromActivity, AccountSetupCheckSettings.class),
                    REQUEST_CODE_AUTO_DISCOVER);
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
        
        // For debugging UI only, force a true or false result - don't actually try connection
        if (DBG_SKIP_CHECK_OK || DBG_SKIP_CHECK_ERR) {
            setResult(DBG_SKIP_CHECK_OK ? RESULT_OK : RESULT_CANCELED);
            finish();
            return;
        }

        // Gather our data before starting up the thread to avoid any thread unpleasantness
        final Account account = SetupData.getAccount();
        final boolean checkIncoming = SetupData.isCheckIncoming();
        final boolean checkOutgoing = SetupData.isCheckOutgoing();
        final boolean checkAutodiscover = SetupData.isCheckAutodiscover();
        final boolean allowAutodiscover = SetupData.isAllowAutodiscover();
        final String userName = SetupData.getUsername();
        final String password = SetupData.getPassword();
        
        new Thread() {
            @Override
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
                    if (checkAutodiscover && allowAutodiscover) {
                        Log.d(Email.LOG_TAG, "Begin auto-discover for " + userName);
                        Store store = Store.getInstance(
                                account.getStoreUri(AccountSetupCheckSettings.this),
                                getApplication(), null);
                        Bundle result = store.autoDiscover(AccountSetupCheckSettings.this,
                                userName, password);
                        // Result will be null if there was a remote exception
                        // Otherwise, we can check the exception code and handle auth failed
                        // Other errors will be ignored, and the user will be taken to manual
                        // setup
                        if (result != null) {
                            int errorCode =
                                result.getInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE);
                            if (errorCode == MessagingException.AUTHENTICATION_FAILED) {
                                throw new MessagingException(
                                        MessagingException.AUTODISCOVER_AUTHENTICATION_FAILED);
                            } else if (errorCode != MessagingException.NO_ERROR) {
                                setResult(RESULT_OK);
                                finish();
                            }
                            // The success case is here
                            Intent resultIntent = new Intent();
                            resultIntent.putExtra("HostAuth", result.getParcelable(
                                    EmailServiceProxy.AUTO_DISCOVER_BUNDLE_HOST_AUTH));
                            setResult(RESULT_OK, resultIntent);
                            finish();
                            // auto-discover is never combined with other ops, so exit now
                            return;
                       }
                    }
                    if (mDestroyed) {
                        return;
                    }
                    if (mCanceled) {
                        finish();
                        return;
                    }
                    if (checkIncoming) {
                        Log.d(Email.LOG_TAG, "Begin check of incoming email settings");
                        setMessage(R.string.account_setup_check_settings_check_incoming_msg);
                        Store store = Store.getInstance(
                                account.getStoreUri(AccountSetupCheckSettings.this),
                                getApplication(), null);
                        Bundle bundle = store.checkSettings();
                        int resultCode = MessagingException.UNSPECIFIED_EXCEPTION;
                        if (bundle != null) {
                            resultCode = bundle.getInt(
                                    EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE);
                        }
                        if (resultCode == MessagingException.SECURITY_POLICIES_REQUIRED) {
                            SetupData.setPolicySet((PolicySet)bundle.getParcelable(
                                    EmailServiceProxy.VALIDATE_BUNDLE_POLICY_SET));
                            showSecurityRequiredDialog();
                            return;
                        }
                        if (resultCode != MessagingException.NO_ERROR) {
                            String errorMessage =
                                bundle.getString(EmailServiceProxy.VALIDATE_BUNDLE_ERROR_MESSAGE);
                            throw new MessagingException(resultCode, errorMessage);
                        }
                    }
                    if (mDestroyed) {
                        return;
                    }
                    if (mCanceled) {
                        finish();
                        return;
                    }
                    if (checkOutgoing) {
                        Log.d(Email.LOG_TAG, "Begin check of outgoing email settings");
                        setMessage(R.string.account_setup_check_settings_check_outgoing_msg);
                        Sender sender = Sender.getInstance(getApplication(),
                                account.getSenderUri(AccountSetupCheckSettings.this));
                        sender.close();
                        sender.open();
                        sender.close();
                    }
                    if (mDestroyed) {
                        return;
                    }
                    setResult(RESULT_OK);
                    finish();
                } catch (final MessagingException me) {
                    int exceptionType = me.getExceptionType();
                    // Handle fatal errors
                    int id;
                    String message = me.getMessage();
                    switch (exceptionType) {
                        case MessagingException.CERTIFICATE_VALIDATION_ERROR:
                            id = (message == null)
                                ? R.string.account_setup_failed_dlg_certificate_message
                                : R.string.account_setup_failed_dlg_certificate_message_fmt;
                            break;
                        case MessagingException.AUTHENTICATION_FAILED:
                        case MessagingException.AUTODISCOVER_AUTHENTICATION_FAILED:
                            id = (message == null)
                                ? R.string.account_setup_failed_dlg_auth_message
                                : R.string.account_setup_failed_dlg_auth_message_fmt;
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
                    showErrorDialog(
                            exceptionType == MessagingException.AUTODISCOVER_AUTHENTICATION_FAILED,
                            id, message);
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

    /**
     * The first argument here indicates whether we return an OK result or a cancelled result
     * An OK result is used by Exchange to indicate a failed authentication via AutoDiscover
     * In that case, we'll end up returning to the AccountSetupBasic screen
     */
    private void showErrorDialog(final boolean autoDiscoverAuthException, final int msgResId,
            final Object... args) {
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
                                        if (autoDiscoverAuthException) {
                                            setResult(RESULT_AUTO_DISCOVER_AUTH_FAILED);
                                        } else if (DBG_FORCE_RESULT_OK) {
                                            setResult(RESULT_OK);
                                        }
                                        finish();
                                    }
                                })
                        .show();
            }
        });
    }

    /**
     * Display a dialog asking the user if they are willing to accept control by the remote
     * server.  This converts the MessagingException.SECURITY_POLICIES_REQUIRED exception into an
     * Activity result of RESULT_OK, thus hiding the exception from the caller entirely.
     *
     * TODO: Perhaps use stronger button names than "OK" and "Cancel" (e.g. "Allow" / "Deny")
     */
    private void showSecurityRequiredDialog() {
        mHandler.post(new Runnable() {
            public void run() {
                if (mDestroyed) {
                    return;
                }
                mProgressBar.setIndeterminate(false);
                String host = SetupData.getAccount().mHostAuthRecv.mAddress;
                Object[] args = new String[] { host };
                new AlertDialog.Builder(AccountSetupCheckSettings.this)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setTitle(getString(R.string.account_setup_security_required_title))
                        .setMessage(getString(
                                R.string.account_setup_security_policies_required_fmt, args))
                        .setCancelable(true)
                        .setPositiveButton(
                                getString(R.string.okay_action),
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        Intent intent = new Intent();
                                        intent.putExtra(
                                                EmailServiceProxy.VALIDATE_BUNDLE_POLICY_SET,
                                                SetupData.getPolicySet());
                                        setResult(RESULT_OK, intent);
                                        finish();
                                    }
                                })
                        .setNegativeButton(
                                getString(R.string.cancel_action),
                                new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int which) {
                                        setResult(RESULT_SECURITY_REQUIRED_USER_CANCEL);
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
