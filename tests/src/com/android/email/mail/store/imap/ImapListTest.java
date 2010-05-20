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

import static com.android.email.mail.store.imap.ImapTestUtils.*;

import com.android.email.mail.store.imap.ImapElement;
import com.android.email.mail.store.imap.ImapList;
import com.android.email.mail.store.imap.ImapSimpleString;
import com.android.email.mail.store.imap.ImapString;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

@SmallTest
public class ImapListTest extends TestCase {

    /**
     * Test for small functions.  (isList, isString, isEmpty and size)
     */
    public void testBasics() {
        ImapList list = new ImapList();

        assertTrue(list.isList());
        assertFalse(list.isString());

        assertTrue(list.isEmpty());
        assertEquals(0, list.size());

        list.add(STRING_1);
        assertFalse(list.isEmpty());
        assertEquals(1, list.size());

        list.add(STRING_2);
        assertEquals(2, list.size());

        list.add(LIST_1);
        assertEquals(3, list.size());
    }

    /**
     * Test for {@link ImapList#EMPTY}.
     */
    public void testEmpty() {
        assertTrue(ImapList.EMPTY.isEmpty());
    }

    public void testIs() {
        final ImapString ABC = new ImapSimpleString("AbC");
        ImapList list = buildList(ImapList.EMPTY, ABC, LIST_1, ImapString.EMPTY);

        assertFalse(list.is(0, "abc"));
        assertFalse(list.is(1, "ab"));
        assertTrue (list.is(1, "abc"));
        assertFalse(list.is(2, "abc"));
        assertFalse(list.is(3, "abc"));
        assertFalse(list.is(4, "abc"));

        assertFalse(list.is(0, "ab", false));
        assertFalse(list.is(1, "ab", false));
        assertTrue (list.is(1, "abc", false));
        assertFalse(list.is(2, "ab", false));
        assertFalse(list.is(3, "ab", false));
        assertFalse(list.is(4, "ab", false));

        assertFalse(list.is(0, "ab", true));
        assertTrue (list.is(1, "ab", true));
        assertTrue (list.is(1, "abc", true));
        assertFalse(list.is(2, "ab", true));
        assertFalse(list.is(3, "ab", true));
        assertFalse(list.is(4, "ab", true));

        // Make sure null is okay
        assertFalse(list.is(0, null, false));

        // Make sure won't crash with empty list
        assertFalse(ImapList.EMPTY.is(0, "abc"));
    }

    public void testGetElementOrNone() {
        ImapList list = buildList(ImapList.EMPTY, STRING_1, LIST_1, ImapString.EMPTY);

        assertElement(ImapList.EMPTY,   list.getElementOrNone(0));
        assertElement(STRING_1,         list.getElementOrNone(1));
        assertElement(LIST_1,           list.getElementOrNone(2));
        assertElement(ImapString.EMPTY, list.getElementOrNone(3));
        assertElement(ImapElement.NONE, list.getElementOrNone(4)); // Out of index.

        // Make sure won't crash with empty list
        assertElement(ImapElement.NONE, ImapList.EMPTY.getElementOrNone(0));
    }

    public void testGetListOrEmpty() {
        ImapList list = buildList(ImapList.EMPTY, STRING_1, LIST_1, ImapString.EMPTY);

        assertElement(ImapList.EMPTY, list.getListOrEmpty(0));
        assertElement(ImapList.EMPTY, list.getListOrEmpty(1));
        assertElement(LIST_1,         list.getListOrEmpty(2));
        assertElement(ImapList.EMPTY, list.getListOrEmpty(3));
        assertElement(ImapList.EMPTY, list.getListOrEmpty(4)); // Out of index.

        // Make sure won't crash with empty list
        assertElement(ImapList.EMPTY, ImapList.EMPTY.getListOrEmpty(0));
    }

    public void testGetStringOrEmpty() {
        ImapList list = buildList(ImapList.EMPTY, STRING_1, LIST_1, ImapString.EMPTY);

        assertElement(ImapString.EMPTY, list.getStringOrEmpty(0));
        assertElement(STRING_1,         list.getStringOrEmpty(1));
        assertElement(ImapString.EMPTY, list.getStringOrEmpty(2));
        assertElement(ImapString.EMPTY, list.getStringOrEmpty(3));
        assertElement(ImapString.EMPTY, list.getStringOrEmpty(4)); // Out of index.

        // Make sure won't crash with empty list
        assertElement(ImapString.EMPTY, ImapList.EMPTY.getStringOrEmpty(0));
    }

