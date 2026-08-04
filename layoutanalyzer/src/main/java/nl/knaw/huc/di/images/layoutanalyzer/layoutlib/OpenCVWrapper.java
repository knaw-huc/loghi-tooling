package nl.knaw.huc.di.images.layoutanalyzer.layoutlib;

import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.opencv.core.CvType.*;

// This wrapper class provides methods from opencv. Where new Mat() is synchronized to prevent concurrent access issues.
// It also provides methods to release Mats and MatOfInt, MatOfPoint, MatOfPoint2f.
// It also provides methods with extra checks to ensure preallocated Mats to perform common operations like bitwise_not, warpAffine, adaptiveThreshold, addWeighted, GaussianBlur, cvtColor, merge, line, Sobel, threshold, imread and resize.
public class OpenCVWrapper {
    private static final Logger LOG = LoggerFactory.getLogger(OpenCVWrapper.class);


    public static synchronized Mat newMat() {
        Mat newMat = new Mat();
        return newMat;
    }
    public static synchronized Mat newMat(Size size, int cvType) {
        Mat newMat = new Mat(size, cvType);
        return newMat;
    }

    public static synchronized Mat newMat(Size size, int cvType, Scalar scalar) {
        Mat newMat = new Mat(size, cvType, scalar);
        return newMat;
    }
    public static synchronized Mat newMat(int rows, int cols, int cvType) {
        Mat newMat = new Mat(rows, cols, cvType);
        return newMat;
    }

    public static synchronized Mat zeros(Size size, int cvType) {
        Mat newMat = Mat.zeros(size, cvType);
        return newMat;
    }


    public static synchronized Mat release(Mat mat) {
        if (mat != null) {
            if (mat.dataAddr() == 0) {
                LOG.error("Mat is already released. Calling release on already released Mat.");
                throw new RuntimeException("Mat is already released. Calling release on already released Mat.");
            }
            long address = mat.dataAddr();
            LOG.debug("Releasing Mat with size: {} and type: {} and channels: {} and address: {}", mat.size(), mat.type(), mat.channels(), address);
            mat.release();
            if (mat.dataAddr() != 0 ) {
                LOG.error("Mat is not released properly. Address is still: {}", mat.dataAddr());
                throw new RuntimeException("Mat is not released properly. Address is still: " + mat.dataAddr());
            }
            LOG.debug("Mat released successfully. Address: {}", address);

        }else{
            LOG.error("Mat is already null. Calling release on null Mat.");
            throw new RuntimeException("Mat is already null. Calling release on null Mat.");
        }
        return null;
    }

    public static synchronized MatOfInt release(MatOfInt mat) {
        if (mat != null) {
            if (mat.dataAddr()== 0) {
                LOG.error("MatOfInt is already released. Calling release on already released Mat.");
                throw new RuntimeException("MatOfInt is already released. Calling release on already released Mat.");
            }
            long address = mat.dataAddr();
            LOG.debug("Releasing MatOfInt with size: {} and type: {} and address: {}", mat.size(), mat.type(), address);
            mat.release();
            LOG.debug("MatOfInt released successfully. Address: {}", address);
        }else{
            LOG.error("MatOfInt is already null. Calling release on null MatOfInt.");
        }
        return null;
    }

    public static synchronized MatOfPoint release(MatOfPoint mat) {
        if (mat != null) {
            if (mat.dataAddr() == 0) {
                LOG.error("MatOfPoint is already released. Calling release on already released Mat.");
                throw new RuntimeException("MatOfPoint is already released. Calling release on already released Mat.");
            }
            long address = mat.dataAddr();
            LOG.debug("Releasing MatOfPoint with size: {} and type: {} and address: {}", mat.size(), mat.type(), address);
            mat.release();
            LOG.debug("MatOfPoint released successfully. Address: {}", address);
        }else{
            LOG.error("MatOfPoint is already null. Calling release on null MatOfPoint.");
        }
        return null;
    }

