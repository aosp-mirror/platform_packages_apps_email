/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.email.activity.setup;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.google.common.annotations.VisibleForTesting;

/**
 * Superclass for setup UI fragments.
 * Currently holds a super-interface for the callbacks, as well as the state it was launched for so
 * we can unwind things correctly when the user navigates the back stack.
 */
public class AccountSetupFragment extends Fragment implements View.OnClickListener {
    private static final String SAVESTATE_STATE = "AccountSetupFragment.state";
    private int mState;

    protected View mNextButton;
    protected View mPreviousButton;

    public interface Callback {
        void onNextButton();
        void onBackPressed();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mState = savedInstanceState.getInt(SAVESTATE_STATE);
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SAVESTATE_STATE, mState);
    }

    public void setState(int state) {
        mState = state;
    }

    public int getState() {
        return mState;
    }

    /**
     * This method wraps the given content layout with the chrome appropriate for the account setup
     * flow. It also attaches itself as a click handler to the previous and next buttons.
     *
     * @param inflater LayoutInflater scoped to the appropriate context
     * @param container ViewGroup to inflate the view into
     * @param contentLayout Resource ID of the main content layout to insert into the template
     * @param headline Resource ID of the headline string
     * @return Fully inflated view hierarchy.
     */
    protected View inflateTemplatedView(final LayoutInflater inflater, final ViewGroup container,
            final int contentLayout, final int headline) {
        final View template = inflater.inflate(R.layout.account_setup_template, container, false);

        TextView headlineView = UiUtilities.getView(template, R.id.headline);
        if (headline > 0) {
            headlineView.setText(headline);
            headlineView.setVisibility(View.VISIBLE);
        } else {
            headlineView.setVisibility(View.GONE);
        }

        final ViewGroup contentContainer =
                (ViewGroup) template.findViewById(R.id.setup_fragment_content);
        inflater.inflate(contentLayout, contentContainer, true);

        mNextButton = UiUtilities.getView(template, R.id.next);
        mNextButton.setOnClickListener(this);
        mPreviousButton = UiUtilities.getView(template, R.id.previous);
        mPreviousButton.setOnClickListener(this);
        return template;
    }

    @Override
    public void onClick(View v) {
        final int viewId = v.getId();
        final Callback callback = (Callback) getActivity();

        if (viewId == R.id.next) {
            callback.onNextButton();
        } else if (viewId == R.id.previous) {
            callback.onBackPressed();
        }
    }

    public void setNextButtonEnabled(boolean enabled) {
        if (mNextButton != null) {
            mNextButton.setEnabled(enabled);
        }
    }

    public boolean isNextButtonEnabled() {
        return mNextButton.isEnabled();
    }


    /**
     * Set visibility of the "previous" button
     * @param visibility {@link View#INVISIBLE}, {@link View#VISIBLE}, {@link View#GONE}
     */
    public void setPreviousButtonVisibility(int visibility) {
        mPreviousButton.setVisibility(visibility);
    }

    /**
     * Set the visibility of the "next" button
     * @param visibility {@link View#INVISIBLE}, {@link View#VISIBLE}, {@link View#GONE}
     */
    public void setNextButtonVisibility(int visibility) {
        mNextButton.setVisibility(visibility);
    }
}
