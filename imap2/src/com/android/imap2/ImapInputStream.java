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

package com.android.imap2;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public class ImapInputStream extends FilterInputStream {

    public ImapInputStream(InputStream in) {
        super(in);
    }

    public String readLine () throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = read();
            // Line ends with \n; ignore \r
            // I'm not sure this is the right thing with a raw \r (no \n following)
            if (b < 0)
                throw new IOException("Socket closed in readLine");
            if (b == '\n')
                return sb.toString();
            else if (b != '\r') {
                sb.append((char)b);
            }
        }
    }

    public boolean ready () throws IOException {
        return this.available() > 0;
    }
}
