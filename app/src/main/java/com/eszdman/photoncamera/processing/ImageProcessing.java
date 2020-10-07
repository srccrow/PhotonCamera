package com.eszdman.photoncamera.processing;


import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.params.BlackLevelPattern;
import android.media.Image;
import android.util.Log;

import androidx.exifinterface.media.ExifInterface;

import com.eszdman.photoncamera.R;
import com.eszdman.photoncamera.Wrapper;
import com.eszdman.photoncamera.api.Camera2ApiAutoFix;
import com.eszdman.photoncamera.api.CameraReflectionApi;
import com.eszdman.photoncamera.api.ParseExif;
import com.eszdman.photoncamera.app.PhotonCamera;
import com.eszdman.photoncamera.processing.opengl.postpipeline.PostPipeline;
import com.eszdman.photoncamera.processing.opengl.rawpipeline.RawPipeline;
import com.eszdman.photoncamera.processing.opengl.scripts.AverageParams;
import com.eszdman.photoncamera.processing.opengl.scripts.AverageRaw;
import com.eszdman.photoncamera.processing.parameters.IsoExpoSelector;
import com.eszdman.photoncamera.settings.PreferenceKeys;
import com.eszdman.photoncamera.ui.camera.CameraFragment;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import static androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL;
import static com.eszdman.photoncamera.processing.parameters.IsoExpoSelector.baseFrame;

public class ImageProcessing {
    private static final String TAG = "ImageProcessing";
    private static ByteBuffer unlimitedBuffer;
    private final ProcessingEventsListener processingEventsListener;
    private Boolean isRaw;
    private Boolean isYuv;
    private String filePath;
    private ArrayList<Image> mImageFramesToProcess;

    public ImageProcessing(ProcessingEventsListener processingEventsListener) {
        this.processingEventsListener = processingEventsListener;
    }

    /**
     * Applies Processing algorithms to Image
     * based on image type
     */
    public void Run() {
        try {
            Camera2ApiAutoFix.ApplyRes();
            processingEventsListener.onProcessingStarted("Multi Frames Processing Started");
            if (isRaw) {
                ApplyHdrX();
            }
            if (isYuv) {
//                ApplyStabilization();
            }
            processingEventsListener.onProcessingFinished((isRaw ? "HDRX" : isYuv ? "Stablisation" : "") + " Processing Finished Successfully");
        } catch (Exception e) {
            Log.e(TAG, ProcessingEventsListener.FAILED_MSG);
            e.printStackTrace();
            processingEventsListener.onErrorOccured(ProcessingEventsListener.FAILED_MSG);
        }
    }

    public void unlimitedCycle(Image input) {
        int width = input.getPlanes()[0].getRowStride() / input.getPlanes()[0].getPixelStride();
        int height = input.getHeight();
        PhotonCamera.getParameters().rawSize = new android.graphics.Point(width, height);
        if (unlimitedBuffer == null) {
            unlimitedBuffer = input.getPlanes()[0].getBuffer().duplicate();
        }
        AverageRaw averageRaw = new AverageRaw(PhotonCamera.getParameters().rawSize, R.raw.average, "UnlimitedAvr");
        averageRaw.additionalParams = new AverageParams(unlimitedBuffer, input.getPlanes()[0].getBuffer());
        averageRaw.Run();
        unlimitedBuffer = averageRaw.Output;
        averageRaw.close();
        input.close();
    }

