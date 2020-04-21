// Copyright (C) 2020 Eric Callahan <arksine.code@gmail.com>
//
// This file may be distributed under the terms of the GNU GPLv3 license

#include "AndroidUsbDevice.h"
#include "util.h"
#include <cstdlib>
#include <linux/version.h>

#define PROFILE_VIDEO_URB

// TODO: If a bulk transfer returns -EPIPE, it is stalled.  Need to ioctl send clear halt

AndroidUsbDevice::AndroidUsbDevice(int fd) {
	_fileDescriptor = fd;
	_urbThread = nullptr;
	_isoUrbsSubmitted = 0;
	_bulkUrbsSubmitted = 0;
	_urbThreadRunning = false;
	checkCapabilities();
}

AndroidUsbDevice::~AndroidUsbDevice() {
	if (_urbThreadRunning) {
		stopUrbAsyncRead();
	}

	freeBulkUrbs();
	freeIsoUrbs();
}

/**
 *  Kernel versions >= 3.6.0 support Scatter-Gather techniques for bulk transfers using buffers
 *  larger than 16KB as long as the host controller supports it.  This function checks to
 *  the kernel version to see if it is supported.  If not, Bulk Continuation must be used
 *  for large buffers.  Bulk continuation was added in kernel 2.6.32, and considering that
 *  Usbhost was added in API 12 which had a kernel of 2.6.36, it should always be supported.
 *
 *  It should be noted that XHCI (USB 3.0) does not support Bulk Continuation, but it should
 *  in theory support scatter-gather
 */
void AndroidUsbDevice::checkCapabilities() {
	uint32_t caps;
	int ret = ioctl(_fileDescriptor, USBDEVFS_GET_CAPABILITIES, &caps);
	if (ret == 0) {
		_scatterGatherEnabled = (caps & USBDEVFS_CAP_BULK_SCATTER_GATHER) > 0;
		LOGD("Scatter-Gather enabled status: %s", _scatterGatherEnabled ? "true" : "false");
		if (!_scatterGatherEnabled) {
			bool bulkContEnabled = (caps & USBDEVFS_CAP_BULK_CONTINUATION) > 0;
			if (!bulkContEnabled) {
				LOGE("Oh shit, neither scatter-gather nor bulk continuation is available");
			}
		}
	} else {
		LOGD("Device capability query failed");
		_scatterGatherEnabled = false;
	}
}

/**
 * Sets the interface and alternate setting of a connected usb device
 * @param interface the interface number to set
 * @param altSetting the alternate setting to set
 * @return true if successful, false if ioctl returns an error
 */
bool AndroidUsbDevice::setInterface(unsigned int interface, unsigned int altSetting) {
	usbdevfs_setinterface intf;
	intf.interface = interface;
	intf.altsetting = altSetting;

	int ret = ioctl(_fileDescriptor, USBDEVFS_SETINTERFACE, &intf);

	if (ret == 0) {
		return true;
	} else {
		LOGI("IOCTL Set Interface Error, code %d", ret);
		return false;
	}

}

/**
 * Performs a control transfer on a connected usb device
 * @param requestType Type of request (ie USB_DIR_IN | USB_RECIP_DEVICE)
 * @param request     The request id (device specific)
 * @param value       The value to send for the request
 * @param index       The index for the request
 * @param buffer      The buffer to send/receive data (can be NULL)
 * @param length      The length of the buffer
 * @param timeout     Time to wait for transaction in ms, 0 = wait indefinitely
 * @return true if successful, false if ioctl returns an error
 */
bool AndroidUsbDevice::controlTransfer(uint8_t requestType, uint8_t request, uint16_t value,
                                       uint16_t index, void *buffer, uint16_t length,
                                       uint32_t timeout) {

	usbdevfs_ctrltransfer ctrltransfer;
	ctrltransfer.bRequestType = requestType;
	ctrltransfer.bRequest = request;
	ctrltransfer.wValue = value;
	ctrltransfer.wIndex = index;
	ctrltransfer.wLength = length;
	ctrltransfer.timeout = timeout;
	ctrltransfer.data = buffer;

	int ret = ioctl(_fileDescriptor, USBDEVFS_CONTROL, &ctrltransfer);

	if (ret == 0) {
		return true;
	} else {
		LOGI("IOCTL Control Transfer Error, code %d", ret);
		return false;
	}
}

