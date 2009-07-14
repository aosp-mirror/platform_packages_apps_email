/*
 * Copyright (C) 2008-2009 Marc Blank
 * Licensed to The Android Open Source Project.
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

import java.util.HashMap;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.exchange.IEmailService;

/**
 * Proxy for an IEmailService (remote email service); handles all connections to the service.
 * All calls via the proxy are synchronous; the UI must ensure that these calls are running
 * on appropriate background threads.
 *
 * A call to loadAttachment, for example, would look like this (assuming MyService is the service)
 *     EmailProxyService.getService(context, MyService.class).loadAttachment(..args..);
 */

public class EmailServiceProxy {

    // Map associating a context and a proxy
    static HashMap<Context, EmailServiceProxy> sProxyMap =
        new HashMap<Context, EmailServiceProxy>();

    // Map associating, for a given proxy, a class name (String) and a connected service
    public HashMap<String, IEmailService> serviceMap =
        new HashMap<String, IEmailService>();

    public EmailServiceProxy () {
    }

    class EmailServiceConnection implements ServiceConnection {
        EmailServiceProxy mProxy;

        EmailServiceConnection (EmailServiceProxy proxy) {
            mProxy = proxy;
        }

        void setProxy (EmailServiceProxy proxy) {
            mProxy = proxy;
        }

        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (mProxy) {
                IEmailService service = IEmailService.Stub.asInterface(binder);
                mProxy.serviceMap.put(name.getClassName(), service);
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            synchronized (mProxy) {
                mProxy.serviceMap.remove(name.getClassName());
            }
        }
    }

    public ServiceConnection mSyncManagerConnection = new EmailServiceConnection (this);

    static public IEmailService getService(Context context, Class<? extends Service> klass)
            throws RemoteException {
        String className = klass.getName();

        // First, lets get the proxy for this context
        // Make sure we're synchronized on the map
        EmailServiceProxy proxy;
        synchronized (sProxyMap) {
            proxy = sProxyMap.get(context);
            if (proxy == null) {
                proxy = new EmailServiceProxy();
                sProxyMap.put(context, proxy);
            }
        }

        // Once we have the proxy, we need to synchronize working with its map, connect to the
        // appropriate service (if not already connected) and return that service
        synchronized (proxy) {
            if (proxy.serviceMap.get(klass) == null) {
                context.bindService(new Intent(context, klass), proxy.mSyncManagerConnection,
                        Context.BIND_AUTO_CREATE);
            }
        }

        // Wait up to 5 seconds for the connection
        int count = 0;
        IEmailService service = null;
        while (count++ < 10) {
            synchronized (proxy) {
                service = proxy.serviceMap.get(className);
                if (service != null) {
                     break;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
            }
        }

        if (service == null) {
            throw new RemoteException();
        }
        return service;
     }
}
