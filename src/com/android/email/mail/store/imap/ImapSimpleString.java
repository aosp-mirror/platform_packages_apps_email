/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.email.mail.store.imap;

import com.android.emailcommon.utility.Utility;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Subclass of {@link ImapString} used for non literals.
 */
public class ImapSimpleString extends ImapString {
    private String mString;

    /* package */  ImapSimpleString(String string) {
        mString = (string != null) ? string : "";
    }

    @Override
    public void destroy() {
        mString = null;
        super.destroy();
    }

    @Override
    public String getString() {
        return mString;
    }

    @Override
    public InputStream getAsStream() {
        return new ByteArrayInputStream(Utility.toAscii(mString));
    }

    @Override
    public String toString() {
        // Purposefully not return just mString, in order to prevent using it instead of getString.
        return "\"" + mString + "\"";
    }
}
