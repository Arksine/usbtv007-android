//
// Created by Eric on 10/15/2017.
//

#ifndef USBTV007_ANDROID_JAVACALLBACK_H
#define USBTV007_ANDROID_JAVACALLBACK_H

#include <jni.h>
#include <string>
#include "util.h"
#include "usbtv_definitions.h"

class JavaCallback {
private:
	JavaVM* javaVm;
	std::string functionName;
	JNIEnv *env;
	jmethodID cbMethod;
	jclass methodClass;
	jobject methodParent;
	bool threadAttached;

	bool setEnv() {
		jint ret = javaVm->GetEnv((void**)&env, JNI_VERSION_1_6);
		switch (ret) {
			case JNI_OK:
				// env okay
				LOGD("Java Environment Set");
				return true;
			case JNI_EDETACHED:
				if (javaVm->AttachCurrentThread(&env, nullptr) >= 0) {
					LOGD("successfully attached thread");
					return true;
				} else {
					LOGD("Could not attach JVM to thread");
					env = nullptr;
					return false;
				}
			default:
				return false;
		}
	}
public:
	// TODO: when color space and scantype are added to UsbTvFrame, they will
	// also need to be added to the callback.  This will change the signature
	// below and the invoke member function
	JavaCallback(JavaVM* jvm, jobject parent, std::string funcName) {
		javaVm = jvm;
		threadAttached = false;
		functionName = funcName;
		if(setEnv()) {
			methodParent = env->NewGlobalRef(parent);
			jclass cls = env->GetObjectClass(parent);
			methodClass = (jclass) env->NewGlobalRef(cls);
		}


	}
	~JavaCallback() {
		if(setEnv()) {
			env->DeleteGlobalRef(methodClass);
			env->DeleteGlobalRef(methodParent);
		}
	}

	void attachThread() {
		threadAttached = setEnv();

		if (threadAttached) {
			LOGD("Thread successfully attached");
			const char *signature = "([BIII)V";
			cbMethod = env->GetMethodID(methodClass, functionName.c_str(), signature);
		} else {
			LOGD("Unable to attach thread");
		}
	}

	void detachThread() {
		if (threadAttached) {
			javaVm->DetachCurrentThread();
			threadAttached = false;
		}
	}

	void invoke(UsbTvFrame* frame) {
		if (threadAttached) {
			jbyteArray array = env->NewByteArray(frame->bufferSize);
			env->SetByteArrayRegion(array, 0, frame->bufferSize, (jbyte *) frame->buffer);
			env->CallVoidMethod(methodParent, cbMethod, array, (jint) frame->width,
			                    (jint) frame->height, (jint) frame->frameId);
			env->DeleteLocalRef(array);
		}
	}
};

#endif //USBTV007_ANDROID_JAVACALLBACK_H
