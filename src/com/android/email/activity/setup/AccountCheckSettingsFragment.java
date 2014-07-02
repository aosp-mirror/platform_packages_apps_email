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
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;

import com.android.email.R;
import com.android.email.mail.Sender;
import com.android.email.mail.Store;
import com.android.email.service.EmailServiceUtils;
import com.android.email.service.EmailServiceUtils.EmailServiceInfo;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Policy;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.HostAuthCompat;
import com.android.emailcommon.utility.Utility;
import com.android.mail.utils.LogUtils;

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

    public final static String TAG = "AccountCheckStgFrag";

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

    // Args
    private final static String ARGS_MODE = "mode";

    private int mMode;

    // Support for UI
    private boolean mAttached;
    private boolean mPaused = false;
    private MessagingException mProgressException;

    // Support for AsyncTask and account checking
    AccountCheckTask mAccountCheckTask;

    // Result codes returned by onCheckSettingsAutoDiscoverComplete.
    /** AutoDiscover completed successfully with server setup data */
    public final static int AUTODISCOVER_OK = 0;
    /** AutoDiscover completed with no data (no server or AD not supported) */
    public final static int AUTODISCOVER_NO_DATA = 1;
    /** AutoDiscover reported authentication error */
    public final static int AUTODISCOVER_AUTHENTICATION = 2;

    /**
     * Callback interface for any target or activity doing account check settings
     */
    public interface Callback {
        /**
         * Called when CheckSettings completed
         */
        void onCheckSettingsComplete();

        /**
         * Called when we determine that a security policy will need to be installed
         * @param hostName Passed back from the MessagingException
         */
        void onCheckSettingsSecurityRequired(String hostName);

        /**
         * Called when we receive an error while validating the account
         * @param reason from
         *      {@link CheckSettingsErrorDialogFragment#getReasonFromException(MessagingException)}
         * @param message from
         *      {@link CheckSettingsErrorDialogFragment#getErrorString(Context, MessagingException)}
         */
        void onCheckSettingsError(int reason, String message);

        /**
         * Called when autodiscovery completes.
         * @param result autodiscovery result code - success is AUTODISCOVER_OK
         */
        void onCheckSettingsAutoDiscoverComplete(int result);
    }

    // Public no-args constructor needed for fragment re-instantiation
    public AccountCheckSettingsFragment() {}

    /**
     * Create a retained, invisible fragment that checks accounts
     *
     * @param mode incoming or outgoing
     */
    public static AccountCheckSettingsFragment newInstance(int mode) {
        final AccountCheckSettingsFragment f = new AccountCheckSettingsFragment();
        final Bundle b = new Bundle(1);
        b.putInt(ARGS_MODE, mode);
        f.setArguments(b);
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
        mMode = getArguments().getInt(ARGS_MODE);
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
            final SetupDataFragment.SetupDataContainer container =
                    (SetupDataFragment.SetupDataContainer) getActivity();
            // TODO: don't pass in the whole SetupDataFragment
            mAccountCheckTask = (AccountCheckTask)
                    new AccountCheckTask(getActivity().getApplicationContext(), this, mMode,
                            container.getSetupData())
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    /**
     * When resuming, restart the progress/error UI if necessary by re-reporting previous values
     */
    @Override
    public void onResume() {
        super.onResume();
        mPaused = false;

        if (mState != STATE_START) {
            reportProgress(mState, mProgressException);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        mPaused = true;
    }

    /**
     * This is called when the fragment is going away.  It is NOT called
     * when the fragment is being propagated between activity instances.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mAccountCheckTask != null) {
            Utility.cancelTaskInterrupt(mAccountCheckTask);
            mAccountCheckTask = null;
        }
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
        if (mAttached && !mPaused) {
            final FragmentManager fm = getFragmentManager();

            switch (newState) {
                case STATE_CHECK_OK:
                    // immediately terminate, clean up, and report back
                    getCallbackTarget().onCheckSettingsComplete();
                    break;
                case STATE_CHECK_SHOW_SECURITY:
                    // report that we need to accept a security policy
                    String hostName = ex.getMessage();
                    if (hostName != null) {
                        hostName = hostName.trim();
                    }
                    getCallbackTarget().onCheckSettingsSecurityRequired(hostName);
                    break;
                case STATE_CHECK_ERROR:
                case STATE_AUTODISCOVER_AUTH_DIALOG:
                    // report that we had an error
                    final int reason =
                            CheckSettingsErrorDialogFragment.getReasonFromException(ex);
                    final String errorMessage =
                            CheckSettingsErrorDialogFragment.getErrorString(getActivity(), ex);
                    getCallbackTarget().onCheckSettingsError(reason, errorMessage);
                    break;
                case STATE_AUTODISCOVER_RESULT:
                    final HostAuth autoDiscoverResult = ((AutoDiscoverResults) ex).mHostAuth;
                    // report autodiscover results back to target fragment or activity
                    getCallbackTarget().onCheckSettingsAutoDiscoverComplete(
                            (autoDiscoverResult != null) ? AUTODISCOVER_OK : AUTODISCOVER_NO_DATA);
                    break;
                default:
                    // Display a normal progress message
                    CheckSettingsProgressDialogFragment checkingDialog =
                            (CheckSettingsProgressDialogFragment)
                                    fm.findFragmentByTag(CheckSettingsProgressDialogFragment.TAG);

                    if (checkingDialog != null) {
                        checkingDialog.updateProgress(mState);
                    }
                    break;
            }
        }
    }

    /**
     * Find the callback target, either a target fragment or the activity
     */
    private Callback getCallbackTarget() {
        final Fragment target = getTargetFragment();
        if (target instanceof Callback) {
            return (Callback) target;
        }
        Activity activity = getActivity();
        if (activity instanceof Callback) {
            return (Callback) activity;
        }
        throw new IllegalStateException();
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
    private static class AccountCheckTask extends AsyncTask<Void, Integer, MessagingException> {
        final Context mContext;
        final AccountCheckSettingsFragment mCallback;
        final int mMode;
        final SetupDataFragment mSetupData;
        final Account mAccount;
        final String mStoreHost;
        final String mCheckEmail;
        final String mCheckPassword;

        /**
         * Create task and parameterize it
         * @param context application context object
         * @param mode bits request operations
         * @param setupData {@link SetupDataFragment} holding values to be checked
         */
        public AccountCheckTask(Context context, AccountCheckSettingsFragment callback, int mode,
                SetupDataFragment setupData) {
            mContext = context;
            mCallback = callback;
            mMode = mode;
            mSetupData = setupData;
            mAccount = setupData.getAccount();
            mStoreHost = mAccount.mHostAuthRecv.mAddress;
            mCheckEmail = mAccount.mEmailAddress;
            mCheckPassword = mAccount.mHostAuthRecv.mPassword;
        }

        @Override
        protected MessagingException doInBackground(Void... params) {
            try {
                if ((mMode & SetupDataFragment.CHECK_AUTODISCOVER) != 0) {
                    if (isCancelled()) return null;
                    LogUtils.d(Logging.LOG_TAG, "Begin auto-discover for %s", mCheckEmail);
                    publishProgress(STATE_CHECK_AUTODISCOVER);
                    final Store store = Store.getInstance(mAccount, mContext);
                    final Bundle result = store.autoDiscover(mContext, mCheckEmail, mCheckPassword);
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
                    if (errorCode == MessagingException.AUTODISCOVER_AUTHENTICATION_FAILED) {
                        return new AutoDiscoverResults(true, null);
                    } else if (errorCode != MessagingException.NO_ERROR) {
                        return new AutoDiscoverResults(false, null);
                    } else {
                        final HostAuthCompat hostAuthCompat =
                            result.getParcelable(EmailServiceProxy.AUTO_DISCOVER_BUNDLE_HOST_AUTH);
                        HostAuth serverInfo = null;
                        if (hostAuthCompat != null) {
                            serverInfo = hostAuthCompat.toHostAuth();
                        }
                        return new AutoDiscoverResults(false, serverInfo);
                    }
                }

                // Check Incoming Settings
                if ((mMode & SetupDataFragment.CHECK_INCOMING) != 0) {
                    if (isCancelled()) return null;
                    LogUtils.d(Logging.LOG_TAG, "Begin check of incoming email settings");
                    publishProgress(STATE_CHECK_INCOMING);
                    final Store store = Store.getInstance(mAccount, mContext);
                    final Bundle bundle = store.checkSettings();
                    if (bundle == null) {
                        return new MessagingException(MessagingException.UNSPECIFIED_EXCEPTION);
                    }
                    mAccount.mProtocolVersion = bundle.getString(
                            EmailServiceProxy.VALIDATE_BUNDLE_PROTOCOL_VERSION);
                    int resultCode = bundle.getInt(EmailServiceProxy.VALIDATE_BUNDLE_RESULT_CODE);
                    final String redirectAddress = bundle.getString(
                            EmailServiceProxy.VALIDATE_BUNDLE_REDIRECT_ADDRESS, null);
                    if (redirectAddress != null) {
                        mAccount.mHostAuthRecv.mAddress = redirectAddress;
                    }
                    // Only show "policies required" if this is a new account setup
                    if (resultCode == MessagingException.SECURITY_POLICIES_REQUIRED &&
                            mAccount.isSaved()) {
                        resultCode = MessagingException.NO_ERROR;
                    }
                    if (resultCode == MessagingException.SECURITY_POLICIES_REQUIRED) {
                        mSetupData.setPolicy((Policy)bundle.getParcelable(
                                EmailServiceProxy.VALIDATE_BUNDLE_POLICY_SET));
                        return new MessagingException(resultCode, mStoreHost);
                    } else if (resultCode == MessagingException.SECURITY_POLICIES_UNSUPPORTED) {
                        final Policy policy = bundle.getParcelable(
                                EmailServiceProxy.VALIDATE_BUNDLE_POLICY_SET);
                        final String unsupported = policy.mProtocolPoliciesUnsupported;
                        final String[] data =
                                unsupported.split("" + Policy.POLICY_STRING_DELIMITER);
                        return new MessagingException(resultCode, mStoreHost, data);
                    } else if (resultCode != MessagingException.NO_ERROR) {
                        final String errorMessage;
                        errorMessage = bundle.getString(
                                EmailServiceProxy.VALIDATE_BUNDLE_ERROR_MESSAGE);
                        return new MessagingException(resultCode, errorMessage);
                    }
                }

                final String protocol = mAccount.mHostAuthRecv.mProtocol;
                final EmailServiceInfo info = EmailServiceUtils.getServiceInfo(mContext, protocol);

                // Check Outgoing Settings
                if (info.usesSmtp && (mMode & SetupDataFragment.CHECK_OUTGOING) != 0) {
                    if (isCancelled()) return null;
                    LogUtils.d(Logging.LOG_TAG, "Begin check of outgoing email settings");
                    publishProgress(STATE_CHECK_OUTGOING);
                    final Sender sender = Sender.getInstance(mContext, mAccount);
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
         * Progress reports (runs in UI thread).  This should be used for real progress only
         * (not for errors).
         */
        @Override
        protected void onProgressUpdate(Integer... progress) {
            if (isCancelled()) return;
            mCallback.reportProgress(progress[0], null);
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
                mCallback.reportProgress(STATE_CHECK_OK, null);
            } else {
                int progressState = STATE_CHECK_ERROR;
                final int exceptionType = result.getExceptionType();

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
                mCallback.reportProgress(progressState, result);
            }
        }
    }

    /**
     * Convert progress to message
     */
    protected static String getProgressString(Context context, int progress) {
        int stringId = 0;
        switch (progress) {
            case STATE_CHECK_AUTODISCOVER:
                stringId = R.string.account_setup_check_settings_retr_info_msg;
                break;
            case STATE_START:
            case STATE_CHECK_INCOMING:
                stringId = R.string.account_setup_check_settings_check_incoming_msg;
                break;
            case STATE_CHECK_OUTGOING:
                stringId = R.string.account_setup_check_settings_check_outgoing_msg;
                break;
        }
        if (stringId != 0) {
            return context.getString(stringId);
        } else {
            return null;
        }
    }

    /**
     * Convert mode to initial progress
     */
    protected static int getProgressForMode(int checkMode) {
        switch (checkMode) {
            case SetupDataFragment.CHECK_INCOMING:
                return STATE_CHECK_INCOMING;
            case SetupDataFragment.CHECK_OUTGOING:
                return STATE_CHECK_OUTGOING;
            case SetupDataFragment.CHECK_AUTODISCOVER:
                return STATE_CHECK_AUTODISCOVER;
        }
        return STATE_START;
    }
}
