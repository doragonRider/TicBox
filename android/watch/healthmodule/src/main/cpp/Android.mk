LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := ticbox_healthmodule
LOCAL_SRC_FILES := bootstrap.cpp
LOCAL_CFLAGS := -Wall -Wextra -Werror -fvisibility=hidden -ffunction-sections -fdata-sections
LOCAL_CPPFLAGS := -std=c++17 -fno-exceptions -fno-rtti
LOCAL_LDLIBS := -llog -lstdc++
LOCAL_LDFLAGS := -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--build-id=none
include $(BUILD_SHARED_LIBRARY)
