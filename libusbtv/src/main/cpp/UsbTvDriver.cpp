// Copyright (C) 2020 Eric Callahan <arksine.code@gmail.com>
//
// This file may be distributed under the terms of the GNU GPLv3 license

#include "UsbTvDriver.h"

#if defined(PROFILE_FRAME) || defined(PROFILE_VIDEO_URB)
#include <chrono>
#endif

void frame_process_thread(Driver::ThreadContext* ctx);

// TODO: Some audio notes:  The structure seems simple, the bulk transfer comes in 256-byte packets with 4-byte headers
// So when processing a buffer I simply process in 256 byte segments, skipping the first 4-bytes.  The
// Rest is the payload.  The driver uses a transfer size of 20480, which is 20160 of payload.  This
// is approximately 100ms of audio based on the parameters below, so I think its safe to assume
// that the device replies to bulk transfers in that interval.  I'm best off using bulk continuation
// to get all of the data, but its tricky to implement.
//
// The driver uses the following params:
// Rate: 48800 Hz
// Channels - 2
// Depth - 16-bits

UsbTvDriver::UsbTvDriver(JNIEnv *env, JavaCallback* cb, jobject params)
		: _paramsHelper(env){

	_initialized = false;

	if (env == nullptr || cb == nullptr || params == nullptr) {
		return;
	}
	_env = env;

	/*
	 *  Get initial values from the params object usings the helper class
	 */

	int fd = _paramsHelper.getFileDescriptor(env, params);
	_framePoolSize = (uint16_t)_paramsHelper.getFramePoolSize(env, params);
	_isoEndpoint = (uint8_t)_paramsHelper.getVideoEndpoint(env, params);
	_maxIsoPacketSize = (uint32_t)_paramsHelper.getVideoUrbPacketSize(env, params);
	// TODO: Audio Endpoint and Audio Urb Size should also be retreived here


	_frameProcessQueue = new moodycamel::BlockingConcurrentQueue<UsbTvFrame*>((unsigned long)
	                                                                          (_framePoolSize - 1));


	_streamActive = false;
	_framePool = nullptr;
	_numIsoPackets = USBTV_ISOC_PACKETS_PER_REQUEST;
	_currentFrameId = 0;
	_lastOdd = true;
	_secondFrame = false;
	_packetsDone = 0;
	_packetsPerField = 0;

	_usbConnection = new AndroidUsbDevice(fd);

	_useCallback = false;
	_frameProcessThread = nullptr;
	_processThreadRunning = false;
	_frameProcessContext = new Driver::ThreadContext;
	_frameProcessContext->usbtv = this;
	_frameProcessContext->useCallback = &_useCallback;
	_frameProcessContext->threadRunning = &_processThreadRunning;
	_frameProcessContext->callback = cb;

#if defined(PROFILE_FRAME)
	_framePoolSpins = 0;
	_isoMaxCheck = false;
#endif
	// TODO: Initialize Audio Vars

	_initialized = true;
}


UsbTvDriver::~UsbTvDriver() {

	if(_initialized) {
		if (_streamActive) {
			stopStreaming();
		}

		LOGD("Streaming stopped");
		// TODO: Delete any dynamic Audio Vars if necessary

		delete _frameProcessQueue;
		delete _frameProcessContext;
		delete _usbConnection;
	}
}

