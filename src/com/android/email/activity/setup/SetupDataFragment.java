package com.android.email.activity.setup;

import android.app.Fragment;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.email.service.EmailServiceUtils;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
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
    // TODO: figure out if we still care about these
    public static final int FLOW_MODE_RETURN_TO_CALLER = 5;
    public static final int FLOW_MODE_RETURN_TO_MESSAGE_LIST = 6;
    public static final int FLOW_MODE_RETURN_NO_ACCOUNTS_RESULT = 7;
    public static final int FLOW_MODE_NO_ACCOUNTS = 8;

    // Mode bits for AccountSetupCheckSettings, indicating the type of check requested
    public static final int CHECK_INCOMING = 1;
    public static final int CHECK_OUTGOING = 2;
    public static final int CHECK_AUTODISCOVER = 4;

    private static final String SAVESTATE_FLOWMODE = "SetupDataFragment.flowMode";
    private static final String SAVESTATE_ACCOUNT = "SetupDataFragment.account";
    private static final String SAVESTATE_EMAIL = "SetupDataFragment.email";
    private static final String SAVESTATE_CREDENTIAL = "SetupDataFragment.credential";
    private static final String SAVESTATE_INCOMING_LOADED = "SetupDataFragment.incomingLoaded";
    private static final String SAVESTATE_OUTGOING_LOADED = "SetupDataFragment.outgoingLoaded";
    private static final String SAVESTATE_POLICY = "SetupDataFragment.policy";
    private static final String SAVESTATE_INCOMING_PROTOCOL = "SetupDataFragment.incomingProtocol";
    private static final String SAVESTATE_AM_PROTOCOL = "SetupDataFragment.amProtocol";

    // All access will be through getters/setters
    private int mFlowMode = FLOW_MODE_NORMAL;
    private Account mAccount;
    private String mEmail;
    private Bundle mCredentialResults;
    // These are used to track whether we've preloaded the login credentials into incoming/outgoing
    // settings. Set them to 'true' by default, and false when we change the credentials or email
    private boolean mIncomingCredLoaded = true;
    private boolean mOutgoingCredLoaded = true;
    // This is accessed off-thread in AccountCheckSettingsFragment
    private volatile Policy mPolicy;
    // Cache incoming protocol and service info here
    private EmailServiceUtils.EmailServiceInfo mIncomingServiceInfo;
    private String mIncomingProtocol;
    // Protocol the user chose in the account manager "Add an account" screen
    private String mAmProtocol;

    public interface SetupDataContainer {
        public SetupDataFragment getSetupData();
    }

    public SetupDataFragment() {
        mPolicy = null;
        setAccount(new Account());
        mEmail = null;
        mCredentialResults = null;
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(SAVESTATE_FLOWMODE, mFlowMode);
        outState.putParcelable(SAVESTATE_ACCOUNT, mAccount);
        outState.putString(SAVESTATE_EMAIL, mEmail);
        outState.putParcelable(SAVESTATE_CREDENTIAL, mCredentialResults);
        outState.putBoolean(SAVESTATE_INCOMING_LOADED, mIncomingCredLoaded);
        outState.putBoolean(SAVESTATE_OUTGOING_LOADED, mOutgoingCredLoaded);
        outState.putParcelable(SAVESTATE_POLICY, mPolicy);
        outState.putString(SAVESTATE_INCOMING_PROTOCOL, mIncomingProtocol);
        outState.putString(SAVESTATE_AM_PROTOCOL, mAmProtocol);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mFlowMode = savedInstanceState.getInt(SAVESTATE_FLOWMODE);
            setAccount((Account) savedInstanceState.getParcelable(SAVESTATE_ACCOUNT));
            mEmail = savedInstanceState.getString(SAVESTATE_EMAIL);
            mCredentialResults = savedInstanceState.getParcelable(SAVESTATE_CREDENTIAL);
            mIncomingCredLoaded = savedInstanceState.getBoolean(SAVESTATE_INCOMING_LOADED);
            mOutgoingCredLoaded = savedInstanceState.getBoolean(SAVESTATE_OUTGOING_LOADED);
            mPolicy = savedInstanceState.getParcelable(SAVESTATE_POLICY);
            mIncomingProtocol = savedInstanceState.getString(SAVESTATE_INCOMING_PROTOCOL);
            mAmProtocol = savedInstanceState.getString(SAVESTATE_AM_PROTOCOL);
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

    public Account getAccount() {
        return mAccount;
    }

    public void setAccount(Account account) {
        mAccount = account;
        mAccount.setTemporary(true);
    }

    public String getEmail() {
        return mEmail;
    }

    public void setEmail(String email) {
        mEmail = email;
        mAccount.mEmailAddress = email;
        mIncomingCredLoaded = false;
        mOutgoingCredLoaded = false;
    }

    public Bundle getCredentialResults() {
        return mCredentialResults;
    }

    public void setCredentialResults(Bundle credentialResults) {
        mCredentialResults = credentialResults;
        mIncomingCredLoaded = false;
        mOutgoingCredLoaded = false;
    }

    public boolean isIncomingCredLoaded() {
        return mIncomingCredLoaded;
    }

    public void setIncomingCredLoaded(boolean incomingCredLoaded) {
        mIncomingCredLoaded = incomingCredLoaded;
    }

    public boolean isOutgoingCredLoaded() {
        return mOutgoingCredLoaded;
    }

    public void setOutgoingCredLoaded(boolean outgoingCredLoaded) {
        mOutgoingCredLoaded = outgoingCredLoaded;
    }

    public synchronized Policy getPolicy() {
        return mPolicy;
    }

    public synchronized void setPolicy(Policy policy) {
        mPolicy = policy;
    }

    /**
     * Retrieve the service info for the incoming protocol
     * @param context For resolving the service info, and possibly loading the {@link HostAuth}
     * @return service info object
     */
    public EmailServiceUtils.EmailServiceInfo getIncomingServiceInfo(Context context) {
        if (mIncomingServiceInfo == null) {
            mIncomingServiceInfo = EmailServiceUtils.getServiceInfo(context,
                    getIncomingProtocol(context));
        }
        return mIncomingServiceInfo;
    }

    /**
     * Retrieve the protocol as previously set in setIncomingProtocol, but don't attempt to look at
     * {@link #mAccount#hostAuthRecv }
     * @return Protocol string
     */
    public String getIncomingProtocol() {
        return mIncomingProtocol;
    }

    /**
     * Retrieve the protocol as previously set in setIncomingProtocol, or from
     * {@link #mAccount#hostAuthRecv}. Try not to call this on the main thread if it's unlikely that
     * the hostauth isn't already loaded.
     * @param context context to possibly load the {@link HostAuth} from the provider
     * @return Protocol string
     */
    public String getIncomingProtocol(Context context) {
        if (mIncomingProtocol != null) {
            return mIncomingProtocol;
        }

        final HostAuth recvAuth = mAccount.getOrCreateHostAuthRecv(context);
        return recvAuth.mProtocol;
    }

    public void setIncomingProtocol(final Context context, final String protocol) {
        final HostAuth recvAuth = mAccount.getOrCreateHostAuthRecv(context);
        recvAuth.setConnection(protocol, recvAuth.mAddress, recvAuth.mPort, recvAuth.mFlags);
        mIncomingProtocol = protocol;
        mIncomingServiceInfo = null;
    }

    public String getClientCert(Context context) {
        final HostAuth recvAuth = mAccount.getOrCreateHostAuthRecv(context);
        return recvAuth.mClientCertAlias;
    }

    public String getAmProtocol() {
        return mAmProtocol;
    }

    public void setAmProtocol(String amProtocol) {
        mAmProtocol = amProtocol;
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
        dest.writeParcelable(mAccount, 0);
        dest.writeString(mEmail);
        dest.writeParcelable(mCredentialResults, 0);
        dest.writeBooleanArray(new boolean[] {mIncomingCredLoaded, mOutgoingCredLoaded});
        dest.writeParcelable(mPolicy, 0);
    }

    public SetupDataFragment(Parcel in) {
        final ClassLoader loader = getClass().getClassLoader();
        mFlowMode = in.readInt();
        setAccount((Account) in.readParcelable(loader));
        mEmail = in.readString();
        mCredentialResults = in.readParcelable(loader);
        final boolean[] credsLoaded = in.createBooleanArray();
        mIncomingCredLoaded = credsLoaded[0];
        mOutgoingCredLoaded = credsLoaded[1];
        mPolicy = in.readParcelable(loader);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("SetupData");
        sb.append(":acct=");
        sb.append(mAccount == null ? "none" :mAccount.mId);
        if (mEmail != null) {
            sb.append(":user=");
            sb.append(mEmail);
        }
        if (mCredentialResults != null) {
            sb.append(":cred=");
            sb.append(mCredentialResults.toString());
        }
        sb.append(":policy=");
        sb.append(mPolicy == null ? "none" : "exists");
        return sb.toString();
    }

}
