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


package com.android.emailcommon.utility;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.security.KeyChain;
import android.security.KeyChainAliasCallback;

/**
 * A headless Activity which simply calls into the framework {@link KeyChain} service to select
 * a certificate to use for establishing secure connections in the Email app.
 */
public class CertificateRequestor extends Activity implements KeyChainAliasCallback {
    public static final String EXTRA_HOST = "CertificateRequestor.host";
    public static final String EXTRA_PORT = "CertificateRequestor.port";

    public static final String RESULT_ALIAS = "CertificateRequestor.alias";

    public static final Uri CERTIFICATE_REQUEST_URI =
            Uri.parse("eas://com.android.emailcommon/certrequest");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent i = getIntent();
        String host = i.getStringExtra(EXTRA_HOST);
        int port = i.getIntExtra(EXTRA_PORT, -1);

        if (savedInstanceState == null) {
            KeyChain.choosePrivateKeyAlias(
                    this, this,
                    null /* keytypes */, null /* issuers */,
                    host, port,
                    null /* alias */);
        }
    }

    /**
     * Callback for the certificate request. Does not happen on the UI thread.
     */
    @Override
    public void alias(String alias) {
        if (alias == null) {
            setResult(RESULT_CANCELED);
        } else {
            Intent data = new Intent();
            data.putExtra(RESULT_ALIAS, alias);
            setResult(RESULT_OK, data);
        }
        finish();
    }
}
