/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.mail.providers;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.android.email.activity.setup.AccountSettings;

public class EmailAccountCacheProvider extends MailAppProvider {
    // Content provider for Email
    private static final String sAuthority = "com.android.email2.accountcache";
    /**
     * Authority for the suggestions provider. This is specified in AndroidManifest.xml and
     * res/xml/searchable.xml.
     */
    private static final String sSuggestionsAuthority = "com.android.email.suggestionsprovider";

    @Override
    protected String getAuthority() {
        return sAuthority;
    }

    @Override
    protected Intent getNoAccountsIntent(Context context) {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_EDIT);
        intent.setData(Uri.parse("content://ui.email.android.com/settings"));
        intent.putExtra(AccountSettings.EXTRA_NO_ACCOUNTS, true);
        return intent;
    }

    @Override
    public String getSuggestionAuthority() {
        return sSuggestionsAuthority;
    }
}
