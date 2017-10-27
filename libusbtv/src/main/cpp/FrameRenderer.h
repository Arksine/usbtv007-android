//
// Created by Eric on 10/18/2017.
//

#ifndef USBTV007_ANDROID_FRAMERENDERER_H
#define USBTV007_ANDROID_FRAMERENDERER_H

#include "usbtv_definitions.h"
#include "ConcurrentQueue/blockingconcurrentqueue.h"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <mutex>
#include <thread>
// TODO: Just stubbing out a few functions, will implement later


/**
 *
 */
class FrameRenderer {
private:
	enum class Status {
		STATUS_OFF,
		STATUS_UPDATE_WINDOW,
		STATUS_STOP,
		STATUS_STREAMING
	};

	Status      _currentStatus;

	std::thread*    _renderThread;
	std::mutex      _renderMutex;

	moodycamel::BlockingConcurrentQueue<UsbTvFrame*>* _frameQueue;

	ANativeWindow*  _renderWindow;
	EGLDisplay      _renderDisplay;
	EGLSurface      _renderSurface;
	EGLContext      _eglContext;

	GLuint _shaderProgramId;
	GLint _attrPosVertexId;
	GLint _attrTexVertexId;
	GLint _yuvTextureId;
	GLint _yuvMaskId;

	GLfloat*    _posVertexBuffer;
	GLfloat*    _texVertexBuffer;
	GLushort*   _indicesBuffer;
	uint8_t *   _yMaskBuffer;

	// TODO: Need buffers for vertices (positon and texture), indices, and y-value mask
	// TODO: need integers for all the opengl IDs that are generate


	bool initializeGL();
	bool initDisplay();
	void destroy();
	void drawFrame();

	static GLuint loadShaderProgram(const GLchar* vertexString,
	                                const GLchar* fragmentString);
	static GLuint loadShader(GLenum type, const GLchar* shaderString);
public:
	FrameRenderer();
	~FrameRenderer();

	void setRenderWindow(ANativeWindow* win);
	void onSurfaceChanged(int width, int height);  // not sure if I need this.  I can set the native window's buffer geometry and never change it
	void renderFrame(UsbTvFrame* frame);

	void enterMessageLoop();

	// TODO: I could just restart the thread every time a new surface is created, rather
	// than create a message loop.  Every time the thread starts the first thing it does
	// is initialize the EGL context and the OGL params (such as the shaders)
	void start();
	void stop();

	void threadStartCheck();
	void threadEndCheck();

	void initYmask(uint16_t frameWidth, uint16_t frameHeight);
};


#endif //USBTV007_ANDROID_FRAMERENDERER_H
