LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)


chips_dir := ../../../../frameworks/ex/chips
unified_email_dir := ../../../../packages/apps/UnifiedEmail

# Include res dir from chips
# Include res dir from UnifiedEmail
res_dirs := res $(chips_dir)/res $(unified_email_dir)/res

LOCAL_MODULE_TAGS := optional

src_dirs := src \
    $(unified_email_dir)/src  \
    $(unified_email_dir)/email_src


LOCAL_STATIC_JAVA_LIBRARIES := android-common-chips
LOCAL_STATIC_JAVA_LIBRARIES += guava
LOCAL_STATIC_JAVA_LIBRARIES += android-common
LOCAL_STATIC_JAVA_LIBRARIES += com.android.emailcommon

LOCAL_SDK_VERSION := 14


LOCAL_SRC_FILES := $(call all-java-files-under, $(src_dirs)) \
        $(call all-logtags-files-under, $(src_dirs))

LOCAL_RESOURCE_DIR := $(addprefix $(LOCAL_PATH)/, $(res_dirs))

# Use assets dir from UnifiedEmail
# (the default package target doesn't seem to deal with multiple asset dirs)
LOCAL_ASSET_DIR := $(LOCAL_PATH)/$(unified_email_dir)/assets

LOCAL_AAPT_FLAGS := \
    --auto-add-overlay \
    --extra-packages com.android.ex.chips:com.android.mail

LOCAL_PACKAGE_NAME := Email2

LOCAL_PROGUARD_FLAG_FILES := proguard.flags $(unified_email_dir)/proguard.flags

include $(BUILD_PACKAGE)

# Use the following include to make our test apk.
include $(call all-makefiles-under,$(LOCAL_PATH))