bool UsbTvDriver::parseStreamingParams(jobject params) {

	_frameParams.frameWidth = (uint16_t)_paramsHelper.getFrameWidth(_env, params);
	_frameParams.frameHeight = (uint16_t)_paramsHelper.getFrameHeight(_env, params);
	_frameParams.bufferSize = (uint32_t)_paramsHelper.getFrameSizeInBytes(_env, params);
	_packetsPerField = (uint16_t)_paramsHelper.getVideoPacketsPerField(_env, params);

	LOGD("Params Frame Width: %d", _frameParams.frameWidth);
	LOGD("Params Frame Height: %d", _frameParams.frameHeight);
	LOGD("Params Buffer Size: %d", _frameParams.bufferSize);
	LOGD("Params Packets Per Field: %d", _packetsPerField);

	// TODO: I should do checks on the ordinals, or just use switch statements to assign them
	int ord = _paramsHelper.getNormOrdinal(_env, params);
	_frameParams.norm = static_cast<TvNorm>(ord);
	LOGD("Params TvNorm Ordinal: %d", ord);
	ord = _paramsHelper.getScanTypeOrdinal(_env, params);
	_frameParams.scanType = static_cast<ScanType>(ord);
	LOGD("Params ScanType Ordinal: %d", ord);
	ord = _paramsHelper.getInputSelectionOrdinal(_env, params);
	_input = static_cast<TvInput>(ord);
	LOGD("Params InputSelection Ordinal: %d", ord);

	return true;
}


bool UsbTvDriver::startStreaming(jobject params) {
	if (_initialized && !_streamActive) {
		bool success;
		_streamActive = true;
		_droppedFrameCounter = 0;
		_incompleteFrameCounter = 0;

		// Setup Parameters
		parseStreamingParams(params);

		// TODO: Pause Audio when implemented

		// Set interface to zero
		success = _usbConnection->setInterface(0, 0);
		if (!success) {
			LOGI("Could not set Interface to 0, 0");
			return false;
		}


		// Initialize video stream
		success = setRegisters(VIDEO_INIT, ARRAY_SIZE(VIDEO_INIT));
		if (!success) {
			LOGI("Could not initialize video stream registers");
			_streamActive = false;
			return false;
		}

		// Set the TV Norm registers
		switch (_frameParams.norm) {
			case TvNorm::NTSC:
				success = setRegisters(NTSC_TV_NORM, ARRAY_SIZE(NTSC_TV_NORM));
				break;
			case TvNorm::PAL:
				success = setRegisters(PAL_TV_NORM, ARRAY_SIZE(PAL_TV_NORM));
				break;
		}

		if (!success) {
			LOGI("Could not initialize Tv Norm Registers");
			_streamActive = false;
			return false;
		}

		// Set the Input registers
		switch (_input) {
			case TvInput::USBTV_COMPOSITE_INPUT:
				success = setRegisters(COMPOSITE_INPUT, ARRAY_SIZE(COMPOSITE_INPUT));
				break;
			case TvInput::USBTV_SVIDEO_INPUT:
				success =  setRegisters(SVIDEO_INPUT, ARRAY_SIZE(SVIDEO_INPUT));
				break;
		}

		if (!success) {
			LOGI("Could not initialize video input registers");
			_streamActive = false;
			return false;
		}

		// Init variables that depend on user settings

		allocateFramePool(params);
		_usbInputFrame = fetchFrameFromPool();

		// Start Frame processing thread
		if (_frameProcessThread == nullptr) {
			_processThreadRunning = true;
			_frameProcessThread = new std::thread(frame_process_thread, _frameProcessContext);
			success = (_frameProcessThread != nullptr);
		} else {
			LOGE("ERROR, Process thread not free;");
			success = false;
		}

		if (!success) {
			LOGI("Could not start Frame Process Thread");
			_processThreadRunning = false;
			stopStreaming();
			return false;
		}


		// Set interface to alternate setting 1, which begins streaming
		success = _usbConnection->setInterface(0, 1);
		if (!success) {
			LOGI("Could not set Interface to 0, 1");
			stopStreaming();        // stopStreaming will clean up the frame pool and process thread
			return false;
		}

		// Setup Isonchronous Usb Streaming
		success = _usbConnection->initIsoUrbs(USBTV_ISOC_TRANSFERS, _isoEndpoint,
		                                      _maxIsoPacketSize, _numIsoPackets,
		                                      std::bind(&UsbTvDriver::onUrbReceived, this,
		                                                std::placeholders::_1));

		if (!success) {
			LOGI("Could not Initialize Iso Transfers");
			stopStreaming();
			return false;
		}

		success = _usbConnection->startUrbAsyncRead();

		if (!success) {
			LOGI("Could not start Isonchrnous transfer Thread");
			stopStreaming();
			return false;
		}

		// TODO: Resume Audio when implemented

		return true;
	} else {
		return _streamActive;
	}
}

