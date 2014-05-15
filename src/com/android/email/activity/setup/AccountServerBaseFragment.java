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

import android.app.Activity;
import android.app.LoaderManager;
import android.content.Context;
import android.content.Loader;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;

/**
 * Common base class for server settings fragments, so they can be more easily manipulated by
 * AccountSettingsXL.  Provides the following common functionality:
 *
 * Activity-provided callbacks
 * Activity callback during onAttach
 * Present "Next" button and respond to its clicks
 */
public abstract class AccountServerBaseFragment extends AccountSetupFragment
        implements OnClickListener {

    private static final String BUNDLE_KEY_SETTINGS = "AccountServerBaseFragment.settings";
    private static final String BUNDLE_KEY_ACTIVITY_TITLE = "AccountServerBaseFragment.title";
    private static final String BUNDLE_KEY_SAVING = "AccountServerBaseFragment.saving";
    private static final String BUNDLE_KEY_SENDAUTH = "AccountServerBaseFragment.sendAuth";
    private static final String BUNDLE_KEY_RECVAUTH = "AccountServerBaseFragment.recvAuth";

    protected Context mAppContext;
    /**
     * Whether or not we are in "settings mode". We re-use the same screens for both the initial
     * account creation as well as subsequent account modification. If this is
     * <code>false</code>, we are in account creation mode. Otherwise, we are in account
     * modification mode.
     */
    protected boolean mSettingsMode;
    protected HostAuth mLoadedSendAuth;
    protected HostAuth mLoadedRecvAuth;

    protected SetupDataFragment mSetupData;

    // This is null in the setup wizard screens, and non-null in AccountSettings mode
    private View mProceedButton;
    protected String mBaseScheme = "protocol";

    // Set to true if we're in the process of saving
    private boolean mSaving;

    /**
     // Used to post the callback once we're done saving, since we can't perform fragment
     // transactions from {@link LoaderManager.LoaderCallbacks#onLoadFinished(Loader, Object)}
     */
    private Handler mHandler = new Handler();

    /**
     * Callback interface that owning activities must provide
     */
    public interface Callback extends AccountSetupFragment.Callback {
        /**
         * Called when user clicks "next".  Starts account checker.
         * @param checkMode values from {@link SetupDataFragment}
         */
        public void onAccountServerUIComplete(int checkMode);
        public void onAccountServerSaveComplete();
    }

    /**
     * Creates and returns a bundle of arguments in the format we expect
     *
     * @param settingsMode True if we're in settings, false if we're in account creation
     * @return Arg bundle
     */
    public static Bundle getArgs(boolean settingsMode) {
        final Bundle setupModeArgs = new Bundle(1);
        setupModeArgs.putBoolean(BUNDLE_KEY_SETTINGS, settingsMode);
        return setupModeArgs;
    }

    public AccountServerBaseFragment() {}

    /**
     * At onCreate time, read the fragment arguments
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get arguments, which modally switch us into "settings" mode (different appearance)
        mSettingsMode = false;
        if (savedInstanceState != null) {
            mSettingsMode = savedInstanceState.getBoolean(BUNDLE_KEY_SETTINGS);
            mSaving = savedInstanceState.getBoolean(BUNDLE_KEY_SAVING);
            mLoadedSendAuth = savedInstanceState.getParcelable(BUNDLE_KEY_SENDAUTH);
            mLoadedRecvAuth = savedInstanceState.getParcelable(BUNDLE_KEY_RECVAUTH);
        } else if (getArguments() != null) {
            mSettingsMode = getArguments().getBoolean(BUNDLE_KEY_SETTINGS);
        }
        setHasOptionsMenu(true);
    }

    /**
     * Called from onCreateView, to do settings mode configuration
     */
    protected void onCreateViewSettingsMode(View view) {
        if (mSettingsMode) {
            UiUtilities.getView(view, R.id.cancel).setOnClickListener(this);
            mProceedButton = UiUtilities.getView(view, R.id.done);
            mProceedButton.setOnClickListener(this);
            mProceedButton.setEnabled(false);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        final Activity activity = getActivity();
        mAppContext = activity.getApplicationContext();
        if (mSettingsMode && savedInstanceState != null) {
            // startPreferencePanel launches this fragment with the right title initially, but
            // if the device is rotated we must set the title ourselves
            activity.setTitle(savedInstanceState.getString(BUNDLE_KEY_ACTIVITY_TITLE));
        }
        SetupDataFragment.SetupDataContainer container =
                (SetupDataFragment.SetupDataContainer) activity;
        mSetupData = container.getSetupData();

        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mSaving) {
            // We need to call this here in case the save completed while we weren't resumed
            saveSettings();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(BUNDLE_KEY_ACTIVITY_TITLE, (String) getActivity().getTitle());
        outState.putBoolean(BUNDLE_KEY_SETTINGS, mSettingsMode);
        outState.putParcelable(BUNDLE_KEY_SENDAUTH, mLoadedSendAuth);
        outState.putParcelable(BUNDLE_KEY_RECVAUTH, mLoadedRecvAuth);
    }

    @Override
    public void onPause() {
        // Hide the soft keyboard if we lose focus
        final InputMethodManager imm =
                (InputMethodManager) mAppContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getView().getWindowToken(), 0);
        super.onPause();
    }

    /**
     * Implements OnClickListener
     */
    @Override
    public void onClick(View v) {
        final int viewId = v.getId();
        if (viewId == R.id.cancel) {
            collectUserInputInternal();
            getActivity().onBackPressed();
        } else if (viewId == R.id.done) {
            collectUserInput();
        } else {
            super.onClick(v);
        }
    }

    /**
     * Enable/disable the "next" button
     */
    public void enableNextButton(boolean enable) {
        // If we are in settings "mode" we may be showing our own next button, and we'll
        // enable it directly, here
        if (mProceedButton != null) {
            mProceedButton.setEnabled(enable);
        } else {
            setNextButtonEnabled(enable);
        }
    }

    /**
     * Make the given text view uneditable. If the text view is ever focused, the specified
     * error message will be displayed.
     */
    protected void makeTextViewUneditable(final TextView view, final String errorMessage) {
        // We're editing an existing account; don't allow modification of the user name
        if (mSettingsMode) {
            view.setKeyListener(null);
            view.setFocusable(true);
            view.setOnFocusChangeListener(new OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    if (hasFocus) {
                        // Framework will not auto-hide IME; do it ourselves
                        InputMethodManager imm = (InputMethodManager) mAppContext.
                                getSystemService(Context.INPUT_METHOD_SERVICE);
                        imm.hideSoftInputFromWindow(getView().getWindowToken(), 0);
                        view.setError(errorMessage);
                    } else {
                        view.setError(null);
                    }
                }
            });
            view.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (view.getError() == null) {
                        view.setError(errorMessage);
                    } else {
                        view.setError(null);
                    }
                }
            });
        }
    }

    /**
     * Returns whether or not any settings have changed.
     */
    public boolean haveSettingsChanged() {
        collectUserInputInternal();
        final Account account = mSetupData.getAccount();

        final HostAuth sendAuth = account.getOrCreateHostAuthSend(mAppContext);
        final boolean sendChanged = (mLoadedSendAuth != null && !mLoadedSendAuth.equals(sendAuth));

        final HostAuth recvAuth = account.getOrCreateHostAuthRecv(mAppContext);
        final boolean recvChanged = (mLoadedRecvAuth != null && !mLoadedRecvAuth.equals(recvAuth));

        return sendChanged || recvChanged;
    }

    public void saveSettings() {
        getLoaderManager().initLoader(0, null, new LoaderManager.LoaderCallbacks<Boolean>() {
            @Override
            public Loader<Boolean> onCreateLoader(int id, Bundle args) {
                return getSaveSettingsLoader();
            }

            @Override
            public void onLoadFinished(Loader<Boolean> loader, Boolean data) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isResumed()) {
                            final Callback callback = (Callback) getActivity();
                            callback.onAccountServerSaveComplete();
                        }
                    }
                });
            }

            @Override
            public void onLoaderReset(Loader<Boolean> loader) {}
        });
    }

    public abstract Loader<Boolean> getSaveSettingsLoader();

    /**
     * Collect the user's input into the setup data object.  Concrete classes must implement.
     */
    public abstract int collectUserInputInternal();

    public void collectUserInput() {
        final int phase = collectUserInputInternal();
        final Callback callback = (Callback) getActivity();
        callback.onAccountServerUIComplete(phase);
    }
}
