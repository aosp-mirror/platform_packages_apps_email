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

LOCAL_PATH := $(call my-dir)

# Build the Email application itself, along with its tests and the tests for the emailcommon
# static library.  All tests can be run via runtest email

include $(CLEAR_VARS)
# Include res dir from chips
chips_dir := ../../../frameworks/ex/chips/res
mail_common_dir := ../../../frameworks/opt/mailcommon/res
res_dir := $(chips_dir) $(mail_common_dir) res

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src/com/android/email)
LOCAL_SRC_FILES += $(call all-java-files-under, src/com/beetstra)
LOCAL_RESOURCE_DIR := $(addprefix $(LOCAL_PATH)/, $(res_dir))
LOCAL_AAPT_FLAGS := --auto-add-overlay
LOCAL_AAPT_FLAGS += --extra-packages com.android.ex.chips

LOCAL_STATIC_JAVA_LIBRARIES := android-common com.android.emailcommon guava android-common-chips

LOCAL_PACKAGE_NAME := Email

LOCAL_PROGUARD_FLAG_FILES := proguard.flags

LOCAL_SDK_VERSION := current

# The Emma tool analyzes code coverage when running unit tests on the
# application. This configuration line selects which packages will be analyzed,
# leaving out code which is tested by other means (e.g. static libraries) that
# would dilute the coverage results. These options do not affect regular
# production builds.
LOCAL_EMMA_COVERAGE_FILTER := +com.android.emailcommon.*,+com.android.email.*, \
    +org.apache.james.mime4j.*,+com.beetstra.jutf7.*,+org.apache.commons.io.*

include $(BUILD_PACKAGE)

# only include rules to build other stuff for the original package, not the derived package.
ifeq ($(strip $(LOCAL_PACKAGE_OVERRIDES)),)
# additionally, build unit tests in a separate .apk
include $(call all-makefiles-under,$(LOCAL_PATH))
endif
