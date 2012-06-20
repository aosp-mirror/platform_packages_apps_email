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

package com.android.email.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.RemoteException;

import com.android.email.R;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.IEmailServiceCallback;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility functions for EmailService support.
 */
public class EmailServiceUtils {
    private static final ArrayList<EmailServiceInfo> sServiceList =
            new ArrayList<EmailServiceInfo>();

    /**
     * Starts an EmailService by protocol
     */
    public static void startService(Context context, String protocol) {
        EmailServiceInfo info = getServiceInfo(context, protocol);
        if (info != null && info.intentAction != null) {
            context.startService(new Intent(info.intentAction));
        }
    }

    /**
     * Starts all remote services
     */
    public static void startRemoteServices(Context context) {
        for (EmailServiceInfo info: getServiceInfoList(context)) {
            if (info.intentAction != null) {
                context.startService(new Intent(info.intentAction));
            }
        }
    }

    /**
     * Returns whether or not remote services are present on device
     */
    public static boolean areRemoteServicesInstalled(Context context) {
        for (EmailServiceInfo info: getServiceInfoList(context)) {
            if (info.intentAction != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Starts all remote services
     */
    public static void setRemoteServicesLogging(Context context, int debugBits) {
        for (EmailServiceInfo info: getServiceInfoList(context)) {
            if (info.intentAction != null) {
                EmailServiceProxy service =
                        EmailServiceUtils.getService(context, null, info.protocol);
                if (service != null) {
                    try {
                        service.setLogging(debugBits);
                    } catch (RemoteException e) {
                        // Move along, nothing to see
                    }
                }
            }
        }
    }

    /**
     * Determine if the EmailService is available
     */
    public static boolean isServiceAvailable(Context context, String protocol) {
        EmailServiceInfo info = getServiceInfo(context, protocol);
        if (info == null) return false;
        if (info.klass != null) return true;
        return new EmailServiceProxy(context, info.intentAction, null).test();
    }

    /**
     * For a given account id, return a service proxy if applicable, or null.
     *
     * @param accountId the message of interest
     * @result service proxy, or null if n/a
     */
    public static EmailServiceProxy getServiceForAccount(Context context,
            IEmailServiceCallback callback, long accountId) {
        return getService(context, callback, Account.getProtocol(context, accountId));
    }

    /**
     * Holder of service information (currently just name and class/intent); if there is a class
     * member, this is a (local, i.e. same process) service; otherwise, this is a remote service
     */
    public static class EmailServiceInfo {
        public String protocol;
        public String name;
        public String accountType;
        Class<? extends Service> klass;
        String intentAction;
        public int port;
        public int portSsl;
        public boolean defaultSsl = false;
        public boolean offerTls = false;
        public boolean offerCerts = false;
        public boolean usesSmtp = false;
        public boolean offerLocalDeletes = false;
        public boolean offerPrefix = false;
        public boolean usesAutodiscover = false;
        public boolean offerPush = false;
        public boolean offerLookback = false;
        public boolean syncContacts = false;
        public boolean syncCalendar = false;
        public boolean offerAttachmentPreload = false;
        public int serverLabel;
        public CharSequence[] syncIntervalStrings;
        public CharSequence[] syncIntervals;
    }

    public static EmailServiceProxy getService(Context context, IEmailServiceCallback callback,
            String protocol) {
        EmailServiceInfo info = getServiceInfo(context, protocol);
        if (info.klass != null) {
            return new EmailServiceProxy(context, info.klass, callback);
        } else {
            return new EmailServiceProxy(context, info.intentAction, callback);
        }
    }

    public static EmailServiceInfo getServiceInfo(Context context, String protocol) {
        if (sServiceList.isEmpty()) {
            findServices(context);
        }
        for (EmailServiceInfo info: sServiceList) {
            if (info.protocol.equals(protocol)) {
                return info;
            }
        }
        return null;
    }

    public static List<EmailServiceInfo> getServiceInfoList(Context context) {
        if (sServiceList.isEmpty()) {
            findServices(context);
        }
        return sServiceList;
    }

    /**
     * Parse services.xml file to find our available email services
     */
    @SuppressWarnings("unchecked")
    private static void findServices(Context context) {
        try {
            Resources res = context.getResources();
            XmlResourceParser xml = res.getXml(R.xml.services);
            int xmlEventType;
            // walk through senders.xml file.
            while ((xmlEventType = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                if (xmlEventType == XmlResourceParser.START_TAG &&
                        "emailservice".equals(xml.getName())) {
                    EmailServiceInfo info = new EmailServiceInfo();
                    TypedArray ta = res.obtainAttributes(xml, R.styleable.EmailServiceInfo);
                    info.protocol = ta.getString(R.styleable.EmailServiceInfo_protocol);
                    info.name = ta.getString(R.styleable.EmailServiceInfo_name);
                    String klass = ta.getString(R.styleable.EmailServiceInfo_serviceClass);
                    info.intentAction = ta.getString(R.styleable.EmailServiceInfo_intent);
                    info.accountType = ta.getString(R.styleable.EmailServiceInfo_accountType);
                    info.defaultSsl = ta.getBoolean(R.styleable.EmailServiceInfo_defaultSsl, false);
                    info.port = ta.getInteger(R.styleable.EmailServiceInfo_port, 0);
                    info.portSsl = ta.getInteger(R.styleable.EmailServiceInfo_portSsl, 0);
                    info.offerTls = ta.getBoolean(R.styleable.EmailServiceInfo_offerTls, false);
                    info.offerCerts = ta.getBoolean(R.styleable.EmailServiceInfo_offerCerts, false);
                    info.offerLocalDeletes =
                            ta.getBoolean(R.styleable.EmailServiceInfo_offerLocalDeletes, false);
                    info.offerPrefix =
                            ta.getBoolean(R.styleable.EmailServiceInfo_offerPrefix, false);
                    info.usesSmtp = ta.getBoolean(R.styleable.EmailServiceInfo_usesSmtp, false);
                    info.usesAutodiscover =
                        ta.getBoolean(R.styleable.EmailServiceInfo_usesAutodiscover, false);
                    info.offerPush = ta.getBoolean(R.styleable.EmailServiceInfo_offerPush, false);
                    info.offerLookback =
                            ta.getBoolean(R.styleable.EmailServiceInfo_offerLookback, false);
                    info.syncContacts =
                            ta.getBoolean(R.styleable.EmailServiceInfo_syncContacts, false);
                    info.syncCalendar =
                            ta.getBoolean(R.styleable.EmailServiceInfo_syncCalendar, false);
                    info.offerAttachmentPreload =
                        ta.getBoolean(R.styleable.EmailServiceInfo_offerAttachmentPreload, false);
                    info.syncIntervalStrings =
                        ta.getTextArray(R.styleable.EmailServiceInfo_syncIntervalStrings);
                    info.syncIntervals =
                        ta.getTextArray(R.styleable.EmailServiceInfo_syncIntervals);

                    // Must have either "class" (local) or "intent" (remote)
                    if (klass != null) {
                        try {
                            info.klass = (Class<? extends Service>) Class.forName(klass);
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException(
                                    "Class not found in service descriptor: " + klass);
                        }
                    }
                    if (info.klass == null && info.intentAction == null) {
                        throw new IllegalStateException(
                                "No class or intent action specified in service descriptor");
                    }
                    if (info.klass != null && info.intentAction != null) {
                        throw new IllegalStateException(
                                "Both class and intent action specified in service descriptor");
                    }
                    sServiceList.add(info);
                }
            }
        } catch (XmlPullParserException e) {
            // ignore
        } catch (IOException e) {
            // ignore
        }
    }

}
