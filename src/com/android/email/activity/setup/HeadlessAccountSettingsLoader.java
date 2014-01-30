package com.android.email.activity.setup;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceActivity;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.utility.IntentUtilities;

/**
 * This activity is headless. It exists to load the Account object from  the given account ID and
 * then starts the {@link AccountSettings} activity with the appropriate fragment showing in place.
 */
public class HeadlessAccountSettingsLoader extends Activity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent i = getIntent();
        final long accountID = IntentUtilities.getAccountIdFromIntent(i);

        if ("incoming".equals(i.getData().getLastPathSegment())) {
            new LoadAccountIncomingSettingsAsyncTask(getApplicationContext())
                    .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, accountID);
        }
    }

    /**
     * Asynchronously loads the Account object from its ID and then navigates to the AccountSettings
     * fragment.
     */
    private class LoadAccountIncomingSettingsAsyncTask extends AsyncTask<Long, Void, Account> {
        private final Context mContext;

        private LoadAccountIncomingSettingsAsyncTask(Context context) {
            mContext = context;
        }

        protected Account doInBackground(Long... params) {
            return Account.restoreAccountWithId(mContext, params[0]);
        }

        protected void onPostExecute(Account result) {
            // create an Intent to view a new activity
            final Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // we are navigating explicitly to the AccountSettings activity
            intent.setClass(mContext, AccountSettings.class);

            // place the account in the intent as an extra
            intent.putExtra(AccountSettings.EXTRA_ACCOUNT, result);

            // these extras show the "incoming fragment" in the AccountSettings activity by default
            intent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT,
                    AccountSetupIncomingFragment.class.getCanonicalName());
            intent.putExtra(PreferenceActivity.EXTRA_SHOW_FRAGMENT_ARGUMENTS,
                    AccountSetupIncomingFragment.getArgs(true));
            intent.putExtra(PreferenceActivity.EXTRA_NO_HEADERS, true);

            mContext.startActivity(intent);

            finish();
         }
    }
}