//
// Created by Eric on 10/15/2017.
//

#ifndef USBTV007_ANDROID_JAVACALLBACK_H
#define USBTV007_ANDROID_JAVACALLBACK_H

#include <jni.h>
#include <string>
#include "util.h"
#include "usbtv_definitions.h"

// TODO: I should make the base of this abstract, then inherit from it, requiring the
// child to implement invoke.  Also, the second method (_getBufMethod) should be in the
// child.
class JavaCallback {
private:
	JavaVM*     _javaVm;
	std::string _functionName;
	JNIEnv*     _env;
	jmethodID   _cbMethod;
	jmethodID   _getBufMethod;
	jclass      _methodClass;
	jobject     _methodParent;
	bool        _threadAttached;

	bool setEnv() {
		jint ret = _javaVm->GetEnv((void**)&_env, JNI_VERSION_1_6);
		switch (ret) {
			case JNI_OK:
				// env okay
				LOGD("Java Environment Set");
				return true;
			case JNI_EDETACHED:
				if (_javaVm->AttachCurrentThread(&_env, nullptr) >= 0) {
					return true;
				} else {
					_env = nullptr;
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
		_javaVm = jvm;
		_threadAttached = false;
		_functionName = funcName;
		if(setEnv()) {
			_methodParent = _env->NewGlobalRef(parent);
			jclass cls = _env->GetObjectClass(parent);
			_methodClass = (jclass) _env->NewGlobalRef(cls);
		}


	}
	~JavaCallback() {
		if(setEnv()) {
			_env->DeleteGlobalRef(_methodClass);
			_env->DeleteGlobalRef(_methodParent);
		}
	}

	void attachThread() {
		_threadAttached = setEnv();

		if (_threadAttached) {
			LOGD("Thread successfully attached");
			const char *cbsig = "(I)V";
			const char *getBufSig = "()[B";
			_cbMethod = _env->GetMethodID(_methodClass, _functionName.c_str(), cbsig);
			_getBufMethod = _env->GetMethodID(_methodClass, "getJavaBuffer", getBufSig);
		} else {
			LOGD("Unable to attach thread");
		}
	}

	void detachThread() {
		if (_threadAttached) {
			_javaVm->DetachCurrentThread();
			_threadAttached = false;
		}
	}

	void invoke(UsbTvFrame* frame) {
		if (_threadAttached) {
			jbyteArray array = (jbyteArray )_env->CallObjectMethod(_methodParent, _getBufMethod);
			if (array != nullptr) {
				_env->SetByteArrayRegion(array, 0, frame->bufferSize, (jbyte *) frame->buffer);
				_env->CallVoidMethod(_methodParent, _cbMethod, (jint) frame->frameId);
				_env->DeleteLocalRef(array);
			}
		}
	}
};

#endif //USBTV007_ANDROID_JAVACALLBACK_H
