/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

/**
 * Modified to improve efficiency by Android   21-Aug-2009
 */

package org.apache.james.mime4j.decoder;

import java.io.IOException;
import java.io.InputStream;

/**
 * Performs Base-64 decoding on an underlying stream.
 * 
 * 
 * @version $Id: Base64InputStream.java,v 1.3 2004/11/29 13:15:47 ntherning Exp $
 */
public class Base64InputStream extends InputStream {
    private final InputStream s;
    private int outCount = 0;
    private int outIndex = 0;
    private final int[] outputBuffer = new int[3];
    private final byte[] inputBuffer = new byte[4];
    private boolean done = false;

    public Base64InputStream(InputStream s) {
        this.s = s;
    }

    /**
     * Closes the underlying stream.
     * 
     * @throws IOException on I/O errors.
     */
    @Override
    public void close() throws IOException {
        s.close();
    }
    
    @Override
    public int read() throws IOException {
        if (outIndex == outCount) {
            fillBuffer();
            if (outIndex == outCount) {
                return -1;
            }
        }

        return outputBuffer[outIndex++];
    }

    /**
     * Retrieve data from the underlying stream, decode it,
     * and put the results in the byteq.
     * @throws IOException
     */
    private void fillBuffer() throws IOException {
        outCount = 0;
        outIndex = 0;
        int inCount = 0;

        int i;
        // "done" is needed for the two successive '=' at the end
        while (!done) {
            switch (i = s.read()) {
                case -1:
                    // No more input - just return, let outputBuffer drain out, and be done
                    return;
                case '=':
                    // once we meet the first '=', avoid reading the second '='
                    done = true;
                    decodeAndEnqueue(inCount);
                    return;
                default:
                    byte sX = TRANSLATION[i];
                    if (sX < 0) continue;
                    inputBuffer[inCount++] = sX;
                    if (inCount == 4) {
                        decodeAndEnqueue(inCount);
                        return;
                    }
                    break;
            }
        }
    }

    private void decodeAndEnqueue(int len) {
        int accum = 0;
        accum |= inputBuffer[0] << 18;
        accum |= inputBuffer[1] << 12;
        accum |= inputBuffer[2] << 6;
        accum |= inputBuffer[3];

        // There's a bit of duplicated code here because we want to have straight-through operation
        // for the most common case of len==4
        if (len == 4) {
            outputBuffer[0] = (accum >> 16) & 0xFF;
            outputBuffer[1] = (accum >> 8) & 0xFF;
            outputBuffer[2] = (accum) & 0xFF;
            outCount = 3;
            return;
        } else if (len == 3) {
            outputBuffer[0] = (accum >> 16) & 0xFF;
            outputBuffer[1] = (accum >> 8) & 0xFF;
            outCount = 2;
            return;
        } else {    // len == 2
            outputBuffer[0] = (accum >> 16) & 0xFF;
            outCount = 1;
            return;
        }
    }

    private static byte[] TRANSLATION = {
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, /* 0x00 */
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, /* 0x10 */
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 62, -1, -1, -1, 63, /* 0x20 */
        52, 53, 54, 55, 56, 57, 58, 59, 60, 61, -1, -1, -1, -1, -1, -1, /* 0x30 */
        -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, /* 0x40 */
        15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, -1, -1, -1, -1, -1, /* 0x50 */
        -1, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, /* 0x60 */
        41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, -1, -1, -1, -1, -1, /* 0x70 */
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, /* 0x80 */
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, /* 0x90 */
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, /* 0xA0 */
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, /* 0xB0 */
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, /* 0xC0 */
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, /* 0xD0 */
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, /* 0xE0 */
        -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1     /* 0xF0 */
    };


}
