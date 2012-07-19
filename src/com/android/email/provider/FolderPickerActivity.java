/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
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

package com.android.email.provider;

import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.os.Bundle;

import com.android.emailcommon.provider.Mailbox;
import com.android.emailcommon.provider.EmailContent.MailboxColumns;
import com.android.mail.providers.Folder;

public class FolderPickerActivity extends Activity implements FolderPickerCallback {
    private long mAccountId;
    private int mMailboxType;

    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        Intent i = getIntent();
        com.android.mail.providers.Account account =
                i.getParcelableExtra(EmailProvider.PICKER_UI_ACCOUNT);
        mAccountId = Long.parseLong(account.uri.getLastPathSegment());
        mMailboxType = i.getIntExtra(EmailProvider.PICKER_MAILBOX_TYPE, -1);
        new FolderSelectionDialog(this, account, this).show();
    }

    @Override
    public void select(Folder folder) {
        String folderId = folder.uri.getLastPathSegment();
        Long id = Long.parseLong(folderId);
        ContentValues values = new ContentValues();

        // If we already have a mailbox of this type, change it back to generic mail type
        Mailbox ofType = Mailbox.restoreMailboxOfType(this, mAccountId, mMailboxType);
        if (ofType != null) {
            values.put(MailboxColumns.TYPE, Mailbox.TYPE_MAIL);
            getContentResolver().update(
                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, ofType.mId), values,
                    null, null);
        }

        // Change this mailbox to be of the desired type
        Mailbox mailbox = Mailbox.restoreMailboxWithId(this, id);
        if (mailbox != null) {
            values.put(MailboxColumns.TYPE, mMailboxType);
            getContentResolver().update(
                    ContentUris.withAppendedId(Mailbox.CONTENT_URI, mailbox.mId), values,
                    null, null);
        }
        finish();
    }

    @Override
    public void create() {
        // TODO: Not sure about this...
        finish();
    }
}
