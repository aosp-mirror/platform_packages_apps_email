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

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.TimeInterpolator;
import android.content.Context;
import android.content.res.Resources;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;

import com.android.email.R;
import com.android.emailcommon.Logging;

/**
 * The "three pane" layout used on tablet.
 *
 * This layout can show up to two panes at any given time, and operates in two different modes.
 * See {@link #isPaneCollapsible()} for details on the two modes.
 *
 * TODO Unit tests, when UX is settled.
 *
 * TODO onVisiblePanesChanged() should be called *AFTER* the animation, not before.
 */
public class ThreePaneLayout extends LinearLayout implements View.OnClickListener {
    private static final boolean ANIMATION_DEBUG = false; // DON'T SUBMIT WITH true

    private static final int ANIMATION_DURATION = ANIMATION_DEBUG ? 1000 : 150;
    private static final TimeInterpolator INTERPOLATOR = new DecelerateInterpolator(1.75f);

    /** Uninitialized state -- {@link #changePaneState} hasn't been called yet. */
    private static final int STATE_UNINITIALIZED = -1;

    /** Mailbox list + message list both visible. */
    private static final int STATE_LEFT_VISIBLE = 0;

    /**
     * A view where the MessageView is visible. The MessageList is visible if
     * {@link #isPaneCollapsible} is false, but is otherwise collapsed and hidden.
     */
    private static final int STATE_RIGHT_VISIBLE = 1;

    /**
     * A view where the MessageView is partially visible and a collapsible MessageList on the left
     * has been expanded to be in view. {@link #isPaneCollapsible} must return true for this
     * state to be active.
     */
    private static final int STATE_MIDDLE_EXPANDED = 2;

    // Flags for getVisiblePanes()
    public static final int PANE_LEFT = 1 << 2;
    public static final int PANE_MIDDLE = 1 << 1;
    public static final int PANE_RIGHT = 1 << 0;

    /** Current pane state.  See {@link #changePaneState} */
    private int mPaneState = STATE_UNINITIALIZED;

    /** See {@link #changePaneState} and {@link #onFirstSizeChanged} */
    private int mInitialPaneState = STATE_UNINITIALIZED;

    private View mLeftPane;
    private View mMiddlePane;
    private View mRightPane;
    private MessageCommandButtonView mMessageCommandButtons;

    // Views used only when the left pane is collapsible.
    private View mFoggedGlass;

    private boolean mFirstSizeChangedDone;

    /** Mailbox list width.  Comes from resources. */
    private int mMailboxListWidth;
    /**
     * Message list width, on:
     * - the message list + message view mode, when the left pane is not collapsible
     * - the message view + expanded message list mode, when the left pane is collapsible
     * Comes from resources.
     */
    private int mMessageListWidth;

    /** Hold last animator to cancel. */
    private Animator mLastAnimator;

    /**
     * Hold last animator listener to cancel.  See {@link #startLayoutAnimation} for why
     * we need both {@link #mLastAnimator} and {@link #mLastAnimatorListener}
     */
    private AnimatorListener mLastAnimatorListener;

    // 2nd index for {@link #changePaneState}
    private static final int INDEX_VISIBLE = 0;
    private static final int INDEX_INVISIBLE = 1;
    private static final int INDEX_GONE = 2;

    // Arrays used in {@link #changePaneState}
    // First index: STATE_*
    // Second index: INDEX_*
    private View[][][] mShowHideViews;

    private Callback mCallback = EmptyCallback.INSTANCE;

    public interface Callback {
        /** Called when {@link ThreePaneLayout#getVisiblePanes()} has changed. */
        public void onVisiblePanesChanged(int previousVisiblePanes);
    }

    private static final class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();

