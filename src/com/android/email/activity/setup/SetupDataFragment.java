package com.android.email.activity.setup;

import android.accounts.AccountAuthenticatorResponse;
import android.app.Fragment;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Policy;

/**
 * Headless fragment to hold setup data for the account setup or settings flows
 */
public class SetupDataFragment extends Fragment implements Parcelable {
    // The "extra" name for the Bundle saved with SetupData
    public static final String EXTRA_SETUP_DATA = "com.android.email.setupdata";

    // NORMAL is the standard entry from the Email app; EAS and POP_IMAP are used when entering via
    // Settings -> Accounts
    public static final int FLOW_MODE_UNSPECIFIED = -1;
    public static final int FLOW_MODE_NORMAL = 0;
    public static final int FLOW_MODE_ACCOUNT_MANAGER = 1;
    public static final int FLOW_MODE_EDIT = 3;
    public static final int FLOW_MODE_FORCE_CREATE = 4;
    // The following two modes are used to "pop the stack" and return from the setup flow.  We
    // either return to the caller (if we're in an account type flow) or go to the message list
    public static final int FLOW_MODE_RETURN_TO_CALLER = 5;
    public static final int FLOW_MODE_RETURN_TO_MESSAGE_LIST = 6;
    public static final int FLOW_MODE_RETURN_NO_ACCOUNTS_RESULT = 7;
    public static final int FLOW_MODE_NO_ACCOUNTS = 8;

    // Mode bits for AccountSetupCheckSettings, indicating the type of check requested
    public static final int CHECK_INCOMING = 1;
    public static final int CHECK_OUTGOING = 2;
    public static final int CHECK_AUTODISCOVER = 4;

    private static final String SAVESTATE_FLOWMODE = "flowMode";
    private static final String SAVESTATE_FLOWACCOUNTTYPE = "flowAccountType";
    private static final String SAVESTATE_ACCOUNT = "account";
    private static final String SAVESTATE_USERNAME = "username";
    private static final String SAVESTATE_PASSWORD = "password";
    private static final String SAVESTATE_CHECKSETTINGSMODE = "checkSettingsMode";
    private static final String SAVESTATE_ALLOWAUTODISCOVER = "allowAutoDiscover";
    private static final String SAVESTATE_POLICY = "policy";
    private static final String SAVESTATE_ACCOUNTAUTHENTICATORRESPONSE =
            "accountAuthenticatorResponse";
    private static final String SAVESTATE_REPORT_AUTHENTICATION_ERROR =
            "reportAuthenticationError";

    // All access will be through getters/setters
    private int mFlowMode = FLOW_MODE_NORMAL;
    private String mFlowAccountType;
    private Account mAccount;
    private String mUsername;
    private String mPassword;
    private int mCheckSettingsMode = 0;
    private boolean mAllowAutodiscover = true;
    private Policy mPolicy;
    private AccountAuthenticatorResponse mAccountAuthenticatorResponse = null;
    private boolean mReportAccountAuthenticationError = false;

    public interface SetupDataContainer {
        public SetupDataFragment getSetupData();
        public void setSetupData(SetupDataFragment setupData);
    }

    public SetupDataFragment() {
        mPolicy = null;
        mAllowAutodiscover = true;
        mCheckSettingsMode = 0;
        mAccount = new Account();
        mUsername = null;
        mPassword = null;
        mAccountAuthenticatorResponse = null;
        mReportAccountAuthenticationError = false;
    }

    public SetupDataFragment(int flowMode) {
        this();
        mFlowMode = flowMode;
    }

    public SetupDataFragment(int flowMode, String accountType) {
        this(flowMode);
        mFlowAccountType = accountType;
    }