/**
 * Initiates a usb bulk transfer to a connected usb device.  Note: if a transfer length exceeds the
 * maximum transfer length allowed by the OS, the the request is broken down into multiple
 * transfers.  In this case the timeout variable refers to the timeout PER transfer.  If
 * any transfer exceeds the timeout the transaction will break and return.  For reads the
 * number of bytes read will be returned, for writes -1 will be returned (unsuccesful)
 *
 * @param endpoint The endpoint to transfer.  It should be ORed with the direction, ie (ep | USB_DIR_IN)
 * @param length   The length of the buffer to read or write
 * @param timeout  Time to wait for transaction in ms, 0 = wait indefinitely
 * @param data     The buffer of data to send or recieve
 * @return the number of bytes written or read,  -1 if there was an error
 */
int AndroidUsbDevice::bulkTransfer(uint8_t endpoint, unsigned int length, unsigned int timeout,
                                   void *data) {
	if (endpoint == 0) {
		LOGE("Error, Endpoint cannot use Endpoint 0 for bulk transfers");
		return -1;
	}

	if ((endpoint & USB_DIR_IN) == USB_DIR_IN) {
		return bulkRead(endpoint, length, timeout, data);
	} else {
		return bulkWrite(endpoint, length, timeout, data);
	}

}

int AndroidUsbDevice::bulkRead(uint8_t endpoint, unsigned int length, unsigned int timeout,
                               void *data) {
	if (data == nullptr) {
		LOGE("Cannot bulk read into a null buffer");
		return -1;
	}

	usbdevfs_bulktransfer bulk;
	uint8_t * outBuf = (uint8_t*)data;
	uint32_t count = 0;
	int ret, retry;

	while (length > 0) {
		int xfer = (length > MAX_USBFS_BULK_SIZE) ? MAX_USBFS_BULK_SIZE : length;

		bulk.ep = endpoint;
		bulk.len = (unsigned int)xfer;
		bulk.timeout = timeout;
		bulk.data = outBuf;
		retry = 0;

		do {
			ret = ioctl(_fileDescriptor, USBDEVFS_BULK, &bulk);
			retry++;
			if (retry > MAX_USBFS_BULK_RETRIES) {
				LOGE("Bulk read exceeded maximum number of retries");
				return -1;
			}
		} while (ret < 0);

		count += ret;
		length -= ret;
		outBuf += ret;

		if (ret < xfer) {
			// Fewer bytes received than requested, break and return amount
			// read
			break;
		}
	}

	return count;

}

int AndroidUsbDevice::bulkWrite(uint8_t endpoint, unsigned int length, unsigned int timeout,
                                void *data) {

	usbdevfs_bulktransfer bulk;
	uint8_t* outBuf = (uint8_t*)data;
	uint32_t count = 0;
	int ret;

	if (length == 0) {
		// Zero length packet
		bulk.ep = endpoint;
		bulk.len = length;
		bulk.timeout = timeout;
		bulk.data = data;

		ret = ioctl(_fileDescriptor, USBDEVFS_BULK, &bulk);
		if (ret == 0) {
			return 0;
		} else {
			LOGI("IOCTL Bulk Transfer Error, code %d", ret);
			return -1;
		}
	} else if (data == nullptr) {
		LOGE("Cannot bulk write from a null buffer");
		return -1;
	}


	while (length > 0) {
		int xfer = (length > MAX_USBFS_BULK_SIZE) ? MAX_USBFS_BULK_SIZE : length;

		bulk.ep = endpoint;
		bulk.len = (unsigned int)xfer;
		bulk.timeout = timeout;
		bulk.data = outBuf;

		ret = ioctl(_fileDescriptor, USBDEVFS_BULK, &bulk);

		if (ret != xfer) {
			// Did not write all data
			LOGE("IOCTL Bulk Write did not send all data");
			return -1;
		}

		count += xfer;
		length -= xfer;
		outBuf += xfer;
	}

	return count;
}

/**
 * Allocates, initializes, and submits the requested number of isochronous usb transfers.
 * These transfers are for input.
 *
 * @param numTransfers
 * @param endpoint
 * @param packetLength
 * @param numberOfPackets
 * @param callback
 * @return
 */
