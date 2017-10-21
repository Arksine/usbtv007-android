package com.arksine.usbtvsample2;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.view.Surface;

import com.arksine.libusbtv.DeviceParams;
import com.arksine.libusbtv.UsbTv;
import com.arksine.libusbtv.UsbTvFrame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import timber.log.Timber;

/**
 * Created by Eric on 10/20/2017.
 */

/**
 * TODO : I think I know how  to accomplish this. I need two textures.  The first
 * is a texture backed by an array of bytes that matches the frame size (ie 720 x 240) for
 * a progressive frame.  This buffer will simply alternate 0s and 1s.  If its zero, the y-value
 * in the primary texture is the x value.  Otherwise it will be the z value(third).  If I am
 * understanding GLSL correctly, this will cause the fragment shader to operate on each pixel
 * of the main buffer twice (I hope).  This Texture should be of type GLES20.GL_LUMINANCE, which
 * represents a single byte.
 *
 * ie:  mBackingBuffer = bytebuffer of alternating 0s and 1s  -- Make it a directbytebuffer with native byteorder
 * GLES20.glTexImage2D(   GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 720, 240, 0,
 * GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, mBackingBuffer);
 *
 *
 * The main texture should be of typem GLES20.GL_RGBA.  I believe its with should be HALF the
 * other buffer, and its height should be equal.
 *
 * ie:  frame.getBuffer() = direct bytebuffer holding frame
 * GLES20.glTexImage2D(   GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 720/2, 240, 0,
 * GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, frame.getBuffer());
 *
 *
 * Should probably separate the Vertices buffers into Position and Texture vertices.  That way
 * I don't need to specify a strinde
 *
 */

public class OGLRenderer implements GLSurfaceView.Renderer, UsbTv.onFrameReceivedListener {

    private BlockingQueue<UsbTvFrame> mFrameQueue;
    private DeviceParams mParams;


    // TODO: Allocate all of these direct! Set byte order to native order
    private FloatBuffer mTexVertexBuf;
    private FloatBuffer mPosVertexBuf;
    private ShortBuffer mIndicesBuf;
    private ByteBuffer mYUVindexBuf;

    private int mShaderProgramId;
    private int mPositionAttr;
    private int mTextureAttr;
    private int mYUVTextureId;
    private int mPositonTextureId;

    public OGLRenderer(DeviceParams params) {
        mParams = params;
        mFrameQueue = new ArrayBlockingQueue<>(params.getFramePoolSize(), true);
        initByteBuffers();
    }

    // TODO: I should have a function that chances render Parameters if the device params change