    public SetupDataFragment(int flowMode, Account account) {
        this(flowMode);
        mAccount = account;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SAVESTATE_FLOWMODE, mFlowMode);
        outState.putString(SAVESTATE_FLOWACCOUNTTYPE, mFlowAccountType);
        outState.putParcelable(SAVESTATE_ACCOUNT, mAccount);
        outState.putString(SAVESTATE_USERNAME, mUsername);
        outState.putString(SAVESTATE_PASSWORD, mPassword);
        outState.putInt(SAVESTATE_CHECKSETTINGSMODE, mCheckSettingsMode);
        outState.putBoolean(SAVESTATE_ALLOWAUTODISCOVER, mAllowAutodiscover);
        outState.putParcelable(SAVESTATE_POLICY, mPolicy);
        outState.putParcelable(SAVESTATE_ACCOUNTAUTHENTICATORRESPONSE,
                mAccountAuthenticatorResponse);
        outState.putBoolean(SAVESTATE_REPORT_AUTHENTICATION_ERROR,
                mReportAccountAuthenticationError);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mFlowMode = savedInstanceState.getInt(SAVESTATE_FLOWMODE);
            mFlowAccountType = savedInstanceState.getString(SAVESTATE_FLOWACCOUNTTYPE);
            mAccount = savedInstanceState.getParcelable(SAVESTATE_ACCOUNT);
            mUsername = savedInstanceState.getString(SAVESTATE_USERNAME);
            mPassword = savedInstanceState.getString(SAVESTATE_PASSWORD);
            mCheckSettingsMode = savedInstanceState.getInt(SAVESTATE_CHECKSETTINGSMODE);
            mAllowAutodiscover = savedInstanceState.getBoolean(SAVESTATE_ALLOWAUTODISCOVER);
            mPolicy = savedInstanceState.getParcelable(SAVESTATE_POLICY);
            mAccountAuthenticatorResponse =
                    savedInstanceState.getParcelable(SAVESTATE_ACCOUNTAUTHENTICATORRESPONSE);
            mReportAccountAuthenticationError =
                    savedInstanceState.getBoolean(SAVESTATE_REPORT_AUTHENTICATION_ERROR, false);
        }
        setRetainInstance(true);
    }

    // Getters and setters
    public int getFlowMode() {
        return mFlowMode;
    }

    public void setFlowMode(int flowMode) {
        mFlowMode = flowMode;
    }

    public String getFlowAccountType() {
        return mFlowAccountType;
    }

    public void setFlowAccountType(String flowAccountType) {
        mFlowAccountType = flowAccountType;
    }

    public Account getAccount() {
        return mAccount;
    }

    public void setAccount(Account account) {
        mAccount = account;
    }

    public String getUsername() {
        return mUsername;
    }

    public void setUsername(String username) {
        mUsername = username;
    }

    public String getPassword() {
        return mPassword;
    }

    public void setPassword(String password) {
        mPassword = password;
    }

    public int getCheckSettingsMode() {
        return mCheckSettingsMode;
    }

    public void setCheckSettingsMode(int checkSettingsMode) {
        mCheckSettingsMode = checkSettingsMode;
    }

    public boolean isAllowAutodiscover() {
        return mAllowAutodiscover;
    }

    public void setAllowAutodiscover(boolean allowAutodiscover) {
        mAllowAutodiscover = allowAutodiscover;
    }

    public Policy getPolicy() {
        return mPolicy;
    }

    public void setPolicy(Policy policy) {
        mPolicy = policy;
    }

    public AccountAuthenticatorResponse getAccountAuthenticatorResponse() {
        return mAccountAuthenticatorResponse;
    }

    public void setAccountAuthenticatorResponse(
            AccountAuthenticatorResponse accountAuthenticatorResponse) {
        mAccountAuthenticatorResponse = accountAuthenticatorResponse;
    }

    public boolean getReportAccountAuthenticationError() {
        return mReportAccountAuthenticationError;
    }

    public void setReportAccountAuthenticationError(final boolean report) {
        mReportAccountAuthenticationError = report;
    }

    // Parcelable methods
    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<SetupDataFragment> CREATOR =
            new Parcelable.Creator<SetupDataFragment>() {
                @Override
                public SetupDataFragment createFromParcel(Parcel in) {
                    return new SetupDataFragment(in);
                }

                @Override
                public SetupDataFragment[] newArray(int size) {
                    return new SetupDataFragment[size];
                }
            };

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mFlowMode);
        dest.writeString(mFlowAccountType);
        dest.writeParcelable(mAccount, 0);
        dest.writeString(mUsername);
        dest.writeString(mPassword);
        dest.writeInt(mCheckSettingsMode);
        dest.writeInt(mAllowAutodiscover ? 1 : 0);
        dest.writeParcelable(mPolicy, 0);
        dest.writeParcelable(mAccountAuthenticatorResponse, 0);
    }

    public SetupDataFragment(Parcel in) {
        final ClassLoader loader = getClass().getClassLoader();
        mFlowMode = in.readInt();
        mFlowAccountType = in.readString();
        mAccount = in.readParcelable(loader);
        mUsername = in.readString();
        mPassword = in.readString();
        mCheckSettingsMode = in.readInt();
        mAllowAutodiscover = in.readInt() == 1;
        mPolicy = in.readParcelable(loader);
        mAccountAuthenticatorResponse = in.readParcelable(loader);
    }

    public String debugString() {
        final StringBuilder sb = new StringBuilder("SetupData");
        sb.append(":acct=");
        sb.append(mAccount == null ? "none" :mAccount.mId);
        if (mUsername != null) {
            sb.append(":user=");
            sb.append(mUsername);
        }
        if (mPassword != null) {
            sb.append(":pass=");
            sb.append(mPassword);
        }
        sb.append(":a/d=");
        sb.append(mAllowAutodiscover);
        sb.append(":check=");
        if ((mCheckSettingsMode & CHECK_INCOMING) != 0) sb.append("in+");
        if ((mCheckSettingsMode & CHECK_OUTGOING) != 0) sb.append("out+");
        if ((mCheckSettingsMode & CHECK_AUTODISCOVER) != 0) sb.append("a/d");
        sb.append(":policy=");
        sb.append(mPolicy == null ? "none" : "exists");
        return sb.toString();
    }

}