void UsbTvDriver::stopStreaming() {
	if (_initialized) {
		_streamActive = false;

		// TODO: Stop Audio

		// Stop Iso requests
		if (_usbConnection->isUrbThreadRunning()) {
			_usbConnection->stopUrbAsyncRead();
		} else {
			_usbConnection->discardIsoUrbs();
		}

		// Stop Frame processor thread
		if (_frameProcessThread != nullptr) {
			if (_processThreadRunning) {
				_processThreadRunning = false;
				UsbTvFrame *frame = nullptr;
				// Enqueue a null frame to make sure that the thread exits its loop
				_frameProcessQueue->enqueue(frame);
			}
			_frameProcessThread->join();
			delete _frameProcessThread;
			_frameProcessThread = nullptr;
		}

		_usbConnection->setInterface(0, 0);

		LOGD("Interface set to zero");

		// Make sure the process queue is empty and all locks have been released
		UsbTvFrame* frame;
		while(_frameProcessQueue->try_dequeue(frame)) {
			if (frame != nullptr) {
				frame->lock.clear(std::memory_order_release);
			}
		}

		// Clear lock for frame that usb was reading into;
		if (_usbInputFrame != nullptr) {
			_usbInputFrame->lock.clear(std::memory_order_release);
			_usbInputFrame = nullptr;
		}

		freeFramePool();

		LOGD("Dropped Frames: %d", _droppedFrameCounter);
		LOGD("Incomplete Frames: %d", _incompleteFrameCounter);
#if defined(PROFILE_FRAME)
		LOGD("Frame Pool Spins: %ld", _framePoolSpins);
		LOGD("Iso packets larger than 16KB recd: %s", _isoMaxCheck ? "true" : "false");
#endif
	}
}



bool UsbTvDriver::setTvInput(int input) {
	TvInput old = _input;
	switch (input) {
		case 0:
			_input = TvInput ::USBTV_COMPOSITE_INPUT;
			if (_streamActive && old != _input) {
				return setRegisters(COMPOSITE_INPUT, ARRAY_SIZE(COMPOSITE_INPUT));
			}
			break;
		case 1:
			_input = TvInput ::USBTV_SVIDEO_INPUT;
			if (_streamActive && old != _input) {
				return setRegisters(SVIDEO_INPUT, ARRAY_SIZE(SVIDEO_INPUT));
			}
			break;
		default:
			LOGI("Invalid input selection");
			return false;
	}

	return true;
}

bool UsbTvDriver::setControl(int control, int value) {

	if (_initialized) {
		// TODO:
	}
	return false;
}

int UsbTvDriver::getControl(int control) {
	if (_initialized) {
		// TODO:
	}
	return 0;
 }

/**
 * Polls for a complete frame.
 *
 * @return A completed UsbTvFrame received and parsed from the capture device
 */
UsbTvFrame* UsbTvDriver::getFrame() {
	UsbTvFrame* frame;
	_frameProcessQueue->wait_dequeue(frame);
	return frame;
}

/**
 * Allows an external frame consumer to clear the lock on a frame buffer when
 * it is finished processing.  This returns the buffer to the frame pool, making it available
 * to write.
 *
 * @param framePoolIndex    The index of the frame pool to release
 * @return  True if successful, otherwise false
 */
bool UsbTvDriver::clearFrameLock(int framePoolIndex) {
	bool success = true;
	_framePoolMutex.lock();  // Because its possible for this to be called when destroying the
							 // frame pool a mutex is necessary

	// TODO: instead of using a counter, just use an atomic flag.
	if (_framePool != nullptr) {
		// Note: the Pool Index is strictly controlled, so it shouldn't be possible to get a
		// frame index outside of the array bounds

		_framePool[framePoolIndex]->lock.clear(std::memory_order_release);
	} else {
		success = false;
	}
	_framePoolMutex.unlock();
	return success;
}

