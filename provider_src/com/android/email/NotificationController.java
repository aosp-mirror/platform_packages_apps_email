/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.Context;
import android.content.Intent;
import com.android.emailcommon.provider.Account;
import com.android.emailcommon.provider.EmailContent.Attachment;


public interface NotificationController {
    void watchForMessages();
    void showDownloadForwardFailedNotificationSynchronous(Attachment attachment);
    void showLoginFailedNotificationSynchronous(long accountId, boolean incoming);
    void cancelLoginFailedNotification(long accountId);
    void cancelNotifications(final Context context, final Account account);
    void handleUpdateNotificationIntent(Context context, Intent intent);
    void showSecurityNeededNotification(Account account);
    void showSecurityUnsupportedNotification(Account account);
    void showSecurityChangedNotification(Account account);
    void cancelSecurityNeededNotification();
    void showPasswordExpiringNotificationSynchronous(long accountId);
    void showPasswordExpiredNotificationSynchronous(long accountId);
    void cancelPasswordExpirationNotifications();
}
