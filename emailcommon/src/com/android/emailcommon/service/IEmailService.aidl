/*
 * Copyright (C) 2008-2010 Marc Blank
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

package com.android.emailcommon.service;

import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.service.IEmailServiceCallback;
import com.android.emailcommon.service.SearchParams;

import android.os.Bundle;

interface IEmailService {
    // Core email operations.
    // TODO: is sendMail really necessary, or should we standardize on sync(outbox)?
    void sendMail(long accountId);
    oneway void loadAttachment(IEmailServiceCallback cb, long accountId, long attachmentId,
            boolean background);
    oneway void updateFolderList(long accountId);

    void sync(long accountId, boolean updateFolderList, int mailboxType, in long[] foldersToSync);

    // Push-related functionality.

    // Notify the service that the push configuration has changed for an account.
    void pushModify(long accountId);

    // Other email operations.
    // TODO: Decouple this call from HostAuth (i.e. use a dedicated data structure, or just pass
    // the necessary strings directly).
    Bundle validate(in HostAuth hostauth);
    int searchMessages(long accountId, in SearchParams params, long destMailboxId);

    // PIM functionality (not strictly EAS specific).
    oneway void sendMeetingResponse(long messageId, int response);

    // Specific to EAS protocol.
    Bundle autoDiscover(String userName, String password);

    // Service control operations (i.e. does not generate a client-server message).
    oneway void setLogging(int on);

    // Needs to get moved into Email since this is NOT a client-server command.
    void deleteAccountPIMData(String emailAddress);
}
