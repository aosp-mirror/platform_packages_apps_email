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

import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Fragment;
import android.app.LoaderManager;
import android.content.Context;
import android.content.Intent;
import android.content.Loader;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;

import com.android.email.service.EmailServiceUtils;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.mail.preferences.AccountPreferences;
import com.android.mail.ui.MailAsyncTaskLoader;
import com.android.mail.utils.LogUtils;

import java.io.IOException;

/**
 * This retained headless fragment acts as a container for the multi-step task of creating the
 * AccountManager account and saving our account object to the database, as well as some misc
 * related background tasks.
 */
public class AccountCreationFragment extends Fragment {
    public static final String TAG = "AccountCreationFragment";

    public static final int REQUEST_CODE_ACCEPT_POLICIES = 1;

    private static final String ACCOUNT_TAG = "account";
    private static final String SYNC_EMAIL_TAG = "email";
    private static final String SYNC_CALENDAR_TAG = "calendar";
    private static final String SYNC_CONTACTS_TAG = "contacts";
    private static final String NOTIFICATIONS_TAG = "notifications";

    private static final String SAVESTATE_STAGE = "AccountCreationFragment.stage";
    private static final int STAGE_BEFORE_ACCOUNT_SECURITY = 0;
    private static final int STAGE_REFRESHING_ACCOUNT = 1;
    private static final int STAGE_WAITING_FOR_ACCOUNT_SECURITY = 2;
    private static final int STAGE_AFTER_ACCOUNT_SECURITY = 3;
    private int mStage = 0;

    private Context mAppContext;
    private final Handler mHandler;

    public interface Callback {
        void onAccountCreationFragmentComplete();
        void destroyAccountCreationFragment();
        void showCreateAccountErrorDialog();
        void setAccount(Account account);
    }

    public AccountCreationFragment() {
        mHandler = new Handler();
    }

