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

import com.android.email.provider.EmailContent.Mailbox;
import com.android.exchange.EasSyncService;

import java.io.IOException;
import java.io.InputStream;

/**
 * Sync adapter class for EAS calendars
 *
 */
public class CalendarSyncAdapter extends AbstractSyncAdapter {

    public CalendarSyncAdapter(Mailbox mailbox, EasSyncService service) {
        super(mailbox, service);
    }

    @Override
    public String getCollectionName() {
        return "Calendar";
    }

    @Override
    public boolean sendLocalChanges(Serializer s) throws IOException {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void cleanup() {
        // TODO Auto-generated method stub
    }

    @Override
    public boolean parse(InputStream is) throws IOException {
        // TODO Auto-generated method stub
        return false;
    }
}
