package nl.knaw.huc.di.images.imageanalysiscommon;

import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.opencv.core.CvType.CV_8UC3;

public class Histogram {
    public Mat createCombinedRGBHistogram(Mat first, Mat second) {
        Mat firstHisto = createRGBHistogram(first);
        Mat secondHisto = createRGBHistogram(second);

        Mat combined = new Mat();
        List<Mat> imagesToConcat = Arrays.asList(firstHisto, secondHisto);
        Core.vconcat(imagesToConcat, combined);
        return combined;

    }

    public Mat createRGBHistogram(Mat src) {
        Mat srcRefGray = new Mat();
        Mat srcRefMask = new Mat();
        Mat histRef = new Mat();
        List<Mat> bgr_planes = new ArrayList<>();
        MatOfInt histSize = new MatOfInt(256, 256, 256);
        MatOfFloat ranges = new MatOfFloat(0f, 255f, 0f, 255f, 0f, 255f);
        MatOfInt channels = new MatOfInt(0, 1, 2);
        ArrayList<Mat> histImages = new ArrayList<>();
        histImages.add(src);
        Mat histImageb = null;
        Mat histImageg = null;
        Mat histImager = null;
        try {
            Imgproc.cvtColor(src, srcRefGray, Imgproc.COLOR_RGB2GRAY);
            Imgproc.threshold(srcRefGray, srcRefMask, 127, 255, Imgproc.THRESH_OTSU);
            Mat white = new Mat(srcRefMask.rows(), srcRefMask.cols(), srcRefMask.type(), new Scalar(255, 255, 255));
            try {
                Core.subtract(white, srcRefMask, srcRefMask);
            } finally {
                white.release();
            }

            Imgproc.calcHist(histImages, channels, srcRefMask, histRef, histSize, ranges, false);
            Core.split(src, bgr_planes);

            List<Mat> bPlane = new ArrayList<>();
            bPlane.add(bgr_planes.get(0));
            List<Mat> gPlane = new ArrayList<>();
            gPlane.add(bgr_planes.get(1));
            List<Mat> rPlane = new ArrayList<>();
            rPlane.add(bgr_planes.get(2));

            histImageb = getHistImage(srcRefMask, 256, bPlane);
            histImageg = getHistImage(srcRefMask, 256, gPlane);
            histImager = getHistImage(srcRefMask, 256, rPlane);

            Mat concatted = new Mat();
            Core.hconcat(Arrays.asList(histImager, histImageg, histImageb), concatted);
            return concatted;
        } finally {
            srcRefGray.release();
            srcRefMask.release();
            histRef.release();
            histSize.release();
            ranges.release();
            channels.release();
            for (Mat mat : bgr_planes) {
                mat.release();
            }
            if (histImageb != null) {
                histImageb.release();
            }
            if (histImageg != null) {
                histImageg.release();
            }
            if (histImager != null) {
                histImager.release();
            }
        }
    }

    public Mat getHistImage(Mat srcRefMask, int bins, List<Mat> bPlane) {
        Mat bHist = new Mat();
        MatOfInt histSize = new MatOfInt(bins);
        MatOfFloat histRange = new MatOfFloat(0, 255);
        MatOfInt channels = new MatOfInt(0);

        try {
            /// Compute the histograms:
            Imgproc.calcHist(bPlane, channels, srcRefMask, bHist, histSize, histRange, true);

            int hist_w = 512;
            int hist_h = 400;
            int bin_w = (hist_w / bins);

            Mat histImageb = Mat.zeros(hist_h, hist_w, CV_8UC3);
            Mat emptyMask = new Mat();
            try {
                /// Normalize the result to [0, histImage.rows ]
                Core.normalize(bHist, bHist, 0, histImageb.rows(), Core.NORM_MINMAX, -1, emptyMask);
            } finally {
                emptyMask.release();
            }

            /// Draw for each channel
            for (int i = 0; i < bins; i++) {
                if (bHist.get(i, 0) != null) {
                    Imgproc.line(histImageb,
                            new Point(bin_w * (i), histImageb.height() - 1),
                            new Point(bin_w * (i), hist_h - bHist.get(i, 0)[0]),
                            new Scalar(255, 0, 0), 2, 8, 0);
                }
            }
            return histImageb;
        } finally {
            bHist.release();
            histSize.release();
            histRange.release();
            channels.release();
        }
    }
}
