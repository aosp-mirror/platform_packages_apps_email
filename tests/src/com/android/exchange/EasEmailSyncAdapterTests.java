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

package com.android.exchange;

import com.android.email.provider.EmailContent.Account;
import com.android.email.provider.EmailContent.Mailbox;
import com.android.exchange.adapter.EasEmailSyncAdapter;
import com.android.exchange.adapter.EasEmailSyncAdapter.EasEmailSyncParser;

import android.test.AndroidTestCase;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class EasEmailSyncAdapterTests extends AndroidTestCase {

    /**
     * Create and return a short, simple InputStream that has at least four bytes, which is all
     * that's required to initialize an EasParser (the parent class of EasEmailSyncParser)
     * @return the InputStream
     */
    public InputStream getTestInputStream() {
        return new ByteArrayInputStream(new byte[] {0, 0, 0, 0, 0});
    }

    /**
     * Check functionality for getting mime type from a file name (using its extension)
     * The default for all unknown files is application/octet-stream
     */
    public void testGetMimeTypeFromFileName() throws IOException {
        Mailbox mailbox = new Mailbox();
        mailbox.mId = -1;
        Account account = new Account();
        account.mId = -1;
        EasSyncService service = new EasSyncService();
        service.mContext = getContext();
        service.mMailbox = mailbox;
        service.mAccount = account;
        EasEmailSyncAdapter adapter = new EasEmailSyncAdapter(mailbox);
        EasEmailSyncParser p;
        p = adapter.new EasEmailSyncParser(getTestInputStream(), service);
        // Test a few known types
        String mimeType = p.getMimeTypeFromFileName("foo.jpg");
        assertEquals("image/jpeg", mimeType);
        mimeType = p.getMimeTypeFromFileName("this_is_a_weird_filename.gif");
        assertEquals("image/gif", mimeType);
        // Test an illegal file name ending with the extension prefix
        mimeType = p.getMimeTypeFromFileName("foo.");
        assertEquals("application/octet-stream", mimeType);
        // Test a really awful name
        mimeType = p.getMimeTypeFromFileName(".....");
        assertEquals("application/octet-stream", mimeType);
        // Test a bare file name (no extension)
        mimeType = p.getMimeTypeFromFileName("foo");
        assertEquals("application/octet-stream", mimeType);
        // And no name at all (null isn't a valid input)
        mimeType = p.getMimeTypeFromFileName("");
        assertEquals("application/octet-stream", mimeType);
    }
}
