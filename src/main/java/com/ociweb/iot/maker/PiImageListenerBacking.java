package com.ociweb.iot.maker;

import java.io.File;
import java.io.IOException;

/**
 * Time-based image listener backing for Raspberry Pi hardware.
 *
 * @author Brandon Sanders [brandon@alicorn.io]
 */
public class PiImageListenerBacking implements TimeListener {

    private final ImageListener handler;

    /**
     * Takes a picture from the currently connected Raspberry Pi camera and
     * saves it to a file.
     *
     * TODO: This method is primitive in that it relies on Pi command line tools
     *       and unknown delays in order to capture images. It would be preferable
     *       to leverage the native APIs in the Raspberry Pi, but those are
     *       extremely complex and undocumented...
     *
     * @param fileName Name of the file (without extensions) to save
     *                 the image to.
     *
     * @return A reference to the created image file.
     */
    public static File takePicture(String fileName) {
        try {
            Runtime.getRuntime().exec("raspistill --nopreview --timeout 1 --shutter 2500 --width 1280 --height 960 --quality 75 --output " + fileName + ".jpg");
        } catch (IOException e) {
            e.printStackTrace();
        }

        return new File(fileName + ".jpg");
    }

    public PiImageListenerBacking(ImageListener handler) {
        this.handler = handler;
    }

    @Override
    public void timeEvent(long time) {
        File f = takePicture("Pronghorn-Image-Capture-" + time);
        handler.onImage(f);
    }
}
