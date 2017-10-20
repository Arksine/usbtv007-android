LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := usbtv
LOCAL_SRC_FILES := usbtv.cpp UsbTvDriver.cpp AndroidUsbDevice.cpp
LOCAL_CLANG := true

LOCAL_CFLAGS := -std=c++11 -Wall -Werror
LOCAL_ARM_MODE := arm

LOCAL_LDLIBS := -llog \
				-landroid \
				-latomic

# TODO: Add JNI Renderscript compat if necessary

include $(BUILD_SHARED_LIBRARY)