        @Override public void onVisiblePanesChanged(int previousVisiblePanes) {}
    }

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

        mLeftPane = findViewById(R.id.left_pane);
        mMiddlePane = findViewById(R.id.middle_pane);
        mMessageCommandButtons =
                (MessageCommandButtonView) findViewById(R.id.message_command_buttons);

        mFoggedGlass = findViewById(R.id.fogged_glass);
        if (mFoggedGlass != null) {
            mRightPane = findViewById(R.id.right_pane_with_fog);
            mFoggedGlass.setOnClickListener(this);
        } else {
            mRightPane = findViewById(R.id.right_pane);
        }

        if (!isPaneCollapsible()) {
            mShowHideViews = new View[][][] {
                    // STATE_LEFT_VISIBLE
                    {
                        {mLeftPane, mMiddlePane}, // Visible
                        {mRightPane}, // Invisible
                        {mMessageCommandButtons}, // Gone
                    },
                    // STATE_RIGHT_VISIBLE
                    {
                        {mMiddlePane, mMessageCommandButtons, mRightPane}, // Visible
                        {mLeftPane}, // Invisible
                        {}, // Gone
                    },
                    // STATE_MIDDLE_EXPANDED
                    {
                        {}, // Visible
                        {}, // Invisible
                        {}, // Gone
                    },
            };
        } else {
            mShowHideViews = new View[][][] {
                    // STATE_LEFT_VISIBLE
                    {
                        {mLeftPane, mMiddlePane}, // Visible
                        {mRightPane, mFoggedGlass}, // Invisible
                        {mMessageCommandButtons}, // Gone
                    },
                    // STATE_RIGHT_VISIBLE
                    {
                        {mRightPane, mMessageCommandButtons}, // Visible
                        {mLeftPane, mMiddlePane, mFoggedGlass}, // Invisible
                        {}, // Gone
                    },
                    // STATE_MIDDLE_EXPANDED
                    {
                        {mMiddlePane, mRightPane, mMessageCommandButtons, mFoggedGlass}, // Visible
                        {mLeftPane}, // Invisible
                        {}, // Gone
                    },
            };
        }

        mInitialPaneState = STATE_LEFT_VISIBLE;

        final Resources resources = getResources();
        mMailboxListWidth = getResources().getDimensionPixelSize(
                R.dimen.mailbox_list_width);
        mMessageListWidth = getResources().getDimensionPixelSize(R.dimen.message_list_width);
    }


    public void setCallback(Callback callback) {
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
    }

    /**
     * Return whether or not the left pane should be collapsible.
     */
    public boolean isPaneCollapsible() {
        return mFoggedGlass != null;
    }

    public MessageCommandButtonView getMessageCommandButtons() {
        return mMessageCommandButtons;
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
        mInitialPaneState = ss.mPaneState;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!mFirstSizeChangedDone) {
            mFirstSizeChangedDone = true;
            onFirstSizeChanged();
        }
    }

    /**
     * @return bit flags for visible panes.  Combination of {@link #PANE_LEFT}, {@link #PANE_MIDDLE}
     * and {@link #PANE_RIGHT},
     */
    public int getVisiblePanes() {
        int ret = 0;
        if (mLeftPane.getVisibility() == View.VISIBLE) ret |= PANE_LEFT;
        if (mMiddlePane.getVisibility() == View.VISIBLE) ret |= PANE_MIDDLE;
        if (mRightPane.getVisibility() == View.VISIBLE) ret |= PANE_RIGHT;
        return ret;
    }

    public boolean isLeftPaneVisible() {
        return mLeftPane.getVisibility() == View.VISIBLE;
    }
    public boolean isMiddlePaneVisible() {
        return mMiddlePane.getVisibility() == View.VISIBLE;
    }
    public boolean isRightPaneVisible() {
        return mRightPane.getVisibility() == View.VISIBLE;
    }

    /**
     * Handles the back event.
     *
     */
    public boolean uncollapsePane() {
        if (!isPaneCollapsible()) {
            return false;
        }

        if (mPaneState == STATE_RIGHT_VISIBLE) {
            return changePaneState(STATE_MIDDLE_EXPANDED, true);
        }

        return false;
    }

    /**
     * Show the left most pane.  (i.e. mailbox list)
     */
    public boolean showLeftPane() {
        return changePaneState(STATE_LEFT_VISIBLE, true);
    }

    /**
     * Before the first call to {@link #onSizeChanged}, we don't know the width of the view, so we
     * can't layout properly.  We just remember all the requests to {@link #changePaneState}
     * until the first {@link #onSizeChanged}, at which point we actually change to the last
     * requested state.
     */
    private void onFirstSizeChanged() {
        if (mInitialPaneState != STATE_UNINITIALIZED) {
            changePaneState(mInitialPaneState, false);
            mInitialPaneState = STATE_UNINITIALIZED;
        }
    }

    /**
     * Show the right most pane.  (i.e. message view)
     */
    public boolean showRightPane() {
        return changePaneState(STATE_RIGHT_VISIBLE, true);
    }

    private boolean changePaneState(int newState, boolean animate) {
        if (!isPaneCollapsible() && (newState == STATE_MIDDLE_EXPANDED)) {
            newState = STATE_RIGHT_VISIBLE;
        }
        if (!mFirstSizeChangedDone) {
            // Before first onSizeChanged(), we don't know the width of the view, so we can't
            // layout properly.
            // Just remember the new state and return.
            mInitialPaneState = newState;
            return false;
        }
        if (newState == mPaneState) {
            return false;
        }
        // Just make sure the first transition doesn't animate.
        if (mPaneState == STATE_UNINITIALIZED) {
            animate = false;
        }

        final int previousVisiblePanes = getVisiblePanes();
        mPaneState = newState;

        // Animate to the new state.
        // (We still use animator even if animate == false; we just use 0 duration.)
        final int totalWidth = getMeasuredWidth();

        final int expectedMailboxLeft;
        final int expectedMessageListWidth;

        final String animatorLabel; // for debug purpose

        if (!isPaneCollapsible()) {
            setViewWidth(mLeftPane, mMailboxListWidth);
            setViewWidth(mRightPane, totalWidth - mMessageListWidth);

            switch (mPaneState) {
                case STATE_LEFT_VISIBLE:
                    // mailbox + message list
                    animatorLabel = "moving to [mailbox list + message list]";
                    expectedMailboxLeft = 0;
                    expectedMessageListWidth = totalWidth - mMailboxListWidth;
                    break;
                case STATE_RIGHT_VISIBLE:
                    // message list + message view
                    animatorLabel = "moving to [message list + message view]";
                    expectedMailboxLeft = -mMailboxListWidth;
                    expectedMessageListWidth = mMessageListWidth;
                    break;
                default:
                    throw new IllegalStateException();
            }

        } else {
            setViewWidth(mLeftPane, mMailboxListWidth);
            setViewWidth(mRightPane, totalWidth);

            switch (mPaneState) {
                case STATE_LEFT_VISIBLE:
                    // message list + Message view -> mailbox + message list
                    animatorLabel = "moving to [mailbox list + message list]";
                    expectedMailboxLeft = 0;
                    expectedMessageListWidth = totalWidth - mMailboxListWidth;
                    break;
                case STATE_MIDDLE_EXPANDED:
                    // mailbox + message list -> message list + message view
                    animatorLabel = "moving to [message list + message view]";
                    expectedMailboxLeft = -mMailboxListWidth;
                    expectedMessageListWidth = mMessageListWidth;
                    break;
                case STATE_RIGHT_VISIBLE:
                    // message view only
                    animatorLabel = "moving to [message view]";
                    expectedMailboxLeft = -(mMailboxListWidth + mMessageListWidth);
                    expectedMessageListWidth = mMessageListWidth;
                    break;
                default:
                    throw new IllegalStateException();
            }
        }

        final View[][] showHideViews = mShowHideViews[mPaneState];
        final AnimatorListener listener = new AnimatorListener(animatorLabel,
                showHideViews[INDEX_VISIBLE],
                showHideViews[INDEX_INVISIBLE],
                showHideViews[INDEX_GONE],
                previousVisiblePanes);

        // Animation properties -- mailbox list left and message list width, at the same time.
        startLayoutAnimation(animate ? ANIMATION_DURATION : 0, listener,
                PropertyValuesHolder.ofInt(PROP_MAILBOX_LIST_LEFT,
                        getCurrentMailboxLeft(), expectedMailboxLeft),
                PropertyValuesHolder.ofInt(PROP_MESSAGE_LIST_WIDTH,
                        getCurrentMessageListWidth(), expectedMessageListWidth)
                );
        return true;
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
            case R.id.fogged_glass:
                if (!isPaneCollapsible()) {
                    return; // Shouldn't happen
                }
                changePaneState(STATE_RIGHT_VISIBLE, true);
                break;
        }
    }

    private void setViewWidth(View v, int value) {
        v.getLayoutParams().width = value;
        requestLayout();
    }

    private static final String PROP_MAILBOX_LIST_LEFT = "mailboxListLeftAnim";
    private static final String PROP_MESSAGE_LIST_WIDTH = "messageListWidthAnim";

    public void setMailboxListLeftAnim(int value) {
        ((ViewGroup.MarginLayoutParams) mLeftPane.getLayoutParams()).leftMargin = value;
        requestLayout();
    }

    public void setMessageListWidthAnim(int value) {
        setViewWidth(mMiddlePane, value);
    }

    private int getCurrentMailboxLeft() {
        return ((ViewGroup.MarginLayoutParams) mLeftPane.getLayoutParams()).leftMargin;
    }

    private int getCurrentMessageListWidth() {
        return mMiddlePane.getLayoutParams().width;
    }

    /**
     * Helper method to start animation.
     */
    private void startLayoutAnimation(int duration, AnimatorListener listener,
            PropertyValuesHolder... values) {
        if (mLastAnimator != null) {
            mLastAnimator.cancel();
        }
        if (mLastAnimatorListener != null) {
            if (ANIMATION_DEBUG) {
                Log.w(Logging.LOG_TAG, "Anim: Cancelling last animation: " + mLastAnimator);
            }
            // Animator.cancel() doesn't call listener.cancel() immediately, so sometimes
            // we end up cancelling the previous one *after* starting the next one.
            // Directly tell the listener it's cancelled to avoid that.
            mLastAnimatorListener.cancel();
        }

        final ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(
                this, values).setDuration(duration);
        animator.setInterpolator(INTERPOLATOR);
        if (listener != null) {
            animator.addListener(listener);
        }
        mLastAnimator = animator;
        mLastAnimatorListener = listener;
        animator.start();
    }

    /**
     * Animation listener.
     *
     * Update the visibility of each pane before/after an animation.
     */
    private class AnimatorListener implements Animator.AnimatorListener {
        private final String mLogLabel;
        private final View[] mViewsVisible;
        private final View[] mViewsInvisible;
        private final View[] mViewsGone;
        private final int mPreviousVisiblePanes;

        private boolean mCancelled;

        public AnimatorListener(String logLabel, View[] viewsVisible, View[] viewsInvisible,
                View[] viewsGone, int previousVisiblePanes) {
            mLogLabel = logLabel;
            mViewsVisible = viewsVisible;
            mViewsInvisible = viewsInvisible;
            mViewsGone = viewsGone;
            mPreviousVisiblePanes = previousVisiblePanes;
        }

        private void log(String message) {
            if (ANIMATION_DEBUG) {
                Log.w(Logging.LOG_TAG, "Anim: " + mLogLabel + "[" + this + "] " + message);
            }
        }

        public void cancel() {
            log("cancel");
            mCancelled = true;
        }

        /**
         * Show the about-to-become-visible panes before an animation.
         */
        @Override
        public void onAnimationStart(Animator animation) {
            log("start");
            for (View v : mViewsVisible) {
                v.setVisibility(View.VISIBLE);
            }

            // TODO These things, making invisible views and calling the visible pane changed
            // callback, should really be done in onAnimationEnd.
            // However, because we may want to initiate a fragment transaction in the callback but
            // by the time animation is done, the activity may be stopped (by user's HOME press),
            // it's not easy to get right.  For now, we just do this before the animation.
            for (View v : mViewsInvisible) {
                v.setVisibility(View.INVISIBLE);
            }
            for (View v : mViewsGone) {
                v.setVisibility(View.GONE);
            }
            mCallback.onVisiblePanesChanged(mPreviousVisiblePanes);
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
        }

        @Override
        public void onAnimationCancel(Animator animation) {
        }

        /**
         * Hide the about-to-become-hidden panes after an animation.
         */
        @Override
        public void onAnimationEnd(Animator animation) {
            if (mCancelled) {
                return; // But they shouldn't be hidden when cancelled.
            }
            log("end");
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
            out.writeInt(mPaneState);
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
