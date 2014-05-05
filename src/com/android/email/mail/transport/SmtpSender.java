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

package com.android.email.mail.transport;

import android.content.Context;
import android.util.Base64;

import com.android.email.mail.Sender;
import com.android.email.mail.internet.AuthenticationCache;
import com.android.email2.ui.MailActivityEmail;
import com.android.emailcommon.Logging;
import com.android.emailcommon.internet.Rfc822Output;
import com.android.emailcommon.mail.Address;
import com.android.emailcommon.mail.AuthenticationFailedException;
import com.android.emailcommon.mail.CertificateValidationException;
import com.android.emailcommon.mail.MessagingException;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.Credential;
import com.android.emailcommon.provider.EmailContent.Message;
import com.android.emailcommon.provider.HostAuth;
import com.android.emailcommon.utility.EOLConvertingOutputStream;
import com.android.mail.utils.LogUtils;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;

import javax.net.ssl.SSLException;

/**
 * This class handles all of the protocol-level aspects of sending messages via SMTP.
 */
public class SmtpSender extends Sender {

    private final Context mContext;
    private MailTransport mTransport;
    private Account mAccount;
    private String mUsername;
    private String mPassword;
    private boolean mUseOAuth;

    /**
     * Static named constructor.
     */
    public static Sender newInstance(Account account, Context context) throws MessagingException {
        return new SmtpSender(context, account);
    }

    /**
     * Creates a new sender for the given account.
     */
    public SmtpSender(Context context, Account account) {
        mContext = context;
        mAccount = account;
        HostAuth sendAuth = account.getOrCreateHostAuthSend(context);
        mTransport = new MailTransport(context, "SMTP", sendAuth);
        String[] userInfoParts = sendAuth.getLogin();
        mUsername = userInfoParts[0];
        mPassword = userInfoParts[1];
        Credential cred = sendAuth.getCredential(context);
        if (cred != null) {
            mUseOAuth = true;
        }
    }

    /**
     * For testing only.  Injects a different transport.  The transport should already be set
     * up and ready to use.  Do not use for real code.
     * @param testTransport The Transport to inject and use for all future communication.
     */
    public void setTransport(MailTransport testTransport) {
        mTransport = testTransport;
    }

    @Override
    public void open() throws MessagingException {
        try {
            mTransport.open();

            // Eat the banner
            executeSimpleCommand(null);

            String localHost = "localhost";
            // Try to get local address in the proper format.
            InetAddress localAddress = mTransport.getLocalAddress();
            if (localAddress != null) {
                // Address Literal formatted in accordance to RFC2821 Sec. 4.1.3
                StringBuilder sb = new StringBuilder();
                sb.append('[');
                if (localAddress instanceof Inet6Address) {
                    sb.append("IPv6:");
                }
                sb.append(localAddress.getHostAddress());
                sb.append(']');
                localHost = sb.toString();
            }
            String result = executeSimpleCommand("EHLO " + localHost);

            /*
             * TODO may need to add code to fall back to HELO I switched it from
             * using HELO on non STARTTLS connections because of AOL's mail
             * server. It won't let you use AUTH without EHLO.
             * We should really be paying more attention to the capabilities
             * and only attempting auth if it's available, and warning the user
             * if not.
             */
            if (mTransport.canTryTlsSecurity()) {
                if (result.contains("STARTTLS")) {
                    executeSimpleCommand("STARTTLS");
                    mTransport.reopenTls();
                    /*
                     * Now resend the EHLO. Required by RFC2487 Sec. 5.2, and more specifically,
                     * Exim.
                     */
                    result = executeSimpleCommand("EHLO " + localHost);
                } else {
                    if (MailActivityEmail.DEBUG) {
                        LogUtils.d(Logging.LOG_TAG, "TLS not supported but required");
                    }
                    throw new MessagingException(MessagingException.TLS_REQUIRED);
                }
            }

            /*
             * result contains the results of the EHLO in concatenated form
             */
            boolean authLoginSupported = result.matches(".*AUTH.*LOGIN.*$");
            boolean authPlainSupported = result.matches(".*AUTH.*PLAIN.*$");
            boolean authOAuthSupported = result.matches(".*AUTH.*XOAUTH2.*$");

            if (mUseOAuth) {
                if (!authOAuthSupported) {
                    LogUtils.w(Logging.LOG_TAG, "OAuth requested, but not supported.");
                    throw new MessagingException(MessagingException.OAUTH_NOT_SUPPORTED);
                }
                saslAuthOAuth(mUsername);
            } else if (mUsername != null && mUsername.length() > 0 && mPassword != null
                    && mPassword.length() > 0) {
                if (authPlainSupported) {
                    saslAuthPlain(mUsername, mPassword);
                }
                else if (authLoginSupported) {
                    saslAuthLogin(mUsername, mPassword);
                }
                else {
                    LogUtils.w(Logging.LOG_TAG, "No valid authentication mechanism found.");
                    throw new MessagingException(MessagingException.AUTH_REQUIRED);
                }
            } else {
                // It is acceptable to hvae no authentication at all for SMTP.
            }
        } catch (SSLException e) {
            if (MailActivityEmail.DEBUG) {
                LogUtils.d(Logging.LOG_TAG, e.toString());
            }
            throw new CertificateValidationException(e.getMessage(), e);
        } catch (IOException ioe) {
            if (MailActivityEmail.DEBUG) {
                LogUtils.d(Logging.LOG_TAG, ioe.toString());
            }
            throw new MessagingException(MessagingException.IOERROR, ioe.toString());
        }
    }

