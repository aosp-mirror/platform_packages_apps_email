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

package com.android.email.activity.setup;

import com.android.email.R;

import android.app.Activity;
import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;

/**
 * Common base class for server settings fragments, so they can be more easily manipulated by
 * AccountSettingsXL.  Provides the following common functionality:
 *
 * Activity-provided callbacks
 * Activity callback during onAttach
 * Present "Next" button and respond to its clicks
 */
public abstract class AccountServerBaseFragment extends Fragment
        implements AccountCheckSettingsFragment.Callbacks {

    protected Context mContext;
    protected Callback mCallback = EmptyCallback.INSTANCE;
    protected boolean mNextButtonEnabled;

    /**
     * Callback interface that owning activities must provide
     */
    public interface Callback {
        /**
         * Called each time the user-entered input transitions between valid and invalid
         * @param enable true to enable proceed/next button, false to disable
         */
        public void onEnableProceedButtons(boolean enable);

        /**
         * Called when user clicks "next".  Starts account checker.
         * @param checkMode values from {@link SetupData}
         * @param target the fragment that requested the check
         */
        public void onProceedNext(int checkMode, AccountServerBaseFragment target);

        /**
         * Called when account checker returns "ok".  Fragments are responsible for saving
         * own edited data;  This is primarily for the activity to do post-check navigation.
         * @param setupMode signals if we were editing or creating
         */
        public void onCheckSettingsOk(int setupMode);
    }

    private static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onEnableProceedButtons(boolean enable) { }
        @Override public void onProceedNext(int checkMode, AccountServerBaseFragment target) { }
        @Override public void onCheckSettingsOk(int setupMode) { }
    }

    /**
     * Called when a fragment is first attached to its activity.
     * {@link #onCreate(Bundle)} will be called after this.
     */
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mContext = activity;

        // Notify the activity that we're here.
        if (activity instanceof AccountSettingsXL) {
            ((AccountSettingsXL)activity).onAttach(this);
        }
    }

    /**
     * Called to do initial creation of a fragment.  This is called after
     * {@link #onAttach(Activity)} and before {@link #onActivityCreated(Bundle)}.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setHasOptionsMenu(true);
        mNextButtonEnabled = false;
    }

    // Add a "Next" button when this fragment is displayed
    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.account_setup_next_option, menu);
    }

    /**
     * Enable/disable "next" button
     */
    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(R.id.next);
        item.setEnabled(mNextButtonEnabled);
    }

    /**
     * Respond to clicks in the "Next" button
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.next:
                onNext();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Activity provides callbacks here.
     */
    public void setCallback(Callback callback) {
        mCallback = (callback == null) ? EmptyCallback.INSTANCE : callback;
        mContext = getActivity();
    }

    /**
     * Enable/disable the "next" button
     */
    public void enableNextButton(boolean enable) {
        // We have to set mNextButtonEnabled first, because invalidateOptionsMenu calls
        // onPrepareOptionsMenu immediately
        boolean wasEnabled = mNextButtonEnabled;
        mNextButtonEnabled = enable;

        if (enable != wasEnabled) {
            getActivity().invalidateOptionsMenu();
        }

        // TODO: This supports the legacy activities and will be removed
        mCallback.onEnableProceedButtons(enable);
    }

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     *
     * Handle OK result from check settings.  Save settings, and exit to previous fragment.
     */
    @Override
    public void onCheckSettingsOk() {
        if (SetupData.getFlowMode() == SetupData.FLOW_MODE_EDIT) {
            saveSettingsAfterEdit();
        } else {
            saveSettingsAfterSetup();
        }
        // Signal to owning activity that a settings check was OK
        mCallback.onCheckSettingsOk(SetupData.getFlowMode());
    }

    /**
     * Save settings after "OK" result from checker.  Concrete classes must implement.
     */
    public abstract void saveSettingsAfterEdit();

    /**
     * Save settings after "OK" result from checker.  Concrete classes must implement.
     */
    public abstract void saveSettingsAfterSetup();

    /**
     * Respond to a click of the "Next" button.  Concrete classes must implement.
     */
    public abstract void onNext();
}
