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

package com.android.email.mail.transport;

import com.android.email.mail.Sender;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;

import android.content.Context;

/**
 * Our Exchange service does not use the sender/store model.  This class exists for exactly one
 * purpose, which is to return "null" for getSettingActivityClass().
 */
public class ExchangeSender extends Sender {

    /**
     * Factory method.
     */
    public static Sender newInstance(Account account, Context context) throws MessagingException {
        return new ExchangeSender(context, account);
    }

    private ExchangeSender(Context context, Account account) {
    }

    @Override
    public void close() {
    }

    @Override
    public void open() {
    }

    @Override
    public void sendMessage(long messageId) {
    }

    /**
     * Get class of SettingActivity for this Sender class.
     * @return Activity class that has class method actionEditOutgoingSettings(), or null if
     * outgoing settings should not be presented (e.g. they're handled by the incoming settings
     * screen).
     */
    @Override
    public Class<? extends android.app.Activity> getSettingActivityClass() {
        return null;
    }

}
