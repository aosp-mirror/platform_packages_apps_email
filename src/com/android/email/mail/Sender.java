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

import com.android.email.Email;
import com.android.email.R;

import org.xmlpull.v1.XmlPullParserException;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;

public abstract class Sender {
    protected static final int SOCKET_CONNECT_TIMEOUT = 10000;

    private static final HashMap<String, Sender> sSenders = new HashMap<String, Sender>();

    /**
     * Static named constructor.  It should be overrode by extending class.
     * Because this method will be called through reflection, it can not be protected.
     */
    public static Sender newInstance(Context context, String uri)
            throws MessagingException {
        throw new MessagingException("Sender.newInstance: Unknown scheme in " + uri);
    }

    private static Sender instantiateSender(Context context, String className, String uri)
        throws MessagingException {
        Object o = null;
        try {
            Class<?> c = Class.forName(className);
            // and invoke "newInstance" class method and instantiate sender object.
            java.lang.reflect.Method m =
                c.getMethod("newInstance", Context.class, String.class);
            o = m.invoke(null, context, uri);
        } catch (Exception e) {
            Log.d(Email.LOG_TAG, String.format(
                    "exception %s invoking %s.newInstance.(Context, String) method for %s",
                    e.toString(), className, uri));
            throw new MessagingException("can not instantiate Sender object for " + uri);
        }
        if (!(o instanceof Sender)) {
            throw new MessagingException(
                    uri + ": " + className + " create incompatible object");
        }
        return (Sender) o;
    }
    
    /**
     * Find Sender implementation consulting with sender.xml file.
     */
    private static Sender findSender(Context context, int resourceId, String uri)
            throws MessagingException {
        Sender sender = null;
        try {
            XmlResourceParser xml = context.getResources().getXml(resourceId);
            int xmlEventType;
            // walk through senders.xml file.
            while ((xmlEventType = xml.next()) != XmlResourceParser.END_DOCUMENT) {
                if (xmlEventType == XmlResourceParser.START_TAG &&
                    "sender".equals(xml.getName())) {
                    String scheme = xml.getAttributeValue(null, "scheme");
                    if (uri.startsWith(scheme)) {
                        // found sender entry whose scheme is matched with uri.
                        // then load sender class.
                        String className = xml.getAttributeValue(null, "class");
                        sender = instantiateSender(context, className, uri);
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

    public synchronized static Sender getInstance(Context context, String uri)
            throws MessagingException {
       Sender sender = sSenders.get(uri);
       if (sender == null) {
           sender = findSender(context, R.xml.senders_product, uri);
           if (sender == null) {
               sender = findSender(context, R.xml.senders, uri);
           }

           if (sender != null) {
               sSenders.put(uri, sender);
           }
       }

       if (sender == null) {
            throw new MessagingException("Unable to locate an applicable Transport for " + uri);
       }

       return sender;
    }
    
    /**
     * Get class of SettingActivity for this Sender class.
     * @return Activity class that has class method actionEditOutgoingSettings(). 
     */
    public Class<? extends android.app.Activity> getSettingActivityClass() {
        // default SettingActivity class
        return com.android.email.activity.setup.AccountSetupOutgoing.class;
    }

    public abstract void open() throws MessagingException;
    
    public String validateSenderLimit(long messageId) {
        return null;
    }

    /**
     * Check message has any limitation of Sender or not.
     * 
     * @param messageId the message that will be checked.
     * @throws LimitViolationException
     */
    public void checkSenderLimitation(long messageId) throws LimitViolationException {
    }
    
    public static class LimitViolationException extends MessagingException {
        public final int mMsgResourceId;
        public final long mActual;
        public final long mLimit;
        
        private LimitViolationException(int msgResourceId, long actual, long limit) {
            super(UNSPECIFIED_EXCEPTION);
            mMsgResourceId = msgResourceId;
            mActual = actual;
            mLimit = limit;
        }
        
        public static void check(int msgResourceId, long actual, long limit)
            throws LimitViolationException {
            if (actual > limit) {
                throw new LimitViolationException(msgResourceId, actual, limit);
            }
        }
    }
    
    public abstract void sendMessage(long messageId) throws MessagingException;

    public abstract void close() throws MessagingException;
}
