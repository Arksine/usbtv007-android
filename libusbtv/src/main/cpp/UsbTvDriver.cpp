//
// Created by Eric on 10/12/2017.
//

#include "UsbTvDriver.h"

#if defined(PROFILE_FRAME) || defined(PROFILE_VIDEO_URB)
#include <chrono>
#endif

void frame_process_thread(Driver::ThreadContext* ctx);

UsbTvDriver::UsbTvDriver(JNIEnv *env, jobject utvObj, JavaCallback* cb, int fd,
                         int isoEndpoint, int maxIsoPacketSize, int framePoolSize,
                         int input, int norm, int scanType)
		: _frameProcessQueue((size_t)(framePoolSize - 1)){

	_initalized = false;

	if (env == nullptr || cb == nullptr || utvObj == nullptr) {
		return;
	}

	_env = env;
	_streamActive = false;
	_framePool = nullptr;
	_framePoolSize = (uint16_t)framePoolSize;

	if (!setTvNorm(norm)){
		return;
	}

	if (!setTvInput(input)) {
		return;
	}

	if (!setScanType(scanType)) {
		return;
	}

	_isoEndpoint = (uint8_t)isoEndpoint;
	_numIsoPackets = USBTV_ISOC_PACKETS_PER_REQUEST;
	_maxIsoPacketSize = (uint32_t)maxIsoPacketSize;
	_currentFrameId = 0;
	_lastOdd = true;
	_secondFrame = false;
	_packetsDone = 0;
	_packetsPerField = 0;

	_usbtvObj = env->NewGlobalRef(utvObj);
	_usbConnection = new AndroidUsbDevice(fd, std::bind(&UsbTvDriver::onUrbReceived, this,
	                                                    std::placeholders::_1));

	_useCallback = false;
	_frameProcessThread = nullptr;
	_processThreadRunning = false;
	_frameProcessContext = new Driver::ThreadContext;
	_frameProcessContext->usbtv = this;
	_frameProcessContext->useCallback = &_useCallback;
	_frameProcessContext->renderer = &_glRenderer;
	_frameProcessContext->threadRunning = &_processThreadRunning;
	_frameProcessContext->callback = cb;

#if defined(PROFILE_FRAME)
	_framePoolSpins = 0;
#endif
	// TODO: Initialize Audio Vars

	_initalized = true;
}

UsbTvDriver::~UsbTvDriver() {

	if(_initalized) {
		if (_streamActive) {
			stopStreaming();
		}

		LOGD("Streaming stopped");
		// TODO: Delete any dynamic Audio Vars if necessary

		_env->DeleteGlobalRef(_usbtvObj);
		delete _frameProcessContext;
		delete _usbConnection;
	}
}