    public void unlimitedEnd() {
        Log.d(TAG, "Wrapper.processFrame()");
        PhotonCamera.getParameters().FillParameters(CameraFragment.mCaptureResult, CameraFragment.mCameraCharacteristics, PhotonCamera.getParameters().rawSize);
//        PhotonCamera.getParameters().path = ImageSaver.imageFileToSave.getAbsolutePath();
        PostPipeline pipeline = new PostPipeline();
        pipeline.Run(unlimitedBuffer, PhotonCamera.getParameters());
        pipeline.close();
        try {
            ExifInterface inter = ParseExif.Parse(CameraFragment.mCaptureResult, ImageSaver.imageFileToSave.getAbsolutePath());
            if (!PhotonCamera.getSettings().rawSaver) {
                try {
                    inter.saveAttributes();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        unlimitedBuffer.clear();
        processingEventsListener.onImageSaved(ImageSaver.imageFileToSave);
//        ImageSaver.triggerMediaScanner(ImageSaver.imageFileToSave);
    }

    //================================================Setters/Getters================================================

    public void setImageFramesToProcess(ArrayList<Image> mImageFramesToProcess) {
        this.mImageFramesToProcess = mImageFramesToProcess;
    }

    public void setRaw(Boolean raw) {
        isRaw = raw;
    }

    public void setYuv(Boolean yuv) {
        isYuv = yuv;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getFilePath() {
        return filePath;
    }

    //================================================Private Methods================================================

    private void ApplyHdrX() {
        boolean debugAlignment = false;
        if (PhotonCamera.getSettings().alignAlgorithm == 1) {
            debugAlignment = true;
        }
        CaptureResult res = CameraFragment.mCaptureResult;
//        processingstep();
        long startTime = System.currentTimeMillis();
        int width = mImageFramesToProcess.get(0).getPlanes()[0].getRowStride() / mImageFramesToProcess.get(0).getPlanes()[0].getPixelStride(); //mImageFramesToProcess.get(0).getWidth()*mImageFramesToProcess.get(0).getHeight()/(mImageFramesToProcess.get(0).getPlanes()[0].getRowStride()/mImageFramesToProcess.get(0).getPlanes()[0].getPixelStride());
        int height = mImageFramesToProcess.get(0).getHeight();
        Log.d(TAG, "APPLYHDRX: buffer:" + mImageFramesToProcess.get(0).getPlanes()[0].getBuffer().asShortBuffer().remaining());
        Log.d(TAG, "Api WhiteLevel:" + CameraFragment.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));
        if (!debugAlignment) {
            if (IsoExpoSelector.HDR)
                Wrapper.init(width, height, mImageFramesToProcess.size() - 2);
            else
                Wrapper.init(width, height, mImageFramesToProcess.size());
        }
        Object level = CameraFragment.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        int levell = 1023;
        if (level != null)
            levell = (int) level;
        float fakelevel = levell;//(float)Math.pow(2,16)-1.f;//bits raw
        float k = fakelevel / levell;
        CameraReflectionApi.set(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL, (int) fakelevel);
        BlackLevelPattern blevel = CameraFragment.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        int[] levelarr = new int[4];
        if (blevel != null) {
            blevel.copyTo(levelarr, 0);
            for (int i = 0; i < 4; i++) {
                levelarr[i] = (int) (levelarr[i] * k);
            }
            CameraReflectionApi.PatchBL(blevel, levelarr);
            CameraReflectionApi.set(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN, blevel);
        }
        float[] dynBL = res.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        if (dynBL != null) {
            for (int i = 0; i < dynBL.length; i++) {
                dynBL[i] *= k;
            }
            CameraReflectionApi.set(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL, dynBL, res);
        }
        Object wl = res.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        if (wl != null) {
            int wll = (int) wl;
            wl = (int) (wll * k);
            CameraReflectionApi.set(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL, wll);
        }
        Log.d(TAG, "Api WhiteLevel:" + CameraFragment.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL));
        Log.d(TAG, "Api Blacklevel:" + CameraFragment.mCameraCharacteristics.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN));
        PhotonCamera.getParameters().FillParameters(res, CameraFragment.mCameraCharacteristics, new android.graphics.Point(width, height));
        if (PhotonCamera.getParameters().realWL == -1) {
            PhotonCamera.getParameters().realWL = levell;
        }
        Log.d(TAG, "Wrapper.init");
        RawPipeline rawPipeline = new RawPipeline();
        ArrayList<ByteBuffer> images = new ArrayList<>();
        ByteBuffer lowexp = null;
        ByteBuffer highexp = null;
        for (int i = 0; i < mImageFramesToProcess.size(); i++) {
            ByteBuffer byteBuffer;
            if (i == 0) {
                byteBuffer = mImageFramesToProcess.get(baseFrame).getPlanes()[0].getBuffer();
            } else if (i == baseFrame) {
                byteBuffer = mImageFramesToProcess.get(0).getPlanes()[0].getBuffer();
            } else {
                byteBuffer = mImageFramesToProcess.get(i).getPlanes()[0].getBuffer();
            }
            if (i == 3 && IsoExpoSelector.HDR) {
                //rawPipeline.sensivity = k*0.7f;
                highexp = byteBuffer;
                continue;
            }
            if (i == 2 && IsoExpoSelector.HDR) {
                //rawPipeline.sensivity = k*6.0f;
                lowexp = byteBuffer;
                continue;
            }
            byteBuffer.position(0);
            images.add(byteBuffer);
            if (!debugAlignment)
                Wrapper.loadFrame(byteBuffer);
        }
        rawPipeline.imageobj = mImageFramesToProcess;
        rawPipeline.images = images;
        Log.d(TAG, "WhiteLevel:" + PhotonCamera.getParameters().whitelevel);
        Log.d(TAG, "Wrapper.loadFrame");
        Object sensitivity = CameraFragment.mCaptureResult.get(CaptureResult.SENSOR_SENSITIVITY);
        if (sensitivity == null) {
            sensitivity = (int) 100;
        }
        float deghostlevel = (float) Math.sqrt(((int) sensitivity) * IsoExpoSelector.getMPY() - 50.) / 16.2f;
        deghostlevel = Math.min(0.25f, deghostlevel);
        Log.d(TAG, "Deghosting level:" + deghostlevel);
        ByteBuffer output = null;
        if (!debugAlignment) {
            output = Wrapper.processFrame(0.9f + deghostlevel);
        } else
            output = rawPipeline.Run();
       /*
        if (IsoExpoSelector.HDR) {
            Wrapper.init(width,height,2);
            RawSensivity rawSensivity = new RawSensivity(new android.graphics.Point(width,height),null);
            RawParams rawParams = new RawParams(res);
            rawParams.input = mImageFramesToProcess.get(0).getPlanes()[0].getBuffer();
            rawParams.sensivity = 0.7f;
            rawSensivity.additionalParams = rawParams;
            rawSensivity.Run();
            Wrapper.loadFrame(rawSensivity.Output);
            Wrapper.loadFrame(highexp);
            highexp = Wrapper.processFrame(0.9f+deghostlevel);

            Wrapper.init(width,height,2);
            rawSensivity = new RawSensivity(new android.graphics.Point(width,height),null);
            rawParams = new RawParams(res);
            rawParams.input = mImageFramesToProcess.get(0).getPlanes()[0].getBuffer();
            rawParams.sensivity = 6.0f;
            rawSensivity.Run();
            Wrapper.loadFrame(rawSensivity.Output);
            Wrapper.loadFrame(lowexp);
            lowexp = Wrapper.processFrame(0.9f+deghostlevel);
            rawSensivity.close();
        }
        */
        //Black shot fix
        mImageFramesToProcess.get(0).getPlanes()[0].getBuffer().position(0);
        mImageFramesToProcess.get(0).getPlanes()[0].getBuffer().put(output);
        mImageFramesToProcess.get(0).getPlanes()[0].getBuffer().position(0);
        for (int i = 1; i < mImageFramesToProcess.size(); i++) {
            if ((i == 3 || i == 2) && IsoExpoSelector.HDR)
                continue;
            mImageFramesToProcess.get(i).close();
        }
        if (debugAlignment)
            rawPipeline.close();
        Log.d(TAG, "HDRX Alignment elapsed:" + (System.currentTimeMillis() - startTime) + " ms");
        if (PhotonCamera.getSettings().rawSaver) {
            saveRaw(mImageFramesToProcess.get(0));
            return;
        }
        Log.d(TAG, "Wrapper.processFrame()");
//        PhotonCamera.getParameters().path = path;
        PostPipeline pipeline = new PostPipeline();
        pipeline.lowFrame = lowexp;
        pipeline.highFrame = highexp;
        pipeline.Run(mImageFramesToProcess.get(0).getPlanes()[0].getBuffer(), PhotonCamera.getParameters());
        pipeline.close();
        mImageFramesToProcess.get(0).close();
    }


    private void ProcessRaw(ByteBuffer input) {
        if (PhotonCamera.getSettings().rawSaver) {
            saveRaw(mImageFramesToProcess.get(0));
            return;
        }
        Log.d(TAG, "Wrapper.processFrame()");
//        PhotonCamera.getParameters().path = path;
        PostPipeline pipeline = new PostPipeline();
        pipeline.Run(mImageFramesToProcess.get(0).getPlanes()[0].getBuffer(), PhotonCamera.getParameters());
        pipeline.close();
        mImageFramesToProcess.get(0).close();
    }

    private void saveRaw(Image in) {
        DngCreator dngCreator = new DngCreator(CameraFragment.mCameraCharacteristics, CameraFragment.mCaptureResult);
        try {
            FileOutputStream outB = new FileOutputStream(ImageSaver.imageFileToSave);
            dngCreator.setDescription(PhotonCamera.getParameters().toString());
            int rotation = PhotonCamera.getGravity().getCameraRotation();
            Log.d(TAG, "Gravity rotation:" + PhotonCamera.getGravity().getRotation());
            Log.d(TAG, "Sensor rotation:" + PhotonCamera.getCameraFragment().mSensorOrientation);
            int orientation = ORIENTATION_NORMAL;
            switch (rotation) {
                case 90:
                    orientation = ExifInterface.ORIENTATION_ROTATE_90;
                    break;
                case 180:
                    orientation = ExifInterface.ORIENTATION_ROTATE_180;
                    break;
                case 270:
                    orientation = ExifInterface.ORIENTATION_ROTATE_270;
                    break;
            }
            dngCreator.setOrientation(orientation);
            dngCreator.writeImage(outB, in);
            in.close();
            outB.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //final ORB orb = ORB.create();
    //final DescriptorMatcher matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING);
    /*Mat findFrameHomography(Mat need, Mat from) {
        Mat descriptors1 = new Mat(), descriptors2 = new Mat();
        MatOfKeyPoint keyPoints1 = new MatOfKeyPoint();
        MatOfKeyPoint keyPoints2 = new MatOfKeyPoint();
        orb.detectAndCompute(need, new Mat(), keyPoints1, descriptors1);
        orb.detectAndCompute(from, new Mat(), keyPoints2, descriptors2);
        MatOfDMatch matches = new MatOfDMatch();
        matcher.match(descriptors1, descriptors2, matches, new Mat());
        MatOfPoint2f points1 = new MatOfPoint2f(), points2 = new MatOfPoint2f();
        DMatch[] arr = matches.toArray();
        List<KeyPoint> keypoints1 = keyPoints1.toList();
        List<KeyPoint> keypoints2 = keyPoints2.toList();
        ArrayList<Point> keypoints1f = new ArrayList<Point>();
        ArrayList<Point> keypoints2f = new ArrayList<Point>();
        for (DMatch dMatch : arr) {
            Point on1 = keypoints1.get(dMatch.queryIdx).pt;
            Point on2 = keypoints2.get(dMatch.trainIdx).pt;
            if (dMatch.distance < 50) {
                keypoints1f.add(on1);
                keypoints2f.add(on2);
            }
        }
        points1.fromArray(keypoints1f.toArray(new Point[0]));
        points2.fromArray(keypoints2f.toArray(new Point[0]));
        Mat h = null;
        if (!points1.empty() && !points2.empty()) h = findHomography(points2, points1, RANSAC);
        keyPoints1.release();
        keyPoints2.release();

        return h;
    }*/



    /*void processingstep() {
        processingEventsListener.onProcessingChanged(null);
    }*/
}