    public static AccountCreationFragment newInstance(Account account, boolean syncEmail,
            boolean syncCalendar, boolean syncContacts, boolean enableNotifications) {
        final Bundle args = new Bundle(5);
        args.putParcelable(AccountCreationFragment.ACCOUNT_TAG, account);
        args.putBoolean(AccountCreationFragment.SYNC_EMAIL_TAG, syncEmail);
        args.putBoolean(AccountCreationFragment.SYNC_CALENDAR_TAG, syncCalendar);
        args.putBoolean(AccountCreationFragment.SYNC_CONTACTS_TAG, syncContacts);
        args.putBoolean(AccountCreationFragment.NOTIFICATIONS_TAG, enableNotifications);

        final AccountCreationFragment f = new AccountCreationFragment();
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);
        if (savedInstanceState != null) {
            mStage = savedInstanceState.getInt(SAVESTATE_STAGE);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mAppContext = getActivity().getApplicationContext();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SAVESTATE_STAGE, mStage);
    }

    @Override
    public void onResume() {
        super.onResume();

        switch (mStage) {
            case STAGE_BEFORE_ACCOUNT_SECURITY:
                kickBeforeAccountSecurityLoader();
                break;
            case STAGE_REFRESHING_ACCOUNT:
                kickRefreshingAccountLoader();
                break;
            case STAGE_WAITING_FOR_ACCOUNT_SECURITY:
                // TODO: figure out when we might get here and what to do if we do
                break;
            case STAGE_AFTER_ACCOUNT_SECURITY:
                kickAfterAccountSecurityLoader();
                break;
        }
    }

    private void kickBeforeAccountSecurityLoader() {
        final LoaderManager loaderManager = getLoaderManager();

        loaderManager.destroyLoader(STAGE_REFRESHING_ACCOUNT);
        loaderManager.destroyLoader(STAGE_AFTER_ACCOUNT_SECURITY);
        loaderManager.initLoader(STAGE_BEFORE_ACCOUNT_SECURITY, getArguments(),
                new BeforeAccountSecurityCallbacks());
    }

    private void kickRefreshingAccountLoader() {
        final LoaderManager loaderManager = getLoaderManager();

        loaderManager.destroyLoader(STAGE_BEFORE_ACCOUNT_SECURITY);
        loaderManager.destroyLoader(STAGE_AFTER_ACCOUNT_SECURITY);
        loaderManager.initLoader(STAGE_REFRESHING_ACCOUNT, getArguments(),
                new RefreshAccountCallbacks());
    }

    private void kickAfterAccountSecurityLoader() {
        final LoaderManager loaderManager = getLoaderManager();

        loaderManager.destroyLoader(STAGE_BEFORE_ACCOUNT_SECURITY);
        loaderManager.destroyLoader(STAGE_REFRESHING_ACCOUNT);
        loaderManager.initLoader(STAGE_AFTER_ACCOUNT_SECURITY, getArguments(),
                new AfterAccountSecurityCallbacks());
    }

    private class BeforeAccountSecurityCallbacks
            implements LoaderManager.LoaderCallbacks<Boolean> {
        public BeforeAccountSecurityCallbacks() {}

        @Override
        public Loader<Boolean> onCreateLoader(int id, Bundle args) {
            final Account account = args.getParcelable(ACCOUNT_TAG);
            final boolean email = args.getBoolean(SYNC_EMAIL_TAG);
            final boolean calendar = args.getBoolean(SYNC_CALENDAR_TAG);
            final boolean contacts = args.getBoolean(SYNC_CONTACTS_TAG);
            final boolean notificationsEnabled = args.getBoolean(NOTIFICATIONS_TAG);

            /**
             * Task loader returns true if we created the account, false if we bailed out.
             */
            return new MailAsyncTaskLoader<Boolean>(mAppContext) {
                @Override
                protected void onDiscardResult(Boolean result) {}

                @Override
                public Boolean loadInBackground() {
                    // Set the incomplete flag here to avoid reconciliation issues
                    account.mFlags |= Account.FLAGS_INCOMPLETE;

                    AccountSettingsUtils.commitSettings(mAppContext, account);
                    final AccountManagerFuture<Bundle> future =
                            EmailServiceUtils.setupAccountManagerAccount(mAppContext, account,
                                    email, calendar, contacts, null);

                    boolean createSuccess = false;
                    try {
                        future.getResult();
                        createSuccess = true;
                    } catch (OperationCanceledException e) {
                        LogUtils.d(LogUtils.TAG, "addAccount was canceled");
                    } catch (IOException e) {
                        LogUtils.d(LogUtils.TAG, "addAccount failed: " + e);
                    } catch (AuthenticatorException e) {
                        LogUtils.d(LogUtils.TAG, "addAccount failed: " + e);
                    }
                    if (!createSuccess) {
                        return false;
                    }
                    // We can move the notification setting to the inbox FolderPreferences
                    // later, once we know what the inbox is
                    new AccountPreferences(mAppContext, account.getEmailAddress())
                            .setDefaultInboxNotificationsEnabled(notificationsEnabled);

                    // Now that AccountManager account creation is complete, clear the
                    // INCOMPLETE flag
                    account.mFlags &= ~Account.FLAGS_INCOMPLETE;
                    AccountSettingsUtils.commitSettings(mAppContext, account);

                    return true;
                }
            };
        }

        @Override
        public void onLoadFinished(Loader<Boolean> loader, Boolean success) {
            if (success == null || !isResumed()) {
                return;
            }
            if (success) {
                mStage = STAGE_REFRESHING_ACCOUNT;
                kickRefreshingAccountLoader();
            } else {
                final Callback callback = (Callback) getActivity();
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isResumed()) {
                            return;
                        }
                        // Can't do this from within onLoadFinished
                        callback.destroyAccountCreationFragment();
                        callback.showCreateAccountErrorDialog();
                    }
                });
            }
        }

        @Override
        public void onLoaderReset(Loader<Boolean> loader) {}
    }

    private class RefreshAccountCallbacks implements LoaderManager.LoaderCallbacks<Account> {

        @Override
        public Loader<Account> onCreateLoader(int id, Bundle args) {
            final Account account = args.getParcelable(ACCOUNT_TAG);
            return new MailAsyncTaskLoader<Account>(mAppContext) {
                @Override
                protected void onDiscardResult(Account result) {}

                @Override
                public Account loadInBackground() {
                    account.refresh(mAppContext);
                    return account;
                }
            };
        }

        @Override
        public void onLoadFinished(Loader<Account> loader, Account account) {
            if (account == null || !isResumed()) {
                return;
            }

            getArguments().putParcelable(ACCOUNT_TAG, account);

            if ((account.mFlags & Account.FLAGS_SECURITY_HOLD) != 0) {
                final Intent intent = AccountSecurity
                        .actionUpdateSecurityIntent(getActivity(), account.mId, false);
                startActivityForResult(intent, REQUEST_CODE_ACCEPT_POLICIES);
                mStage = STAGE_WAITING_FOR_ACCOUNT_SECURITY;
            } else {
                mStage = STAGE_AFTER_ACCOUNT_SECURITY;
                kickAfterAccountSecurityLoader();
            }
        }

        @Override
        public void onLoaderReset(Loader<Account> loader) {}
    }

    private class AfterAccountSecurityCallbacks
            implements LoaderManager.LoaderCallbacks<Account> {
        @Override
        public Loader<Account> onCreateLoader(int id, Bundle args) {
            final Account account = args.getParcelable(ACCOUNT_TAG);
            return new MailAsyncTaskLoader<Account>(mAppContext) {
                @Override
                protected void onDiscardResult(Account result) {}

                @Override
                public Account loadInBackground() {
                    // Clear the security hold flag now
                    account.mFlags &= ~Account.FLAGS_SECURITY_HOLD;
                    AccountSettingsUtils.commitSettings(mAppContext, account);
                    // Start up services based on new account(s)
                    MailActivityEmail.setServicesEnabledSync(mAppContext);
                    EmailServiceUtils
                            .startService(mAppContext, account.mHostAuthRecv.mProtocol);
                    return account;
                }
            };
        }

        @Override
        public void onLoadFinished(final Loader<Account> loader, final Account account) {
            // Need to do this from a runnable because this triggers fragment transactions
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (account == null || !isResumed()) {
                        return;
                    }

                    // Move to final setup screen
                    Callback callback = (Callback) getActivity();
                    callback.setAccount(account);
                    callback.onAccountCreationFragmentComplete();

                    // Update the folder list (to get our starting folders, e.g. Inbox)
                    final EmailServiceProxy proxy = EmailServiceUtils
                            .getServiceForAccount(mAppContext, account.mId);
                    try {
                        proxy.updateFolderList(account.mId);
                    } catch (RemoteException e) {
                        // It's all good
                    }

                }
            });
        }

        @Override
        public void onLoaderReset(Loader<Account> loader) {}
    }

    /**
     * This is called after the AccountSecurity activity completes.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        mStage = STAGE_AFTER_ACCOUNT_SECURITY;
        // onResume() will be called immediately after this to kick the next loader
    }
}
