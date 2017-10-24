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
                                                                      jint fd,
                                                                      jint isoEndpoint,
                                                                      jint maxIsoPacketSize,
                                                                      jint framePoolSize,
                                                                      jint input,
                                                                      jint norm,
                                                                      jint scanType) {
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

	callback = new JavaCallback(javaVm, thisObj, "nativeFrameCallback", "(II)V");

	usbtv = new UsbTvDriver(jenv, thisObj, callback, (int)fd, (int)isoEndpoint,
	                        (int)maxIsoPacketSize, (int)framePoolSize, (int)input,
	                        (int)norm, (int)scanType);

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

JNIEXPORT void JNICALL Java_com_arksine_libusbtv_UsbTv_setSurface(JNIEnv* jenv,
                                                                  jobject thisObj,
                                                                  jobject surface) {
	if (usbtv != nullptr) {
		if (surface != nullptr) {
			ANativeWindow* window = ANativeWindow_fromSurface(jenv, surface);
			usbtv->setRenderWindow(window);
		} else {
			usbtv->setRenderWindow(nullptr);
		}

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
                                                                          jobject thisObj) {
	if (usbtv != nullptr) {
		return (jboolean)usbtv->startStreaming();
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

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_setTvNorm(JNIEnv* jenv,
                                                                 jobject thisObj,
                                                                 jint norm) {
	if (usbtv != nullptr) {
		return (jboolean)usbtv->setTvNorm((int)norm);
	} else {
		return (jboolean)false;
	}

}

JNIEXPORT jboolean JNICALL Java_com_arksine_libusbtv_UsbTv_setScanType(JNIEnv* jenv,
                                                                   jobject thisObj,
                                                                   jint scanType) {
	if (usbtv != nullptr) {
		return (jboolean)usbtv->setScanType((int)scanType);
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