/**
 * Sets the Provided register values using a control transfer
 */
bool UsbTvDriver::setRegisters(const uint16_t regs[][2] , int size ) {
	uint16_t index;
	uint16_t value;

	for (int i = 0; i < size; i++) {
		index = regs[i][0];
		value = regs[i][1];

		if (!_usbConnection->controlTransfer(
				USB_DIR_OUT | USB_TYPE_VENDOR | USB_RECIP_DEVICE,
				USBTV_REQUEST_REG,
				value,
				index,
				NULL, 0, 0 )) {
			return false;
		}
	}

	return true;
}

/**
 * Allocates a pool of UsbTvFrame objects and their buffers
 *
 */
void UsbTvDriver::allocateFramePool(jobject params) {
	_framePoolMutex.lock();
	if (_framePool == nullptr) {
		_framePool = new UsbTvFrame*[_framePoolSize];

		// Variables necessary to create Java UsbTvFrame objects.
		const char* initSig = "(Lcom/arksine/libusbtv/DeviceParams;Ljava/nio/ByteBuffer;I)V";
		jclass framecls = _env->FindClass("com/arksine/libusbtv/UsbTvFrame");
		jmethodID midInit = _env->GetMethodID(framecls, "<init>", initSig);

		// init frame pool
		for (uint8_t i = 0; i < _framePoolSize; i++) {
			_framePool[i] = new UsbTvFrame;
			_framePool[i]->buffer = malloc(_frameParams.bufferSize);
			_framePool[i]->flags = 0;
			_framePool[i]->lock.clear(std::memory_order_release);
			_framePool[i]->frameId = 0;
			_framePool[i]->params = &_frameParams;

			// Each Frame in the FramePool also contains its corresponding java implementation
			// That way it only needs to be handled here, and it the frame can be returned
			// Through a function.
			jobject bb = _env->NewDirectByteBuffer(_framePool[i]->buffer, _frameParams.bufferSize);
			jobject jFrame = _env->NewObject(framecls, midInit, params, bb, (jint)i);
			_framePool[i]->javaFrame = _env->NewGlobalRef(jFrame);

			_env->DeleteLocalRef(jFrame);
			_env->DeleteLocalRef(bb);
		}

		_env->DeleteLocalRef(framecls);
	}
	_framePoolMutex.unlock();
}

/**
 * Frees UsbTvFrame objects from the heap, as well as their associated buffers.
 */
void UsbTvDriver::freeFramePool() {
	_framePoolMutex.lock();
	if (_framePool!= nullptr && !_streamActive) {
		for (int i = 0; i < _framePoolSize; i++) {
			if (_framePool[i]->lock.test_and_set(std::memory_order_acquire)) {
				LOGD("frame index %d still has a lock when attempting to free", i);
			}
			_env->DeleteGlobalRef(_framePool[i]->javaFrame);
			free(_framePool[i]->buffer);
			delete _framePool[i];
		}
		delete [] _framePool;
		_framePool = nullptr;
	}
	_framePoolMutex.unlock();
}

/**
 * Fetches an unlocked frame from the frame pool.  It will block until an unlocked frame
 * is received.
 *
 * @return A prevously unlocked frame from the pool, which is subsequently locked.
 */
UsbTvFrame* UsbTvDriver::fetchFrameFromPool() {
	UsbTvFrame* frame;
	uint8_t index = 0;

	// TODO: Add debug logic to profile to determine if the pool is spinning for a long period of time

	// Loop until an unlocked frame is found while the stream is active.  If the stream stops
	// and this pool is spinning then it will break so the thread can quit.
	while(_streamActive) {
		frame = _framePool[index];

		// Test lock for current frame.  The test atomically sets the lock active.  If
		// the lock was previously inactive then this is a free frame and it will be returned.
		if (!frame->lock.test_and_set(std::memory_order_acquire)) {
			frame->flags = FRAME_START;
			return frame;
		}

		index++;

		if (index >= _framePoolSize) {
			index = 0;
#if defined(PROFILE_FRAME)
			_framePoolSpins++;
#endif
		}
	}

	// to reach this point the stream is no longer active.  Just return any frame, as it
	// shouldn't be processed again.
	return _framePool[0];
}

