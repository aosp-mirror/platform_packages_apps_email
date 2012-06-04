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
import android.content.res.XmlResourceParser;

import com.android.email.R;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.service.EmailServiceProxy;
import com.android.emailcommon.service.IEmailServiceCallback;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.HashMap;

/**
 * Utility functions for EmailService support.
 */
public class EmailServiceUtils {
    private static final HashMap<String, EmailServiceInfo> sServiceMap =
            new HashMap<String, EmailServiceInfo>();

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
    static class EmailServiceInfo {
        String name;
        Class<? extends Service> klass;
        String intentAction;
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

    private static EmailServiceInfo getServiceInfo(Context context, String protocol) {
        if (sServiceMap.isEmpty()) {
            findServices(context);
        }
        return sServiceMap.get(protocol);
    }

    /**
     * Parse services.xml file to find our available email services
     */
    @SuppressWarnings("unchecked")
    private static void findServices(Context context) {
        try {
            XmlResourceParser xml = context.getResources().getXml(R.xml.services);
            int xmlEventType;
            // walk through senders.xml file.
            while ((xmlEventType = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                if (xmlEventType == XmlResourceParser.START_TAG &&
                        "service".equals(xml.getName())) {
                    EmailServiceInfo info = new EmailServiceInfo();
                    String protocol = xml.getAttributeValue(null, "protocol");
                    if (protocol == null) {
                        throw new IllegalStateException(
                                "No protocol specified in service descriptor");
                    }
                    info.name = xml.getAttributeValue(null, "name");
                    if (info.name == null) {
                        throw new IllegalStateException(
                                "No name specified in service descriptor");
                    }
                    String klass = xml.getAttributeValue(null, "class");
                    if (klass != null) {
                        try {
                            info.klass = (Class<? extends Service>) Class.forName(klass);
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException(
                                    "Class not found in service descriptor: " + klass);
                        }
                    }
                    info.intentAction = xml.getAttributeValue(null, "intent");

                    if (info.klass == null && info.intentAction == null) {
                        throw new IllegalStateException(
                                "No class or intent action specified in service descriptor");
                    }
                    if (info.klass != null && info.intentAction != null) {
                        throw new IllegalStateException(
                                "Both class and intent action specified in service descriptor");
                    }
                    sServiceMap.put(protocol, info);
                }
            }
        } catch (XmlPullParserException e) {
            // ignore
        } catch (IOException e) {
            // ignore
        }
    }

}
