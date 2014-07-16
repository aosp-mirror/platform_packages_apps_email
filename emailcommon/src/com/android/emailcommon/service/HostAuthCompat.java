package com.android.emailcommon.service;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.emailcommon.provider.Credential;
import com.android.emailcommon.provider.HostAuth;
import com.google.common.base.Objects;

/*
 * This class is explicitly for communicating HostAuth information to different implementations of
 * IEmailService. We do not want to use the regular HostAuth class because it's used in many ways
 * and could need to change at some point, which could break Exchange.
 */
public class HostAuthCompat implements Parcelable {
    private String mProtocol;
    private String mAddress;
    private int mPort;
    private int mFlags;
    private String mLogin;
    private String mPassword;
    private String mDomain;
    private String mClientCertAlias;
    private byte[] mServerCert;
    private String mProviderId;
    private String mAccessToken;
    private String mRefreshToken;
    private long mExpiration;

    public HostAuthCompat(HostAuth hostAuth) {
        mProtocol = hostAuth.mProtocol;
        mAddress = hostAuth.mAddress;
        mPort = hostAuth.mPort;
        mFlags = hostAuth.mFlags;
        mLogin = hostAuth.mLogin;
        mPassword = hostAuth.mPassword;
        mDomain = hostAuth.mDomain;
        mClientCertAlias = hostAuth.mClientCertAlias;
        mServerCert = hostAuth.mServerCert;
        if (hostAuth.mCredential != null) {
            mProviderId = hostAuth.mCredential.mProviderId;
            mAccessToken = hostAuth.mCredential.mAccessToken;
            mRefreshToken = hostAuth.mCredential.mRefreshToken;
            mExpiration = hostAuth.mCredential.mExpiration;
        }
    }

    public HostAuth toHostAuth() {
        HostAuth hostAuth = new HostAuth();
        hostAuth.mProtocol = mProtocol;
        hostAuth.mAddress = mAddress;
        hostAuth.mPort = mPort;
        hostAuth.mFlags = mFlags;
        hostAuth.mLogin = mLogin;
        hostAuth.mPassword = mPassword;
        hostAuth.mDomain = mDomain;
        hostAuth.mClientCertAlias = mClientCertAlias;
        hostAuth.mServerCert = mServerCert;
        if (!TextUtils.isEmpty(mProviderId)) {
            hostAuth.mCredential = new Credential();
            hostAuth.mCredential.mProviderId = mProviderId;
            hostAuth.mCredential.mAccessToken = mAccessToken;
            hostAuth.mCredential.mRefreshToken = mRefreshToken;
            hostAuth.mCredential.mExpiration = mExpiration;
        }
        return hostAuth;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "[protocol " + mProtocol + "]";
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(mProtocol);
        parcel.writeString(mAddress);
        parcel.writeInt(mPort);
        parcel.writeInt(mFlags);
        parcel.writeString(mLogin);
        parcel.writeString(mPassword);
        parcel.writeString(mDomain);
        parcel.writeString(mClientCertAlias);
        parcel.writeByteArray(mServerCert);
        parcel.writeString(mProviderId);
        parcel.writeString(mAccessToken);
        parcel.writeString(mRefreshToken);
        parcel.writeLong(mExpiration);
    }

    /**
     * Supports Parcelable
     */
    public HostAuthCompat(Parcel in) {
        mProtocol = in.readString();
        mAddress = in.readString();
        mPort = in.readInt();
        mFlags = in.readInt();
        mLogin = in.readString();
        mPassword = in.readString();
        mDomain = in.readString();
        mClientCertAlias = in.readString();
        mServerCert = in.createByteArray();
        mProviderId = in.readString();
        mAccessToken = in.readString();
        mRefreshToken = in.readString();
        mExpiration = in.readLong();
    }

    /**
     * Supports Parcelable
     */
    public static final Parcelable.Creator<HostAuthCompat> CREATOR
            = new Parcelable.Creator<HostAuthCompat>() {
        @Override
        public HostAuthCompat createFromParcel(Parcel in) {
            return new HostAuthCompat(in);
        }

        @Override
        public HostAuthCompat[] newArray(int size) {
            return new HostAuthCompat[size];
        }
    };

}
