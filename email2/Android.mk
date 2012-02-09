LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)


# Include res dir from chips
chips_dir := ../../../../frameworks/ex/chips/res

# Include res dir from UnifiedEmail
unified_email_dir := ../../UnifiedEmail/res
res_dirs := res $(chips_dir) $(unified_email_dir)

LOCAL_MODULE_TAGS := optional

src_dirs := src \
    ../../UnifiedEmail/src  \
    ../../UnifiedEmail/email_src


LOCAL_STATIC_JAVA_LIBRARIES := android-common-chips
LOCAL_STATIC_JAVA_LIBRARIES += guava
LOCAL_STATIC_JAVA_LIBRARIES += android-common
LOCAL_STATIC_JAVA_LIBRARIES += com.android.emailcommon

LOCAL_SDK_VERSION := 14


LOCAL_SRC_FILES := $(call all-java-files-under, $(src_dirs)) \
        $(call all-logtags-files-under, $(src_dirs))

LOCAL_RESOURCE_DIR := $(addprefix $(LOCAL_PATH)/, $(res_dirs))

LOCAL_AAPT_FLAGS := \
    --auto-add-overlay \
    --rename-manifest-package com.android.email2 \
    --extra-packages com.android.ex.chips

LOCAL_PACKAGE_NAME := Email2

LOCAL_OVERRIDES_PACKAGES := UnifiedEmail

LOCAL_PROGUARD_FLAG_FILES := proguard.flags

include $(BUILD_PACKAGE)

# Use the following include to make our test apk.
include $(call all-makefiles-under,$(LOCAL_PATH))