/**
 * Callback given to the AndroidUsbDevice instance.  When a Usb Request Block
 * is received, this callback will be executed with a pointer to the URB
 *
 * @param urb A pointer to the current URB received from a USB device
 */
void UsbTvDriver::onUrbReceived(usbdevfs_urb *urb) {
	uint8_t* buffer = (uint8_t*)urb->buffer;
	unsigned int packetLength;
	unsigned int packetOffset = 0;

	// TODO: Check indexes 6 and 7 to see if they are always empty.  This is a check
	// to see if the USBDEVFS buffer limit of 16KB is applicable to iso transfers as well

	for (int i = 0; i < urb->number_of_packets; i++) {
		packetLength = urb->iso_frame_desc[i].actual_length;
		buffer += packetOffset;

#if defined(PROFILE_FRAME)
		if ((i > 5) && packetLength > 0) {
			_isoMaxCheck = true;
		}
#endif

		if (urb->iso_frame_desc[i].status == 0) {
			int count = packetLength / USBTV_PACKET_SIZE;
			for (int j = 0; j < count; j++) {
				uint8_t* packet = buffer + (j * USBTV_PACKET_SIZE);
				processPacket((__be32*) packet);
			}
		}
		packetOffset = urb->iso_frame_desc[i].length;
	}

	// Resubmit urb
	((UsbDevice::UrbContext *) urb->usercontext)->usbDevice->resubmitUrb(urb);
}

/**
 * Processes a URB packet
 *
 * @param packet a pointer to the packet buffer
 */
void UsbTvDriver::processPacket(__be32 *packet) {
	// Should be one packet, 1024 bytes long (256 words)

	uint32_t frameId;
	uint32_t packetNumber;
	bool isOdd;

	if (USBTV_FRAME_OK(packet)) {
		frameId = USBTV_FRAME_ID(packet);
		packetNumber = USBTV_PACKET_NO(packet);
		isOdd = USBTV_ODD(packet);

		packet++;   // Increment pointer past the header

		// The code below is to make sure packets are received as expected.  They did not
		// in java using JNA, which is why I am attempting a Native implementation
#if defined(DEBUG_PACKET)
		if (_currentFrameId != frameId) {
			LOGD("New Frame Id: %d\nOld Id: %d", frameId, _currentFrameId);
			_currentFrameId = frameId;
			if (_packetsDone != 0) {
				LOGD("Old frame packets dropped: %d", (359 - _packetsDone));
				_packetsDone = 0;
			}

		}

		if (packetNumber != _packetsDone) {
			LOGD("%d packets dropped on frame id %d", (packetNumber - _packetsDone), _currentFrameId);
			_packetsDone = (uint16_t)packetNumber;
		}

		if (packetNumber == 359) {
			_packetsDone = 0;
		} else {
			_packetsDone++;
		}
		return;
#endif

		if (packetNumber >= _packetsPerField) {
			LOGD("Packet number exceeds packets per field");
			LOGD("Frame Id: %d", frameId);
			LOGD("Is Field Odd: %s", isOdd ? "true" : "false");
			LOGD("Packet Number: %d", packetNumber);
			return;
		}

		if (packetNumber == 0 || frameId != _currentFrameId) {
			// If last frame was in progress but not submitted then it is dropped
			// TODO: I could do a frame check here, rather than at the end.  That way
			// I won't drop as many frames
			if ((_usbInputFrame->flags & FRAME_IN_PROGRESS) > 0) {
				LOGD("Incomplete Frame Dropped, ID: %d", _currentFrameId);
				_droppedFrameCounter++;
			}
			_lastOdd = isOdd;
			_currentFrameId = frameId;
			_packetsDone = 0;
			_usbInputFrame->flags = FRAME_IN_PROGRESS;
		}

		switch (_frameParams.scanType) {
			case ScanType::PROGRESSIVE:
				packetToProgressiveFrame((uint8_t*)packet, packetNumber);
				break;
			case ScanType::DISCARD:
				if (isOdd) {
					packetToProgressiveFrame((uint8_t*)packet, packetNumber);
				}
				break;
			case ScanType::INTERLEAVED:
				packetToInterleavedFrame((uint8_t*)packet, packetNumber, isOdd);
				break;
		}

		_packetsDone++;

		// TODO: instead of checking a Finished Frame when packetNumber == 359, I could
		// do it when a new Id is received.  Use _lastOdd to determine if the
		// frame was odd or not
		// Packet Finished
		if (packetNumber == (uint32_t)(_packetsPerField - 1)) {
			checkFinishedFrame(isOdd);
		}
	}
}