bool AndroidUsbDevice::initIsoUrbs(uint8_t numTransfers, uint8_t endpoint,
                                   uint32_t packetLength, uint8_t numberOfPackets,
                                   UrbCallback callback) {
	_urbMutex.lock();
	bool success = true;
	if (_isoUrbsSubmitted > 0) {
		// TODO: Should I just return false?
		discardIsoUrbs();
	}

	// Allocate transfers to memory.  If transfers are already allocated, delete the old ones.
	if (_isoUrbPool.size() > 0) {
		freeIsoUrbs();
	}

	uint32_t urbSize = sizeof(usbdevfs_urb) + (numberOfPackets * sizeof(usbdevfs_iso_packet_desc));
	uint32_t isoBufferSize = packetLength * numberOfPackets;
	LOGD("Iso Urb Size: %d\n Buffer Size: %d", (int) urbSize, (int) isoBufferSize);

	// Allocate, Initialize, and submit Iso Urbs
	for (int i = 0; i < numTransfers; i++) {

		usbdevfs_urb* urb = allocateUrb(urbSize, isoBufferSize, callback);
		urb->type = USBDEVFS_URB_TYPE_ISO;
		urb->endpoint = endpoint;
		urb->flags = USBDEVFS_URB_ISO_ASAP;
		urb->number_of_packets = numberOfPackets;
		((UsbDevice::UrbContext*)urb->usercontext)->poolIndex = (uint8_t )i;

		for (int j = 0; j < numberOfPackets; j++) {
			urb->iso_frame_desc[j].length = packetLength;
		}

		if (!submitUrb(urb)) {
			deleteUrb(urb);
			break;
		}
		_isoUrbsSubmitted++;
		_isoUrbPool.push_back(urb);
	}

	if (_isoUrbsSubmitted != numTransfers) {
		LOGE("Could not submit all URBs, discarding.\nSubmitted: %d",
		     _isoUrbsSubmitted);

		// discard submitted iso transfers
		discardIsoUrbs();
		freeIsoUrbs();
		success = false;
	}

	_urbMutex.unlock();

	return success;
}

/**
 * Initializes and submits an isochronous urb.  The urb should be an input urb.  This
 * function requres that the URB and its buffer have already been allocated.
 *
 * @param urb               The urb to submit
 */
bool AndroidUsbDevice::submitUrb(usbdevfs_urb *urb) {
	if (urb == nullptr) {
		LOGD("Cannot submit null urb");
		return false;
	}


	int ret = ioctl(_fileDescriptor, USBDEVFS_SUBMITURB, urb);
	if (ret < 0) {
		LOGE("Error submitting urb");
		return false;
	}

	return true;

}

/**
 * Creates and Submits a bulk urb for asyncronous I/O
 *
 * @param endpoint      The endpoint for the bulk transfer
 * @param bufferSize    The size of the buffer to write to
 * @param callback      The callback to execute when the Urb has been completed
 * @return              True if the submission was successful, otherwise false
 */