    public static synchronized MatOfPoint2f release(MatOfPoint2f mat) {
        if (mat != null) {
            if (mat.dataAddr()== 0) {
                LOG.error("MatOfPoint2f is already released. Calling release on already released Mat.");
                throw new RuntimeException("MatOfPoint2f is already released. Calling release on already released Mat.");
            }
            long address = mat.dataAddr();
            LOG.debug("Releasing MatOfPoint2f with size: {} and type: {} and address: {}", mat.size(), mat.type(), address);
            mat.release();
            LOG.debug("MatOfPoint2f released successfully. Address: {}", address);
        }else{
            LOG.error("MatOfPoint2f is already null. Calling release on null MatOfPoint2f.");
        }
        return null;
    }

    public static void bitwise_not(Mat input, Mat destination) {
        if (input == null) {
            LOG.error("Input is null. ");
            throw new RuntimeException("Input is null.");
        }
        if (destination == null) {
            LOG.error("Destination is null.");
            throw new RuntimeException("Destination is null.");
        }
        if (input.type() != CV_8UC1){
            LOG.error("Input is not a valid type.");
            throw new RuntimeException("Input is not a valid type.");
        }
        if (input.size().width != destination.size().width || input.size().height != destination.size().height) {
            LOG.error("Input and destination sizes do not match.");
            throw new IllegalArgumentException("Input and destination sizes do not match.");
        }

        Core.bitwise_not(input, destination);
    }

    public static void warpAffine(Mat input, Mat destination, Mat rotationMat, Size newSize) {
        if (input == null) {
            LOG.error("Input is null.");
            throw new IllegalArgumentException("Input is null.");
        }
        if (rotationMat == null) {
            LOG.error("Rotation matrix is null.");
            throw new IllegalArgumentException("Rotation matrix is null.");
        }
        if (newSize == null) {
            LOG.error("New size is null.");
            throw new IllegalArgumentException("New size is null.");
        }
        if (destination == null) {
            LOG.error("Destination is null.");
            throw new IllegalArgumentException("Destination is null.");
        }
        if (newSize.width != destination.size().width || newSize.height != destination.size().height) {
            LOG.error("NewSize and destination sizes do not match.");
            throw new IllegalArgumentException("Input and destination sizes do not match.");
        }

        Imgproc.warpAffine(input, destination, rotationMat, newSize, Imgproc.INTER_LINEAR);
    }

    public static void adaptiveThreshold(Mat input, Mat destination, int blocksize) {
        if (input == null) {
            LOG.error("Input is null.");
            throw new IllegalArgumentException("Input is null.");
        }
        if (input.size().width != destination.size().width || input.size().height != destination.size().height) {
            LOG.error("Input and destination sizes do not match.");
            throw new IllegalArgumentException("Input and destination sizes do not match.");
        }
        LOG.debug("Applying adaptive threshold with blocksize: {}", blocksize);
        Imgproc.adaptiveThreshold(input, destination, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, blocksize, 15);
        LOG.debug("Adaptive threshold applied successfully.");
    }


//    public static synchronized void addWeighted(Mat input1, Mat input2, Mat destination) {
//        if (input1 == null) {
//            LOG.error("Input1 is null.");
//            throw new IllegalArgumentException("Input1 is null.");
//        }
//        if (input2 == null) {
//            LOG.error("Input2 is null.");
//            throw new IllegalArgumentException("Input2 is null.");
//        }
//        if (input1.size().equals(input2.size())) {
//            Core.addWeighted(input1, 0.5, input2, 0.5, 0.0, destination);
//        } else {
//            LOG.error("Matrices are not the same size.");
//            throw new IllegalArgumentException("Matrices are not the same size.");
//        }
//    }

