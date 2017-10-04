package com.arksine.libusbtv;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDeviceConnection;

/**
 * Handles Control Transfers for Usbtv007 video/audio capture
 *
 * TODO: Notes:
 * - Determine if its required to actually initialize Audio. Eventually I will
 *   attempt to implement audio, but want to start by testing video only
 * - It appears that Control transfers should be done on Interface 0,
 *   iso/urb requests should be initialized on interface 1. (Or does interface 0 simply
 *   stop streaming, while interface 1 starts streaming?
 * - It appears that Audio is received via bulk transfer.  Video is an iso transfer
 */

public class UsbtvControl {

    // A tuple containing the index and value for a control transfer
    private static class ControlTransferPair {
        private final int mIndex;
        private final int mValue;

        ControlTransferPair(int index, int value) {
            mIndex = index;
            mValue = value;
        }

        public int getIndex() {
            return mIndex;
        }

        public int getValue() {
            return mValue;
        }

    }

    /**
     * Driver Constants
     */
    private static final int USBTV_BASE = 0xc000;
    private static final int USBTV_CONTROL_SEND_REGISTER = 11;
    private static final int USBTV_CONTROL_REQ_REGISTER = 12;

    // index/value of control transfer request to set composite input
    private static final ControlTransferPair[] COMPOSITE_INPUT_CTRL = {
            new ControlTransferPair(USBTV_BASE + 0x0105, 0x0060),
            new ControlTransferPair(USBTV_BASE + 0x011f, 0x00f2),
            new ControlTransferPair(USBTV_BASE + 0x0127, 0x0060),
            new ControlTransferPair(USBTV_BASE + 0x00ae, 0x0010),
            new ControlTransferPair(USBTV_BASE + 0x0239, 0x0060)
    };

    // index/value of control transfer request to set composite input
    private static final ControlTransferPair[] SVIDEO_INPUT_CTRL = {
            new ControlTransferPair(USBTV_BASE + 0x0105, 0x0010),
            new ControlTransferPair(USBTV_BASE + 0x011f, 0x00ff),
            new ControlTransferPair(USBTV_BASE + 0x0127, 0x0060),
            new ControlTransferPair(USBTV_BASE + 0x00ae, 0x0030),
            new ControlTransferPair(USBTV_BASE + 0x0239, 0x0060)
    };

    // set to PAL
    private static final ControlTransferPair[] PAL_CTRL = {
            new ControlTransferPair(USBTV_BASE + 0x001a, 0x0068),
            new ControlTransferPair(USBTV_BASE + 0x010e, 0x0072),
            new ControlTransferPair(USBTV_BASE + 0x010f, 0x00a2),
            new ControlTransferPair(USBTV_BASE + 0x0112, 0x00b0),
            new ControlTransferPair(USBTV_BASE + 0x0117, 0x0001),
            new ControlTransferPair(USBTV_BASE + 0x0118, 0x002c),
            new ControlTransferPair(USBTV_BASE + 0x012d, 0x0010),
            new ControlTransferPair(USBTV_BASE + 0x012f, 0x0020),
            new ControlTransferPair(USBTV_BASE + 0x024f, 0x0002),
            new ControlTransferPair(USBTV_BASE + 0x0254, 0x0059),
            new ControlTransferPair(USBTV_BASE + 0x025a, 0x0016),
            new ControlTransferPair(USBTV_BASE + 0x025b, 0x0035),
            new ControlTransferPair(USBTV_BASE + 0x0263, 0x0017),
            new ControlTransferPair(USBTV_BASE + 0x0266, 0x0016),
            new ControlTransferPair(USBTV_BASE + 0x0267, 0x0036)
    };

    // set to NTSC
    private static final ControlTransferPair[] NTSC_CTRL = {
            new ControlTransferPair(USBTV_BASE + 0x001a, 0x0079),
            new ControlTransferPair(USBTV_BASE + 0x010e, 0x0068),
            new ControlTransferPair(USBTV_BASE + 0x010f, 0x009c),
            new ControlTransferPair(USBTV_BASE + 0x0112, 0x00f0),
            new ControlTransferPair(USBTV_BASE + 0x0117, 0x0000),
            new ControlTransferPair(USBTV_BASE + 0x0118, 0x00fc),
            new ControlTransferPair(USBTV_BASE + 0x012d, 0x0004),
            new ControlTransferPair(USBTV_BASE + 0x012f, 0x0008),
            new ControlTransferPair(USBTV_BASE + 0x024f, 0x0001),
            new ControlTransferPair(USBTV_BASE + 0x0254, 0x005f),
            new ControlTransferPair(USBTV_BASE + 0x025a, 0x0012),
            new ControlTransferPair(USBTV_BASE + 0x025b, 0x0001),
            new ControlTransferPair(USBTV_BASE + 0x0263, 0x001c),
            new ControlTransferPair(USBTV_BASE + 0x0266, 0x0011),
            new ControlTransferPair(USBTV_BASE + 0x0267, 0x0005)
    };

