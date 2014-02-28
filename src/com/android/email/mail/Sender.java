/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.email.mail;

import android.content.Context;
import android.content.res.XmlResourceParser;

import com.android.email.R;
import com.android.emailcommon.Logging;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.HostAuth;
import com.android.mail.utils.LogUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

public abstract class Sender {
    protected static final int SOCKET_CONNECT_TIMEOUT = 10000;

    /**
     * Static named constructor.  It should be overrode by extending class.
     * Because this method will be called through reflection, it can not be protected.
     */
    public static Sender newInstance(Account account) throws MessagingException {
        throw new MessagingException("Sender.newInstance: Unknown scheme in "
                + account.mDisplayName);
    }

    private static Sender instantiateSender(Context context, String className, Account account)
        throws MessagingException {
        Object o = null;
        try {
            Class<?> c = Class.forName(className);
            // and invoke "newInstance" class method and instantiate sender object.
            java.lang.reflect.Method m =
                c.getMethod("newInstance", Account.class, Context.class);
            o = m.invoke(null, account, context);
        } catch (Exception e) {
            LogUtils.d(Logging.LOG_TAG, String.format(
                    "exception %s invoking method %s#newInstance(Account, Context) for %s",
                    e.toString(), className, account.mDisplayName));
            throw new MessagingException("can not instantiate Sender for " + account.mDisplayName);
        }
        if (!(o instanceof Sender)) {
            throw new MessagingException(
                    account.mDisplayName + ": " + className + " create incompatible object");
        }
        return (Sender) o;
    }

    /**
     * Find Sender implementation consulting with sender.xml file.
     */
    private static Sender findSender(Context context, int resourceId, Account account)
            throws MessagingException {
        Sender sender = null;
        try {
            XmlResourceParser xml = context.getResources().getXml(resourceId);
            int xmlEventType;
            HostAuth sendAuth = account.getOrCreateHostAuthSend(context);
            // walk through senders.xml file.
            while ((xmlEventType = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                if (xmlEventType == XmlResourceParser.START_TAG &&
                    "sender".equals(xml.getName())) {
                    String xmlScheme = xml.getAttributeValue(null, "scheme");
                    if (sendAuth.mProtocol != null && sendAuth.mProtocol.startsWith(xmlScheme)) {
                        // found sender entry whose scheme is matched with uri.
                        // then load sender class.
                        String className = xml.getAttributeValue(null, "class");
                        sender = instantiateSender(context, className, account);
                    }
                }
            }
        } catch (XmlPullParserException e) {
            // ignore
        } catch (IOException e) {
            // ignore
        }
        return sender;
    }

    /**
     * Get an instance of a mail sender for the given account. The account must be valid (i.e. has
     * at least an outgoing server name).
     *
     * @param context the caller's context
     * @param account the account of the sender.
     * @return an initialized sender of the appropriate class
     * @throws MessagingException If the sender cannot be obtained or if the account is invalid.
     */
    public synchronized static Sender getInstance(Context context, Account account)
            throws MessagingException {
        Context appContext = context.getApplicationContext();
        Sender sender = findSender(appContext, R.xml.senders_product, account);
        if (sender == null) {
            sender = findSender(appContext, R.xml.senders, account);
        }
        if (sender == null) {
            throw new MessagingException("Cannot find sender for account " + account.mDisplayName);
        }
        return sender;
    }

    public abstract void open() throws MessagingException;

    public abstract void sendMessage(long messageId) throws MessagingException;

    public abstract void close() throws MessagingException;
}