    public static synchronized void addWeighted(Mat input1, Mat input2, Mat destination, int dtype) {
        if (input1 == null) {
            LOG.error("Input1 is null.");
            throw new IllegalArgumentException("Input1 is null.");
        }
        if (input2 == null) {
            LOG.error("Input2 is null.");
            throw new IllegalArgumentException("Input2 is null.");
        }
        if (input1.size().equals(input2.size())) {
            Core.addWeighted(input1, 0.5, input2, 0.5, 0.0, destination, dtype);
        } else {
            LOG.error("Matrices are not the same size.");
            throw new IllegalArgumentException("Matrices are not the same size.");
        }
    }

    public static void GaussianBlur(Mat input1, Mat destination, Size size, int sigmaX) {
        if (input1 == null) {
            LOG.error("Input1 is null.");
            throw new IllegalArgumentException("Input1 is null.");
        }
        if (destination == null) {
            LOG.error("Destination is null.");
            throw new IllegalArgumentException("Destination is null.");
        }
        if (size == null || size.width <= 0 || size.height <= 0) {
            LOG.error("Size is invalid.");
            throw new IllegalArgumentException("Size is invalid.");
        }
        if (input1.size().width != destination.size().width || input1.size().height != destination.size().height) {
            LOG.error("Input and destination sizes do not match.");
            throw new IllegalArgumentException("Input and destination sizes do not match.");
        }
        LOG.debug("Applying Gaussian Blur with size: {} and sigmaX: {}", size, sigmaX);
        Imgproc.GaussianBlur(input1, destination, size, sigmaX);
        LOG.debug("Gaussian Blur applied successfully.");
    }

    public static void GaussianBlur(Mat input1, Mat destination) {
        GaussianBlur(input1, destination, new Size(5, 5), 0);
    }

    public static void cvtColor(Mat input, Mat grayImage) {
        if (input.size().width != grayImage.size().width || input.size().height != grayImage.size().height) {
            LOG.error("Input and grayImage sizes do not match.");
            throw new IllegalArgumentException("Input and grayImage sizes do not match.");
        }
        if (grayImage.type() != CV_8UC1) {
            LOG.error("Gray image must be of type CV_8UC1.");
            throw new IllegalArgumentException("Gray image must be of type CV_8UC1.");
        }
        Imgproc.cvtColor(input, grayImage, Imgproc.COLOR_BGR2GRAY);
    }


    public static synchronized void merge(List<Mat> toMerge, Mat destination) {
        int cvtype;
        if (toMerge.size() == 3) {
            cvtype = CV_8UC3;
        } else if (toMerge.size()==4) {
            cvtype = CV_8UC4;
        } else {
            throw new IllegalArgumentException("Can only merge 3 or 4 Mats");
        }
        int initialType = toMerge.get(0).type();
        for (Mat mat : toMerge) {
            if (mat.type() != CV_8UC1) {
                throw new IllegalArgumentException("All Mats must be CV_8UC1");
            }
            if (mat.type() != initialType) {
                throw new IllegalArgumentException("All Mats must have the same type");
            }
        }
        Core.merge(toMerge, destination);
    }

    public static void line(String identifier, Mat image, Point point1, Point point2, Scalar scalar, int thickness) {
        if (image == null) {
            LOG.error(identifier + " Image is null. Not drawing line.");
            return;
        }
        if (point1 == null) {
            LOG.error(identifier + " Point1 is null. Not drawing line.");
            return;
        }
        if (point2 == null) {
            LOG.error(identifier + " Point2 is null. Not drawing line.");
            return;
        }
        if (scalar == null) {
            LOG.error(identifier + " Scalar is null. Not drawing line.");
            return;
        }
        if (point1.x < 0 || point1.y < 0 || point2.x < 0 || point2.y < 0) {
            LOG.error(identifier+ " Point is negative. Not drawing line.");
            return;
        }
        if (point1.x >= image.width() || point1.y >= image.height() || point2.x >= image.width() || point2.y >= image.height()) {
            LOG.error(identifier+ " Point is outside image. Not drawing line. Image size: " + image.size() + ", point1: " + point1 + ", point2: " + point2);
            return;
        }
        Imgproc.line(image, point1, point2, scalar, thickness);
    }

