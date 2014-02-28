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

import android.app.Fragment;
import android.app.LoaderManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Loader;
import android.os.Bundle;
import android.os.Handler;

import com.android.email.provider.AccountBackupRestore;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent;
import com.android.mail.ui.MailAsyncTaskLoader;

public class AccountFinalizeFragment extends Fragment {
    public static final String TAG = "AccountFinalizeFragment";

    private static final String ACCOUNT_TAG = "account";

    private static final int FINAL_ACCOUNT_TASK_LOADER_ID = 0;

    private Context mAppContext;
    private final Handler mHandler = new Handler();

    public interface Callback {
        void onAccountFinalizeFragmentComplete();
    }

    public AccountFinalizeFragment() {}

    public static AccountFinalizeFragment newInstance(Account account) {
        final AccountFinalizeFragment f = new AccountFinalizeFragment();
        final Bundle args = new Bundle(1);
        args.putParcelable(ACCOUNT_TAG, account);
        f.setArguments(args);
        return f;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mAppContext = getActivity().getApplicationContext();

        setRetainInstance(true);
    }

    @Override
    public void onResume() {
        super.onResume();

        getLoaderManager().initLoader(FINAL_ACCOUNT_TASK_LOADER_ID, getArguments(),
                new LoaderManager.LoaderCallbacks<Boolean>() {
                    @Override
                    public Loader<Boolean> onCreateLoader(int id, Bundle args) {
                        final Account accountArg = args.getParcelable(ACCOUNT_TAG);
                        return new FinalSetupTaskLoader(mAppContext, accountArg);
                    }

                    @Override
                    public void onLoadFinished(Loader<Boolean> loader, Boolean success) {
                        if (!success) {
                            return;
                        }
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isResumed()) {
                                    Callback activity = (Callback) getActivity();
                                    activity.onAccountFinalizeFragmentComplete();
                                }
                            }
                        });

                    }

                    @Override
                    public void onLoaderReset(Loader<Boolean> loader) {}
                });
    }

    /**
     * Final account setup work is handled in this Loader:
     *   Commit final values to provider
     *   Trigger account backup
     */
    private static class FinalSetupTaskLoader extends MailAsyncTaskLoader<Boolean> {

        private final Account mAccount;

        public FinalSetupTaskLoader(Context context, Account account) {
            super(context);
            mAccount = account;
        }

        Account getAccount() {
            return mAccount;
        }

        @Override
        public Boolean loadInBackground() {
            // Update the account in the database
            final ContentValues cv = new ContentValues();
            cv.put(EmailContent.AccountColumns.DISPLAY_NAME, mAccount.getDisplayName());
            cv.put(EmailContent.AccountColumns.SENDER_NAME, mAccount.getSenderName());
            mAccount.update(getContext(), cv);

            // Update the backup (side copy) of the accounts
            AccountBackupRestore.backup(getContext());

            return true;
        }

        @Override
        protected void onDiscardResult(Boolean result) {}
    }
}