    // Initialize Video
    private static final ControlTransferPair[] SETUP_VIDEO_CTRL = {
            // Enable device
            new ControlTransferPair(USBTV_BASE + 0x0008, 0x0001),
            new ControlTransferPair(USBTV_BASE + 0x01d0, 0x00ff),
            new ControlTransferPair(USBTV_BASE + 0x01d9, 0x0002),

            // According to linux driver, these set defaults for brightness, contrast, etc
            new ControlTransferPair(USBTV_BASE + 0x0239, 0x0040),
            new ControlTransferPair(USBTV_BASE + 0x0240, 0x0000),
            new ControlTransferPair(USBTV_BASE + 0x0241, 0x0000),
            new ControlTransferPair(USBTV_BASE + 0x0242, 0x0002),
            new ControlTransferPair(USBTV_BASE + 0x0243, 0x0080),
            new ControlTransferPair(USBTV_BASE + 0x0244, 0x0012),
            new ControlTransferPair(USBTV_BASE + 0x0245, 0x0090),
            new ControlTransferPair(USBTV_BASE + 0x0246, 0x0000),

            new ControlTransferPair(USBTV_BASE + 0x0278, 0x002d),
            new ControlTransferPair(USBTV_BASE + 0x0279, 0x000a),
            new ControlTransferPair(USBTV_BASE + 0x027a, 0x0032),
            new ControlTransferPair(0xf890, 0x000c),
            new ControlTransferPair(0xf894, 0x0086),

            new ControlTransferPair(USBTV_BASE + 0x00ac, 0x00c0),
            new ControlTransferPair(USBTV_BASE + 0x00ad, 0x0000),
            new ControlTransferPair(USBTV_BASE + 0x00a2, 0x0012),
            new ControlTransferPair(USBTV_BASE + 0x00a3, 0x00e0),
            new ControlTransferPair(USBTV_BASE + 0x00a4, 0x0028),
            new ControlTransferPair(USBTV_BASE + 0x00a5, 0x0082),
            new ControlTransferPair(USBTV_BASE + 0x00a7, 0x0080),
            new ControlTransferPair(USBTV_BASE + 0x0000, 0x0014),
            new ControlTransferPair(USBTV_BASE + 0x0006, 0x0003),
            new ControlTransferPair(USBTV_BASE + 0x0090, 0x0099),
            new ControlTransferPair(USBTV_BASE + 0x0091, 0x0090),
            new ControlTransferPair(USBTV_BASE + 0x0094, 0x0068),
            new ControlTransferPair(USBTV_BASE + 0x0095, 0x0070),
            new ControlTransferPair(USBTV_BASE + 0x009c, 0x0030),
            new ControlTransferPair(USBTV_BASE + 0x009d, 0x00c0),
            new ControlTransferPair(USBTV_BASE + 0x009e, 0x00e0),
            new ControlTransferPair(USBTV_BASE + 0x0019, 0x0006),
            new ControlTransferPair(USBTV_BASE + 0x008c, 0x00ba),
            new ControlTransferPair(USBTV_BASE + 0x0101, 0x00ff),
            new ControlTransferPair(USBTV_BASE + 0x010c, 0x00b3),
            new ControlTransferPair(USBTV_BASE + 0x01b2, 0x0080),
            new ControlTransferPair(USBTV_BASE + 0x01b4, 0x00a0),
            new ControlTransferPair(USBTV_BASE + 0x014c, 0x00ff),
            new ControlTransferPair(USBTV_BASE + 0x014d, 0x00ca),
            new ControlTransferPair(USBTV_BASE + 0x0113, 0x0053),
            new ControlTransferPair(USBTV_BASE + 0x0119, 0x008a),
            new ControlTransferPair(USBTV_BASE + 0x013c, 0x0003),
            new ControlTransferPair(USBTV_BASE + 0x0150, 0x009c),
            new ControlTransferPair(USBTV_BASE + 0x0151, 0x0071),
            new ControlTransferPair(USBTV_BASE + 0x0152, 0x00c6),
            new ControlTransferPair(USBTV_BASE + 0x0153, 0x0084),
            new ControlTransferPair(USBTV_BASE + 0x0154, 0x00bc),
            new ControlTransferPair(USBTV_BASE + 0x0155, 0x00a0),
            new ControlTransferPair(USBTV_BASE + 0x0156, 0x00a0),
            new ControlTransferPair(USBTV_BASE + 0x0157, 0x009c),
            new ControlTransferPair(USBTV_BASE + 0x0158, 0x001f),
            new ControlTransferPair(USBTV_BASE + 0x0159, 0x0006),
            new ControlTransferPair(USBTV_BASE + 0x015d, 0x0000),

            new ControlTransferPair(USBTV_BASE + 0x0003, 0x0004),
            new ControlTransferPair(USBTV_BASE + 0x0100, 0x00d3),
            new ControlTransferPair(USBTV_BASE + 0x0115, 0x0015),
            new ControlTransferPair(USBTV_BASE + 0x0220, 0x002e),
            new ControlTransferPair(USBTV_BASE + 0x0225, 0x0008),
            new ControlTransferPair(USBTV_BASE + 0x024e, 0x0002),
            new ControlTransferPair(USBTV_BASE + 0x024e, 0x0002),
            new ControlTransferPair(USBTV_BASE + 0x024f, 0x0002)
    };


