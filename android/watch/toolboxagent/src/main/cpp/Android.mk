LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := ticbox_pty
LOCAL_SRC_FILES := pty.c screen_udp.c screen_fec.c
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
LOCAL_SRC_FILES += screen_fec_neon.c.neon
endif
ifeq ($(TARGET_ARCH_ABI),arm64-v8a)
LOCAL_SRC_FILES += screen_fec_neon.c
endif
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -Werror -fvisibility=hidden -ffunction-sections -fdata-sections
LOCAL_LDFLAGS := -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--build-id=none
include $(BUILD_SHARED_LIBRARY)
