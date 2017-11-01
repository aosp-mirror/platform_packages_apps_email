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

LOCAL_PATH:= $(call my-dir)

src_dirs := src
res_dirs := res

##################################################

include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

LOCAL_STATIC_JAVA_LIBRARIES := android-support-test

# For LOCAL_JAVA_LANGUAGE_VERSION >= 1.7, OpenJDK 9 javac generates synthetic calls to
# Objects.requireNonNull() which was only added in Android API level 19. Thus, this must
# stay at 1.6 as long as LOCAL_SDK_VERSION is set to a value < 19. See http://b/38495704
LOCAL_JAVA_LANGUAGE_VERSION := 1.6
LOCAL_SDK_VERSION := 14
LOCAL_PACKAGE_NAME := EmailTests
LOCAL_INSTRUMENTATION_FOR := Email

LOCAL_SRC_FILES := $(call all-java-files-under, $(src_dirs))
LOCAL_RESOURCE_DIR := $(addprefix $(LOCAL_PATH)/, $(res_dirs))
LOCAL_AAPT_FLAGS := --auto-add-overlay

include $(BUILD_PACKAGE)

# Build all sub-directories
include $(call all-makefiles-under,$(LOCAL_PATH))
