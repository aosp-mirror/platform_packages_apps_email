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

import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;

import android.content.AsyncTaskLoader;
import android.content.Context;


/**
 * Loader to load {@link Mailbox} and {@link Account}.
 */
public class MailboxAccountLoader extends AsyncTaskLoader<MailboxAccountLoader.Result> {
    public static class Result {
        public Account mAccount;
        public Mailbox mMailbox;

        public boolean isFound() {
            return (mAccount != null) && (mMailbox != null);
        }
    }

    private final Context mContext;
    private final long mMailboxId;

    public MailboxAccountLoader(Context context, long mailboxId) {
        super(context);
        mContext = context;
        mMailboxId = mailboxId;
    }

    @Override
    public Result loadInBackground() {
        Result result = new Result();
        if (mMailboxId < 0) {
            // Magic mailbox.
        } else {
            result.mMailbox = Mailbox.restoreMailboxWithId(mContext, mMailboxId);
            if (result.mMailbox != null) {
                result.mAccount = Account.restoreAccountWithId(mContext,
                        result.mMailbox.mAccountKey);
            }
            if (result.mAccount == null) { // account removed??
                result.mMailbox = null;
            }
        }
        return result;
    }

    @Override
    public void startLoading() {
        cancelLoad();
        forceLoad();
    }

    @Override
    public void stopLoading() {
        cancelLoad();
    }

    @Override
    public void destroy() {
        stopLoading();
    }
}
