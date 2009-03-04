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

package com.android.email.activity;

import android.content.Context;

/**
 * A listener that the user can register for global, persistent progress events.
 */
public interface ProgressListener {
    /**
     * @param context
     * @param title
     * @param message
     * @param currentProgress
     * @param maxProgress
     * @param indeterminate
     */
    void showProgress(Context context, String title, String message, long currentProgress,
            long maxProgress, boolean indeterminate);

    /**
     * @param context
     * @param title
     * @param message
     * @param currentProgress
     * @param maxProgress
     * @param indeterminate
     */
    void updateProgress(Context context, String title, String message, long currentProgress,
            long maxProgress, boolean indeterminate);

    /**
     * @param context
     */
    void hideProgress(Context context);
}
