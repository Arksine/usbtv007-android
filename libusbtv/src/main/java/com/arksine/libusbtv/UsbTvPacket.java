package com.arksine.libusbtv;

import com.sun.jna.Pointer;


/**
 * Created by Eric on 10/4/2017.
 */

class UsbTvPacket {
    private static final int USBTV_PACKET_SIZE = 1024;       // Packet size in bytes

    final private Pointer mPacketAddress;
    final private int mPacketSize;
    final private int mNumSubFrames;

    public UsbTvPacket(Pointer address, int size) {
        mPacketAddress = address;
        mPacketSize = size;
        mNumSubFrames = mPacketSize / USBTV_PACKET_SIZE;
    }

    public int getNumberOfFrames() {
        return mNumSubFrames;
    }

    public int getPacketHeader(int subframeIndex) {

        if (subframeIndex >= mNumSubFrames || subframeIndex < 0) {
            // TODO: Throw exception

        }
        byte[] intBuf = new byte[4];
        mPacketAddress.read((subframeIndex * USBTV_PACKET_SIZE), intBuf, 0, 4);
        return beByteArrayToInt(intBuf);
    }

    public Pointer getPayloadPointer(int subframeIndex) {
        if (subframeIndex >= mNumSubFrames || subframeIndex < 0) {
            // TODO: Throw exception

        }

        return mPacketAddress.getPointer((subframeIndex * USBTV_PACKET_SIZE) + 4);
    }

    private static int beByteArrayToInt(byte[] data) {
        int value = data[0] << 24;
        value |= (data[1] & 0xFF) << 16;
        value |= (data[2] & 0xFF) << 8;
        value |= (data[3] & 0xFF);

        return value;
    }

}