bool UsbTvDriver::startStreaming() {
	if (_initalized && !_streamActive) {
		bool success;
		_streamActive = true;
		_droppedFrameCounter = 0;
		_incompleteFrameCounter = 0;

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
		switch (_tvNorm) {
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
		_packetsPerField = uint16_t((_frameWidth * _frameHeight) / USBTV_PAYLOAD_SIZE);
		allocateFramePool();
		_usbInputFrame = fetchFrameFromPool();

		// Init Native renderer y-mask
		uint16_t height = (_scanType == ScanType::INTERLEAVED) ? _frameHeight :
		                  (uint16_t )(_frameHeight / 2);
		_glRenderer.initYmask(_frameWidth, height);

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
		success =_usbConnection->initIsoTransfers(USBTV_ISOC_TRANSFERS, _isoEndpoint,
		                                          _maxIsoPacketSize, _numIsoPackets);

		if (!success) {
			LOGI("Could not Initialize Iso Transfers");
			stopStreaming();
			return false;
		}

		success = _usbConnection->startIsoAsyncRead();

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
	if (_initalized) {
		_streamActive = false;

		// TODO: Stop Audio

		_glRenderer.signalStop();       // Stop Rendering

		// Stop Iso requests
		if (_usbConnection->isIsoThreadRunning()) {
			_usbConnection->stopIsoAsyncRead();
		} else {
			_usbConnection->discardIsoTransfers();
		}

		// Stop Frame processor thread
		if (_frameProcessThread != nullptr) {
			if (_processThreadRunning) {
				_processThreadRunning = false;
				UsbTvFrame *frame = nullptr;
				// Enqueue a null frame to make sure that the thread exits its loop
				_frameProcessQueue.enqueue(frame);
			}
			_frameProcessThread->join();
			delete _frameProcessThread;
			_frameProcessThread = nullptr;
		}

		_usbConnection->setInterface(0, 0);

		LOGD("Interface set to zero");

		// Make sure the process queue is empty and all locks have been released
		UsbTvFrame* frame;
		while(_frameProcessQueue.try_dequeue(frame)) {
			if (frame != nullptr) {
				frame->lock = 0;
			}
		}

		// Clear lock for frame that usb was reading into;
		if (_usbInputFrame != nullptr) {
			_usbInputFrame->lock = 0;
			_usbInputFrame = nullptr;
		}

		freeFramePool();

		LOGD("Dropped Frames: %d", _droppedFrameCounter);
		LOGD("Incomplete Frames: %d", _incompleteFrameCounter);
#if defined(PROFILE_FRAME)
		LOGD("Frame Pool Spins: %ld", _framePoolSpins);
#endif
	}
}

bool UsbTvDriver::setTvNorm(int norm) {
	TvNorm old = _tvNorm;
	switch (norm) {
		case 0:
			_tvNorm = TvNorm ::NTSC;
			_frameWidth = 720;
			_frameHeight = 480;
			break;
		case 1:
			_tvNorm = TvNorm ::PAL;
			_frameWidth = 720;
			_frameHeight = 576;
			break;
		default:
			LOGI("Invalid TV norm selection");
			return false;
	}

	// If streaming, restart
	if (_streamActive && old != _tvNorm) {
		stopStreaming();
		return startStreaming();
	}

	return true;
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

bool UsbTvDriver::setScanType(int scanType) {
	ScanType old = _scanType;
	switch (scanType) {
		case 0:
			_scanType = ScanType::PROGRESSIVE;
			break;
		case 1:
			_scanType = ScanType::DISCARD;
			break;
		case 2:
			_scanType = ScanType::INTERLEAVED;
			break;
		default:
			LOGI("Invalid Scan Type selection");
			return false;
	}

	// If streaming, restart
	if (_streamActive && _scanType != old) {
		stopStreaming();
		return startStreaming();
	}

	return true;
}

bool UsbTvDriver::setControl(int control, int value) {

	if (_initalized) {
		// TODO:
	}
	return false;
}

int UsbTvDriver::getControl(int control) {
	if (_initalized) {
		// TODO:
	}
	return 0;
 }

/**
 * Sets the render surface, received from Java.  If the surface received is null, rendering
 * will be stopped
 *
 * @param surface The Android surface object to render to
 */
void UsbTvDriver::setRenderWindow(ANativeWindow* window) {
	_glRenderer.setRenderWindow(window);
}

/**
 * Polls for a complete frame.
 *
 * @return A completed UsbTvFrame received and parsed from the capture device
 */
UsbTvFrame* UsbTvDriver::getFrame() {
	UsbTvFrame* frame;
	_frameProcessQueue.wait_dequeue(frame);
	return frame;
}

/**
 * Allows an external frame consumer to clear the lock on a frame buffer when
 * it is finished processing, which makes it for the driver to write.
 *
 * @param framePoolIndex    The index of the frame pool to release
 * @return  True if successful, otherwise false
 */
bool UsbTvDriver::clearFrameLock(int framePoolIndex) {
	bool success = true;
	_framePoolMutex.lock();
	if (_framePool != nullptr) {
		// the Pool Index is strictly controlled, so it shouldn't be possible to get a
		// frame index outside of the array bounds

		_framePool[framePoolIndex]->lock--; // Decrement pool lock counter
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
void UsbTvDriver::allocateFramePool() {
	_framePoolMutex.lock();
	if (_framePool == nullptr) {
		_framePool = new UsbTvFrame*[_framePoolSize];
		uint32_t bufferheight = (_scanType == ScanType::INTERLEAVED) ? _frameHeight :
		                        uint32_t(_frameHeight / 2);
		size_t buffersize = _frameWidth * bufferheight * 2;  // width * height * bytes per pixel

		// Setup callback to send pool references to the VM

		// TODO: rather than callback to java to create a pool of frames there, create them here
		// send them back to the callback.  Or even better, let java poll the
		// getFrame() function, which returns the created java object that the struct below references
		// JNI allows the user to set private fields, so that won't be a problem and shouldn't be
		// too slow, especially since the setting of those fields would be happening in the
		// thread that is polling getFrame()

		// (pool index would be set here)
		const char* signature = "(Ljava/nio/ByteBuffer;I)V";
		const char* funcname = "nativePoolSetup";
		jclass cls = _env->GetObjectClass(_usbtvObj);
		jmethodID poolSetupMethod = _env->GetMethodID(cls, funcname, signature);

		// init frame pool
		for (uint8_t i = 0; i < _framePoolSize; i++) {
			_framePool[i] = new UsbTvFrame;
			_framePool[i]->poolIndex = i;
			_framePool[i]->buffer = malloc(buffersize);
			_framePool[i]->bufferSize = (uint32_t) buffersize;
			_framePool[i]->width = _frameWidth;
			_framePool[i]->height = (uint16_t) bufferheight;
			_framePool[i]->flags = 0;
			_framePool[i]->lock = 0;

			// Create a DirectByteBuffer around the frame's buffer, then notify JVM so
			// it can create a matching pool with references to the buffers
			jobject bb = _env->NewDirectByteBuffer(_framePool[i]->buffer, buffersize);
			_framePool[i]->byteBuffer = _env->NewGlobalRef(bb);
			_env->CallVoidMethod(_usbtvObj, poolSetupMethod, bb, (jint)i);
			_env->DeleteLocalRef(bb);
		}

		_env->DeleteLocalRef(cls);
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
			if (_framePool[i]->lock != 0) {
				LOGD("frame index %d still has a lock when attempting to free", i);
			}
			_env->DeleteGlobalRef(_framePool[i]->byteBuffer);
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

	// Loop until an unlocked frame is found.
	while(true) {
		frame = _framePool[index];

		// Test lock for current frame.  A frame is unlocked when the counter reaches 0.
		// The counter is incremented for each reference to a frame (at this point there is
		// a maximum of two)
		if (frame->lock == 0) {
			frame->lock++;
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

	for (int i = 0; i < urb->number_of_packets; i++) {
		packetLength = urb->iso_frame_desc[i].actual_length;
		buffer += packetOffset;
		if (urb->iso_frame_desc[i].status == 0) {
			int count = packetLength / USBTV_PACKET_SIZE;
			for (int j = 0; j < count; j++) {
				uint8_t* packet = buffer + (j * USBTV_PACKET_SIZE);
				processPacket((__be32*) packet);
			}
		}
		packetOffset = urb->iso_frame_desc[i].length;
	}
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

		switch (_scanType) {
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
	switch (_scanType){
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
 * TODO: add detailed information as to how interleaved packets are processed
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
	int lineSize = _frameWidth * 2;  // Line width in bytes

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
	if (_frameProcessQueue.try_enqueue(frame)) {
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

	ctx->renderer->threadStartCheck();
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
		auto startTime = std::chrono::system_clock::now();
#endif

		if (frame == nullptr) {
			continue;
		}

		if (*(ctx->useCallback)) {
			frame->lock++;      // this is a second reference, add a counter to the lock
			ctx->callback->invoke(frame);
		}

		// Render a frame.  If the render window is not set, the frame's lock will
		// simply be decremented.
		ctx->renderer->renderFrame(frame);


#if defined(PROFILE_FRAME)
		auto processTime = std::chrono::duration_cast<std::chrono::milliseconds>(
				std::chrono::system_clock::now() - startTime);

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

	// TODO: Must call destroy on the renderer here.  The EGL context requires that
	// everything be done on the same thread

	ctx->callback->detachThread();
	ctx->renderer->threadEndCheck();

	return;
}