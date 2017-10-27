//
// Created by Eric on 10/12/2017.
//

#ifndef USBTV007_ANDROID_USBTVDRIVER_H
#define USBTV007_ANDROID_USBTVDRIVER_H

#include <thread>
#include <android/native_window.h>
#include "usbtv_definitions.h"
#include "AndroidUsbDevice.h"
#include "FrameRenderer.h"
#include "JavaCallback.h"
#include "ConcurrentQueue/blockingconcurrentqueue.h"
#include "DeviceParamsHelper.h"

class UsbTvDriver;

namespace Driver {
	struct ThreadContext {
		UsbTvDriver*    usbtv;
		JavaCallback*   callback;
		FrameRenderer*  renderer;
		bool*           useCallback;
		bool*           threadRunning;
	};
}

class UsbTvDriver {
private:
	bool _initialized; // Variable to check to make sure constructor successfully completed
	bool _streamActive;

	JNIEnv* _env;        // Reference to Java environment from local thread

	DeviceParamsHelper _paramsHelper;
	FrameParams        _frameParams;

	/* Video Members */
	TvInput     _input;

	uint16_t        _framePoolSize;
	UsbTvFrame**    _framePool;
	std::mutex      _framePoolMutex;

	AndroidUsbDevice*   _usbConnection;
	bool                _useCallback;

	// Isonchronous Transfer Variables
	uint8_t     _isoEndpoint;
	uint8_t     _numIsoPackets;
	uint32_t    _maxIsoPacketSize;

	// packet/frame tracking variables
	UsbTvFrame* _usbInputFrame;      // The current frame being written to from Usb
	uint32_t    _currentFrameId;
	uint16_t    _packetsPerField;
	uint16_t    _packetsDone;
	bool        _lastOdd;
	bool        _secondFrame;

	// Frame Process variables
	Driver::ThreadContext*  _frameProcessContext;
	bool                    _processThreadRunning;
	std::thread*            _frameProcessThread;

	moodycamel::BlockingConcurrentQueue<UsbTvFrame*>*    _frameProcessQueue;

	FrameRenderer _glRenderer;

	uint32_t    _droppedFrameCounter;
	uint32_t    _incompleteFrameCounter;

#if defined(PROFILE_FRAME)
	long _framePoolSpins;
#endif

	/* TODO: Audio Members */

	/* Private Member Functions */
	bool setRegisters(const uint16_t regs[][2], int size);
	UsbTvFrame* fetchFrameFromPool();
	void allocateFramePool(jobject params);
	void freeFramePool();

	bool parseStreamingParams(jobject params);
	void onUrbReceived(usbdevfs_urb* urb);
	void processPacket(__be32* packet);
	void packetToProgressiveFrame(uint8_t* packet, uint32_t packetNo);
	void packetToInterleavedFrame(uint8_t* packet, uint32_t packetNo, bool isOdd);
	void checkFinishedFrame(bool isOdd);
	void addCompleteFrameToQueue();


public:
	UsbTvDriver(JNIEnv *env, JavaCallback* cb, jobject params);
	~UsbTvDriver();

	bool isInitialized() { return _initialized;}
	bool isStreaming() { return _streamActive;};


	void setCallback(bool shouldUse) {_useCallback = shouldUse;}
	void setRenderWindow(ANativeWindow* window);

	UsbTvFrame* getFrame();
	bool clearFrameLock(int framePoolIndex);

	bool startStreaming(jobject params);
	void stopStreaming();
	bool setTvInput(int input);
	bool setControl(int control, int value);
	int  getControl(int control);

};


#endif //USBTV007_ANDROID_USBTVDRIVER_H
