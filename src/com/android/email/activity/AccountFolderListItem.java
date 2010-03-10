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

package com.android.email.activity;

import com.android.email.R;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.LinearLayout;

/**
 * This custom View is the list item for the AccountFolderList activity, and serves two purposes:
 * 1.  It's a container to store row metadata
 * 2.  It handles internal clicks so we can create virtual "buttons" in the list
 */
public class AccountFolderListItem extends LinearLayout {

    public long mAccountId;

    private AccountFolderList.AccountsAdapter mAdapter;

    private boolean mHasFolderButton;
    private boolean mDownEvent;
    private boolean mCachedViewPositions;
    private int mFolderLeft;

    private final static float FOLDER_PAD = 5.0F;

    public AccountFolderListItem(Context context) {
        super(context);
    }

    public AccountFolderListItem(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * Called by the adapter at bindView() time
     * 
     * @param adapter the adapter that creates this view
     */
    public void bindViewInit(AccountFolderList.AccountsAdapter adapter, boolean hasFolderButton) {
        mAdapter = adapter;
        mCachedViewPositions = false;
        mHasFolderButton = hasFolderButton;
    }

    /**
     * Overriding this method allows us to "catch" clicks in the checkbox or star
     * and process them accordingly.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mAdapter.isOnDeletingAccountView(mAccountId)) {
            return true;
        }

        // Short-circuit all of this for list items w/o folder buttons
        if (!mHasFolderButton) {
            return super.onTouchEvent(event);
        }

        boolean handled = false;
        int touchX = (int) event.getX();

        if (!mCachedViewPositions) {
            float paddingScale = getContext().getResources().getDisplayMetrics().density;
            int folderPadding = (int) ((FOLDER_PAD * paddingScale) + 0.5);
            mFolderLeft = findViewById(R.id.folder_button).getLeft() - folderPadding;
            mCachedViewPositions = true;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mDownEvent = true;
                if (touchX > mFolderLeft) {
                    handled = true;
                }
                break;

            case MotionEvent.ACTION_CANCEL:
                mDownEvent = false;
                break;

            case MotionEvent.ACTION_UP:
                if (mDownEvent) {
                    if (touchX > mFolderLeft) {
                        mAdapter.onClickFolder(this);
                        handled = true;
                    }
                }
                break;
        }

        if (handled) {
            postInvalidate();
        } else {
            handled = super.onTouchEvent(event);
        }

        return handled;
    }
}
