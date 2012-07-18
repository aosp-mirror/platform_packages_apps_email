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

package com.android.emailcommon.utility;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class EOLConvertingOutputStream extends FilterOutputStream {
    int lastChar;

    public EOLConvertingOutputStream(OutputStream out) {
        super(out);
    }

    @Override
    public void write(int oneByte) throws IOException {
        if (oneByte == '\n') {
            if (lastChar != '\r') {
                super.write('\r');
            }
        }
        super.write(oneByte);
        lastChar = oneByte;
    }

    @Override
    public void flush() throws IOException {
        if (lastChar == '\r') {
            super.write('\n');
            lastChar = '\n';
        }
        super.flush();
    }
}
