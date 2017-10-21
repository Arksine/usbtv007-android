package com.arksine.usbtvsample2;
import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;

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

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import timber.log.Timber;

/**
 * Created by Eric on 10/20/2017.
 */


/**
 * Open GL Frame Renderer.  Converts YUYV (YUY2) frames to RGB
 */
public class OGLRenderer implements GLSurfaceView.Renderer, UsbTv.onFrameReceivedListener {

    interface OnSurfaceCreatedListener {
        void onGLSurfaceCreated();
    }

    private OnSurfaceCreatedListener mSurfaceListener = null;
    private Context mContext;       // Dont really need a context, but better to keep one for later
                                    // when attempting to get Shaders from resource
    private BlockingQueue<UsbTvFrame> mFrameQueue;
    private DeviceParams mParams;

    private FloatBuffer mTexVertexBuf;
    private FloatBuffer mPosVertexBuf;
    private ShortBuffer mIndicesBuf;
    private float mScreenWidth;

    private int mShaderProgramId;
    private int mPositionAttr;
    private int mTextureAttr;
    private int mYUVTextureId;
    private int mUniformWidthId;


    public OGLRenderer(Context context, DeviceParams params) {
        mContext = context;
        mParams = params;
        mScreenWidth = mParams.getFrameWidth();
        mFrameQueue = new ArrayBlockingQueue<>(params.getFramePoolSize(), true);
        initByteBuffers();
    }

    public void setOnSurfaceCratedListener(OnSurfaceCreatedListener listener) {
        mSurfaceListener = listener;
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

        // Clear the color buffer
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // User Shader Program
        GLES20.glUseProgram(mShaderProgramId);

        // Set up Vertex Buffers
        mPosVertexBuf.position(0);
        GLES20.glVertexAttribPointer(mPositionAttr, 2, GLES20.GL_FLOAT, false, 0, mPosVertexBuf);
        mTexVertexBuf.position(0);
        GLES20.glVertexAttribPointer(mTextureAttr, 2, GLES20.GL_FLOAT, false, 0, mTexVertexBuf);

        GLES20.glEnableVertexAttribArray(mPositionAttr);
        GLES20.glEnableVertexAttribArray(mTextureAttr);

        // Set up texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glUniform1i(mYUVTextureId, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frame.getWidth()/2,
                frame.getHeight(), 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frame.getFrameBuf());
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Set up screen widht
        GLES20.glUniform1f(mUniformWidthId, mScreenWidth);

        // Draw
        mIndicesBuf.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, mIndicesBuf);

        frame.returnFrame();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        // should make sure the viewport always fills the screen
        mScreenWidth = (float) width;
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Initialize Shaders
        initShaderProgram();

        // Get Vertex Shader Attributes
        mPositionAttr = GLES20.glGetAttribLocation(mShaderProgramId, "a_position");
        mTextureAttr = GLES20.glGetAttribLocation(mShaderProgramId, "a_texCoord");

        GLES20.glEnable(GLES20.GL_TEXTURE_2D);
        mYUVTextureId = GLES20.glGetUniformLocation(mShaderProgramId, "yuv_texture");
        int[] textureNames = new int[1];
        GLES20.glGenTextures(1, textureNames, 0);
        int yuvTextureName = textureNames[0];
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextureName);

        mUniformWidthId = GLES20.glGetUniformLocation(mShaderProgramId, "screenWidth");

        // Clear Background
        GLES20.glClearColor(1.0f, 0.0f, 0.0f, 0.0f);

        if (mSurfaceListener != null) {
            mSurfaceListener.onGLSurfaceCreated();
        }
    }


    private void initByteBuffers() {

        final float[] positionVertices = {
                -1.0f, 1.0f,    // Position 0
                -1.0f, -1.0f,   // Position 1
                1.0f, -1.0f,    // Position 2
                1.0f, 1.0f,     // Position 3
        };

        final float[] textureVertices = {
                0.0f, 0.0f,     // TexCoord 0
                0.0f, 1.0f,     // TexCoord 1
                1.0f, 1.0f,     // TexCoord 2
                1.0f, 0.0f      // TexCoord 3
        };

        final short[] indices = { 0, 1, 2, 0, 2, 3 };

        mPosVertexBuf = ByteBuffer.allocateDirect(positionVertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mPosVertexBuf.put(positionVertices).position(0);

        mTexVertexBuf = ByteBuffer.allocateDirect(textureVertices.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        mTexVertexBuf.put(textureVertices).position(0);

        mIndicesBuf = ByteBuffer.allocateDirect(indices.length * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        mIndicesBuf.put(indices).position(0);

    }

    private void initShaderProgram() {
        // TODO: Load these from raw resources

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
                "uniform sampler2D yuv_texture;                     \n" +
                "uniform float screenWidth;                         \n"+

                "void main (void){                                  \n" +
                "   float r, g, b, y, u, v;                         \n" +
                "   float screenPosX;                               \n"+

                // Get determine whether the y-component is the first or second yuyv pixel
                // Normalize the screen x-coordinate to a range of [0,1]
                "   screenPosX = gl_FragCoord.x / screenWidth;      \n"+

                // If the screen X-Coordinate is less than the Texture Coordinate, use
                // The first Y-Value.  Othwerise use the second.
                "   if (screenPosX < v_texCoord.x) {                \n"+
                "       y = texture2D(yuv_texture, v_texCoord).x;   \n"+
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
    private static int loadShader(int type, String shaderString) {
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
    private static int loadProgram(String vertexString, String fragmentString) {
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
