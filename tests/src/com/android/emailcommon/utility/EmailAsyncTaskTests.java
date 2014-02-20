/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.emailcommon.utility;

import android.test.AndroidTestCase;
import android.test.MoreAsserts;
import android.test.suitebuilder.annotation.SmallTest;

@SmallTest
public class EmailAsyncTaskTests extends AndroidTestCase {
    public void testAll() throws Exception {
        // Because AsyncTask relies on the UI thread and how we use threads in test, we can't
        // execute() these tasks.
        // Instead, we directly call onPostExecute/onCancel.

        final EmailAsyncTask.Tracker tracker = new EmailAsyncTask.Tracker();

        // Initially empty
        assertEquals(0, tracker.getTaskCountForTest());

        // Start 4 tasks
        final MyTask task1 = new MyTask(tracker);
        assertEquals(1, tracker.getTaskCountForTest());

        final MyTask task2 = new MyTask(tracker);
        assertEquals(2, tracker.getTaskCountForTest());

        final MyTask task3 = new MyTask(tracker);
        assertEquals(3, tracker.getTaskCountForTest());

        final MyTask task4 = new MyTask(tracker);
        assertEquals(4, tracker.getTaskCountForTest());

        // Check the piping for doInBackground
        task1.mDoInBackgroundResult = "R";
        assertEquals("R", task1.callDoInBackgroundForTest("1", "2"));
        MoreAsserts.assertEquals(new String[] {"1", "2"}, task1.mDoInBackgroundArg);

        // Finish task1
        task1.callOnPostExecuteForTest("a");

        // onPostExecute should unregister the instance
        assertEquals(3, tracker.getTaskCountForTest());
        // and call onPostExecuteInternal
        assertEquals("a", task1.mOnPostExecuteArg);
        assertNull(task1.mOnCancelledArg);

        // Cancel task 3
        task3.callOnCancelledForTest("b");
        // onCancelled should unregister the instance too
        assertEquals(2, tracker.getTaskCountForTest());
        // and call onCancelledInternal
        assertNull(task3.mOnPostExecuteArg);
        assertEquals("b", task3.mOnCancelledArg);

        // Task 2 and 4 are still registered.

        // Cancel all left
        tracker.cancelAllInterrupt();

        // Check if they're canceled
        assertEquals(0, tracker.getTaskCountForTest());
    }

    // Make sure null tracker will be accepted
    public void testNullTracker() {
        final MyTask task1 = new MyTask(null);
        task1.unregisterSelf();
    }

    /**
     * Test for {@link EmailAsyncTask.Tracker#cancelOthers}
     */
    public void testCancellOthers() {
        final EmailAsyncTask.Tracker tracker = new EmailAsyncTask.Tracker();

        final MyTask task1 = new MyTask(tracker);
        final MyTask task2 = new MyTask(tracker);
        final MyTask task3 = new MyTask(tracker);

        final MyTask sub1 = new MyTaskSubClass(tracker);
        final MyTask sub2 = new MyTaskSubClass(tracker);
        final MyTask sub3 = new MyTaskSubClass(tracker);

        // All should be in the tracker.
        assertEquals(6, tracker.getTaskCountForTest());

        // This should remove task1, task2, but not task3 itself.
        tracker.cancelOthers(task3);

        assertEquals(4, tracker.getTaskCountForTest());
        assertTrue(tracker.containsTaskForTest(task3));

        // Same for sub1.
        tracker.cancelOthers(sub1);

        assertEquals(2, tracker.getTaskCountForTest());
        assertTrue(tracker.containsTaskForTest(task3));
        assertTrue(tracker.containsTaskForTest(sub1));
    }

    private static class MyTask extends EmailAsyncTask<String, String, String> {
        public String[] mDoInBackgroundArg;
        public String mDoInBackgroundResult;
        public String mOnCancelledArg;
        public String mOnPostExecuteArg;

        public MyTask(Tracker tracker) {
            super(tracker);
        }

        @Override
        protected String doInBackground(String... params) {
            mDoInBackgroundArg = params;
            return mDoInBackgroundResult;
        }

        @Override
        protected void onCancelled(String result) {
            mOnCancelledArg = result;
        }

        @Override
        protected void onSuccess(String result) {
            mOnPostExecuteArg = result;
        }
    }

    private static class MyTaskSubClass extends MyTask {
        public MyTaskSubClass(Tracker tracker) {
            super(tracker);
        }
    }
}