    public static void line(Mat image, Point point1, Point point2) {
        Imgproc.line(image, point1, point2, new Scalar(255), 5);
    }

    public static void Sobel(Mat input, int ddepth, int dx, int dy, int ksize, int scale, int delta, Mat destination) {
        if (input.size() == null || input.size().width <= 0 || input.size().height <= 0) {
            LOG.error("Input size is invalid. Cannot apply Sobel filter.");
            throw new IllegalArgumentException("Input size is invalid. Cannot apply Sobel filter.");
        }
        if (destination == null) {
            LOG.error("Destination is null. Cannot apply Sobel filter.");
            throw new IllegalArgumentException("Destination is null. Cannot apply Sobel filter.");
        }
        if (input.type() != CV_8UC1 && input.type() != CV_8UC3) {
            LOG.error("Input type is not supported. Cannot apply Sobel filter.");
            throw new IllegalArgumentException("Input type is not supported. Cannot apply Sobel filter.");
        }
//        if (ddepth != CV_8U && ddepth != CV_16S && ddepth != CV_32F) {
//            LOG.error("ddepth is not supported. Cannot apply Sobel filter.");
//            throw new IllegalArgumentException("ddepth is not supported. Cannot apply Sobel filter.");
//        }
        if (input.size().width != destination.size().width || input.size().height != destination.size().height) {
            LOG.error("Input and destination sizes do not match. Cannot apply Sobel filter.");
            throw new IllegalArgumentException("Input and destination sizes do not match. Cannot apply Sobel filter.");
        }
        Imgproc.Sobel(input, destination, ddepth, dx, dy, ksize, scale, delta);
    }

    public static void threshold(Mat input, Mat output, int threshold, boolean invert) {
        if (input.size().width != output.size().width || input.size().height != output.size().height) {
            LOG.error("Input and output sizes do not match. Cannot apply threshold.");
            throw new IllegalArgumentException("Input and output sizes do not match. Cannot apply threshold.");
        }
        if (invert) {
            Imgproc.threshold(input, output, threshold, 255, Imgproc.THRESH_BINARY_INV);
        } else {
            Imgproc.threshold(input, output, threshold, 255, Imgproc.THRESH_BINARY);
        }
        if (output.type() != CV_8UC1) {
            LOG.error("Output type is not CV_8UC1. Something is wrong.");
            throw new IllegalArgumentException("Output type is not CV_8UC1. Something is wrong.");
        }

    }

    public static Mat imread(String filePath, int flags) {
        if (filePath == null || filePath.isEmpty()) {
            LOG.error("File path is null or empty. Cannot read image.");
            throw new IllegalArgumentException("File path is null or empty. Cannot read image.");
        }
        Mat destination = newMat();
        if (destination == null) {
            LOG.error("Destination Mat is null. Cannot read image.");
            throw new IllegalArgumentException("Destination Mat is null. Cannot read image.");
        }
        Imgcodecs.imread(filePath, destination, flags);
        return destination;
    }

