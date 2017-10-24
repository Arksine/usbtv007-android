//
// Created by Eric on 10/18/2017.
//

#include "FrameRenderer.h"
#include "util.h"
#include "android/native_window.h"
#include <cstdlib>

// TODO: When the surface changes rather than is created or destroyed, I should simply
// pass the new width and height rather than create a new window.  I can change
// the viewport.

// TODO: Manage and start a thread owned by the Frame renderer.  I can use a blocking queue
// for this, but in the future I'll just poll the TVDriver.

// TODO: The application should communicate directly with the renderer

static GLfloat positionVertices[] = {
		-1.0f, 1.0f,    // Position 0
		-1.0f, -1.0f,   // Position 1
		1.0f, -1.0f,    // Position 2
		1.0f, 1.0f      // Position 3
};

static GLfloat textureVertices[] = {
		0.0f, 0.0f,     // TexCoord 0
		0.0f, 1.0f,     // TexCoord 1
		1.0f, 1.0f,     // TexCoord 2
		1.0f, 0.0f      // TexCoord 3
};

static GLushort indices[] = {0, 1, 2, 0, 2, 3};

//Our vertex shader code; nothing special
static const GLchar* vertexShaderString =
	"attribute vec4 a_position;                         \n"
	"attribute vec2 a_texCoord;                         \n"
	"varying vec2 v_texCoord;                           \n"

	"void main(){                                       \n"
	"   gl_Position = a_position;                       \n"
	"   v_texCoord = a_texCoord;                        \n"
	"}";


//Our fragment shader code; takes Y,U,V values for each pixel and calculates R,G,B colors,
//Effectively making YUV to RGB conversion
static const GLchar* fragmentShaderString =
		"#ifdef GL_ES                                       \n"
		"precision highp float;                             \n"
		"#endif                                             \n"

		"varying vec2 v_texCoord;                           \n"
		"uniform sampler2D yuv_texture;                     \n"
		"uniform sampler2D y_mask;                          \n"

		"void main (void){                                  \n"
		"   float r, g, b, y, u, v;                         \n"
		"   float yPosition;                                \n"
		"   vec4 yuvPixel;                                  \n"

		// Get the Pixel and Y-Value mask
		"   yuvPixel = texture2D(yuv_texture, v_texCoord);  \n"
		"   yPosition = texture2D(y_mask, v_texCoord).x;    \n"


		// If the mask is zero (or thereabout), use the 1st y-value.
		// Otherwise use the 2nd.
		"   if (yPosition < 1.0f) {                         \n"
		"       y = yuvPixel.x;                             \n"
		"   } else {                                        \n"
		"       y = yuvPixel.z;                             \n"
		"   }                                               \n"

		// U and V components are always the 2nd and 4th positions (not sure why subtracting .5)
		"   u = yuvPixel.y - 0.5;                           \n"
		"   v = yuvPixel.w - 0.5;                           \n"


		//The numbers are just YUV to RGB conversion constants
		"   r = y + 1.13983*v;                              \n"
		"   g = y - 0.39465*u - 0.58060*v;                  \n"
		"   b = y + 2.03211*u;                              \n"

		//We finally set the RGB color of our pixel
		"   gl_FragColor = vec4(r, g, b, 1.0);              \n"
		"}";


FrameRenderer::FrameRenderer() {
	_currentStatus = Status::STATUS_OFF;

	_posVertexBuffer = positionVertices;
	_texVertexBuffer = textureVertices;
	_indicesBuffer = indices;
	_yMaskBuffer = nullptr;

	_renderWindow = nullptr;
	_renderDisplay = EGL_NO_DISPLAY;
	_renderSurface = EGL_NO_SURFACE;
	_eglContext = EGL_NO_CONTEXT;
}

FrameRenderer::~FrameRenderer() {
	if (_yMaskBuffer != nullptr) {
		free(_yMaskBuffer);
		_yMaskBuffer = nullptr;
	}

	if (_renderWindow != nullptr) {
		ANativeWindow_release(_renderWindow);
	}

}

/**
 * The renderer does not manage the thread, so call this before entering the main loop
 * thread loop.  This function checks to see if the render window had been set previously,
 * but is not in the process of being reinitialized.
 */
