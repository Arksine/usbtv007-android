//
// Created by Eric on 10/12/2017.
//

#include "UsbTvDriver.h"
#include "util.h"
#include <cstdlib>

void *frameProcessThread(void* context);

UsbTvDriver::UsbTvDriver(JNIEnv *env, jobject thisObj, int fd, int isoEndpoint,
                         int maxIsoPacketSize, int input, int norm, int scanType) {

	_initalized = false;
	_renderSurface = nullptr;
	_shouldRender = false;
	_streamActive = false;
	_framePool = nullptr;

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
	_secondFrame = false;
	_packetsDone = 0;
	_packetsPerField = 0;

	_usbConnection = new AndroidUsbDevice(fd, std::bind(&UsbTvDriver::onUrbReceived, this,
	                                                    std::placeholders::_1));

	_useCallback = false;
	_processFrame = nullptr;
	_frameProcessThread = nullptr;
	_frameProcessMutex = PTHREAD_MUTEX_INITIALIZER;
	_frameReadyCond = PTHREAD_COND_INITIALIZER;
	_frameWait = false;
	_frameProcessContext = new ThreadContext;
	_frameProcessContext->usbtv = this;
	_frameProcessContext->shouldRender = &_shouldRender;
	_frameProcessContext->useCallback = &_useCallback;
	_frameProcessContext->callback = new JavaCallback(env, thisObj, "frameCallback");

	// TODO: Initialize Audio Vars

	// TODO: Initialize Frame Renderer when implemented

	_initalized = true;
}

UsbTvDriver::~UsbTvDriver() {

	if (_streamActive) {
		stopStreaming();
	}
	// TODO: Delete any dynamic Audio Vars and Frame Renderer if necessary

	delete _frameProcessContext->callback;
	delete _frameProcessContext;
	delete _usbConnection;
}

bool UsbTvDriver::startStreaming() {
	if (_initalized && !_streamActive) {
		bool success;
		_streamActive = true;

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

		// Start Frame processing thread
		success = pthread_create(_frameProcessThread, nullptr,
		                         frameProcessThread, (void*)_frameProcessContext) == 0;
		if (!success) {
			LOGI("Could not start Frame Process Thread");
			_frameProcessThread = nullptr;
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
			startStreaming();
			return false;
		}

		success = _usbConnection->startIsoAsyncRead();

		if (!success) {
			LOGI("Could not start Isonchrnous transfer Thread");
			startStreaming();
			return false;
		}

		// TODO: Resume Audio when implemented

		return true;
	} else {
		return false;
	}
}

void UsbTvDriver::stopStreaming() {
	if (_initalized) {
		_streamActive = false;

		// TODO: Stop Audio

		// TODO: stop rendering after it is implemented

		// Stop Frame processor thread
		if (_frameProcessThread != nullptr) {
			pthread_mutex_lock(&_frameProcessMutex);
			if (_frameWait) {
				pthread_cond_signal(&_frameReadyCond);
			}
			pthread_mutex_unlock(&_frameProcessMutex);
			pthread_join(*_frameProcessThread, nullptr);
		}

		//
		if (_usbConnection->isIsoThreadRunning()) {
			_usbConnection->stopIsoAsyncRead();
		} else {
			_usbConnection->discardIsoTransfers();
		}

		// Clear lock for frame that usb was reading into;
		if (_usbInputFrame != nullptr) {
			_usbInputFrame->lock.clear();
			_usbInputFrame = nullptr;
		}

		// Clear lock for process frame
		if (_processFrame != nullptr) {
			_processFrame->lock.clear();
			_processFrame = nullptr;
		}

		freeFramePool();
	}
}

bool UsbTvDriver::setTvNorm(int norm) {
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
	if (_streamActive) {
		stopStreaming();
		return startStreaming();
	}

	return true;
}

bool UsbTvDriver::setTvInput(int input) {
	switch (input) {
		case 0:
			_input = TvInput ::USBTV_COMPOSITE_INPUT;
			break;
		case 1:
			_input = TvInput ::USBTV_SVIDEO_INPUT;
			break;
		default:
			LOGI("Invalid input selection");
			return false;
	}

	// If streaming, restart
	if (_streamActive) {
		stopStreaming();
		return startStreaming();
	}

	return true;
}