    /**
     * Synchronised, leak-tracked equivalent of {@link Imgcodecs#imread(String)}.
     * <p>
     * Always pre-allocates the destination {@link Mat} through {@link #newMat()},
     * so the returned object is always non-null and goes through the same
     * synchronisation lock as other allocations. Ownership of the returned
     * {@code Mat} is transferred to the caller — the caller must release it via
     * {@link #release(Mat)} (or in a {@code finally} block / try-with-resources
     * wrapper such as {@link MatScope}).
     *
     * @param filePath file system path to the image file
     * @return a {@link Mat} that may be {@code empty()} if OpenCV could not
     * decode the file (callers should check this before use)
     */
    public static Mat imread(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            LOG.error("File path is null or empty. Cannot read image.");
            throw new IllegalArgumentException("File path is null or empty. Cannot read image.");
        }
        Mat destination = newMat();
        Imgcodecs.imread(filePath, destination);
        return destination;
    }

    public static void resize(Mat input, Mat destination, Size newSize) {
        if (input == null) {
            LOG.error("Input is null. Cannot resize image.");
            throw new IllegalArgumentException("Input is null. Cannot resize image.");
        }
        if (destination == null) {
            LOG.error("Destination is null. Cannot resize image.");
            throw new IllegalArgumentException("Destination is null. Cannot resize image.");
        }
        if (newSize == null) {
            LOG.error("New size is null. Cannot resize image.");
            throw new IllegalArgumentException("New size is null. Cannot resize image.");
        }
        if (newSize.width <= 0 || newSize.height <= 0) {
            LOG.error("New size is invalid. Cannot resize image.");
            throw new IllegalArgumentException("New size is invalid. Cannot resize image.");
        }
        if ((int)newSize.width != destination.width() || (int)newSize.height != destination.height()) {
            LOG.error("Destination size does not match new size. Cannot resize image.");
            throw new IllegalArgumentException("Destination size ("+ destination.size() +") does not match new size ("+ newSize +"). Cannot resize image.");
        }
        Imgproc.resize(input, destination, newSize);
    }


    public static int connectedComponentsWithStats(Mat input, Mat labeled, Mat stats, Mat centroids) {
        return connectedComponentsWithStats(input, labeled, stats, centroids, 8);
    }

    public static int connectedComponentsWithStats(Mat input, Mat labeled, Mat stats, Mat centroids, int connectivity) {
        return connectedComponentsWithStats(input, labeled, stats, centroids, connectivity, CV_32S);
    }

    public static int connectedComponentsWithStats(Mat input, Mat labeled, Mat stats, Mat centroids, int connectivity, int type) {
        if (input == null) {
            LOG.error("Input is null. Cannot perform connected components analysis.");
            throw new IllegalArgumentException("Input is null. Cannot perform connected components analysis.");
        }
        if (labeled == null) {
            LOG.error("Labels Mat is null. Cannot perform connected components analysis.");
            throw new IllegalArgumentException("Labels Mat is null. Cannot perform connected components analysis.");
        }
        if (stats == null) {
            LOG.error("Stats Mat is null. Cannot perform connected components analysis.");
            throw new IllegalArgumentException("Stats Mat is null. Cannot perform connected components analysis.");
        }
        if (centroids == null) {
            LOG.error("Centroids Mat is null. Cannot perform connected components analysis.");
            throw new IllegalArgumentException("Centroids Mat is null. Cannot perform connected components analysis.");
        }
        if (connectivity != 4 && connectivity != 8) {
            LOG.error("Connectivity must be either 4 or 8. Cannot perform connected components analysis.");
            throw new IllegalArgumentException("Connectivity must be either 4 or 8. Cannot perform connected components analysis.");
        }
        if (type != CV_32S && type != CV_16U) {
            LOG.error("Type must be either CV_32S or CV_16U. Cannot perform connected components analysis.");
            throw new IllegalArgumentException("Type must be either CV_32S or CV_16U. Cannot perform connected components analysis.");
        }
        if (labeled.type() != CV_16U && labeled.type() != CV_32S) {
            LOG.error("Labels Mat must be of type CV_16U or CV_32S. Cannot perform connected components analysis.");
            throw new IllegalArgumentException("Labels Mat must be of type CV_16U or CV_32S. Cannot perform connected components analysis.");
        }
        if (labeled.size().width != input.size().width || labeled.size().height != input.size().height) {
            LOG.error("Labels Mat size does not match input size. Cannot perform connected components analysis.");
            throw new IllegalArgumentException("Labels Mat size does not match input size. Cannot perform connected components analysis.");
        }
        return Imgproc.connectedComponentsWithStats(input, labeled, stats, centroids, connectivity, type);
    }

    public static synchronized MatOfPoint2f newMatOfPoint2f() {
        return new MatOfPoint2f();
    }

    public static synchronized MatOfPoint newMatOfPoint() {
        return new MatOfPoint();
    }

    public static synchronized Mat newMatCV_64F(Size size, Scalar mean) {
        return new Mat(size, CV_64F, mean);
    }
}
