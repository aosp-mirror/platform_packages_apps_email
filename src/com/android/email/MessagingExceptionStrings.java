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

package com.android.email;

import com.android.emailcommon.mail.MessagingException;

import android.content.Context;

/**
 * @return the error message associated with this exception.
 */
public class MessagingExceptionStrings {
    public static String getErrorString(Context context, MessagingException e) {
        return context.getResources().getString(getErrorStringResourceId(e));
    }

    /**
     * @return the resource ID of the error message associated with this exception.
     */
    private static int getErrorStringResourceId(MessagingException e) {
        switch (e.getExceptionType()) {
            case MessagingException.IOERROR:
                return R.string.account_setup_failed_ioerror;
            case MessagingException.ATTACHMENT_NOT_FOUND:
                return R.string.attachment_not_found;
            case MessagingException.TLS_REQUIRED:
                return R.string.account_setup_failed_tls_required;
            case MessagingException.AUTH_REQUIRED:
                return R.string.account_setup_failed_auth_required;
            case MessagingException.GENERAL_SECURITY:
                return R.string.account_setup_failed_security;
                // TODO Generate a unique string for this case, which is the case
                // where the security policy needs to be updated.
            case MessagingException.SECURITY_POLICIES_REQUIRED:
                return R.string.account_setup_failed_security;
            case MessagingException.ACCESS_DENIED:
                return R.string.account_setup_failed_access_denied;
            case MessagingException.CLIENT_CERTIFICATE_ERROR:
                return R.string.account_setup_failed_certificate_inaccessible;
        }
        return R.string.status_network_error; // default
    }
}
