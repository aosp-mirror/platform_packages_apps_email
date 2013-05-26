/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.email.mail.transport;

import com.android.emailcommon.Logging;
import com.android.mail.utils.LogUtils;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class StatusOutputStream extends FilterOutputStream {
    private long mCount = 0;

    public StatusOutputStream(OutputStream out) {
        super(out);
    }

    @Override
    public void write(int oneByte) throws IOException {
        super.write(oneByte);
        mCount++;
        if (Logging.LOGD) {
            if (mCount % 1024 == 0) {
                LogUtils.v(Logging.LOG_TAG, "# " + mCount);
            }
        }
    }
}