    public void testGetKeyedElementOrNull() {
        final ImapString K1 = new ImapSimpleString("aBCd");
        final ImapString K2 = new ImapSimpleString("Def");
        final ImapString K3 = new ImapSimpleString("abC");

        ImapList list = buildList(
                K1, STRING_1,
                K2, K3,
                K3, STRING_2);

        assertElement(null,     list.getKeyedElementOrNull("ab", false));
        assertElement(STRING_1, list.getKeyedElementOrNull("abcd", false));
        assertElement(K3,       list.getKeyedElementOrNull("def", false));
        assertElement(STRING_2, list.getKeyedElementOrNull("abc", false));

        assertElement(STRING_1, list.getKeyedElementOrNull("ab", true));
        assertElement(STRING_1, list.getKeyedElementOrNull("abcd", true));
        assertElement(K3,       list.getKeyedElementOrNull("def", true));
        assertElement(STRING_1, list.getKeyedElementOrNull("abc", true));

        // Make sure null is okay
        assertElement(null, list.getKeyedElementOrNull(null, false));

        // Make sure won't crash with empty list
        assertNull(ImapList.EMPTY.getKeyedElementOrNull("ab", false));

        // Shouldn't crash with a list with an odd number of elements.
        assertElement(null, buildList(K1).getKeyedElementOrNull("abcd", false));
    }

    public void getKeyedListOrEmpty() {
        final ImapString K1 = new ImapSimpleString("Key");
        ImapList list = buildList(K1, LIST_1);

        assertElement(LIST_1,         list.getKeyedListOrEmpty("key", false));
        assertElement(LIST_1,         list.getKeyedListOrEmpty("key", true));
        assertElement(ImapList.EMPTY, list.getKeyedListOrEmpty("ke", false));
        assertElement(LIST_1,         list.getKeyedListOrEmpty("ke", true));

        assertElement(ImapList.EMPTY, list.getKeyedListOrEmpty("ke"));
        assertElement(LIST_1,         list.getKeyedListOrEmpty("key"));
    }

    public void getKeyedStringOrEmpty() {
        final ImapString K1 = new ImapSimpleString("Key");
        ImapList list = buildList(K1, STRING_1);

        assertElement(STRING_1,         list.getKeyedListOrEmpty("key", false));
        assertElement(STRING_1,         list.getKeyedListOrEmpty("key", true));
        assertElement(ImapString.EMPTY, list.getKeyedListOrEmpty("ke", false));
        assertElement(STRING_1,         list.getKeyedListOrEmpty("ke", true));

        assertElement(ImapString.EMPTY, list.getKeyedListOrEmpty("ke"));
        assertElement(STRING_1,         list.getKeyedListOrEmpty("key"));
    }

    public void testContains() {
        final ImapString K1 = new ImapSimpleString("aBCd");
        final ImapString K2 = new ImapSimpleString("Def");
        final ImapString K3 = new ImapSimpleString("abC");

        ImapList list = buildList(K1, K2, K3);

        assertTrue(list.contains("abc"));
        assertTrue(list.contains("abcd"));
        assertTrue(list.contains("def"));
        assertFalse(list.contains(""));
        assertFalse(list.contains("a"));
        assertFalse(list.contains(null));

        // Make sure null is okay
        assertFalse(list.contains(null));

        // Make sure won't crash with empty list
        assertFalse(ImapList.EMPTY.contains(null));
    }

    public void testFlatten() {
        assertEquals("[]", ImapList.EMPTY.flatten());
        assertEquals("[aBc]", buildList(STRING_1).flatten());
        assertEquals("[[]]", buildList(ImapList.EMPTY).flatten());
        assertEquals("[aBc,[,X y z],aBc]",
                buildList(STRING_1, buildList(ImapString.EMPTY, STRING_2), STRING_1).flatten());
    }
}
