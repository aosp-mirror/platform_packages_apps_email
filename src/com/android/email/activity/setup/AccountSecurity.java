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
import android.app.FragmentManager;
import android.app.LoaderManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Loader;
import android.content.res.Resources;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;

import com.android.email.R;
import com.android.email.SecurityPolicy;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Policy;
import com.android.mail.ui.MailAsyncTaskLoader;
import com.android.mail.utils.LogUtils;

/**
 * Psuedo-activity (no UI) to bootstrap the user up to a higher desired security level.  This
 * bootstrap requires the following steps.
 *
 * 1.  Confirm the account of interest has any security policies defined - exit early if not
 * 2.  If not actively administrating the device, ask Device Policy Manager to start that
 * 3.  When we are actively administrating, check current policies and see if they're sufficient
 * 4.  If not, set policies
 * 5.  If necessary, request for user to update device password
 * 6.  If necessary, request for user to activate device encryption
 */
public class AccountSecurity extends Activity {
    private static final String TAG = "Email/AccountSecurity";

    private static final boolean DEBUG = false;  // Don't ship with this set to true

    private static final String EXTRA_ACCOUNT_ID = "ACCOUNT_ID";
    private static final String EXTRA_SHOW_DIALOG = "SHOW_DIALOG";
    private static final String EXTRA_PASSWORD_EXPIRING = "EXPIRING";
    private static final String EXTRA_PASSWORD_EXPIRED = "EXPIRED";

    private static final String SAVESTATE_INITIALIZED_TAG = "initialized";
    private static final String SAVESTATE_TRIED_ADD_ADMINISTRATOR_TAG = "triedAddAdministrator";
    private static final String SAVESTATE_TRIED_SET_PASSWORD_TAG = "triedSetpassword";
    private static final String SAVESTATE_TRIED_SET_ENCRYPTION_TAG = "triedSetEncryption";
    private static final String SAVESTATE_ACCOUNT_TAG = "account";

    private static final int REQUEST_ENABLE = 1;
    private static final int REQUEST_PASSWORD = 2;
    private static final int REQUEST_ENCRYPTION = 3;

    private boolean mTriedAddAdministrator;
    private boolean mTriedSetPassword;
    private boolean mTriedSetEncryption;

    private Account mAccount;

    protected boolean mInitialized;

    private Handler mHandler;
    private boolean mActivityResumed;

    private static final int ACCOUNT_POLICY_LOADER_ID = 0;
    private AccountAndPolicyLoaderCallbacks mAPLoaderCallbacks;
    private Bundle mAPLoaderArgs;

    /**
     * Used for generating intent for this activity (which is intended to be launched
     * from a notification.)
     *
     * @param context Calling context for building the intent
     * @param accountId The account of interest
     * @param showDialog If true, a simple warning dialog will be shown before kicking off
     * the necessary system settings.  Should be true anywhere the context of the security settings
     * is not clear (e.g. any time after the account has been set up).
     * @return an Intent which can be used to view that account
     */
    public static Intent actionUpdateSecurityIntent(Context context, long accountId,
            boolean showDialog) {
        Intent intent = new Intent(context, AccountSecurity.class);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(EXTRA_SHOW_DIALOG, showDialog);
        return intent;
    }

