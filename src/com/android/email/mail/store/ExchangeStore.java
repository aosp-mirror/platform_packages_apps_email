/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.email.mail.store;

import android.content.Context;

import com.android.email.mail.Store;
import com.android.email.service.EmailServiceUtils;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.service.IEmailService;

/**
 * Our Exchange service does not use the sender/store model.
 */
public class ExchangeStore extends ServiceStore {

    /**
     * Static named constructor.
     */
    public static Store newInstance(Account account, Context context) throws MessagingException {
        return new ExchangeStore(account, context);
    }

    /**
     * Creates a new store for the given account.
     */
    public ExchangeStore(Account account, Context context) throws MessagingException {
        super(account, context);
    }

    @Override
    public Class<? extends android.app.Activity> getSettingActivityClass() {
        return com.android.email.activity.setup.AccountSetupExchange.class;
    }

    @Override
    protected IEmailService getService() {
        return EmailServiceUtils.getExchangeService(mContext, null);
    }
}
