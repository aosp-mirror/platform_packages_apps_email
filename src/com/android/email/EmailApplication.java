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

import android.app.Application;
import android.content.Context;
import android.content.Intent;

import com.android.email.activity.setup.EmailPreferenceActivity;
import com.android.email.preferences.EmailPreferenceMigrator;
import com.android.mail.browse.ConversationMessage;
import com.android.mail.browse.InlineAttachmentViewIntentBuilder;
import com.android.mail.browse.InlineAttachmentViewIntentBuilderCreator;
import com.android.mail.browse.InlineAttachmentViewIntentBuilderCreatorHolder;
import com.android.mail.preferences.BasePreferenceMigrator;
import com.android.mail.preferences.PreferenceMigratorHolder;
import com.android.mail.preferences.PreferenceMigratorHolder.PreferenceMigratorCreator;
import com.android.mail.providers.Account;
import com.android.mail.ui.settings.PublicPreferenceActivity;
import com.android.mail.utils.LogTag;

public class EmailApplication extends Application {
    private static final String LOG_TAG = "Email";

    static {
        LogTag.setLogTag(LOG_TAG);

        PreferenceMigratorHolder.setPreferenceMigratorCreator(new PreferenceMigratorCreator() {
            @Override
            public BasePreferenceMigrator createPreferenceMigrator() {
                return new EmailPreferenceMigrator();
            }
        });

        InlineAttachmentViewIntentBuilderCreatorHolder.setInlineAttachmentViewIntentCreator(
                new InlineAttachmentViewIntentBuilderCreator() {
                    @Override
                    public InlineAttachmentViewIntentBuilder
                    createInlineAttachmentViewIntentBuilder(Account account, long conversationId) {
                        return new InlineAttachmentViewIntentBuilder() {
                            @Override
                            public Intent createInlineAttachmentViewIntent(Context context,
                                    String url, ConversationMessage message) {
                                return null;
                            }
                        };
                    }
                });

        PublicPreferenceActivity.sPreferenceActivityClass = EmailPreferenceActivity.class;
    }
}
