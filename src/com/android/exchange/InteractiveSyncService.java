/*
 * Copyright (C) 2008-2009 Marc Blank
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

package com.android.exchange;

import com.android.email.provider.EmailContent.Attachment;
import com.android.email.provider.EmailContent.Mailbox;

import android.content.Context;

/**
 * The parent class of all SyncServices that are interactive (i.e. need to
 * respond to user input in a timely way. The abstract methods for the most part
 * track the service methods available in the ISyncManager interface. The
 * SyncManager is responsible for ensuring that an InteractiveSyncService has
 * been started, and then passes the appropriate call into it. Each ISS will
 * interpret/handle the method as it deems appropriate.
 */
public abstract class InteractiveSyncService extends AbstractSyncService {

    public InteractiveSyncService(Context _context, Mailbox _mailbox) {
        super(_context, _mailbox);
    }

    public InteractiveSyncService(String prefix) {
        super(prefix);
    }

    public abstract void startSync();

    public abstract void stopSync();

    public abstract void reloadFolderList();

    public abstract void loadAttachment(Attachment att, IEmailServiceCallback cb);
}
