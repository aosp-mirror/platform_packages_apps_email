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
import android.app.Fragment;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnFocusChangeListener;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import com.android.email.R;
import com.android.email.activity.UiUtilities;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.Utility;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Common base class for server settings fragments, so they can be more easily manipulated by
 * AccountSettingsXL.  Provides the following common functionality:
 *
 * Activity-provided callbacks
 * Activity callback during onAttach
 * Present "Next" button and respond to its clicks
 */
public abstract class AccountServerBaseFragment extends Fragment
        implements AccountCheckSettingsFragment.Callbacks, OnClickListener {

    public static Bundle sSetupModeArgs = null;
    protected static URI sDefaultUri;

    private static final String BUNDLE_KEY_SETTINGS = "AccountServerBaseFragment.settings";
    private static final String BUNDLE_KEY_ACTIVITY_TITLE = "AccountServerBaseFragment.title";

    protected Context mContext;
    protected Callback mCallback = EmptyCallback.INSTANCE;
    /**
     * Whether or not we are in "settings mode". We re-use the same screens for both the initial
     * account creation as well as subsequent account modification. If <code>mSettingsMode</code>
     * if <code>false</code>, we are in account creation mode. Otherwise, we are in account
     * modification mode.
     */
    protected boolean mSettingsMode;
    /*package*/ HostAuth mLoadedSendAuth;
    /*package*/ HostAuth mLoadedRecvAuth;

    // This is null in the setup wizard screens, and non-null in AccountSettings mode
    private Button mProceedButton;
    // This is used to debounce multiple clicks on the proceed button (which does async work)
    private boolean mProceedButtonPressed;
    /*package*/ String mBaseScheme = "protocol";

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
         * Called when account checker completes.  Fragments are responsible for saving
         * own edited data;  This is primarily for the activity to do post-check navigation.
         * @param result check settings result code - success is CHECK_SETTINGS_OK
         * @param setupMode signals if we were editing or creating
         */
        public void onCheckSettingsComplete(int result, int setupMode);
    }

    private static class EmptyCallback implements Callback {
        public static final Callback INSTANCE = new EmptyCallback();
        @Override public void onEnableProceedButtons(boolean enable) { }
        @Override public void onProceedNext(int checkMode, AccountServerBaseFragment target) { }
        @Override public void onCheckSettingsComplete(int result, int setupMode) { }
    }

    /**
     * Get the static arguments bundle that forces a server settings fragment into "settings" mode
     * (If not included, you'll be in "setup" mode which behaves slightly differently.)
     */
    public static synchronized Bundle getSettingsModeArgs() {
        if (sSetupModeArgs == null) {
            sSetupModeArgs = new Bundle();
            sSetupModeArgs.putBoolean(BUNDLE_KEY_SETTINGS, true);
        }
        return sSetupModeArgs;
    }

    public AccountServerBaseFragment() {
        if (sDefaultUri == null) {
            try {
                sDefaultUri = new URI("");
            } catch (URISyntaxException ignore) {
                // ignore; will never happen
            }
        }
    }

    /**
     * At onCreate time, read the fragment arguments
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get arguments, which modally switch us into "settings" mode (different appearance)
        mSettingsMode = false;
        if (getArguments() != null) {
            mSettingsMode = getArguments().getBoolean(BUNDLE_KEY_SETTINGS);
        }
    }

    /**
     * Called from onCreateView, to do settings mode configuration
     */
    protected void onCreateViewSettingsMode(View view) {
        if (mSettingsMode) {
            UiUtilities.getView(view, R.id.cancel).setOnClickListener(this);
            mProceedButton = (Button) UiUtilities.getView(view, R.id.done);
            mProceedButton.setOnClickListener(this);
            mProceedButton.setEnabled(false);
        }
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        // startPreferencePanel launches this fragment with the right title initially, but
        // if the device is rotate we must set the title ourselves
        if (mSettingsMode && savedInstanceState != null) {
            getActivity().setTitle(savedInstanceState.getString(BUNDLE_KEY_ACTIVITY_TITLE));
        }
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putString(BUNDLE_KEY_ACTIVITY_TITLE, (String) getActivity().getTitle());
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mContext = activity;
    }

    @Override
    public void onDetach() {
        super.onDetach();

        // Ensure that we don't have any callbacks at this point.
        mCallback = EmptyCallback.INSTANCE;
    }

    @Override
    public void onPause() {
        // Hide the soft keyboard if we lose focus
        InputMethodManager imm =
                (InputMethodManager)mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getView().getWindowToken(), 0);
        super.onPause();
    }

    /**
     * Implements OnClickListener
     */
    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.cancel:
                getActivity().onBackPressed();
                break;
            case R.id.done:
                // Simple debounce - just ignore while checks are underway
                if (mProceedButtonPressed) {
                    return;
                }
                mProceedButtonPressed = true;
                onNext();
                break;
        }
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
        // If we are in settings "mode" we may be showing our own next button, and we'll
        // enable it directly, here
        if (mProceedButton != null) {
            mProceedButton.setEnabled(enable);
        }
        clearButtonBounce();

        // TODO: This supports the phone UX activities and will be removed
        mCallback.onEnableProceedButtons(enable);
    }

    /**
     * Performs async operations as part of saving changes to the settings.
     *      Check for duplicate account
     *      Display dialog if necessary
     *      Else, proceed via mCallback.onProceedNext
     */
    protected void startDuplicateTaskCheck(long accountId, String checkHost, String checkLogin,
            int checkSettingsMode) {
        new DuplicateCheckTask(accountId, checkHost, checkLogin, checkSettingsMode)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
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
                        InputMethodManager imm = (InputMethodManager)mContext.
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
     * A keyboard listener which dismisses the keyboard when "DONE" is pressed, but doesn't muck
     * around with focus. This is useful in settings screens, as we don't want focus to change
     * since some fields throw up errors when they're focused to give the user more info.
     */
    protected final OnEditorActionListener mDismissImeOnDoneListener =
            new OnEditorActionListener() {
        @Override
        public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                // Dismiss soft keyboard but don't modify focus.
                final Context context = getActivity();
                if (context == null) {
                    return false;
                }
                InputMethodManager imm = (InputMethodManager) context.getSystemService(
                        Context.INPUT_METHOD_SERVICE);
                if (imm != null && imm.isActive()) {
                    imm.hideSoftInputFromWindow(getView().getWindowToken(), 0);
                }
                return true;
            }
            return false;
        }
    };

    /**
     * Clears the "next" button de-bounce flags and allows the "next" button to activate.
     */
    private void clearButtonBounce() {
        mProceedButtonPressed = false;
    }

    private class DuplicateCheckTask extends AsyncTask<Void, Void, Account> {

        private final long mAccountId;
        private final String mCheckHost;
        private final String mCheckLogin;
        private final int mCheckSettingsMode;

        public DuplicateCheckTask(long accountId, String checkHost, String checkLogin,
                int checkSettingsMode) {
            mAccountId = accountId;
            mCheckHost = checkHost;
            mCheckLogin = checkLogin;
            mCheckSettingsMode = checkSettingsMode;
        }

        @Override
        protected Account doInBackground(Void... params) {
            Account account = Utility.findExistingAccount(mContext, mAccountId,
                    mCheckHost, mCheckLogin);
            return account;
        }

        @Override
        protected void onPostExecute(Account duplicateAccount) {
            AccountServerBaseFragment fragment = AccountServerBaseFragment.this;
            if (duplicateAccount != null) {
                // Show duplicate account warning
                DuplicateAccountDialogFragment dialogFragment =
                    DuplicateAccountDialogFragment.newInstance(duplicateAccount.mDisplayName);
                dialogFragment.show(fragment.getFragmentManager(),
                        DuplicateAccountDialogFragment.TAG);
            } else {
                // Otherwise, proceed with the save/check
                mCallback.onProceedNext(mCheckSettingsMode, fragment);
            }
            clearButtonBounce();
        }
    }

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     *
     * Handle OK or error result from check settings.  Save settings (async), and then
     * exit to previous fragment.
     */
    @Override
    public void onCheckSettingsComplete(final int settingsResult) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                if (settingsResult == AccountCheckSettingsFragment.CHECK_SETTINGS_OK) {
                    if (SetupData.getFlowMode() == SetupData.FLOW_MODE_EDIT) {
                        saveSettingsAfterEdit();
                    } else {
                        saveSettingsAfterSetup();
                    }
                }
                return null;
            }

            @Override
            protected void onPostExecute(Void result) {
                // Signal to owning activity that a settings check completed
                mCallback.onCheckSettingsComplete(settingsResult, SetupData.getFlowMode());
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Implements AccountCheckSettingsFragment.Callbacks
     * This is overridden only by AccountSetupExchange
     */
    @Override
    public void onAutoDiscoverComplete(int result, HostAuth hostAuth) {
        throw new IllegalStateException();
    }

    /**
     * Returns whether or not any settings have changed.
     */
    public boolean haveSettingsChanged() {
        Account account = SetupData.getAccount();

        HostAuth sendAuth = account.getOrCreateHostAuthSend(mContext);
        boolean sendChanged = (mLoadedSendAuth != null && !mLoadedSendAuth.equals(sendAuth));

        HostAuth recvAuth = account.getOrCreateHostAuthRecv(mContext);
        boolean recvChanged = (mLoadedRecvAuth != null && !mLoadedRecvAuth.equals(recvAuth));

        return sendChanged || recvChanged;
    }

    /**
     * Save settings after "OK" result from checker.  Concrete classes must implement.
     * This is called from a worker thread and is allowed to perform DB operations.
     */
    public abstract void saveSettingsAfterEdit();

    /**
     * Save settings after "OK" result from checker.  Concrete classes must implement.
     * This is called from a worker thread and is allowed to perform DB operations.
     */
    public abstract void saveSettingsAfterSetup();

    /**
     * Respond to a click of the "Next" button.  Concrete classes must implement.
     */
    public abstract void onNext();
}