void FrameRenderer::threadStartCheck() {
	_renderMutex.lock();

	// If the render window has not been set, or if it is preparing to be set, exit.
	// The renderer will be started upon successful completion of window update
	if (_renderWindow != nullptr && _currentStatus != Status::STATUS_UPDATE_WINDOW) {
		_currentStatus = Status::STATUS_UPDATE_WINDOW;
	}

	_renderMutex.unlock();
}

/**
 * This renderer does not manage the render thread, so the only way to be sure that
 * the EGL context is destroyed is to call a check before the thread ends.  This check
 * should be done in the event that the calling thread loop exits before it processes the
 * stop signal.
 */
void FrameRenderer::threadEndCheck() {
	_renderMutex.lock();
	if (_renderDisplay != EGL_NO_DISPLAY) {
		LOGI("Renderer was not shut down via stop signal, threadEndCheck() will destroy egl context");
		destroy();
	}
	_renderMutex.unlock();
}

void FrameRenderer::signalStop() {
	_renderMutex.lock();
	if (_currentStatus != Status::STATUS_OFF) {
		_currentStatus = Status::STATUS_STOP;
	}
	_renderMutex.unlock();
}

void FrameRenderer::setRenderWindow(ANativeWindow *win) {
	_renderMutex.lock();

	// release the previous window if it was set
	if (_renderWindow != nullptr) {
		ANativeWindow_release(_renderWindow);
	}

	_renderWindow = win;
	if (win == nullptr) {
		if (_currentStatus != Status::STATUS_OFF) {
			_currentStatus = Status::STATUS_STOP;
		}
	} else {
		_currentStatus = Status::STATUS_UPDATE_WINDOW;
	}

	_renderMutex.unlock();
}

void FrameRenderer::initYmask(uint16_t frameWidth, uint16_t frameHeight) {
	_renderMutex.lock();
	if (_yMaskBuffer != nullptr) {
		free(_yMaskBuffer);
		_yMaskBuffer = nullptr;
	}

	// Set up the Y-value mask.  Even values are 0, odd values are 2.  This allows the
	// shader to determine which Y-Value to use when fetching a texel that contains a YUYV
	// format vector.
	size_t bufferSize = frameWidth*frameHeight;
	_yMaskBuffer = (uint8_t*)malloc(bufferSize);

	for (int i = 0; i < bufferSize; i++) {
		_yMaskBuffer[i] = (uint8_t)((i % 2)*2);
	}
	_renderMutex.unlock();
}

bool FrameRenderer::initDisplay() {
	const EGLint attribs[] = {
			EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
			EGL_BLUE_SIZE, 8,
			EGL_GREEN_SIZE, 8,
			EGL_RED_SIZE, 8,
			EGL_NONE
	};
	const EGLint contextAttrib[] = {
		EGL_CONTEXT_CLIENT_VERSION, 2,
	    EGL_NONE
	};

	EGLConfig config;
	EGLint numConfigs;
	EGLint format;
	EGLint width;
	EGLint height;

	if (_eglContext != EGL_NO_CONTEXT) {
		// Destroy previous display, context, etc
		destroy();
	}

	LOGI("Initializing EGL Context");

	_renderDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
	if (_renderDisplay == EGL_NO_DISPLAY) {
		LOGE("eglGetDisplay() returned error: %d", eglGetError());
		return false;
	}

	if (!eglInitialize(_renderDisplay, 0, 0)) {
		LOGE("eglInitialize() returned error: %d", eglGetError());
		destroy();
		return false;
	}

	if (!eglChooseConfig(_renderDisplay, attribs, &config, 1, &numConfigs)) {
		LOGE("eglChooseConfig() returned error: %d", eglGetError());
		destroy();
		return false;
	}

	if (!eglGetConfigAttrib(_renderDisplay,config, EGL_NATIVE_VISUAL_ID,  &format)) {
		LOGE("eglGetConfigAttrib() returned error: %d", eglGetError());
		destroy();
		return false;
	}

	ANativeWindow_setBuffersGeometry(_renderWindow, 0, 0, format);

	_renderSurface = eglCreateWindowSurface(_renderDisplay, config, _renderWindow, 0);
	if (!_renderSurface) {
		LOGE("eglCreateWindowSurface() returned error: %d", eglGetError());
		destroy();
		return false;
	}

	_eglContext = eglCreateContext(_renderDisplay, config, 0, contextAttrib);
	if (!_eglContext) {
		LOGE("eglCreateContext() returned error: %d", eglGetError());
		destroy();
		return false;
	}

	if (!eglMakeCurrent(_renderDisplay, _renderSurface, _renderSurface, _eglContext)) {
		LOGE("eglMakeCurrent returned error: %d", eglGetError());
		destroy();
		return false;
	}

	if (!eglQuerySurface(_renderDisplay, _renderSurface, EGL_WIDTH, &width) ||
	    !eglQuerySurface(_renderDisplay, _renderSurface, EGL_HEIGHT, &height)) {
		LOGE("eglQuerySurface() returned error: %d", eglGetError());
		destroy();
		return false;
	}

	glViewport(0, 0, width, height);

	return initializeGL();
}

