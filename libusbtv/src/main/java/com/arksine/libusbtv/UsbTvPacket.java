package com.arksine.libusbtv;


/**
 * Created by Eric on 10/4/2017.
 */

class UsbTvPacket {
    private static final int USBTV_PACKET_SIZE = 1024;       // Packet size in bytes

    final private byte[] mPacketBuffer;
    final private int mNumSubFrames;

    public UsbTvPacket(byte[] packetBuf) {
        mPacketBuffer = packetBuf;
        mNumSubFrames = mPacketBuffer.length / USBTV_PACKET_SIZE;
    }

    public int getNumberOfFrames() {
        return mNumSubFrames;
    }

    public boolean isPacketValid(int subPacketIndex) {
        /*if (subPacketIndex >= mNumSubFrames || subPacketIndex < 0) {
            // TODO: Throw exception

        }*/

        int index = subPacketIndex * USBTV_PACKET_SIZE;
        return frameCheck(mPacketBuffer[index]);
    }



    public int getFrameNumber(int subPacketIndex) {
        /*if (subPacketIndex >= mNumSubFrames || subPacketIndex < 0) {
            // TODO: Throw exception

        }*/


        int index = subPacketIndex * USBTV_PACKET_SIZE + 1;
        return getFrameId(mPacketBuffer[index]);
    }

    public int getPacketNumber(int subPacketIndex) {
        /*if (subPacketIndex >= mNumSubFrames || subPacketIndex < 0) {
            // TODO: Throw exception

        }*/

        int index = subPacketIndex * USBTV_PACKET_SIZE + 2;
        return getPacketNo(mPacketBuffer, index);
    }

    public boolean isPacketOdd(int subPacketIndex) {
        /*if (subPacketIndex >= mNumSubFrames || subPacketIndex < 0) {
            // TODO: Throw exception

        }*/

        int index = subPacketIndex * USBTV_PACKET_SIZE + 2;
        return getPacketOdd(mPacketBuffer[index]) != 0;

    }

    public void copyPayloadToFrame(int subPacketIndex, byte[] destBuf, int frameBufOffset, int length) {
        int srcStart = subPacketIndex * USBTV_PACKET_SIZE + 4;
        System.arraycopy(mPacketBuffer, srcStart, destBuf, frameBufOffset, length);
    }

    private static boolean frameCheck(byte checkByte) {
        return (checkByte == (byte)0x88);
    }

    private static int getFrameId(byte idByte) {
        return (idByte & 0xff);
    }

    private static int getPacketOdd(byte oddByte) {
        return ((oddByte & 0xf0) >> 7);
    }

    private static int getPacketNo (byte[] packet, int start) {
        int num = (packet[start + 1] & 0xff);
        num |= ((packet[start] & 0x0f) << 8);
        return num;
    }


}
