/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.email;

import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

/**
 * This is a series of unit tests for the GroupMessagingListener class.
 */
@SmallTest
public class GroupMessagingListenerUnitTests extends TestCase {

    /**
     * Tests adding and removing elements from the listener
     */
    public void testAddRemove() {
        GroupMessagingListener groupListener = new GroupMessagingListener();

        MessagingListener listener1 = new MessagingListener();
        MessagingListener listener2 = new MessagingListener();

        groupListener.addListener(listener1);
        groupListener.addListener(listener2);

        groupListener.removeListener(listener1);
        groupListener.removeListener(listener2);
    }

    /**
     * Tests isActiveListener()
     */
    public void testIsActiveListener() {
        GroupMessagingListener groupListener = new GroupMessagingListener();

        MessagingListener listener1 = new MessagingListener();
        MessagingListener listener2 = new MessagingListener();

        assertFalse(groupListener.isActiveListener(listener1));
        assertFalse(groupListener.isActiveListener(listener2));

        groupListener.addListener(listener1);
        assertTrue(groupListener.isActiveListener(listener1));
        assertFalse(groupListener.isActiveListener(listener2));

        groupListener.addListener(listener2);
        assertTrue(groupListener.isActiveListener(listener1));
        assertTrue(groupListener.isActiveListener(listener2));

        groupListener.removeListener(listener1);
        assertFalse(groupListener.isActiveListener(listener1));
        assertTrue(groupListener.isActiveListener(listener2));

        groupListener.removeListener(listener2);
        assertFalse(groupListener.isActiveListener(listener1));
        assertFalse(groupListener.isActiveListener(listener2));
    }

    /**
     * TODO: Test that if you add a set of listeners, they will be called
     */
}
