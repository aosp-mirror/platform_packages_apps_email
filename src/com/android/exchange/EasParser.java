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

import java.io.*;
import java.util.ArrayList;

import android.content.Context;
import android.util.Log;


public abstract class EasParser {

    private static final String TAG = "EasParser";

    public static final int START_DOCUMENT = 0;
    public static final int DONE = 1;
    public static final int START = 2;
    public static final int END = 3;
    public static final int TEXT = 4;
    public static final int END_DOCUMENT = 3;

    private static final int NOT_FETCHED = Integer.MIN_VALUE;
    private static final int NOT_ENDED = Integer.MIN_VALUE;

    private static final int EOF_BYTE = -1;

    private boolean debug = false;
    private boolean capture = false;
    private ArrayList<Integer> captureArray;
    private InputStream in;
    private int depth;
    private int nextId = NOT_FETCHED;
    private String[] tagTable;
    private String[][] tagTables = new String[24][];
    private String[] nameArray = new String[32];
    private int[] tagArray = new int[32];
    private boolean noContent;

    // Available to all to avoid method calls
    public int endTag = NOT_ENDED;
    public int type;
    public int tag;
    public String name;
    public String text;
    public int num;

    public void parse () throws IOException {
    }

    public EasParser (InputStream in) throws IOException {
        String[][] pages = EasTags.pages;
        for (int i = 0; i < pages.length; i++) {
            String[] page = pages[i];
            if (page.length > 0) {
                setTagTable(i, page);
            }
        }
        setInput(in);
    }

    public void setDebug (boolean val) {
        debug = val;
    }

    public void captureOn () {
        capture = true;
        captureArray = new ArrayList<Integer>();
    }

    public void captureOff (Context context, String file) {
        try {
            FileOutputStream out = context.openFileOutput(file, Context.MODE_WORLD_WRITEABLE);
            out.write(captureArray.toString().getBytes());
            out.close();
        } catch (FileNotFoundException e) {
        } catch (IOException e) {
        }
    }

    public String getValue () throws IOException {
        getNext(false);
        String val = text;
        getNext(false);
        if (type != END) {
            throw new IOException("No END found!");
        }
        endTag = tag;
        return val;
    }

    public int getValueInt () throws IOException {
        getNext(true);
        int val = num;
        getNext(false);
        if (type != END) {
            throw new IOException("No END found!");
        }
        endTag = tag;
        return val;
    }

    public int nextTag (int endTag) throws IOException {
        while (getNext(false) != DONE) {
            if (type == START) {
                return tag;
            } else if (type == END && tag == endTag) {
                return END;
            }
        }
        if (endTag == START_DOCUMENT) {
            return END_DOCUMENT;
        }
        throw new EodException();
    }

    public void skipTag () throws IOException {
        int thisTag = tag;
        while (getNext(false) != DONE) {
            if (type == END && tag == thisTag) {
                return;
            }
        }
        throw new EofException();
    }

    public int nextToken() throws IOException {
        getNext(false);
        return type;
    }

    public void setInput(InputStream in) throws IOException {
        this.in = in;
        readByte(); // version
        readInt();  // ?
        readInt();  // 106 (UTF-8)
        readInt();  // string table length
        tagTable = tagTables[0];
    }

    public int next () throws IOException {
        getNext(false);
        return type;
    }

    private final int getNext(boolean asInt) throws IOException {
        if (type == END) {
            depth--;
        } else {
            endTag = NOT_ENDED;
        }

        if (noContent) {
            type = END;
            noContent = false;
            return type;
        }

        text = null;
        name = null;

        int id = nextId ();
        while (id == Wbxml.SWITCH_PAGE) {
            nextId = NOT_FETCHED;
            tagTable = tagTables[(readByte())];
            id = nextId();
        }
        nextId = NOT_FETCHED;

        switch (id) {
            case -1 :
                type = DONE;
                break;

            case Wbxml.END : 
                type = END;
                if (debug) {
                    name = nameArray[depth];
                    Log.v(TAG, "</" + name + '>');
                }
                tag = endTag = tagArray[depth];
                break;

            case Wbxml.STR_I :
                type = TEXT;
                if (asInt) {
                    num = readInlineInt();
                } else {
                    text = readInlineString();
                }
                if (debug) {
                    Log.v(TAG, asInt ? Integer.toString(num) : text);
                }
                break;

            default :
                type = START;
            tag = id & 0x3F;
            noContent = (id & 0x40) == 0;
            depth++;
            if (debug) {
                name = tagTable[tag - 5];
                Log.v(TAG, '<' + name + '>');
                nameArray[depth] = name;
            }
            tagArray[depth] = tag;
        }

        return type;
    }

    private int read () throws IOException {
        int i = in.read();
        if (capture) {
            captureArray.add(i);
        }
        return i;
    }

    private int nextId () throws IOException {
        if (nextId == NOT_FETCHED) {
            nextId = read();
        }
        return nextId;
    }

    private int readByte() throws IOException {
        int i = read();
        if (i == EOF_BYTE) {
            throw new EofException();
        }
        return i;
    }

    private int readInlineInt() throws IOException {
        int result = 0;

        while (true) {
            int i = readByte();
            if (i == 0) {
                return result;
            }
            if (i >= '0' && i <= '9') {
                result = (result * 10) + (i - '0');
            } else {
                throw new IOException("Non integer");
            }
        }
    }

    private int readInt() throws IOException {
        int result = 0;
        int i;

        do {
            i = readByte();
            result = (result << 7) | (i & 0x7f);
        } while ((i & 0x80) != 0);

        return result;
    }

    private String readInlineString() throws IOException {
        StringBuilder sb = new StringBuilder(4096);

        while (true){
            int i = read();
            if (i == 0) {
                break;
            } else if (i == EOF_BYTE) {
                throw new EofException();
            }
            sb.append((char)i);
        }
        String res = sb.toString();
        return res;
    }

    public void setTagTable(int page, String[] table) {
        tagTables[page] = table;
    }
}