LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := usbtv
LOCAL_SRC_FILES := usbtv.cpp UsbTvDriver.cpp util.cpp AndroidUsbDevice.cpp
LOCAL_CLANG := true

LOCAL_CFLAGS := -std=c++11 -pthread -Wall -Werror

LOCAL_LDLIBS := -llog \
				-landroid \
				-latomic

# TODO: Add JNI Renderscript compat if necessary

include $(BUILD_SHARED_LIBRARY)