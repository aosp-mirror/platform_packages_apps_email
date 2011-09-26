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

import android.app.Activity;
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
import android.text.TextUtils;
import android.util.Log;

import com.android.email.R;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.utility.Utility;

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
 */
public class AccountCheckSettingsFragment extends Fragment {

    public final static String TAG = "AccountCheckSettingsFragment";

    // Debugging flags - for debugging the UI
    // If true, use a "fake" account check cycle
    private static final boolean DEBUG_FAKE_CHECK_CYCLE = false;            // DO NOT CHECK IN TRUE
    // If true, use fake check cycle, return failure
    private static final boolean DEBUG_FAKE_CHECK_ERR = false;              // DO NOT CHECK IN TRUE
    // If true, use fake check cycle, return "security required"
    private static final boolean DEBUG_FORCE_SECURITY_REQUIRED = false;     // DO NOT CHECK IN TRUE

    // State
    private final static int STATE_START = 0;
    private final static int STATE_CHECK_AUTODISCOVER = 1;
    private final static int STATE_CHECK_INCOMING = 2;
    private final static int STATE_CHECK_OUTGOING = 3;
    private final static int STATE_CHECK_OK = 4;                    // terminal
    private final static int STATE_CHECK_SHOW_SECURITY = 5;         // terminal
    private final static int STATE_CHECK_ERROR = 6;                 // terminal
    private final static int STATE_AUTODISCOVER_AUTH_DIALOG = 7;    // terminal
    private final static int STATE_AUTODISCOVER_RESULT = 8;         // terminal
    private int mState = STATE_START;

    // Support for UI
    private boolean mAttached;
    private CheckingDialog mCheckingDialog;
    private MessagingException mProgressException;

    // Support for AsyncTask and account checking
    AccountCheckTask mAccountCheckTask;

    // Result codes returned by onCheckSettingsComplete.
    /** Check settings returned successfully */
    public final static int CHECK_SETTINGS_OK = 0;
    /** Check settings failed due to connection, authentication, or other server error */
    public final static int CHECK_SETTINGS_SERVER_ERROR = 1;
    /** Check settings failed due to user refusing to accept security requirements */
    public final static int CHECK_SETTINGS_SECURITY_USER_DENY = 2;
    /** Check settings failed due to certificate being required - user needs to pick immediately. */
    public final static int CHECK_SETTINGS_CLIENT_CERTIFICATE_NEEDED = 3;

    // Result codes returned by onAutoDiscoverComplete.
    /** AutoDiscover completed successfully with server setup data */
    public final static int AUTODISCOVER_OK = 0;
    /** AutoDiscover completed with no data (no server or AD not supported) */
    public final static int AUTODISCOVER_NO_DATA = 1;
    /** AutoDiscover reported authentication error */
    public final static int AUTODISCOVER_AUTHENTICATION = 2;

    /**
     * Callback interface for any target or activity doing account check settings
     */
    public interface Callbacks {
        /**
         * Called when CheckSettings completed
         * @param result check settings result code - success is CHECK_SETTINGS_OK
         */
        public void onCheckSettingsComplete(int result);

