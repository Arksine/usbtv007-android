//
// Created by Eric on 10/8/2017.
//

#ifndef USBTV007_ANDROID_USBTV_H
#define USBTV007_ANDROID_USBTV_H

#include <jni.h>
#include <endian.h>
#include <atomic>
#include "AndroidUsbDevice.h"

extern "C" {
jint JNI_OnLoad(JavaVM *jvm, void *reserved);

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_initialize(JNIEnv* jenv,
                                                                      jobject thisObj,
                                                                      jint fd,
                                                                      jint isoEndpoint,
                                                                      jint maxIsoPacketSize,
                                                                      jint framePoolSize,
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
