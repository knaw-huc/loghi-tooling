package nl.knaw.huc.di.images.layoutanalyzer.layoutlib;

import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import nl.knaw.huc.di.images.imageanalysiscommon.StringConverter;
import nl.knaw.huc.di.images.imageanalysiscommon.UnicodeToAsciiTranslitirator;
import nl.knaw.huc.di.images.imageanalysiscommon.visualization.VisualizationHelper;
import nl.knaw.huc.di.images.layoutanalyzer.DocumentPage;
import nl.knaw.huc.di.images.layoutanalyzer.LayoutConfiguration;
import nl.knaw.huc.di.images.layoutanalyzer.Statistics;
import nl.knaw.huc.di.images.layoutanalyzer.Tuple;
import nl.knaw.huc.di.images.layoutds.models.DocumentTextBlock;
import nl.knaw.huc.di.images.layoutds.models.DocumentTextLine;
import nl.knaw.huc.di.images.layoutds.models.Page.*;
import nl.knaw.huc.di.images.layoutds.models.connectedComponent.ConnectedComponent;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import org.opencv.core.*;
import org.opencv.core.Point;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.StringWriter;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static nl.knaw.huc.di.images.imageanalysiscommon.StringConverter.*;
import static org.opencv.core.CvType.*;
import static org.opencv.imgproc.Imgproc.*;

public class LayoutProc {
    private static final Logger LOG = LoggerFactory.getLogger(LayoutProc.class);

    public static final int MINIMUM_XHEIGHT = 15;
    public static final int MINIMUM_WIDTH = 5;
    public static final UnicodeToAsciiTranslitirator UNICODE_TO_ASCII_TRANSLITIRATOR = new UnicodeToAsciiTranslitirator();
    private static boolean _outputDebug = true;
    private static final int bestBinarizationThreshold = 15;
    private static final int bestBinarizationBlockSize = 51;
    private static final double SEAM_NO_GO_ENERGY = 1.0e20;

    private static final class TextLineGeometry {
        private final TextLine textLine;
        private final List<Point> baselinePoints;
        private final Rect baselineRect;

        private TextLineGeometry(TextLine textLine, List<Point> baselinePoints) {
            this.textLine = textLine;
            this.baselinePoints = baselinePoints;
            this.baselineRect = getBoundingBox(baselinePoints);
        }
    }

    public static void setOutputDebug(boolean _outputDebug) {
        LayoutProc._outputDebug = _outputDebug;
    }

    public static void removeCoco(Mat input, ConnectedComponent coco) {
        int cocoBitmapWidth = coco.getBitMap().getWidth();
        int cocoBitmapHeight = coco.getBitMap().getHeight();
        BufferedImage cocoBitMap = coco.getBitMap();
        for (int i = 0; i < cocoBitmapHeight; i++) {
            for (int j = 0; j < cocoBitmapWidth; j++) {
                if (cocoBitMap.getRGB(j, i) == Color.WHITE.getRGB()) {
                    // // TODO: 20-9-16 don't use put, there is a faster way
                    safePut(input, i + coco.getY(), j + coco.getX(), 0);
                }
            }
        }
    }

    public static void deSpeckle(Mat input, java.util.List<ConnectedComponent> cocos, int minimumSize) {
        for (ConnectedComponent coco : cocos) {
            if (coco.getWidth() < minimumSize && coco.getHeight() < minimumSize) {
                removeCoco(input, coco);
            }
        }
    }

    public static void deSpeckleFast(Mat input, int minimumSize, Mat labels, Mat stats, Mat centroids, int noComponents) {
        Mat mask = OpenCVWrapper.newMat();
        for (int i = 0; i < noComponents; i++) {
            double height = getSafeDouble(stats,i, CC_STAT_HEIGHT);
            double width = getSafeDouble(stats,i, CC_STAT_WIDTH);
            if (height < minimumSize && width < minimumSize) {
                Core.inRange(labels, new Scalar(i), new Scalar(i), mask);
                input.setTo(new Scalar(0), mask);
            }
        }
        mask = OpenCVWrapper.release(mask);
//        for (CoCo coco : cocos) {
//            if (coco.getBitMap().getWidth() < minimumSize && coco.getBitMap().getHeight() < minimumSize) {
//                removeCoco(input, coco);
//            }
//        }
    }

    public static void deSpeckleSingleDimension(Mat input, List<ConnectedComponent> cocos, int minimumSize) {
        for (ConnectedComponent coco : cocos) {
            if (coco.getBitMap().getWidth() < minimumSize || coco.getBitMap().getHeight() < minimumSize) {
                removeCoco(input, coco);
            }
        }
    }

    public static void removeLargeCoCos(Mat input, List<ConnectedComponent> cocos, int minimumSize) {
        for (ConnectedComponent coco : cocos) {
            if (coco.getBitMap().getWidth() > minimumSize || coco.getBitMap().getHeight() > minimumSize) {
                removeCoco(input, coco);
            }
        }
    }

    private static List<Integer> horizontalProfileByte(Mat binaryImage, int startY, int stopY, int startX, int stopX, int thickness) {
        if (binaryImage.channels() != 1) {
            LOG.error("invalid input, image is not binary/grayscale");
        }
        long size = binaryImage.total() * binaryImage.channels();
        int width = binaryImage.width();
        byte[] data = new byte[(int) size];
        binaryImage.get(0, 0, data);
        ArrayList<Integer> horizontals = new ArrayList<>();

        for (int y = 0; y < startY; y++) {
            horizontals.add(y, 0);
        }
        for (int y = startY; y < stopY; y++) {
            int length = 0;
            int currentLength = 0;
            for (int x = startX; x < stopX; x++) {
                if (data[y * width + x] != 0) {
                    currentLength++;
                } else {
                    currentLength = 0;
                }
                if (thickness == 0 || currentLength < thickness) {
                    length += data[y * width + x] & 0xFF;
                }
            }
            horizontals.add(y, length / 255);
        }
        return horizontals;
    }

    public static List<Integer> horizontalProfileByte(Mat binaryImage, int startY, int stopY) {
        if (binaryImage.channels() != 1) {
            LOG.error("invalid input, image is not binary/grayscale");
        }
        int width = binaryImage.width();
        ArrayList<Integer> horizontals = new ArrayList<>();

        for (int y = startY; y < stopY; y++) {
            horizontals.add(y - startY, Core.countNonZero(binaryImage.submat(y, y + 1, 0, width)));
        }

//        int[] data = new int[(int) binaryImage.total()];
//        binaryImage.get(0, 0, data);
//
//        for (int y = startY; y < stopY; y++) {
//            int length = 0;
//            for (int x = 0; x < width; x++) {
//                length += data[y * width + x] & 0xFF;
//            }
//            horizontals.add(y-startY, length);
//        }

        return horizontals;
    }

    public static List<Integer> horizontalProfileInt(Mat binaryImage, int startY, int stopY) {
        if (binaryImage.channels() != 1) {
            LOG.error("invalid input, image is not binary/grayscale");
        }
        long size = binaryImage.total() * binaryImage.channels();
        int width = binaryImage.width();
        int[] data = new int[(int) size];
        binaryImage.get(0, 0, data);
        ArrayList<Integer> horizontals = new ArrayList<>();

        for (int y = startY; y < stopY; y++) {
            int length = 0;
            for (int x = 0; x < width; x++) {
                length += data[y * width + x] & 0xFF;
            }
            horizontals.add(y, length);
        }

        return horizontals;
    }

    public static List<Integer> horizontalProfileByte(Mat binaryImage) {
        return horizontalProfileByte(binaryImage, 0, binaryImage.height());
//        if (binaryImage.channels() != 1) {
//            LOG.error("invalid input, image is not binary/grayscale");
//        }
//        long size = binaryImage.total() * binaryImage.channels();
//        int width = binaryImage.width();
//        byte[] data = new byte[(int) size];
//        binaryImage.get(0, 0, data);
//        ArrayList<Integer> horizontals = new ArrayList<>();
//
//        for (int y = 0; y < binaryImage.height(); y++) {
//            int length = 0;
//            for (int x = 0; x < width; x++) {
//                length += data[y * width + x] & 0xFF;
//            }
//            horizontals.add(y, length / 255);
//        }
//
//        return horizontals;
    }

    public static List<Integer> verticalProfile(Mat binaryImage, int startY, int stopY) {
        if (binaryImage.channels() != 1) {
            LOG.error("invalid input, image is not binary/grayscale");
        }
        long size = binaryImage.total() * binaryImage.channels();
        int width = binaryImage.width();
        byte[] data = new byte[(int) size];
        binaryImage.get(0, 0, data);
        ArrayList<Integer> verticals = new ArrayList<>();

        for (int x = 0; x < width; x++) {
            int length = 0;
            for (int y = startY; y < stopY; y++) {
                length += data[y * width + x] & 0xFF;
            }
            verticals.add(x, length / 255);
        }

        return verticals;
    }

    public static List<Integer> verticalProfileInt(Mat verticalImage, int startY, int stopY) {
        if (verticalImage.channels() != 1) {
            LOG.error("invalid input, image is not binary/grayscale");
        }
        long size = verticalImage.total() * verticalImage.channels();
        int width = verticalImage.width();
        int[] data = new int[(int) size];
        verticalImage.get(0, 0, data);
        ArrayList<Integer> verticals = new ArrayList<>();

        for (int x = 0; x < width; x++) {
            int length = 0;
            for (int y = startY; y < stopY; y++) {
                length += data[y * width + x];//& 0xFF;
            }
            verticals.add(x, length / 255);
        }

        return verticals;
    }

    public static Mat horizontalRunlengthByte(Mat input, int targetcolor) {
        int size = (int) input.total() * input.channels();
        int width = input.width();
        int height = input.height();
        byte[] data = new byte[size];
        input.get(0, 0, data);
        Mat destination = OpenCVWrapper.zeros(new Size(width, height), CvType.CV_8UC1);
        for (int y = 0; y < height; y++) {
            int length = 0;
            for (int x = 0; x < width; x++) {
                if ((data[y * width + x] & 0xFF) == targetcolor) {
                    length++;
                }

                // end of line || new empty pixel
                if (length > 0 && x == width - 1 || length > 0 && (data[y * width + x] & 0xFF) != targetcolor) {
                    VisualizationHelper.drawHorizontalLine(destination, x - (length - 1), y, length, true);
                    length = 0;
                }
            }
        }
        return destination;
    }

    public static Mat horizontalRunlengthInt(Mat input, int targetcolor) {
        int size = (int) input.total() * input.channels();
        int width = input.width();
        int height = input.height();
        byte[] data = new byte[size];
        input.get(0, 0, data);
        Mat destination = Mat.zeros(height, width, CvType.CV_32S);
        for (int y = 0; y < height; y++) {
            int length = 0;
            for (int x = 0; x < width; x++) {
                if ((data[y * width + x] & 0xFF) == targetcolor) {
                    length++;
                }

                // end of line || new empty pixel
                if (length > 0 && x == width - 1 || length > 0 && (data[y * width + x] & 0xFF) != targetcolor) {
                    VisualizationHelper.drawHorizontalLine(destination, x - (length - 1), y, length, false);
                    length = 0;
                }
            }
        }
        return destination;
    }

    public static Mat verticalRunlengthInt(Mat binaryImage, int targetColor) {
        if (binaryImage.channels() != 1) {
            LOG.error("invalid input, image is not binary/grayscale");
            throw new RuntimeException("invalid input, image is not binary/grayscale");
        }
        int size = (int) binaryImage.total() * binaryImage.channels();
        int imageWidth = binaryImage.width();
        int imageHeight = binaryImage.height();
        byte[] data = new byte[size];
        binaryImage.get(0, 0, data);
        Mat destination = Mat.zeros(imageHeight, imageWidth, CvType.CV_32S);

        for (int x = 0; x < imageWidth; x++) {
            int length = 0;
            for (int y = 0; y < imageHeight; y++) {
                int value = data[y * imageWidth + x] & 0xFF;
                if (value == targetColor) {
                    length++;
                }

                // end of line || new empty pixel
                if (length > 0 && y == imageHeight - 1 || length > 0 && value != targetColor) {
                    VisualizationHelper.drawVerticalLine(destination, x, y - (length - 1), length, false);
                    length = 0;
                }
            }
        }
        return destination;
    }

    private static int getBottomMargin(List<Double> profile, int from) {
        double bestValue;
        int bottomMargin = profile.size() - 1;
        bestValue = 0;
        for (int i = from; i < profile.size(); i++) {
            if (profile.get(i) > bestValue) {
                bestValue = profile.get(i);
                bottomMargin = i;
            }
        }
        return bottomMargin;
    }

    public static int getBottomMargin(List<Double> horizontalProfile, boolean isDoublePage) {
        double bestValue;
        int bottomMargin = horizontalProfile.size() - 1;
        bestValue = 0;
        int checkFrom = horizontalProfile.size() / 2;
        if (isDoublePage) {
            checkFrom = horizontalProfile.size() * 2 / 3;
        }
        for (int i = checkFrom; i < horizontalProfile.size(); i++) {
            if (horizontalProfile.get(i) > bestValue) {
                bestValue = horizontalProfile.get(i);
                bottomMargin = i;
            }
        }
        return bottomMargin;
    }

    private static int getTopMargin(List<Double> horizontalProfile, int to) {
        int topMargin = 0;
        double bestValue = 0;
        for (int i = 0; i < to; i++) {
            if (horizontalProfile.get(i) >= bestValue) {
                bestValue = horizontalProfile.get(i);
                topMargin = i;
            }
        }
        return topMargin;
    }

    public static int getTopMargin(List<Double> horizontalProfile, boolean isDoublepage) {
        int topMargin = 0;
        double bestValue = 0;
        int checkUpto = horizontalProfile.size() / 2;
        if (isDoublepage) {
            checkUpto = horizontalProfile.size() / 3;
        }
        for (int i = 0; i < checkUpto; i++) {
            if (horizontalProfile.get(i) >= bestValue) {
                bestValue = horizontalProfile.get(i);
                topMargin = i;
            }
        }
        return topMargin;
    }

    private static ArrayList<org.opencv.core.Point> droplet(Mat binaryImage, int xStart, int xStop, int yStart, int upperboundary, int lowerboundary) {

        int x = xStart;
        long size = binaryImage.total() * binaryImage.channels();
        int width = binaryImage.width();
        byte[] data = new byte[(int) size];
        binaryImage.get(0, 0, data);

        Droplet droplet = new Droplet(data, width, xStart, xStop, yStart, upperboundary, lowerboundary);

        while (x < xStop) {
            x = droplet.getX();
            droplet.move();
        }
        return droplet.getPointList();
    }

    private static List<Integer> getInkThicknessList(Mat binaryImage, int startY, int stopY, int startX, int stopX) {
        long size = binaryImage.total() * binaryImage.channels();
        byte[] data = new byte[(int) size];
        binaryImage.get(0, 0, data);
        ArrayList<Integer> thicknessList = new ArrayList<>();
        for (int thickness = 0; thickness < 50; thickness++) {
            thicknessList.add(0);
        }
        for (int y = startY; y < stopY; y++) {
            int thickness = 0;
            boolean lastPixelOn = false;
            for (int x = startX; x < stopX; x++) {
                if (data[y * binaryImage.width() + x] != 0) {
                    if (lastPixelOn) {
                        thickness++;
                    }
                    lastPixelOn = true;
                } else {
                    if (thickness > 0 && thickness < 50) {
                        thicknessList.set(thickness, thicknessList.get(thickness) + 1);
                    }
                    lastPixelOn = false;
                    thickness = 0;
                }
            }
        }
        return thicknessList;
    }

    public static int getInkThickness(Mat despeckledImage, int startY, int stopY, int startX, int stopX) {
        List<Integer> thicknessList = getInkThicknessList(despeckledImage, startY, stopY, startX, stopX);
        int bestThickness = 0;
        int bestThicknessCount = 0;
        for (int thickness = 0; thickness < 50; thickness++) {
            int thicknessCount = thicknessList.get(thickness);
            if (thicknessCount > bestThicknessCount) {
                bestThickness = thickness;
                bestThicknessCount = thicknessCount;
            }
        }
        LOG.error("best thickness: " + bestThickness);
        return bestThickness;
    }

    public static int getXHeight(java.util.List<ConnectedComponent> cocos, int target) {
        ArrayList<Integer> widthList = new ArrayList<>();
        ArrayList<Integer> heightList = new ArrayList<>();
        ArrayList<Integer> mixedList = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            widthList.add(0);
            heightList.add(0);
            mixedList.add(0);
        }
        for (ConnectedComponent coco : cocos) {
            int width = coco.getBitMap().getWidth();
            int height = coco.getBitMap().getHeight();
            int mixed = (int) Math.sqrt(width * height);
            if (width < 250) {
                widthList.set(width, widthList.get(width) + 1);
            }
            if (height < 250) {
                heightList.set(height, heightList.get(height) + 1);
            }
            if (mixed < 250) {
                mixedList.set(mixed, mixedList.get(mixed) + 1);
            }
        }
        if (_outputDebug) {
            LOG.error("    Width");
            for (int i = 0; i < 250; i++) {
                LOG.error(String.format(" %d %d", i, widthList.get(i)));
            }
            LOG.error("    Height");
        }
        int xHeight = 0;
        int bestScore = 0;
        for (int i = (int) (target * 0.8); i < 250; i++) {
            int heightCount = heightList.get(i);
            if (heightCount > bestScore) {
                bestScore = heightCount;
                xHeight = i;
            }
            if (_outputDebug) {
                LOG.error(String.format(" %d %d", i, heightList.get(i)));
            }
        }
        if (_outputDebug) {
            LOG.debug("xheight   " + xHeight);
            LOG.debug("    Mixed");
            for (int i = 0; i < 250; i++) {
                LOG.debug(String.format(" %s   %s%n", i, mixedList.get(i)));
            }
        }

