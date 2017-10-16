//
// Created by Eric on 10/9/2017
//

#include "AndroidUsbDevice.h"
#include "util.h"
#include <cstdlib>

// TODO: If a bulk transfer returns -EPIPE, it is stalled.  Need to ioctl send clear halt

void *iso_read_thread(void* context);

AndroidUsbDevice::AndroidUsbDevice(int fd, IsonchronousCallback callback) {
	fileDescriptor = fd;
	isoMutex = PTHREAD_MUTEX_INITIALIZER;
	isoEndConditon = PTHREAD_COND_INITIALIZER;
	isoTransfersAllocated = 0;
	isoTransfersSubmitted = 0;
	isoEndpoint = 0;
	maxIsoPacketLength = 0;
	numIsoPackets = 0;
	isoThreadRunning = false;
	isoThreadCtx = new UsbDevice::ThreadContext;
	isoThreadCtx->parent = this;
	isoThreadCtx->isoThreadRunning = &isoThreadRunning;
	isoThreadCtx->isoMutex = &isoMutex;
	isoThreadCtx->isoEndCondition = &isoEndConditon;
	isoThreadCtx->callback = callback;
}

AndroidUsbDevice::~AndroidUsbDevice() {
	if (isoThreadRunning) {
		stopIsoAsyncRead();
	}
	delete isoThreadCtx;
	discardIsoTransfers();
	freeIsoTransfers();
	pthread_mutex_destroy(&isoMutex);
	pthread_cond_destroy(&isoEndConditon);
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

	int ret = ioctl(fileDescriptor, USBDEVFS_SETINTERFACE, &intf);

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

	int ret = ioctl(fileDescriptor, USBDEVFS_CONTROL, &ctrltransfer);

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
	if (data == NULL) {
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
		bulk.len = xfer;
		bulk.timeout = timeout;
		bulk.data = outBuf;
		retry = 0;

		do {
			ret = ioctl(fileDescriptor, USBDEVFS_BULK, &bulk);
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

		ret = ioctl(fileDescriptor, USBDEVFS_BULK, &bulk);
		if (ret == 0) {
			return 0;
		} else {
			LOGI("IOCTL Bulk Transfer Error, code %d", ret);
			return -1;
		}
	} else if (data == NULL) {
		LOGE("Cannot bulk write from a null buffer");
		return -1;
	}


	while (length > 0) {
		int xfer = (length > MAX_USBFS_BULK_SIZE) ? MAX_USBFS_BULK_SIZE : length;

		bulk.ep = endpoint;
		bulk.len = xfer;
		bulk.timeout = timeout;
		bulk.data = outBuf;

		ret = ioctl(fileDescriptor, USBDEVFS_BULK, &bulk);

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
 * Allocates, initializes, and submits the requested number of isonchronous usb transfers.
 * These transfers are for input.
 *
 * @param numTransfers
 * @param endpoint
 * @param packetLength
 * @param numberOfPackets
 * @return
 */
bool AndroidUsbDevice::initIsoTransfers(uint8_t numTransfers, uint8_t endpoint,
                                        uint32_t packetLength, uint8_t numberOfPackets) {

	pthread_mutex_lock(&isoMutex);
	bool success = true;
	if (isoTransfersSubmitted > 0) {
		// TODO: Should I just return false?
		discardIsoTransfers();
	}

	// Allocate transfers to memory.  If transfers are already allocated, delete the old ones.
	if (isoTransfersAllocated > 0) {
		freeIsoTransfers();
	}

	allocateIsoTransfers(packetLength, numberOfPackets, numTransfers);

	for (int i = 0; i < numTransfers; i++) {

		if (!submitIsoUrb(isonchronousUrbs[i], endpoint, packetLength, numberOfPackets)) {
			break;
		}

		isoTransfersSubmitted++;
	}

	if (isoTransfersSubmitted != numTransfers) {
		LOGE("Could not submit all URBs, discarding.\nSubmitted: %d",
		     isoTransfersSubmitted);

		// discard submitted iso transfers
		discardIsoTransfers();
		success = false;
	} else {
		// save the variables for resubmission
		isoEndpoint = endpoint;
		maxIsoPacketLength = packetLength;
		numIsoPackets = numberOfPackets;
	}

	pthread_mutex_unlock(&isoMutex);

	return success;
}

/**
 * Initializes and submits an isonchronous urb.  The urb should be an input urb.  This
 * function requres that the URB and its buffer have already been allocated.
 *
 * @param urb               The urb to initialize and submit
 * @param endpoint          The usb endpoint for input iso transfers
 * @param packetLength      The maximum input packet size
 * @param numberOfPackets   The number of packets allowed for each iso transfer
 * @return True if successful, false if ioctl returns an error
 */
bool AndroidUsbDevice::submitIsoUrb(usbdevfs_urb *urb, uint8_t endpoint, uint32_t packetLength,
                                    uint8_t numberOfPackets) {
	if (urb == nullptr) {
		LOGD("Cannot submit null urb");
		return false;
	}

	urb->type = USBDEVFS_URB_TYPE_ISO;
	urb->endpoint = endpoint;
	urb->status = 0;
	urb->flags = USBDEVFS_URB_ISO_ASAP;
	urb->buffer_length = packetLength * numberOfPackets;
	urb->actual_length = 0;
	urb->start_frame = 0;
	urb->number_of_packets = numberOfPackets;
	urb->error_count = 0;
	urb->signr = 0;
	urb->usercontext = this;

	for (int i = 0; i < numberOfPackets; i++) {
		urb->iso_frame_desc[i].length = packetLength;
		urb->iso_frame_desc[i].actual_length = 0;
		urb->iso_frame_desc[i].status = 0;
	}

	int ret = ioctl(fileDescriptor, USBDEVFS_SUBMITURB, urb);
	if (ret < 0) {
		LOGE("Error submitting urb");
		return false;
	}

	return true;

}

/**
 * Resubmits a URB previously initialized by a call initIsoTransfers
 * @param urb The Urb to resubmit
 * @return true if succesful, false otherwise
 */
bool AndroidUsbDevice::resubmitIsoUrb(usbdevfs_urb *urb) {
	if (isoTransfersSubmitted > 0) {
		return submitIsoUrb(urb, isoEndpoint, maxIsoPacketLength, numIsoPackets);
	} else {
		return false;
	}
}

/**
 * Removes iso transfers from usbdevfs if they are submitted
 * @return true on success, false if ioctl returns an error
 */
bool AndroidUsbDevice::discardIsoTransfers() {
	if (isoTransfersSubmitted == 0) {
		return true;
	}
	int ret;
	bool success = true;

	for (int i = 0; i < isoTransfersSubmitted; i++) {
		ret = ioctl(fileDescriptor, USBDEVFS_DISCARDURB, isonchronousUrbs[i]);

		if (ret < 0) {
			LOGE("Error discarding urb index: %d", i);
			success = false;
		} else {
			LOGD("Successfully discarded urb, index: %d", i);
		}
	}

	isoTransfersSubmitted = 0;

	return success;
}

void AndroidUsbDevice::freeIsoTransfers() {
	if (isoTransfersSubmitted > 0) {
		discardIsoTransfers();
	}

	for (int i = 0; i < isoTransfersAllocated; i++) {
		if (isonchronousUrbs[i] != nullptr) {
			free(isonchronousUrbs[i]->buffer);
			free(isonchronousUrbs[i]);
			isonchronousUrbs[i] = nullptr;
		}
	}

	isoTransfersAllocated = 0;
}

void AndroidUsbDevice::allocateIsoTransfers(uint32_t packetLength, uint8_t numberOfPackets,
                                            uint8_t numTransfers) {
	size_t urbSize = sizeof(usbdevfs_urb) + (numberOfPackets * sizeof(usbdevfs_iso_packet_desc));
	size_t isoBufferSize = packetLength * numberOfPackets;
	LOGD("Urb Size: %d\n Buffer Size: %d", (int)urbSize, (int)isoBufferSize);
	for (int i = 0; i < numTransfers; i++) {
		isonchronousUrbs[i] = (usbdevfs_urb*)malloc(urbSize);
		isonchronousUrbs[i]->buffer = malloc(size_t(isoBufferSize));
	}
	isoTransfersAllocated = numTransfers;
}

/**
 * Starts async iso read thread
 * @param cb The Callback to execute when an iso request is received
 * @return
 */
bool AndroidUsbDevice::startIsoAsyncRead() {
	int ret;
	bool success = true;
	pthread_mutex_lock(&isoMutex);
	if (!isoThreadRunning) {
		isoThreadRunning = true;
		ret = pthread_create(&isoThread, NULL, iso_read_thread, (void *)isoThreadCtx);
		if (ret != 0) {
			success = false;
			isoThreadRunning = false;
		}
	} else {
		success = false;
	}
	pthread_mutex_unlock(&isoMutex);

	return success;
}

/**
 * Stops async iso read thread.
 */
void AndroidUsbDevice::stopIsoAsyncRead() {
	pthread_mutex_lock(&isoMutex);
	if (isoThreadRunning) {
		isoThreadRunning = false;
		pthread_join(isoThread, nullptr);
		discardIsoTransfers();

	}
	pthread_mutex_unlock(&isoMutex);
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

	usbdevfs_urb* urb = NULL;
	int ret = ioctl(fileDescriptor, req, &urb);
	if (ret < 0) {
		return NULL;
	} else if (urb->usercontext == this) {
		return urb;
	} else {
		return NULL;
	}
}

/**
 * Function for iso read thread
 * @param context Contains contextual data shared with the caller
 * @return
 */
void *iso_read_thread(void* context) {
	UsbDevice::ThreadContext* ctx = (UsbDevice::ThreadContext*) context;
	int fd = ctx->parent->getFileDescriptor();
	int ret;

	LOGD("Iso thread start.  Thread Running: %s",
	*(ctx->isoThreadRunning) ? "true" : "false");

	int errorCount = 0;
	while(*(ctx->isoThreadRunning)) {
		usbdevfs_urb* urb = nullptr;
		ret = ioctl(fd, USBDEVFS_REAPURB, &urb);


		if (ret == 0) {
			if (urb->usercontext == ctx->parent) {
				// Got a valid urb that was submitted from this context
				ctx->callback(urb);
			}
		} else {
			errorCount++;
			if (errorCount > 1800) {
				LOGD("Entire frame of URBs errored");
				errorCount = 0;
			}

			// TODO: handle disconnections.  Some of the errors returned are not recoverable
			/*if (ret == -EAGAIN) {
				// EAGAIN is recoverable
				continue;
			} else {
				*(ctx->isoThreadRunning) = false;
				break;
			}*/
		}

		if (urb != nullptr && urb->usercontext == ctx->parent) {
			ctx->parent->resubmitIsoUrb(urb);
		}

	}

	return 0;
}