    /**
     * Used for generating intent for this activity (which is intended to be launched
     * from a notification.)  This is a special mode of this activity which exists only
     * to give the user a dialog (for context) about a device pin/password expiration event.
     */
    public static Intent actionDevicePasswordExpirationIntent(Context context, long accountId,
            boolean expired) {
        Intent intent = new ForwardingIntent(context, AccountSecurity.class);
        intent.putExtra(EXTRA_ACCOUNT_ID, accountId);
        intent.putExtra(expired ? EXTRA_PASSWORD_EXPIRED : EXTRA_PASSWORD_EXPIRING, true);
        return intent;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mHandler = new Handler();

        final Intent i = getIntent();
        final long accountId = i.getLongExtra(EXTRA_ACCOUNT_ID, -1);
        final SecurityPolicy security = SecurityPolicy.getInstance(this);
        security.clearNotification();
        if (accountId == -1) {
            finish();
            return;
        }

        if (savedInstanceState != null) {
            mInitialized = savedInstanceState.getBoolean(SAVESTATE_INITIALIZED_TAG, false);

            mTriedAddAdministrator =
                    savedInstanceState.getBoolean(SAVESTATE_TRIED_ADD_ADMINISTRATOR_TAG, false);
            mTriedSetPassword =
                    savedInstanceState.getBoolean(SAVESTATE_TRIED_SET_PASSWORD_TAG, false);
            mTriedSetEncryption =
                    savedInstanceState.getBoolean(SAVESTATE_TRIED_SET_ENCRYPTION_TAG, false);

            mAccount = savedInstanceState.getParcelable(SAVESTATE_ACCOUNT_TAG);
        }

        if (!mInitialized) {
            startAccountAndPolicyLoader(i.getExtras());
        }
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SAVESTATE_INITIALIZED_TAG, mInitialized);

        outState.putBoolean(SAVESTATE_TRIED_ADD_ADMINISTRATOR_TAG, mTriedAddAdministrator);
        outState.putBoolean(SAVESTATE_TRIED_SET_PASSWORD_TAG, mTriedSetPassword);
        outState.putBoolean(SAVESTATE_TRIED_SET_ENCRYPTION_TAG, mTriedSetEncryption);