bool FrameRenderer::initializeGL() {
	bool success = true;


	_shaderProgramId = loadShaderProgram(vertexShaderString, fragmentShaderString);
	if (_shaderProgramId == 0) {
		success = false;
	}

	//  initialize GL attributes, textures, etc
	_attrPosVertexId = glGetAttribLocation(_shaderProgramId, "a_position");
	_attrTexVertexId = glGetAttribLocation(_shaderProgramId, "a_texCoord");

	glEnable(GL_TEXTURE_2D);
	_yuvTextureId = glGetUniformLocation(_shaderProgramId, "yuv_texture");
	GLuint yuvTexName[1];
	glGenTextures(1, yuvTexName);
	glActiveTexture(GL_TEXTURE1);
	glBindTexture(GL_TEXTURE_2D, yuvTexName[0]);

	glEnable(GL_TEXTURE_2D);
	_yuvMaskId = glGetUniformLocation(_shaderProgramId, "y_mask");
	GLuint yMaskName[1];
	glGenTextures(1, yMaskName);
	glActiveTexture(GL_TEXTURE2);
	glBindTexture(GL_TEXTURE_2D, yMaskName[0]);

	glClearColor(0.0f, 1.0f, 0.0f, 0.0f);

	return success;
}

void FrameRenderer::destroy() {
	if (_yMaskBuffer != nullptr) {
		free(_yMaskBuffer);
		_yMaskBuffer = nullptr;
	}

	glDeleteProgram(_shaderProgramId);

	eglMakeCurrent(_renderDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
	eglDestroyContext(_renderDisplay, _eglContext);
	eglDestroySurface(_renderDisplay, _renderSurface);
	eglTerminate(_renderDisplay);

	_renderDisplay = EGL_NO_DISPLAY;
	_renderSurface = EGL_NO_SURFACE;
	_eglContext = EGL_NO_CONTEXT;

	_currentStatus = Status::STATUS_OFF;
}



void FrameRenderer::renderFrame(UsbTvFrame *frame) {
	_renderMutex.lock();

	// Check the current status, and proceed based on that status
	switch (_currentStatus) {
		case Status::STATUS_OFF:
			// Don't do anything.  Decrement lock reference and return
			frame->lock--;
			_renderMutex.unlock();
			return;
		case Status::STATUS_UPDATE_WINDOW:
			// The window update must happen in this function, because
			// the EGL context is tied to the thread.  If the surface changes,
			// we must update it in this loop.

			if (!initDisplay()) {
				// Init display failed
				_currentStatus = Status::STATUS_OFF;
			} else {
				// Both Display and GL are initialized, proceed to render
				_currentStatus = Status::STATUS_STREAMING;
			}

			// skip this frame
			frame->lock--;
			_renderMutex.unlock();
			return;
		case Status::STATUS_STOP:
			destroy();
			frame->lock--;
			_renderMutex.unlock();
			return;
		default:
			break;
	}

	// Clear display and init program
	glClear(GL_COLOR_BUFFER_BIT);
	glUseProgram(_shaderProgramId);

	// Set up vertex buffers
	glVertexAttribPointer((GLuint)_attrPosVertexId, 2, GL_FLOAT, (GLboolean)false, 0,
	                      _posVertexBuffer);
	glVertexAttribPointer((GLuint)_attrTexVertexId, 2, GL_FLOAT, (GLboolean) false, 0,
	                      _texVertexBuffer);
	glEnableVertexAttribArray((GLuint)_attrPosVertexId);
	glEnableVertexAttribArray((GLuint)_attrTexVertexId);

	// Set up YUV texture
	glActiveTexture(GL_TEXTURE1);
	glUniform1i(_yuvTextureId, 1);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, frame->width / 2, frame->height, 0,
	             GL_RGBA, GL_UNSIGNED_BYTE, frame->buffer);
	glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

	// Set up Y-Mask
	glActiveTexture(GL_TEXTURE2);
	glUniform1i(_yuvMaskId, 2);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, frame->width, frame->height, 0,
	             GL_LUMINANCE, GL_UNSIGNED_BYTE, _yMaskBuffer);
	glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
	glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
	glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameterf(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);



	// Draw
	glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, _indicesBuffer);


	// swap egl buffers to display
	eglSwapBuffers(_renderDisplay, _renderSurface);


	// Release the Frame's lock, as we no longer need it
	frame->lock--;
	_renderMutex.unlock();
}