    @Override
    public void onFrameReceived(UsbTvFrame frame) {
        if (!mFrameQueue.offer(frame)) {
            frame.returnFrame();
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        UsbTvFrame frame;
        try {
            // Poll for 100 ms
            frame = mFrameQueue.poll(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Timber.i(e);
            return;
        }

        if (frame == null) {
            // TODO: render black frame?
            return;
        }

        // TODO: Draw Frame




        frame.returnFrame();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        // should make sure the viewport always fills the screen
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Initialize Shaders
        initShaderProgram();

        // Get Vertex Shader Attributes
        mPositionAttr = GLES20.glGetAttribLocation(mShaderProgramId, "a_position");
        mTextureAttr = GLES20.glGetAttribLocation(mShaderProgramId, "a_texCoord");

        // TODO: Get YUV and Position texture IDs and complete init
    }


    private void initByteBuffers() {

        final float[] positonVertices = {
                -1.0f, 1.0f,    // Position 0
                -1.0f, -1.0f,   // Position 1
                1.0f, -1.0f,    // Position 2
                1.0f, 1.0f,     // Position 3
        };

        // TODO: I could potentially set this up to equal the frame size,
        // IE: from 0-720 for height and 0-240 for width.  Then when I get
        // a coordinate in the fragment shader if the coordinate mod 2 is
        // zero its the first element, otherwise its the second element.
        // This would eliminate the need
        final float[] textureVertices = {
                0.0f, 0.0f,     // TexCoord 0
                0.0f, 1.0f,     // TexCoord 1
                1.0f, 1.0f,     // TexCoord 2
                1.0f, 0.0f      // TexCoord 3
        };

        final short[] indices = { 0, 1, 2, 0, 2, 3 };

        mPosVertexBuf = ByteBuffer.allocateDirect(positonVertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mPosVertexBuf.put(positonVertices).position(0);

        mTexVertexBuf = ByteBuffer.allocateDirect(textureVertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTexVertexBuf.put(textureVertices).position(0);

        mIndicesBuf = ByteBuffer.allocateDirect(indices.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        mIndicesBuf.put(indices).position(0);

        int yuvBufSize = mParams.getFrameHeight() * mParams.getFrameWidth();

        // Init YUV Index buffer.  If the byte is 0, the Y-Value is x.  If the
        // byte is 1, the Y-Value is z.
        mYUVindexBuf = ByteBuffer.allocate(yuvBufSize)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < yuvBufSize; i++) {
            mYUVindexBuf.put((byte)(i % 2));
        }
        mYUVindexBuf.position(0);
    }

    private void initShaderProgram() {
        // TODO: Load these from raw resources
        // TODO: I can probably calculate the y-position in the
        // vertex shader.  I'll try it after I see if the current
        // attempt to render is successful

        //Our vertex shader code; nothing special
        String vertexShader =
                "attribute vec4 a_position;                         \n" +
                "attribute vec2 a_texCoord;                         \n" +
                "varying vec2 v_texCoord;                           \n" +

                "void main(){                                       \n" +
                "   gl_Position = a_position;                       \n" +
                "   v_texCoord = a_texCoord;                        \n" +
                "}                                                  \n";

        //Our fragment shader code; takes Y,U,V values for each pixel and calculates R,G,B colors,
        //Effectively making YUV to RGB conversion
        String fragmentShader =
                "#ifdef GL_ES                                       \n" +
                "precision highp float;                             \n" +
                "#endif                                             \n" +

                "varying vec2 v_texCoord;                           \n" +
                "uniform sampler2D pos_texture;                     \n" +
                "uniform sampler2D yuv_texture;                     \n" +


                "void main (void){                                  \n" +
                "   float r, g, b, y, u, v;                         \n" +
                "   int pos;                                        \n"+

                // Get determine whether the y-component is the first or second yuyv pixel
                "   pos = texture2D(pos_texture, v_texCoord).x      \n"+
                "   if (pos == 0) {                                 \n"+
                "       y = texture2D(yuv_texture, v_texCoord).x    \n"+
                "   } else {                                        \n"+
                "       y = texture2D(yuv_texture, v_texCoord).z;   \n"+
                "   }                                               \n"+

                // U and V components are always the 2nd and 4th positions (not sure why subtracting .5)
                "   u = texture2D(yuv_texture, v_texCoord).y - 0.5;  \n" +
                "   v = texture2D(yuv_texture, v_texCoord).w - 0.5;  \n" +


                //The numbers are just YUV to RGB conversion constants
                "   r = y + 1.13983*v;                              \n" +
                "   g = y - 0.39465*u - 0.58060*v;                  \n" +
                "   b = y + 2.03211*u;                              \n" +

                //We finally set the RGB color of our pixel
                "   gl_FragColor = vec4(r, g, b, 1.0);              \n" +
                "}                                                  \n";

        mShaderProgramId = loadProgram(vertexShader, fragmentShader);
    }

    /**
     * Creates a shader
     * @param type          The Shader Type, being either vertex or fragment
     * @param shaderString  The String containing the shader source code
     * @return              The shader Id, or 0 if failed
     */
    public static int loadShader(int type, String shaderString) {
        int shaderId;
        int[] compiled = new int[1];

        shaderId = GLES20.glCreateShader(type);
        if (shaderId == 0) {
            Timber.e("Error creating shader");
            return 0;
        }

        GLES20.glShaderSource(shaderId, shaderString);
        GLES20.glCompileShader(shaderId);
        GLES20.glGetShaderiv(shaderId, GLES20.GL_COMPILE_STATUS, compiled, 0);

        if (compiled[0] == 0) {
            Timber.e("Error Compiling Shader: %s", GLES20.glGetShaderInfoLog(shaderId));
            GLES20.glDeleteShader(shaderId);
            return 0;
        }
        return shaderId;
    }

    /**
     * Creates a Shader program.
     *
     * @param vertexString  String containting the Vertex Program Source
     * @param fragmentString    String containing the Fragment Program's source
     * @return  The Shader Program's Object ID
     */
    public static int loadProgram(String vertexString, String fragmentString) {
        int vertexShaderId;
        int fragmentShaderId;
        int programId;
        int[] linked = new int[1];

        vertexShaderId = loadShader(GLES20.GL_VERTEX_SHADER, vertexString);
        if (vertexShaderId == 0) {
            return 0;
        }

        fragmentShaderId = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentString);
        if (fragmentShaderId == 0) {
            return 0;
        }

        programId = GLES20.glCreateProgram();
        if (programId == 0) {
            return 0;
        }

        GLES20.glAttachShader(programId, vertexShaderId);
        GLES20.glAttachShader(programId, fragmentShaderId);
        GLES20.glLinkProgram(programId);
        GLES20.glGetProgramiv(programId, GLES20.GL_LINK_STATUS, linked, 0);

        if (linked[0] == 0) {
            Timber.e("Error Linking Program: %s", GLES20.glGetProgramInfoLog(programId));
            GLES20.glDeleteProgram(programId);
            return 0;
        }

        // Don't need Shader Objects anymore, delete them
        GLES20.glDeleteShader(vertexShaderId);
        GLES20.glDeleteShader(fragmentShaderId);

        return programId;
    }
}
