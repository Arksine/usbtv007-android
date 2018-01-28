//
// A helper class to get Fields from the DeviceParams object.  This takes care
// of getting refences to the fields and/or methods, as well as cleaning up
// the local references
//

#ifndef USBTV007_ANDROID_DEVICEPARAMSHELPER_H
#define USBTV007_ANDROID_DEVICEPARAMSHELPER_H

#include <jni.h>

class DeviceParamsHelper {
private:
	jfieldID _fidFileDescriptor;
	jfieldID _fidVideoEndpoint;
	jfieldID _fidAudioEndpoint;
	jfieldID _fidVideoUrbPacketSize;
	jfieldID _fidAudioUrbPacketSize;
	jfieldID _fidVideoPacketsPerField;
	jfieldID _fidCaptureAudio;
	jfieldID _fidFramePoolSize;
	jfieldID _fidFrameWidth;
	jfieldID _fidFrameHeight;
	jfieldID _fidFrameSizeInBytes;
	jfieldID _fidNorm;
	jfieldID _fidScanType;
	jfieldID _fidInputSelection;

	jmethodID _midNormOrdinal;
	jmethodID _midScanTypeOrdinal;
	jmethodID _midInputSelectionOrdinal;

public:
	DeviceParamsHelper(JNIEnv* env) {
		jclass paramsCls = env->FindClass("com/arksine/libusbtv/DeviceParams");
		jclass normCls = env->FindClass("com/arksine/libusbtv/UsbTv$TvNorm");
		jclass scanTypeCls = env->FindClass("com/arksine/libusbtv/UsbTv$ScanType");
		jclass inputCls = env->FindClass("com/arksine/libusbtv/UsbTv$TvNorm");

		_fidFileDescriptor = env->GetFieldID(paramsCls, "mFileDescriptor", "I");
		_fidVideoEndpoint = env->GetFieldID(paramsCls, "mVideoEndpoint", "I");
		_fidAudioEndpoint = env->GetFieldID(paramsCls, "mAudioEndpoint", "I");
		_fidVideoUrbPacketSize = env->GetFieldID(paramsCls, "mVideoUrbPacketSize", "I");
		_fidAudioUrbPacketSize = env->GetFieldID(paramsCls, "mAudioUrbPacketSize", "I");
		_fidVideoPacketsPerField = env->GetFieldID(paramsCls, "mVideoPacketsPerField", "I");
		_fidCaptureAudio = env->GetFieldID(paramsCls, "mCaptureAudio", "Z");
		_fidFramePoolSize = env->GetFieldID(paramsCls, "mFramePoolSize", "I");
		_fidFrameWidth = env->GetFieldID(paramsCls, "mFrameWidth", "I");
		_fidFrameHeight = env->GetFieldID(paramsCls, "mFrameHeight", "I");
		_fidFrameSizeInBytes = env->GetFieldID(paramsCls, "mFrameSizeBytes", "I");
		_fidNorm = env->GetFieldID(paramsCls, "mNorm", "com/arksine/libusbtv/UsbTv$TvNorm");
		_fidScanType = env->GetFieldID(paramsCls, "mScanType", "com/arksine/libusbtv/UsbTv$ScanType");
		_fidInputSelection = env->GetFieldID(paramsCls, "mInput", "com/arksine/libusbtv/UsbTv$InputSelection");

		_midNormOrdinal = env->GetMethodID(normCls, "ordinal", "()I");
		_midScanTypeOrdinal = env->GetMethodID(scanTypeCls, "ordinal", "()I");
		_midInputSelectionOrdinal = env->GetMethodID(inputCls, "ordinal", "()I");

		env->DeleteLocalRef(paramsCls);
		env->DeleteLocalRef(normCls);
		env->DeleteLocalRef(scanTypeCls);
		env->DeleteLocalRef(inputCls);
	}

	int getFileDescriptor(JNIEnv* env, jobject params) {
		return (int) env->GetIntField(params, _fidFileDescriptor);
	}

	int getVideoEndpoint(JNIEnv* env, jobject params) {
		return (int) env->GetIntField(params, _fidVideoEndpoint);
	}

	int getAudioEndpoint(JNIEnv* env, jobject params) {
		return (int) env->GetIntField(params, _fidAudioEndpoint);
	}

	int getVideoUrbPacketSize(JNIEnv* env, jobject params) {
		return (int) env->GetIntField(params, _fidVideoUrbPacketSize);
	}

	int getAudioUrbPacketSize(JNIEnv* env, jobject params) {
		return (int) env->GetIntField(params, _fidAudioUrbPacketSize);
	}

	int getVideoPacketsPerField(JNIEnv* env, jobject params) {
		return (int) env->GetIntField(params, _fidVideoPacketsPerField);
	}

	bool isAudioEnabled(JNIEnv* env, jobject params) {
		return (bool) env->GetBooleanField(params, _fidCaptureAudio);
	}

	int getFramePoolSize(JNIEnv* env, jobject params) {
		return (int) env->GetIntField(params, _fidFramePoolSize);
	}
	int getFrameWidth(JNIEnv* env, jobject params) {
		return (int) env->GetIntField(params, _fidFrameWidth);
	}

	int getFrameHeight(JNIEnv* env, jobject params) {
		return (int) env->GetIntField(params, _fidFrameHeight);
	}

	int getFrameSizeInBytes(JNIEnv* env, jobject params) {
		return (int) env->GetIntField(params, _fidFrameSizeInBytes);
	}

	int getNormOrdinal(JNIEnv* env, jobject params) {
		jobject normObj = env->GetObjectField(params, _fidNorm);
		int ord = (int) env->CallIntMethod(normObj, _midNormOrdinal);
		env->DeleteLocalRef(normObj);
		return ord;
	}

	int getScanTypeOrdinal(JNIEnv* env, jobject params) {
		jobject stObj = env->GetObjectField(params, _fidScanType);
		int ord = (int) env->CallIntMethod(stObj, _midScanTypeOrdinal);
		env->DeleteLocalRef(stObj);
		return ord;
	}

	int getInputSelectionOrdinal(JNIEnv* env, jobject params) {
		jobject inputObj = env->GetObjectField(params, _fidInputSelection);
		int ord = (int) env->CallIntMethod(inputObj, _midInputSelectionOrdinal);
		env->DeleteLocalRef(inputObj);
		return ord;
	}

};


#endif //USBTV007_ANDROID_PARAMSJNIHELPER_H
