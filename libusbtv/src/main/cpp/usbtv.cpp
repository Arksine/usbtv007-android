//
// Created by Eric on 10/8/2017.
//

#include <cstdlib>
#include "usbtv.h"
#include "UsbTvDriver.h"
#include "util.h"

UsbTvDriver* usbtv = nullptr;
JavaVM* javaVm = nullptr;

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
	if (usbtv != nullptr) {
		LOGE("UsbTV already initialized, dispose prior to a new initialization");
		return (jboolean) false;
	}

	usbtv = new UsbTvDriver(javaVm, thisObj, (int)fd, (int)isoEndpoint, (int)maxIsoPacketSize,
	                        (int)framePoolSize, (int)input, (int)norm, (int)scanType);


	return (jboolean)true;

}

JNIEXPORT void JNICALL Java_com_arksine_libusbtv_UsbTv_dispose(JNIEnv* jenv,
                                                               jobject thisObj) {
	if (usbtv != nullptr) {
		delete usbtv;
		usbtv = nullptr;
	}

}

JNIEXPORT void JNICALL Java_com_arksine_libusbtv_UsbTv_setSurface(JNIEnv* jenv,
                                                                  jobject thisObj,
                                                                  jobject surface) {
	if (usbtv != nullptr) {
		usbtv->setSurface(surface);
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