        return xHeight;
    }

    public static boolean isRotated(Mat image) {
        //TODO detect Orientation
//        Mat scaled = OpenCVWrapper.newMat();
//        Mat dst = OpenCVWrapper.newMat();
//
//        Size size = new Size(1000, 1000);
//        Imgproc.resize(image, scaled, size);
//        DocumentPage documentPage = new DocumentPage(scaled);
//
//        Core.transpose(scaled, dst);
//        Core.flip(dst, dst, 0);
//        long binaryImageSquared = getSumSquaredProfile(scaled);
//        long rotateImageSquared = getSumSquaredProfile(dst);
//        Highgui.imwrite("/scratch/images/rotate-90.png",dst);
//        if (rotateImageSquared> binaryImageSquared){
//            return true;
//        }
        return false;
    }

    private static Point closestPoint(List<Point> points, Point seed) {
        Point closest = null;
        double distance = Double.MAX_VALUE;
        for (Point point : points) {
            if (closest == null) {
                closest = point;
                continue;
            }
            double currentDistance = StringConverter.distance(seed, point);
            if (currentDistance < distance) {
                closest = point;
                distance = currentDistance;

            }
        }
        if (distance == Double.MAX_VALUE) {
            LOG.info("No closest point found");
        }
        return closest;
    }

    public static double interlineMedian(List<TextLine> textLines, double minValue) {
        double interlineDistance = interlineMedian(textLines);
        return Math.max(interlineDistance, minValue);
    }

    private static double interlineMedianFromGeometry(List<TextLineGeometry> textLineGeometries, double minValue) {
        double interlineDistance = interlineMedianFromGeometry(textLineGeometries);
        return Math.max(interlineDistance, minValue);
    }



    public static double findClosestDistance(List<Point> polygon1, List<Point> polygon2) {
        double minDistance = Double.MAX_VALUE;

        // Check distance between all pairs of points
        for (Point point1 : polygon1) {
            for (Point point2 : polygon2) {
                double distance = calculateDistance(point1, point2);
                if (distance < minDistance) {
                    minDistance = distance;
                }
            }
        }

        // Check distance between all pairs of edges
        for (int i = 0; i < polygon1.size(); i++) {
            Point p1 = polygon1.get(i);
            Point p2 = polygon1.get((i + 1) % polygon1.size());
            for (int j = 0; j < polygon2.size(); j++) {
                Point q1 = polygon2.get(j);
                Point q2 = polygon2.get((j + 1) % polygon2.size());
                double distance = calculateDistanceBetweenEdges(p1, p2, q1, q2);
                if (distance < minDistance) {
                    minDistance = distance;
                }
            }
        }

        return minDistance;
    }

    private static double calculateDistance(Point p1, Point p2) {
        return Math.sqrt(Math.pow(p1.x - p2.x, 2) + Math.pow(p1.y - p2.y, 2));
    }

    private static double calculateDistanceBetweenEdges(Point p1, Point p2, Point q1, Point q2) {
        // Calculate the distance between two line segments (p1-p2 and q1-q2)
        double dist1 = pointToSegmentDistance(p1, q1, q2);
        double dist2 = pointToSegmentDistance(p2, q1, q2);
        double dist3 = pointToSegmentDistance(q1, p1, p2);
        double dist4 = pointToSegmentDistance(q2, p1, p2);
        return Math.min(Math.min(dist1, dist2), Math.min(dist3, dist4));
    }

    private static double pointToSegmentDistance(Point p, Point v, Point w) {
        // Return minimum distance between point p and segment vw
        double l2 = calculateDistance(v, w);
        if (l2 == 0.0) return calculateDistance(p, v);
        double t = ((p.x - v.x) * (w.x - v.x) + (p.y - v.y) * (w.y - v.y)) / l2;
        t = Math.max(0, Math.min(1, t));
        return calculateDistance(p, new Point(v.x + t * (w.x - v.x), v.y + t * (w.y - v.y)));
    }


    public static double interlineMedian(List<TextLine> textLines) {
        ArrayList<Double> distances = new ArrayList<>();
        for (TextLine textLine : textLines) {
            List<Point> allPoints = getAllPoints(textLines);
            ArrayList<Point> points = StringConverter.stringToPoint(textLine.getBaseline().getPoints());
            allPoints.removeAll(points);
            double distance = findClosestDistance(allPoints, points);
            distances.add(distance);
        }
        Statistics statistics = new Statistics(distances);
        return statistics.median();
    }

    private static double interlineMedianFromGeometry(List<TextLineGeometry> textLineGeometries) {
        if (textLineGeometries.size() < 2) {
            return 0;
        }

        ArrayList<Double> distances = new ArrayList<>(textLineGeometries.size());
        for (TextLineGeometry textLineGeometry : textLineGeometries) {
            double closestDistance = Double.MAX_VALUE;
            for (TextLineGeometry otherLineGeometry : textLineGeometries) {
                if (textLineGeometry == otherLineGeometry) {
                    continue;
                }
                closestDistance = Math.min(closestDistance,
                        findClosestDistance(otherLineGeometry.baselinePoints, textLineGeometry.baselinePoints));
            }
            if (closestDistance != Double.MAX_VALUE) {
                distances.add(closestDistance);
            }
        }
        if (distances.isEmpty()) {
            return 0;
        }
        Statistics statistics = new Statistics(distances);
        return statistics.median();
    }

    private static List<Point> getAllPoints(List<TextLine> textLines) {
        ArrayList<Point> allPoints = new ArrayList<>();
        for (TextLine textLine : textLines) {
            ArrayList<Point> points = StringConverter.stringToPoint(textLine.getBaseline().getPoints());
            allPoints.addAll(points);
        }
        return allPoints;
    }

    public static void fixPoints(List<Point> points, int maxX, int maxY) {
        for (Point point : points) {
            fixPoint(point, maxX, maxY);
        }
    }


    private static Point fixPoint(Point point, int maxX, int maxY) {
        if (point.x < 0) {
            LOG.warn("point x coordinate smaller zero. Setting to zero");
            point.x = 0;
        }
        if (point.y < 0) {
            LOG.warn("point y coordinate smaller zero. Setting to zero");
            point.y = 0;
        }
        if (point.x > maxX) {
            LOG.warn("point x coordinate larger than width. Setting to max ({})", maxX);
            point.x = maxX;
        }
        if (point.y > maxY) {
            LOG.warn("point x coordinate larger than height. Setting to max ({})", maxY);
            point.y = maxY;
        }
        return point;
    }

    public static void recalculateTextLinesFromBaselines(PcGts page) {
        List<TextLine> textlines = new ArrayList<>();
        for (TextRegion textRegion : page.getPage().getTextRegions()) {
            textlines.addAll(textRegion.getTextLines());
        }
        double interlineDistance = interlineMedian(textlines);

        for (TextLine textLine : textlines) {
            ArrayList<Point> points = StringConverter.stringToPoint(textLine.getBaseline().getPoints());
            List<Point> allPoints = getAllPoints(textlines);
            allPoints.removeAll(points);
            Point previousPoint = null;
            ArrayList<Point> textLinePoints = new ArrayList<>();
            ArrayList<Point> bottomPoints = new ArrayList<>();
            for (Point point : points) {
                Point closestPoint = closestPoint(allPoints, point);
                if (closestPoint == null) {
                    continue;
                }
//                Imgproc.line(image, closestPoint, point, new Scalar(0, 255, 0), 10);
                if (previousPoint == null) {
                    previousPoint = point;
                    textLinePoints.add(fixPoint(new Point(point.x - interlineDistance / 2, point.y), page.getPage().getImageWidth() - 1, page.getPage().getImageHeight() - 1));
                    textLinePoints.add(fixPoint(new Point(point.x, point.y - interlineDistance * 0.9), page.getPage().getImageWidth() - 1, page.getPage().getImageHeight() - 1));
                    bottomPoints.add(fixPoint(new Point(previousPoint.x, previousPoint.y + interlineDistance / 4), page.getPage().getImageWidth() - 1, page.getPage().getImageHeight() - 1));
                    continue;
                }
//                Imgproc.line(image, previousPoint, point, new Scalar(255, 0, 0), 10);
//                Imgproc.line(image, new Point(previousPoint.x, previousPoint.y - interlineDistance / 2), new Point(point.x, point.y - interlineDistance / 2), new Scalar(0, 0, 255), 5);
//                Imgproc.line(image, new Point(previousPoint.x, previousPoint.y + interlineDistance / 4), new Point(point.x, point.y + interlineDistance / 4), new Scalar(0, 255, 255), 5);
                textLinePoints.add(fixPoint(new Point(point.x, point.y - interlineDistance * 0.9), page.getPage().getImageWidth() - 1, page.getPage().getImageHeight() - 1));
                bottomPoints.add(fixPoint(new Point(point.x, point.y + interlineDistance / 4), page.getPage().getImageWidth() - 1, page.getPage().getImageHeight() - 1));
                previousPoint = point;
            }
            textLinePoints.addAll(Lists.reverse(bottomPoints));
            textLine.getCoords().setPoints(StringConverter.pointToString(textLinePoints));
        }
//        Imgcodecs.imwrite("/tmp/baselines.png", image);
//        Imgcodecs.imwrite("/tmp/binary.png", binaryImage);
    }

    private static Statistics getTextBlockLineStats(DocumentTextBlock textBlock) {
        DocumentTextLine lastLine = null;
        ArrayList<Double> diffs = new ArrayList<>();
        ArrayList<Double> heights = new ArrayList<>();
        for (DocumentTextLine textLine : textBlock.getDocumentParagraphs().get(0).getDocumentTextLines()) {
            heights.add((double) textLine.getHeight());
            if (lastLine != null) {
                Double diff = (double) textLine.getLineCenter() - lastLine.getLineCenter();
                diffs.add(diff);
                LOG.info("distance between lines: " + diff);
            }
            lastLine = textLine;
        }
        Statistics stats = null;
        // if distance between lines increases, it might be a paragaph border
        if (diffs.size() > 0) {
            stats = new Statistics(diffs, 0, diffs.size());
            LOG.info("mean " + stats.getMean());
            LOG.info("median" + stats.median());
            LOG.info("min " + stats.getMinimum());
            LOG.info("max " + stats.getMaximum());
        }

        if (heights.size() > 0) {
            stats = new Statistics(heights, 0, heights.size());
            LOG.info("heights mean " + stats.getMean());
            LOG.info("heights median" + stats.median());
            LOG.info("heights min " + stats.getMinimum());
            LOG.info("heights max " + stats.getMaximum());
        }

        return stats;
    }

    private static void doDroplets(Mat binaryImage, DocumentTextBlock textBlock, ArrayList<DocumentTextLine> documentTextLines) {
        for (int i = 0; i < documentTextLines.size(); i++) {

            DocumentTextLine documentTextLine = documentTextLines.get(i);
            int upperboundary;
            if (i == 0) {
                upperboundary = textBlock.getYStart();
            } else {
                DocumentTextLine previousLine = documentTextLines.get(i - 1);
                upperboundary = previousLine.getLineCenter();
            }

            ArrayList<org.opencv.core.Point> pointList = droplet(binaryImage,
                    documentTextLine.getXStart(),
                    documentTextLine.getXStart() + documentTextLine.getWidth(),
                    documentTextLine.getYStart(),
                    upperboundary,
                    documentTextLine.getLineCenter());
            documentTextLine.setUpperPoints(pointList);

        }
    }

    private static long getSumSquaredProfile(Mat testMat) {
        List<Integer> horizontalProfile = LayoutProc.horizontalProfileByte(testMat);
        long sum = 0;
        for (Integer aHorizontalProfile : horizontalProfile) sum += Math.pow(aHorizontalProfile, 2);
        return sum;
    }

    private static Mat getGaborImageVertical(Mat grayImage) {
        Mat resultImage = Mat.zeros(grayImage.rows(), grayImage.cols(), CV_8UC1);

        double sigma = 1;
        double lambd = 8;
        double gamma = 0.02;
        Size size = new Size(33, 33);
        Mat kernel = Imgproc.getGaborKernel(size, sigma, 0, lambd, gamma);//, 3.1415/4,CV_32F);
        Imgproc.filter2D(grayImage, resultImage, CV_8UC1, kernel);
        Imgproc.threshold(resultImage, resultImage, 127, 255, Imgproc.THRESH_OTSU);
        kernel = OpenCVWrapper.release(kernel);
        return resultImage;
    }

    private static ArrayList<Double> getVerticalNoiseProfile(DocumentPage documentPage) {
        double sigma = 1;
        double lambd = 8;
        double gamma = 0.02;
        Size size = new Size(15, 15);

        Mat kernel = Imgproc.getGaborKernel(size, sigma, 0, lambd, gamma);//, 3.1415/4,CV_32F);
        Mat tmpImage = OpenCVWrapper.newMat();

        org.opencv.core.Point anchor = new org.opencv.core.Point(kernel.cols() - kernel.cols() / 2.0 - 1, kernel.rows() - kernel.rows() / 2.0 - 1);
//        int borderMode = BORDER_CONSTANT;
//        cv::flip(kernel, kernel, -1);
//        cv::filter2D(source, dest, img.depth(), kernel , anchor, 0, borderMode);

        Imgproc.filter2D(documentPage.getBinaryImage(), tmpImage, CV_8UC1, kernel, anchor, 0);

        List<Integer> verticalProfile0 = LayoutProc.verticalProfile(tmpImage, 0, tmpImage.height());
        List<Double> verticalProfileSmooth0 = LayoutProc.smoothList(verticalProfile0, tmpImage.width() / 50);
        tmpImage = OpenCVWrapper.release(tmpImage);

        kernel = Imgproc.getGaborKernel(size, sigma, 90, lambd, gamma);//, 3.1415/4,CV_32F);
        tmpImage = OpenCVWrapper.newMat();

        Imgproc.filter2D(documentPage.getBinaryImage(), tmpImage, CV_8UC1, kernel, anchor, 0);
        kernel = OpenCVWrapper.release(kernel);

        List<Integer> verticalProfile90 = LayoutProc.verticalProfile(tmpImage, 0, tmpImage.height());
        List<Double> verticalProfileSmooth90 = LayoutProc.smoothList(verticalProfile90, tmpImage.width() / 50);


        Mat cannyImage = OpenCVWrapper.newMat();
        Imgproc.Canny(documentPage.getGrayImage(), cannyImage, 300, 600, 5, true);
        List<Integer> cannyProfileVertical = LayoutProc.verticalProfile(cannyImage, 0, cannyImage.height());

        List<Double> smoothCannyProfileVertical = LayoutProc.smoothList(cannyProfileVertical, tmpImage.width() / 50);


        ArrayList<Double> verticalProfileSmoothDivided = new ArrayList<>();
        for (int i = 0; i < verticalProfileSmooth90.size(); i++) {
            if (verticalProfileSmooth90.get(i) > 0) {
                verticalProfileSmoothDivided.add(smoothCannyProfileVertical.get(i) * verticalProfileSmooth0.get(i) / verticalProfileSmooth90.get(i));
            } else {
                verticalProfileSmoothDivided.add(0d);
            }
        }

        VisualizationHelper.drawVerticalInkProfileDouble(tmpImage, verticalProfileSmoothDivided);

        Imgcodecs.imwrite("/scratch/out-gaborresult-120div90.png", tmpImage);
        tmpImage = OpenCVWrapper.release(tmpImage);
        cannyImage = OpenCVWrapper.release(cannyImage);
        return verticalProfileSmoothDivided;
    }

    public static void getMargins(DocumentPage documentPage) {
        if (LayoutConfiguration.getGlobal().documentEdgeIsBorder()) { // if precut image
            documentPage.setLeftMargin(0);
            documentPage.setRightMargin(documentPage.getWidth() - 1);
            documentPage.setTopMargin(0);
            documentPage.setBottomMargin(documentPage.getHeight() - 1);
            return;
        }

        Mat binaryOtsu = documentPage.getBinaryOtsu();

//        Imgproc.dilate(binaryOtsu, binaryOtsu, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2)));
//        Imgproc.dilate(binaryOtsu, binaryOtsu, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2)));
//        Imgproc.dilate(binaryOtsu, binaryOtsu, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2)));

        List<Integer> otsuProfileVertical = documentPage.getOtsuProfileVertical();
        List<Integer> otsuProfileHorizontal = documentPage.getOtsuProfileHorizontal();
        ArrayList<Double> verticalNoiseProfile = getVerticalNoiseProfile(documentPage);
        Statistics stats = new Statistics(verticalNoiseProfile, 0, verticalNoiseProfile.size());
        List<Double> otsuProfileVerticalSmoothed = LayoutProc.smoothList(otsuProfileVertical, otsuProfileVertical.size() / 50);
        VisualizationHelper.drawVerticalInkProfileDouble(binaryOtsu, otsuProfileVerticalSmoothed);
        Statistics otsuStats = new Statistics(otsuProfileVerticalSmoothed, 0, otsuProfileVerticalSmoothed.size());

        if (_outputDebug) {
            LOG.debug("writing /scratch/images/out-binaryOtsu.png");
            Imgcodecs.imwrite("/scratch/images/out-binaryOtsu.png", binaryOtsu);
        }
        Mat cannyImage = OpenCVWrapper.newMat();
        Imgproc.Canny(documentPage.getGrayImage(), cannyImage, 300, 600, 5, true);
        List<Integer> cannyProfileVertical = LayoutProc.verticalProfile(cannyImage, 0, cannyImage.height());
        List<Integer> cannyProfileHorizontal = LayoutProc.horizontalProfileByte(cannyImage, 0, cannyImage.height());

        List<Double> smoothCannyProfileVertical = LayoutProc.smoothList(cannyProfileVertical, 3);


        Mat binaryImage = documentPage.getBinaryImage();
        // find margins based on projection profiles of the ink
        Mat horizontalImage = horizontalRunlengthByte(binaryImage, 255);
//        Mat verticalImage = verticalRunlengthInt(binaryImage, 255);
//        ArrayList<Integer> horizontalProfile = LayoutProc.horizontalProfileByte(horizontalImage, 0, binaryImage.height(), 0, binaryImage.width(), 0);
        List<Integer> horizontalProfile = LayoutProc.horizontalProfileByte(horizontalImage, 0, binaryImage.height());
        horizontalImage= OpenCVWrapper.release(horizontalImage);
        List<Integer> verticalProfile = LayoutProc.verticalProfile(binaryImage, 0, binaryImage.height());

        List<Double> smoothedHorizontalProfile = smoothList(horizontalProfile, LayoutConfiguration.getGlobal().getSmoothFactor());
        List<Double> smoothedVerticalProfile = smoothList(verticalProfile, LayoutConfiguration.getGlobal().getSmoothFactor());

        for (int i = 0; i < smoothedVerticalProfile.size() - 1; i++) {
            if (smoothedVerticalProfile.get(i) >= 1) {
                smoothCannyProfileVertical.set(i, smoothCannyProfileVertical.get(i) / smoothedVerticalProfile.get(i));
            }
        }
//        smoothCannyProfileVertical = LayoutProc.smoothListDouble(smoothCannyProfileVertical,13);
        VisualizationHelper.drawVerticalInkProfile(binaryImage, smoothCannyProfileVertical);
        if (_outputDebug) {
            LOG.debug("writing /scratch/images/out-marginImageTest.png");
            Imgcodecs.imwrite("/scratch/images/out-marginImageTest.png", binaryImage);
        }


        // top margin
        int topMargin;
        int i = 0;
        int minProfile = Integer.MAX_VALUE;
//        if (!document.topStartsWithPaper()) {
        while (i < otsuProfileHorizontal.size() && (otsuProfileHorizontal.get(i) <= minProfile || otsuProfileHorizontal.get(i) > documentPage.getHeight() / 2)) {
            minProfile = otsuProfileHorizontal.get(i);
            i++;
        }
        if (i < cannyProfileHorizontal.size()) {
            minProfile = cannyProfileHorizontal.get(i);
        }
        while (i < cannyProfileHorizontal.size() && cannyProfileHorizontal.get(i) >= minProfile) {
            minProfile = cannyProfileHorizontal.get(i);
            i++;
        }

        while (i < cannyProfileHorizontal.size() && cannyProfileHorizontal.get(i) < minProfile) {
            minProfile = cannyProfileHorizontal.get(i);
            i++;
        }
//        }
        topMargin = i;
//        if (document.topStartsWithPaper()) {
//            topMargin = LayoutProc.getTopMargin(smoothedHorizontalProfile, false);
//        }
        if (topMargin > documentPage.getHeight() / 2) {
            topMargin = LayoutProc.getTopMargin(smoothedHorizontalProfile, (int) (0.2 * documentPage.getHeight()));
        }


        // bottom margin
        i = otsuProfileHorizontal.size() - 1;
        minProfile = Integer.MAX_VALUE;
        boolean bottomStartsWithPaper = otsuProfileHorizontal.get(otsuProfileHorizontal.size() - 1) < documentPage.getWidth() / 2;

        if (!bottomStartsWithPaper) {
            // remove dark border
            while (i > 0 && (otsuProfileHorizontal.get(i) <= minProfile || otsuProfileHorizontal.get(i) > documentPage.getWidth() / 2)) {
                minProfile = otsuProfileHorizontal.get(i);
                i--;
            }
            if (i > 0) {
                minProfile = cannyProfileHorizontal.get(i);
            }
            while (i > 0 && cannyProfileHorizontal.get(i) >= minProfile) {
                minProfile = cannyProfileHorizontal.get(i);
                i--;
            }

            while (i > 0 && cannyProfileHorizontal.get(i) < minProfile) {
                minProfile = cannyProfileHorizontal.get(i);
                i--;
            }
        }
        int bottomMargin = i;
//            bottomMargin = LayoutProc.getBottomMargin(smoothedHorizontalProfile, (int) (0.9 * document.getHeight()));

        if (bottomStartsWithPaper) {
            bottomMargin = LayoutProc.getBottomMargin(smoothedHorizontalProfile, (int) (0.9 * documentPage.getHeight()));
        }
        if (bottomMargin < documentPage.getHeight() / 2) {
            bottomMargin = LayoutProc.getBottomMargin(smoothedHorizontalProfile, (int) (0.8 * documentPage.getHeight()));
        }


        // todo: we need different analysis techniques for different document scan types
        // eg: high quality scans including dark background vs low-res binary scans of pages where more or less of the borders are cropped

        // left margin
        ArrayList<Double> chancePaper = new ArrayList<>();
//        ArrayList<Double> chanceLeft = new ArrayList<>();
//        ArrayList<Double> chanceRight = new ArrayList<>();
        double maxVertical = 1;
        double maxVerticalLeft = 1;
        double maxVerticalRight = 1;
        for (i = 0; i < cannyProfileVertical.size(); i++) {
            if (cannyProfileVertical.get(i) > maxVertical) {
                maxVertical = cannyProfileVertical.get(i);
            }
            if (i < cannyProfileVertical.size() / 2 && cannyProfileVertical.get(i) > maxVerticalLeft) {
                maxVerticalLeft = cannyProfileVertical.get(i);
            }
            if (i >= cannyProfileVertical.size() / 2 && cannyProfileVertical.get(i) > maxVerticalRight) {
                maxVerticalRight = cannyProfileVertical.get(i);
            }
        }
        for (i = 0; i < cannyProfileVertical.size(); i++) {
            if (i < cannyProfileVertical.size() / 2) {
                chancePaper.add((binaryImage.height() * cannyProfileVertical.get(i).doubleValue()) / maxVerticalLeft);
            } else {
                chancePaper.add((binaryImage.height() * cannyProfileVertical.get(i).doubleValue()) / maxVerticalRight);
            }
        }

//        for (i =0; i<cannyProfileVertical.size();i++){
//            if (i < cannyProfileVertical.size()/2){
//                chanceLeft.add(cannyProfileVertical.get(i).doubleValue()/maxVertical);
//            }else{
//                chanceLeft.add(cannyProfileVertical.get(i).doubleValue()/maxVertical);
//            }
//        }


        i = 0;
        minProfile = Integer.MAX_VALUE;
        boolean leftBorderRemoved = false;
        int leftMargin = i;
        if (documentPage.leftStartsWithPaper()) {
            //could this be a single page with borders already removed?
            for (int counter = 0; counter < cannyProfileHorizontal.size() / 10; counter++) {
                if (cannyProfileHorizontal.get(counter) == 0) {
                    leftBorderRemoved = true;
                    break;
                }
            }
        } else {
            while (i < otsuProfileVertical.size() && (otsuProfileVertical.get(i) <= minProfile || otsuProfileVertical.get(i) > documentPage.getHeight() / 2)) {
                minProfile = otsuProfileVertical.get(i);
                i++;
            }

            VisualizationHelper.drawVerticalInkProfileDoubleGray(cannyImage, chancePaper);
//            VisualizationHelper.drawVerticalInkProfile(cannyImage, cannyProfileVertical, smoothCannyProfileVertical);
//            VisualizationHelper.drawVerticalInkProfileInteger(cannyImage, cannyProfileVertical);
            if (_outputDebug) {
                LOG.debug("writing /scratch/images/out-cannyImage.png");
                Imgcodecs.imwrite("/scratch/images/out-cannyImage.png", cannyImage);
            }
            if (i < smoothCannyProfileVertical.size()) {
                minProfile = smoothCannyProfileVertical.get(i).intValue();
            }
            while (i < smoothCannyProfileVertical.size() && smoothCannyProfileVertical.get(i) >= minProfile) {
                minProfile = smoothCannyProfileVertical.get(i).intValue();
                i++;
            }

            while (i < smoothCannyProfileVertical.size() && smoothCannyProfileVertical.get(i) < minProfile) {
                minProfile = smoothCannyProfileVertical.get(i).intValue();
                i++;
            }
            leftMargin = i;
        }
        cannyImage= OpenCVWrapper.release(cannyImage);
        if (!leftBorderRemoved) {
            // we try to fix left margin if it is an open book with a lot of visible page edges
            int whitespace = i;
            while (i < smoothCannyProfileVertical.size() && verticalNoiseProfile.get(i) < stats.getMean() * 1.5) {
                i++;
            }
            whitespace = i - whitespace;
            int noise = i;
            while (i < smoothCannyProfileVertical.size() && verticalNoiseProfile.get(i) > stats.getMean() * 1.5 && otsuProfileVerticalSmoothed.get(i) > otsuStats.getMean()) {
                i++;
            }
            noise = i - noise;
            int maxMargin = documentPage.getWidth() / 3;
            if (documentPage.isDoublePage()) {
                maxMargin /= 2;
            }
            if (noise > documentPage.getWidth() / 50 && i <= maxMargin) {
                leftMargin = i;
            }
            if (documentPage.leftStartsWithPaper() || leftMargin > maxMargin) {
                leftMargin = LayoutProc.getTopMargin(smoothedVerticalProfile, (int) (0.2 * documentPage.getWidth()));
            }
            // end fixing
        }

        if (documentPage.isDoublePage()) {
            if (leftMargin > documentPage.getWidth() / 4) {
                leftMargin = documentPage.getWidth() / 4 - 1;
            }
        } else {
            if (leftMargin > documentPage.getWidth() / 2) {
                leftMargin = documentPage.getWidth() / 2 - 1;
            }
        }

        // right margin
        i = otsuProfileVertical.size() - 1;
        minProfile = Integer.MAX_VALUE;

        if (!documentPage.rightStartsWithPaper()) {
            // remove dark border
            while (i > 0 && (otsuProfileVertical.get(i) <= minProfile || otsuProfileVertical.get(i) > documentPage.getHeight() / 2)) {
                minProfile = otsuProfileVertical.get(i);
                i--;
            }
            if (i > 0) {
                minProfile = cannyProfileVertical.get(i);
            }
            while (i > 0 && cannyProfileVertical.get(i) >= minProfile) {
                minProfile = cannyProfileVertical.get(i);
                i--;
            }

            while (i > 0 && cannyProfileVertical.get(i) < minProfile) {
                minProfile = cannyProfileVertical.get(i);
                i--;
            }
        }

        int rightMargin = i;
        if (documentPage.rightStartsWithPaper()) {
            rightMargin = LayoutProc.getBottomMargin(smoothedVerticalProfile, (int) (0.9 * documentPage.getWidth()));
        }

        if (documentPage.isDoublePage()) {
            if (rightMargin < documentPage.getWidth() * 3 / 4) {
                rightMargin = documentPage.getWidth() * 3 / 4 + 1;
            }
        } else {
            if (rightMargin < documentPage.getWidth() / 2) {
                rightMargin = documentPage.getWidth() / 2 + 1;
            }
        }

        documentPage.setTopMargin(topMargin);
        documentPage.setBottomMargin(bottomMargin);
        documentPage.setLeftMargin(leftMargin);
        documentPage.setRightMargin(rightMargin);
    }

    static List<Double> smoothListDouble(List<Double> profile, int smoothFactor) {
        ArrayList<Double> result = new ArrayList<>();
        for (int i = 0; i < profile.size(); i++) {
            int counter = 0;
            result.add(i, 0d);
            for (int j = -smoothFactor; j <= smoothFactor; j++) {
                if (i + j >= 0 && i + j < profile.size()) {
                    result.set(i, result.get(i) + profile.get(i + j));
                    counter++;
                }
            }
            result.set(i, result.get(i) / counter);
        }
        return result;
    }

    public static List<Double> smoothList(List<Integer> profile, int smoothFactor) {
        ArrayList<Double> result = new ArrayList<>();
        for (int i = 0; i < profile.size(); i++) {
            int counter = 0;
            result.add(i, 0d);
            for (int j = -smoothFactor; j <= smoothFactor; j++) {
                if (i + j >= 0 && i + j < profile.size()) {
                    result.set(i, result.get(i) + profile.get(i + j));
                    counter++;
                }
            }
            result.set(i, result.get(i) / counter);
        }
        return result;
    }

    private static boolean mergeCoCoColor(ConnectedComponent line, ConnectedComponent segment) {
        int startY = line.getY();
        int startX = line.getX();

        int stopY = line.getY() + line.getBitMap().getHeight();
        int stopX = line.getX() + line.getBitMap().getWidth();

        int segmentHeight = segment.getBitMap().getHeight();
        int segmentWidth = segment.getBitMap().getWidth();
        BufferedImage lineBitMap = line.getBitMap();
        BufferedImage segmentBitMap = segment.getBitMap();

        for (int i = startY; i < stopY; i++) {
            for (int j = startX; j < stopX; j++) {
                if (j - segment.getX() >= 0 && i - segment.getY() >= 0 &&
                        j - segment.getX() < segmentWidth && i - segment.getY() < segmentHeight &&
                        lineBitMap.getRGB(j - startX, i - startY) != 0 &&
                        segmentBitMap.getRGB(j - segment.getX(), i - segment.getY()) != 0) {
                    if (segment.getColor() == line.getColor()) {
                        return false;
                    } else {
                        segment.setColor(line.getColor());
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public static void mergeCoCoColors(java.util.List<ConnectedComponent> lineCoCos, java.util.List<ConnectedComponent> segments, int minSize, int maxHeight) {
        //TODO what to do what cocos that are connected to two different line segments?
        for (ConnectedComponent lineCoco : lineCoCos) {
            int lineCoCoHeight = lineCoco.getBitMap().getHeight();
            if (lineCoCoHeight > maxHeight ||
                    lineCoCoHeight < minSize ||
                    lineCoco.getBitMap().getWidth() < minSize) {
                continue;
            }
            for (ConnectedComponent segment : segments) {
                if (segment.getBitMap().getHeight() > maxHeight ||
                        segment.getBitMap().getHeight() < minSize ||
                        segment.getBitMap().getWidth() < minSize) {
                    continue;
                }
                if (segment.getX() + segment.getBitMap().getWidth() < lineCoco.getX()) {
                    continue;
                }
                if (lineCoco.getX() + lineCoco.getBitMap().getWidth() < segment.getX()) {
                    continue;
                }
                if (segment.getY() + segment.getBitMap().getHeight() < lineCoco.getY()) {
                    continue;
                }
                if (lineCoco.getY() + lineCoco.getBitMap().getHeight() < segment.getY()) {
                    continue;
                }

                mergeCoCoColor(lineCoco, segment);
            }
        }

    }

    public static double inkRatio(Mat binaryImage, int startY, int stopY, int startX, int stopX) {
        int inkCounter = 0;
        long size = binaryImage.total() * binaryImage.channels();
        int width = binaryImage.width();
        byte[] data = new byte[(int) size];
        binaryImage.get(0, 0, data);
        for (int y = startY; y < stopY; y++) {
            for (int x = startX; x < stopX; x++) {
                if ((data[y * width + x] & 0xFF) == 255) {
                    inkCounter++;
                }
            }
        }
        return (double) inkCounter / ((stopY - startY) * (stopX - startX));
    }

    private static double getLocalConfidence(int i, List<Double> verticalProfileSmoothWhitespace,
                                             double averageWhitespace, List<Double> verticalProfileSmooth,
                                             Statistics stats, List<Double> smoothCannyProfileVertical, Statistics cannyStats) {
        double localConfidence = 0;
        if (verticalProfileSmooth.get(i) > 0) {
            localConfidence += (verticalProfileSmooth.get(i) - stats.getMean()) / (verticalProfileSmooth.get(i) + stats.getMean());
        }
        if (verticalProfileSmoothWhitespace.get(i) > 0) {
            localConfidence -= (verticalProfileSmoothWhitespace.get(i) - averageWhitespace) / (verticalProfileSmoothWhitespace.get(i) + averageWhitespace);
            if (verticalProfileSmoothWhitespace.get(i) / averageWhitespace > 2) {
                localConfidence -= 1;
            }
        }

        if (smoothCannyProfileVertical.get(i) > 0) {
            localConfidence += (smoothCannyProfileVertical.get(i) - cannyStats.getMean()) / (smoothCannyProfileVertical.get(i) + cannyStats.getMean());
        }
        return localConfidence;
    }

    public static Mat rotate(Mat source, double angle) {
        org.opencv.core.Point center = new org.opencv.core.Point((source.cols() - 1) / 2.0, (source.rows() - 1) / 2.0);

        Mat rotationMatrix2D = Imgproc.getRotationMatrix2D(center, angle, 1.0);
        Rect bbox = new RotatedRect(center, source.size(), angle).boundingRect();

//        Mat destination = Mat.zeros(bbox.height, bbox.width, CV_8UC1);
        Mat destination = OpenCVWrapper.newMat();

        double[] val = rotationMatrix2D.get(0, 2);
        LOG.info("rotationmatrix2d is of type: " + rotationMatrix2D.type());
        safePut(rotationMatrix2D, 0, 2, val[0] + bbox.width / 2.0 - source.cols() / 2.0);

        val = rotationMatrix2D.get(1, 2);
        safePut(rotationMatrix2D, 1, 2, val[0] + bbox.height / 2.0 - source.rows() / 2.0);

        OpenCVWrapper.warpAffine(source, destination, rotationMatrix2D, bbox.size());

        rotationMatrix2D= OpenCVWrapper.release(rotationMatrix2D);

        return destination;
    }

    public static double getDeskewAngle(Mat image) {
        //TODO: calculate correct size for destination image
        double bestAngle = 0;
        long highestHorizontalScore = 0;
        for (double angle = -5.0; angle <= 5.0; angle += 0.1) {
            Mat testMat = rotate(image, angle);

            long sum = LayoutProc.getSumSquaredProfile(testMat);

            if (sum > highestHorizontalScore) {
                highestHorizontalScore = sum;
                bestAngle = angle;
            }
            testMat = OpenCVWrapper.release(testMat);
        }
        return bestAngle;
    }

    private static ArrayList<Integer> lineCrossingsHorizontal(Mat binaryImage, int startY, int stopY, int startX, int stopX) {
        long size = binaryImage.total() * binaryImage.channels();
        byte[] data = new byte[(int) size];
        binaryImage.get(0, 0, data);
        ArrayList<Integer> horizontals = new ArrayList<>();

        for (int y = startY; y < stopY; y++) {
            int crossings = 0;
            boolean lastPixelOn = false;
            for (int x = startX; x < stopX; x++) {
                if (data[y * binaryImage.width() + x] != 0) {
                    if (!lastPixelOn) {
                        crossings++;
                    }
                    lastPixelOn = true;
                } else {
                    lastPixelOn = false;
                }
            }
            horizontals.add(y, crossings);
        }
        return horizontals;
    }

    public static int getBestThreshold(Mat grayScaleImage) {
        return 15; // 15 is a good default threshold, works less well when bleedthrough or other noise is present
        // 10 works ok-ish on faded carbon-copies
    }

    public static Mat darkenImage(String uri) {
        Mat image = OpenCVWrapper.imread(uri);
        LayoutConfiguration configuration = new LayoutConfiguration(image);
        LayoutProc.setOutputDebug(false);
        configuration.setOutputFile(false);
        configuration.setOutputDebug(false);
        LayoutConfiguration.setGlobal(configuration);

        DocumentPage documentPage = new DocumentPage(image, uri);

        Mat mask = OpenCVWrapper.newMat();
        Imgproc.adaptiveThreshold(documentPage.getGrayImage(), mask, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 71, 10);//15);
        Mat maskInverse = OpenCVWrapper.newMat();
        Core.bitwise_not(mask, maskInverse);

        Mat binary = OpenCVWrapper.newMat();
        Imgproc.cvtColor(mask, binary, Imgproc.COLOR_GRAY2BGR);

        Mat background = OpenCVWrapper.newMat();
        Core.bitwise_and(binary, binary, background, maskInverse);


        Mat foreground = OpenCVWrapper.newMat();
        Core.bitwise_and(image, image, foreground, mask);
        Mat result = OpenCVWrapper.newMat();
        Core.add(background, foreground, result);

        Core.addWeighted(result, 0.5, image, 0.5, 0, result, image.depth());
        image = OpenCVWrapper.release(image);
        mask = OpenCVWrapper.release(mask);
        maskInverse = OpenCVWrapper.release(maskInverse);
        binary = OpenCVWrapper.release(binary);
        background = OpenCVWrapper.release(background);
        foreground = OpenCVWrapper.release(foreground);
        return result;
    }

    public static Mat darkenImage(Mat image) {
        LayoutConfiguration configuration = new LayoutConfiguration(image);
        LayoutProc.setOutputDebug(false);
        configuration.setOutputFile(false);
        configuration.setOutputDebug(false);
        LayoutConfiguration.setGlobal(configuration);

        Mat mask = OpenCVWrapper.newMat();
        Mat maskInverse = OpenCVWrapper.newMat();
        Mat grayImage = null;
        if (image.type() != CV_8U) {
            grayImage = OpenCVWrapper.newMat();
            Imgproc.cvtColor(image, grayImage, Imgproc.COLOR_BGR2GRAY);
        } else {
            grayImage = image.clone();
        }

        Imgproc.adaptiveThreshold(grayImage, mask, 255, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 71, 10);//15);
        Core.bitwise_not(mask, maskInverse);

        Mat binary = OpenCVWrapper.newMat();
        Imgproc.cvtColor(mask, binary, Imgproc.COLOR_GRAY2BGR);

        Mat background = OpenCVWrapper.newMat();
        Core.bitwise_and(binary, binary, background, maskInverse);


        Mat foreground = OpenCVWrapper.newMat();
        Core.bitwise_and(image, image, foreground, mask);
        Mat result = OpenCVWrapper.newMat();
        Core.add(background, foreground, result);

        Core.addWeighted(result, 0.5, image, 0.5, 0, result, image.depth());
        mask = OpenCVWrapper.release(mask);
        maskInverse = OpenCVWrapper.release(maskInverse);
        binary = OpenCVWrapper.release(binary);
        background = OpenCVWrapper.release(background);
        foreground= OpenCVWrapper.release(foreground);
        return result;
    }

    public static double intersectOverUnion(Mat first, Mat second) {
        Mat intersect = OpenCVWrapper.newMat();
        Mat union = OpenCVWrapper.newMat();
        Core.bitwise_and(first, second, intersect);
        Core.bitwise_or(first, second, union);
        double countIntersect = Core.countNonZero(intersect);
        double countUnion = Core.countNonZero(union);
        intersect = OpenCVWrapper.release(intersect);
        union = OpenCVWrapper.release(union);
        return countIntersect / countUnion;
    }

    public static double intersectOverUnion(Rect first, Rect second) {
        return intersect(first, second) / union(first, second);
    }

    public static double union(Rect first, Rect second) {
        double result = first.area() + second.area() - intersect(first, second);
        return result;
    }

    public static double intersect(Rect first, Rect second) {

        if (first.x > second.x + second.width || second.x > first.x + first.width) {
            return 0;
        }
        if (first.y > second.y + second.height || second.y > first.y + first.height) {
            return 0;
        }
        int leftStart = Math.max(first.x, second.x);
        int topStart = Math.max(first.y, second.y);
        int rightStop = Math.min(first.x + first.width, second.x + second.width);
        int bottomStop = Math.min(first.y + first.height, second.y + second.height);

        double result = ((rightStop - leftStart) * (bottomStop - topStart));

        return result;
    }

    public static Rect getBoundingBox(List<Point> points) {
        if (points == null || points.isEmpty()) {
            throw new IllegalArgumentException("Points list cannot be null or empty");
        }
        int xStart = Integer.MAX_VALUE;
        int xStop = Integer.MIN_VALUE;
        int yStart = Integer.MAX_VALUE;
        int yStop = Integer.MIN_VALUE;

        for (Point point : points) {
            int x = (int) point.x;
            int y = (int) point.y;

            if (x < xStart) {
                xStart = x;
            }
            if (x > xStop) {
                xStop = x;
            }
            if (y < yStart) {
                yStart = y;
            }
            if (y > yStop) {
                yStop = y;
            }
        }

        return new Rect(xStart, yStart, xStop - xStart, yStop - yStart);
    }

    public static Rect getBoundingBoxTextLine(TextLine textLine){
        return getBoundingBox(StringConverter.stringToPoint(textLine.getCoords().getPoints()));
    }

    public static Rect growCluster(Rect region, TextLine textLine){
        Rect textLineBoundingBox = getBoundingBoxTextLine(textLine);
        int xStart = Math.min(region.x, textLineBoundingBox.x);
        int yStart = Math.min(region.y, textLineBoundingBox.y);
        int xStop = Math.max(region.x + region.width, textLineBoundingBox.x + textLineBoundingBox.width);
        int yStop = Math.max(region.y + region.height, textLineBoundingBox.y + textLineBoundingBox.height);
        return new Rect(xStart, yStart, xStop - xStart, yStop - yStart);
    }
    public static Rect getBoundingBoxTextLines(List<TextLine> textLines) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (TextLine textLine : textLines) {
            Rect textLineBoundingBox = getBoundingBoxTextLine(textLine);
            if (textLineBoundingBox.x < minX) {
                minX = textLineBoundingBox.x;
            }
            if (textLineBoundingBox.x + textLineBoundingBox.width > maxX) {
                maxX = textLineBoundingBox.x +textLineBoundingBox.width;
            }
            if (textLineBoundingBox.y < minY) {
                minY = textLineBoundingBox.y;
            }
            if (textLineBoundingBox.y + textLineBoundingBox.height> maxY) {
                maxY = textLineBoundingBox.y + textLineBoundingBox.height;
            }

        }
        Rect rect = new Rect(minX, minY, maxX - minX, maxY - minY);
        return rect;
    }

    private static boolean isBelow(TextRegion existing, TextRegion couldBeBelow) {
        Rect firstRect = getBoundingBox(StringConverter.stringToPoint(existing.getCoords().getPoints()));
        Rect secondRect = getBoundingBox(StringConverter.stringToPoint(couldBeBelow.getCoords().getPoints()));
        return firstRect.y  < secondRect.y;
    }

    private static boolean isAbove(TextRegion existing, TextRegion couldBeBelow) {
        return !isBelow(existing, couldBeBelow);
    }

    private static boolean isCompletelyBelow(TextRegion existing, TextRegion couldBeBelow){
        Rect firstRect = getBoundingBox(StringConverter.stringToPoint(existing.getCoords().getPoints()));
        Rect secondRect = getBoundingBox(StringConverter.stringToPoint(couldBeBelow.getCoords().getPoints()));
        return firstRect.x < secondRect.x && firstRect.x+firstRect.width > secondRect.x+secondRect.width && isBelow(existing, couldBeBelow);
    }

    private static boolean isCompletelyAbove(TextRegion existing, TextRegion couldBeAbove) {
        Rect firstRect = getBoundingBox(StringConverter.stringToPoint(existing.getCoords().getPoints()));
        Rect secondRect = getBoundingBox(StringConverter.stringToPoint(couldBeAbove.getCoords().getPoints()));
        return firstRect.x < secondRect.x && firstRect.x + firstRect.width > secondRect.x + secondRect.width && isAbove(existing, couldBeAbove);
    }

    private static boolean isRightOf(TextRegion existing, TextRegion couldBeRightOf) {
        Rect firstRect = getBoundingBox(StringConverter.stringToPoint(existing.getCoords().getPoints()));
        Rect secondRect = getBoundingBox(StringConverter.stringToPoint(couldBeRightOf.getCoords().getPoints()));
        return firstRect.x + firstRect.width < secondRect.x;
    }

    public static void reorderRegions(PcGts page, List<String> regionOrderList) {
        List<TextRegion> finalTextRegions = new ArrayList<>();
        List<TextRegion> tmpRegionList = new ArrayList<>();
        OrderedGroup orderedGroup = new OrderedGroup();
        List<RegionRefIndexed> refList = new ArrayList<>();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        for (TextRegion textRegion : page.getPage().getTextRegions()) {
            tmpRegionList.add(textRegion);
            Rect boundingBox = getBoundingBox(StringConverter.stringToPoint(textRegion.getCoords().getPoints()));
            if (boundingBox.x < minX) {
                minX = boundingBox.x;
            }
            if (boundingBox.y < minY) {
                minY = boundingBox.y;
            }
        }

        int counter = 0;
        for (String regionTypeOrder : regionOrderList) {
//            LOG.debug("regionTypeOrder: " +regionTypeOrder);
            List<TextRegion> newSortedTextRegionsBatch = new ArrayList<>();
            List<TextRegion> unsortedTextRegions = new ArrayList<>();
            for (TextRegion textRegion : tmpRegionList) {
                //custom="structure {type:paragraph;}">
                String custom = textRegion.getCustom();
                if (custom != null) {
                    String[] splitted = custom.split(":");
                    if (splitted.length > 1) {
                        custom = splitted[1].split(";")[0].trim();
                    }
                }
                if ((custom != null && custom.equals(regionTypeOrder))
                        || (textRegion.getRegionType() != null && textRegion.getRegionType().equals(regionTypeOrder))
                        || regionTypeOrder == null) {
//                    LOG.debug("textRegion.getRegionType(): " + textRegion.getRegionType());
                    unsortedTextRegions.add(textRegion);
                }
            }
            tmpRegionList.removeAll(unsortedTextRegions);

            while (unsortedTextRegions.size() > 0) {
                TextRegion best = null;

                // initialization: select top left
                if (newSortedTextRegionsBatch.size() == 0) {
                    best = getTopLeftRegion(unsortedTextRegions, minX, minY, best);
                    unsortedTextRegions.remove(best);
                    newSortedTextRegionsBatch.add(best);
                } else {
                    // based on last found region select next region
                    TextRegion previousRegion = newSortedTextRegionsBatch.get(newSortedTextRegionsBatch.size() - 1);
                    Rect boundingBoxPreviousRegion = getBoundingBox(StringConverter.stringToPoint(previousRegion.getCoords().getPoints()));

                    double bestDistance = Double.MAX_VALUE;
                    // find region that matches bottom left with top left and is not right of previous region
                    for (TextRegion textRegion : unsortedTextRegions) {
                        Rect boundingBox = getBoundingBox(StringConverter.stringToPoint(textRegion.getCoords().getPoints()));
                        double currentDistance =
                                StringConverter.distance(
                                        new Point(boundingBoxPreviousRegion.x, boundingBoxPreviousRegion.y + boundingBoxPreviousRegion.height),
                                        new Point(boundingBox.x, boundingBox.y));
                        if ((best == null ||currentDistance < bestDistance) && !isRightOf(previousRegion, textRegion)) {
                            best = textRegion;
                            bestDistance = currentDistance;
                        }
                    }
                    // find region that matches bottom center with top center
                    if (previousRegion.getTextLines() != null && previousRegion.getTextLines().size() > 0) {
                        boundingBoxPreviousRegion = getBoundingBox(StringConverter.stringToPoint(previousRegion.getTextLines().get(previousRegion.getTextLines().size() - 1).getCoords().getPoints()));
                    }
                    for (TextRegion textRegion : unsortedTextRegions) {
                        Rect boundingBox = getBoundingBox(StringConverter.stringToPoint(textRegion.getCoords().getPoints()));
                        if (textRegion.getTextLines() != null && textRegion.getTextLines().size() > 0) {
                            boundingBox = getBoundingBox(StringConverter.stringToPoint(textRegion.getTextLines().get(0).getCoords().getPoints()));
                        }
                        double currentDistance =
                                StringConverter.distance(
                                        new Point(boundingBoxPreviousRegion.x + boundingBoxPreviousRegion.width / 2, boundingBoxPreviousRegion.y + boundingBoxPreviousRegion.height),
                                        new Point(boundingBox.x + boundingBox.width / 2, boundingBox.y));
                        if ((best == null ||currentDistance < bestDistance) && !isRightOf(previousRegion, textRegion)) {
                            best = textRegion;
                            bestDistance = currentDistance;
                        }
                    }

                    for (TextRegion textRegion : unsortedTextRegions) {
                        Rect boundingBox = getBoundingBox(StringConverter.stringToPoint(textRegion.getCoords().getPoints()));
                        double currentDistance = boundingBox.y - boundingBoxPreviousRegion.y;
                        if (isCompletelyBelow(previousRegion, textRegion) && currentDistance<bestDistance) {
                            bestDistance = currentDistance;
                            best = textRegion;
                        }
                    }

                    boolean foundCompletelyAbove = false;
                    for (TextRegion textRegion : unsortedTextRegions) {
                        if (isCompletelyAbove(previousRegion, textRegion)) {
                            best = textRegion;
                            foundCompletelyAbove = true;
                            break;
                        }
                    }
                    if (foundCompletelyAbove) {
                        unsortedTextRegions.remove(best);
                        newSortedTextRegionsBatch.add(newSortedTextRegionsBatch.size() - 1, best);
                        continue;
                    }

                    if (best == null) {
                        best = getTopLeftRegion(unsortedTextRegions, minX, minY, best);
                    }

                    unsortedTextRegions.remove(best);
                    newSortedTextRegionsBatch.add(best);
                }

            }
            for (TextRegion best : newSortedTextRegionsBatch) {
                counter = PageUtils.addRegionRefIndex(refList, counter, best);
            }

            finalTextRegions.addAll(newSortedTextRegionsBatch);
        }

        page.getPage().setTextRegions(finalTextRegions);
        orderedGroup.setRegionRefIndexedList(refList);
        if (!refList.isEmpty()) {
            ReadingOrder readingOrder = new ReadingOrder();
            readingOrder.setOrderedGroup(orderedGroup);
            page.getPage().setReadingOrder(readingOrder);
        }
    }


    private static TextRegion getTopLeftRegion(List<TextRegion> textRegions, int x, int y, TextRegion best) {
        double bestDistance = Double.MAX_VALUE;
        for (TextRegion textRegion : textRegions) {
            Rect boundingBox = getBoundingBox(StringConverter.stringToPoint(textRegion.getCoords().getPoints()));
            double currentDistance =
                    StringConverter.distance(
                            new Point(x, y),
                            new Point(boundingBox.x, boundingBox.y));
            if (best == null ||
                    currentDistance < bestDistance
            ) {
                best = textRegion;
                bestDistance = currentDistance;
            }
        }
        return best;
    }

    public static void calcSeamImage(Mat energyMat, Size newSize, Mat destination) {
        Mat seamImageResized = OpenCVWrapper.newMat();
        Imgproc.resize(energyMat, seamImageResized, newSize, 0, 0, INTER_NEAREST);

        int height = seamImageResized.height();
        int width = seamImageResized.width();
        int type = seamImageResized.type();

        if (type == CV_32F) {
            calcSeamImageFloat(seamImageResized, height, width);
        } else if (type == CV_64F) {
            calcSeamImageDouble(seamImageResized, height, width);
        } else {
            // Per-pixel fallback path for any unexpected types. Kept for safety; the
            // production callers always pass CV_32F.
            calcSeamImageGeneric(seamImageResized, height, width);
        }

        Imgproc.resize(seamImageResized, destination, new Size(energyMat.width(), energyMat.height()));
        seamImageResized = OpenCVWrapper.release(seamImageResized); // Release seamImage
    }

    private static void calcSeamImageFloat(Mat seamImageResized, int height, int width) {
        // Bulk-read the entire resized seam image into a single float[] and run the
        // column-major DP in pure Java. Replaces a nested per-pixel getSafeDouble /
        // safePut pair (each allocating a fresh float[1]) with two JNI transitions.
        float[] data = new float[height * width];
        seamImageResized.get(0, 0, data);
        for (int j = 0; j < width; j++) {
            if (j == 0) {
                // First column has no predecessor; lowest is 0 by definition.
                for (int i = 0; i < height; i++) {
                    // data[i*width + j] += 0; no-op.
                }
                continue;
            }
            int prevCol = j - 1;
            for (int i = 0; i < height; i++) {
                int yUp = i > 0 ? i - 1 : 0;
                int yDn = i + 1 < height ? i + 1 : height - 1;
                float a = data[yUp * width + prevCol];
                float b = data[i * width + prevCol];
                float c = data[yDn * width + prevCol];
                float lowest = a < b ? a : b;
                if (c < lowest) lowest = c;
                int idx = i * width + j;
                data[idx] = lowest + data[idx];
            }
        }
        seamImageResized.put(0, 0, data);
    }

    private static void calcSeamImageDouble(Mat seamImageResized, int height, int width) {
        double[] data = new double[height * width];
        seamImageResized.get(0, 0, data);
        for (int j = 0; j < width; j++) {
            if (j == 0) {
                continue;
            }
            int prevCol = j - 1;
            for (int i = 0; i < height; i++) {
                int yUp = i > 0 ? i - 1 : 0;
                int yDn = i + 1 < height ? i + 1 : height - 1;
                double a = data[yUp * width + prevCol];
                double b = data[i * width + prevCol];
                double c = data[yDn * width + prevCol];
                double lowest = a < b ? a : b;
                if (c < lowest) lowest = c;
                int idx = i * width + j;
                data[idx] = lowest + data[idx];
            }
        }
        seamImageResized.put(0, 0, data);
    }

    private static void calcSeamImageGeneric(Mat seamImageResized, int height, int width) {
        for (int j = 0; j < width; j++) {
            for (int i = 0; i < height; i++) {
                double lowest = Double.MAX_VALUE;
                for (int m = -1; m <= 1; m++) {
                    int y = i + m;
                    int x = j - 1;
                    if (y < 0) {
                        y = 0;
                    }
                    if (y >= height) {
                        y = height - 1;
                    }
                    if (x < 0) {
                        lowest = 0;
                    } else {
                        double value = getSafeDouble(seamImageResized, y, x);
                        if (value < lowest || lowest == Double.MAX_VALUE) {
                            lowest = value;
                        }
                    }
                }
                if (lowest == Double.MAX_VALUE) {
                    LOG.error("Lowest is still Double.MAX_VALUE. i = " + i + " j = " + j + " seamImage.height() = " + height + " seamImage.width() = " + width);
                    lowest = 0;
                }
                double putter = lowest + getSafeDouble(seamImageResized, i, j);
                safePut(seamImageResized, i, j, putter);
            }
        }
    }

    public static double getMeanAngle(List<Double> anglesDeg) {
        double x = 0.0;
        double y = 0.0;

        for (double angleD : anglesDeg) {
            double angleR = Math.toRadians(angleD);
            x += Math.cos(angleR);
            y += Math.sin(angleR);
        }
        double avgR = Math.atan2(y / anglesDeg.size(), x / anglesDeg.size());
        return Math.toDegrees(avgR);
    }

    public static double getMainAngle(List<Point> baseLinePoints) {
        Point lastPoint = null;
        List<Double> data = new ArrayList<>();
        for (Point point : baseLinePoints) {
            if (lastPoint == null) {
                lastPoint = point;
                continue;
            }
            double y = point.y - lastPoint.y;
            double x = point.x - lastPoint.x;
            double angle = Math.toDegrees(Math.atan2(y, x));
            double distance = distance(point, lastPoint);
            for (int i = 0; i < distance; i++) {
                data.add(angle);
            }
            lastPoint = point;
        }
        // This is to handle BIG CAPITALS at the start of a sentence
        //TODO: deal with rotated images
        if (data.size() > 200) {
            return getMeanAngle(data.subList(150, data.size() - 1));
//            statistics = new Statistics(data, 150, data.size() - 1);
//            return Math.toDegrees(statistics.getMean());
        }

        return getMeanAngle(data);
//        statistics = new Statistics(data);
//        double median = statistics.median();
////        double mean = statistics.getMean();
////
////        if (Math.abs(median)<Math.abs(mean)){
////            return mean;
////        }
//        return Math.toDegrees(median);
    }

    public static Rect getBoundingBoxBaseLine(TextLine textLine) {
        if (textLine.getBaseline() == null || Strings.isNullOrEmpty(textLine.getBaseline().getPoints())) {
            LOG.warn("No baseline for textline: " + textLine.getId());
            getBoundingBoxTextLine(textLine);
        }
        List<Point> baseLinePoints = StringConverter.stringToPoint(textLine.getBaseline().getPoints());
        return getBoundingBox(baseLinePoints);
    }

    public static TextLine closestLineAbove(TextLine current, List<TextLine> textLines) {
        Rect currentRect = getBoundingBoxBaseLine(current);
        TextLine closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (TextLine textLine : textLines) {
            Rect otherRect = getBoundingBoxBaseLine(textLine);
            int distance = currentRect.y - otherRect.y;
            if (otherRect.x + otherRect.width > currentRect.x
                    && otherRect.x < currentRect.x + currentRect.width
                    && otherRect.y < currentRect.y
                    && distance < closestDistance) {
                closest = textLine;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private static TextLineGeometry closestLineAbove(TextLineGeometry current, List<TextLineGeometry> textLines) {
        Rect currentRect = current.baselineRect;
        TextLineGeometry closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (TextLineGeometry textLine : textLines) {
            Rect otherRect = textLine.baselineRect;
            int distance = currentRect.y - otherRect.y;
            if (otherRect.x + otherRect.width > currentRect.x
                    && otherRect.x < currentRect.x + currentRect.width
                    && otherRect.y < currentRect.y
                    && distance < closestDistance) {
                closest = textLine;
                closestDistance = distance;
            }
        }
        return closest;
    }

    public static synchronized TextLine closestLineBelow(TextLine current, List<TextLine> textLines) {
        Rect currentRect = getBoundingBoxBaseLine(current);
        TextLine closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (TextLine textLine : textLines) {
            Rect otherRect = getBoundingBoxBaseLine(textLine);
            int distance = otherRect.y - currentRect.y;
            if (otherRect.x + otherRect.width > currentRect.x
                    && otherRect.x < currentRect.x + currentRect.width
                    && otherRect.y > currentRect.y
                    && distance < closestDistance) {
                closest = textLine;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private static TextLineGeometry closestLineBelow(TextLineGeometry current, List<TextLineGeometry> textLines) {
        Rect currentRect = current.baselineRect;
        TextLineGeometry closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (TextLineGeometry textLine : textLines) {
            Rect otherRect = textLine.baselineRect;
            int distance = otherRect.y - currentRect.y;
            if (otherRect.x + otherRect.width > currentRect.x
                    && otherRect.x < currentRect.x + currentRect.width
                    && otherRect.y > currentRect.y
                    && distance < closestDistance) {
                closest = textLine;
                closestDistance = distance;
            }
        }
        return closest;
    }

    public static String convertToBoxFile(Mat binaryLineStrip, String text) {
        return convertToBoxFile(binaryLineStrip.height(), binaryLineStrip.width(), text);
    }

    public static String convertToBoxFile(int height, int width, String text) {
        int left = 0;
        int right = width;
        int top = height;
        int bottom = 0;
        StringWriter box = new StringWriter();
        for (int j = 0; j < text.length(); j++) {
            String character = text.substring(j, j + 1);
            box.append(String.format("%s %s %s %s %s 0%n", character, left, bottom, right, top));
        }
        box.append(String.format("\t %s %s %s %s 0%n", left, bottom, right, top));

        return box.toString();
    }

    private static void energyImage(Mat grayImage, Mat destination) {
        if (grayImage ==null){
            LOG.error("grayImage is null");
            throw new RuntimeException("grayImage is null");
        }
        if (grayImage.size().width == 0 || grayImage.size().height == 0) {
            LOG.error("broken grayImage");
            throw new RuntimeException("broken grayImage");
        }
        if (destination == null){
            LOG.error("destination is null");
            throw new RuntimeException("destination is null");
        }

        Mat grayImageInverted = OpenCVWrapper.newMat(grayImage.size(), grayImage.type());
        OpenCVWrapper.bitwise_not(grayImage, grayImageInverted);
        if (grayImageInverted.size().width == 0 || grayImageInverted.size().height == 0) {
            LOG.error("broken grayImageInverted");
            throw new RuntimeException("broken grayImageInverted");
        }

        Mat sobel1 = OpenCVWrapper.newMat(grayImageInverted.size(), grayImageInverted.type());
        OpenCVWrapper.Sobel(grayImageInverted, -1, 1, 0, 3, 1, -15, sobel1);
        Mat sobel2 = OpenCVWrapper.newMat(grayImageInverted.size(), grayImageInverted.type());
        OpenCVWrapper.Sobel(grayImageInverted, -1, 0, 1, 3, 1, -15, sobel2);

        grayImageInverted = OpenCVWrapper.release(grayImageInverted);

        Mat combined = OpenCVWrapper.newMat();
        OpenCVWrapper.addWeighted(sobel1, sobel2, combined, destination.type());
        if (combined.size().width == 0 || combined.size().height == 0) {
            LOG.error("broken combined");
            throw new RuntimeException("broken combined");
        }
        sobel1 = OpenCVWrapper.release(sobel1);
        sobel2 = OpenCVWrapper.release(sobel2);
        Mat binary = OpenCVWrapper.newMat(grayImage.size(), CV_8U);
        OpenCVWrapper.adaptiveThreshold(grayImage, binary, 21);

        OpenCVWrapper.addWeighted(combined, binary, combined, destination.type());
        binary = OpenCVWrapper.release(binary);

        OpenCVWrapper.GaussianBlur(combined, destination);
        combined = OpenCVWrapper.release(combined);
    }

    private static void drawBaselines(String identifier, List<TextLine> textlines, Mat image, int thickness) {
        for (TextLine textLine : textlines) {
            ArrayList<Point> points = StringConverter.stringToPoint(textLine.getBaseline().getPoints());
            drawBaseline(identifier, points, image, thickness);
        }
    }

    private static void drawBaselinesFromGeometry(String identifier, List<TextLineGeometry> textlines, Mat image, int thickness) {
        for (TextLineGeometry textLine : textlines) {
            drawBaseline(identifier, textLine.baselinePoints, image, thickness);
        }
    }

    private static void drawBaseline(String identifier, List<Point> points, Mat image, int thickness) {
        Point lastPoint = null;
        Scalar scalar;
        if (image.type() == CV_8UC1) {
            scalar = new Scalar(255);
        } else if (image.type() == CV_8UC3) {
            scalar = new Scalar(255, 255, 255);
        } else if (image.type() == CV_32F) {
            scalar = new Scalar(255);
        } else if (image.type() == CV_64F) {
            scalar = new Scalar(255);
        } else {
            throw new RuntimeException("Unsupported image type");
        }
        for (Point point : points) {
            Point clippedPoint = new Point(point.x, point.y);
            if (clippedPoint.x < 0) {
                clippedPoint.x = 0;
            }
            if (clippedPoint.x >= image.width()) {
                clippedPoint.x = image.width() - 1;
            }
            if (clippedPoint.y < 0) {
                clippedPoint.y = 0;
            }
            if (clippedPoint.y >= image.height()) {
                clippedPoint.y = image.height() - 1;
            }
            if (lastPoint != null) {
                OpenCVWrapper.line(identifier, image, lastPoint, clippedPoint, scalar, thickness);
            }
            lastPoint = clippedPoint;
        }
    }

    private static double getLocalInterlineDistance(double interlineDistance, Rect baselineRect, TextLine closestAbove) {
        double localInterlineDistance = interlineDistance;
        if (closestAbove != null) {
            Rect closestAboveRect = getBoundingBoxBaseLine(closestAbove);
            int tmpDistance = baselineRect.y - (closestAboveRect.y + closestAboveRect.height);
            if (tmpDistance > interlineDistance) {
                if (2 * interlineDistance > tmpDistance) {
                    localInterlineDistance = tmpDistance;
                } else {
                    localInterlineDistance = 2 * interlineDistance;
                }
            }
        } else {
            localInterlineDistance = 2 * interlineDistance;
        }
        return localInterlineDistance;
    }

    private static double getYStartTop(List<Point> baseLinePoints, double localInterlineDistance) {
        double min = Double.MAX_VALUE;
        for (Point point : baseLinePoints) {
            double current = point.y - localInterlineDistance;
            if (current < min) {
                min = current;
            }
        }
        if (min < 0) {
            min = 0;
        }
        return min;
    }

    private static double getYStartBottom(Mat blurred, double xHeight, List<Point> baseLinePoints) {
        double max = Double.MIN_VALUE;
        for (Point point : baseLinePoints) {
            double current = point.y + xHeight;
            if (current > max) {
                max = current;
            }
        }
        if (max >= blurred.height()) {
            max = blurred.height() - 1;
        }
        return max;
    }

    private static int getXStart(Mat blurred, int xMargin, List<Point> baseLinePoints) {
        int xStart = (int) (baseLinePoints.get(baseLinePoints.size() - 1).x) + xMargin;
        if (xStart >= blurred.width()) {
            xStart = blurred.width() - 1;
        }
        return xStart;
    }

    private static int getXStop(int xMargin, List<Point> baseLinePoints) {
        int xStop = (int) (baseLinePoints.get(0).x) - xMargin;
        if (xStop < 0) {
            xStop = 0;
        }
        return xStop;
    }

    //this function finds the path with the highest energy
    public static List<Point> findSeam(Mat seamImage) {
        List<Point> points = new ArrayList<>();
        int yStart = 0;
        int xStart = seamImage.width() - 1;
        double maxEnergy = -1;
        for (int i = 0; i < seamImage.rows(); i++) {
            double energy = getSafeDouble(seamImage, i, xStart);
            if (energy > maxEnergy) {
                yStart = i;
                maxEnergy = energy;
            }
        }

        points.add(new Point(xStart, yStart));
        for (int j = xStart; j > 0; j--) {
            double first = getSafeDouble(seamImage,yStart - 1, j);
            double middle = getSafeDouble(seamImage, yStart, j);
            double last = getSafeDouble(seamImage, yStart + 1, j);
            if (first > middle && first > last) {
                yStart--;
            } else if (last > middle) {
                yStart++;
            } else if (middle >= first && middle >= last) {

            } else {
                new Exception("error").printStackTrace();
            }
            if (yStart < 1) {
                yStart = 1;
            }
            if (yStart > seamImage.height() - 2) {
                yStart = seamImage.height() - 2;
            }
            points.add(new Point(j, yStart));
        }

        points = Lists.reverse(points);
        return points;
    }

    private static List<Point> findSeamOld(Mat seamImage, int xStart, int seamOffsetFromBaseline, int xStop, boolean preferDown,
                                           boolean preferUp, List<Point> baseLinePoints, int yOffset, int xHeight, int xOffset,
                                           int margin, int interLineDistance) {
        List<Point> baseLinePointsExpanded = StringConverter.expandPointList(baseLinePoints);
        for (Point point : baseLinePointsExpanded) {
            point.y = point.y - yOffset;
            point.x = (point.x - xOffset) - margin;
        }
        List<Point> points = new ArrayList<>();
        int yStart = (int) (baseLinePoints.get(baseLinePoints.size() - 1).y - yOffset) + seamOffsetFromBaseline;
        if (yStart >= seamImage.height()) {
            yStart = seamImage.height() - 1;
        }
        if (yStart < 0) {
            yStart = 0;
        }

        points.add(new Point(xStart, yStart));
        for (int j = xStart; j >= xStop; j--) {
            if (j >= seamImage.width()) {
                new Exception("writing outside image width5").printStackTrace();
            }
            if (j < 0) {
                new Exception("writing outside image width6").printStackTrace();
            }
            if (yStart < 1) {
                yStart = 1;
            }
            if (yStart > seamImage.height() - 2) {
                yStart = seamImage.height() - 2;
            }
            double first = seamImage.get(yStart - 1, j)[0];
            double middle = seamImage.get(yStart, j)[0];
            double last = seamImage.get(yStart + 1, j)[0];
            int flexMargin = 50;
            boolean outside = false;
//            if (preferDown || preferUp) {
//                for (Point baseLinePoint : baseLinePointsExpanded) {
//                    if ((int) (baseLinePoint.x) == j) {
//                        int currentBaselinePoint = (int) (baseLinePoint.y);
//                        int currentBottomTopBorder = currentBaselinePoint - xHeight;
//                        if (preferDown) {
//                            int targetLine = currentBottomTopBorder - yStart;
//                            if (targetLine < 0) {
//                                flexMargin = (int) Math.sqrt(Math.abs(targetLine));
////                                flexMargin = -(int) Math.sqrt(targetLine);
////                                flexMargin = 0;
//                            } else {
//                                flexMargin = -(int) Math.sqrt(targetLine);
////                                flexMargin = 0;
//                            }
//                            if (yStart >= currentBaselinePoint && yStart >= 2) {
////                                yStart--;
//                                outside = true;
//                            }
//                        }
//                        if (preferUp) {
//                            int targetLine = currentBaselinePoint - yStart + seamOffsetFromBaseline;
////                            int targetLine = seamOffsetFromBaseline - ((int) (point.y - (yOffset)) + (xHeight) / 2);//(int) point.y - (yOffset) + (interLineDistance - xHeight) / 2;
//                            if (targetLine < 0) {
////                                flexMargin = -(int) Math.sqrt(Math.abs(targetLine));
//                                flexMargin = 0;
//                            } else {
//                                flexMargin = -targetLine;
//                            }
//                            if (yStart < (baseLinePoint.y) && yStart < seamImage.height() - 2) {
////                                yStart++;
//                                outside = true;
//                            }
//
//                        }
////                        flexMargin *=flexMargin;
//                        break;
//                    }
//                }
//            }
//            if (preferUp) {
//                first += flexMargin;
//                middle += flexMargin;
//            }
//            if (preferDown) {
//                last += flexMargin;
//                middle += flexMargin;
//            }
            if (first < middle && first < last) {
                yStart--;
            } else if (last < middle) {
                yStart++;
            } else if (middle <= first && middle <= last) {

            } else {
                new Exception("error").printStackTrace();
            }
            if (yStart >= seamImage.height()) {
                yStart = seamImage.height() - 1;
            }
            if (yStart < 0) {
                yStart = 0;
            }

            if (preferUp && outside && yStart < seamImage.height() - 3) {
                yStart += 2;
            }
            if (preferDown && outside && yStart >= 2) {
                yStart -= 2;
            }
            points.add(new Point(j, yStart));
        }

        Lists.reverse(points);
        Point lastPoint = null;
        for (Point point : points) {
            if (lastPoint != null) {
                if (lastPoint.x < 0) {
                    new Exception("writing outside image width1").printStackTrace();
                }
                if (lastPoint.x >= seamImage.width()) {
                    new Exception("writing outside image width2").printStackTrace();
                }
                if (lastPoint.y < 0) {
                    new Exception("writing outside image height1").printStackTrace();
                }
                if (lastPoint.y >= seamImage.height()) {
                    new Exception("writing outside image height2").printStackTrace();
                }
                if (point.x < 0) {
                    new Exception("writing outside image width1").printStackTrace();
                }
                if (point.x >= seamImage.width()) {
                    new Exception("writing outside image width2").printStackTrace();
                }
                if (point.y < 0) {
                    new Exception("writing outside image height1").printStackTrace();
                }
                if (point.y >= seamImage.height()) {
                    new Exception("writing outside image height2").printStackTrace();
                }
            }
            lastPoint = point;
        }
        return points;
    }

    private static List<Point> findSeam(String identifier, Mat seamImage, double seamOffsetFromBaseline,
                                        boolean preferDown, boolean preferUp, List<Point> baseLinePoints,
                                        double yOffset, int xOffset, int margin) {
        List<Point> baseLinePointsExpanded = StringConverter.expandPointList(baseLinePoints);
        for (Point point : baseLinePointsExpanded) {
            point.y = point.y - yOffset;
            point.x = (point.x - xOffset) - margin;
        }
        List<Point> points = new ArrayList<>();
        int xStart = seamImage.width()-1;
        int xStop = 0;
        int yStart = (int) ((baseLinePointsExpanded.get(baseLinePointsExpanded.size() - 1).y) + seamOffsetFromBaseline);
        if (yStart >= seamImage.height()) {
            LOG.warn(identifier + " yStart >= seamImage.height() " + yStart + " >= " + seamImage.height());
            yStart = seamImage.height() - 1;
        }
        if (yStart < 0) {
            LOG.warn(identifier + " yStart < 0 " + yStart);
            yStart = 0;
        }

        points.add(new Point(xStart, yStart));
        for (int j = xStart; j >= xStop; j--) {
            if (j >= seamImage.width()) {
                new Exception(identifier + " writing outside image width5: " + j + " : " + seamImage.width()).printStackTrace();
            }
            if (j < 0) {
                new Exception(identifier + "writing outside image width6: " + j).printStackTrace();
            }
            if (yStart < 1) {
                yStart = 1;
            }
            if (yStart > seamImage.height() - 2) {
                yStart = seamImage.height() - 2;
            }
            if (yStart < 1) {
                new Exception(identifier + "image too small? seamImage.height(): " + seamImage.height()).printStackTrace();
            }
            double first = getSafeDouble(seamImage,yStart - 1, j);
            double middle = getSafeDouble(seamImage, yStart, j);
            double last = getSafeDouble(seamImage, yStart + 1, j);
            boolean outside = false;
            if (first < middle && first < last) {
                yStart--;
            } else if (last < middle) {
                yStart++;
            } else if (middle <= first && middle <= last) {

            } else {
                LOG.error("first: " + first);
                LOG.error("middle: " + middle);
                LOG.error("last: " + last);
                LOG.error("yStart: " + yStart);
                LOG.error("j: " + j);
                new Exception("error, we should never ever get here. Only times i got here: I had bad RAM").printStackTrace();
            }
            if (yStart >= seamImage.height()) {
                yStart = seamImage.height() - 1;
            }
            if (yStart < 0) {
                yStart = 0;
            }

            if (preferUp && outside && yStart < seamImage.height() - 3) {
                yStart += 2;
            }
            if (preferDown && outside && yStart >= 2) {
                yStart -= 2;
            }
            points.add(new Point(j, yStart));
        }

        Lists.reverse(points);
        Point lastPoint = null;
        for (Point point : points) {
            if (lastPoint != null) {
                if (lastPoint.x < 0) {
                    new Exception("writing outside image width1").printStackTrace();
                }
                if (lastPoint.x >= seamImage.width()) {
                    new Exception("writing outside image width2").printStackTrace();
                }
                if (lastPoint.y < 0) {
                    new Exception("writing outside image height1").printStackTrace();
                }
                if (lastPoint.y >= seamImage.height()) {
                    new Exception("writing outside image height2").printStackTrace();
                }
                if (point.x < 0) {
                    new Exception("writing outside image width1").printStackTrace();
                }
                if (point.x >= seamImage.width()) {
                    new Exception("writing outside image width2").printStackTrace();
                }
                if (point.y < 0) {
                    new Exception("writing outside image height1").printStackTrace();
                }
                if (point.y >= seamImage.height()) {
                    new Exception("writing outside image height2").printStackTrace();
                }
            }
            lastPoint = point;
        }
        return points;
    }

    private static synchronized void drawSeam(Mat colorizedSubmat, List<Point> topPoints) {
        Point lastPoint = null;
        for (Point point : topPoints) {
            if (point != null) {
                lastPoint = point;
                continue;
            }
            Imgproc.line(colorizedSubmat, lastPoint, point, new Scalar(255));
        }
    }

    public static void recalculateTextLineContoursFromBaselines(String identifier, Mat image, PcGts page,
                                                                int minimumInterlineDistance, int thickness,
                                                                int minimumBaselineThickness, boolean ignoreBroken) {
        recalculateTextLineContoursFromBaselines(identifier, image, page, 1, minimumInterlineDistance, thickness,
                minimumBaselineThickness, ignoreBroken);
    }

    /**
     * Recalculates the textline contours from the baselines
     * @param identifier identifier for logging
     * @param image image
     * @param page pageXML
     * @param scaleDownFactor scale down factor
     * @param minimumInterlineDistance minimum interline distance
     * @param thickness thickness of the baseline
     */
    public static void recalculateTextLineContoursFromBaselines(String identifier, Mat image, PcGts page,
                                                                double scaleDownFactor, int minimumInterlineDistance,
                                                                int thickness, int minimumBaselineThickness, boolean ignoreBroken) {
        Mat grayImage = OpenCVWrapper.newMat(image.size(), CV_8UC1);
        OpenCVWrapper.cvtColor(image, grayImage);
        Mat energyImage = new Mat(grayImage.size(), CV_32F);
//         Mat energyImage = OpenCVWrapper.newMat(grayImage.size(), CV_64F);

        energyImage(grayImage, energyImage);
        if (energyImage.size().width == 0 || energyImage.size().height == 0) {
            LOG.error("broken energyImage");
            throw new RuntimeException("broken energyImage");
        }
        if (energyImage.type() != CV_32F) {
            LOG.error("energyImage type is not CV_32F, but: " + energyImage.type());
            throw new RuntimeException("energyImage type is not CV_32F, but: " + energyImage.type());
        }
        List<TextLine> allLines = new ArrayList<>();
        for (TextRegion textRegion : page.getPage().getTextRegions()) {
            allLines.addAll(textRegion.getTextLines());
        }
        ArrayList<TextLine> linesToRemove = new ArrayList<>();
        for (TextLine textLine : allLines) {
            if (textLine.getBaseline() == null || Strings.isNullOrEmpty(textLine.getBaseline().getPoints())) {
                if (ignoreBroken) {
                    LOG.warn(identifier + " textline " + textLine.getId() + " has no baseline, ignoring it");
                    linesToRemove.add(textLine);
                }
            }
        }
        allLines.removeAll(linesToRemove);

        List<TextLineGeometry> allLineGeometries = new ArrayList<>();
        Map<TextLine, TextLineGeometry> geometryByTextLine = new IdentityHashMap<>();
        for (TextLine textLine : allLines) {
            List<Point> baselinePoints = StringConverter.stringToPoint(textLine.getBaseline().getPoints());
            if (baselinePoints.size() <= 1) {
                continue;
            }
            TextLineGeometry textLineGeometry = new TextLineGeometry(textLine, baselinePoints);
            allLineGeometries.add(textLineGeometry);
            geometryByTextLine.put(textLine, textLineGeometry);
        }

        Mat baselineImage = new Mat(energyImage.size(), energyImage.type());
        energyImage.copyTo(baselineImage);
        drawBaselinesFromGeometry(identifier, allLineGeometries, baselineImage, thickness);
//         Mat baselineImage = OpenCVWrapper.newMat(energyImage.size(), CV_64F);
//         energyImage.convertTo(baselineImage, CV_64F);
//         drawBaselines(identifier, allLines, baselineImage, thickness);

        int counter = 0;
        double interlineDistance = LayoutProc.interlineMedianFromGeometry(allLineGeometries, minimumInterlineDistance);//94;
        LOG.info(identifier + " interline distance: " + interlineDistance);

        Stopwatch stopwatch = Stopwatch.createStarted();

        for (TextRegion textRegion : page.getPage().getTextRegions()) {
            for (TextLine textLine : textRegion.getTextLines()) {
                if (textLine.getBaseline() == null || Strings.isNullOrEmpty(textLine.getBaseline().getPoints())) {
                    if (ignoreBroken) {
                        LOG.warn(identifier + " textline " + textLine.getId() + " has no baseline, ignoring it");
                        continue;
                    } else {
                        LOG.error(identifier + " textline " + textLine.getId() + " has no baseline, cannot recalculate contours");
                        throw new RuntimeException("Textline has no baseline: " + textLine.getId());
                    }
                }
                TextLineGeometry textLineGeometry = geometryByTextLine.get(textLine);
                if (textLineGeometry == null) {
                    continue;
                }
                counter = recalculateTextLine(identifier, scaleDownFactor, textLineGeometry, interlineDistance, stopwatch,
                        allLineGeometries, energyImage, baselineImage, counter, minimumBaselineThickness);
            }
        }
        grayImage = OpenCVWrapper.release(grayImage);
        energyImage = OpenCVWrapper.release(energyImage);
        baselineImage = OpenCVWrapper.release(baselineImage);
        LOG.info(identifier + " textlines: " + (counter));
        if (counter > 0) {
            LOG.info(identifier + " average textline took: " + (stopwatch.elapsed(TimeUnit.MILLISECONDS) / counter));
        }
    }

    private static int recalculateTextLine(String identifier, double scaleDownFactor, TextLineGeometry textLineGeometry,
                                           double interlineDistance, Stopwatch stopwatch, List<TextLineGeometry> allLines,
                                           Mat energyImage, Mat baselineImage, int counter, int minimumBaselineThickness) {
        TextLine textLine = textLineGeometry.textLine;
        double xHeightBasedOnInterline = interlineDistance / 3;
        if (xHeightBasedOnInterline < (MINIMUM_XHEIGHT)) {
            xHeightBasedOnInterline = (MINIMUM_XHEIGHT);
        }
        int baselineThickness = (int) (xHeightBasedOnInterline / (2 * scaleDownFactor));
        LOG.debug(identifier + " baselineThickness: " + baselineThickness);
        if (baselineThickness < minimumBaselineThickness) {
            baselineThickness = minimumBaselineThickness;
        }
        long startTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
        int xMargin = (int) xHeightBasedOnInterline;
        List<Point> baseLinePoints = textLineGeometry.baselinePoints;
        if (baseLinePoints.size() <= 1) {
            // no base line, use existing textline Coords
            return counter;
        }
        Rect baselineRect = textLineGeometry.baselineRect;
        TextLineGeometry closestAbove = LayoutProc.closestLineAbove(textLineGeometry, allLines);
        double localInterlineDistance = getLocalInterlineDistance(interlineDistance, baselineRect, null);

        double yStartTop = getYStartTop(baseLinePoints, localInterlineDistance);
        double yStartBottom = getYStartBottom(energyImage, xHeightBasedOnInterline, baseLinePoints);

        int xStart = getXStart(energyImage, xMargin, baseLinePoints);
        int xStop = getXStop(xMargin, baseLinePoints);

        Rect roi = new Rect(xStop, (int) yStartTop, xStart - xStop, (int) (yStartBottom - yStartTop));
        if (roi.height <= 0 || roi.width <= 0) {
            return counter;
        }
        if (roi.height + roi.y > energyImage.height()
                || roi.width + roi.x > energyImage.width()) {
            return counter;
        }
        Mat baselineImageSubmat = baselineImage.submat(roi);
        Mat energyImageSubmat = energyImage.submat(roi);
        Mat averageMat = new Mat(roi.height, roi.width, energyImage.type(), Core.mean(energyImageSubmat));
        energyImageSubmat = OpenCVWrapper.release(energyImageSubmat);

        if (!baselineImageSubmat.size().equals(averageMat.size()) || baselineImageSubmat.type() != averageMat.type()) {
            LOG.error("Matrix dimensions or types are not compatible for subtraction. baselineImageSubmat.size(): " + baselineImageSubmat.size() + " averageMat.size(): " + averageMat.size() + " baselineImageSubmat.type(): " + baselineImageSubmat.type() + " averageMat.type(): " + averageMat.type());
            throw new RuntimeException("Matrix dimensions or types are not compatible for subtraction.");
        }

        Mat clonedMat = OpenCVWrapper.newMat();
        try {
            Core.subtract(baselineImageSubmat, averageMat, clonedMat);
        }catch (CvException cvException){
            LOG.error("baselineImageSubmat.channels(): " + baselineImageSubmat.channels());
            LOG.error("averageMat.channels(): " + averageMat.channels());
            LOG.error("baselineImageSubmat.size(): " + baselineImageSubmat.size());
            LOG.error("averageMat.size(): " + averageMat.size());
            LOG.error("baselineImageSubmat.type(): " + baselineImageSubmat.type());
            LOG.error("averageMat.type(): " + averageMat.type());
            LOG.error("baselineImageSubmat: " + baselineImageSubmat);
            LOG.error("averageMat: " + averageMat);
            LOG.error("clonedMat: " + clonedMat);
            throw cvException;
        }
        baselineImageSubmat = OpenCVWrapper.release(baselineImageSubmat);
        averageMat = OpenCVWrapper.release(averageMat);

        int newWidth = (int) Math.ceil(clonedMat.width() / scaleDownFactor);
        int newHeight = (int) Math.ceil(clonedMat.height() / scaleDownFactor);
        Size newSize = new Size(newWidth, newHeight);

        Mat seamImageTop = OpenCVWrapper.newMat();


        // no-go area above the baseline closest above this baseline
        if (closestAbove != null) {
            for (Point point : expandPointList(closestAbove.baselinePoints)) {
                if (point.x - roi.x < 0 || point.x - roi.x >= clonedMat.width()) {
                    continue;
                }
                int yTarget = (int) point.y - roi.y;
                if (yTarget > 0 && yTarget < clonedMat.height()) {
                    Imgproc.line(
                            clonedMat,
                            new Point(point.x - roi.x, 0),
                            new Point(point.x - roi.x, yTarget),
                            new Scalar(SEAM_NO_GO_ENERGY),
                            1);
                }
            }
        }

        // no-go area of baseline itself
        Point lastPoint = null;
        for (Point point : baseLinePoints) {
            if (lastPoint != null) {
                Imgproc.line(clonedMat,
                        new Point(point.x - roi.x, point.y - roi.y),
                        new Point(point.x - roi.x, lastPoint.y - roi.y),
                        new Scalar(SEAM_NO_GO_ENERGY),
                        baselineThickness);
            }
            lastPoint = point;
        }

        // no-go area below this baseline
        for (Point point : expandPointList(baseLinePoints)) {
            Imgproc.line(clonedMat,
                    new Point(point.x - roi.x, point.y - roi.y),
                    new Point(point.x - roi.x, clonedMat.height()),
                    new Scalar(SEAM_NO_GO_ENERGY),
                    1);
        }


        LayoutProc.calcSeamImage(clonedMat, newSize, seamImageTop);
        if (seamImageTop.type()!= energyImage.type()){
            LOG.error("seamImageTop.type()!= energyImage.type()");
        }
        clonedMat = OpenCVWrapper.release(clonedMat);
        if (seamImageTop.height() <= 2) {
            LOG.warn(identifier + " seamImageTop.height() <= 2");
            seamImageTop = OpenCVWrapper.release(seamImageTop);
            return counter;
        }


        List<Point> contourPoints = findSeam(
                identifier,
                seamImageTop,
                -(1.5 * xHeightBasedOnInterline),
                true,
                false,
                baseLinePoints,
                roi.y,
                roi.x,
                xMargin);
        seamImageTop = OpenCVWrapper.release(seamImageTop);

        for (Point point : contourPoints) {
            point.x += roi.x;
            point.y += roi.y;
        }


        /// bottom line
        yStartTop = baselineRect.y;
        if (yStartTop < 0) {
            yStartTop = 0;
        }
        if (yStartTop >= energyImage.height()) {
            yStartTop = energyImage.height() - 1;
        }

        yStartBottom = baselineRect.y + (baselineRect.height - 1) + interlineDistance;
        if (yStartBottom >= energyImage.height()) {
            yStartBottom = energyImage.height() - 1;
        }

        if (yStartBottom - yStartTop <= 0) {
            return counter;
        }
        roi = new Rect(xStop, (int) yStartTop, xStart - xStop, (int) (yStartBottom - yStartTop));
        Mat tmpSubmat2 = energyImage.submat(roi);
        Mat average2 = new Mat(roi.size(), energyImage.type(), Core.mean(tmpSubmat2));
        tmpSubmat2 = OpenCVWrapper.release(tmpSubmat2);

        baselineImageSubmat = baselineImage.submat(roi);
        Mat cloned2 = new Mat(roi.size(), energyImage.type());
        Core.subtract(baselineImageSubmat, average2, cloned2);

        average2 = OpenCVWrapper.release(average2);
        baselineImageSubmat = OpenCVWrapper.release(baselineImageSubmat);

        List<Point> localPoints = new ArrayList<>();
        for (Point point : baseLinePoints) {
            localPoints.add(new Point(point.x - xStop - xMargin, point.y - yStartTop));
        }
        for (Point point : baseLinePoints) {
            localPoints.add(new Point(point.x - xStop - xMargin, point.y - yStartTop));
        }
        Point lastBaseLinePoint = null;
        for (Point point : localPoints) {
            if (lastBaseLinePoint != null
                    && lastBaseLinePoint.x >= 0
                    && lastBaseLinePoint.x < cloned2.width()
                    && lastBaseLinePoint.y >= 0
                    && lastBaseLinePoint.y < cloned2.height()
                    && point.x >= 0
                    && point.x < cloned2.width()
                    && point.y >= 0
                    && point.y < cloned2.height()
            ) {
                Imgproc.line(cloned2, lastBaseLinePoint, point, new Scalar(255* getLength(baseLinePoints)), (int) (10 / scaleDownFactor));
            }
            lastBaseLinePoint = point;
        }

        TextLineGeometry closestBelow = LayoutProc.closestLineBelow(textLineGeometry, allLines);

        int newWidthBottom = (int) Math.ceil(cloned2.width() / scaleDownFactor);
        int newHeightBottom = (int) Math.ceil(cloned2.height() / scaleDownFactor);
        Size newSizeBottom = new Size(newWidthBottom, newHeightBottom);
        Mat seamImageBottom = OpenCVWrapper.newMat();

        //        no-go of the baseline itself
        lastBaseLinePoint = null;
        for (Point point : baseLinePoints) {
            if (lastBaseLinePoint != null) {
                Imgproc.line(cloned2,
                        new Point(point.x - roi.x, point.y - roi.y),
                        new Point(point.x - roi.x, lastBaseLinePoint.y - roi.y),
                        new Scalar(SEAM_NO_GO_ENERGY),
                        1);
            }
            lastBaseLinePoint = point;
        }

//        no-go area above  this baseline
        for (Point point : expandPointList(baseLinePoints)) {
            Imgproc.line(cloned2,
                    new Point(point.x - roi.x, point.y - roi.y),
                    new Point(point.x - roi.x, 0),
                    new Scalar(SEAM_NO_GO_ENERGY),
                    1);
        }

//        no-go area of baseline below this baseline

        if (closestBelow != null) {
            for (Point point : expandPointList(closestBelow.baselinePoints)) {
                int yTarget = (int) point.y - roi.y;
                if (yTarget > 0
                        && yTarget < cloned2.height()
                        && point.x - roi.x >= 0
                        && point.x - roi.x < cloned2.width()) {
                    Imgproc.line(
                            cloned2,
                            new Point(point.x - roi.x, yTarget),
                            new Point(point.x - roi.x, cloned2.height()),
                            new Scalar(SEAM_NO_GO_ENERGY),
                            baselineThickness);
                }
            }
        }

        LayoutProc.calcSeamImage(cloned2, newSizeBottom, seamImageBottom);
        cloned2 = OpenCVWrapper.release(cloned2);
        if (seamImageBottom.height() <= 2) {
            LOG.error(identifier + " seamImageBottom.height() <= 2");
            seamImageBottom = OpenCVWrapper.release(seamImageBottom);
            return counter;
        }

        List<Point> bottomPoints = findSeam(
                identifier,
                seamImageBottom,
                xHeightBasedOnInterline / 2,
                false,
                true,
                baseLinePoints,
                roi.y,
                roi.x,
                xMargin);

        seamImageBottom = OpenCVWrapper.release(seamImageBottom);

        for (Point point : bottomPoints) {
            point.x += roi.x;
            point.y += roi.y;
        }

        contourPoints = Lists.reverse(contourPoints);

        contourPoints.addAll(bottomPoints);

        for (Point point : contourPoints) {
            if (point.x < 0) {
                new Exception("point.x<0").printStackTrace();
            }
            if (point.y < 0) {
                new Exception("point.y<0").printStackTrace();
            }
        }
        List<Point> newPoints = simplifyPolygon(contourPoints);
        textLine.getCoords().setPoints(StringConverter.pointToString(simplifyPolygon(newPoints, 5)));
        if (textLine.getTextStyle() == null) {
            textLine.setTextStyle(new TextStyle());
        }
        textLine.getTextStyle().setxHeight((int) xHeightBasedOnInterline);

        counter++;
        return counter;
    }

    private static void shrinkPoints(List<Point> points, double shrinkFactor) {
        for (Point point : points) {
            point.x = point.x / shrinkFactor;
            point.y = point.y / shrinkFactor;
        }
    }

    private static RotatedRect getRotatedRect(List<Point> baseLinePoints) {
        MatOfPoint2f sourceMat = OpenCVWrapper.newMatOfPoint2f();
        sourceMat.fromList(baseLinePoints);

        RotatedRect rect = Imgproc.minAreaRect(sourceMat);
        sourceMat = OpenCVWrapper.release(sourceMat);
        return rect;
    }

    private static List<Point> warpPoints(List<Point> points, Mat perspectiveMat) {
        MatOfPoint2f matOfPoint = OpenCVWrapper.newMatOfPoint2f();
        matOfPoint.fromList(points);
        MatOfPoint2f matOfPointResult = OpenCVWrapper.newMatOfPoint2f();
        if (matOfPoint.channels() + 1 != perspectiveMat.cols()) {
            new Exception("matOfPoint.channels()+1 != perspectiveMat.cols()").printStackTrace();
        }
        Core.perspectiveTransform(matOfPoint, matOfPointResult, perspectiveMat);
        List<Point> returnPoints = matOfPointResult.toList();
        matOfPoint = OpenCVWrapper.release(matOfPoint);
        matOfPointResult = OpenCVWrapper.release(matOfPointResult);
        return returnPoints;
    }

    private static MatOfPoint2f getDestinationMat(RotatedRect rotatedRect, double radians, Point point1a, Point point2a, Point point3a, Point point4a) {
        MatOfPoint2f dst;
        radians = -radians;
        double x = point1a.x - rotatedRect.center.x;
        double y = point1a.y - rotatedRect.center.y;
        Point point1b = new Point(x * Math.cos(radians) - y * Math.sin(radians), x * Math.sin(radians) + y * Math.cos(radians));
        point1b.x += rotatedRect.center.x;
        point1b.y += rotatedRect.center.y;
        x = point2a.x - rotatedRect.center.x;
        y = point2a.y - rotatedRect.center.y;
        Point point2b = new Point(x * Math.cos(radians) - y * Math.sin(radians), x * Math.sin(radians) + y * Math.cos(radians));
        point2b.x += rotatedRect.center.x;
        point2b.y += rotatedRect.center.y;
        x = point3a.x - rotatedRect.center.x;
        y = point3a.y - rotatedRect.center.y;
        Point point3b = new Point(x * Math.cos(radians) - y * Math.sin(radians), x * Math.sin(radians) + y * Math.cos(radians));
        point3b.x += rotatedRect.center.x;
        point3b.y += rotatedRect.center.y;
        x = point4a.x - rotatedRect.center.x;
        y = point4a.y - rotatedRect.center.y;
        Point point4b = new Point(x * Math.cos(radians) - y * Math.sin(radians), x * Math.sin(radians) + y * Math.cos(radians));
        point4b.x += rotatedRect.center.x;
        point4b.y += rotatedRect.center.y;
        dst = OpenCVWrapper.newMatOfPoint2f();
        dst.fromArray(point1b, point2b, point3b, point4b);
        return dst;
    }

    private static void getBaselineMat(Mat baselineMat, List<Point> points, double xHeight) {

        for (Point point : points) {
            Point topPoint = new Point(point.x, point.y - xHeight);
            Point bottomPoint = new Point(point.x, point.y);
            if (topPoint.x <= 0) {
                topPoint.x = 0;
            }
            if (bottomPoint.x <= 0) {
                bottomPoint.x = 0;
            }
            if (topPoint.y >= baselineMat.height()) {
                topPoint.y = baselineMat.height() - 1;
            }
            if (topPoint.x > baselineMat.width() - 1) {
                topPoint.x = baselineMat.width() - 1;
            }

            Imgproc.line(baselineMat, topPoint, bottomPoint, new Scalar(255), 1);
        }
    }

    //    private static Mat extractRotatedRect(Mat image,RotatedRect rotatedRect,  List<Point> points, int above, int below, double mainAngle){
//        Mat rotated = rotate(image.submat(rotatedRect.boundingRect()), mainAngle);
//        rotatedRect.points();
//        return rotated;
//    }
    /*
    Gets a text line from an image based on the baseline and contours. Text line is rotated to its main horizontal axis(straightened).
     */
    public static Mat getBinaryLineStripOld(Mat image, List<Point> contourPoints, List<Point> baseLinePoints, double xHeight, boolean includeMask) {
        Mat finalOutput = null;
        Mat rotationMat = null;
        Mat perspectiveMat = null;
        MatOfPoint sourceMat = null;
        MatOfPoint2f src = null;
        MatOfPoint2f dst = null;
        Mat deskewedSubmat = null;
        List<Point> expandedBaseline = StringConverter.expandPointList(baseLinePoints);
        Rect baseLineBox = LayoutProc.getBoundingBox(baseLinePoints);

        // if just one point: ignore
        if (baseLinePoints.size() < 2) {
            return null;
        }
        // if too small just ignore
        if (baseLineBox.width < 10) {
            return null;
        }
        // FIXME RUTGERCHECK: this also includes vertical and upside down lines.
        if (baseLinePoints.get(0).x > baseLinePoints.get(baseLinePoints.size() - 1).x) {
            return null;
        }

        if (baseLineBox.y < 0
                || baseLineBox.y + baseLineBox.height > image.height()
                || baseLineBox.x < 0
                || baseLineBox.x + baseLineBox.width > image.width()
        ) {
            LOG.error("error baselineBox outside image");
            return null;
        }
        RotatedRect rotatedRect = getRotatedRect(baseLinePoints);
        double mainAngle = LayoutProc.getMainAngle(baseLinePoints);
        double angle = rotatedRect.angle;
        angle = mainAngle;
        while (angle < -45) {
            angle += 90;
        }
        while (angle > 45) {
            angle -= 90;
        }
        rotationMat = Imgproc.getRotationMatrix2D(rotatedRect.center, angle, 1);

        double radians = Math.toRadians(angle);
        double sin = Math.abs(Math.sin(radians));
        double cos = Math.abs(Math.cos(radians));
        int newWidth = (int) Math.ceil(image.width() * cos + image.height() * sin);
        int newHeight = (int) Math.ceil(image.width() * sin + image.height() * cos);

        Mat deskewedImage = OpenCVWrapper.newMat(new Size(newWidth, newHeight), image.type());
//        Mat deskewedImage = OpenCVWrapper.newMat();
        OpenCVWrapper.warpAffine(image, deskewedImage, rotationMat, new Size(newWidth, newHeight));
        rotationMat = OpenCVWrapper.release(rotationMat);

        Rect cuttingRect = rotatedRect.boundingRect();
        cuttingRect.y = (int) (rotatedRect.center.y - 2 * xHeight);
        cuttingRect.height = (int) (3 * xHeight);
        if (cuttingRect.x + cuttingRect.width > deskewedImage.width()) {
            cuttingRect.width = deskewedImage.width() - cuttingRect.x;
        }
        if (cuttingRect.y < 0
                || cuttingRect.y + cuttingRect.height >= deskewedImage.height() - 1
                || cuttingRect.x < 0
                || cuttingRect.x + cuttingRect.width >= deskewedImage.width() - 1) {
            //do nothing
            // TODO: fixme. Due to rotation x or y might fall outside the image.
            LOG.error("dropping out this line due to going outside the box after rotation");
        } else {
//            Mat tmpSubmat = null;
            try {
                Mat deskewedRoi = deskewedImage.submat(cuttingRect);
                deskewedSubmat = deskewedRoi.clone();
                deskewedRoi = OpenCVWrapper.release(deskewedRoi);
            } catch (Exception ex) {
                LOG.error("Could not create deskewed submat", ex);
            }
//            deskewedSubmat = tmpSubmat.clone();
//            tmpSubmat = OpenCVWrapper.release(tmpSubmat);
        }
        deskewedImage = OpenCVWrapper.release(deskewedImage);

        if (deskewedSubmat != null) {
            Point point1a = new Point(0, 0);
            Point point2a = new Point(image.width() - 1, 0);
            Point point3a = new Point(0, image.height() - 1);
            Point point4a = new Point(image.width() - 1, image.height() - 1);
            src = OpenCVWrapper.newMatOfPoint2f();
            src.fromArray(point1a, point2a, point3a, point4a);
            dst = getDestinationMat(rotatedRect, radians, point1a, point2a, point3a, point4a);

            perspectiveMat = Imgproc.getPerspectiveTransform(src, dst);
            expandedBaseline = warpPoints(expandedBaseline, perspectiveMat);
            for (Point point : expandedBaseline) {
                point.x -= cuttingRect.x;
                point.y -= cuttingRect.y;
            }
            Point offset = new Point(cuttingRect.x, cuttingRect.y);
//            Mat baselineMat = Mat.zeros(cuttingRect.size(), CV_8UC1);
//            getBaselineMat(baselineMat, expandedBaseline, xHeight);
//            baseLineMat = OpenCVWrapper.release(baseLineMat);

//            contourPoints = new ArrayList<>();
//            contourPoints.add(new Point(0,0));
//            contourPoints.add(new Point(image.width(), image.height()));
            ArrayList<Point> clonedPoints = new ArrayList<>();
            List<Point> tmpPoints = warpPoints(contourPoints, perspectiveMat);
            perspectiveMat = OpenCVWrapper.release(perspectiveMat);
            for (Point point : tmpPoints) {
                clonedPoints.add(new Point(point.x - cuttingRect.x, point.y - cuttingRect.y));
            }

            sourceMat = OpenCVWrapper.newMatOfPoint();
            sourceMat.fromList(clonedPoints);
            List<MatOfPoint> finalPoints = new ArrayList<>();
            finalPoints.add(sourceMat);
            Mat mask = OpenCVWrapper.newMat(cuttingRect.size(), CV_8UC1, new Scalar(0));
            Scalar color = new Scalar(127);
            Imgproc.fillPoly(mask, finalPoints, color);
            for (int i = 1; i < expandedBaseline.size(); i++) {
                Imgproc.line(mask, expandedBaseline.get(i - 1), expandedBaseline.get(i), new Scalar(255), (int) xHeight / 3);
            }
            int size = deskewedSubmat.height() / 2;
            if (size % 2 == 0) {
                size++;
            }
            if (size >= deskewedSubmat.width()) {
                size = deskewedSubmat.width() / 2;
                if (size % 2 == 0) {
                    size++;
                }
            }
            if (size > 1) {

                List<Mat> splittedImage = new ArrayList<>();
                Core.split(deskewedSubmat, splittedImage);
                deskewedSubmat = OpenCVWrapper.release(deskewedSubmat);
                List<Mat> toMerge = null;
                if (includeMask) {
                    toMerge = Arrays.asList(splittedImage.get(0), splittedImage.get(1), splittedImage.get(2), mask);//, mask);
                } else {
                    toMerge = Arrays.asList(splittedImage.get(0), splittedImage.get(1), splittedImage.get(2));//, mask);
                }
                if (mask.channels() != 1) {
                    new Exception(" mask.channels() != 1").printStackTrace();
                }
                if (mask.type() != CV_8UC1) {
                    new Exception(" mask.type() != CV_8UC1").printStackTrace();
                }

                try {
                    finalOutput=null;
                    if (toMerge.size() == 3) {
                        finalOutput = OpenCVWrapper.newMat();
                    }else if (toMerge.size() == 4){
                        finalOutput = OpenCVWrapper.newMat();
                    }else{
                        throw new RuntimeException("toMerge.size() " + toMerge.size());
                    }
                    OpenCVWrapper.merge(toMerge, finalOutput);
                    OpenCVWrapper.release(splittedImage.get(0));
                    OpenCVWrapper.release(splittedImage.get(1));
                    OpenCVWrapper.release(splittedImage.get(2));
                    mask = OpenCVWrapper.release(mask);
                } catch (Exception ex) {
                    LOG.error("Unexpected error", ex);
                    LOG.error("toMerge.size() " + toMerge.size());
                    LOG.error("deskewSubmat.size() " + deskewedSubmat.size());
                    LOG.error("mask.size() " + mask.size());
                    new Exception("here").printStackTrace();
                }
            }
        }

        perspectiveMat = OpenCVWrapper.release(perspectiveMat);
        deskewedSubmat = OpenCVWrapper.release(deskewedSubmat);
        sourceMat = OpenCVWrapper.release(sourceMat);
        src= OpenCVWrapper.release(src);
        dst = OpenCVWrapper.release(dst);
        return finalOutput;
    }

    public static Mat convertToBinaryImage(Mat grayImage) {
//        Mat binaryImage = Mat.zeros(new Size(grayImage.width(), grayImage.height()), CvType.CV_8UC1);
        Mat binaryImage = OpenCVWrapper.newMat();
        Imgproc.adaptiveThreshold(grayImage, binaryImage, 255, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, bestBinarizationBlockSize, bestBinarizationThreshold);
        return binaryImage;
    }

    public static Boolean rotatedRectContainsPoint(RotatedRect rect, Point point) {
        // rotate around rectangle center by -rectAngle
        double sin = Math.sin(-rect.angle);
        double cos = Math.cos(-rect.angle);

        // set origin to rect center
        Point newPoint = new Point(point.x - rect.center.x, point.y - rect.center.y);
        // rotate
        newPoint = new Point(newPoint.x * cos - newPoint.y * sin, newPoint.x * sin + newPoint.y * cos);
        // put origin back
        newPoint = new Point(newPoint.x + rect.center.x, newPoint.y + rect.center.y);

        // check if our transformed point is in the rectangle, which is no longer
        // rotated relative to the point

        return newPoint.x >= rect.center.x - rect.size.width / 2
                && newPoint.x <= rect.center.x + rect.size.width / 2
                && newPoint.y >= rect.center.y - rect.size.height / 2 && newPoint.y <= rect.center.y + rect.size.height / 2;
    }


    private static Point rotatePoint(Point point, Point oldCenter, Point newCenter, double rotation) {
        Point result = new Point(point.x - newCenter.x, point.y - newCenter.y);
        result = new Point(result.x * Math.cos(rotation) - result.y * Math.sin(rotation), result.x * Math.sin(rotation) + result.y * Math.cos(rotation));
        result = new Point(result.x + oldCenter.x, result.y + oldCenter.y);
        return result;
    }

    public static boolean insideImage(Rect rectangle, Mat image) {
        return rectangle.y >= 0
                && rectangle.y + rectangle.height <= image.height()
                && rectangle.x >= 0
                && rectangle.x + rectangle.width <= image.width();
    }

    /*
Gets a text line from an image based on the baseline and contours. Text line is rotated to its main horizontal axis(straightened).
 */
    public static BinaryLineStrip getBinaryLineStrip(String identifier, Mat image, List<Point> contourPoints, List<Point> baseLinePoints,
                                                     Integer xHeight, boolean includeMask, int minWidth, String textLineId,
                                                     double aboveMultiplier, double belowMultiplier, double besideMultiplier) {
        Stopwatch stopwatch = Stopwatch.createStarted();
        Mat finalFinalOutputMat = null;
        Mat deskewedSubmat;
        fixPoints(baseLinePoints, image.width(), image.height());
        List<Point> expandedBaseline = StringConverter.expandPointList(baseLinePoints);
        Rect baseLineBox = LayoutProc.getBoundingBox(baseLinePoints);

        // if just one point: ignore
        if (baseLinePoints.size() < 2) {
            LOG.debug(identifier + ": just one point: ignore");
//            just return textLine contour Mat
            BinaryLineStrip binaryLineStrip = getBinaryLineStripFromContours(image, contourPoints);

            return binaryLineStrip;
        }

        // if too small just ignore
        double baselineLength = StringConverter.calculateBaselineLength(baseLinePoints);
        if (baselineLength < minWidth) {
            LOG.debug(identifier + ": too small just ignore");
            return null;
        }
        // FIXME RUTGERCHECK: this also includes vertical and upside down lines.
//        if (baseLinePoints.get(0).x > baseLinePoints.get(baseLinePoints.size() - 1).x) {
//            return null;
//        }

        if (!insideImage(baseLineBox, image)) {
            LOG.error(identifier + ": error baselineBox outside image");
            return null;
        }
        double mainAngle = LayoutProc.getMainAngle(baseLinePoints);
        double radians = Math.toRadians(mainAngle);

        double above = aboveMultiplier * xHeight;
        double below = belowMultiplier * xHeight;
        double beside = besideMultiplier * xHeight;

        List<Point> newPoints = new ArrayList<>();
        double tmpAngle = -radians;
        for (Point point : baseLinePoints) {
            // add point above baseline point
            double x = 0 * Math.cos(tmpAngle) - above * Math.sin(tmpAngle);
            double y = 0 * Math.sin(tmpAngle) + above * Math.cos(tmpAngle);
            Point newPoint = new Point(point.x - x, point.y - y);
            newPoints.add(newPoint);
            // add point below baseline point
            x = 0 * Math.cos(tmpAngle) + below * Math.sin(tmpAngle);
            y = 0 * Math.sin(tmpAngle) - below * Math.cos(tmpAngle);
            newPoint = new Point(point.x - x, point.y - y);
            newPoints.add(newPoint);
            // add point besides baseline point. Really should be only necessary for for and last point
            x = beside * Math.cos(tmpAngle);
            y = beside * Math.sin(tmpAngle);
            newPoint = new Point(point.x - x, point.y - y);
            newPoints.add(newPoint);
            x = -beside * Math.cos(tmpAngle);
            y = -beside * Math.sin(tmpAngle);
            newPoint = new Point(point.x - x, point.y - y);
            newPoints.add(newPoint);
        }
        Point pivotPoint = getPivotPoint(baseLinePoints);
        RotatedRect rotatedRect = new RotatedRect(pivotPoint, new Size(100, 100), mainAngle);
        Mat sourcePoints = OpenCVWrapper.newMat();
        Imgproc.boxPoints(rotatedRect, sourcePoints);

//        MatOfPoint2f destiniationPoints = new MatOfPoint2f();
//        destiniationPoints.fromArray(
//                new Point(pivotPoint.x - 50, pivotPoint.y + 50),
//                new Point(pivotPoint.x - 50, pivotPoint.y - 50),
//                new Point(pivotPoint.x + 50, pivotPoint.y - 50),
//                new Point(pivotPoint.x + 50, pivotPoint.y + 50)
//        );

//        Mat perspectiveMat = getPerspectiveTransform(sourcePoints, destiniationPoints);
        List<Point> warpedNewPoints = new ArrayList<>();// = warpPoints(newPoints, perspectiveMat);
        for (Point point : newPoints) {
            Point result = rotatePoint(point, pivotPoint, pivotPoint, -radians);
            warpedNewPoints.add(result);
        }

        MatOfPoint warpedNewPointsMat = new MatOfPoint();
        warpedNewPointsMat.fromList(warpedNewPoints);
        // TODO: this line below sometimes throws an error:
        //        Exception in thread "pool-1-thread-5" CvException [org.opencv.core.CvException: cv::Exception: OpenCV(4.9.0) /src/opencv/modules/core/src/matrix.cpp:220: error: (-215:Assertion failed) 0 <= _dims && _dims <= CV_MAX_DIM in function 'setSize'
        //]
        //        at org.opencv.imgproc.Imgproc.boundingRect_0(Native Method)
        //        at org.opencv.imgproc.Imgproc.boundingRect(Imgproc.java:7720)
        //        at nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc.getBinaryLineStrip(LayoutProc.java:3240)
        //        at nl.knaw.huc.di.images.minions.MinionCutFromImageBasedOnPageXMLNew.runFile(MinionCutFromImageBasedOnPageXMLNew.java:490)
        //        at nl.knaw.huc.di.images.minions.MinionCutFromImageBasedOnPageXMLNew.run(MinionCutFromImageBasedOnPageXMLNew.java:610)
        //        at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1128)
        //        at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:628)
        //        at java.base/java.lang.Thread.run(Thread.java:829)
        Rect boundingRect = boundingRect(warpedNewPointsMat);
        warpedNewPointsMat = OpenCVWrapper.release(warpedNewPointsMat);
        RotatedRect rotatedRectFull = new RotatedRect(pivotPoint, boundingRect.size(), mainAngle);

//        Rect cuttingRect = rotatedRectFull.boundingRect();

        sourcePoints = OpenCVWrapper.newMat();
        Imgproc.boxPoints(rotatedRectFull, sourcePoints);

        double width= rotatedRectFull.size.width;
        double height = rotatedRectFull.size.height;

        MatOfPoint2f destiniationPoints = new MatOfPoint2f();
        destiniationPoints.fromArray(
                new Point(0, height - 1),
                new Point(0, 0),
                new Point(width - 1, 0),
                new Point(width - 1, height - 1)
        );

        Mat perspectiveMatTest = Imgproc.getPerspectiveTransform(sourcePoints, destiniationPoints);
        sourcePoints = OpenCVWrapper.release(sourcePoints);
        destiniationPoints = OpenCVWrapper.release(destiniationPoints);
        deskewedSubmat = OpenCVWrapper.newMat();
        if (boundingRect.size().height>=Short.MAX_VALUE || boundingRect.size().height>=Short.MAX_VALUE ){
            LOG.error("Maximum height and/or width exceeded. Lines with one side >="+Short.MAX_VALUE+ " are not supported.");
            return null;
        }
        Imgproc.warpPerspective(image, deskewedSubmat, perspectiveMatTest, boundingRect.size(), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, new Scalar(255, 255, 255));
        List<Point> warpedContourPoints = warpPoints(contourPoints, perspectiveMatTest);

//        if (write ){
//            Imgcodecs.imwrite("/tmp/rotated.png",deskewedSubmat);
//        }

        if (deskewedSubmat == null) {
            LOG.error(identifier + ": deskewedSubmat is null");
        }
        if (deskewedSubmat != null) {

            Mat mask = Mat.ones(deskewedSubmat.size(), CV_8UC1);
            getMask(warpedContourPoints, mask);

            expandedBaseline = warpPoints(expandedBaseline, perspectiveMatTest);
//          perspectiveMat= OpenCVWrapper.release(perspectiveMat);
            perspectiveMatTest = OpenCVWrapper.release(perspectiveMatTest);

            Mat grayImage = OpenCVWrapper.newMat(deskewedSubmat.size(), CV_8UC1);
            OpenCVWrapper.cvtColor(deskewedSubmat, grayImage);
            OpenCVWrapper.bitwise_not(grayImage, grayImage);
            Scalar meanGray = Core.mean(grayImage);
            Mat tmpGrayBackgroundSubtracted = OpenCVWrapper.newMat();
            Core.subtract(grayImage, meanGray, tmpGrayBackgroundSubtracted);
            grayImage = OpenCVWrapper.release(grayImage);

            Mat maskedBinary = OpenCVWrapper.newMat(new Size(mask.cols(), mask.rows()), mask.type());
            tmpGrayBackgroundSubtracted.copyTo(maskedBinary, mask);
            tmpGrayBackgroundSubtracted = OpenCVWrapper.release(tmpGrayBackgroundSubtracted);

            if (mask == null || mask.width() == 0) {
                LOG.error("Mask width is 0, base line is not within the boundaries of the image");
            }
//            good
            List<Integer> horizontalProfile = horizontalProfileByte(maskedBinary);
            maskedBinary = OpenCVWrapper.release(maskedBinary);
            List<Double> horizontalProfileDouble = smoothList(horizontalProfile, LayoutProc.MINIMUM_XHEIGHT / 2);
//            // seems good, did the short test. now testing:

            Point lowestBaselinePoint = getLowestPoint(expandedBaseline);
            Point highestBaselinePoint = getHighestPoint(expandedBaseline);
            double medianY = getMedianY(expandedBaseline);
//          // short test up until here, now testing from here:  // test2
            xHeight = calculateXHeightViaProjection(textLineId, horizontalProfileDouble, lowestBaselinePoint, highestBaselinePoint);
            xHeight += calculateXHeightViaMask(mask);
            xHeight /= 2;
            if (xHeight == null) {
                return null;
            }
//          // short test up until here, now testing from here:  // test 3

            finalFinalOutputMat = OpenCVWrapper.newMat();
            getMaskedOutput(identifier, xHeight, includeMask, deskewedSubmat, mask, (int) medianY, finalFinalOutputMat);
            deskewedSubmat = OpenCVWrapper.release(deskewedSubmat);
            mask = OpenCVWrapper.release(mask);
        }
//        return null;
        BinaryLineStrip binaryLineStrip = new BinaryLineStrip();
        binaryLineStrip.setLineStrip(finalFinalOutputMat);
        binaryLineStrip.setxHeight(xHeight);
        return binaryLineStrip;
    }

    private static void getMaskedOutput(String identifier, Integer xHeight, boolean includeMask, Mat deskewedSubmat, Mat mask, int medianY, Mat destination) {
        if (mask.height() != deskewedSubmat.height()) {
            LOG.error("mask.height()!= deskewedSubmat.height()");
        }
        if (mask.width() != deskewedSubmat.width()) {
            LOG.error("mask.width()!= deskewedSubmat.width()");
        }
        if (mask.channels() != 1) {
            new Exception(" mask.channels() != 1").printStackTrace();
        }
        if (mask.type() != CV_8UC1) {
            new Exception(" mask.type() != CV_8UC1").printStackTrace();
        }

        Mat finalOutput = null;
        Mat finalFinalOutputTmp = null;
        boolean ownsFinalOutput = false;
        MatOfInt fromTo = null;
        try {
            if (includeMask) {
                // Build the BGRA result in a single mixChannels pass: BGR comes from
                // deskewedSubmat (channels 0-2), alpha from mask (channel 3 of the
                // concatenated input list). This replaces a Core.split / Core.merge
                // round-trip that allocated 3 single-channel scratch Mats per line.
                finalOutput = new Mat(deskewedSubmat.size(), CV_8UC4);
                ownsFinalOutput = true;
                fromTo = new MatOfInt(0, 0, 1, 1, 2, 2, 3, 3);
                if (mask.channels() != 1) {
                    new Exception(" mask.channels() != 1").printStackTrace();
                }
                if (mask.type() != CV_8UC1) {
                    new Exception(" mask.type() != CV_8UC1").printStackTrace();
                }

                Core.mixChannels(Arrays.asList(deskewedSubmat, mask), Arrays.asList(finalOutput), fromTo);
            } else {
                // No alpha needed: the deskewed BGR Mat is the final output already.
                finalOutput = deskewedSubmat;
            }

            int rowStart = medianY - 2 * xHeight;
            if (rowStart < 0) {
                rowStart = 0;
            }
            int rowEnd = medianY + 1 * xHeight;
            if (rowEnd >= finalOutput.height()) {
                rowEnd = finalOutput.height() - 1;
            }

            finalFinalOutputTmp = finalOutput
                    .submat(rowStart,
                            rowEnd,
                            0,
                            finalOutput.width() - 1);
            finalFinalOutputTmp.copyTo(destination);

        } catch (Exception ex) {
            LOG.error("Unexpected error", ex);
            LOG.error(identifier + ": deskewSubmat.size() " + deskewedSubmat.size());
            LOG.error(identifier + ": mask.size() " + mask.size());
            new Exception("here").printStackTrace();
        } finally {
            finalFinalOutputTmp = OpenCVWrapper.release(finalFinalOutputTmp);
            if (ownsFinalOutput && finalOutput != null) {
                OpenCVWrapper.release(finalOutput);
            }
            if (fromTo != null) {
                OpenCVWrapper.release(fromTo);
            }
        }
    }

    private static BinaryLineStrip getBinaryLineStripFromContours(Mat image, List<Point> contourPoints) {
        BinaryLineStrip binaryLineStrip = new BinaryLineStrip();
        Rect boundingBox = LayoutProc.getBoundingBox(contourPoints);
        Mat lineStripRoi = image.submat(boundingBox);
        Mat lineStripSubmat = lineStripRoi.clone();
        lineStripRoi = OpenCVWrapper.release(lineStripRoi);
        binaryLineStrip.setLineStrip(lineStripSubmat);
        binaryLineStrip.setxHeight(lineStripSubmat.height()/3);
        return binaryLineStrip;
    }

    private static Integer calculateXHeightViaMask(Mat mask) {
        int sum = 0;
        int columns = 0;
        for (int i = 0; i < mask.width(); i++) {
            int counter = 0;
            for (int j = 0; j < mask.height(); j++) {
                double value = mask.get(j, i)[0];
                if (value > 1) {
                    counter++;
                }
            }
            if (counter > 0) {
                columns++;
                sum += counter;
            }
        }
        return (int) (((double) sum / columns) * 0.5);
    }

    private static Integer calculateXHeightViaProjection(String textLineId, List<Double> horizontalProfileDouble, Point lowestBaselinePoint, Point highestBaselinePoint) {
        Integer start = null;
        Integer end = null;
        Integer xHeight;
        Double averageBaselineLineProfileScore = null;
        Double maxScore = Double.MIN_VALUE;
        Double minScore = Double.MAX_VALUE;
        int startY = (int) (lowestBaselinePoint.y + highestBaselinePoint.y) / 2;
        // Start from the bottom and work our way up
        for (int i = startY; i > 0; i--) {
            if (averageBaselineLineProfileScore == null) {
                if (i >= horizontalProfileDouble.size()) {
                    LOG.error("going out of bounds");
                }
                averageBaselineLineProfileScore = horizontalProfileDouble.get(i);
            }
            // go up while it increases, with Minimum xheight as minimum to go up
            if (maxScore == Double.MIN_VALUE || horizontalProfileDouble.get(i) >= maxScore || startY - i < LayoutProc.MINIMUM_XHEIGHT) {
                if (horizontalProfileDouble.get(i) > maxScore) {
                    maxScore = horizontalProfileDouble.get(i);
                }
                if (horizontalProfileDouble.get(i) < minScore) {
                    minScore = horizontalProfileDouble.get(i);
                }
                if (end == null) {
                    end = i;
                }
                start = i;
            } else {
                break;
            }
//                Imgproc.line(deskewedSubmat, new Point(0,i), new Point(horizontalProfileDouble.get(i), i), new Scalar(255,0,0));
        }
        // continue from peak
//            if (start == null) {
//                Imgcodecs.imwrite("/tmp/test.png", deskewedSubmat);
//            }
        // TODO: fixme this is a workaround for lines that are rotated a bit too much right now
        // these are now falsely excluded
        if (start == null) {
            LOG.error("dropping line: " + textLineId);
            return null;
        }
        for (int i = start; i > 0; i--) {
            if (end - i <= LayoutProc.MINIMUM_XHEIGHT || horizontalProfileDouble.get(i) >= (minScore + maxScore) / 2) {
                start = i;
            } else {
                break;
            }
//                Imgproc.line(deskewedSubmat, new Point(0,i), new Point(horizontalProfileDouble.get(i), i), new Scalar(0,255,0));
        }
        Integer zeroLine = null;
        for (int i = start; i > 0; i--) {
            if (horizontalProfileDouble.get(i) < 0.1) {
                zeroLine = i;
                break;
            }
        }
        xHeight = end - start;
//        if (zeroLine!=null && (double)(end - zeroLine) / (double)xHeight < 1.5){
//            xHeight = (int) (xHeight/ 1.5);
//        }
        return xHeight;
    }

    private static Point findRotatedTopPoint(List<Point> newPoints, double mainAngle) {
        double radians = Math.toRadians(mainAngle);
        double yZero = Double.MAX_VALUE;
        Point rotatedTopPoint = null;
        for (Point point : newPoints) {
            Point xIntersect = new Point(0, point.y * Math.cos(radians) + point.x * Math.sin(radians));
            if (yZero > xIntersect.y) {
                rotatedTopPoint = point;
                yZero = xIntersect.y;
            }
        }
        return rotatedTopPoint;
    }

    private static Point findRotatedBottomPoint(List<Point> newPoints, double mainAngle) {
        double radians = Math.toRadians(mainAngle);
        double yZero = -Double.MAX_VALUE;
        Point rotatedBottomPoint = null;
        for (Point point : newPoints) {
            Point xIntersect = new Point(0, point.y * Math.cos(radians) + point.x * Math.sin(radians));
            if (yZero < xIntersect.y) {
                rotatedBottomPoint = point;
                yZero = xIntersect.y;
            }
        }
        return rotatedBottomPoint;
    }

    private static Point findRotatedLeftMostPoint(List<Point> newPoints, double mainAngle) {
        double radians = Math.toRadians(mainAngle);
        double xZero = Double.MAX_VALUE;
        Point rotatedLeftMostPoint = null;
        for (Point point : newPoints) {
            Point yAxis = new Point(point.x + Math.tan(radians) * point.y, 0);
            if (xZero > yAxis.x) {
                rotatedLeftMostPoint = point;
                xZero = yAxis.x;
            }
        }
        return rotatedLeftMostPoint;
    }

    private static Point findRotatedRightMostPoint(List<Point> newPoints, double mainAngle) {
        double radians = Math.toRadians(mainAngle);
        double xZero = -Double.MAX_VALUE;
        Point rotatedRightMostPoint = null;
        for (Point point : newPoints) {
            Point yAxis = new Point(point.x + Math.tan(radians) * point.y, 0);
            if (xZero < yAxis.x) {
                rotatedRightMostPoint = point;
                xZero = yAxis.x;
            }
        }
        return rotatedRightMostPoint;
    }

    private static Point getPivotPoint(List<Point> newPoints) {
        Point highest = getHighestPoint(newPoints);
        Point lowest = getLowestPoint(newPoints);
        Point leftMost = getLeftMostPoint(newPoints);
        Point rightMost = getRightMostPoint(newPoints);

        return new Point((leftMost.x + rightMost.x) / 2., (highest.y + lowest.y) / 2.);

    }

    private static void getMask(List<Point> warpedContourPoints, Mat mask) {
        MatOfPoint sourceMat = new MatOfPoint();
        sourceMat.fromList(warpedContourPoints);
        List<MatOfPoint> finalPoints = new ArrayList<>();
        finalPoints.add(sourceMat);

        Scalar color = new Scalar(255);
        Imgproc.fillPoly(mask, finalPoints, color);
        sourceMat = OpenCVWrapper.release(sourceMat);
    }

    private static double getMedianY(List<Point> expandedBaseline) {

        List<Double> yPoints = new ArrayList<>();
        for (Point point : expandedBaseline) {
            yPoints.add(point.y);
        }

        return new Statistics(yPoints).median();
    }

    private static Point getHighestPoint(List<Point> newPoints) {
        Double y = null;
        Point bestPoint = null;
        for (Point point : newPoints) {
            if (y == null || point.y < y) {
                y = point.y;
                bestPoint = point;
            }
        }
        return bestPoint;
    }

    private static Point getLowestPoint(List<Point> newPoints) {
        Double y = null;
        Point bestPoint = null;
        for (Point point : newPoints) {
            if (y == null || point.y > y) {
                y = point.y;
                bestPoint = point;
            }
        }
        return bestPoint;
    }

    private static Point getLeftMostPoint(List<Point> newPoints) {
        Double x = null;
        Point bestPoint = null;
        for (Point point : newPoints) {
            if (x == null || point.x < x) {
                x = point.x;
                bestPoint = point;
            }
        }
        return bestPoint;
    }

    private static Point getRightMostPoint(List<Point> newPoints) {
        Double x = null;
        Point bestPoint = null;
        for (Point point : newPoints) {
            if (x == null || point.x > x) {
                x = point.x;
                bestPoint = point;
            }
        }
        return bestPoint;
    }

    public static void splitLinesIntoWords(PcGts page) {
        final Integer maxY = page.getPage().getImageHeight();
        final Integer maxX = page.getPage().getImageWidth();
        for (TextRegion textRegion : page.getPage().getTextRegions()) {
            final List<TextLine> textLinesToRemove = new ArrayList<>();
            for (TextLine textLine : textRegion.getTextLines()) {
                TextEquiv textEquiv = textLine.getTextEquiv();
                if (textEquiv != null) {
                    String text = textEquiv.getUnicode();
                    if (Strings.isNullOrEmpty(text)) {
                        text = textEquiv.getPlainText();
                    }
                    if (!Strings.isNullOrEmpty(text) && !text.trim().isEmpty()) {

                        List<Point> baselinePoints = StringConverter.expandPointList(StringConverter.stringToPoint(textLine.getBaseline().getPoints()));
                        if (baselinePoints.isEmpty()) {
                            LOG.error("Textline with id '" + textLine.getId() + "' has no (valid) baseline.");
                            LOG.error("words: " + text);
                            LOG.error("baselinepoints: " + textLine.getBaseline().getPoints());
                            continue;
                        }

                        textLine.setWords(new ArrayList<>());


                        final double baselineLength = StringConverter.calculateBaselineLength(baselinePoints);

                        List<String> words = splitWordsOnSpace(text);
                        int numchars = 0;
                        for (final String wordString : words) {
                            numchars += wordString.length();
                        }
                        int spaces = Math.max(0, words.size() - 1);
                        double charWidth = baselineLength / (numchars + spaces);
                        if (charWidth < 2) {
                            textLinesToRemove.add(textLine);
                            LOG.warn("Ignoring TextLine '{}', it has less than 2 a character. TextLine will be removed from region", textLine.getId());
                            continue;
                        }

                        int nextBaseLinePointIndex = 0;
                        // FIXME see TI-541
                        final int magicValueForYHigherThanWord = 35;
                        final int magicValueForYLowerThanWord = 10;
                        int currentSentenceLength = 0;
                        double sentenceBaselineLength = 0;
                        Point lastSentenceBaselinePoint = null;
                        for (final String wordString : words) {
                            Word word = new Word();
                            word.setTextEquiv(new TextEquiv(null, UNICODE_TO_ASCII_TRANSLITIRATOR.toAscii(wordString), wordString));
                            List<Point> wordBaselinePoints = new ArrayList<>();

                            double wordLength = wordString.length() * charWidth;
                            Point nextBaselinePoint;
                            Point previousWordBaselinePoint = null;
                            double wordBaselineLength = 0;
                            // FIXME Something goes wrong when wordBaseLinePoints is larger than wordLength
//                            0.1 is added to avoid rounding errors
                            while (wordBaselineLength + 0.1 < wordLength) {
                                if (nextBaseLinePointIndex >= baselinePoints.size()) {
                                    break;
                                }
                                nextBaselinePoint = baselinePoints.get(nextBaseLinePointIndex);
                                if (previousWordBaselinePoint != null) {
                                    wordBaselineLength += StringConverter.distance(previousWordBaselinePoint, nextBaselinePoint);
                                }
                                wordBaselinePoints.add(nextBaselinePoint);
                                previousWordBaselinePoint = nextBaselinePoint;
                                nextBaseLinePointIndex++;
                            }

                            wordBaselinePoints = simplifyPolygon(wordBaselinePoints, 0.9);

                            Coords wordCoords = getWordCoords(maxY, maxX, textLine, text, magicValueForYHigherThanWord,
                                    magicValueForYLowerThanWord, wordString, wordBaselinePoints);
                            word.setCoords(wordCoords);
                            textLine.getWords().add(word);
                            for (Point wordBaselinePoint : wordBaselinePoints) {
                                if (lastSentenceBaselinePoint != null) {
                                    sentenceBaselineLength += StringConverter.distance(lastSentenceBaselinePoint, wordBaselinePoint);
                                }
                                lastSentenceBaselinePoint = wordBaselinePoint;
                            }
                            currentSentenceLength += wordString.length();
                            while (nextBaseLinePointIndex + 1 < baselinePoints.size() && sentenceBaselineLength < charWidth * (currentSentenceLength + 1)) {
                                Point sentenceBaselinePoint = baselinePoints.get(++nextBaseLinePointIndex);
                                if (lastSentenceBaselinePoint != null) {
                                    sentenceBaselineLength += StringConverter.distance(lastSentenceBaselinePoint, sentenceBaselinePoint);
                                }
                                lastSentenceBaselinePoint = sentenceBaselinePoint;
                            }
                            currentSentenceLength++;
                        }
                    }
                }
            }
            for (TextLine textLine : textLinesToRemove) {
                textRegion.getTextLines().remove(textLine);
            }

        }

        page.getMetadata().setLastChange(new Date());
        if (page.getMetadata().getMetadataItems() == null) {
            page.getMetadata().setMetadataItems(new ArrayList<>());
        }
        MetadataItem metadataItem = new MetadataItem();
        metadataItem.setType("processingStep");
        metadataItem.setName("word-splitting");
        metadataItem.setValue("loghi-htr-tooling");

        page.getMetadata().getMetadataItems().add(metadataItem);
    }

    private static List<String> splitWordsOnSpace(String text) {
        List<String> words = new ArrayList<>();
        int length = text.length();
        int start = 0;
        while (start < length) {
            while (start < length && text.charAt(start) == ' ') {
                start++;
            }
            if (start >= length) {
                break;
            }
            int end = start + 1;
            while (end < length && text.charAt(end) != ' ') {
                end++;
            }
            words.add(text.substring(start, end));
            start = end + 1;
        }
        return words;
    }

    private static Coords getWordCoords(Integer maxY, Integer maxX, TextLine textLine, String text, int magicValueForYHigherThanWord, int magicValueForYLowerThanWord, String wordString, List<Point> wordBaselinePoints) {
        List<Point> upperWordPoints = new ArrayList<>();
        List<Point> lowerWordPoints = new ArrayList<>();
        for (int i = 0; i< wordBaselinePoints.size()-1; i++) {
            Point startPointOfWord = wordBaselinePoints.get(i);
            Point nextWordBaselinePoint = wordBaselinePoints.get(i+1);
            final double distance = distance(startPointOfWord, nextWordBaselinePoint);
            final double distanceHorizontal = StringConverter.distanceHorizontal(startPointOfWord, nextWordBaselinePoint);
            final double cos = distanceHorizontal / distance;
            final double distanceVertical = StringConverter.distanceVertical(startPointOfWord, nextWordBaselinePoint);
            final double sin = distanceVertical / distance;

            final double compensatedSpaceAboveBaselineY = magicValueForYHigherThanWord * cos;
            final double compensatedSpaceBelowBaselineY = magicValueForYLowerThanWord * cos;
            final double compensatedSpaceAboveBaselineX = magicValueForYHigherThanWord * sin;
            final double compensatedSpaceBelowBaselineX = magicValueForYLowerThanWord * sin;

            if (upperWordPoints.isEmpty()) {
                upperWordPoints.add(new Point(Math.min(maxX, Math.max(0, startPointOfWord.x + compensatedSpaceAboveBaselineX)),
                        Math.max(0, startPointOfWord.y - compensatedSpaceAboveBaselineY)));
                lowerWordPoints.add(new Point(Math.min(maxX, Math.max(0, startPointOfWord.x - compensatedSpaceBelowBaselineX)), Math.min(maxY, startPointOfWord.y + compensatedSpaceBelowBaselineY)));
            }
            upperWordPoints.add(new Point(Math.min(maxX, Math.max(0, nextWordBaselinePoint.x + compensatedSpaceAboveBaselineX)), Math.max(0, nextWordBaselinePoint.y - compensatedSpaceAboveBaselineY)));
            lowerWordPoints.add(new Point(Math.min(maxX, Math.max(0, nextWordBaselinePoint.x - compensatedSpaceBelowBaselineX)), Math.min(maxY,nextWordBaselinePoint.y + compensatedSpaceBelowBaselineY)));
        }

        if (upperWordPoints.isEmpty()) {
            String error = "Word '" + wordString + "' of line '" + text + "' has no coords. Baseline Coords: " + textLine.getBaseline().getPoints() + " Cowardly refusing to produce invalid PageXML.";
            LOG.error(error);
            throw new IllegalArgumentException(error);
        }
        Collections.reverse(lowerWordPoints);
        upperWordPoints.addAll(lowerWordPoints);
        Coords wordCoords = new Coords();
        wordCoords.setPoints(StringConverter.pointToString(upperWordPoints));
        return wordCoords;
    }

    public static double getDistance(Point last, Point first) {
        return Math.sqrt(Math.pow(last.x - first.x, 2) + Math.pow(last.y - first.y, 2));
    }

    public static double getLength(List<Point> points) {
        if (points.size() < 2) {
            return 0;
        }
        double length = 0;
        Point lastPoint = null;
        for (Point point : points) {
            if (lastPoint == null) {
                lastPoint = point;
                continue;
            }
            length += getDistance(lastPoint, point);
            lastPoint = point;
        }
        return length;
    }

    public static void safePut(Mat mat, int i, int j, double data){
        if (mat.channels()>1){
            throw new RuntimeException("Mat has more than 1 channel, data is single channel");
        }
        if (mat.type() != CV_64F && mat.type() != CV_32F) {
            throw new RuntimeException("Mat type is not CV_64F/CV_32F but of type: " + mat.type());
        }
        if (i>=0 && i<mat.height() && j>=0 && j<mat.width()){
            if (mat.type() == CV_64F) {
                double[] dataDouble = new double[1];
                dataDouble[0] = data;
                mat.put(i, j, dataDouble);
            } else {
                float[] dataFloat = new float[1];
                dataFloat[0] = (float) data;
                mat.put(i, j, dataFloat);
            }
        }else{
            LOG.error("Trying to put data outside of mat: " + i + " " + j);
            throw new RuntimeException("writing outside bounds");
        }
    }

    public static void safePut(Mat mat, int i, int j, int data){
        if (mat.channels()>1){
            throw new RuntimeException("Mat has more than 1 channel, data is single channel");
        }
        if (mat.type()!=CV_8U && mat.type()!=CV_32S){
            throw new RuntimeException("Mat type is not CV_8U/CV_32S but of type: " + mat.type());
        }
        if (mat.type()== CV_8U){
            if (data<0 || data>255){
                throw new RuntimeException("Data is not in range 0-255: " + data);
            }
        }
        if (i>=0 && i<mat.height() && j>=0 && j<mat.width()){
            if (mat.type() == CV_8U) {
                final byte[] dataByte = new byte[1];
                dataByte[0] = (byte) data;
                mat.put(i, j, dataByte);
            }else if (mat.type() == CV_32S){
                mat.put(i, j, data);
            }else{
                throw new RuntimeException("Mat type is not CV_8U/CV_32S but of type: " + mat.type());
            }
        }else{
            LOG.error("Trying to put data outside of mat: " + i + " " + j);
            throw new RuntimeException("writing outside bounds");
        }
    }


    public static double getSafeDouble(Mat mat, int i, int j){
        if (mat.channels()>1){
            throw new RuntimeException("Mat has more than 1 channel, data is single channel");
        }
        if (mat.type() != CV_64F && mat.type() != CV_32F){
            throw new RuntimeException("Mat type is not CV_64F/CV_32F but of type: " + mat.type());
        }
        if (i>=0 && i<mat.height() && j>=0 && j<mat.width()){
            if (mat.type() == CV_64F) {
                final double[] data = new double[1];
                mat.get(i, j, data);
                return data[0];
            }
            final float[] data = new float[1];
            mat.get(i, j, data);
            return data[0];
        }else{
            LOG.error("Trying to get data outside of mat: " + i + " " + j);
            throw new RuntimeException("reading outside bounds");
        }
    }

    public static int getSafeInt(Mat mat, int i, int j){
        if (mat.channels() > 1) {
            throw new RuntimeException("Mat has more than 1 channel, data is single channel");
        }
        if (mat.type() != CV_8UC1 && mat.type() != CV_32S) {
            throw new RuntimeException("Mat type is not CV_8UC1/CV_32S but of type: " + mat.type());
        }
        if (i < 0 || i >= mat.height() || j < 0 || j >= mat.width()) {
            LOG.error("Trying to get data outside of mat: " + i + " " + j);
            throw new RuntimeException("reading outside bounds");
        }
        if (mat.type() == CV_8UC1) {
            final byte[] data = new byte[1];
            mat.get(i, j, data);
            return data[0] & 0xFF;
        } else if (mat.type() == CV_32S) {
            final int[] data = new int[1];
            mat.get(i, j, data);
            return data[0];
        } else {
            throw new RuntimeException("Unsupported Mat type: " + mat.type());
        }
    }

    public static List<Tuple<Mat,Point>> splitBaselines(Mat inputBaselineMat, int label, Point offsetPoint,
                                                        double triggerLineThicknessMultiplier, double maxLineThicknessMultiplier){

        Mat baselineMat = inputBaselineMat.clone();
        try {
            List<Tuple<Mat, Point>> splitBaselines = new ArrayList<>();
            //TODO: split merged text lines
            // calculate runlengths & detect merged lines
            ArrayList<Double> runLengths = new ArrayList<>();
            boolean previousLineDetected = false;
            boolean mergedLineDetected = false;
            for (int i = 0; i < baselineMat.width(); i++) {
                int counter = 0;
                for (int j = 0; j < baselineMat.height(); j++) {
                    int pixelValue = getSafeInt(baselineMat, j, i);
                    if (pixelValue == label) {
                        counter++;
                        if (previousLineDetected) {
                            mergedLineDetected = true;
                        }
                    }
                    if (pixelValue != label || j == baselineMat.height() - 1) {
                        if (counter > 0) {
                            runLengths.add((double) counter);
                            counter = 0;
                            previousLineDetected = true;
                        }
                    }
                }
                if (counter > 0) {
                    runLengths.add((double) counter);
                }
            }

            double medianRunLength = new Statistics(runLengths).median();
            LOG.info("medianRunLength: " + medianRunLength);
            double triggerLineThickness = triggerLineThicknessMultiplier * medianRunLength;
            double maxLineThickness = maxLineThicknessMultiplier * medianRunLength;
            //detect where merged lines are
            for (int i = 0; i < baselineMat.width(); i++) {
                int counter = 0;
                Integer start = null;
                for (int j = 0; j < baselineMat.height(); j++) {
                    int pixelValue = getSafeInt(baselineMat, j, i);
                    if (pixelValue == label) {
                        counter++;
                        if (start == null) {
                            start = j;
                        }
                    }
                    if (pixelValue != label || j == baselineMat.height() - 1) {
                        if (mergedLineDetected && counter > triggerLineThickness) {
                            // trackback and limit to medianRunLength
                            // TODO: somehow this is not working correctly
                            for (int k = start + (int) (1.0 * medianRunLength); k < start + (int) maxLineThickness; k++) {
                                if (k < baselineMat.height()) {
                                    safePut(baselineMat, k, i, 0);
                                }
                            }
                        }
                        counter = 0;
                    }
                }
            }

            // run connected components on baselineMat
            Mat stats = OpenCVWrapper.newMat();
            Mat centroids = OpenCVWrapper.newMat();
            Mat labeled = OpenCVWrapper.newMat(baselineMat.size(), CV_32S);
            // convert Mat to 8U
            Mat baselineMat8U = OpenCVWrapper.newMat(baselineMat.size(), CV_8UC1);
//            convert all non zero values to 255
            convertToBinaryImage(baselineMat, baselineMat8U);
            int numLabels = OpenCVWrapper.connectedComponentsWithStats(baselineMat8U, labeled, stats, centroids, 4, CvType.CV_32S);
            baselineMat8U = OpenCVWrapper.release(baselineMat8U);
            centroids = OpenCVWrapper.release(centroids);
            LOG.info("FOUND SUBLABELS:" + numLabels);
            if (numLabels == 2) {
                Tuple<Mat, Point> tuple = new Tuple<>(baselineMat.clone(), offsetPoint);
                splitBaselines.add(tuple);
                labeled = OpenCVWrapper.release(labeled);
                stats = OpenCVWrapper.release(stats);
                return splitBaselines;
            }
            // FOR debugging purposes
            for (int i = 1; i < numLabels; i++) {
                Rect rect = LayoutProc.getRectFromStats(stats, i);
                Point newOffsetPoint = new Point(rect.x + offsetPoint.x, rect.y + offsetPoint.y);
                Mat labeledRoi = labeled.submat(rect);
                Mat labeledSubmat = labeledRoi.clone();
                labeledRoi = OpenCVWrapper.release(labeledRoi);
                // relabel
                for (int j = 0; j < labeledSubmat.width(); j++) {
                    for (int k = 0; k < labeledSubmat.height(); k++) {
                        if (getSafeInt(labeledSubmat, k, j) == i) {
                            safePut(labeledSubmat, k, j, label);
                        } else {
                            safePut(labeledSubmat, k, j, 0);
                        }
                    }
                }
                Tuple<Mat, Point> tuple = new Tuple<>(labeledSubmat, newOffsetPoint);
                splitBaselines.add(tuple);
            }
            labeled = OpenCVWrapper.release(labeled);
            stats = OpenCVWrapper.release(stats);
            return splitBaselines;
        }finally {
            baselineMat = OpenCVWrapper.release(baselineMat);
        }
    }

    private static void convertToBinaryImage(Mat source, Mat destination) {
        // Iterate through each pixel in the 32S Mat object
        for (int i = 0; i < source.rows(); i++) {
            for (int j = 0; j < source.cols(); j++) {
                // Get the pixel value from the 32S Mat
                int pixelValue = (int) source.get(i, j)[0];

                // Set the corresponding pixel in the binary image
                if (pixelValue != 0) {
                    safePut(destination, i, j, 255);
                } else {
                    safePut(destination, i, j, 0);
                }
            }
        }
    }

    public static Rect getRectFromStats(Mat stats, int labelNumber) {
        if (stats.rows() <= labelNumber) {
            LOG.error("stats.rows() <= labelNumber");
            throw new RuntimeException("stats.rows() <= labelNumber");
        }
        Rect rect = new Rect(LayoutProc.getSafeInt(stats,labelNumber, Imgproc.CC_STAT_LEFT),
                LayoutProc.getSafeInt(stats,labelNumber, Imgproc.CC_STAT_TOP),
                LayoutProc.getSafeInt(stats,labelNumber, Imgproc.CC_STAT_WIDTH),
                LayoutProc.getSafeInt(stats,labelNumber, Imgproc.CC_STAT_HEIGHT));
        return rect;
    }
}