        /**
         * Called when autodiscovery completes.
         * @param result autodiscovery result code - success is AUTODISCOVER_OK
         * @param hostAuth configuration data returned by AD server, or null if no data available
         */
        public void onAutoDiscoverComplete(int result, HostAuth hostAuth);
    }

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
            mAccountCheckTask = (AccountCheckTask)
                    new AccountCheckTask(checkMode, checkAccount)
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    /**
     * When resuming, restart the progress/error UI if necessary by re-reporting previous values
     */
    @Override
    public void onResume() {
        super.onResume();

        if (mState != STATE_START) {
            reportProgress(mState, mProgressException);
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
     */
    private void reportProgress(int newState, MessagingException ex) {
        mState = newState;
        mProgressException = ex;

        // If we are attached, create, recover, and/or update the dialog
        if (mAttached) {
            FragmentManager fm = getFragmentManager();

            switch (newState) {
                case STATE_CHECK_OK:
                    // immediately terminate, clean up, and report back
                    // 1. get rid of progress dialog (if any)
                    recoverAndDismissCheckingDialog();
                    // 2. exit self
                    fm.popBackStack();
                    // 3. report OK back to target fragment or activity
                    getCallbackTarget().onCheckSettingsComplete(CHECK_SETTINGS_OK);
                    break;
                case STATE_CHECK_SHOW_SECURITY:
                    // 1. get rid of progress dialog (if any)
                    recoverAndDismissCheckingDialog();
                    // 2. launch the error dialog, if needed
                    if (fm.findFragmentByTag(SecurityRequiredDialog.TAG) == null) {
                        String message = ex.getMessage();
                        if (message != null) {
                            message = message.trim();
                        }
                        SecurityRequiredDialog securityRequiredDialog =
                                SecurityRequiredDialog.newInstance(this, message);
                        fm.beginTransaction()
                                .add(securityRequiredDialog, SecurityRequiredDialog.TAG)
                                .commit();
                    }
                    break;
                case STATE_CHECK_ERROR:
                case STATE_AUTODISCOVER_AUTH_DIALOG:
                    // 1. get rid of progress dialog (if any)
                    recoverAndDismissCheckingDialog();
                    // 2. launch the error dialog, if needed
                    if (fm.findFragmentByTag(ErrorDialog.TAG) == null) {
                        ErrorDialog errorDialog = ErrorDialog.newInstance(
                                getActivity(), this, mProgressException);
                        fm.beginTransaction()
                                .add(errorDialog, ErrorDialog.TAG)
                                .commit();
                    }
                    break;
                case STATE_AUTODISCOVER_RESULT:
                    HostAuth autoDiscoverResult = ((AutoDiscoverResults) ex).mHostAuth;
                    // 1. get rid of progress dialog (if any)
                    recoverAndDismissCheckingDialog();
                    // 2. exit self
                    fm.popBackStack();
                    // 3. report back to target fragment or activity
                    getCallbackTarget().onAutoDiscoverComplete(
                            (autoDiscoverResult != null) ? AUTODISCOVER_OK : AUTODISCOVER_NO_DATA,
                            autoDiscoverResult);
                    break;
                default:
                    // Display a normal progress message
                    mCheckingDialog = (CheckingDialog) fm.findFragmentByTag(CheckingDialog.TAG);

                    if (mCheckingDialog == null) {
                        mCheckingDialog = CheckingDialog.newInstance(this, mState);
                        fm.beginTransaction()
                                .add(mCheckingDialog, CheckingDialog.TAG)
                                .commit();
                    } else {
                        mCheckingDialog.updateProgress(mState);
                    }
                    break;
            }
        }
    }

    /**
     * Find the callback target, either a target fragment or the activity
     */
    private Callbacks getCallbackTarget() {
        Fragment target = getTargetFragment();
        if (target instanceof Callbacks) {
            return (Callbacks) target;
        }
        Activity activity = getActivity();
        if (activity instanceof Callbacks) {
            return (Callbacks) activity;
        }
        throw new IllegalStateException();
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
            mCheckingDialog.dismissAllowingStateLoss();
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
        finish();
    }

    private void onEditCertificateOk() {
        Callbacks callbackTarget = getCallbackTarget();
        getCallbackTarget().onCheckSettingsComplete(CHECK_SETTINGS_CLIENT_CERTIFICATE_NEEDED);
        finish();
    }

    /**
     * This is called when the user clicks "edit" from the error dialog.  The error dialog
     * should have already dismissed itself.
     * Depending on the context, the target will remain in the current activity (e.g. editing
     * settings) or return to its own parent (e.g. enter new credentials).
     */
    private void onErrorDialogEditButton() {
        // 1. handle "edit" - notify callback that we had a problem with the test
        Callbacks callbackTarget = getCallbackTarget();
        if (mState == STATE_AUTODISCOVER_AUTH_DIALOG) {
            // report auth error to target fragment or activity
            callbackTarget.onAutoDiscoverComplete(AUTODISCOVER_AUTHENTICATION, null);
        } else {
            // report check settings failure to target fragment or activity
            callbackTarget.onCheckSettingsComplete(CHECK_SETTINGS_SERVER_ERROR);
        }
        finish();
    }

    /** Kill self if not already killed. */
    private void finish() {
        FragmentManager fm = getFragmentManager();
        if (fm != null) {
            fm.popBackStack();
        }
    }

    /**
     * This is called when the user clicks "ok" or "cancel" on the "security required" dialog.
     * Shuts everything down and dismisses everything, and reports the result appropriately.
     */
    private void onSecurityRequiredDialogResultOk(boolean okPressed) {
        // 1. handle OK/cancel - notify that security is OK and we can proceed
        Callbacks callbackTarget = getCallbackTarget();
        callbackTarget.onCheckSettingsComplete(
                okPressed ? CHECK_SETTINGS_OK : CHECK_SETTINGS_SECURITY_USER_DENY);

        // 2. kill self if not already killed by callback
        FragmentManager fm = getFragmentManager();
        if (fm != null) {
            fm.popBackStack();
        }
    }

    /**
     * This exception class is used to report autodiscover results via the reporting mechanism.
     */
    public static class AutoDiscoverResults extends MessagingException {
        public final HostAuth mHostAuth;

        /**
         * @param authenticationError true if auth failure, false for result (or no response)
         * @param hostAuth null for "no autodiscover", non-null for server info to return
         */
        public AutoDiscoverResults(boolean authenticationError, HostAuth hostAuth) {
            super(null);
            if (authenticationError) {
                mExceptionType = AUTODISCOVER_AUTHENTICATION_FAILED;
            } else {
                mExceptionType = AUTODISCOVER_AUTHENTICATION_RESULT;
            }
            mHostAuth = hostAuth;
        }
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
        final Account mAccount;
        final String mStoreHost;
        final String mCheckEmail;
        final String mCheckPassword;

        /**
         * Create task and parameterize it
         * @param mode bits request operations
         * @param checkAccount account holding values to be checked
         */
        public AccountCheckTask(int mode, Account checkAccount) {
            mContext = getActivity().getApplicationContext();
            mMode = mode;
            mAccount = checkAccount;
            mStoreHost = checkAccount.mHostAuthRecv.mAddress;
            mCheckEmail = checkAccount.mEmailAddress;
            mCheckPassword = checkAccount.mHostAuthRecv.mPassword;
        }

        @Override
        protected MessagingException doInBackground(Void... params) {
            if (DEBUG_FAKE_CHECK_CYCLE) {
                return fakeChecker();
            }

            try {
                if ((mMode & SetupData.CHECK_AUTODISCOVER) != 0) {
                    if (isCancelled()) return null;
                    publishProgress(STATE_CHECK_AUTODISCOVER);
                    Log.d(Logging.LOG_TAG, "Begin auto-discover for " + mCheckEmail);
                    Store store = Store.getInstance(mAccount, mContext);
                    Bundle result = store.autoDiscover(mContext, mCheckEmail, mCheckPassword);
                    // Result will be one of:
                    //  null: remote exception - proceed to manual setup
                    //  MessagingException.AUTHENTICATION_FAILED: username/password rejected
                    //  Other error: proceed to manual setup
                    //  No error: return autodiscover results
                    if (result == null) {
                        return new AutoDiscoverResults(false, null);
                    }
                    int errorCode =
                            result.getInt(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_ERROR_CODE);
                    if (errorCode == MessagingException.AUTHENTICATION_FAILED) {
                        return new AutoDiscoverResults(true, null);
                    } else if (errorCode != MessagingException.NO_ERROR) {
                        return new AutoDiscoverResults(false, null);
                    } else {
                        HostAuth serverInfo = (HostAuth)
                            result.getParcelable(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_HOST_AUTH);
                        return new AutoDiscoverResults(false, serverInfo);
                    }
                }

                // Check Incoming Settings
                if ((mMode & SetupData.CHECK_INCOMING) != 0) {
                    if (isCancelled()) return null;
                    Log.d(Logging.LOG_TAG, "Begin check of incoming email settings");
                    publishProgress(STATE_CHECK_INCOMING);
                    Store store = Store.getInstance(mAccount, mContext);
                    Bundle bundle = store.checkSettings();
                    int resultCode = MessagingException.UNSPECIFIED_EXCEPTION;
                    if (bundle != null) {
                        resultCode = bundle.getInt(
                                EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE);
                    }
                    if (resultCode == MessagingException.SECURITY_POLICIES_REQUIRED) {
                        SetupData.setPolicy((Policy)bundle.getParcelable(
                                EmailServiceProxy.VALIDATE_BUNDLE_POLICY_SET));
                        return new MessagingException(resultCode, mStoreHost);
                    } else if (resultCode == MessagingException.SECURITY_POLICIES_UNSUPPORTED) {
                        String[] data = bundle.getStringArray(
                                EmailServiceProxy.VALIDATE_BUNDLE_UNSUPPORTED_POLICIES);
                        return new MessagingException(resultCode, mStoreHost, data);
                    } else if (resultCode != MessagingException.NO_ERROR) {
                        String errorMessage =
                            bundle.getString(EmailServiceProxy.VALIDATE_BUNDLE_ERROR_MESSAGE);
                        return new MessagingException(resultCode, errorMessage);
                    }
                }

                // Check Outgoing Settings
                if ((mMode & SetupData.CHECK_OUTGOING) != 0) {
                    if (isCancelled()) return null;
                    Log.d(Logging.LOG_TAG, "Begin check of outgoing email settings");
                    publishProgress(STATE_CHECK_OUTGOING);
                    Sender sender = Sender.getInstance(mContext, mAccount);
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
         * Dummy background worker, for testing UI only.
         */
        private MessagingException fakeChecker() {
            // Dummy:  Publish a series of progress setups, 2 sec delays between them;
            // then return "ok" (null)
            final int DELAY = 2*1000;
            if (isCancelled()) return null;
            if ((mMode & SetupData.CHECK_AUTODISCOVER) != 0) {
                publishProgress(STATE_CHECK_AUTODISCOVER);
                try {
                    Thread.sleep(DELAY);
                } catch (InterruptedException e) { }
                if (DEBUG_FAKE_CHECK_ERR) {
                    return new MessagingException(MessagingException.AUTHENTICATION_FAILED);
                }
                // Return "real" AD results
                HostAuth auth = new HostAuth();
                auth.setLogin("user", "password");
                auth.setConnection(HostAuth.SCHEME_EAS, "testserver.com", 0);
                return new AutoDiscoverResults(false, auth);
            }
            if (isCancelled()) return null;
            if ((mMode & SetupData.CHECK_INCOMING) != 0) {
                publishProgress(STATE_CHECK_INCOMING);
                try {
                    Thread.sleep(DELAY);
                } catch (InterruptedException e) { }
                if (DEBUG_FAKE_CHECK_ERR) {
                    return new MessagingException(MessagingException.IOERROR);
                } else if (DEBUG_FORCE_SECURITY_REQUIRED) {
                    return new MessagingException(MessagingException.SECURITY_POLICIES_REQUIRED);
                }
            }
            if (isCancelled()) return null;
            if ((mMode & SetupData.CHECK_OUTGOING) != 0) {
                publishProgress(STATE_CHECK_OUTGOING);
                try {
                    Thread.sleep(DELAY);
                } catch (InterruptedException e) { }
                if (DEBUG_FAKE_CHECK_ERR) {
                    return new MessagingException(MessagingException.TLS_REQUIRED);
                }
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
            reportProgress(progress[0], null);
        }

        /**
         * Result handler (runs in UI thread).
         *
         * AutoDiscover authentication errors are handled a bit differently than the
         * other errors;  If encountered, we display the error dialog, but we return with
         * a different callback used only for AutoDiscover.
         *
         * @param result null for a successful check;  exception for various errors
         */
        @Override
        protected void onPostExecute(MessagingException result) {
            if (isCancelled()) return;
            if (result == null) {
                reportProgress(STATE_CHECK_OK, null);
            } else {
                int progressState = STATE_CHECK_ERROR;
                int exceptionType = result.getExceptionType();

                switch (exceptionType) {
                    // NOTE: AutoDiscover reports have their own reporting state, handle differently
                    // from the other exception types
                    case MessagingException.AUTODISCOVER_AUTHENTICATION_FAILED:
                        progressState = STATE_AUTODISCOVER_AUTH_DIALOG;
                        break;
                    case MessagingException.AUTODISCOVER_AUTHENTICATION_RESULT:
                        progressState = STATE_AUTODISCOVER_RESULT;
                        break;
                    // NOTE: Security policies required has its own report state, handle it a bit
                    // differently from the other exception types.
                    case MessagingException.SECURITY_POLICIES_REQUIRED:
                        progressState = STATE_CHECK_SHOW_SECURITY;
                        break;
                }
                reportProgress(progressState, result);
            }
        }
    }

    private static String getErrorString(Context context, MessagingException ex) {
        int id;
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
                id = TextUtils.isEmpty(message)
                        ? R.string.account_setup_failed_dlg_auth_message
                        : R.string.account_setup_failed_dlg_auth_message_fmt;
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
                    Log.w(TAG, "No data for unsupported policies?");
                    break;
                }
                // Build a string, concatenating policies we don't support
                StringBuilder sb = new StringBuilder();
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

    /**
     * Simple dialog that shows progress as we work through the settings checks.
     * This is stateless except for its UI (e.g. current strings) and can be torn down or
     * recreated at any time without affecting the account checking progress.
     */
    public static class CheckingDialog extends DialogFragment {
        @SuppressWarnings("hiding")
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

        /**
         * Listen for cancellation, which can happen from places other than the
         * negative button (e.g. touching outside the dialog), and stop the checker
         */
        @Override
        public void onCancel(DialogInterface dialog) {
            AccountCheckSettingsFragment target =
                (AccountCheckSettingsFragment) getTargetFragment();
            target.onCheckingDialogCancel();
            super.onCancel(dialog);
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
        @SuppressWarnings("hiding")
        public final static String TAG = "ErrorDialog";

        // Bundle keys for arguments
        private final static String ARGS_MESSAGE = "ErrorDialog.Message";
        private final static String ARGS_EXCEPTION_ID = "ErrorDialog.ExceptionId";

        /**
         * Use {@link #newInstance} This public constructor is still required so
         * that DialogFragment state can be automatically restored by the
         * framework.
         */
        public ErrorDialog() {
        }

        public static ErrorDialog newInstance(Context context, AccountCheckSettingsFragment target,
                MessagingException ex) {
            ErrorDialog fragment = new ErrorDialog();
            Bundle arguments = new Bundle();
            arguments.putString(ARGS_MESSAGE, getErrorString(context, ex));
            arguments.putInt(ARGS_EXCEPTION_ID, ex.getExceptionType());
            fragment.setArguments(arguments);
            fragment.setTargetFragment(target, 0);
            return fragment;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final Context context = getActivity();
            final Bundle arguments = getArguments();
            final String message = arguments.getString(ARGS_MESSAGE);
            final int exceptionId = arguments.getInt(ARGS_EXCEPTION_ID);
            final AccountCheckSettingsFragment target =
                    (AccountCheckSettingsFragment) getTargetFragment();

            AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(context.getString(R.string.account_setup_failed_dlg_title))
                .setMessage(message)
                .setCancelable(true);

            if (exceptionId == MessagingException.CLIENT_CERTIFICATE_REQUIRED) {
                // Certificate error - show two buttons so the host fragment can auto pop
                // into the appropriate flow.
                builder.setPositiveButton(
                        context.getString(android.R.string.ok),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                dismiss();
                                target.onEditCertificateOk();
                            }
                        });
                builder.setNegativeButton(
                        context.getString(android.R.string.cancel),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                dismiss();
                                target.onErrorDialogEditButton();
                            }
                        });

            } else {
                // "Normal" error - just use a single "Edit details" button.
                builder.setPositiveButton(
                        context.getString(R.string.account_setup_failed_dlg_edit_details_action),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                dismiss();
                                target.onErrorDialogEditButton();
                            }
                        });
            }

            return builder.create();
        }

    }

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
    public static class SecurityRequiredDialog extends DialogFragment {
        @SuppressWarnings("hiding")
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
                .setIconAttribute(android.R.attr.alertDialogIcon)
                .setTitle(context.getString(R.string.account_setup_security_required_title))
                .setMessage(context.getString(
                        R.string.account_setup_security_policies_required_fmt, hostName))
                .setCancelable(true)
                .setPositiveButton(
                        context.getString(R.string.okay_action),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                dismiss();
                                target.onSecurityRequiredDialogResultOk(true);
                            }
                        })
                .setNegativeButton(
                        context.getString(R.string.cancel_action),
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {
                                dismiss();
                                target.onSecurityRequiredDialogResultOk(false);
                            }
                        })
                 .create();
        }

    }

}