bool UsbTvDriver::setScanType(int scanType) {
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
	if (_streamActive) {
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
void UsbTvDriver::setSurface(jobject surface) {
	_shouldRender = surface != nullptr;
	// TODO: If rendering stop rendering if surface is null
	_renderSurface = surface;
	// TODO: set surface for framerender (I probably don't even need to track it in a var here)
}

/**
 * Polls for a complete frame.
 *
 * @return A completed UsbTvFrame received and parsed from the capture device
 */
UsbTvFrame* UsbTvDriver::getFrame() {
	pthread_mutex_lock(&_frameProcessMutex);
	_frameWait = true;
	pthread_cond_wait(&_frameReadyCond, &_frameProcessMutex);
	_frameWait = false;
	pthread_mutex_unlock(&_frameProcessMutex);
	return _processFrame;
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
 */
void UsbTvDriver::allocateFramePool() {
	if (_framePool == nullptr) {
		_framePool = new UsbTvFrame*[USBTV_FRAME_POOL_SIZE];
		uint32_t bufferheight = (_scanType == ScanType::INTERLEAVED) ? _frameHeight :
		                        uint32_t(_frameHeight / 2);
		size_t buffersize = _frameWidth * bufferheight * 2;  // width * height * bytes per pixel

		// init frame pool
		for (int i = 0; i < USBTV_FRAME_POOL_SIZE; i++) {
			_framePool[i] = new UsbTvFrame;
			_framePool[i]->buffer = malloc(buffersize);
			_framePool[i]->bufferSize = (uint32_t) buffersize;
			_framePool[i]->width = _frameWidth;
			_framePool[i]->height = (uint16_t) bufferheight;
			_framePool[i]->lock.clear();
		}
	}
}

/**
 * Frees UsbTvFrame objects from the heap, as well as their associated buffers.
 */
void UsbTvDriver::freeFramePool() {
	if (_framePool!= nullptr) {
		for (int i = 0; i < USBTV_FRAME_POOL_SIZE; i++) {
			while (_framePool[i]->lock.test_and_set());  // spinlock until buffer flag is released
			free(_framePool[i]->buffer);
			delete _framePool[i];
		}
		delete [] _framePool;
		_framePool = nullptr;
	}
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

		// Test lock for current frame.  When a frame is found with a cleared lock,
		// the lock flag will be set and the frame will be returned
		if (!frame->lock.test_and_set()) {
			return frame;
		}

		index++;

		if (index >= USBTV_FRAME_POOL_SIZE) {
			index = 0;
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
		#ifdef DEBUG_ON
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

		if (packetNumber == 0) {
			// New Frame
			_currentFrameId = frameId;
			_packetsDone = 0;
		} else if (frameId != _currentFrameId) {
			LOGD("Frame ID mismatch. Current: %d Received :%d",
					_currentFrameId, frameId);
			return;
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

		// Packet Finished
		if (packetNumber == (_packetsPerField - 1)) {
			if (_packetsDone != _packetsPerField) {
				LOGD("Fewer Packets processed than packets per field");
				// TODO: Frame error.  Should create a flag in TvFrame to
				// show the error rather than returning?
				return;
			}

			// An entire frame has been written to the buffer. Process by ScanType.
			//  - For progressive 60, execute color conversion and render here.
			//  - For progressive 30 only render if this is an ODD frame.
			//  - For interleaved only render after an entire frame is received.
			switch (_scanType){
				case ScanType::PROGRESSIVE:
					notifyFrameComplete();
					break;
				case ScanType::DISCARD:
					if (isOdd) {
						notifyFrameComplete();
					}
					break;
				case ScanType::INTERLEAVED:
					if (_secondFrame) {
						notifyFrameComplete();
						_secondFrame = false;
					} else if (isOdd) {
						_secondFrame = true;
					}
					break;
			}
		}
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
 * If another thread is polling via getFrame, this function sets the next
 * frame to be processed and signals the polling thread.
 *
 * If no thread is polling getFrame(), then nothing is done.  The current
 * _usbInputFrame will be overwritten by the next set of packets and
 * _processFrame will not be touched
 */
void UsbTvDriver::notifyFrameComplete() {
	pthread_mutex_lock(&_frameProcessMutex);
	if (_frameWait) {
		// Another thread is polling and waiting on a new frame, set it
		_usbInputFrame->frameId = _currentFrameId;
		_processFrame = _usbInputFrame;
		_usbInputFrame = fetchFrameFromPool();  // fetch a new frame
		pthread_cond_signal(&_frameReadyCond);
	} else {
		// Frame dropped / TODO: add to a counter?
	}
	pthread_mutex_unlock(&_frameProcessMutex);
}

/**
 * Function to be executed in the frame process thread
 *
 * @param context
 * @return
 */
void *frameProcessThread(void* context) {
	UsbTvDriver::ThreadContext* ctx = (UsbTvDriver::ThreadContext*) context;
	UsbTvDriver* usbtv = ctx->usbtv;

	// If callback is set, execute it.  Otherwise render if the surface is set.
	// If neither is set, do nothing except release the lock on frameToRender
	if (usbtv == NULL) {
		return 0;
	}

	UsbTvFrame* frame;

	while (usbtv->isStreaming()) {
		frame = usbtv->getFrame();

		if (frame == nullptr) {
			continue;
		}

		if (*(ctx->useCallback)) {
			ctx->callback->invoke(frame);
		}

		if (*(ctx->shouldRender)) {
			// TODO: call render frame
		}

		frame->lock.clear();
	}

	return 0;
}