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

import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

// TODO Collapsing the middle pane should cancel the selection mode on message list
// TODO Implement animation
// TODO On STATE_PORTRAIT_MIDDLE_EXPANDED state, right pane should be pushed out, rather than
// squished.

/**
 * The "three pane" layout used on tablet.
 *
 * It'll encapsulate the behavioral differences between portrait mode and landscape mode.
 */
public class ThreePaneLayout extends LinearLayout implements View.OnClickListener {

    /** Uninitialized state -- {@link #changePaneState} hasn't been called yet. */
    private static final int STATE_LEFT_UNINITIALIZED = 0;

    /** Mailbox list + message list */
    private static final int STATE_LEFT_VISIBLE = 1;

    /** Message view on portrait, + message list on landscape. */
    private static final int STATE_RIGHT_VISIBLE = 2;

    /** Portrait mode only: message view + expanded message list */
    private static final int STATE_PORTRAIT_MIDDLE_EXPANDED = 3;

    private int mPaneState = STATE_LEFT_UNINITIALIZED;

    private View mLeftPane;
    private View mMiddlePane;
    private View mRightPane;

    // Views used only on portrait
    private View mCollapser;
    private View mFoggedGlass;
    private View mRightWithFog;

    public ThreePaneLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initView();
    }

    public ThreePaneLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        initView();
    }

    public ThreePaneLayout(Context context) {
        super(context);
        initView();
    }

    /** Perform basic initialization */
    private void initView() {
        setOrientation(LinearLayout.HORIZONTAL); // Always horizontal
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        getViews();

        if (!isLandscape()) {
            mFoggedGlass.setOnClickListener(this);
            mCollapser.setOnClickListener(this);
        }

        changePaneState(STATE_LEFT_VISIBLE, false);
    }

    /**
     * Look for views and set to members.  {@link #isLandscape} can be used after this method.
     */
    private void getViews() {
        mLeftPane = findViewById(R.id.left_pane);
        mMiddlePane = findViewById(R.id.middle_pane);
        mRightPane = findViewById(R.id.right_pane);

        mCollapser = findViewById(R.id.collapser);
        if (mCollapser != null) { // If it's there, it's portrait.
            mFoggedGlass = findViewById(R.id.fogged_glass);
            mRightWithFog = findViewById(R.id.right_pane_with_fog);
        }
    }

    private boolean isLandscape() {
        return mCollapser == null;
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        SavedState ss = new SavedState(super.onSaveInstanceState());
        ss.mPaneState = mPaneState;
        return ss;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        // Called after onFinishInflate()
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        changePaneState(ss.mPaneState, false);
    }

    /**
     * Show/hide the right most pane.  (i.e. message view)
     */
    public void showRightPane(boolean show) {
        changePaneState(show ? STATE_RIGHT_VISIBLE : STATE_LEFT_VISIBLE, true);
    }

    private void changePaneState(int newState, boolean animate) {
        if (isLandscape() && (newState == STATE_PORTRAIT_MIDDLE_EXPANDED)) {
            newState = STATE_RIGHT_VISIBLE;
        }
        if (newState == mPaneState) {
            return;
        }
        mPaneState = newState;
        switch (mPaneState) {
            case STATE_LEFT_VISIBLE:
                mLeftPane.setVisibility(View.VISIBLE);

                if (isLandscape()) {
                    mMiddlePane.setVisibility(View.VISIBLE);
                    mRightPane.setVisibility(View.GONE);
                } else { // Portrait
                    mMiddlePane.setVisibility(View.VISIBLE);
                    mCollapser.setVisibility(View.GONE);

                    mRightWithFog.setVisibility(View.GONE);
                }

                break;
            case STATE_RIGHT_VISIBLE:
                mLeftPane.setVisibility(View.GONE);

                if (isLandscape()) {
                    mMiddlePane.setVisibility(View.VISIBLE);
                    mRightPane.setVisibility(View.VISIBLE);
                } else { // Portrait
                    mMiddlePane.setVisibility(View.GONE);
                    mCollapser.setVisibility(View.VISIBLE);

                    mRightWithFog.setVisibility(View.VISIBLE);
                    mRightPane.setVisibility(View.VISIBLE);
                    mFoggedGlass.setVisibility(View.GONE);
                }
                break;
            case STATE_PORTRAIT_MIDDLE_EXPANDED:
                mLeftPane.setVisibility(View.GONE);

                mMiddlePane.setVisibility(View.VISIBLE);
                mCollapser.setVisibility(View.VISIBLE);

                mRightWithFog.setVisibility(View.VISIBLE);
                mRightPane.setVisibility(View.VISIBLE);
                mFoggedGlass.setVisibility(View.VISIBLE);
                break;
        }
    }

    /**
     * @return The ID of the view for the left pane fragment.  (i.e. mailbox list)
     */
    public int getLeftPaneId() {
        return R.id.left_pane;
    }

    /**
     * @return The ID of the view for the middle pane fragment.  (i.e. message list)
     */
    public int getMiddlePaneId() {
        return R.id.middle_pane;
    }

    /**
     * @return The ID of the view for the right pane fragment.  (i.e. message view)
     */
    public int getRightPaneId() {
        return R.id.right_pane;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.collapser:
                if (isLandscape()) {
                    return; // Shouldn't happen
                }
                changePaneState((mPaneState == STATE_RIGHT_VISIBLE)
                        ? STATE_PORTRAIT_MIDDLE_EXPANDED
                        : STATE_RIGHT_VISIBLE, true);
                break;
            case R.id.fogged_glass:
                if (isLandscape()) {
                    return; // Shouldn't happen
                }
                changePaneState(STATE_RIGHT_VISIBLE, true);
                break;
        }
    }

    private static class SavedState extends BaseSavedState {
        int mPaneState;

        /**
         * Constructor called from {@link ThreePaneLayout#onSaveInstanceState()}
         */
        SavedState(Parcelable superState) {
            super(superState);
        }

        /**
         * Constructor called from {@link #CREATOR}
         */
        private SavedState(Parcel in) {
            super(in);
            mPaneState = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeLong(mPaneState);
        }

        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }
}