bool AndroidUsbDevice::submitBulkUrb(uint8_t endpoint, uint32_t bufferSize,
                                              UrbCallback callback) {

	bool success = true;

	_urbMutex.lock();
	if (bufferSize <= MAX_USBFS_BULK_SIZE || _scatterGatherEnabled) {
		// Either the transfer fits into the buffer, or the kernel / controller supports
		// scatter/gather buffer transfers.  We can send the data in on request
		usbdevfs_urb* bulkUrb = allocateUrb(sizeof(usbdevfs_urb), bufferSize, callback);

		bulkUrb->type = USBDEVFS_URB_TYPE_BULK;
		bulkUrb->endpoint = endpoint;
		bulkUrb->flags = 0;         // I don't think I need any flags here
		((UsbDevice::UrbContext*)bulkUrb->usercontext)->poolIndex = (uint8_t)_bulkUrbPool.size();

		if (!submitUrb(bulkUrb)) {
			deleteUrb(bulkUrb);
			success = false;
		} else {
			_bulkUrbsSubmitted++;
			_bulkUrbPool.push_back(bulkUrb);
		}
	} else {
		// Create the mainurb that will be sent back to the user.  I can't use the allocateUrb
		// function because the context is different
		uint32_t urbCount = 0;
		size_t urbSize = sizeof(usbdevfs_urb);
		usbdevfs_urb* mainUrb = (usbdevfs_urb*)calloc(1, urbSize);
		mainUrb->buffer = malloc(bufferSize);

		UsbDevice::ContinuousBulkContext* bulkContext = new UsbDevice::ContinuousBulkContext;

		mainUrb->usercontext = bulkContext;
		mainUrb->type = USBDEVFS_URB_TYPE_BULK;
		mainUrb->endpoint = endpoint;
		mainUrb->flags = USBDEVFS_URB_BULK_CONTINUATION;

		// Get the URB Count.  It will be the requested buffersize divided by the maximum bulk
		// size that usbdevfs can transfer.  If there is a remainder, then we need an additional urb
		urbCount = bufferSize / MAX_USBFS_BULK_SIZE;
		uint32_t lastUrbSize = bufferSize % MAX_USBFS_BULK_SIZE;
		if (lastUrbSize > 0) {
			urbCount++;
		} else {
			lastUrbSize = MAX_USBFS_BULK_SIZE;
		}

		// Allocate the array of suburbs
		bulkContext->subUrbCount = (uint8_t)urbCount;
		bulkContext->subUrbs = (usbdevfs_urb**)malloc(urbCount*sizeof(usbdevfs_urb*));


		uint8_t* curBuf;
		for (uint8_t i = 0; i < urbCount; i ++) {
			curBuf = (uint8_t*)mainUrb->buffer + (urbCount * MAX_USBFS_BULK_SIZE);
			usbdevfs_urb* urb = (usbdevfs_urb*) calloc(1, urbSize);
			urb->type = USBDEVFS_URB_TYPE_BULK;
			urb->endpoint = endpoint;
			urb->buffer = curBuf;

			UsbDevice::UrbContext* context =  new UsbDevice::UrbContext;
			context->usbDevice = this;
			context->callback = callback;
			context->contBulkUrb = mainUrb;
			context->poolIndex = i;
			urb->usercontext = context;

			if (i == 0) {
				// first urb request
				urb->flags = USBDEVFS_URB_SHORT_NOT_OK;
				urb->buffer_length = MAX_USBFS_BULK_SIZE;
				context->isLast = false;
			} else if (i == (urbCount - 1)) {
				// last urb request
				urb->flags = USBDEVFS_URB_BULK_CONTINUATION;
				urb->buffer_length = lastUrbSize;
				context->isLast = true;
			} else {
				urb->flags = USBDEVFS_URB_BULK_CONTINUATION | USBDEVFS_URB_SHORT_NOT_OK;
				urb->buffer_length = MAX_USBFS_BULK_SIZE;
				context->isLast = false;
			}

			bulkContext->subUrbs[i] = urb;
		}

		// Submit continuous urbs
		for (uint8_t i = 0; i < bulkContext->subUrbCount; i++) {
			if (!submitUrb(bulkContext->subUrbs[i])) {
				success = false;
			}
		}

		if (!success) {
			LOGD("Error submitting continuous bulk urb");
			// One of the urbs did not submit, discard and delete
			for (uint8_t i = 0;i <= bulkContext->subUrbCount; i++) {
				ioctl(_fileDescriptor, USBDEVFS_DISCARDURB, bulkContext->subUrbs[i]);
			}
			// TODO: It is possible that some of the urbs were successfully submitted.  In that
			// situation deleting here might cause an issue.  If a successfully submitted URB
			// is in the process of being reaped and we delete it then it will result in a
			// segmentation fault.
			//
			// Update - libusb agrees.  They set a flag on their version of a "mainUrb"
			// noting the error,and wait for submitted URBs to be reaped.  Essentially they
			// return success here.
			//
			// After I fix this, I need to do the same for iso transfers.  Currently if one errors
			// out I break the loop, discard and delete.  That could also result in a seg fault.
			deleteContinuousBulkUrb(mainUrb);
		} else {
			// success, add it to the pool
			_bulkUrbsSubmitted++;
			_bulkUrbPool.push_back(mainUrb);
		}

	}

	_urbMutex.unlock();

	return success;
}

bool AndroidUsbDevice::killUrb(usbdevfs_urb *urb) {
	if (urb == nullptr) {
		return false;
	}

	_urbMutex.lock();

	bool success = true;

	if ((urb->flags & USBDEVFS_URB_BULK_CONTINUATION) == 0) {
		// Not Bulk Continuation, resubmit
		int ret = ioctl(_fileDescriptor, USBDEVFS_DISCARDURB, urb);
		if (ret != 0) {
			LOGD("Error discarding urb, code: %d", ret);
			success = false;
		}
	} else {
		int ret;
		UsbDevice::ContinuousBulkContext* context = (UsbDevice::ContinuousBulkContext*)urb->usercontext;
		for (int i = 0;i <= context->subUrbCount; i++) {
			ret = ioctl(_fileDescriptor, USBDEVFS_DISCARDURB, context->subUrbs[i]);
			if (ret != 0) {
				LOGD("Error discarding continuous bulk urb at sub index %d code: %d", i, ret);
				success = false;
			}
		}
	}

	_urbMutex.unlock();
	return success;
}