void UsbTvDriver::checkFinishedFrame(bool isOdd) {
	if (_packetsDone != _packetsPerField) {
		// Frame not completed, write error
		_usbInputFrame->flags = FRAME_PARTIAL;
		_incompleteFrameCounter++;
	} else {
		_usbInputFrame->flags = FRAME_COMPLETE;
	}

	// An entire frame has been written to the buffer. Process by ScanType.
	//  - For progressive 60, execute color conversion and render here.
	//  - For progressive 30 only render if this is an ODD frame.
	//  - For interleaved only render after an entire frame is received.
	switch (_frameParams.scanType){
		case ScanType::PROGRESSIVE:
			addCompleteFrameToQueue();
			break;
		case ScanType::DISCARD:
			if (isOdd) {
				addCompleteFrameToQueue();
			}
			break;
		case ScanType::INTERLEAVED:
			if (_secondFrame) {
				addCompleteFrameToQueue();
				_secondFrame = false;
			} else if (isOdd) {
				_secondFrame = true;
			}
			break;
	}
}

/**
 * Writes a packet to a progressive frame.  The location in the frame is
 * determined by the packet number.
 *
 * @param packet    The packet to write
 * @param packetNo  The packet number in the frame
 */
void UsbTvDriver::packetToProgressiveFrame(uint8_t *packet, uint32_t packetNo) {
	uint8_t* dstFrame = (uint8_t*)(_usbInputFrame->buffer);
	uint32_t bufOffset = packetNo * USBTV_PAYLOAD_SIZE;
	dstFrame += bufOffset;
	memcpy(dstFrame, packet, USBTV_PAYLOAD_SIZE);

}

/**
 * Writes a packet to an interleaved frame.  The location in the buffer is determined
 * by the packet number and whether or not the packet belongs to an odd frame.  This
 * particlar method writes frames in an Top Field First interleaved method (Odd Frames
 * should be the first field)
 *
 * Per the linux driver documentation, the driver sends FIELDS sequentially.  The first field
 * holds the odd lines captured, the second field holds the even.  PACKETS contain a portion of
 * the field, and they also come in sequentially.  Each packet holds 960 bytes of frame data,
 * the equivalent of 2/3 of an actual line.  This results in 3 packets making two lines.
 *
 * This function breaks those packets in half, then writes them to their appropriate line.  It
 * interleaves the fields, in Top Frame First order.  For example, an odd field received
 * will be written to the buffer as follows:
 *
 * line 0: <packet[0][0]> <packet[0][1]> <packet[1][0]>
 * line 2: <packet[1][1]> <packet[2][0]> <packet[2][1]>
 * ...repeat until field is complete
 *
 * @param packet    The packet to write to the frame
 * @param packetNo  The number of the accompanying packet
 * @param isOdd     Flag determining if the packet and even or odd field in the frame
 */