    @Override
    public void sendMessage(long messageId) throws MessagingException {
        close();
        open();

        Message message = Message.restoreMessageWithId(mContext, messageId);
        if (message == null) {
            throw new MessagingException("Trying to send non-existent message id="
                    + Long.toString(messageId));
        }
        Address from = Address.firstAddress(message.mFrom);
        Address[] to = Address.fromHeader(message.mTo);
        Address[] cc = Address.fromHeader(message.mCc);
        Address[] bcc = Address.fromHeader(message.mBcc);

        try {
            executeSimpleCommand("MAIL FROM:" + "<" + from.getAddress() + ">");
            for (Address address : to) {
                executeSimpleCommand("RCPT TO:" + "<" + address.getAddress().trim() + ">");
            }
            for (Address address : cc) {
                executeSimpleCommand("RCPT TO:" + "<" + address.getAddress().trim() + ">");
            }
            for (Address address : bcc) {
                executeSimpleCommand("RCPT TO:" + "<" + address.getAddress().trim() + ">");
            }
            executeSimpleCommand("DATA");
            // TODO byte stuffing
            Rfc822Output.writeTo(mContext, message,
                    new EOLConvertingOutputStream(mTransport.getOutputStream()),
                    false /* do not use smart reply */,
                    false /* do not send BCC */,
                    null  /* attachments are in the message itself */);
            executeSimpleCommand("\r\n.");
        } catch (IOException ioe) {
            throw new MessagingException("Unable to send message", ioe);
        }
    }

    /**
     * Close the protocol (and the transport below it).
     *
     * MUST NOT return any exceptions.
     */
    @Override
    public void close() {
        mTransport.close();
    }

    /**
     * Send a single command and wait for a single response.  Handles responses that continue
     * onto multiple lines.  Throws MessagingException if response code is 4xx or 5xx.  All traffic
     * is logged (if debug logging is enabled) so do not use this function for user ID or password.
     *
     * @param command The command string to send to the server.
     * @return Returns the response string from the server.
     */
    private String executeSimpleCommand(String command) throws IOException, MessagingException {
        return executeSensitiveCommand(command, null);
    }

