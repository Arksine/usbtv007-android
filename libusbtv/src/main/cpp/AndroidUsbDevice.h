//
// Created by Eric on 10/9/2017.
//

#ifndef USBTV007_ANDROID_ANDROIDUSBDEVICE_H
#define USBTV007_ANDROID_ANDROIDUSBDEVICE_H

#include <sys/ioctl.h>
#include <cstdint>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>
#include <vector>
#include <thread>
#include <mutex>
#include <functional>


#define MAX_USBFS_BULK_RETRIES 5
#define MAX_USBFS_BULK_SIZE 16384

typedef std::function<void(usbdevfs_urb*)> UrbCallback;

class AndroidUsbDevice;

// TODO: I can add some data to the UrbContext for transfers that are bulk-continuation, then
// send the last transfer
namespace UsbDevice {
	// Context used for the primary continuous bulk transfer.  It contains indices for
	// the subtransfers used to actually fetch the data

	// TODO: Should I add poolIndex to the below as well?
	struct ContinuousBulkContext {
		uint8_t                 subUrbCount;
		usbdevfs_urb**          subUrbs;
	};

	struct UrbContext {
		AndroidUsbDevice*       usbDevice;
		UrbCallback             callback;
		uint8_t                 poolIndex;
		usbdevfs_urb*           contBulkUrb = nullptr;      // used only for continuous URBs
		bool                    isLast      = true;        // used only for continuous URBs
	};
}


// TODO: I should probably add a member to keep device status.  If I recieve -ESHUTDOWN or
// another unrecoverable event I shouldn't perform any type of USB transaction
class AndroidUsbDevice {
private:

	int     _fileDescriptor;
	bool    _scatterGatherEnabled;

	uint8_t                     _isoUrbsSubmitted;
	uint8_t                     _bulkUrbsSubmitted;
	bool                        _urbThreadRunning;
	std::thread*                _urbThread;
	std::mutex                  _urbMutex;
	std::vector<usbdevfs_urb*>  _isoUrbPool;
	std::vector<usbdevfs_urb*>  _bulkUrbPool;


	void checkCapabilities();
	void reapUrbAsync();
	bool clearHalt(uint8_t endpoint);
	int bulkRead(uint8_t endpoint, unsigned int length,
	             unsigned int timeout, void* data);
	int bulkWrite(uint8_t endpoint, unsigned int length,
	             unsigned int timeout, void* data);
	bool submitUrb(usbdevfs_urb *urb);

	usbdevfs_urb* allocateUrb(uint32_t urbSize, uint32_t bufferSize, UrbCallback callback);

	void deleteUrb(usbdevfs_urb* urb);
	void deleteContinuousBulkUrb(usbdevfs_urb* continousUrb);

	void freeIsoUrbs();
	void freeBulkUrbs();

public:


	AndroidUsbDevice(int fd);
	~AndroidUsbDevice();

	int getFileDescriptor() {
		return _fileDescriptor;
	}

	bool isUrbThreadRunning() {
		return _urbThreadRunning;
	}

	bool setInterface(unsigned int interface, unsigned int altSetting);
	bool controlTransfer(uint8_t requestType, uint8_t request, uint16_t value,
	                     uint16_t index, void* buffer, uint16_t length,
	                     uint32_t timeout);

	int bulkTransfer(uint8_t endpoint, unsigned int length,
	                 unsigned int timeout, void* data);

	bool initIsoUrbs(uint8_t numTransfers, uint8_t endpoint, uint32_t packetLength,
	                 uint8_t numberOfPackets, UrbCallback callback);


	bool submitBulkUrb(uint8_t endpoint, uint32_t bufferSize, UrbCallback callback);
	bool killUrb(usbdevfs_urb *urb);

	bool resubmitUrb(usbdevfs_urb *urb);

	bool discardIsoUrbs();
	bool discardBulkUrbs();
	bool startUrbAsyncRead();
	void stopUrbAsyncRead();
	usbdevfs_urb* isoReadSync(bool wait);
};


#endif //USBTV007_ANDROID_ANDROIDUSBDEVICE_H
