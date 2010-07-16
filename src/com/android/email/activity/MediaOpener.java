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

package com.android.email.activity;

import com.android.email.R;
import com.android.email.Utility;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.media.MediaScannerConnection.MediaScannerConnectionClient;
import android.net.Uri;

import java.io.File;

/**
 * Call to pass a media file to the media scanner service and open it when scanned.
 *
 * TODO We maight want to set FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET when starting intent, as its
 * javadoc suggest. (But seems like Gmail doesn't do that either.)
 */
public class MediaOpener {
    private final Activity mActivity;
    private final MediaScannerConnection mConnection;
    private final File mFile;

    private MediaOpener(Activity activity, File file) {
        mActivity = activity;
        mFile = file;
        mConnection = new MediaScannerConnection(mActivity.getApplicationContext(),
                new MediaScannerConnectionClient() {
            @Override
            public void onMediaScannerConnected() {
                mConnection.scanFile(mFile.getAbsolutePath(), null);
            }

            @Override
            public void onScanCompleted(String path, Uri uri) {
                MediaOpener.this.onScanCompleted(path, uri);
            }
        });
    }

    private void start() {
        mConnection.connect();
    }

    private void onScanCompleted(String path, Uri uri) {
        try {
            if (uri != null) {
                mActivity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
            }
        } catch (ActivityNotFoundException e) {
            Utility.showToast(mActivity, R.string.message_view_display_attachment_toast);
            // TODO: Add a proper warning message (and lots of upstream cleanup to prevent
            // it from happening)
        } finally {
            mConnection.disconnect();
        }
    }

    /**
     * Start scanning a file and opening it when scanned.
     *
     * @param activity activity used as a parent when starting an intent.
     * @param file file to open.
     */
    public static void scanAndOpen(Activity activity, File file) {
        new MediaOpener(activity, file).start();
    }
}
