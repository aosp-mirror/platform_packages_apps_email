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

import com.android.email.Email;
import com.android.email.R;
import com.android.email.Utility;
import com.android.email.SecurityPolicy.PolicySet;
import com.android.email.mail.MessagingException;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.email.provider.EmailContent.Account;
import com.android.email.service.EmailServiceProxy;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentManager;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;

/**
 * Check incoming or outgoing settings, or perform autodiscovery.
 *
 * There are three components that work together.  1. This fragment is retained and non-displayed,
 * and controls the overall process.  2. An AsyncTask that works with the stores/services to
 * check the accounts settings.  3. A stateless progress dialog (which will be recreated on
 * orientation changes).
 *
 * There are also two lightweight error dialogs which are used for notification of terminal
 * conditions.
 *
 * TODO support for account setup, including
 *  - general workflow
 *  - autodiscover
 *  - forwarding account security info
 * TODO Change progress dialog from AlertDialog to ProgressDialog (can't use builder)
 */
public class AccountCheckSettingsFragment extends Fragment {
    
    public final static String TAG = "AccountCheckSettingsFragment";

    // Debugging flags - for debugging the UI
    // If true, walks through a "fake" account check cycle
    private static final boolean DEBUG_FAKE_CHECK_CYCLE = false;   // DO NOT CHECK IN WHILE TRUE
    // If true, fake check cycle runs but returns failure
    private static final boolean DEBUG_FAKE_CHECK_ERR = false;    // DO NOT CHECK IN WHILE TRUE
    // If true, performs real check(s), but always returns fixed OK result
    private static final boolean DEBUG_FORCE_RESULT_OK = false;   // DO NOT CHECK IN WHILE TRUE

    // State
    private final static int STATE_START = 0;
    private final static int STATE_CHECK_AUTODISCOVER = 1;
    private final static int STATE_CHECK_INCOMING = 2;
    private final static int STATE_CHECK_OUTGOING = 3;
    private final static int STATE_CHECK_OK = 4;            // terminal
    private final static int STATE_CHECK_SHOW_SECURITY = 5; // terminal
    private final static int STATE_CHECK_ERROR = 6;         // terminal
    private int mState = STATE_START;

    // Support for UI
    private boolean mAttached;
    private CheckingDialog mCheckingDialog;
    private int mErrorStringId;
    private String mErrorMessage;
    private ErrorDialog mErrorDialog;
    private SecurityRequiredDialog mSecurityRequiredDialog;
    
    // Support for AsyncTask and account checking
    AccountCheckTask mAccountCheckTask;
    
    /**
     * Create a retained, invisible fragment that checks accounts
     *
     * @param mode incoming or outgoing
     */
    public static AccountCheckSettingsFragment newInstance(int mode, Fragment parentFragment) {
        AccountCheckSettingsFragment f = new AccountCheckSettingsFragment();
        f.setTargetFragment(parentFragment, mode);
        return f;
    }

    /**
     * Fragment initialization.  Because we never implement onCreateView, and call
     * setRetainInstance here, this creates an invisible, persistent, "worker" fragment.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
    }

    /**
     * This is called when the Fragment's Activity is ready to go, after
     * its content view has been installed; it is called both after
     * the initial fragment creation and after the fragment is re-attached
     * to a new activity.
     */
    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mAttached = true;

        // If this is the first time, start the AsyncTask
        if (mAccountCheckTask == null) {
            int checkMode = getTargetRequestCode();
            Account checkAccount = SetupData.getAccount();
            String storeUri = checkAccount.getStoreUri(getActivity());
            String storeHostname = checkAccount.mHostAuthRecv.mAddress;
            String senderUri = checkAccount.getSenderUri(getActivity());
            mAccountCheckTask = (AccountCheckTask)
                    new AccountCheckTask(checkMode, storeUri, storeHostname, senderUri)
                    .execute();
        }

