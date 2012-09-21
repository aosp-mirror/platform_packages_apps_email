/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.email.mail.store.imap;

import java.util.ArrayList;

/**
 * Class represents an IMAP list.
 */
public class ImapList extends ImapElement {
    /**
     * {@link ImapList} representing an empty list.
     */
    public static final ImapList EMPTY = new ImapList() {
        @Override public void destroy() {
            // Don't call super.destroy().
            // It's a shared object.  We don't want the mDestroyed to be set on this.
        }

        @Override void add(ImapElement e) {
            throw new RuntimeException();
        }
    };

    private ArrayList<ImapElement> mList = new ArrayList<ImapElement>();

    /* package */ void add(ImapElement e) {
        if (e == null) {
            throw new RuntimeException("Can't add null");
        }
        mList.add(e);
    }

    @Override
    public final boolean isString() {
        return false;
    }

    @Override
    public final boolean isList() {
        return true;
    }

    public final int size() {
        return mList.size();
    }

    public final boolean isEmpty() {
        return size() == 0;
    }

    /**
     * Return true if the element at {@code index} exists, is string, and equals to {@code s}.
     * (case insensitive)
     */
    public final boolean is(int index, String s) {
        return is(index, s, false);
    }

    /**
     * Same as {@link #is(int, String)}, but does the prefix match if {@code prefixMatch}.
     */
    public final boolean is(int index, String s, boolean prefixMatch) {
        if (!prefixMatch) {
            return getStringOrEmpty(index).is(s);
        } else {
            return getStringOrEmpty(index).startsWith(s);
        }
    }

    /**
     * Return the element at {@code index}.
     * If {@code index} is out of range, returns {@link ImapElement#NONE}.
     */
    public final ImapElement getElementOrNone(int index) {
        return (index >= mList.size()) ? ImapElement.NONE : mList.get(index);
    }

    /**
     * Return the element at {@code index} if it's a list.
     * If {@code index} is out of range or not a list, returns {@link ImapList#EMPTY}.
     */
    public final ImapList getListOrEmpty(int index) {
        ImapElement el = getElementOrNone(index);
        return el.isList() ? (ImapList) el : EMPTY;
    }

    /**
     * Return the element at {@code index} if it's a string.
     * If {@code index} is out of range or not a string, returns {@link ImapString#EMPTY}.
     */
    public final ImapString getStringOrEmpty(int index) {
        ImapElement el = getElementOrNone(index);
        return el.isString() ? (ImapString) el : ImapString.EMPTY;
    }

    /**
     * Return an element keyed by {@code key}.  Return null if not found.  {@code key} has to be
     * at an even index.
     */
    /* package */ final ImapElement getKeyedElementOrNull(String key, boolean prefixMatch) {
        for (int i = 1; i < size(); i += 2) {
            if (is(i-1, key, prefixMatch)) {
                return mList.get(i);
            }
        }
        return null;
    }

    /**
     * Return an {@link ImapList} keyed by {@code key}.
     * Return {@link ImapList#EMPTY} if not found.
     */
    public final ImapList getKeyedListOrEmpty(String key) {
        return getKeyedListOrEmpty(key, false);
    }

    /**
     * Return an {@link ImapList} keyed by {@code key}.
     * Return {@link ImapList#EMPTY} if not found.
     */
    public final ImapList getKeyedListOrEmpty(String key, boolean prefixMatch) {
        ImapElement e = getKeyedElementOrNull(key, prefixMatch);
        return (e != null) ? ((ImapList) e) : ImapList.EMPTY;
    }

    /**
     * Return an {@link ImapString} keyed by {@code key}.
     * Return {@link ImapString#EMPTY} if not found.
     */
    public final ImapString getKeyedStringOrEmpty(String key) {
        return getKeyedStringOrEmpty(key, false);
    }

    /**
     * Return an {@link ImapString} keyed by {@code key}.
     * Return {@link ImapString#EMPTY} if not found.
     */
    public final ImapString getKeyedStringOrEmpty(String key, boolean prefixMatch) {
        ImapElement e = getKeyedElementOrNull(key, prefixMatch);
        return (e != null) ? ((ImapString) e) : ImapString.EMPTY;
    }

    /**
     * Return true if it contains {@code s}.
     */
    public final boolean contains(String s) {
        for (int i = 0; i < size(); i++) {
            if (getStringOrEmpty(i).is(s)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void destroy() {
        if (mList != null) {
            for (ImapElement e : mList) {
                e.destroy();
            }
            mList = null;
        }
        super.destroy();
    }

    @Override
    public String toString() {
        return mList.toString();
    }

    /**
     * Return the text representations of the contents concatenated with ",".
     */
    public final String flatten() {
        return flatten(new StringBuilder()).toString();
    }

    /**
     * Returns text representations (i.e. getString()) of contents joined together with
     * "," as the separator.
     *
     * Only used for building the capability string passed to vendor policies.
     *
     * We can't use toString(), because it's for debugging (meaning the format may change any time),
     * and it won't expand literals.
     */
    private final StringBuilder flatten(StringBuilder sb) {
        sb.append('[');
        for (int i = 0; i < mList.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            final ImapElement e = getElementOrNone(i);
            if (e.isList()) {
                getListOrEmpty(i).flatten(sb);
            } else if (e.isString()) {
                sb.append(getStringOrEmpty(i).getString());
            }
        }
        sb.append(']');
        return sb;
    }

    @Override
    public boolean equalsForTest(ImapElement that) {
        if (!super.equalsForTest(that)) {
            return false;
        }
        ImapList thatList = (ImapList) that;
        if (size() != thatList.size()) {
            return false;
        }
        for (int i = 0; i < size(); i++) {
            if (!mList.get(i).equalsForTest(thatList.getElementOrNone(i))) {
                return false;
            }
        }
        return true;
    }
}
