//
// Created by Eric on 10/12/2017.
//

#ifndef USBTV007_ANDROID_USBTV_DEFINITIONS_H
#define USBTV007_ANDROID_USBTV_DEFINITIONS_H

#include <jni.h>
#include <atomic>
#include <asm/byteorder.h>

//#define DEBUG_PACKET
#define PROFILE_VIDEO_URB
#define PROFILE_FRAME


#define USBTV_BASE		    0xc000
#define USBTV_VIDEO_EP	    0x81
#define USBTV_AUDIO_EP  	0x83
#define USBTV_CONTROL_REG	11
#define USBTV_REQUEST_REG	12


#define USBTV_ISOC_TRANSFERS	            16
#define USBTV_ISOC_PACKETS_PER_REQUEST	    8

// Isonchronous Packet Sizes, in bytes
#define USBTV_PACKET_SIZE	    1024
#define USBTV_PAYLOAD_SIZE      960

// size of the array containing input frame buffers.  TODO: I should probably make this a dynamic size
#define USBTV_FRAME_POOL_SIZE 4

#define USBTV_AUDIO_URBSIZE	20480
#define USBTV_AUDIO_HDRSIZE	4
#define USBTV_AUDIO_BUFFER	65536

#define USBTV_FRAME_OK(packet)	((__be32_to_cpu(packet[0]) & 0xff000000) == 0x88000000)
#define USBTV_FRAME_ID(packet)	((__be32_to_cpu(packet[0]) & 0x00ff0000) >> 16)
#define USBTV_ODD(packet)	(((__be32_to_cpu(packet[0]) & 0x0000f000) >> 15) != 0)
#define USBTV_PACKET_NO(packet)	(__be32_to_cpu(packet[0]) & 0x00000fff)
#define ARRAY_SIZE(array) (sizeof((array))/sizeof((array[0])))

// UsbTvFrame Flag defs
#define FRAME_START         1
#define FRAME_IN_PROGRESS   (1 << 1)
#define FRAME_COMPLETE      (1 << 2)
#define FRAME_PARTIAL       (1 << 3)

enum struct TvInput {
	USBTV_COMPOSITE_INPUT,
	USBTV_SVIDEO_INPUT,
};

enum struct TvNorm {
	NTSC,
	PAL,
};

enum struct ScanType {
	PROGRESSIVE,
	DISCARD,
	INTERLEAVED
};

enum struct ColorControl {
	BRIGHTNESS,
	CONTRAST,
	SATURATION,
	HUE,
	SHARPNESS
};

struct FrameParams {
	uint16_t    frameWidth;
	uint16_t    frameHeight;
	TvNorm      norm;
	ScanType    scanType;
	uint32_t    bufferSize;
};

// TODO: add colorspace and scantype so that receiving functions know how to process it. Also
// add TvNorm and a Flag for Frame Status (complete, incomplete, other possible statuses)

// TODO: I need another atomic flag, or atomic counter?
struct UsbTvFrame {
	void*           buffer;
	FrameParams*    params;
	uint32_t        frameId;
	uint32_t        flags;
	jobject         javaFrame;     // This is a reference to Java Class implementation of this frame.

	std::atomic_flag lock = ATOMIC_FLAG_INIT;
};

/*Control Register Definitions*/
static const uint16_t COMPOSITE_INPUT[][2] = {
		{ USBTV_BASE + 0x0105, 0x0060 },
		{ USBTV_BASE + 0x011f, 0x00f2 },
		{ USBTV_BASE + 0x0127, 0x0060 },
		{ USBTV_BASE + 0x00ae, 0x0010 },
		{ USBTV_BASE + 0x0239, 0x0060 },
};

static const uint16_t SVIDEO_INPUT[][2] = {
		{ USBTV_BASE + 0x0105, 0x0010 },
		{ USBTV_BASE + 0x011f, 0x00ff },
		{ USBTV_BASE + 0x0127, 0x0060 },
		{ USBTV_BASE + 0x00ae, 0x0030 },
		{ USBTV_BASE + 0x0239, 0x0060 },
};