/**
 * Resubmits a URB previously allocated and submitted
 * @param urb The Urb to resubmit
 * @return true if succesful, false otherwise
 */
bool AndroidUsbDevice::resubmitUrb(usbdevfs_urb *urb) {
	if (urb == nullptr) {
		return false;
	}

	_urbMutex.lock();

	urb->status = 0;
	urb->actual_length = 0;
	urb->error_count = 0;

	if (urb->type == USBDEVFS_URB_TYPE_ISO) {
		for (int i = 0; i < urb->number_of_packets; i++) {
			urb->iso_frame_desc[i].actual_length = 0;
			urb->iso_frame_desc[i].status = 0;
		}
	} else if ((urb->flags & USBDEVFS_URB_BULK_CONTINUATION) > 0) {
		// continuous bulk urb
		bool success = true;
		UsbDevice::ContinuousBulkContext* bulkContext = (UsbDevice::ContinuousBulkContext*)urb
				->usercontext;
		for (uint8_t i = 0; i < bulkContext->subUrbCount; i++) {
			bulkContext->subUrbs[i]->status = 0;
			bulkContext->subUrbs[i]->actual_length = 0;
			bulkContext->subUrbs[i]->error_count = 0;
			if (!submitUrb(bulkContext->subUrbs[i])) {
				success = false;
			}
		}

		_urbMutex.unlock();
		return success;
	}


	_urbMutex.unlock();

	return submitUrb(urb);

}

/**
 * Removes iso transfers from usbdevfs if they are submitted
 * @return true on success, false if ioctl returns an error
 */
bool AndroidUsbDevice::discardIsoUrbs() {
	if (_isoUrbPool.empty()) {
		return true;
	}
	int ret = 0;
	bool success = true;

	for (int i = 0; i < _isoUrbPool.size(); i++) {

		ret = ioctl(_fileDescriptor, USBDEVFS_DISCARDURB, _isoUrbPool[i]);

		if (ret < 0) {
			LOGE("Error discarding iso urb index: %d\nRet Value: %d", i, ret);
			success = false;
		} else {
			LOGD("Successfully iso discarded urb, index: %d", i);
		}
	}

	_isoUrbsSubmitted = 0;

	return success;
}

bool AndroidUsbDevice::discardBulkUrbs() {
	if (_bulkUrbPool.empty()) {
		return true;
	}

	int ret = 0;
	bool success = true;

	for (int i = 0; i < _bulkUrbPool.size(); i++) {
		if ((_bulkUrbPool[i]->flags & USBDEVFS_URB_BULK_CONTINUATION) == 0) {
			// regular urb
			ret = ioctl(_fileDescriptor, USBDEVFS_DISCARDURB, _bulkUrbPool[i]);

			if (ret < 0) {
				LOGE("Error discarding bulk urb index: %d\nRet Value: %d", i, ret);
				success = false;
			} else {
				LOGD("Successfully discarded bulk urb, index: %d", i);
			}
		} else {
			UsbDevice::ContinuousBulkContext* context =
					(UsbDevice::ContinuousBulkContext*)_bulkUrbPool[i]->usercontext;
			for (int j = 0; j < context->subUrbCount; j++) {
				ret = ioctl(_fileDescriptor, USBDEVFS_DISCARDURB, context->subUrbs[j]);
				if (ret < 0) {
					LOGE("Error discarding continuous bulk urb, index: %d, sub index: %d"
							     "\nRet Value: %d", i, j, ret);
					success = false;
				} else {
					LOGD("Successfully discarded continuous bulk urb, index: %d, sub index: %d",
					     i, j);
				}
			}
		}
	}

	_bulkUrbsSubmitted = 0;
	return success;
}

bool AndroidUsbDevice::clearHalt(uint8_t endpoint) {
	int ret;

	ret = ioctl(_fileDescriptor, USBDEVFS_CLEAR_HALT, endpoint);

	if (ret == 0) {
		return true;
	} else {
		LOGD("Unable to clear halt on Endpoint %d, return code: %d", endpoint, ret);
		return false;
	}
}

