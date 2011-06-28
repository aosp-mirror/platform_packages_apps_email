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

import com.google.common.annotations.VisibleForTesting;

import android.os.Handler;

import java.util.ArrayList;
import java.util.LinkedList;

/**
 * Class that helps post {@link Runnable}s to a {@link Handler}, and cancel pending ones
 * at once.
 */
public class DelayedOperations {
    private final Handler mHandler;

    @VisibleForTesting
    final LinkedList<QueuedOperation> mPendingOperations = new LinkedList<QueuedOperation>();

    private class QueuedOperation implements Runnable {
        private final Runnable mActualRannable;

        public QueuedOperation(Runnable actualRannable) {
            mActualRannable = actualRannable;
        }

        @Override
        public void run() {
            mPendingOperations.remove(this);
            mActualRannable.run();
        }

        public void cancel() {
            mPendingOperations.remove(this);
            cancelRunnable(this);
        }
    }

    public DelayedOperations(Handler handler) {
        mHandler = handler;
    }

    /**
     * Post a {@link Runnable} to the handler.  Equivalent to {@link Handler#post(Runnable)}.
     */
    public void post(Runnable r) {
        final QueuedOperation qo = new QueuedOperation(r);
        mPendingOperations.add(qo);
        postRunnable(qo);
    }

    /**
     * Cancel a runnable that's been posted with {@link #post(Runnable)}.
     *
     * Equivalent to {@link Handler#removeCallbacks(Runnable)}.
     */
    public void removeCallbacks(Runnable r) {
        QueuedOperation found = null;
        for (QueuedOperation qo : mPendingOperations) {
            if (qo.mActualRannable == r) {
                found = qo;
                break;
            }
        }
        if (found != null) {
            found.cancel();
        }
    }

    /**
     * Cancel all pending {@link Runnable}s.
     */
    public void removeCallbacks() {
        // To avoid ConcurrentModificationException
        final ArrayList<QueuedOperation> temp = new ArrayList<QueuedOperation>(mPendingOperations);
        for (QueuedOperation qo : temp) {
            qo.cancel();
        }
    }

    /** Overridden by test, as Handler is not mockable. */
    void postRunnable(Runnable r) {
        mHandler.post(r);
    }

    /** Overridden by test, as Handler is not mockable. */
    void cancelRunnable(Runnable r) {
        mHandler.removeCallbacks(r);
    }
}
