/**
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.email.activity;

import android.content.Intent;
import android.os.Bundle;
import com.android.mail.compose.ComposeActivity;

/**
 * A subclass of {@link ComposeActivityEmail} which is exported for other Android packages to open.
 */
public class ComposeActivityEmailExternal extends ComposeActivityEmail {

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    sanitizeIntent();
    super.onCreate(savedInstanceState);
  }

  /**
   * Only relevant when WebView Compose is enabled. Change this when WebView
   * Compose is enabled for Email.
   */
  @Override
  public boolean isExternal() {
      return false;
  }

  /**
   * Overrides the value of {@code #getIntent()} so any future callers will get a sanitized version
   * of the intent.
   */
  // See b/114493057 for context.
  private void sanitizeIntent() {
    Intent sanitizedIntent = getIntent();
    if (sanitizedIntent != null) {
      sanitizedIntent.removeExtra(ComposeActivity.EXTRA_IN_REFERENCE_TO_MESSAGE_URI);
      setIntent(sanitizedIntent);
    }
  }
}