void UsbTvDriver::packetToInterleavedFrame(uint8_t *packet, uint32_t packetNo, bool isOdd) {
	uint8_t* dstFrame;
	uint8_t packetHalf;
	uint32_t halfPayloadSize = USBTV_PAYLOAD_SIZE / 2;
	uint8_t oddFieldOffset = (uint8_t)((isOdd) ? 0 : 1);
	int lineSize = _frameParams.frameWidth * 2;  // Line width in bytes

	for (packetHalf = 0; packetHalf < 2; packetHalf++) {
		// Get the overall index of the packet half I am operating on.
		uint32_t partIndex = packetNo * 2 + packetHalf;

		// 3 parts makes a line, so the line index is determined by dividing the part index
		// by 3.  Multiply by two to skip every other line. The oddFieldOffset is added to write to the correct
		// line in the buffer
		uint32_t lineIndex = (partIndex / 3) * 2 + oddFieldOffset;

		// the starting byte of a line is determined by the lineIndex * lineSize.
		// From there we can determine how far into the line we need to begin our write.
		// partIndex MOD 3 == 0 - start at beginning of line
		// partIndex MOD 3 == 1 - offset half a payload
		// partIndex MOD 3 == 2 - offset entire payload
		uint32_t bufferOffset = (lineIndex * lineSize) + (halfPayloadSize * (partIndex % 3));
		dstFrame = (uint8_t*)(_usbInputFrame->buffer) + bufferOffset;
		memcpy(dstFrame, packet, halfPayloadSize);
		packet += halfPayloadSize;
	}
}

/**
 * Called when a complete frame has been copied from Usb Request Blocks.
 * Simply adds a frame to the process frame queue,
 */
void UsbTvDriver::addCompleteFrameToQueue() {
	UsbTvFrame* frame = _usbInputFrame;
	if (_frameProcessQueue->try_enqueue(frame)) {
		_usbInputFrame = fetchFrameFromPool();
	} else {
		LOGD("Frame Dropped, no space in process Queue. ID: %d", _currentFrameId);
		_droppedFrameCounter++;
		_usbInputFrame->flags = FRAME_START;
	}
}

/**
 * Function to be executed in the frame process thread
 *
 * @param context
 * @return
 */
void frame_process_thread(Driver::ThreadContext* ctx) {
	UsbTvDriver* usbtv = ctx->usbtv;

#if defined(PROFILE_FRAME)
	//** PROFILING VARS ***
	int frameCount = 0;
	long maxTime = 0;
	long minTime = 100;
#endif

	// If callback is set, execute it.  Otherwise render if the surface is set.
	// If neither is set, do nothing except release the lock on frameToRender
	if (usbtv == NULL) {
		return;
	}

	// Attach native thread to Java thread
	ctx->callback->attachThread();

	UsbTvFrame* frame;

	while (*(ctx->threadRunning)) {
		frame = usbtv->getFrame();

#if defined(PROFILE_FRAME)
		/**
		 * Update: Keeping a java side frame pool seems to have eliminated the high spikes.
		 * Process times typically range between less than 1ms to 5ms, with spikes no higher
		 * than 10 ms.  This  falls in acceptable parameters.
		 */
		auto startTime = std::chrono::steady_clock::now();
#endif

		if (frame == nullptr) {
			continue;
		}

		if (*(ctx->useCallback)) {
			ctx->callback->invoke(frame);
		}

#if defined(PROFILE_FRAME)
		auto processTime = std::chrono::duration_cast<std::chrono::milliseconds>(
				std::chrono::steady_clock::now() - startTime);

		maxTime = (processTime.count() > maxTime) ? processTime.count() : maxTime;
		minTime = (processTime.count() < minTime) ? processTime.count() : minTime;

		frameCount++;

		// Print profiling vars
		if (frameCount >= 120) {
			LOGD("Last 120 Frames, Process Max Time: %ld ms\nMin Time: %ld ms",maxTime, minTime);
			frameCount = 0;
			maxTime = 0;
			minTime = 1000;
		}
#endif

	}

	ctx->callback->detachThread();

	return;
}