        outState.putParcelable(SAVESTATE_ACCOUNT_TAG, mAccount);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mActivityResumed = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        mActivityResumed = true;
        tickleAccountAndPolicyLoader();
    }

    protected boolean isActivityResumed() {
        return mActivityResumed;
    }

    private void tickleAccountAndPolicyLoader() {
        // If we're already initialized we don't need to tickle.
        if (!mInitialized) {
            getLoaderManager().initLoader(ACCOUNT_POLICY_LOADER_ID, mAPLoaderArgs,
                    mAPLoaderCallbacks);
        }
    }

    private void startAccountAndPolicyLoader(final Bundle args) {
        mAPLoaderArgs = args;
        mAPLoaderCallbacks = new AccountAndPolicyLoaderCallbacks();
        tickleAccountAndPolicyLoader();
    }

    private class AccountAndPolicyLoaderCallbacks
            implements LoaderManager.LoaderCallbacks<Account> {
        @Override
        public Loader<Account> onCreateLoader(final int id, final Bundle args) {
            final long accountId = args.getLong(EXTRA_ACCOUNT_ID, -1);
            final boolean showDialog = args.getBoolean(EXTRA_SHOW_DIALOG, false);
            final boolean passwordExpiring =
                    args.getBoolean(EXTRA_PASSWORD_EXPIRING, false);
            final boolean passwordExpired =
                    args.getBoolean(EXTRA_PASSWORD_EXPIRED, false);

            return new AccountAndPolicyLoader(getApplicationContext(), accountId,
                    showDialog, passwordExpiring, passwordExpired);
        }

        @Override
        public void onLoadFinished(final Loader<Account> loader, final Account account) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    final AccountSecurity activity = AccountSecurity.this;
                    if (!activity.isActivityResumed()) {
                        return;
                    }

                    if (account == null || (account.mPolicyKey != 0 && account.mPolicy == null)) {
                        activity.finish();
                        LogUtils.d(TAG, "could not load account or policy in AccountSecurity");
                        return;
                    }

                    if (!activity.mInitialized) {
                        activity.mInitialized = true;

                        final AccountAndPolicyLoader apLoader = (AccountAndPolicyLoader) loader;
                        activity.completeCreate(account, apLoader.mShowDialog,
                                apLoader.mPasswordExpiring, apLoader.mPasswordExpired);
                    }
                }
            });
        }

        @Override
        public void onLoaderReset(Loader<Account> loader) {}
    }

    private static class AccountAndPolicyLoader extends MailAsyncTaskLoader<Account> {
        private final long mAccountId;
        public final boolean mShowDialog;
        public final boolean mPasswordExpiring;
        public final boolean mPasswordExpired;

        private final Context mContext;

        AccountAndPolicyLoader(final Context context, final long accountId,
                final boolean showDialog, final boolean passwordExpiring,
                final boolean passwordExpired) {
            super(context);
            mContext = context;
            mAccountId = accountId;
            mShowDialog = showDialog;
            mPasswordExpiring = passwordExpiring;
            mPasswordExpired = passwordExpired;
        }

        @Override
        public Account loadInBackground() {
            final Account account = Account.restoreAccountWithId(mContext, mAccountId);
            if (account == null) {
                return null;
            }

            final long policyId = account.mPolicyKey;
            if (policyId != 0) {
                account.mPolicy = Policy.restorePolicyWithId(mContext, policyId);
            }

            account.getOrCreateHostAuthRecv(mContext);

            return account;
        }

        @Override
        protected void onDiscardResult(Account result) {}
    }

    protected void completeCreate(final Account account, final boolean showDialog,
            final boolean passwordExpiring, final boolean passwordExpired) {
        mAccount = account;

        // Special handling for password expiration events
        if (passwordExpiring || passwordExpired) {
            FragmentManager fm = getFragmentManager();
            if (fm.findFragmentByTag("password_expiration") == null) {
                PasswordExpirationDialog dialog =
                    PasswordExpirationDialog.newInstance(mAccount.getDisplayName(),
                            passwordExpired);
                if (MailActivityEmail.DEBUG || DEBUG) {
                    LogUtils.d(TAG, "Showing password expiration dialog");
                }
                dialog.show(fm, "password_expiration");
            }
            return;
        }
        // Otherwise, handle normal security settings flow
        if (mAccount.mPolicyKey != 0) {
            // This account wants to control security
            if (showDialog) {
                // Show dialog first, unless already showing (e.g. after rotation)
                FragmentManager fm = getFragmentManager();
                if (fm.findFragmentByTag("security_needed") == null) {
                    SecurityNeededDialog dialog =
                        SecurityNeededDialog.newInstance(mAccount.getDisplayName());
                    if (MailActivityEmail.DEBUG || DEBUG) {
                        LogUtils.d(TAG, "Showing security needed dialog");
                    }
                    dialog.show(fm, "security_needed");
                }
            } else {
                // Go directly to security settings
                tryAdvanceSecurity(mAccount);
            }
            return;
        }
        finish();
    }

    /**
     * After any of the activities return, try to advance to the "next step"
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        tryAdvanceSecurity(mAccount);
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Walk the user through the required steps to become an active administrator and with
     * the requisite security settings for the given account.
     *
     * These steps will be repeated each time we return from a given attempt (e.g. asking the
     * user to choose a device pin/password).  In a typical activation, we may repeat these
     * steps a few times.  It may go as far as step 5 (password) or step 6 (encryption), but it
     * will terminate when step 2 (isActive()) succeeds.
     *
     * If at any point we do not advance beyond a given user step, (e.g. the user cancels
     * instead of setting a password) we simply repost the security notification, and exit.
     * We never want to loop here.
     */
    private void tryAdvanceSecurity(Account account) {
        SecurityPolicy security = SecurityPolicy.getInstance(this);
        // Step 1.  Check if we are an active device administrator, and stop here to activate
        if (!security.isActiveAdmin()) {
            if (mTriedAddAdministrator) {
                if (MailActivityEmail.DEBUG || DEBUG) {
                    LogUtils.d(TAG, "Not active admin: repost notification");
                }
                repostNotification(account, security);
                finish();
            } else {
                mTriedAddAdministrator = true;
                // retrieve name of server for the format string
                final HostAuth hostAuth = account.mHostAuthRecv;
                if (hostAuth == null) {
                    if (MailActivityEmail.DEBUG || DEBUG) {
                        LogUtils.d(TAG, "No HostAuth: repost notification");
                    }
                    repostNotification(account, security);
                    finish();
                } else {
                    if (MailActivityEmail.DEBUG || DEBUG) {
                        LogUtils.d(TAG, "Not active admin: post initial notification");
                    }
                    // try to become active - must happen here in activity, to get result
                    Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                    intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                            security.getAdminComponent());
                    intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            this.getString(R.string.account_security_policy_explanation_fmt,
                                    hostAuth.mAddress));
                    startActivityForResult(intent, REQUEST_ENABLE);
                }
            }
            return;
        }

        // Step 2.  Check if the current aggregate security policy is being satisfied by the
        // DevicePolicyManager (the current system security level).
        if (security.isActive(null)) {
            if (MailActivityEmail.DEBUG || DEBUG) {
                LogUtils.d(TAG, "Security active; clear holds");
            }
            Account.clearSecurityHoldOnAllAccounts(this);
            security.syncAccount(account);
            security.clearNotification();
            finish();
            return;
        }

        // Step 3.  Try to assert the current aggregate security requirements with the system.
        security.setActivePolicies();

        // Step 4.  Recheck the security policy, and determine what changes are needed (if any)
        // to satisfy the requirements.
        int inactiveReasons = security.getInactiveReasons(null);

        // Step 5.  If password is needed, try to have the user set it
        if ((inactiveReasons & SecurityPolicy.INACTIVE_NEED_PASSWORD) != 0) {
            if (mTriedSetPassword) {
                if (MailActivityEmail.DEBUG || DEBUG) {
                    LogUtils.d(TAG, "Password needed; repost notification");
                }
                repostNotification(account, security);
                finish();
            } else {
                if (MailActivityEmail.DEBUG || DEBUG) {
                    LogUtils.d(TAG, "Password needed; request it via DPM");
                }
                mTriedSetPassword = true;
                // launch the activity to have the user set a new password.
                Intent intent = new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD);
                startActivityForResult(intent, REQUEST_PASSWORD);
            }
            return;
        }

        // Step 6.  If encryption is needed, try to have the user set it
        if ((inactiveReasons & SecurityPolicy.INACTIVE_NEED_ENCRYPTION) != 0) {
            if (mTriedSetEncryption) {
                if (MailActivityEmail.DEBUG || DEBUG) {
                    LogUtils.d(TAG, "Encryption needed; repost notification");
                }
                repostNotification(account, security);
                finish();
            } else {
                if (MailActivityEmail.DEBUG || DEBUG) {
                    LogUtils.d(TAG, "Encryption needed; request it via DPM");
                }
                mTriedSetEncryption = true;
                // launch the activity to start up encryption.
                Intent intent = new Intent(DevicePolicyManager.ACTION_START_ENCRYPTION);
                startActivityForResult(intent, REQUEST_ENCRYPTION);
            }
            return;
        }

        // Step 7.  No problems were found, so clear holds and exit
        if (MailActivityEmail.DEBUG || DEBUG) {
            LogUtils.d(TAG, "Policies enforced; clear holds");
        }
        Account.clearSecurityHoldOnAllAccounts(this);
        security.syncAccount(account);
        security.clearNotification();
        finish();
    }

    /**
     * Mark an account as not-ready-for-sync and post a notification to bring the user back here
     * eventually.
     */
    private static void repostNotification(final Account account, final SecurityPolicy security) {
        if (account == null) return;
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                security.policiesRequired(account.mId);
                return null;
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Dialog briefly shown in some cases, to indicate the user that a security update is needed.
     * If the user clicks OK, we proceed into the "tryAdvanceSecurity" flow.  If the user cancels,
     * we repost the notification and finish() the activity.
     */
    public static class SecurityNeededDialog extends DialogFragment
            implements DialogInterface.OnClickListener {
        private static final String BUNDLE_KEY_ACCOUNT_NAME = "account_name";

        // Public no-args constructor needed for fragment re-instantiation
        public SecurityNeededDialog() {}

        /**
         * Create a new dialog.
         */
        public static SecurityNeededDialog newInstance(String accountName) {
            final SecurityNeededDialog dialog = new SecurityNeededDialog();
            Bundle b = new Bundle();
            b.putString(BUNDLE_KEY_ACCOUNT_NAME, accountName);
            dialog.setArguments(b);
            return dialog;
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final String accountName = getArguments().getString(BUNDLE_KEY_ACCOUNT_NAME);

            final Context context = getActivity();
            final Resources res = context.getResources();
            final AlertDialog.Builder b = new AlertDialog.Builder(context);
            b.setTitle(R.string.account_security_dialog_title);
            b.setIconAttribute(android.R.attr.alertDialogIcon);
            b.setMessage(res.getString(R.string.account_security_dialog_content_fmt, accountName));
            b.setPositiveButton(android.R.string.ok, this);
            b.setNegativeButton(android.R.string.cancel, this);
            if (MailActivityEmail.DEBUG || DEBUG) {
                LogUtils.d(TAG, "Posting security needed dialog");
            }
            return b.create();
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            dismiss();
            AccountSecurity activity = (AccountSecurity) getActivity();
            if (activity.mAccount == null) {
                // Clicked before activity fully restored - probably just monkey - exit quickly
                activity.finish();
                return;
            }
            switch (which) {
                case DialogInterface.BUTTON_POSITIVE:
                    if (MailActivityEmail.DEBUG || DEBUG) {
                        LogUtils.d(TAG, "User accepts; advance to next step");
                    }
                    activity.tryAdvanceSecurity(activity.mAccount);
                    break;
                case DialogInterface.BUTTON_NEGATIVE:
                    if (MailActivityEmail.DEBUG || DEBUG) {
                        LogUtils.d(TAG, "User declines; repost notification");
                    }
                    AccountSecurity.repostNotification(
                            activity.mAccount, SecurityPolicy.getInstance(activity));
                    activity.finish();
                    break;
            }
        }
    }

    /**
     * Dialog briefly shown in some cases, to indicate the user that the PIN/Password is expiring
     * or has expired.  If the user clicks OK, we launch the password settings screen.
     */
    public static class PasswordExpirationDialog extends DialogFragment
            implements DialogInterface.OnClickListener {
        private static final String BUNDLE_KEY_ACCOUNT_NAME = "account_name";
        private static final String BUNDLE_KEY_EXPIRED = "expired";

        /**
         * Create a new dialog.
         */
        public static PasswordExpirationDialog newInstance(String accountName, boolean expired) {
            final PasswordExpirationDialog dialog = new PasswordExpirationDialog();
            Bundle b = new Bundle();
            b.putString(BUNDLE_KEY_ACCOUNT_NAME, accountName);
            b.putBoolean(BUNDLE_KEY_EXPIRED, expired);
            dialog.setArguments(b);
            return dialog;
        }

        // Public no-args constructor needed for fragment re-instantiation
        public PasswordExpirationDialog() {}

        /**
         * Note, this actually creates two slightly different dialogs (for expiring vs. expired)
         */
        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final String accountName = getArguments().getString(BUNDLE_KEY_ACCOUNT_NAME);
            final boolean expired = getArguments().getBoolean(BUNDLE_KEY_EXPIRED);
            final int titleId = expired
                    ? R.string.password_expired_dialog_title
                    : R.string.password_expire_warning_dialog_title;
            final int contentId = expired
                    ? R.string.password_expired_dialog_content_fmt
                    : R.string.password_expire_warning_dialog_content_fmt;

            final Context context = getActivity();
            final Resources res = context.getResources();
            return new AlertDialog.Builder(context)
                    .setTitle(titleId)
                    .setIconAttribute(android.R.attr.alertDialogIcon)
                    .setMessage(res.getString(contentId, accountName))
                    .setPositiveButton(android.R.string.ok, this)
                    .setNegativeButton(android.R.string.cancel, this)
                    .create();
        }

        @Override
        public void onClick(DialogInterface dialog, int which) {
            dismiss();
            AccountSecurity activity = (AccountSecurity) getActivity();
            if (which == DialogInterface.BUTTON_POSITIVE) {
                Intent intent = new Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD);
                activity.startActivity(intent);
            }
            activity.finish();
        }
    }
}
