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

package com.android.email.activity;

import com.android.email.provider.EmailContent.Account;

import android.content.AsyncTaskLoader;
import android.content.Context;

/**
 * A Loader to load the default account id asynchronously.
 *
 * TODO Test it.
 */
public class DefaultAccountLoader extends AsyncTaskLoader<Long> {
    public DefaultAccountLoader(Context context) {
        super(context);
    }

    @Override
    public Long loadInBackground() {
        return Account.getDefaultAccountId(getContext());
    }

    @Override
    public void destroy() {
        stopLoading();
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
}
