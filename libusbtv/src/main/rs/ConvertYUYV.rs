#pragma version(1)
#pragma rs java_package_name(com.arksine.libusbtv)
#pragma rs_fp_relaxed

rs_allocation output;

void __attribute__((kernel)) convertFromYUYV(uchar4 in, uint32_t x)
{
    uchar4 first;
    uchar4 second;

    uint32_t outIndex = 2*x;

    first = rsYuvToRGBA_uchar4(in.x, in.y, in.w);
    second = rsYuvToRGBA_uchar4(in.z, in.y, in.w);

    rsSetElementAt_uchar4(output, first, outIndex);
    rsSetElementAt_uchar4(output, second, outIndex+1);

}