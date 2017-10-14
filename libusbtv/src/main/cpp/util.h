//
// Created by Eric on 10/8/2017.
//

#ifndef USBTV007_ANDROID_UTIL_H
#define USBTV007_ANDROID_UTIL_H

#include <jni.h>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "NativeUsbTvJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,LOG_TAG,__VA_ARGS__)

#define CLEAR(x) memset(&(x), 0, sizeof(x))

// TODO: implement a Timber like scheme for Logging.  In fact, it would be good if
// I could incorporate Timber / Logger into Native code.


#endif //USBTV007_ANDROID_UTIL_H
