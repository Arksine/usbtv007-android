//
// Created by Eric on 10/9/2017.
//

#ifndef USBTV007_ANDROID_ANDROIDUSBDEVICE_H
#define USBTV007_ANDROID_ANDROIDUSBDEVICE_H

#include <sys/ioctl.h>
#include <cstdint>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>
#include <thread>
#include <mutex>
#include <functional>


#define MAX_USBFS_BULK_RETRIES 5
// TODO: See if I can find the max bulk size in one of the headers
#define MAX_USBFS_BULK_SIZE 16384

typedef std::function<void(usbdevfs_urb*)> UrbCallback;

class AndroidUsbDevice;

namespace UsbDevice {
	struct UrbContext {
		AndroidUsbDevice*       usbDevice;
		UrbCallback             callback;
	};
}


// TODO: I should probably add a member to keep device status.  If I recieve -ESHUTDOWN or
// another unrecoverable event I shouldn't perform any type of USB transaction
class AndroidUsbDevice {
private:

	int     _fileDescriptor;
	uint8_t _isoTransfersAllocated;
	uint8_t _isoTransfersSubmitted;

	// iso urb vars
	uint8_t     _isoEndpoint;
	uint32_t    _maxIsoPacketLength;
	uint8_t     _numIsoPackets;
	bool        _urbThreadRunning;

	UsbDevice::UrbContext       _isoUrbCtx;
	UsbDevice::UrbContext       _bulkUrbCtx;
	std::thread*                _urbThread;
	std::mutex                  _urbMutex;
	usbdevfs_urb**              _isoUrbPool;



	void reapUrbAsync();
	int bulkRead(uint8_t endpoint, unsigned int length,
	             unsigned int timeout, void* data);
	int bulkWrite(uint8_t endpoint, unsigned int length,
	             unsigned int timeout, void* data);


	void allocateIsoTransfers(uint32_t packetLength, uint8_t numberOfPackets, uint8_t numTransfers);
	void freeIsoTransfers();

public:


	AndroidUsbDevice(int fd);
	~AndroidUsbDevice();

	int getFileDescriptor() {
		return _fileDescriptor;
	}

	bool isIsoThreadRunning() {
		return _urbThreadRunning;
	}

	bool setInterface(unsigned int interface, unsigned int altSetting);
	bool controlTransfer(uint8_t requestType, uint8_t request, uint16_t value,
	                     uint16_t index, void* buffer, uint16_t length,
	                     uint32_t timeout);

	int bulkTransfer(uint8_t endpoint, unsigned int length,
	                 unsigned int timeout, void* data);

	bool initIsoTransfers(uint8_t numTransfers, uint8_t endpoint, uint32_t packetLength,
	                      uint8_t numberOfPackets, UrbCallback callback);

	bool submitIsoUrb(usbdevfs_urb *urb, uint8_t endpoint, uint32_t packetLength,
	                  uint8_t numberOfPackets);

	bool resubmitIsoUrb(usbdevfs_urb *urb);

	bool discardIsoTransfers();
	bool startUrbAsyncRead();
	void stopUrbAsyncRead();
	usbdevfs_urb* isoReadSync(bool wait);
};


#endif //USBTV007_ANDROID_ANDROIDUSBDEVICE_H