static const uint16_t PAL_TV_NORM[][2] = {
		{ USBTV_BASE + 0x001a, 0x0068 },
		{ USBTV_BASE + 0x010e, 0x0072 },
		{ USBTV_BASE + 0x010f, 0x00a2 },
		{ USBTV_BASE + 0x0112, 0x00b0 },
		{ USBTV_BASE + 0x0117, 0x0001 },
		{ USBTV_BASE + 0x0118, 0x002c },
		{ USBTV_BASE + 0x012d, 0x0010 },
		{ USBTV_BASE + 0x012f, 0x0020 },
		{ USBTV_BASE + 0x024f, 0x0002 },
		{ USBTV_BASE + 0x0254, 0x0059 },
		{ USBTV_BASE + 0x025a, 0x0016 },
		{ USBTV_BASE + 0x025b, 0x0035 },
		{ USBTV_BASE + 0x0263, 0x0017 },
		{ USBTV_BASE + 0x0266, 0x0016 },
		{ USBTV_BASE + 0x0267, 0x0036 }
};

static const uint16_t NTSC_TV_NORM[][2] = {
		{ USBTV_BASE + 0x001a, 0x0079 },
		{ USBTV_BASE + 0x010e, 0x0068 },
		{ USBTV_BASE + 0x010f, 0x009c },
		{ USBTV_BASE + 0x0112, 0x00f0 },
		{ USBTV_BASE + 0x0117, 0x0000 },
		{ USBTV_BASE + 0x0118, 0x00fc },
		{ USBTV_BASE + 0x012d, 0x0004 },
		{ USBTV_BASE + 0x012f, 0x0008 },
		{ USBTV_BASE + 0x024f, 0x0001 },
		{ USBTV_BASE + 0x0254, 0x005f },
		{ USBTV_BASE + 0x025a, 0x0012 },
		{ USBTV_BASE + 0x025b, 0x0001 },
		{ USBTV_BASE + 0x0263, 0x001c },
		{ USBTV_BASE + 0x0266, 0x0011 },
		{ USBTV_BASE + 0x0267, 0x0005 }
};