    /**
     * Audio Control Transfer Values
     */
    private static final ControlTransferPair[] START_AUDIO_CTRL = {
            // These start Audio per Linux driver
            new ControlTransferPair(USBTV_BASE + 0x0008, 0x0001),
            new ControlTransferPair(USBTV_BASE + 0x01d0, 0x00ff),
            new ControlTransferPair(USBTV_BASE + 0x01d9, 0x0002),

            new ControlTransferPair(USBTV_BASE + 0x01da, 0x0013),
            new ControlTransferPair(USBTV_BASE + 0x01db, 0x0012),
            new ControlTransferPair(USBTV_BASE + 0x01e9, 0x0002),
            new ControlTransferPair(USBTV_BASE + 0x01ec, 0x006c),
            new ControlTransferPair(USBTV_BASE + 0x0294, 0x0020),
            new ControlTransferPair(USBTV_BASE + 0x0255, 0x00cf),
            new ControlTransferPair(USBTV_BASE + 0x0256, 0x0020),
            new ControlTransferPair(USBTV_BASE + 0x01eb, 0x0030),
            new ControlTransferPair(USBTV_BASE + 0x027d, 0x00a6),
            new ControlTransferPair(USBTV_BASE + 0x0280, 0x0011),
            new ControlTransferPair(USBTV_BASE + 0x0281, 0x0040),
            new ControlTransferPair(USBTV_BASE + 0x0282, 0x0011),
            new ControlTransferPair(USBTV_BASE + 0x0283, 0x0040),
            new ControlTransferPair(0xf891, 0x0010),

            // Sets Input from composite per linux driver (doesn't work for S-Video?)
            new ControlTransferPair(USBTV_BASE + 0x0284, 0x00aa)
    };

    private static final ControlTransferPair[] STOP_AUDIO_CTRL = {
            new ControlTransferPair(USBTV_BASE + 0x027d, 0x0000),
            new ControlTransferPair(USBTV_BASE + 0x0280, 0x0010),
            new ControlTransferPair(USBTV_BASE + 0x0282, 0x0010)
    };

    private UsbDeviceConnection mUsbtvConnection;


    // Static class, should not be instantiated
    public UsbtvControl(UsbDeviceConnection connection) {
        mUsbtvConnection = connection;
    }

    public boolean initializeVideo() {
        return sendControlTransferPacket(SETUP_VIDEO_CTRL);
    }

    public boolean setVideoInputComposite() {
        return sendControlTransferPacket(COMPOSITE_INPUT_CTRL);
    }

    public boolean setVideoInputSVideo() {
        return sendControlTransferPacket(SVIDEO_INPUT_CTRL);
    }

    public boolean setTvNormPal() {
        return sendControlTransferPacket(PAL_CTRL);
    }

    public boolean setTvNormNtsc() {
        return sendControlTransferPacket(NTSC_CTRL);
    }

    public boolean startAudio() {
        return sendControlTransferPacket(START_AUDIO_CTRL);
    }

    public boolean stopAudio() {
        return sendControlTransferPacket(STOP_AUDIO_CTRL);
    }

    private boolean sendControlTransferPacket(ControlTransferPair[] packet) {
        int ret;
        for (ControlTransferPair pair : packet) {
            ret = mUsbtvConnection.controlTransfer(
                    UsbConstants.USB_TYPE_VENDOR,
                    USBTV_CONTROL_SEND_REGISTER,
                    pair.getValue(),
                    pair.getIndex(),
                    null, 0, 0);

            if (ret < 0) {
                return false;
            }
        }
        return true;
    }



}