    /**
     * Send a single command and wait for a single response.  Handles responses that continue
     * onto multiple lines.  Throws MessagingException if response code is 4xx or 5xx.
     *
     * @param command The command string to send to the server.
     * @param sensitiveReplacement If the command includes sensitive data (e.g. authentication)
     * please pass a replacement string here (for logging).
     * @return Returns the response string from the server.
     */
    private String executeSensitiveCommand(String command, String sensitiveReplacement)
            throws IOException, MessagingException {
        if (command != null) {
            mTransport.writeLine(command, sensitiveReplacement);
        }

        String line = mTransport.readLine(true);

        String result = line;

        while (line.length() >= 4 && line.charAt(3) == '-') {
            line = mTransport.readLine(true);
            result += line.substring(3);
        }

        if (result.length() > 0) {
            char c = result.charAt(0);
            if ((c == '4') || (c == '5')) {
                throw new MessagingException(result);
            }
        }

        return result;
    }


//    C: AUTH LOGIN
//    S: 334 VXNlcm5hbWU6
//    C: d2VsZG9u
//    S: 334 UGFzc3dvcmQ6
//    C: dzNsZDBu
//    S: 235 2.0.0 OK Authenticated
//
//    Lines 2-5 of the conversation contain base64-encoded information. The same conversation, with base64 strings decoded, reads:
//
//
//    C: AUTH LOGIN
//    S: 334 Username:
//    C: weldon
//    S: 334 Password:
//    C: w3ld0n
//    S: 235 2.0.0 OK Authenticated

    private void saslAuthLogin(String username, String password) throws MessagingException,
        AuthenticationFailedException, IOException {
        try {
            executeSimpleCommand("AUTH LOGIN");
            executeSensitiveCommand(
                    Base64.encodeToString(username.getBytes(), Base64.NO_WRAP),
                    "/username redacted/");
            executeSensitiveCommand(
                    Base64.encodeToString(password.getBytes(), Base64.NO_WRAP),
                    "/password redacted/");
        }
        catch (MessagingException me) {
            if (me.getMessage().length() > 1 && me.getMessage().charAt(1) == '3') {
                throw new AuthenticationFailedException(me.getMessage());
            }
            throw me;
        }
    }

    private void saslAuthPlain(String username, String password) throws MessagingException,
            AuthenticationFailedException, IOException {
        byte[] data = ("\000" + username + "\000" + password).getBytes();
        data = Base64.encode(data, Base64.NO_WRAP);
        try {
            executeSensitiveCommand("AUTH PLAIN " + new String(data), "AUTH PLAIN /redacted/");
        }
        catch (MessagingException me) {
            if (me.getMessage().length() > 1 && me.getMessage().charAt(1) == '3') {
                throw new AuthenticationFailedException(me.getMessage());
            }
            throw me;
        }
    }

    private void saslAuthOAuth(String username) throws MessagingException,
            AuthenticationFailedException, IOException {
        final AuthenticationCache cache = AuthenticationCache.getInstance();
        String accessToken = cache.retrieveAccessToken(mContext, mAccount);
        try {
            saslAuthOAuth(username, accessToken);
        } catch (AuthenticationFailedException e) {
            accessToken = cache.refreshAccessToken(mContext, mAccount);
            saslAuthOAuth(username, accessToken);
        }
    }

    private void saslAuthOAuth(final String username, final String accessToken) throws IOException,
            MessagingException {
        final String authPhrase = "user=" + username + '\001' + "auth=Bearer " + accessToken +
                '\001' + '\001';
        byte[] data = Base64.encode(authPhrase.getBytes(), Base64.NO_WRAP);
        try {
            executeSensitiveCommand("AUTH XOAUTH2 " + new String(data),
                    "AUTH XOAUTH2 /redacted/");
        } catch (MessagingException me) {
            if (me.getMessage().length() > 1 && me.getMessage().charAt(1) == '3') {
                throw new AuthenticationFailedException(me.getMessage());
            }
            throw me;
        }
    }
}
