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

package com.android.email.imap2;

import java.util.ArrayList;

public class Parser {
    String str;
    int pos;
    int len;
    static final String white = "\r\n \t";

    public Parser (String _str) {
        str = _str;
        pos = 0;
        len = str.length();
    }

    public Parser (String _str, int start) {
        str = _str;
        pos = start;
        len = str.length();
    }

    public void skipWhite () {
        while ((pos < len) && white.indexOf(str.charAt(pos)) >= 0)
            pos++;
    }

    public String parseAtom () {
        skipWhite();
        int start = pos;
        while ((pos < len) && white.indexOf(str.charAt(pos)) < 0)
            pos++;
        if (pos > start)
            return str.substring(start, pos);
        return null;
    }

    public char nextChar () {
        if (pos >= len)
            return 0;
        else
            return str.charAt(pos++);
    }

    public char peekChar () {
        if (pos >= len)
            return 0;
        else
            return str.charAt(pos);
    }

    public String parseString () {
        return parseString(false);
    }

    public String parseStringOrAtom () {
        return parseString(true);
    }

    public String parseString (boolean orAtom) {
        skipWhite();
        char c = nextChar();
        if (c != '\"') {
            if (c == '{') {
                int cnt = parseInteger();
                c = nextChar();
                if (c != '}')
                    return null;
                int start = pos + 2;
                int end = start + cnt;
                String s = str.substring(start, end);
                pos = end;
                return s;
            } else if (orAtom) {
                backChar();
                return parseAtom();
            } else if (c == 'n' || c == 'N') {
                parseAtom();
                return null;
            } else
                return null;
        }
        int start = pos;
        boolean quote = false;
        while (true) {
            c = nextChar();
            if (c == 0)
                return null;
            else if (quote)
                quote = false;
            else if (c == '\\')
                quote = true;
            else if (c == '\"')
                break;
        }
        return str.substring(start, pos - 1);
    }

    public void backChar () {
        if (pos > 0)
            pos--;
    }

    public String parseListOrNil () {
        String list = parseList();
        if (list == null) {
            parseAtom();
            list = "";
        }
        return list;
    }

    public String parseList () {
        skipWhite();
        if (nextChar() != '(') {
            backChar();
            return null;
        }
        int start = pos;
        int level = 0;
        boolean quote = false;
        boolean string = false;
        while (true) {
            char c = nextChar();
            if (c == 0) {
                return null;
            } else if (quote) {
                quote = false;
            } else if (c == '\\' && string) {
                quote = true;
            } else if (c == '\"') {
                string = !string;
            } else if (c == '(' && !string) {
                level++;
            } else if (c == '{' && !string) {
                // Check for string literal
                Parser p = new Parser(str, pos);
                int cnt = p.parseInteger();
                if (cnt > 0 && p.nextChar() == '}') {
                    pos = p.pos + 2 + cnt;
                }
            } else if (c == ')' && !string) {
                if (level-- == 0)
                    break;
            }
        }
        return str.substring(start, pos - 1);
    }

    public int parseInteger () {
        skipWhite();
        int start = pos;
        while (pos < len) {
            char c = str.charAt(pos);
            if (c >= '0' && c <= '9')
                pos++;
            else
                break;
        }
        if (pos > start) {
            // We know these are positive integers
            int sum = 0;
            for (int i = start; i < pos; i++) {
                sum = (sum * 10) + (str.charAt(i) - '0');
            }
            return sum;
        } else {
            return -1;
        }
    }

    public int[] gatherInts () {
        int[] list = new int[128];
        int size = 128;
        int offs = 0;
        while (true) {
            int i = parseInteger();
            if (i >= 0) {
                if (offs == size) {
                    // Double the size of the array as necessary
                    size <<= 1;
                    int[] tmp = new int[size];
                    System.arraycopy(list, 0, tmp, 0, offs);
                    list = tmp;
                }
                list[offs++] = i;
            }
            else
                break;
        }
        int[] res = new int[offs];
        System.arraycopy(list, 0, res, 0, offs);
        return res;
    }
    public Integer[] gatherIntegers () {
        ArrayList<Integer> list = new ArrayList<Integer>();
        while (true) {
            Integer i = parseInteger();
            if (i >= 0) {
                list.add(i);
            }
            else
                break;
        }
        return list.toArray(new Integer[list.size()]);
    }
}
