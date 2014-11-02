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

package com.android.email.setup;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;


public class AuthenticatorSetupIntentHelper {
    // NORMAL is the standard entry from the Email app; EAS and POP_IMAP are used when entering via
    // Settings -> Accounts
    public static final int FLOW_MODE_UNSPECIFIED = -1;
    public static final int FLOW_MODE_NORMAL = 0;
    public static final int FLOW_MODE_ACCOUNT_MANAGER = 1;
    public static final int FLOW_MODE_EDIT = 3;
    public static final int FLOW_MODE_FORCE_CREATE = 4;

    public static final int FLOW_MODE_NO_ACCOUNTS = 8;


    public static final String EXTRA_FLOW_MODE = "FLOW_MODE";
    public static final String EXTRA_FLOW_ACCOUNT_TYPE = "FLOW_ACCOUNT_TYPE";


    public static Intent actionNewAccountIntent(final Context context) {
        final Intent i = new Intent();
        i.setComponent(
                new ComponentName(context, "com.android.email.activity.setup.AccountSetupFinal"));
        i.putExtra(EXTRA_FLOW_MODE, FLOW_MODE_NORMAL);
        return i;
    }

    public static Intent actionNewAccountWithResultIntent(final Context context) {
        final Intent i = new Intent();
        i.setComponent(
                new ComponentName(context, "com.android.email.activity.setup.AccountSetupFinal"));
        i.putExtra(EXTRA_FLOW_MODE, FLOW_MODE_NO_ACCOUNTS);
        return i;
    }

    public static Intent actionGetCreateAccountIntent(
            final Context context, final String accountManagerType) {
        final Intent i = new Intent();
        i.setComponent(
                new ComponentName(context, "com.android.email.activity.setup.AccountSetupFinal"));
        i.putExtra(EXTRA_FLOW_MODE, FLOW_MODE_ACCOUNT_MANAGER);
        i.putExtra(EXTRA_FLOW_ACCOUNT_TYPE, accountManagerType);
        return i;
    }
}