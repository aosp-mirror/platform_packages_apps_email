# Copyright 2011, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH := $(call my-dir)

# Build the com.android.emailcommon static library. At the moment, this includes
# the emailcommon files themselves plus everything under src/org (apache code).  All of our
# AIDL files are also compiled into the static library

include $(CLEAR_VARS)

unified_email_unified_src_dir := ../../UnifiedEmail/unified_src
unified_email_src_dir := ../../UnifiedEmail/src
apache_src_dir := ../../UnifiedEmail/src/org

imported_unified_email_files := \
        $(unified_email_src_dir)/com/android/mail/utils/LogTag.java \
        $(unified_email_src_dir)/com/android/mail/utils/LogUtils.java \
        $(unified_email_src_dir)/com/android/mail/providers/UIProvider.java

LOCAL_MODULE := com.android.emailcommon
LOCAL_STATIC_JAVA_LIBRARIES := guava android-common
LOCAL_STATIC_JAVA_LIBRARIES += android-support-v4
LOCAL_SRC_FILES := $(call all-java-files-under, src/com/android/emailcommon)
LOCAL_SRC_FILES += \
    src/com/android/emailcommon/service/IEmailService.aidl \
    src/com/android/emailcommon/service/IEmailServiceCallback.aidl \
    src/com/android/emailcommon/service/IPolicyService.aidl \
    src/com/android/emailcommon/service/IAccountService.aidl
LOCAL_SRC_FILES += $(call all-java-files-under, $(apache_src_dir))
LOCAL_SRC_FILES += $(imported_unified_email_files)
LOCAL_SRC_FILES += $(call all-java-files-under, $(unified_email_src_dir)/com/android/emailcommon)

LOCAL_SDK_VERSION := 14

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

include $(BUILD_STATIC_JAVA_LIBRARY)
