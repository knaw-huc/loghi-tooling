package nl.knaw.huc.di.images.layoutanalyzer;

import nl.knaw.huc.di.images.imageanalysiscommon.connectedComponent.ConnectedComponentProc;
import nl.knaw.huc.di.images.imageanalysiscommon.imageConversion.ImageConversionHelper;
import nl.knaw.huc.di.images.imageanalysiscommon.model.ComposedBlock;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.OpenCVWrapper;
import nl.knaw.huc.di.images.layoutds.models.DocumentTextBlock;
import nl.knaw.huc.di.images.layoutds.models.connectedComponent.ConnectedComponent;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.Transient;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.opencv.core.Core.absdiff;
import static org.opencv.core.Core.countNonZero;
import static org.opencv.core.CvType.CV_32FC3;
import static org.opencv.core.CvType.CV_8U;

public class DocumentPage implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentPage.class);

    private final Mat image;
    private List<Integer> otsuProfileVertical = null;
    private List<Integer> otsuProfileHorizontal = null;
    private Mat binaryOtsu = null;
    private Mat grayImage;
    private Mat binaryImage;
    private Mat binaryImageCrude;
    private Mat despeckledImage;
    private Mat horizontalWhitespaceImage;
    private Mat verticalWhitespaceImage;
    private Mat verticalInkImage;
    private Mat horizontalInkImage;
    private Mat textOnlyImage;
    private Mat cannyImage;
    private Mat colorizedImage;

    private ArrayList<ComposedBlock> composedBlocks;
    private List<DocumentTextBlock> textBlocks;
    private int topMargin = -1;
    private int bottomMargin = -1;
    private int leftMargin = -1;
    private int rightMargin = -1;
    private Double inkRatio = null;
    private int height;
    private int width;
    private List<Double> verticalProfileWithinMarginsSmooth;
    private List<Double> verticalProfileSmooth;
    private ArrayList<Double> differenceVerticalSmooth;

    private java.util.List<ConnectedComponent> cocos;
    private int xHeight = 0;
    private String filename;
    private String fullText = "";
    private String pageNumber;
    private String fullTextSparse;
    @Transient
    private String layoutResult;

    Integer getCenterFold() {
        return centerFold;
    }

    private void setCenterFold(Integer centerFold) {
        this.centerFold = centerFold;
    }

    private Integer centerFold;


    public DocumentPage(Mat image, String uri) {
        this.image = image;
        this.width = image.width();
        this.height = image.height();
        this.grayImage = null;
        this.binaryImage = null;
        this.filename = uri;
    }

    public String getFilename() {
        return filename;
    }

    int getMedianPixel(Mat input) {
        List<Mat> bgr_planes = new ArrayList<>();
        Core.split(input, bgr_planes);

        Mat channel = bgr_planes.get(0);
        Mat result = new Mat();
        byte[] output = new byte[channel.rows() * channel.cols()];
        channel = channel.reshape(1, channel.rows() * channel.cols());
        channel.convertTo(result, channel.type());
        result.get(0, 0, output);
//        Mat row = result.;


        Arrays.sort(output);
        return output[channel.rows() * channel.cols() / 2] & 0xff;
    }

    public Mat getHorizontalWhitespaceImage() {

        if (horizontalWhitespaceImage == null) {
            horizontalWhitespaceImage = LayoutProc.horizontalRunlengthInt(getBinaryImage(), 255);

        }
        return horizontalWhitespaceImage;
    }

    public Mat getHorizontalInkImage() {
        if (horizontalInkImage == null) {
            horizontalInkImage = LayoutProc.horizontalRunlengthInt(getBinaryImage(), 255);

        }
        return horizontalInkImage;
    }

    Mat getVerticalWhitespaceImage() throws Exception {
        if (verticalWhitespaceImage == null) {
            verticalWhitespaceImage = LayoutProc.verticalRunlengthInt(getTextOnlyImage(), 0);
        }
        return verticalWhitespaceImage;
    }

    public int getVerticalLines() {
        int counter = 0;
        boolean found = false;
        Mat verticalImage = getVerticalInkImage();
        List<Integer> verticalRunlengthProfile = LayoutProc.verticalProfileInt(verticalImage, 0, getHeight());
        List<Double> verticalRunlengthProfileSmooth = LayoutProc.smoothList(verticalRunlengthProfile, 5);
        for (int j = getLeftMargin(); j < getRightMargin(); j++) {
            if (verticalRunlengthProfileSmooth.get(j) > getHeight() / 2.0) {
                if (!found) {
                    counter++;
                    found = true;
                    LOG.info("found large line: {}", j);
                }
            } else {
                found = false;
            }
        }
        return counter;
    }

    Mat getVerticalInkImage() {
        if (verticalInkImage == null) {
            verticalInkImage = LayoutProc.verticalRunlengthInt(getBinaryImage(), 255);
        }
        return verticalInkImage;
    }

    private Mat getTextOnlyImage() throws Exception {

        if (textOnlyImage == null) {
            textOnlyImage = getDespeckledImage().clone();
            int maxSize = textOnlyImage.width() / 5;
            if (isDoublePage()) {
                maxSize /= 2;
            }
            LayoutProc.removeLargeCoCos(textOnlyImage, cocos, maxSize);
        }
        return textOnlyImage;
    }

    Mat getCannyImage() {

        if (cannyImage == null) {
            cannyImage = new Mat();
            Imgproc.Canny(getBinaryImage(), cannyImage, 300, 600, 5, true);
            Imgcodecs.imwrite("/scratch/images/canny-image.png", cannyImage);
        }
        return cannyImage;
    }


    public int getHeight() {
        return height;
    }

    public int getWidth() {
        return width;
    }

    public Mat getImage() {
        return image;
    }

    public Mat getGrayImage() {
        if (grayImage == null) {
            grayImage = new Mat();
            if (image.type() != CV_8U) {
                Imgproc.cvtColor(image, grayImage, Imgproc.COLOR_BGR2GRAY);
            } else {
                image.copyTo(grayImage);
            }
        }
        return grayImage;
    }

    void setGrayImage(Mat grayImage) {
        this.grayImage = grayImage;
    }

    public Mat getBinaryImage() {
        if (binaryImage == null) {
            LayoutConfiguration layoutConfiguration = LayoutConfiguration.getGlobal();
            binaryImage = new Mat();
            if (layoutConfiguration.doOtsu()) {
                Imgproc.threshold(getGrayImage(), binaryImage, 0, 255, Imgproc.THRESH_OTSU);
                Core.bitwise_not(binaryImage, binaryImage);
                return binaryImage;
            }

            int blockSize = getGrayImage().width() / 50; // default should be something like width / 50
            if (isDoublePage()) {
                blockSize /= 2;
            }
            if (blockSize % 2 == 0) {
                blockSize++;
            }
            if (blockSize <= 1) {
                blockSize = 3;
            }

//            Imgproc.adaptiveThreshold(getGrayImage(), binaryImage, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, windowSize, 15);//15);
//            Imgproc.adaptiveThreshold(getGrayImage(), binaryImage, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, windowSize, 25);//15);
            Imgproc.adaptiveThreshold(getGrayImage(), binaryImage, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, blockSize, LayoutProc.getBestThreshold(this.getGrayImage()));//15);
//            Imgproc.adaptiveThreshold(getGrayImage(), binaryImage, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, windowSize, 35);//15);
//            Imgproc.threshold(getGrayImage(), binaryImage, 127, 255, Imgproc.THRESH_OTSU);//15);
        }
        return binaryImage;
    }

    Mat getBinaryImageCrude() {
        if (binaryImageCrude == null) {
            binaryImageCrude = new Mat();
            int windowSize = getGrayImage().width() / 40;
            if (windowSize % 2 == 0) {
                windowSize++;
            }
            Imgproc.adaptiveThreshold(getGrayImage(), binaryImageCrude, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, windowSize, 5);
        }
        return binaryImageCrude;
    }

    public Mat getDespeckledImage() throws Exception {
        if (despeckledImage == null) {
//            Mat clone = getBinaryImage().clone();
//            Mat fastCoCos = new Mat();
//            Mat cocoStats = new Mat();
//            Mat centroids = new Mat();
//            int coCoCount = OpenCVWrapper.connectedComponentsWithStats(clone, fastCoCos, cocoStats, centroids, 4, CV_32S);
//            LayoutProc.deSpeckleFast(clone, 100,fastCoCos, cocoStats,centroids,coCoCount);
//despeckledImage = clone;

            Mat clone = getBinaryImage().clone();
            long start = System.currentTimeMillis();
            if (LayoutConfiguration.getGlobal().isOutputDebug()) {
                LOG.info("getting cocos");
            }
            java.util.List<ConnectedComponent> cocos = getCocos();//
            if (LayoutConfiguration.getGlobal().isOutputDebug()) {
                LOG.debug("getting cocos took: {}", System.currentTimeMillis() - start);
            }
            int minsize = clone.width() / 800;
            if (isDoublePage()) {
                minsize /= 2;
            }
            if (this.isMachinePrint()) {
                minsize = this.getxHeight() / 8;
            }
            if (minsize < 10) { // this will remove dots and accents, but those are generally not needed for the layout analysis.
                minsize = 10;
            }
            LOG.warn("minsize for despeckling: {}", minsize);
            start = System.currentTimeMillis();
            LayoutProc.deSpeckle(clone, cocos, minsize);
            if (LayoutConfiguration.getGlobal().isOutputDebug()) {
                LOG.debug("actual despeckling took: {}", System.currentTimeMillis() - start);
            }
            despeckledImage = clone;
        }
        return despeckledImage;
    }

    ArrayList<ComposedBlock> getComposedBlocks() {

        //TODO RUTGERCHECK Implement
        if (composedBlocks == null) {
            composedBlocks = new ArrayList<>();
        }
        return composedBlocks;
    }

    void setDocumentTextBlocks(List<DocumentTextBlock> textBlocks) {
        this.textBlocks = textBlocks;
    }

    public int getTopMargin() {
        if (topMargin == -1) {
            LayoutProc.getMargins(this);
        }
        return topMargin;
    }

    public void setTopMargin(int topMargin) {
        this.topMargin = topMargin;
    }

    public int getBottomMargin() {
        if (bottomMargin == -1) {
            LayoutProc.getMargins(this);
        }
        return bottomMargin;
    }

    public void setBottomMargin(int bottomMargin) {
        this.bottomMargin = bottomMargin;
    }

    public int getLeftMargin() {
        if (leftMargin == -1) {
            LayoutProc.getMargins(this);
        }
        return leftMargin;
    }

    public void setLeftMargin(int leftMargin) {
        this.leftMargin = leftMargin;
    }

    public int getRightMargin() {
        if (rightMargin == -1) {
            LayoutProc.getMargins(this);
        }
        return rightMargin;
    }

    public void setRightMargin(int rightMargin) {
        this.rightMargin = rightMargin;
    }

    public java.util.List<ConnectedComponent> getCocos() throws Exception {
        if (cocos == null) {
            ConnectedComponentProc coCoProc = new ConnectedComponentProc();
            BufferedImage binImage = ImageConversionHelper.matToBufferedImage(getBinaryImage());
            cocos = coCoProc.process(binImage, false);
        }
        return cocos;
    }

    public Double getInkRatio() throws Exception {
        if (inkRatio == null) {
            inkRatio = LayoutProc.inkRatio(getDespeckledImage(), 0, getDespeckledImage().height(), 0, getDespeckledImage().width());
        }
        return inkRatio;
    }

    private List<Integer> getVerticalWithinMargins() throws Exception {
        return LayoutProc.verticalProfile(getDespeckledImage(), this.getTopMargin(), this.getBottomMargin());
//        return LayoutProc.verticalProfile(getTextOnlyImage(), this.getTopMargin(), this.getBottomMargin());
    }

    private List<Integer> getVerticalProfile() {
        return LayoutProc.verticalProfile(getBinaryImage(), 0, getHeight());
//        return LayoutProc.verticalProfile(getTextOnlyImage(), this.getTopMargin(), this.getBottomMargin());
    }

    List<Double> getVerticalProfileWithinMarginsSmooth() throws Exception {
        if (verticalProfileWithinMarginsSmooth == null) {
            verticalProfileWithinMarginsSmooth = LayoutProc.smoothList(getVerticalWithinMargins(), new LayoutConfiguration(getImage()).getSmoothFactor());
        }
        return verticalProfileWithinMarginsSmooth;
//        return LayoutProc.smoothList(getVerticalWithinMargins(), new LayoutConfiguration(getBinaryImage()).getSmoothFactor());
    }

    List<Double> getVerticalProfileSmooth() {
        if (verticalProfileSmooth == null) {
            verticalProfileSmooth = LayoutProc.smoothList(getVerticalProfile(), new LayoutConfiguration(getImage()).getSmoothFactor());
        }
        return verticalProfileSmooth;
//        return LayoutProc.smoothList(getVerticalWithinMargins(), new LayoutConfiguration(getBinaryImage()).getSmoothFactor());
    }

    public int getxHeight() {
        if (xHeight == 0) {
            xHeight = LayoutProc.getXHeight(cocos, getHeight() / 200);
        }
        return xHeight;
    }

    public boolean isMachinePrint() {
        //TODO RUTGERCHECK find better measure
        int target = getHeight() / 200;
        return (getxHeight() > target / 2 && getxHeight() < target * 4);
    }

    public boolean isDoublePage() {
        return (this.getWidth() > this.getHeight());
    }

    public boolean isCarbonCopy() {
        //TODO RUTGERCHECK: implement
        /// Carbon copies usually contain lots of noise.
        // The ink we are looking for itself can be faded a bit.
        // smaller characters (less surface) tend to have more ink.
        // often machine print
        return false;
    }

    boolean isLeftPage() {
        return !leftStartsWithPaper() && rightStartsWithPaper();
    }

    boolean isSinglePage() {
        return !leftStartsWithPaper() && !rightStartsWithPaper()
                || leftStartsWithPaper() && rightStartsWithPaper();
    }

    boolean isRightPage() {
        return leftStartsWithPaper() && !rightStartsWithPaper();
    }

    public Mat getBinaryOtsu() {
        if (binaryOtsu == null) {
            binaryOtsu = new Mat();
            Mat tmpOtsu = new Mat();
            Imgproc.threshold(this.getGrayImage(), tmpOtsu, 127, 255, Imgproc.THRESH_OTSU);
            Core.bitwise_not(tmpOtsu, binaryOtsu);
            tmpOtsu = OpenCVWrapper.release(tmpOtsu);
        }
        return binaryOtsu;
    }

    public List<Integer> getOtsuProfileVertical() {
        if (otsuProfileVertical == null) {
            otsuProfileVertical = LayoutProc.verticalProfile(getBinaryOtsu(), 0, getBinaryOtsu().height());
        }
        return otsuProfileVertical;
    }

    public List<Integer> getOtsuProfileHorizontal() {
        if (otsuProfileHorizontal == null) {
            otsuProfileHorizontal = LayoutProc.horizontalProfileByte(getBinaryOtsu(), 0, getBinaryOtsu().height());
        }
        return otsuProfileHorizontal;
    }

    public boolean rightStartsWithPaper() {
        return !isBinary() && getOtsuProfileVertical().get(getOtsuProfileVertical().size() - 1) < this.getHeight() / 2;
    }

    public boolean isBinary() {
        if (image.channels() < 2) {
            Mat bin = new Mat();
            Mat dst = new Mat();
            try {
                Imgproc.threshold(image, bin, 127, 255, Imgproc.THRESH_BINARY);
                absdiff(bin, image, dst);
                return countNonZero(dst) <= 0;
            } finally {
                bin.release();
                dst.release();
            }

        } else {

            Mat dst = new Mat();
            List<Mat> bgr = new ArrayList<>();
            bgr.add(new Mat());
            bgr.add(new Mat());
            bgr.add(new Mat());
            try {
                Core.split(image, bgr);
                absdiff(bgr.get(0), bgr.get(1), dst);

                if (Core.countNonZero(dst) > 0) {
                    return false;
                }

                absdiff(bgr.get(0), bgr.get(2), dst);
                return !(Core.countNonZero(dst) > 0);
            } finally {
                dst.release();
                for (Mat channel : bgr) {
                    channel.release();
                }
            }
        }
    }

    public boolean leftStartsWithPaper() {
        return isBinary() || getOtsuProfileVertical().get(0) < this.getHeight() / 2;
    }

    public boolean topStartsWithPaper() {
        return getOtsuProfileHorizontal().get(0) < this.getWidth() / 2;
    }

    String getFullText() {
        return fullText;
    }

    public void setFullText(String fullText) {
        this.fullText = fullText;
    }

    public String getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(String pageNumber) {
        this.pageNumber = pageNumber;
    }

    public void setFullTextSparse(String fullTextSparse) {
        this.fullTextSparse = fullTextSparse;
    }

    public String getFullTextSparse() {
        return fullTextSparse;
    }

    void setLayoutResult(String layoutResult) {
        this.layoutResult = layoutResult;
    }

    public String getLayoutResult() {
        return layoutResult;
    }

    Mat getColorizedImage() {
        if (colorizedImage == null) {
            colorizedImage = Mat.zeros(image.height(), image.width(), CV_32FC3);
        }
        return colorizedImage;
    }

    public void setColorizedImage(Mat colorizedImage) {
        this.colorizedImage = colorizedImage;
    }

    ArrayList<Double> getVerticalProfileSmoothDifference() throws Exception {
        if (differenceVerticalSmooth == null) {
            differenceVerticalSmooth = new ArrayList<>();
            for (int i = 0; i < getVerticalProfileSmooth().size(); i++) {
                differenceVerticalSmooth.add(getVerticalProfileSmooth().get(i) - getVerticalProfileWithinMarginsSmooth().get(i));
            }
        }
        return differenceVerticalSmooth;

    }

    private Mat releaseAndNull(Mat mat) {
        if (mat != null) {
            if (mat.dataAddr() != 0) {
                mat.release();
            }
            return null;
        }
        return null;
    }

    public void releaseMat() {
        binaryOtsu = releaseAndNull(binaryOtsu);
        grayImage = releaseAndNull(grayImage);
        binaryImage = releaseAndNull(binaryImage);
        binaryImageCrude = releaseAndNull(binaryImageCrude);
        despeckledImage = releaseAndNull(despeckledImage);
        horizontalWhitespaceImage = releaseAndNull(horizontalWhitespaceImage);
        verticalWhitespaceImage = releaseAndNull(verticalWhitespaceImage);
        verticalInkImage = releaseAndNull(verticalInkImage);
        horizontalInkImage = releaseAndNull(horizontalInkImage);
        textOnlyImage = releaseAndNull(textOnlyImage);
        cannyImage = releaseAndNull(cannyImage);
        colorizedImage = releaseAndNull(colorizedImage);
    }

    @Override
    public void close() {
        releaseMat();
        LOG.debug("Released cached Mats for {}", filename);
    }
}
