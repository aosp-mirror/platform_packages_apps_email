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

package com.android.mail.browse;

import com.android.email.R;
import com.android.mail.browse.ConversationCursor.ConversationProvider;

import java.lang.Override;

public class EmailConversationProvider extends ConversationProvider {
    // The authority of our conversation provider (a forwarding provider)
    // This string must match the declaration in AndroidManifest.xml
    private static String sAuthority;

    @Override
    protected String getAuthority() {
        if (sAuthority == null) {
            sAuthority = getContext().getString(R.string.authority_conversation_provider);
        }
        return sAuthority;
    }
}