        // if reattaching, update progress/error UI by re-reporting the previous values
        if (mState != STATE_START) {
            reportProgress(mState, mErrorStringId, mErrorMessage);
        }
    }

    /**
     * This is called when the fragment is going away.  It is NOT called
     * when the fragment is being propagated between activity instances.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        Utility.cancelTaskInterrupt(mAccountCheckTask);
        mAccountCheckTask = null;
    }

    /**
     * This is called right before the fragment is detached from its current activity instance.
     * All reporting and callbacks are halted until we reattach.
     */
    @Override
    public void onDetach() {
        super.onDetach();
        mAttached = false;
    }

    /**
     * The worker (AsyncTask) will call this (in the UI thread) to report progress.  If we are
     * attached to an activity, update the progress immediately;  If not, simply hold the
     * progress for later.
     * @param newState The new progress state being reported
     * @param errorStringId Resource Id of an error string to display
     * @param errorMessage Additional string to insert if the resource string takes a parameter.
     */
    public void reportProgress(int newState, int errorStringId, String errorMessage) {
        mState = newState;
        mErrorStringId = errorStringId;
        mErrorMessage = errorMessage;

        // If we are attached, create, recover, and/or update the dialog
        if (mAttached) {
            FragmentManager fm = getFragmentManager();

            if (newState == STATE_CHECK_OK) {
                // immediately terminate, clean up, and report back
                // 1. get rid of progress dialog (if any)
                recoverAndDismissCheckingDialog();
                // 2. exit self
                fm.popBackStack();
                // 3. report OK back to target fragment
                Fragment target = getTargetFragment();
                if (target instanceof AccountServerBaseFragment) {
                    AccountServerBaseFragment f = (AccountServerBaseFragment) target;
                    f.onCheckSettingsOk();
                 } else {
                     // STOPSHIP: Remove if not needed. May need to handle Account setup here
                     throw new IllegalStateException();
                 }
            } else if (newState == STATE_CHECK_SHOW_SECURITY) {
                // 1. get rid of progress dialog (if any)
                recoverAndDismissCheckingDialog();
                // 2. launch the error dialog
                mSecurityRequiredDialog = SecurityRequiredDialog.newInstance(this, mErrorMessage);
                fm.openTransaction().add(mSecurityRequiredDialog, SecurityRequiredDialog.TAG)
                        .commit();
            } else if (newState == STATE_CHECK_ERROR) {
                // 1. get rid of progress dialog (if any)
                recoverAndDismissCheckingDialog();
                // 2. launch the error dialog
                mErrorDialog = ErrorDialog.newInstance(this, mErrorStringId, mErrorMessage);
                fm.openTransaction().add(mErrorDialog, ErrorDialog.TAG).commit();
            } else {
                // Display a normal progress message
                mCheckingDialog = (CheckingDialog) fm.findFragmentByTag(CheckingDialog.TAG);

                if (mCheckingDialog == null) {
                    mCheckingDialog = CheckingDialog.newInstance(this, mState);
                    fm.openTransaction().add(mCheckingDialog, CheckingDialog.TAG).commit();
                } else {
                    mCheckingDialog.updateProgress(mState);
                }
            }
        }
    }

    /**
     * Recover and dismiss the progress dialog fragment
     */
    private void recoverAndDismissCheckingDialog() {
        if (mCheckingDialog == null) {
            mCheckingDialog = (CheckingDialog)
                    getFragmentManager().findFragmentByTag(CheckingDialog.TAG);
        }
        if (mCheckingDialog != null) {
            mCheckingDialog.dismiss();
            mCheckingDialog = null;
        }
    }

    /**
     * This is called when the user clicks "cancel" on the progress dialog.  Shuts everything
     * down and dismisses everything.
     * This should cause us to remain in the current screen (not accepting the settings)
     */
    private void onCheckingDialogCancel() {
        // 1. kill the checker
        Utility.cancelTaskInterrupt(mAccountCheckTask);
        mAccountCheckTask = null;
        // 2. kill self with no report - this is "cancel"
        getFragmentManager().popBackStack();
    }

    /**
     * This is called when the user clicks "edit" from the error dialog.  The error dialog
     * should have already dismissed itself.
     * This should cause us to remain in the current screen (not accepting the settings)
     */
    private void onErrorDialogEditButton() {
        // Exit self with no report - this is "cancel"
        getFragmentManager().popBackStack();
    }

    /**
     * This is called when the user clicks "ok" or "cancel" on the "security required" dialog.
     * Shuts everything down and dismisses everything, and reports the result appropriately.
     */
    private void onSecurityRequiredDialogButtonOk(boolean okPressed) {
        // 1. handle OK - notify that security is OK and we can proceed
        if (okPressed) {
            Fragment target = getTargetFragment();
            if (target instanceof AccountServerBaseFragment) {
                AccountServerBaseFragment f = (AccountServerBaseFragment) target;
                f.onCheckSettingsOk();
            } else {
                // STOPSHIP: Remove if not needed. May need to handle Account setup here
                throw new IllegalStateException();
            }
        }
        // 2. kill self
        getFragmentManager().popBackStack();
    }

    /**
     * This AsyncTask does the actual account checking
     *
     * TODO: It would be better to remove the UI complete from here (the exception->string
     * conversions).
     */
    private class AccountCheckTask extends AsyncTask<Void, Integer, MessagingException> {

        final Context mContext;
        final int mMode;
        final String mStoreUri;
        final String mStoreHost;
        final String mSenderUri;

        /**
         * Create task and parameterize it
         * @param mode bits request operations
         */
        public AccountCheckTask(int mode, String storeUri, String storeHost, String senderUri) {
            mContext = getActivity().getApplicationContext();
            mMode = mode;
            mStoreUri = storeUri;
            mStoreHost = storeHost;
            mSenderUri = senderUri;
        }

        @Override
        protected MessagingException doInBackground(Void... params) {
            if (DEBUG_FAKE_CHECK_CYCLE) {
                return fakeChecker();
            }

            try {
                // TODO: AutoDiscover
                if ((mMode & SetupData.CHECK_AUTODISCOVER) != 0) {
                    if (isCancelled()) return null;
                    publishProgress(STATE_CHECK_AUTODISCOVER);
                    return new MessagingException(-1, "Autodiscover unimplemented");
                }

                // Check Incoming Settings
                if ((mMode & SetupData.CHECK_INCOMING) != 0) {
                    if (isCancelled()) return null;
                    Log.d(Email.LOG_TAG, "Begin check of incoming email settings");
                    publishProgress(STATE_CHECK_INCOMING);
                    Store store = Store.getInstance(mStoreUri, mContext, null);
                    Bundle bundle = store.checkSettings();
                    int resultCode = MessagingException.UNSPECIFIED_EXCEPTION;
                    if (bundle != null) {
                        resultCode = bundle.getInt(
                                EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE);
                    }
                    if (resultCode == MessagingException.SECURITY_POLICIES_REQUIRED) {
                        SetupData.setPolicySet((PolicySet)bundle.getParcelable(
                                EmailServiceProxy.VALIDATE_BUNDLE_POLICY_SET));
                        return new MessagingException(
                                MessagingException.SECURITY_POLICIES_REQUIRED, mStoreHost);
                    }
                    if (resultCode != MessagingException.NO_ERROR) {
                        String errorMessage =
                            bundle.getString(EmailServiceProxy.VALIDATE_BUNDLE_ERROR_MESSAGE);
                        return new MessagingException(resultCode, errorMessage);
                    }
                }

                // Check Outgoing Settings
                if ((mMode & SetupData.CHECK_OUTGOING) != 0) {
                    if (isCancelled()) return null;
                    Log.d(Email.LOG_TAG, "Begin check of outgoing email settings");
                    publishProgress(STATE_CHECK_OUTGOING);
                    Sender sender = Sender.getInstance(mContext, mSenderUri);
                    sender.close();
                    sender.open();
                    sender.close();
                }

                // If we reached the end, we completed the check(s) successfully
                return null;
            } catch (final MessagingException me) {
                // Some of the legacy account checkers return errors by throwing MessagingException,
                // which we catch and return here.
                return me;
            }
        }

        /**
         * Dummy background worker, for testing UI only.  STOPSHIP remove this
         */
        private MessagingException fakeChecker() {
            // Dummy:  Publish a series of progress setups, 2 sec delays between them;
            // then return "ok" (null)
            final int DELAY = 2*1000;
            if (isCancelled()) return null;
            if ((mMode & SetupData.CHECK_AUTODISCOVER) != 0) {
                publishProgress(STATE_CHECK_AUTODISCOVER);
                if (DEBUG_FAKE_CHECK_ERR) {
                    return new MessagingException(MessagingException.AUTHENTICATION_FAILED);
                }
                try {
                    Thread.sleep(DELAY);
                } catch (InterruptedException e) { }
            }
            if (isCancelled()) return null;
            if ((mMode & SetupData.CHECK_INCOMING) != 0) {
                publishProgress(STATE_CHECK_INCOMING);
                if (DEBUG_FAKE_CHECK_ERR) {
                    return new MessagingException(MessagingException.IOERROR);
                }
                try {
                    Thread.sleep(DELAY);
                } catch (InterruptedException e) { }
            }
            if (isCancelled()) return null;
            if ((mMode & SetupData.CHECK_OUTGOING) != 0) {
                publishProgress(STATE_CHECK_OUTGOING);
                if (DEBUG_FAKE_CHECK_ERR) {
                    return new MessagingException(MessagingException.TLS_REQUIRED);
                }
                try {
                    Thread.sleep(DELAY);
                } catch (InterruptedException e) { }
            }
            return null;
        }

        /**
         * Progress reports (runs in UI thread).  This should be used for real progress only
         * (not for errors).
         */
        @Override
        protected void onProgressUpdate(Integer... progress) {
            if (isCancelled()) return;
            reportProgress(progress[0], 0, null);
        }

        /**
         * Result handler (runs in UI thread)
         * @param result null for a successful check;  exception for various errors
         */
        @Override
        protected void onPostExecute(MessagingException result) {
            if (isCancelled()) return;
            if (result == null) {
                reportProgress(STATE_CHECK_OK, 0, null);
            } else {
                int exceptionType = result.getExceptionType();
                String message = result.getMessage();
                int id;
                switch (exceptionType) {
                    // NOTE: Security policies required has its own report state, handle it a bit
                    // differently from the other exception types.
                    case MessagingException.SECURITY_POLICIES_REQUIRED:
                        reportProgress(STATE_CHECK_SHOW_SECURITY, 0, message);
                        return;
                    // Remaining exception types are handled together, move us to STATE_CHECK_ERROR
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
                    case MessagingException.PROTOCOL_VERSION_UNSUPPORTED:
                        id = R.string.account_setup_failed_protocol_unsupported;
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
                reportProgress(STATE_CHECK_ERROR, id, message);
            }
        }
    }

    /**
     * Simple dialog that shows progress as we work through the settings checks.
     * This is stateless except for its UI (e.g. current strings) and can be torn down or
     * recreated at any time without affecting the account checking progress.
     *
     * TODO: Indeterminate progress or other icon
     */
    public static class CheckingDialog extends DialogFragment {
        public final static String TAG = "CheckProgressDialog";

        // Extras for saved instance state
        private final String EXTRA_PROGRESS_STRING = "CheckProgressDialog.Progress";

        // UI
        private String mProgressString;

        /**
         * Create a dialog that reports progress
         * @param progress initial progress indication
         */
        public static CheckingDialog newInstance(AccountCheckSettingsFragment parentFragment,
                int progress) {
            CheckingDialog f = new CheckingDialog();
            f.setTargetFragment(parentFragment, progress);
            return f;
        }

        /**
         * Update the progress of an existing dialog
         * @param progress latest progress to be displayed
         */
        public void updateProgress(int progress) {
            mProgressString = getProgressString(progress);
            AlertDialog dialog = (AlertDialog) getDialog();
            dialog.setMessage(mProgressString);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            Context context = getActivity();
            if (savedInstanceState != null) {
                mProgressString = savedInstanceState.getString(EXTRA_PROGRESS_STRING);
            }
            if (mProgressString == null) {
                mProgressString = getProgressString(getTargetRequestCode());
            }
            final AccountCheckSettingsFragment target =
                (AccountCheckSettingsFragment) getTargetFragment();

            ProgressDialog dialog = new ProgressDialog(context);
            dialog.setIndeterminate(true);
            dialog.setMessage(mProgressString);
            dialog.setButton(DialogInterface.BUTTON_NEGATIVE,
                    context.getString(R.string.cancel_action),
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dismiss();
                            target.onCheckingDialogCancel();
                        }
                    });
            return dialog;
        }

        @Override
        public void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);
            outState.putString(EXTRA_PROGRESS_STRING, mProgressString);
        }

        /**
         * Convert progress to message
         */
        private String getProgressString(int progress) {
            int stringId = 0;
            switch (progress) {
                case STATE_CHECK_AUTODISCOVER:
                    stringId = R.string.account_setup_check_settings_retr_info_msg;
                    break;
                case STATE_CHECK_INCOMING:
                    stringId = R.string.account_setup_check_settings_check_incoming_msg;
                    break;
                case STATE_CHECK_OUTGOING:
                    stringId = R.string.account_setup_check_settings_check_outgoing_msg;
                    break;
            }
            return getActivity().getString(stringId);
        }
    }

    /**
     * The standard error dialog.  Calls back to onErrorDialogButton().
     */
    public static class ErrorDialog extends DialogFragment {
        public final static String TAG = "ErrorDialog";

        // Bundle keys for arguments
        private final static String ARGS_MESSAGE_ID = "ErrorDialog.Message.Id";
        private final static String ARGS_MESSAGE_ARGS = "ErrorDialog.Message.Args";

        public static ErrorDialog newInstance(AccountCheckSettingsFragment target,
                int messageId, String... messageArguments) {
            ErrorDialog fragment = new ErrorDialog();
            Bundle arguments = new Bundle();
            arguments.putInt(ARGS_MESSAGE_ID, messageId);
            arguments.putStringArray(ARGS_MESSAGE_ARGS, messageArguments);
            fragment.setArguments(arguments);
            fragment.setTargetFragment(target, 0);
            return fragment;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Context context = getActivity();
            final Bundle arguments = getArguments();
            final int messageId = arguments.getInt(ARGS_MESSAGE_ID);
            final Object[] messageArguments = arguments.getStringArray(ARGS_MESSAGE_ARGS);
            final AccountCheckSettingsFragment target =
                    (AccountCheckSettingsFragment) getTargetFragment();

            return new AlertDialog.Builder(context)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(context.getString(R.string.account_setup_failed_dlg_title))
                .setMessage(context.getString(messageId, messageArguments))
                .setCancelable(true)
                .setPositiveButton(
                        context.getString(R.string.account_setup_failed_dlg_edit_details_action),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                dismiss();
                                target.onErrorDialogEditButton();
                            }
                        })
                 .create();
        }

    }

    /**
     * The "security required" error dialog.  Calls back to onSecurityRequiredDialogButtonOk().
     */
    public static class SecurityRequiredDialog extends DialogFragment {
        public final static String TAG = "SecurityRequiredDialog";

        // Bundle keys for arguments
        private final static String ARGS_HOST_NAME = "SecurityRequiredDialog.HostName";

        public static SecurityRequiredDialog newInstance(AccountCheckSettingsFragment target,
                String hostName) {
            SecurityRequiredDialog fragment = new SecurityRequiredDialog();
            Bundle arguments = new Bundle();
            arguments.putString(ARGS_HOST_NAME, hostName);
            fragment.setArguments(arguments);
            fragment.setTargetFragment(target, 0);
            return fragment;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Context context = getActivity();
            final Bundle arguments = getArguments();
            final String hostName = arguments.getString(ARGS_HOST_NAME);
            final AccountCheckSettingsFragment target =
                    (AccountCheckSettingsFragment) getTargetFragment();

            return new AlertDialog.Builder(context)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setTitle(context.getString(R.string.account_setup_security_required_title))
                .setMessage(context.getString(
                        R.string.account_setup_security_policies_required_fmt, hostName))
                .setCancelable(true)
                .setPositiveButton(
                        context.getString(R.string.okay_action),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                dismiss();
                                target.onSecurityRequiredDialogButtonOk(true);
                            }
                        })
                .setNegativeButton(
                        context.getString(R.string.cancel_action),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                dismiss();
                                target.onSecurityRequiredDialogButtonOk(false);
                            }
                        })
                 .create();
        }

    }

}
