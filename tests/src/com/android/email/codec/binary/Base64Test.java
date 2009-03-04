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

package com.android.email.codec.binary;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * A series of tests of the Base64 encoder.
 */
@SmallTest
public class Base64Test extends TestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }
    
    /**
     * Looking for issues with line length and trailing zeros. The code we're modeling is
     * in mail.internet.TextBody:
     *   byte[] bytes = mBody.getBytes("UTF-8");
     *   out.write(Base64.encodeBase64Chunked(bytes));
     */
    public void testLineLength54() {
        byte[] out = Base64.encodeBase64Chunked(getByteArray(54));
        checkBase64Structure(out, 1);
    }
    public void testLineLength55() {
        byte[] out = Base64.encodeBase64Chunked(getByteArray(55));
        checkBase64Structure(out, 1);
    }
    public void testLineLength56() {
        byte[] out = Base64.encodeBase64Chunked(getByteArray(56));
        checkBase64Structure(out, 1);
    }
    public void testLineLength57() {
        byte[] out = Base64.encodeBase64Chunked(getByteArray(57));
        checkBase64Structure(out, 1);
    }
    public void testLineLength58() {
        byte[] out = Base64.encodeBase64Chunked(getByteArray(58));
        checkBase64Structure(out, 2);
    }
    public void testLineLength59() {
        byte[] out = Base64.encodeBase64Chunked(getByteArray(59));
        checkBase64Structure(out, 2);
    }
    
    /**
     * Repeat the above tests with 2x line lengths
     */
    public void testLineLength111() {
        byte[] out = Base64.encodeBase64Chunked(getByteArray(111));
        checkBase64Structure(out, 2);
    }
    public void testLineLength112() {
        byte[] out = Base64.encodeBase64Chunked(getByteArray(112));
        checkBase64Structure(out, 2);
    }
    public void testLineLength113() {
        byte[] out = Base64.encodeBase64Chunked(getByteArray(113));
        checkBase64Structure(out, 2);
    }
    public void testLineLength114() {
        byte[] out = Base64.encodeBase64Chunked(getByteArray(114));
        checkBase64Structure(out, 2);
    }
    public void testLineLength115() {
        byte[] out = Base64.encodeBase64Chunked(getByteArray(115));
        checkBase64Structure(out, 3);
    }
    
    /**
     * Validate that base64 output is structurally sound.  Does not independently confirm
     * that the actual encoding is valid.
     */
    private void checkBase64Structure(byte[] buffer, int expectedChunks) {
        
        // outer loop - divide into chunks
        int chunkCount = 0;
        int chunkStart;
        int nextChunkStart = 0;
        int limit = buffer.length;
        while (nextChunkStart < limit) {
            chunkStart = -1;
            int chunkEnd;
            for (chunkEnd = nextChunkStart; chunkEnd < limit; ++chunkEnd) {
                assertFalse("nulls in chunk", buffer[chunkEnd] == 0);
                if (buffer[chunkEnd] == '\r') {
                    assertTrue(buffer[chunkEnd+1] == '\n');
                    chunkStart = nextChunkStart;
                    break;
                }
                if (chunkEnd == limit) {
                    chunkStart = nextChunkStart;
                    break;
                }
            }
            chunkCount++;
            nextChunkStart = chunkEnd + 2;
            assertTrue("chunk not found", chunkStart >= 0);
            
            // At this point we have a single chunk from chunkStart to chunkEnd
            // And we can analyze it for structural correctness
            int chunkLen = chunkEnd - chunkStart;
            
            // Max chunk length
            assertTrue("chunk length <= 76", chunkLen <= 76);
            
            // Multiple of 4 (every 3 bytes of source -> 4 bytes of output)
            assertEquals("chunk length mod 4", 0, chunkLen % 4);
            
            // 0, 1 or 2 '=' at the end
            boolean lastEquals1 = buffer[chunkEnd-1] == '=';
            boolean lastEquals2 = buffer[chunkEnd-2] == '=';
            boolean lastEquals3 = buffer[chunkEnd-3] == '=';
            
            assertTrue("trailing equals",
                    (!lastEquals1 && !lastEquals2) ||        // 0
                    (lastEquals1 && !lastEquals2) ||         // or 1
                    (lastEquals1 && lastEquals2));           // or 2
        }
        
        assertEquals("total chunk count", expectedChunks, chunkCount);
    }

    /**
     * Generate a test sequence of a given length.
     */
    private byte[] getByteArray(int size) {
        byte[] result = new byte[size];
        byte fillChar = '1';
        for (int i = 0; i < size; ++i) {
            result[i] = fillChar++;
            if (fillChar > '9') {
                fillChar = '0';
            }
        }
        return result;
    }
}
