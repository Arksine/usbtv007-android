//
// Created by Eric on 10/18/2017.
//

#ifndef USBTV007_ANDROID_FRAMERENDERER_H
#define USBTV007_ANDROID_FRAMERENDERER_H

#include "usbtv_definitions.h"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <mutex>
// TODO: Just stubbing out a few functions, will implement later


/**
 *
 */
class FrameRenderer {
private:
	enum class Status {
		STATUS_OFF,
		STATUS_UPDATE_WINDOW,
		STATUS_INIT_GL,
		STATUS_STOP,
		STATUS_STREAMING
	};

	Status      _currentStatus;
	std::mutex  _renderMutex;
	bool        _glInitialized;

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


	bool initializeGL(UsbTvFrame* frame);
	bool initDisplay();
	void destroy();


	static GLuint loadShaderProgram(const GLchar * const *vertexString,
	                                const GLchar * const *fragmentString);
	static GLuint loadShader(GLenum type, const GLchar * const *shaderString);
public:
	FrameRenderer();
	~FrameRenderer();

	void setRenderWindow(ANativeWindow* win);
	void renderFrame(UsbTvFrame* frame);
	void signalStop();

	void threadStartCheck();
	void threadEndCheck();
};


#endif //USBTV007_ANDROID_FRAMERENDERER_H
