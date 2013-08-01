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

import com.android.emailcommon.provider.Account;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;

public class RequireManualSyncDialog extends AlertDialog implements OnClickListener {

    public RequireManualSyncDialog(Context context, Account account) {
        super(context);
        setMessage(context.getResources().getString(R.string.require_manual_sync_message));
        setButton(DialogInterface.BUTTON_POSITIVE, context.getString(android.R.string.ok), this);
        Preferences.getPreferences(context).setHasShownRequireManualSync(account, true);
    }

    /** {@inheritDoc} */
    @Override
    public void onClick(DialogInterface dialog, int which) {
        // No-op.
    }
}