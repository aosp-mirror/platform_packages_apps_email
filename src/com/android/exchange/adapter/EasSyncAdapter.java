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

package com.android.exchange.adapter;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import com.android.exchange.EasSyncService;
import com.android.exchange.EmailContent.Mailbox;

/**
 * Parent class of all sync adapters (EasMailbox, EasCalendar, and EasContacts)
 *
 */
public abstract class EasSyncAdapter {
    public Mailbox mMailbox;

    // Create the data for local changes that need to be sent up to the server
    public abstract boolean sendLocalChanges(EasSerializer s, EasSyncService service) 
        throws IOException;
    // Parse incoming data from the EAS server, creating, modifying, and deleting objects as
    // required through the EmailProvider
    public abstract boolean parse(ByteArrayInputStream is, EasSyncService service) 
        throws IOException;
    // The name used to specify the collection type of the target (Email, Calendar, or Contacts)
    public abstract String getCollectionName();
    public abstract void cleanup(EasSyncService service);

    public EasSyncAdapter(Mailbox mailbox) {
        mMailbox = mailbox;
    }
}

