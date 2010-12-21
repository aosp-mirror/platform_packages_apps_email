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

package com.android.email.data;

import com.android.email.provider.EmailContent;
import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;

import android.content.AsyncTaskLoader;
import android.content.Context;

import java.security.InvalidParameterException;


/**
 * Loader to load {@link Mailbox} and {@link Account}.
 */
public class MailboxAccountLoader extends AsyncTaskLoader<MailboxAccountLoader.Result> {
    public static class Result {
        public final boolean mIsFound;
        public final Account mAccount;
        public final Mailbox mMailbox;
        public final boolean mIsEasAccount;
        public final boolean mIsRefreshable;
        public final int mCountTotalAccounts;

        private Result(boolean found, Account account, Mailbox mailbox, boolean isEasAccount,
                boolean isRefreshable, int countTotalAccounts) {
            mIsFound = found;
            mAccount = account;
            mMailbox = mailbox;
            mIsEasAccount = isEasAccount;
            mIsRefreshable = isRefreshable;
            mCountTotalAccounts = countTotalAccounts;
        }
    }

    private final Context mContext;
    private final long mMailboxId;

    public MailboxAccountLoader(Context context, long mailboxId) {
        super(context);
        if (mailboxId == -1) {
            throw new InvalidParameterException();
        }
        mContext = context;
        mMailboxId = mailboxId;
    }

    @Override
    public Result loadInBackground() {
        boolean found = false;
        Account account = null;
        Mailbox mailbox = null;
        boolean isEasAccount = false;
        boolean isRefreshable = false;

        if (mMailboxId < 0) {
            // Magic mailbox.
            found = true;
        } else {
            mailbox = Mailbox.restoreMailboxWithId(mContext, mMailboxId);
            if (mailbox != null) {
                account = Account.restoreAccountWithId(mContext, mailbox.mAccountKey);
                if (account != null) {
                    found = true;
                    isEasAccount = account.isEasAccount(mContext) ;
                    isRefreshable = Mailbox.isRefreshable(mContext, mMailboxId);
                } else { // Account removed?
                    mailbox = null;
                }
            }
        }
        final int countAccounts = EmailContent.count(mContext, Account.CONTENT_URI);
        Result result = new Result(found, account, mailbox, isEasAccount, isRefreshable,
                countAccounts);
        return result;
    }

    @Override
    protected void onStartLoading() {
        cancelLoad();
        forceLoad();
    }

    @Override
    protected void onStopLoading() {
        cancelLoad();
    }

    @Override
    protected void onReset() {
        stopLoading();
    }
}
