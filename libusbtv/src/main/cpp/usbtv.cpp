//
// Created by Eric on 10/8/2017.
//

#include <cstdlib>
#include <android/native_window_jni.h>
#include "usbtv.h"
#include "UsbTvDriver.h"


// Global vars necessary for tracking
UsbTvDriver* usbtv = nullptr;
JavaVM* javaVm = nullptr;
JavaCallback* callback = nullptr;

// TODO: jniOnUnload?

jint JNI_OnLoad(JavaVM *jvm, void *reserved) {
	javaVm = jvm;

	JNIEnv* env;
	if (jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
		return -1;
	}

	// I could register natives here if I dont want these long function names

	return JNI_VERSION_1_6;

}

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_initialize(JNIEnv* jenv,
                                                                      jobject thisObj,
                                                                      jobject params) {
	if (javaVm == nullptr) {
		LOGE("Error, Java VM pointer is not initialized");
		return (jboolean) false;
	}

	if (usbtv != nullptr) {
		LOGE("UsbTvDriver already initialized, dispose prior to a new initialization");
		return (jboolean) false;
	}

	if (callback != nullptr) {
		delete callback;
	}

	callback = new JavaCallback(javaVm, thisObj, "nativeFrameCallback",
	                            "(Lcom/arksine/libusbtv/UsbTvFrame;II)V");

	usbtv = new UsbTvDriver(jenv, callback, params);

	if (!usbtv->isInitialized()) {
		LOGE("Error Initializing UsbTV Driver");
		delete callback;
		delete usbtv;
		callback = nullptr;
		usbtv = nullptr;

		return (jboolean) false;
	}

	return (jboolean)true;

}

JNIEXPORT void JNICALL Java_com_arksine_libusbtv_UsbTv_dispose(JNIEnv* jenv,
                                                               jobject thisObj) {
	if (usbtv != nullptr) {
		delete usbtv;
		usbtv = nullptr;
	}

	if (callback != nullptr) {
		delete callback;
		callback = nullptr;
	}

}

JNIEXPORT void JNICALL Java_com_arksine_libusbtv_UsbTv_useCallback(JNIEnv* jenv,
                                                                   jobject thisObj,
                                                                   jboolean shouldUse) {
	if (usbtv != nullptr) {
		usbtv->setCallback((bool)shouldUse);
	}
}


JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_startStreaming(JNIEnv* jenv,
                                                                          jobject thisObj,
                                                                          jobject params) {
	if (usbtv != nullptr) {
		return (jboolean)usbtv->startStreaming(params);
	} else {
		return (jboolean)false;
	}
}

JNIEXPORT void JNICALL Java_com_arksine_libusbtv_UsbTv_stopStreaming(JNIEnv* jenv,
                                                                     jobject thisObj) {
	if (usbtv != nullptr) {
		usbtv->stopStreaming();
	}

}

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_setInput(JNIEnv* jenv,
                                                                jobject thisObj,
                                                                jint input) {
	if (usbtv != nullptr) {
		return (jboolean)usbtv->setTvInput((int)input);
	} else {
		return (jboolean)false;
	}

}


JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_setControl(JNIEnv* jenv,
                                                                  jobject thisObj,
                                                                  jint control,
                                                                  jint value) {
	if (usbtv != nullptr) {
		return (jboolean)usbtv->setControl((int)control, (int)value);
	} else {
		return (jboolean)false;
	}

}

JNIEXPORT jint JNICALL Java_com_arksine_libusbtv_UsbTv_getControl(JNIEnv* jenv,
                                                                  jobject thisObj,
                                                                  jint control) {
	if (usbtv != nullptr) {
		return usbtv->getControl((int)control);
	} else {
		return -1;
	}
}

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTvFrame_returnFrameToPool(JNIEnv* jenv,
                                                                              jobject thisObj,
                                                                              jint poolIndex) {
	if (usbtv != nullptr) {
		return (jboolean) usbtv->clearFrameLock((int)poolIndex);
	} else {
		return (jboolean) false;
	}
}