static const uint16_t VIDEO_INIT[][2] = {
		/* These seem to enable the device. */
		{ USBTV_BASE + 0x0008, 0x0001 },
		{ USBTV_BASE + 0x01d0, 0x00ff },
		{ USBTV_BASE + 0x01d9, 0x0002 },

		/* These seem to influence color parameters, such as
		 * brightness, etc. */
		{ USBTV_BASE + 0x0239, 0x0040 },
		{ USBTV_BASE + 0x0240, 0x0000 },
		{ USBTV_BASE + 0x0241, 0x0000 },
		{ USBTV_BASE + 0x0242, 0x0002 },
		{ USBTV_BASE + 0x0243, 0x0080 },
		{ USBTV_BASE + 0x0244, 0x0012 },
		{ USBTV_BASE + 0x0245, 0x0090 },
		{ USBTV_BASE + 0x0246, 0x0000 },

		{ USBTV_BASE + 0x0278, 0x002d },
		{ USBTV_BASE + 0x0279, 0x000a },
		{ USBTV_BASE + 0x027a, 0x0032 },
		{ 0xf890, 0x000c },
		{ 0xf894, 0x0086 },

		{ USBTV_BASE + 0x00ac, 0x00c0 },
		{ USBTV_BASE + 0x00ad, 0x0000 },
		{ USBTV_BASE + 0x00a2, 0x0012 },
		{ USBTV_BASE + 0x00a3, 0x00e0 },
		{ USBTV_BASE + 0x00a4, 0x0028 },
		{ USBTV_BASE + 0x00a5, 0x0082 },
		{ USBTV_BASE + 0x00a7, 0x0080 },
		{ USBTV_BASE + 0x0000, 0x0014 },
		{ USBTV_BASE + 0x0006, 0x0003 },
		{ USBTV_BASE + 0x0090, 0x0099 },
		{ USBTV_BASE + 0x0091, 0x0090 },
		{ USBTV_BASE + 0x0094, 0x0068 },
		{ USBTV_BASE + 0x0095, 0x0070 },
		{ USBTV_BASE + 0x009c, 0x0030 },
		{ USBTV_BASE + 0x009d, 0x00c0 },
		{ USBTV_BASE + 0x009e, 0x00e0 },
		{ USBTV_BASE + 0x0019, 0x0006 },
		{ USBTV_BASE + 0x008c, 0x00ba },
		{ USBTV_BASE + 0x0101, 0x00ff },
		{ USBTV_BASE + 0x010c, 0x00b3 },
		{ USBTV_BASE + 0x01b2, 0x0080 },
		{ USBTV_BASE + 0x01b4, 0x00a0 },
		{ USBTV_BASE + 0x014c, 0x00ff },
		{ USBTV_BASE + 0x014d, 0x00ca },
		{ USBTV_BASE + 0x0113, 0x0053 },
		{ USBTV_BASE + 0x0119, 0x008a },
		{ USBTV_BASE + 0x013c, 0x0003 },
		{ USBTV_BASE + 0x0150, 0x009c },
		{ USBTV_BASE + 0x0151, 0x0071 },
		{ USBTV_BASE + 0x0152, 0x00c6 },
		{ USBTV_BASE + 0x0153, 0x0084 },
		{ USBTV_BASE + 0x0154, 0x00bc },
		{ USBTV_BASE + 0x0155, 0x00a0 },
		{ USBTV_BASE + 0x0156, 0x00a0 },
		{ USBTV_BASE + 0x0157, 0x009c },
		{ USBTV_BASE + 0x0158, 0x001f },
		{ USBTV_BASE + 0x0159, 0x0006 },
		{ USBTV_BASE + 0x015d, 0x0000 },

		{ USBTV_BASE + 0x0003, 0x0004 },
		{ USBTV_BASE + 0x0100, 0x00d3 },
		{ USBTV_BASE + 0x0115, 0x0015 },
		{ USBTV_BASE + 0x0220, 0x002e },
		{ USBTV_BASE + 0x0225, 0x0008 },
		{ USBTV_BASE + 0x024e, 0x0002 },
		{ USBTV_BASE + 0x024e, 0x0002 },
		{ USBTV_BASE + 0x024f, 0x0002 },
};

static const uint16_t AUDIO_INIT[][2] = {
		/* These seem to enable the device. */
		{ USBTV_BASE + 0x0008, 0x0001 },
		{ USBTV_BASE + 0x01d0, 0x00ff },
		{ USBTV_BASE + 0x01d9, 0x0002 },

		{ USBTV_BASE + 0x01da, 0x0013 },
		{ USBTV_BASE + 0x01db, 0x0012 },
		{ USBTV_BASE + 0x01e9, 0x0002 },
		{ USBTV_BASE + 0x01ec, 0x006c },
		{ USBTV_BASE + 0x0294, 0x0020 },
		{ USBTV_BASE + 0x0255, 0x00cf },
		{ USBTV_BASE + 0x0256, 0x0020 },
		{ USBTV_BASE + 0x01eb, 0x0030 },
		{ USBTV_BASE + 0x027d, 0x00a6 },
		{ USBTV_BASE + 0x0280, 0x0011 },
		{ USBTV_BASE + 0x0281, 0x0040 },
		{ USBTV_BASE + 0x0282, 0x0011 },
		{ USBTV_BASE + 0x0283, 0x0040 },
		{ 0xf891, 0x0010 },

		/* this sets the input from composite */
		{ USBTV_BASE + 0x0284, 0x00aa },
};

static const uint16_t AUDIO_STOP[][2] = {
		/* The original windows driver sometimes sends also:
		 *   { USBTV_BASE + 0x00a2, 0x0013 }
		 * but it seems useless and its real effects are untested at
		 * the moment.
		 */
		{ USBTV_BASE + 0x027d, 0x0000 },
		{ USBTV_BASE + 0x0280, 0x0010 },
		{ USBTV_BASE + 0x0282, 0x0010 },
};

#endif //USBTV007_ANDROID_USBTV_DEFINITIONS_H
