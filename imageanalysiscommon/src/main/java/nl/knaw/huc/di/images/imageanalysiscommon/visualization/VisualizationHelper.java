package nl.knaw.huc.di.images.imageanalysiscommon.visualization;

import nl.knaw.huc.di.images.imageanalysiscommon.imageConversion.ImageConversionHelper;
import nl.knaw.huc.di.images.layoutds.models.DocumentParagraph;
import nl.knaw.huc.di.images.layoutds.models.DocumentTextBlock;
import nl.knaw.huc.di.images.layoutds.models.DocumentTextLine;
import nl.knaw.huc.di.images.layoutds.models.connectedComponent.ConnectedComponent;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class VisualizationHelper {

    private static Random _rand;

    public static void drawHorizontalLine(Mat input, int x, int y, int length, boolean limitToUChar) {
        org.opencv.core.Point startPoint = new org.opencv.core.Point(x, y);
        org.opencv.core.Point stopPoint = new org.opencv.core.Point(x + length, y);
        Scalar color;
        if (limitToUChar && length > 255) {
            color = new Scalar(255);
        } else {
            color = new Scalar(length);
        }
        Imgproc.line(input, startPoint, stopPoint, color, defaultLineThickness, defaultLineType, defaultShift);
    }

    public static void drawVerticalLine(Mat input, int x, int y, int length, boolean limitToUChar) {
        org.opencv.core.Point startPoint = new org.opencv.core.Point(x, y);
        org.opencv.core.Point stopPoint = new org.opencv.core.Point(x, y + length);
        Scalar color;
        if (limitToUChar && length > 255) {
            color = new Scalar(255);
        } else {
            color = new Scalar(length);
        }
        Imgproc.line(input, startPoint, stopPoint, color, defaultLineThickness, defaultLineType, defaultShift);
    }

    public static Color getRandomColor() {
        if (_rand == null) {
            _rand = new Random(20160804);
        }


        float r = _rand.nextFloat();
        float g = _rand.nextFloat();
        float b = _rand.nextFloat();

        if (r < 0.10) {
            r = 0.10f;
        }
        if (g < 0.10) {
            g = 0.10f;
        }
        if (b < 0.10) {
            b = 0.10f;
        }

        return new Color(r, g, b);
    }

    public static void colorize(Mat colorizedImage, ConnectedComponent coco) {
        colorize(colorizedImage, coco, null);
    }


    public static void colorize(Mat colorizedImage, ConnectedComponent coco, Color color) {
        BufferedImage cocoBitmap = coco.getBitMap();

        if (color == null) {
            color = coco.getColor();
        }

        float[] colorArray = new float[3];
        colorArray[0] = color.getBlue();
        colorArray[1] = color.getGreen();
        colorArray[2] = color.getRed();

        int cocoHeight = cocoBitmap.getHeight();
        int cocoWidth = cocoBitmap.getWidth();
        for (int i = 0; i < cocoHeight; i++) {
            for (int j = 0; j < cocoWidth; j++) {
                if (cocoBitmap.getRGB(j, i) != Color.black.getRGB()) {

                    // // TODO TI-374: 20-9-16 do not use put, this can be done in such a way that it is faster
                    colorizedImage.put(i + coco.getY(), j + coco.getX(), colorArray);
                }
            }
        }
    }

    //Broken....
    public static void colorizeNew(Mat colorizedImage, ConnectedComponent coco, Color color) {
        BufferedImage cocoBitmap = coco.getBitMap();

        if (color == null) {
            color = coco.getColor();
        }
        float[] colorArray = new float[3];
        colorArray[0] = color.getBlue();
        colorArray[1] = color.getGreen();
        colorArray[2] = color.getRed();

        int cocoHeight = cocoBitmap.getHeight();
        int cocoWidth = cocoBitmap.getWidth();
        for (int i = 0; i < cocoHeight; i++) {
            for (int j = 0; j < cocoWidth; j++) {
                if (cocoBitmap.getRGB(j, i) != Color.black.getRGB()) {
                    Mat submat = colorizedImage.submat(coco.getY(), coco.getY() + coco.getBitMap().getHeight() - 2, coco.getX(), coco.getX() + coco.getWidth() - 2);
                    Mat mask = ImageConversionHelper.bufferedImageToBinaryMat(coco.getBitMap());
                    try {
                        Imgproc.floodFill(
                                submat,
                                mask,
                                new Point(j, i),
                                new Scalar(new Random().nextInt(255), new Random().nextInt(255), new Random().nextInt(255)));
                        return;
                    } finally {
                        submat.release();
                        mask.release();
                    }
                    // // TODO RUTGERCHECK (method is not used): 20-9-16 do not use put, this can be done in such a way that it is faster
//                    colorizedImage.put(i + coco.getY(), j + coco.getX(), colorArray);
                }
            }
        }
    }


    public static void colorize(Mat colorizedImage, List<ConnectedComponent> gaussedCocos) {
        colorize(colorizedImage, gaussedCocos, null);
    }

    public static void colorize(Mat colorizedImage, List<ConnectedComponent> gaussedCocos, Color color) {
        for (ConnectedComponent coco : gaussedCocos) {
            colorize(colorizedImage, coco, color);
        }
    }

    public static void colorizeTextBlocks(Mat colorizedImage, List<DocumentTextBlock> textBlocks) {
        org.opencv.core.Point startPoint;
        org.opencv.core.Point stopPoint;
        for (DocumentTextBlock textBlock : textBlocks) {
            startPoint = new org.opencv.core.Point(textBlock.getXStart(), textBlock.getYStart());
            stopPoint = new org.opencv.core.Point(textBlock.getXStart() + textBlock.getWidth(), textBlock.getYStart() + textBlock.getHeight());
            Imgproc.rectangle(colorizedImage, startPoint, stopPoint, new Scalar(255, 0, 255), 4);
            for (DocumentParagraph paragraph : textBlock.getDocumentParagraphs()) {
                colorizeTextLines(colorizedImage, paragraph.getDocumentTextLines());
            }
        }
    }

    private static final int defaultLineThickness = 1;
    private static final int defaultLineType = 4;
    private static final int defaultShift = 0;

    private static void colorizeTextLines(Mat colorizedImage, List<DocumentTextLine> textLines) {
        org.opencv.core.Point startPoint;
        org.opencv.core.Point stopPoint;
        for (DocumentTextLine textline : textLines) {
            startPoint = new org.opencv.core.Point(textline.getXStart(), textline.getYStart() + textline.getHeight() / 2.0);
            stopPoint = new org.opencv.core.Point(textline.getXStart() + textline.getWidth(), textline.getYStart() + textline.getHeight() / 2.0);
            Imgproc.line(colorizedImage, startPoint, stopPoint, new Scalar(255, 0, 0), defaultLineThickness, defaultLineType, defaultShift);
        }
    }

    public static void drawVerticalInkProfileDouble(Mat colorizedImage, List<Double> verticalProfileSmooth) {
        for (int i = 0; i < verticalProfileSmooth.size(); i++) {
            Imgproc.line(colorizedImage, new Point(i, colorizedImage.height() - 1), new Point(i, colorizedImage.height() - verticalProfileSmooth.get(i).intValue()), new Scalar(200, 0, 255), defaultLineThickness, defaultLineType, defaultShift);
        }
    }

    public static void drawVerticalInkProfileDoubleGray(Mat colorizedImage, ArrayList<Double> verticalProfileSmooth) {
        for (int i = 0; i < verticalProfileSmooth.size(); i++) {
            Imgproc.line(colorizedImage, new Point(i, colorizedImage.height() - 1), new Point(i, colorizedImage.height() - verticalProfileSmooth.get(i).intValue()), new Scalar(200), defaultLineThickness, defaultLineType, defaultShift);
        }
    }


    public static void drawVerticalInkProfileInteger(Mat colorizedImage, ArrayList<Integer> verticalProfileSmooth) {
        for (int i = 0; i < verticalProfileSmooth.size(); i++) {
            Imgproc.line(colorizedImage, new Point(i, colorizedImage.height() - 1), new Point(i, colorizedImage.height() - verticalProfileSmooth.get(i)), new Scalar(200, 0, 255), defaultLineThickness, defaultLineType, defaultShift);
        }
    }

    public static void drawVerticalInkProfile(Mat colorizedImage, List<Double> verticalProfileSmooth) {
        for (int i = 0; i < verticalProfileSmooth.size(); i++) {
            Imgproc.line(colorizedImage, new Point(i, colorizedImage.height() - 1), new Point(i, colorizedImage.height() - verticalProfileSmooth.get(i).intValue()), new Scalar(200, 0, 255), defaultLineThickness, defaultLineType, defaultShift);
        }
    }

    public static void drawVerticalInkProfileClean(Mat colorizedImage, List<Double> verticalProfileSmooth) {
        for (int i = 0; i < verticalProfileSmooth.size(); i++) {
            Imgproc.line(colorizedImage, new Point(i, colorizedImage.height() - 1), new Point(i, colorizedImage.height() - verticalProfileSmooth.get(i).intValue()), new Scalar(100, 200, 155), defaultLineThickness, defaultLineType, defaultShift);
        }
    }

    public static void drawHorizontalInkProfile(Mat colorizedImage, ArrayList<Integer> horizontalWithinMargins, ArrayList<Double> horizontalProfileSmooth) {
        for (int i = 0; i < horizontalWithinMargins.size(); i++) {
            if (colorizedImage.type() == CvType.CV_8UC1) {
                Imgproc.line(colorizedImage, new Point(0, i), new Point(horizontalProfileSmooth.get(i).intValue(), i), new Scalar(127), defaultLineThickness, defaultLineType, defaultShift);

            } else {
                Imgproc.line(colorizedImage, new Point(0, i), new Point(horizontalProfileSmooth.get(i).intValue(), i), new Scalar(0, 255, 255), defaultLineThickness, defaultLineType, defaultShift);
            }
        }
    }

    public static void drawVerticalWhitespaceProfileSmooth(Mat colorizedImage, List<Double> verticalProfileSmoothWhitespace, double averageWhitespace) {
        for (int i = 0; i < verticalProfileSmoothWhitespace.size(); i++) {
            if (verticalProfileSmoothWhitespace.get(i) - averageWhitespace > 0) {
                Imgproc.line(colorizedImage,
                        new Point(i, 0),
                        new Point(i, verticalProfileSmoothWhitespace.get(i) / 100),
                        new Scalar(0, 255, 255),
                        defaultLineThickness,
                        defaultLineType,
                        defaultShift);
            } else {
                Imgproc.line(colorizedImage,
                        new Point(i, 0),
                        new Point(i, verticalProfileSmoothWhitespace.get(i) / 100),
                        new Scalar(255, 255, 255),
                        defaultLineThickness,
                        defaultLineType,
                        defaultShift);
            }

        }
    }

    public static void drawVerticalDifferenceProfile(Mat colorizedImage, ArrayList<Double> verticalProfileDifferenceWithSmooth) {
        for (int i = 0; i < verticalProfileDifferenceWithSmooth.size(); i++) {
            Imgproc.line(colorizedImage, new Point(i, colorizedImage.height() - 1), new Point(i, colorizedImage.height() - verticalProfileDifferenceWithSmooth.get(i).intValue()), new Scalar(255, 200, 255), defaultLineThickness, defaultLineType, defaultShift);
        }
    }

    public static void colorize(Mat colorized, DocumentTextBlock textBlock) {
        colorize(colorized, textBlock, new Scalar(255, 0, 255), 3);
    }

    public static void colorize(Mat colorized, DocumentTextBlock textBlock, Scalar color, int thickness) {
        Imgproc.rectangle(colorized, new Point(textBlock.getXStart(), textBlock.getYStart()),
                new Point(textBlock.getXStart() + textBlock.getWidth(),
                        textBlock.getYStart() + textBlock.getHeight()), color, thickness);
    }


    public static void colorize(Mat colorized, DocumentTextLine documentTextLine, Scalar color) {
        Imgproc.rectangle(colorized, new Point(documentTextLine.getXStart(), documentTextLine.getYStart()),
                new Point(documentTextLine.getXStart() + documentTextLine.getWidth(),
                        documentTextLine.getYStart() + documentTextLine.getHeight()), color, 2);
    }

    public static void colorize(Mat colorized, DocumentTextLine documentTextLine) {
        colorize(colorized, documentTextLine, new Scalar(255, 255, 255));
    }


}
