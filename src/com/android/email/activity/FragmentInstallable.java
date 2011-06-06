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

package com.android.email.activity;

import android.app.Activity;
import android.app.Fragment;

/**
 * Interface for {@link Activity} that can "install" fragments.
 */
public interface FragmentInstallable {
    /**
     * Called when a {@link Fragment} wants to be installed to the host activity.
     *
     * Fragments which use this MUST call this from {@link Fragment#onActivityCreated} using
     * {@link UiUtilities#installFragment}.
     *
     * This means a host {@link Activity} can safely assume a passed {@link Fragment} is already
     * created.
     */
    public void onInstallFragment(Fragment fragment);

    /**
     * Called when a {@link Fragment} wants to be uninstalled from the host activity.
     *
     * Fragments which use this MUST call this from {@link Fragment#onDestroyView} using
     * {@link UiUtilities#uninstallFragment}.
     */
    public void onUninstallFragment(Fragment fragment);
}
