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

import java.io.IOException;
import java.io.InputStream;

/**
 * MockParserStream is an InputStream that feeds pre-generated data into various EasParser
 * subclasses.
 * 
 * After parsing is done, the result can be obtained with getResult
 *
 */
public class MockParserStream extends InputStream {
    int[] array;
    int pos = 0;
    Object value;

    MockParserStream (int[] _array) {
        array = _array;
    }

    @Override
    public int read() throws IOException {
        try {
            return array[pos++];
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("End of stream");
        }
    }

    public void setResult(Object _value) {
        value = _value;
    }

    public Object getResult() {
        return value;
    }
}
