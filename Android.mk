#  Copyright (C) 2015 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_SRC_FILES += $(call all-java-files-under, ../ContactsCommon/src)
LOCAL_SRC_FILES += $(call all-java-files-under, ../PhoneCommon/src)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
ifeq ($(TARGET_BUILD_APPS),)
    LOCAL_RESOURCE_DIR += frameworks/support/v7/appcompat/res
    LOCAL_RESOURCE_DIR += frameworks/support/v7/recyclerview/res
else
    LOCAL_RESOURCE_DIR += prebuilts/sdk/current/support/v7/appcompat/res
    LOCAL_RESOURCE_DIR += prebuilts/sdk/current/support/v7/recyclerview/res
endif
LOCAL_RESOURCE_DIR += frameworks/opt/chips/res
LOCAL_RESOURCE_DIR += frameworks/opt/colorpicker/res
LOCAL_RESOURCE_DIR += frameworks/opt/photoviewer/res
LOCAL_RESOURCE_DIR += frameworks/opt/photoviewer/activity/res
LOCAL_RESOURCE_DIR += packages/apps/ContactsCommon/res
LOCAL_RESOURCE_DIR += packages/apps/PhoneCommon/res

LOCAL_JAVA_LIBRARIES += telephony-common \
    ims-common

LOCAL_STATIC_JAVA_LIBRARIES := android-common
LOCAL_STATIC_JAVA_LIBRARIES += android-common-framesequence
LOCAL_STATIC_JAVA_LIBRARIES += android-support-v4
LOCAL_STATIC_JAVA_LIBRARIES += android-support-v7-appcompat
LOCAL_STATIC_JAVA_LIBRARIES += android-support-v7-palette
LOCAL_STATIC_JAVA_LIBRARIES += android-support-v7-recyclerview
LOCAL_STATIC_JAVA_LIBRARIES += android-support-v13
LOCAL_STATIC_JAVA_LIBRARIES += com.android.vcard
LOCAL_STATIC_JAVA_LIBRARIES += guava
LOCAL_STATIC_JAVA_LIBRARIES += libchips
LOCAL_STATIC_JAVA_LIBRARIES += libphotoviewer
LOCAL_STATIC_JAVA_LIBRARIES += libphonenumber
LOCAL_STATIC_JAVA_LIBRARIES += colorpicker
LOCAL_STATIC_JAVA_LIBRARIES += contacts-picaso

include $(LOCAL_PATH)/version.mk

LOCAL_AAPT_FLAGS := --auto-add-overlay
LOCAL_AAPT_FLAGS += --version-name "$(version_name_package)"
LOCAL_AAPT_FLAGS += --version-code $(version_code_package)
LOCAL_AAPT_FLAGS += --extra-packages android.support.v7.appcompat
LOCAL_AAPT_FLAGS += --extra-packages android.support.v7.recyclerview
LOCAL_AAPT_FLAGS += --extra-packages com.android.ex.chips
LOCAL_AAPT_FLAGS += --extra-packages com.android.vcard
LOCAL_AAPT_FLAGS += --extra-packages com.android.ex.photo
LOCAL_AAPT_FLAGS += --extra-packages com.android.colorpicker
LOCAL_AAPT_FLAGS += --extra-packages com.android.contacts.common
LOCAL_AAPT_FLAGS += --extra-packages com.android.phone.common

ifdef TARGET_BUILD_APPS
    LOCAL_JNI_SHARED_LIBRARIES := libframesequence libgiftranscode
else
    LOCAL_REQUIRED_MODULES:= libframesequence libgiftranscode
endif

# utilize ContactsCommon's phone-number-based contact-info lookup
ifeq ($(contacts_common_dir),)
  contacts_common_dir := ../ContactsCommon
endif
CONTACTS_COMMON_LOOKUP_PROVIDER ?= $(LOCAL_PATH)/$(contacts_common_dir)/info_lookup
include $(CONTACTS_COMMON_LOOKUP_PROVIDER)/phonenumber_lookup_provider.mk

LOCAL_PROGUARD_FLAGS := -ignorewarnings -include build/core/proguard_basic_keeps.flags

LOCAL_PROGUARD_ENABLED := nosystem

LOCAL_PROGUARD_FLAG_FILES := proguard.flags
ifeq (eng,$(TARGET_BUILD_VARIANT))
    LOCAL_PROGUARD_FLAG_FILES += proguard-test.flags
else
    LOCAL_PROGUARD_FLAG_FILES += proguard-release.flags
endif

LOCAL_JACK_ENABLED := disabled

LOCAL_PACKAGE_NAME := messaging

LOCAL_CERTIFICATE := platform

# Causes build errors with ContactsCommon
#LOCAL_SDK_VERSION := current

include $(BUILD_PACKAGE)

include $(call all-makefiles-under, $(LOCAL_PATH))
