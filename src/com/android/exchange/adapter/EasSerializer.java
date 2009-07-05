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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Hashtable;


/**
 * This is a convenience class that simplifies the creation of Wbxml commands and allows
 * multiple commands to be chained together.
 *
 * Each start command must pair with an end command; the values of all data fields are Strings. The
 * methods here should be self-explanatory.
 *
 * Use toString() to obtain the output for the EAS server
 */
public class EasSerializer extends WbxmlSerializer {
    ByteArrayOutputStream byteStream = new ByteArrayOutputStream();

    static Hashtable<String, Object> tagTable = null;

    public EasSerializer() {
        super();
        try {
            setOutput(byteStream, null);
            // Lazy initialization of our tag tables, as created from EasTags
            if (tagTable == null) {
                String[][] pages = EasTags.pages;
                for (int i = 0; i < pages.length; i++) {
                    String[] page = pages[i];
                    if (page.length > 0) {
                        setTagTable(i, page);
                    }
                }
                tagTable = getTagTable();
            } else {
                setTagTable(tagTable);
            }
            startDocument("UTF-8", false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public EasSerializer start(String tag) throws IOException {
        startTag(null, tag);
        return this;
    }

    public EasSerializer end(String tag) throws IOException {
        endTag(null, tag);
        return this;
    }

    public EasSerializer end() throws IOException {
        endDocument();
        return this;
    }

    public EasSerializer data(String tag, String value) throws IOException {
        startTag(null, tag);
        text(value);
        endTag(null, tag);
        return this;
    }

    public EasSerializer tag(String tag) throws IOException {
        startTag(null, tag);
        endTag(null, tag);
        return this;
    }

    public EasSerializer text(String str) throws IOException {
        super.text(str);
        return this;
    }

    public ByteArrayOutputStream getByteStream() {
        return byteStream;
    }

    public String toString() {
        return byteStream.toString();
    }
}