/**
 * Allocates a URB and its associated buffers.  The buffer, context, and buffer_length members are
 * initialized, all other members are zeroed.
 * @param urbSize
 * @param bufferSize
 * @param callback
 * @return
 */
usbdevfs_urb* AndroidUsbDevice::allocateUrb(uint32_t urbSize, uint32_t bufferSize,
                                            UrbCallback callback) {
	usbdevfs_urb* urb = (usbdevfs_urb *) calloc(1, urbSize);
	if (urb == nullptr) {
		return nullptr;
	}

	urb->buffer = malloc(bufferSize);
	if (urb->buffer == nullptr) {
		free(urb);
		return nullptr;
	}
	urb->buffer_length = bufferSize;

	// Interestingly, Using malloc to generate the memory for this structure causes a Segmentation Fault
	// when attempting to assign the callback.  My assumption is that sizeof doesn't work
	// correctly on a struct containing std::function
	UsbDevice::UrbContext* ctx = new UsbDevice::UrbContext;
	ctx->usbDevice = this;
	ctx->callback = callback;
	urb->usercontext = ctx;
	ctx->contBulkUrb = nullptr;

	return urb;
}

void AndroidUsbDevice::deleteUrb(usbdevfs_urb *urb) {
	if (urb != nullptr) {
		delete (UsbDevice::UrbContext*) urb->usercontext;
		free(urb->buffer);
		free(urb);
	}
}

void AndroidUsbDevice::deleteContinuousBulkUrb(usbdevfs_urb *continousUrb) {

	UsbDevice::ContinuousBulkContext* context = (UsbDevice::ContinuousBulkContext*)continousUrb
			->usercontext;

	// Delete the sub urb list and their associated contexts
	for (int i = 0; i < context->subUrbCount; i++) {
		delete (UsbDevice::UrbContext*)(context->subUrbs[i]->usercontext);
		free(context->subUrbs[i]);
	}
	free(context->subUrbs);

	// delete the buffer and continuous urb
	free(continousUrb->buffer);
	free(continousUrb);

	// delete the main urb context;
	delete context;
}

void AndroidUsbDevice::freeIsoUrbs() {
	if (_isoUrbsSubmitted > 0) {
		discardIsoUrbs();
	}

	for (int i = 0; i < _isoUrbPool.size(); i++) {
		deleteUrb(_isoUrbPool[i]);
		_isoUrbPool[i] = nullptr;
	}
	_isoUrbPool.clear();

}

void AndroidUsbDevice::freeBulkUrbs() {

	if (_bulkUrbsSubmitted > 0) {
		discardBulkUrbs();
	}

	for (int i = 0; i > _bulkUrbPool.size(); i++) {
		if ((_bulkUrbPool[i]->flags & USBDEVFS_URB_BULK_CONTINUATION) == 0) {
			// not a continuous bulk urb, okay to delete
			deleteUrb(_bulkUrbPool[i]);
			_bulkUrbPool[i] = nullptr;
		} else {
			// This is a continuous bulk URB.  The urb itself is never submitted.  Its context
			// differs from others, it contains an array to sub-urbs that ARE submitted
			deleteContinuousBulkUrb(_bulkUrbPool[i]);
			_bulkUrbPool[i] = nullptr;
		}
	}
	_bulkUrbPool.clear();
}

/**
 * Starts async iso read thread
 * @param cb The Callback to execute when an iso request is received
 * @return
 */
bool AndroidUsbDevice::startUrbAsyncRead() {
	bool success = true;
	_urbMutex.lock();
	if (_urbThread == nullptr) {
		_urbThreadRunning = true;
		_urbThread = new std::thread(&AndroidUsbDevice::reapUrbAsync, this);
		if ( _urbThread == nullptr) {
			_urbThreadRunning = false;
			LOGE("Error starting isochronous transfer thread");
			success = false;
		}
	} else {
		success = false;
	}
	_urbMutex.unlock();

	return success;
}

/**
 * Stops async iso read thread.
 */
void AndroidUsbDevice::stopUrbAsyncRead() {
	_urbMutex.lock();
	if (_urbThread != nullptr) {
		_urbThreadRunning = false;
		// TODO: I don't necessarily need to join here.  If the IOCTL is stuck, it will return
		// with an error code after the file descriptor is closed.  The problem is if I
		// start and stop without closing the device
		_urbThread->join();
		delete _urbThread;
		_urbThread = nullptr;
		discardIsoUrbs();

	}
	_urbMutex.unlock();
}

