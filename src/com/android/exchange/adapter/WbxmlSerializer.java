/* Copyright (c) 2002,2003, Stefan Haustein, Oberhausen, Rhld., Germany
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or
 * sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The  above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS
 * IN THE SOFTWARE. */

//Contributors: Jonathan Cox, Bogdan Onoiu, Jerry Tian
//Simplified for Google, Inc. by Marc Blank

package com.android.exchange.adapter;

import java.io.*;
import java.util.*;

import org.xmlpull.v1.*;




/**
 * A class for writing WBXML.
 *
 */



public class WbxmlSerializer implements XmlSerializer {

    Hashtable<String, Integer> stringTable = new Hashtable<String, Integer>();

    OutputStream out;

    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    ByteArrayOutputStream stringTableBuf = new ByteArrayOutputStream();

    String pending;
    int depth;
    String name;

    Hashtable<String, Object> tagTable = new Hashtable<String, Object>();

    private int tagPage;

    public void cdsect (String cdsect) throws IOException{
        text (cdsect);
    }

    public void comment (String comment) {
    }


    public void docdecl (String docdecl) {
        throw new RuntimeException ("Cannot write docdecl for WBXML");
    }


    public void entityRef (String er) {
        throw new RuntimeException ("EntityReference not supported for WBXML");
    }

    public int getDepth() {
        return depth;
    }


    public boolean getFeature (String name) {
        return false;
    }

    public String getNamespace() {
        throw new RuntimeException("NYI");
    }

    public String getName() {
        throw new RuntimeException("NYI");
    }

    public String getPrefix(String nsp, boolean create) {
        throw new RuntimeException ("NYI");
    }


    public Object getProperty (String name) {
        return null;
    }

    public void ignorableWhitespace (String sp) {
    }


    public void endDocument() throws IOException {
        writeInt(out, stringTableBuf.size());
        out.write(stringTableBuf.toByteArray());
        out.write(buf.toByteArray());
        out.flush();
    }


    /** ATTENTION: flush cannot work since Wbxml documents require
     buffering. Thus, this call does nothing. */

    public void flush() {
    }


    public void checkPending(boolean degenerated) throws IOException {
        if (pending == null)
            return;

        int[] idx = (int[]) tagTable.get(pending);

        // if no entry in known table, then add as literal
        if (idx == null) {
            throw new IOException("Bad tag: " + pending);
        }
        else {
            if(idx[0] != tagPage) {
                tagPage=idx[0];
                buf.write(Wbxml.SWITCH_PAGE);
                buf.write(tagPage);
            }

            buf.write(degenerated ? idx[1] : idx[1] | 64);
        }

        pending = null;
    }


    public void processingInstruction(String pi) {
    }


    public void setFeature(String name, boolean value) {
    }

    public void setOutput (Writer writer) {
    }

    public void setOutput (OutputStream out, String encoding) throws IOException {
        this.out = out;
        buf = new ByteArrayOutputStream();
        stringTableBuf = new ByteArrayOutputStream();
    }

    public OutputStream getOutput () {
        return out;

    }
    public void setPrefix(String prefix, String nsp) {
        throw new RuntimeException("NYI");
    }

    public void setProperty(String property, Object value) {
        throw new IllegalArgumentException ("unknown property "+property);
    }


    public void startDocument(String s, Boolean b) throws IOException{
        out.write(0x03); // version 1.3
        out.write(0x01); // unknown or missing public identifier
        out.write(106);
    }


    public XmlSerializer startTag(String namespace, String name) throws IOException {
        checkPending(false);
        pending = name;
        depth++;
        return this;
    }

    public XmlSerializer text(char[] chars, int start, int len) throws IOException {
        checkPending(false);
        buf.write(Wbxml.STR_I);
        writeStrI(buf, new String(chars, start, len));
        return this;
    }

    public XmlSerializer text(String text) throws IOException {
        checkPending(false);
        buf.write(Wbxml.STR_I);
        writeStrI(buf, text);
        return this;
    }


    /** Used in text() and attribute() to write text */


    public XmlSerializer endTag(String namespace, String name) throws IOException {
        if (pending != null) {
            checkPending(true);
        } else {
            buf.write(Wbxml.END);
        }
        depth--;
        return this;
    }

    // ------------- internal methods --------------------------

    static void writeInt(OutputStream out, int i) throws IOException {
        byte[] buf = new byte[5];
        int idx = 0;

        do {
            buf[idx++] = (byte) (i & 0x7f);
            i = i >> 7;
        } while (i != 0);

        while (idx > 1) {
            out.write(buf[--idx] | 0x80);
        }
        out.write(buf[0]);
    }

    void writeStrI(OutputStream out, String s) throws IOException {
        byte[] data = s.getBytes("UTF-8");
        out.write(data);
        out.write(0);
    }

    public Hashtable<String, Object> getTagTable () {
        return this.tagTable;
    }

    public void setTagTable (Hashtable<String, Object> tagTable) {
        this.tagTable = tagTable;
    }

    /**
     * Sets the tag table for a given page.
     * The first string in the array defines tag 5, the second tag 6 etc.
     */

    public void setTagTable(int page, String[] tagTable) {

        for (int i = 0; i < tagTable.length; i++) {
            if (tagTable[i] != null) {
                Object idx = new int[]{page, i+5};
                this.tagTable.put(tagTable[i], idx);
            }
        }
    }

    public XmlSerializer attribute(String namespace, String name, String value) 
    throws IOException, IllegalArgumentException, IllegalStateException {
        return null;
    }
}