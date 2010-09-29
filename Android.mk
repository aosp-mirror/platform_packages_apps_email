# Copyright 2008, The Android Open Source Project
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

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)
# EXCHANGE-REMOVE-SECTION-START
LOCAL_SRC_FILES += \
    src/com/android/email/service/IEmailService.aidl \
    src/com/android/email/service/IEmailServiceCallback.aidl
# EXCHANGE-REMOVE-SECTION-END

LOCAL_JAVA_STATIC_LIBRARIES := android-common

LOCAL_PACKAGE_NAME := Email

LOCAL_PROGUARD_FLAG_FILES := proguard.flags

# The Emma tool analyzes code coverage when running unit tests on the
# application. This configuration line selects which packages will be analyzed,
# leaving out code which is tested by other means (e.g. static libraries) that
# would dilute the coverage results. These options do not affect regular
# production builds.
LOCAL_EMMA_COVERAGE_FILTER := +com.android.email.*,+org.apache.james.mime4j.* \
	+com.beetstra.jutf7.*,+org.apache.commons.io.*
# EXCHANGE-REMOVE-SECTION-START
LOCAL_EMMA_COVERAGE_FILTER += +com.android.exchange.*
# EXCHANGE-REMOVE-SECTION-END

include $(BUILD_PACKAGE)

# additionally, build unit tests in a separate .apk
include $(call all-makefiles-under,$(LOCAL_PATH))
