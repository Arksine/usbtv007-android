//
// Created by Eric on 10/8/2017.
//

#ifndef USBTV007_ANDROID_USBTV_H
#define USBTV007_ANDROID_USBTV_H

#include <jni.h>
#include <endian.h>
#include <atomic>
#include "AndroidUsbDevice.h"

#define USBTV_BASE		    0xc000
#define USBTV_VIDEO_EP	    0x81
#define USBTV_AUDIO_EP  	0x83
#define USBTV_CONTROL_REG	11
#define USBTV_REQUEST_REG	12


#define USBTV_ISOC_TRANSFERS	            16
#define USBTV_ISOC_PACKETS_PER_REQUEST	    8

// TODO: These are the sizes in 32-bit buffers.  The sizes in bytes are 1024 and 960 respectively
// Not sure which I should use as of yet, C/C++ makes it easy to cast arrays to any kind of data
// so its not difficult to use either
#define USBTV_PACKET_SIZE	    256
#define USBTV_PAYLOAD_SIZE      240

// size of the array containing input frame buffers.  TODO: I should probably make this a dynamic size
#define USBTV_FRAME_POOL_SIZE 4

#define USBTV_AUDIO_URBSIZE	20480
#define USBTV_AUDIO_HDRSIZE	4
#define USBTV_AUDIO_BUFFER	65536

#define ARRAY_SIZE(array) (sizeof((array))/sizeof((array[0])))

extern "C" {
JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_initialize(JNIEnv* jenv,
                                                                      jobject thisObj,
                                                                      jint fd,
                                                                      jint isoEndpoint,
                                                                      jint maxIsoPacketSize,
                                                                      jint input,
                                                                      jint norm,
                                                                      jint scanType);

JNIEXPORT void JNICALL Java_com_arksine_libusbtv_UsbTv_dispose(JNIEnv* jenv,
                                                               jobject thisObj);

JNIEXPORT void JNICALL Java_com_arksine_libusbtv_UsbTv_setSurface(JNIEnv* jenv,
                                                                  jobject thisObj,
                                                                  jobject surface);

JNIEXPORT void JNICALL Java_com_arksine_libusbtv_UsbTv_useCallback(JNIEnv* jenv,
                                                                  jobject thisObj,
                                                                  jboolean shouldUse);

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_startStreaming(JNIEnv* jenv,
                                                                          jobject thisObj);

JNIEXPORT void JNICALL Java_com_arksine_libusbtv_UsbTv_stopStreaming(JNIEnv* jenv,
                                                                         jobject thisObj);

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_setInput(JNIEnv* jenv,
                                                                        jobject thisObj,
                                                                        jint input);

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_setTvNorm(JNIEnv* jenv,
                                                                     jobject thisObj,
                                                                     jint norm);

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_setScanType(JNIEnv* jenv,
                                                                   jobject thisObj,
                                                                   jint scanType);

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_setControl(JNIEnv* jenv,
                                                                      jobject thisObj,
                                                                      jint control,
                                                                      jint value);

JNIEXPORT jint JNICALL Java_com_arksine_libusbtv_UsbTv_getControl(JNIEnv* jenv,
                                                                  jobject thisObj,
                                                                  jint control);
};

#endif //USBTV007_ANDROID_USBTV_H