/**
 * Synchronous iso read.  The user can choose to return immediately, or block until
 * a request is received.  The request is checked against the user context to be sure
 * that it is one submitted by this instance.
 *
 * @param wait  If true this function will block, otherwise it will return immediately
 * @return A pointer to a urb if one is received, or NULL
 */
usbdevfs_urb* AndroidUsbDevice::isoReadSync(bool wait) {
	int req = wait ? USBDEVFS_REAPURB : USBDEVFS_REAPURBNDELAY;

	usbdevfs_urb* urb = nullptr;
	int ret = ioctl(_fileDescriptor, req, &urb);
	if (ret < 0) {
		return nullptr;
	} else if (urb->usercontext == this) {
		return urb;
	} else {
		return nullptr;
	}
}

/**
 * Function bound to the urbThread;
 */
void AndroidUsbDevice::reapUrbAsync() {
	int ret;

	LOGD("Iso thread start.  Thread Running: %s",
	     _urbThreadRunning ? "true" : "false");

#if defined(PROFILE_VIDEO_URB)
	long urbMinTime = 1000000;
	long urbMaxTime = 0;
	uint32_t urbCount = 0;
#endif

	while (_urbThreadRunning) {

		usbdevfs_urb *urb = nullptr;
		ret = ioctl(_fileDescriptor, USBDEVFS_REAPURB, &urb);


		switch (ret) {
			case 0: {
#if defined(PROFILE_VIDEO_URB)
				auto startTime = std::chrono::steady_clock::now();
#endif

				UsbDevice::UrbContext* context = (UsbDevice::UrbContext*)urb->usercontext;
				// Execute the callback
				if (context->contBulkUrb == nullptr) {
					context->callback(urb);
				} else {
					// TODO: If I get a non-zero status here, is it returned to the IOCTL?
					// If not, do I need to handle it here?  Will usbdevfs kill the entire
					// series so I don't get the last urb?  Check to see how libusb handles it

					// Continuous Bulk Urb
					if (context->isLast || urb->status != 0) {
						context->contBulkUrb->actual_length += urb->actual_length;
						context->contBulkUrb->status = urb->status;
						context->contBulkUrb->error_count = urb->error_count;
						context->callback(context->contBulkUrb);
					} else {
						context->contBulkUrb->actual_length += urb->actual_length;
					}

				}

#if defined(PROFILE_VIDEO_URB)
				auto processTime = std::chrono::duration_cast<std::chrono::microseconds>(
						std::chrono::steady_clock::now() - startTime);
				urbMinTime = processTime.count() < urbMinTime ? processTime.count() : urbMinTime;
				urbMaxTime = processTime.count() > urbMaxTime ? processTime.count() : urbMinTime;
				urbCount++;
				if (urbCount > 1800) {
					LOGD("Last 1800 packets, Max Urb Process Time: %ld us\nMin Urb Process Time: %ld us",
					     urbMaxTime, urbMinTime);
					urbMaxTime = 0;
					urbMinTime = 1000000;
					urbCount = 0;
				}
			}
#endif
				break;
			case -ENODEV:
			case -ENOENT:
			case -ECONNRESET:
			case -ESHUTDOWN:
				// TODO: I don't think these errors are recoverable.  I need to signal
				// an Exit to the Parent Driver so it can clean up
				_urbThreadRunning = false;
				return;
			case -EPIPE:  // Recoverable, resubmit
				// If Type bulk, clear halt
				if (urb->type == USBDEVFS_URB_TYPE_BULK) {
					clearHalt(urb->endpoint);
				}
			case -EAGAIN:   // Recoverable, resubmit
			default:        // Recoverable, resubmit

				// resubmit if this is one of our urbs
				if (urb != nullptr) {
					UsbDevice::UrbContext* context = (UsbDevice::UrbContext *) urb->usercontext;
					if (context->contBulkUrb == nullptr) {
						// standard urb
						resubmitUrb(urb);
					} else {
						// continuous bulk urb, kill the entire series then resubmit
						usbdevfs_urb* mainurb = context->contBulkUrb;
						killUrb(mainurb);
						resubmitUrb(mainurb);
					}
				}
				break;
		}
	}


}