GLuint FrameRenderer::loadShaderProgram(const GLchar* vertexString,
                                        const GLchar* fragmentString) {
	GLuint vertexShaderId;
	GLuint fragmentShaderId;
	GLuint programId;
	GLint linked[1];

	vertexShaderId = loadShader(GL_VERTEX_SHADER, vertexString);
	if (vertexShaderId == 0) {
		LOGE("Vertex Shader Error");
		return 0;
	}

	fragmentShaderId = loadShader(GL_FRAGMENT_SHADER, fragmentString);
	if (fragmentShaderId == 0) {
		LOGE("Fragment Shader Error");
		return 0;
	}

	programId = glCreateProgram();
	if (programId == 0) {
		LOGE("Error creating shader program");
	}

	glAttachShader(programId, vertexShaderId);
	glAttachShader(programId, fragmentShaderId);
	glLinkProgram(programId);

	glGetProgramiv(programId, GL_LINK_STATUS, linked);
	if (linked[0] == 0) {
		GLint loglength[1];
		glGetProgramiv(programId, GL_INFO_LOG_LENGTH, loglength);
		if (loglength[0] > 0) {
			GLsizei len;
			GLchar *logBuf = (GLchar *) malloc((size_t) loglength[0]);
			glGetProgramInfoLog(programId, loglength[0], &len, logBuf);
			LOGD("Error Linking Program: %s", logBuf);
			free(logBuf);
		} else {
			LOGD("Error Linking Program");
		}
		glDeleteShader(vertexShaderId);
		glDeleteShader(fragmentShaderId);
		glDeleteProgram(programId);

		return 0;
	}

	// Don't need the shaders with a linked program
	glDeleteShader(vertexShaderId);
	glDeleteShader(fragmentShaderId);

	return programId;
}

GLuint FrameRenderer::loadShader(GLenum type, const GLchar* shaderString) {
	GLuint shaderId;
	GLint compiled[1];

	shaderId = glCreateShader(type);
	if (shaderId == 0) {
		LOGD("Error Creating Shader");
		return 0;
	}

	glShaderSource(shaderId, 1, &shaderString, NULL);
	glCompileShader(shaderId);
	glGetShaderiv(shaderId, GL_COMPILE_STATUS, compiled);

	if (compiled[0] == 0) {
		GLint loglength[1];
		glGetShaderiv(shaderId, GL_INFO_LOG_LENGTH, loglength);
		if (loglength[0] > 0) {
			GLsizei len;
			GLchar *logBuf = (GLchar *) malloc((size_t) loglength[0]);
			glGetShaderInfoLog(shaderId, loglength[0], &len, logBuf);
			LOGE("Error Compiling Shader: %s", logBuf);
			free(logBuf);
		} else {
			LOGE("Error Compiling Shader");
		}
		glDeleteShader(shaderId);
		return 0;
	}

	return shaderId;
}