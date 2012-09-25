/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.email;

import android.accounts.Account;
import android.content.Context;

import com.android.ex.chips.BaseRecipientAdapter;
import com.android.ex.chips.RecipientEditTextView;

public class RecipientAdapter extends BaseRecipientAdapter {
    public RecipientAdapter(Context context, RecipientEditTextView list) {
        super(context);
    }

    /**
     * Set the account when known. Causes the search to prioritize contacts from
     * that account.
     */
    @Override
    public void setAccount(Account account) {
        if (account != null) {
            // TODO: figure out how to infer the contacts account
            // type from the email account
            super.setAccount(new android.accounts.Account(account.name, "unknown"));
        }
    }

    @Override
    protected int getDefaultPhotoResource() {
        return R.drawable.ic_contact_picture;
    }

    @Override
    protected int getItemLayout() {
        return R.layout.chips_recipient_dropdown_item;
    }
}
