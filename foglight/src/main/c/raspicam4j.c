#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
#include <getopt.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <malloc.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/time.h>
#include <time.h>
#include <sys/mman.h>
#include <sys/ioctl.h>
#include <asm/types.h>
#include <linux/videodev2.h>
#include <libv4l2.h>
#include <signal.h>
#include <stdint.h>
#include <inttypes.h>
#include <jni.h>

// Define booleans.
#define true (1==1)
#define false (!true)

// Utility to get current time in milliseconds.
// Courtesy of: https://stackoverflow.com/a/17083824
long long currentTimeMillis() {
    struct timeval te;
    gettimeofday(&te, NULL);
    long long milliseconds = te.tv_sec*1000LL + te.tv_usec/1000;
    return milliseconds;
}

// Shared buffer info across all calls.
// TODO: If open is called multiple times, the behavior of this variable will be undefined.
struct v4l2_buffer bufferinfo;
void* buffer_start;
int buffer_size = -1;
int buffering = false;

JNIEXPORT jint JNICALL Java_com_ociweb_iot_camera_RaspiCam_open(JNIEnv *env, jobject object, jstring device, jint width, jint height) {
    const char *actualDevice = (*env)->GetStringUTFChars(env, device, NULL);
    int fd = v4l2_open(actualDevice, O_RDWR | O_NONBLOCK, 0);
    if (fd < 0) {
        fprintf(stderr, "Could not open video feed at: %s.\n", actualDevice);
        return -1; // TODO: More descriptive error?
    } else {

        // Clear and setup pixel format.
        struct v4l2_format format;
        memset(&(format), 0, sizeof(format));
        format.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        format.fmt.pix.pixelformat = V4L2_PIX_FMT_RGB24;
        format.fmt.pix.width = width;
        format.fmt.pix.height = height;

        // Configure image format.
        if (v4l2_ioctl(fd, VIDIOC_S_FMT, &format) < 0) {
            v4l2_close(fd);
            fprintf(stderr, "Could not configure desired image format.\n");
            return -1; // TODO: More descriptive error?
        }

        // Validate formatting.
        if (format.fmt.pix.pixelformat != V4L2_PIX_FMT_RGB24) {
            fprintf(stderr, "Camera used %d format instead of %d.\n", format.fmt.pix.pixelformat, V4L2_PIX_FMT_RGB24);
        }

        if (format.fmt.pix.width != width) {
            fprintf(stderr, "Camera used %d width instead of %d.\n", format.fmt.pix.width, width);
        }

        if (format.fmt.pix.height != height) {
            fprintf(stderr, "Camera used %d height instead of %d.\n", format.fmt.pix.height, height);
        }

        if (format.fmt.pix.bytesperline != width * 3) {
        	fprintf(stderr, "Camera used %d bytes per line instead of the expected %d.\n", format.fmt.pix.bytesperline, width * 3);
        }

        // Get buffer size based on W x H.
        buffer_size = format.fmt.pix.sizeimage;

        // Setup buffer format.
        struct v4l2_requestbuffers bufrequest;
        bufrequest.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        bufrequest.memory = V4L2_MEMORY_MMAP;
        bufrequest.count = 1;

        // Configure buffer format.
        if (v4l2_ioctl(fd, VIDIOC_REQBUFS, &bufrequest) < 0) {
            v4l2_close(fd);
            fprintf(stderr, "Could not request buffers.\n");
            return -1; // TODO: More descriptive error?
        }

        // Allocate buffers.
        memset(&bufferinfo, 0, sizeof(bufferinfo));

        bufferinfo.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        bufferinfo.memory = V4L2_MEMORY_MMAP;
        bufferinfo.index = 0;

        // Configure buffers.
        if (v4l2_ioctl(fd, VIDIOC_QUERYBUF, &bufferinfo) < 0) {
            v4l2_close(fd);
            fprintf(stderr, "Could not configure query buffers.\n");
            return -1; // TODO: More descriptive error?
        }

        // Memory map buffers.
        buffer_start = v4l2_mmap(
            NULL,
            bufferinfo.length,
            PROT_READ | PROT_WRITE,
            MAP_SHARED,
            fd,
            bufferinfo.m.offset
        );

        // Validate memory map succeeded.
        if (buffer_start == MAP_FAILED) {
            v4l2_close(fd);
            fprintf(stderr, "Could not memory map camera.\n");
            return -1; // TODO: More descriptive error?
        }

        // Clean struct.
        memset(buffer_start, 0, bufferinfo.length);

        // Put the buffer in the incoming queue.
        if (v4l2_ioctl(fd, VIDIOC_QBUF, &bufferinfo) < 0) {
            fprintf(stderr, "Could not queue buffer (during open).\n");
            return -1; // TODO: More descriptive error?
        } else {
            buffering = true;
        }

        // Activate streaming
        if (v4l2_ioctl(fd, VIDIOC_STREAMON, &bufferinfo.type) < 0) {
            v4l2_close(fd);
            fprintf(stderr, "Could not activate streaming.\n");
            return -1; // TODO: More descriptive error?
        }

        // Return descriptor.
        return fd;
    }
}

JNIEXPORT jobject JNICALL Java_com_ociweb_iot_camera_RaspiCam_getFrameBuffer(JNIEnv *env, jobject object, jint fd) {
    return (*env)->NewDirectByteBuffer(env, buffer_start, buffer_size);
}

JNIEXPORT jlong JNICALL Java_com_ociweb_iot_camera_RaspiCam_readFrame(JNIEnv *env, jobject object, jint fd) {

    // Put the buffer in the incoming queue if we are not currently buffering.
    // We do not automatically re-insert the buffer into the camera's queue
    // in order to prevent the user's data from being overwritten before they
    // are ready to read the next frame.
    if (!buffering) {
        if (v4l2_ioctl(fd, VIDIOC_QBUF, &bufferinfo) < 0) {
            fprintf(stderr, "Could not queue buffer (during read).\n");
        } else {
            buffering = true;
        }
    }

    // Read data from the buffer in the incoming queue.
    // If -1 is returned, the buffer isn't ready to read yet.
    // The buffer most likely will not be ready to read immediately.
    if (buffering) {
        memset(&(bufferinfo), 0, sizeof(bufferinfo));
        bufferinfo.type = V4L2_BUF_TYPE_VIDEO_CAPTURE;
        bufferinfo.memory = V4L2_MEMORY_USERPTR;
        if (v4l2_ioctl(fd, VIDIOC_DQBUF, &bufferinfo) < 0) {
            if (errno != EAGAIN) {
                fprintf(stderr, "Unknown error code %d when reading frame from camera.");
            }

            return -1;
        } else {
            buffering = false;
        }

        return currentTimeMillis();
    }

    // If we either can't queue the buffer or we simply aren't buffering, return.
    return -1;
}

JNIEXPORT jint JNICALL Java_com_ociweb_iot_camera_RaspiCam_close(JNIEnv *env, jobject object, jint fd) {

    // Deactivate streaming
    if (v4l2_ioctl(fd, VIDIOC_STREAMOFF, &bufferinfo.type) < 0){
        return -1; // TODO: More descriptive error?
    } else {
        v4l2_close(fd);
    }
}