/*
 * Copyright (C) 2011 The Android Open Source Project
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


package com.android.emailcommon.provider;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.emailcommon.provider.EmailContent.HostAuthColumns;
import com.android.emailcommon.utility.SSLUtils;
import com.android.emailcommon.utility.Utility;

import java.net.URI;
import java.net.URISyntaxException;

public final class HostAuth extends EmailContent implements HostAuthColumns, Parcelable {
    public static final String TABLE_NAME = "HostAuth";
    @SuppressWarnings("hiding")
    public static final Uri CONTENT_URI = Uri.parse(EmailContent.CONTENT_URI + "/hostauth");
    // TODO the three following constants duplicate constants in Store.java; remove those and
    //      just reference these.
    public static final String SCHEME_IMAP = "imap";
    public static final String SCHEME_POP3 = "pop3";
    public static final String SCHEME_EAS = "eas";
    public static final String SCHEME_SMTP = "smtp";
    public static final String SCHEME_TRUST_ALL_CERTS = "trustallcerts";

    public static final int PORT_UNKNOWN = -1;

    public static final int FLAG_NONE         = 0x00;    // No flags
    public static final int FLAG_SSL          = 0x01;    // Use SSL
    public static final int FLAG_TLS          = 0x02;    // Use TLS
    public static final int FLAG_AUTHENTICATE = 0x04;    // Use name/password for authentication
    public static final int FLAG_TRUST_ALL    = 0x08;    // Trust all certificates
    // Mask of settings directly configurable by the user
    public static final int USER_CONFIG_MASK  = 0x0b;

    public String mProtocol;
    public String mAddress;
    public int mPort;
    public int mFlags;
    public String mLogin;
    public String mPassword;
    public String mDomain;
    public String mClientCertAlias = null;

    public static final int CONTENT_ID_COLUMN = 0;
    public static final int CONTENT_PROTOCOL_COLUMN = 1;
    public static final int CONTENT_ADDRESS_COLUMN = 2;
    public static final int CONTENT_PORT_COLUMN = 3;
    public static final int CONTENT_FLAGS_COLUMN = 4;
    public static final int CONTENT_LOGIN_COLUMN = 5;
    public static final int CONTENT_PASSWORD_COLUMN = 6;
    public static final int CONTENT_DOMAIN_COLUMN = 7;
    public static final int CONTENT_CLIENT_CERT_ALIAS_COLUMN = 8;

    public static final String[] CONTENT_PROJECTION = new String[] {
        RECORD_ID, HostAuthColumns.PROTOCOL, HostAuthColumns.ADDRESS, HostAuthColumns.PORT,
        HostAuthColumns.FLAGS, HostAuthColumns.LOGIN,
        HostAuthColumns.PASSWORD, HostAuthColumns.DOMAIN, HostAuthColumns.CLIENT_CERT_ALIAS
    };

    /**
     * no public constructor since this is a utility class
     */
    public HostAuth() {
        mBaseUri = CONTENT_URI;

        // other defaults policy)
        mPort = PORT_UNKNOWN;
    }

     /**
     * Restore a HostAuth from the database, given its unique id
     * @param context
     * @param id
     * @return the instantiated HostAuth
     */
    public static HostAuth restoreHostAuthWithId(Context context, long id) {
        return EmailContent.restoreContentWithId(context, HostAuth.class,
                HostAuth.CONTENT_URI, HostAuth.CONTENT_PROJECTION, id);
    }


    /**
     * Returns the scheme for the specified flags.
     */
    public static String getSchemeString(String protocol, int flags) {
        return getSchemeString(protocol, flags, null);
    }

    /**
     * Builds a URI scheme name given the parameters for a {@code HostAuth}.
     * If a {@code clientAlias} is provided, this indicates that a secure connection must be used.
     */
    public static String getSchemeString(String protocol, int flags, String clientAlias) {
        String security = "";
        switch (flags & USER_CONFIG_MASK) {
            case FLAG_SSL:
                security = "+ssl+";
                break;
            case FLAG_SSL | FLAG_TRUST_ALL:
                security = "+ssl+trustallcerts";
                break;
            case FLAG_TLS:
                security = "+tls+";
                break;
            case FLAG_TLS | FLAG_TRUST_ALL:
                security = "+tls+trustallcerts";
                break;
        }

        if (!TextUtils.isEmpty(clientAlias)) {
            if (TextUtils.isEmpty(security)) {
                throw new IllegalArgumentException(
                        "Can't specify a certificate alias for a non-secure connection");
            }
            if (!security.endsWith("+")) {
                security += "+";
            }
            security += SSLUtils.escapeForSchemeName(clientAlias);
        }

        return protocol + security;
    }

    /**
     * Returns the flags for the specified scheme.
     */
    public static int getSchemeFlags(String scheme) {
        String[] schemeParts = scheme.split("\\+");
        int flags = HostAuth.FLAG_NONE;
        if (schemeParts.length >= 2) {
            String part1 = schemeParts[1];
            if ("ssl".equals(part1)) {
                flags |= HostAuth.FLAG_SSL;
            } else if ("tls".equals(part1)) {
                flags |= HostAuth.FLAG_TLS;
            }
            if (schemeParts.length >= 3) {
                String part2 = schemeParts[2];
                if (SCHEME_TRUST_ALL_CERTS.equals(part2)) {
                    flags |= HostAuth.FLAG_TRUST_ALL;
                }
            }
        }
        return flags;
    }

    @Override
    public void restore(Cursor cursor) {
        mBaseUri = CONTENT_URI;
        mId = cursor.getLong(CONTENT_ID_COLUMN);
        mProtocol = cursor.getString(CONTENT_PROTOCOL_COLUMN);
        mAddress = cursor.getString(CONTENT_ADDRESS_COLUMN);
        mPort = cursor.getInt(CONTENT_PORT_COLUMN);
        mFlags = cursor.getInt(CONTENT_FLAGS_COLUMN);
        mLogin = cursor.getString(CONTENT_LOGIN_COLUMN);
        mPassword = cursor.getString(CONTENT_PASSWORD_COLUMN);
        mDomain = cursor.getString(CONTENT_DOMAIN_COLUMN);
        mClientCertAlias = cursor.getString(CONTENT_CLIENT_CERT_ALIAS_COLUMN);
    }

    @Override
    public ContentValues toContentValues() {
        ContentValues values = new ContentValues();
        values.put(HostAuthColumns.PROTOCOL, mProtocol);
        values.put(HostAuthColumns.ADDRESS, mAddress);
        values.put(HostAuthColumns.PORT, mPort);
        values.put(HostAuthColumns.FLAGS, mFlags);
        values.put(HostAuthColumns.LOGIN, mLogin);
        values.put(HostAuthColumns.PASSWORD, mPassword);
        values.put(HostAuthColumns.DOMAIN, mDomain);
        values.put(HostAuthColumns.CLIENT_CERT_ALIAS, mClientCertAlias);
        values.put(HostAuthColumns.ACCOUNT_KEY, 0); // Need something to satisfy the DB
        return values;
    }

    /**
     * Sets the user name and password from URI user info string
     */
    public void setLogin(String userInfo) {
        String userName = null;
        String userPassword = null;
        if (!TextUtils.isEmpty(userInfo)) {
            String[] userInfoParts = userInfo.split(":", 2);
            userName = userInfoParts[0];
            if (userInfoParts.length > 1) {
                userPassword = userInfoParts[1];
            }
        }
        setLogin(userName, userPassword);
    }

    /**
     * Sets the user name and password
     */
    public void setLogin(String userName, String userPassword) {
        mLogin = userName;
        mPassword = userPassword;

        if (mLogin == null) {
            mFlags &= ~FLAG_AUTHENTICATE;
        } else {
            mFlags |= FLAG_AUTHENTICATE;
        }
    }

    /**
     * Returns the login information. [0] is the username and [1] is the password. If
     * {@link #FLAG_AUTHENTICATE} is not set, {@code null} is returned.
     */
    public String[] getLogin() {
        if ((mFlags & FLAG_AUTHENTICATE) != 0) {
            String trimUser = (mLogin != null) ? mLogin.trim() : "";
            String password = (mPassword != null) ? mPassword : "";
            return new String[] { trimUser, password };
        }
        return null;
    }

    public void setConnection(String protocol, String address, int port, int flags) {
        setConnection(protocol, address, port, flags, null);
    }

    /**
     * Sets the internal connection parameters based on the specified parameter values.
     * @param protocol the mail protocol to use (e.g. "eas", "imap").
     * @param address the address of the server
     * @param port the port for the connection
     * @param flags flags indicating the security and type of the connection
     * @param clientCertAlias an optional alias to use if a client user certificate is to be
     *     presented during connection establishment. If this is non-empty, it must be the case
     *     that flags indicates use of a secure connection
     */
    public void setConnection(String protocol, String address,
            int port, int flags, String clientCertAlias) {
        // Set protocol, security, and additional flags based on uri scheme
        mProtocol = protocol;

        mFlags &= ~(FLAG_SSL | FLAG_TLS | FLAG_TRUST_ALL);
        mFlags |= (flags & USER_CONFIG_MASK);

        boolean useSecureConnection = (flags & (FLAG_SSL | FLAG_TLS)) != 0;
        if (!useSecureConnection && !TextUtils.isEmpty(clientCertAlias)) {
            throw new IllegalArgumentException("Can't use client alias on non-secure connections");
        }

        mAddress = address;
        mPort = port;
        if (mPort == PORT_UNKNOWN) {
            boolean useSSL = ((mFlags & FLAG_SSL) != 0);
            // infer port# from protocol + security
            // SSL implies a different port - TLS runs in the "regular" port
            // NOTE: Although the port should be setup in the various setup screens, this
            // block cannot easily be moved because we get process URIs from other sources
            // (e.g. for tests, provider templates and account restore) that may or may not
            // have a port specified.
            if (SCHEME_POP3.equals(mProtocol)) {
                mPort = useSSL ? 995 : 110;
            } else if (SCHEME_IMAP.equals(mProtocol)) {
                mPort = useSSL ? 993 : 143;
            } else if (SCHEME_EAS.equals(mProtocol)) {
                mPort = useSSL ? 443 : 80;
            } else if (SCHEME_SMTP.equals(mProtocol)) {
                mPort = useSSL ? 465 : 587;
            }
        }

        mClientCertAlias = clientCertAlias;
    }

    /** Returns {@code true} if this is an EAS connection; otherwise, {@code false}. */
    public boolean isEasConnection() {
        return SCHEME_EAS.equals(mProtocol);
    }

    /** Convenience method to determine if SSL is used. */
    public boolean shouldUseSsl() {
        return (mFlags & FLAG_SSL) != 0;
    }

    /** Convenience method to determine if all server certs should be used. */
    public boolean shouldTrustAllServerCerts() {
        return (mFlags & FLAG_TRUST_ALL) != 0;
    }

    /**
     * Supports Parcelable
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Supports Parcelable
     */
    public static final Parcelable.Creator<HostAuth> CREATOR
            = new Parcelable.Creator<HostAuth>() {
        @Override
        public HostAuth createFromParcel(Parcel in) {
            return new HostAuth(in);
        }

        @Override
        public HostAuth[] newArray(int size) {
            return new HostAuth[size];
        }
    };

    /**
     * Supports Parcelable
     */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // mBaseUri is not parceled
        dest.writeLong(mId);
        dest.writeString(mProtocol);
        dest.writeString(mAddress);
        dest.writeInt(mPort);
        dest.writeInt(mFlags);
        dest.writeString(mLogin);
        dest.writeString(mPassword);
        dest.writeString(mDomain);
        dest.writeString(mClientCertAlias);
    }

    /**
     * Supports Parcelable
     */
    public HostAuth(Parcel in) {
        mBaseUri = CONTENT_URI;
        mId = in.readLong();
        mProtocol = in.readString();
        mAddress = in.readString();
        mPort = in.readInt();
        mFlags = in.readInt();
        mLogin = in.readString();
        mPassword = in.readString();
        mDomain = in.readString();
        mClientCertAlias = in.readString();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof HostAuth)) {
            return false;
        }
        HostAuth that = (HostAuth)o;
        return mPort == that.mPort
                && mFlags == that.mFlags
                && Utility.areStringsEqual(mProtocol, that.mProtocol)
                && Utility.areStringsEqual(mAddress, that.mAddress)
                && Utility.areStringsEqual(mLogin, that.mLogin)
                && Utility.areStringsEqual(mPassword, that.mPassword)
                && Utility.areStringsEqual(mDomain, that.mDomain)
                && Utility.areStringsEqual(mClientCertAlias, that.mClientCertAlias);
    }

    /**
     * The flag, password, and client cert alias are the only items likely to change after a
     * HostAuth is created
     */
    @Override
    public int hashCode() {
        int hashCode = 29;
        if (mPassword != null) {
            hashCode += mPassword.hashCode();
        }
        if (mClientCertAlias != null) {
            hashCode += (mClientCertAlias.hashCode() << 8);
        }
        return (hashCode << 8) + mFlags;
    }

    /**
     * Legacy URI parser. Used in parsing template from provider.xml
     * Example string:
     *   "eas+ssl+trustallcerts://user:password@server/domain:123"
     *
     * Note that the use of client certificate is specified in the URI, a secure connection type
     * must be used.
     */
    public static void setHostAuthFromString(HostAuth auth, String uriString)
            throws URISyntaxException {
        URI uri = new URI(uriString);
        String path = uri.getPath();
        String domain = null;
        if (!TextUtils.isEmpty(path)) {
            // Strip off the leading slash that begins the path.
            domain = path.substring(1);
        }
        auth.mDomain = domain;
        auth.setLogin(uri.getUserInfo());

        String scheme = uri.getScheme();
        auth.setConnection(scheme, uri.getHost(), uri.getPort());
    }

    /**
     * Legacy code for setting connection values from a "scheme" (see above)
     */
    public void setConnection(String scheme, String host, int port) {
        String[] schemeParts = scheme.split("\\+");
        String protocol = schemeParts[0];
        String clientCertAlias = null;
        int flags = getSchemeFlags(scheme);

        // Example scheme: "eas+ssl+trustallcerts" or "eas+tls+trustallcerts+client-cert-alias"
        if (schemeParts.length > 3) {
            clientCertAlias = schemeParts[3];
        } else if (schemeParts.length > 2) {
            if (!SCHEME_TRUST_ALL_CERTS.equals(schemeParts[2])) {
                mClientCertAlias = schemeParts[2];
            }
        }

        setConnection(protocol, host, port, flags, clientCertAlias);
    }

}
