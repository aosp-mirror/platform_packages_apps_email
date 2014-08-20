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

import android.app.ActionBar;
import android.app.Fragment;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;

import com.android.email.R;
import com.android.emailcommon.utility.IntentUtilities;
import com.android.mail.providers.UIProvider.EditSettingsExtras;
import com.android.mail.ui.settings.MailPreferenceActivity;
import com.android.mail.utils.Utils;

import java.util.List;

/**
 * Handles account preferences, using multi-pane arrangement when possible.
 *
 * This activity uses the following fragments:
 *   AccountSettingsFragment
 *   GeneralPreferences
 *   DebugFragment
 *
 */
public class EmailPreferenceActivity extends MailPreferenceActivity {
    /*
     * Intent to open account settings for account=1
        adb shell am start -a android.intent.action.EDIT \
            -d '"content://ui.email.android.com/settings?ACCOUNT_ID=1"'
     */

    // Intent extras for our internal activity launch
    private static final String EXTRA_ENABLE_DEBUG = "AccountSettings.enable_debug";
    // STOPSHIP: Do not ship with the debug menu allowed.
    private static final boolean DEBUG_MENU_ALLOWED = false;

    // Intent extras for launch directly from system account manager
    // NOTE: This string must match the one in res/xml/account_preferences.xml
    private static String INTENT_ACCOUNT_MANAGER_ENTRY;

    // Key codes used to open a debug settings fragment.
    private static final int[] SECRET_KEY_CODES = {
            KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_U,
            KeyEvent.KEYCODE_G
            };
    private int mSecretKeyCodeIndex = 0;

    // When the user taps "Email Preferences" 10 times in a row, we'll enable the debug settings.
    private int mNumGeneralHeaderClicked = 0;

    private boolean mShowDebugMenu;
    private Uri mFeedbackUri;
    private MenuItem mFeedbackMenuItem;

    @Override
    public Intent getIntent() {
        final Intent intent = super.getIntent();
        final long accountId = IntentUtilities.getAccountIdFromIntent(intent);
        if (accountId < 0) {
            return intent;
        }
        Intent modIntent = new Intent(intent);
        modIntent.putExtra(EXTRA_SHOW_FRAGMENT, AccountSettingsFragment.class.getCanonicalName());
        modIntent.putExtra(
                EXTRA_SHOW_FRAGMENT_ARGUMENTS,
                AccountSettingsFragment.buildArguments(
                        IntentUtilities.getAccountNameFromIntent(intent)));
        modIntent.putExtra(EXTRA_NO_HEADERS, true);
        return modIntent;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent i = getIntent();
        if (savedInstanceState == null) {
            // If we are not restarting from a previous instance, we need to
            // figure out the initial prefs to show.  (Otherwise, we want to
            // continue showing whatever the user last selected.)
            if (INTENT_ACCOUNT_MANAGER_ENTRY == null) {
                INTENT_ACCOUNT_MANAGER_ENTRY = getString(R.string.intent_account_manager_entry);
            }
            if (INTENT_ACCOUNT_MANAGER_ENTRY.equals(i.getAction())) {
                // This case occurs if we're changing account settings from Settings -> Accounts.
                // We get an account object in the intent, but it's not actually useful to us since
                // it's always just the first account of that type. The user can't specify which
                // account they wish to view from within the settings UI, so just dump them at the
                // main screen.
                // android.accounts.Account acct = i.getParcelableExtra("account");
            } else if (i.hasExtra(EditSettingsExtras.EXTRA_FOLDER)) {
                throw new IllegalArgumentException("EXTRA_FOLDER is no longer supported");
            } else {
                // Otherwise, we're called from within the Email app and look for our extras
                final long accountId = IntentUtilities.getAccountIdFromIntent(i);
                if (accountId != -1) {
                    final Bundle args = AccountSettingsFragment.buildArguments(accountId);
                    startPreferencePanel(AccountSettingsFragment.class.getName(), args,
                            0, null, null, 0);
                }
            }
        }
        mShowDebugMenu = i.getBooleanExtra(EXTRA_ENABLE_DEBUG, false);

        final ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayOptions(
                    ActionBar.DISPLAY_HOME_AS_UP, ActionBar.DISPLAY_HOME_AS_UP);
        }

        mFeedbackUri = Utils.getValidUri(getString(R.string.email_feedback_uri));
    }

    /**
     * Listen for secret sequence and, if heard, enable debug menu
     */
    @Override
    public boolean onKeyDown(int keyCode, @NonNull KeyEvent event) {
        if (event.getKeyCode() == SECRET_KEY_CODES[mSecretKeyCodeIndex]) {
            mSecretKeyCodeIndex++;
            if (mSecretKeyCodeIndex == SECRET_KEY_CODES.length) {
                mSecretKeyCodeIndex = 0;
                enableDebugMenu();
            }
        } else {
            mSecretKeyCodeIndex = 0;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.settings_menu, menu);

        mFeedbackMenuItem = menu.findItem(R.id.feedback_menu_item);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        if (mFeedbackMenuItem != null) {
            // We only want to enable the feedback menu item, if there is a valid feedback uri
            mFeedbackMenuItem.setVisible(!Uri.EMPTY.equals(mFeedbackUri));
        }
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                // The app icon on the action bar is pressed.  Just emulate a back press.
                // TODO: this should navigate to the main screen, even if a sub-setting is open.
                // But we shouldn't just finish(), as we want to show "discard changes?" dialog
                // when necessary.
                onBackPressed();
                break;
            case R.id.feedback_menu_item:
                Utils.sendFeedback(this, mFeedbackUri, false /* reportingProblem */);
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        return true;
    }

    @Override
    public boolean isValidFragment(String fragmentName) {
        // This activity is not exported, so we can allow any fragment
        return true;
    }

    private void enableDebugMenu() {
        mShowDebugMenu = true;
        invalidateHeaders();
    }

    private void onAddNewAccount() {
        final Intent setupIntent = AccountSetupFinal.actionNewAccountIntent(this);
        startActivity(setupIntent);
    }

    @Override
    public void onBuildExtraHeaders(List<Header> target) {
        super.onBuildExtraHeaders(target);

        loadHeadersFromResource(R.xml.email_extra_preference_headers, target);

        // if debug header is enabled, show it
        if (DEBUG_MENU_ALLOWED) {
            if (mShowDebugMenu) {
                // setup lightweight header for debugging
                final Header debugHeader = new Header();
                debugHeader.title = getText(R.string.debug_title);
                debugHeader.summary = null;
                debugHeader.iconRes = 0;
                debugHeader.fragment = DebugFragment.class.getCanonicalName();
                debugHeader.fragmentArguments = null;
                target.add(debugHeader);
            }
        }
    }

    /**
     * Called when the user selects an item in the header list.  Handles save-data cases as needed
     *
     * @param header The header that was selected.
     * @param position The header's position in the list.
     */
    @Override
    public void onHeaderClick(@NonNull Header header, int position) {
        // Secret keys:  Click 10x to enable debug settings
        if (position == 0) {
            mNumGeneralHeaderClicked++;
            if (mNumGeneralHeaderClicked == 10) {
                enableDebugMenu();
            }
        } else {
            mNumGeneralHeaderClicked = 0;
        }
        if (header.id == R.id.add_account_header) {
            onAddNewAccount();
            return;
        }

        // Process header click normally
        super.onHeaderClick(header, position);
    }

    @Override
    public void onAttachFragment(Fragment f) {
        super.onAttachFragment(f);
        // When we're changing fragments, enable/disable the add account button
        invalidateOptionsMenu();
    }
}
