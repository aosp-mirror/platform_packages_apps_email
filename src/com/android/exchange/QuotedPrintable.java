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

public class QuotedPrintable {
    static public String toString (String str) {
        int len = str.length();
        // Make sure we don't get an index out of bounds error with the = character
        int max = len - 2;
        StringBuilder sb = new StringBuilder(len);
        try {
            for (int i = 0; i < len; i++) {
                char c = str.charAt(i);
                if (c == '=') {
                    if (i < max) {
                        char n = str.charAt(++i);
                        if (n == '\r') {
                            n = str.charAt(++i);
                            if (n == '\n') {
                                continue;
                            } else {
                                System.err.println("Not valid QP");
                            }
                        } else {
                            // Must be less than 0x80, right?
                            int a;
                            if (n >= '0' && n <= '9') {
                                a = (n - '0') << 4;
                            } else {
                                a = (10 + (n - 'A')) << 4;
                            }
                            n = str.charAt(++i);
                            if (n >= '0' && n <= '9') {
                                c = (char) (a + (n - '0'));
                            } else {
                                c = (char) (a + 10 + (n - 'A'));
                            }
                        }
                    } if (i + 1 == len) {
                        continue;
                    }
                }
                sb.append(c);
            }
        } catch (IndexOutOfBoundsException e) {
        }
        String ret = sb.toString();
        return ret;
    }

    static public String encode (String str) {
        int len = str.length();
        StringBuffer sb = new StringBuffer(len + len>>2);
        int i = 0;
        while (i < len) {
            char c = str.charAt(i++);
            if (c < 0x80) {
                sb.append(c);
            } else {
                sb.append('&');
                sb.append('#');
                sb.append((int)c);
                sb.append(';');
            }
        }
        return sb.toString();
    }

    static public int decode (byte[] bytes, int len) {
        // Make sure we don't get an index out of bounds error with the = character
        int max = len - 2;
        int pos = 0;
        try {
            for (int i = 0; i < len; i++) {
                char c = (char)bytes[i];
                if (c == '=') {
                    if (i < max) {
                        char n = (char)bytes[++i];
                        if (n == '\r') {
                            n = (char)bytes[++i];
                            if (n == '\n') {
                                continue;
                            } else {
                                System.err.println("Not valid QP");
                            }
                        } else {
                            // Must be less than 0x80, right?
                            int a;
                            if (n >= '0' && n <= '9') {
                                a = (n - '0') << 4;
                            } else {
                                a = (10 + (n - 'A')) << 4;
                            }
                            n = (char)bytes[++i];
                            if (n >= '0' && n <= '9') {
                                c = (char) (a + (n - '0'));
                            } else {
                                c = (char) (a + 10 + (n - 'A'));
                            }
                        }
                    } if (i + 1 > len) {
                        continue;
                    }
                }
                bytes[pos++] = (byte)c;
            }
        } catch (IndexOutOfBoundsException e) {
        }
        return pos;
    }
}
