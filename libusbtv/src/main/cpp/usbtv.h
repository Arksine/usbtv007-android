//
// Created by Eric on 10/8/2017.
//

#ifndef USBTV007_ANDROID_USBTV_H
#define USBTV007_ANDROID_USBTV_H

#include <jni.h>
#include "AndroidUsbDevice.h"

extern "C" {
jint JNI_OnLoad(JavaVM *jvm, void *reserved);

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_initialize(JNIEnv* jenv,
                                                                      jobject thisObj,
                                                                      jobject params);

JNIEXPORT void JNICALL Java_com_arksine_libusbtv_UsbTv_dispose(JNIEnv* jenv,
                                                               jobject thisObj);

JNIEXPORT void JNICALL Java_com_arksine_libusbtv_UsbTv_useCallback(JNIEnv* jenv,
                                                                  jobject thisObj,
                                                                  jboolean shouldUse);

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_startStreaming(JNIEnv* jenv,
                                                                          jobject thisObj,
                                                                          jobject params);

JNIEXPORT void JNICALL Java_com_arksine_libusbtv_UsbTv_stopStreaming(JNIEnv* jenv,
                                                                         jobject thisObj);

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_setInput(JNIEnv* jenv,
                                                                        jobject thisObj,
                                                                        jint input);

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_setControl(JNIEnv* jenv,
                                                                      jobject thisObj,
                                                                      jint control,
                                                                      jint value);

JNIEXPORT jint JNICALL Java_com_arksine_libusbtv_UsbTv_getControl(JNIEnv* jenv,
                                                                  jobject thisObj,
                                                                  jint control);

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTvFrame_returnFrameToPool(JNIEnv* jenv,
                                                                                  jobject thisObj,
                                                                                  jint poolIndex);
};

#endif //USBTV007_ANDROID_USBTV_H
