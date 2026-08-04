package nl.knaw.huc.di.images.layoutanalyzer;

import nl.knaw.huc.di.images.imageanalysiscommon.Histogram;
import nl.knaw.huc.di.images.imageanalysiscommon.connectedComponent.ConnectedComponentProc;
import nl.knaw.huc.di.images.imageanalysiscommon.imageConversion.ImageConversionHelper;
import nl.knaw.huc.di.images.imageanalysiscommon.visualization.VisualizationHelper;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.OpenCVWrapper;
import nl.knaw.huc.di.images.layoutds.models.connectedComponent.ConnectedComponent;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.opencv.core.*;
import org.opencv.core.Point;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.opencv.core.Core.BORDER_CONSTANT;
import static org.opencv.core.CvType.*;

//import nl.knaw.huc.di.images.imageanalysiscommon.model.DocumentTextBlock;
//import nl.knaw.huc.di.images.imageanalysiscommon.model.DocumentTextLine;


public class LayoutAnalyzer {

    private static final Logger LOG = LoggerFactory.getLogger(LayoutAnalyzer.class);

    private static final AtomicBoolean OUTPUT_DEBUG = new AtomicBoolean(false);
    private static final AtomicBoolean OUTPUT_FILE = new AtomicBoolean(false);
    private static final AtomicInteger GLOBAL_COUNTER = new AtomicInteger(0);

    static {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    /**
     * Shared, XXE-hardened {@link TransformerFactory}. JAXP factory creation is expensive
     * (classpath scan) and the factory is safe to share between threads.
     */
    private static final TransformerFactory SECURE_TRANSFORMER_FACTORY = buildSecureTransformerFactory();

    private static TransformerFactory buildSecureTransformerFactory() {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformerFactory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        return transformerFactory;
    }

    private static TransformerFactory createSecureTransformerFactory() {
        return SECURE_TRANSFORMER_FACTORY;
    }


    private static String documentAsString(Document document) {
        try {
            DOMSource domSource = new DOMSource(document);
            StringWriter writer = new StringWriter();
            StreamResult result = new StreamResult(writer);
            TransformerFactory tf = createSecureTransformerFactory();
            Transformer transformer = tf.newTransformer();
            transformer.transform(domSource, result);
            return writer.toString();
        } catch (TransformerException te) {
            LOG.error("Could not serialize XML document", te);
        }
        return "an error has occurred";
    }

    private static void storeXML(Document document, String outputFile) {
        try {
            Transformer tr = createSecureTransformerFactory().newTransformer();
            tr.setOutputProperty(OutputKeys.INDENT, "yes");
            tr.setOutputProperty(OutputKeys.METHOD, "xml");
            tr.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            tr.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

            // send DOM to file
            tr.transform(new DOMSource(document),
                    new StreamResult(new FileOutputStream(outputFile)));

        } catch (TransformerException | IOException ex) {
            LOG.error("Could not store XML to {}", outputFile, ex);
        }
    }

    private static ArrayList<Integer> lineCrossingsVertical(Mat binaryImage, int startY, int stopY, int startX, int stopX) {
        long size = binaryImage.total() * binaryImage.channels();
        byte[] data = new byte[(int) size];
        binaryImage.get(0, 0, data);
        ArrayList<Integer> verticals = new ArrayList<>();

        for (int x = startX; x < stopX; x++) {
            int crossings = 0;
            boolean lastPixelOn = false;
            for (int y = startY; y < stopY; y++) {
                if (data[y * binaryImage.width() + x] != 0) {
                    if (!lastPixelOn) {
                        crossings++;
                    }
                    lastPixelOn = true;
                } else {
                    lastPixelOn = false;
                }
            }
            verticals.add(x, crossings);
        }
        return verticals;
    }



    private static DocumentPage determineInitialBlur(Mat inputImage, String uri, long start, DocumentPage documentPage) {
        LOG.debug("Determining blur: {}", System.currentTimeMillis() - start);
        int cocoCount = Integer.MAX_VALUE;
        int medianBlurSize;
        for (medianBlurSize = 1; medianBlurSize < 21; medianBlurSize += 2) {
            Mat medianBlurred = new Mat();
            Imgproc.medianBlur(inputImage, medianBlurred, medianBlurSize);
            DocumentPage tmpDocumentPage = new DocumentPage(medianBlurred, uri);
            Mat tmp = new Mat();
            int tmpCoCoCount = Imgproc.connectedComponents(tmpDocumentPage.getBinaryImage(), tmp);
            tmp = OpenCVWrapper.release(tmp);
            if (tmpCoCoCount < cocoCount / 2) {
                cocoCount = tmpCoCoCount;
            } else {
                break;
            }
            medianBlurred = OpenCVWrapper.release(medianBlurred);
        }

        LOG.debug("Determined blur: {}", System.currentTimeMillis() - start);
        LOG.debug("medianBlur: {} cocoCount: {}", medianBlurSize, cocoCount);

        if (medianBlurSize > 1) {
            Imgproc.medianBlur(inputImage, inputImage, medianBlurSize);
            documentPage = new DocumentPage(inputImage, uri);
        }
        return documentPage;
    }

    private static void getSmearedImage(Mat binary) {
        Mat smearedImage = Mat.zeros(binary.height(), binary.width(), CvType.CV_8UC1);
        org.opencv.core.Point anchor = new org.opencv.core.Point(1, 1);
        Imgproc.erode(binary, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(9, 0);
        Imgproc.dilate(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(20, 1)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(11, 0);
        Imgproc.erode(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(25, 1)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(14, 0);
        Imgproc.dilate(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(30, 2)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(16, 0);
        Imgproc.erode(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(35, 1)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(19, 0);
        Imgproc.dilate(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(40, 2)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(21, 0);
        Imgproc.erode(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(45, 1)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(24, 0);
        Imgproc.dilate(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(50, 2)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(26, 0);
        Imgproc.erode(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(55, 1)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(29, 0);
        Imgproc.dilate(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(60, 2)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(31, 0);
        Imgproc.erode(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(65, 1)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(34, 0);
        Imgproc.dilate(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(70, 2)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(36, 0);
        Imgproc.erode(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(75, 1)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(39, 0);
        Imgproc.dilate(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(80, 2)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(41, 0);
        Imgproc.erode(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(85, 1)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(89, 0);
        Imgproc.dilate(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(180, 2)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        anchor = new org.opencv.core.Point(91, 0);
        Imgproc.erode(smearedImage, smearedImage, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(185, 1)), anchor, 1, BORDER_CONSTANT, new Scalar(0));
        Imgcodecs.imwrite("/scratch/images/out-smeared.png", smearedImage);
    }


    private static void createLargeCoCoImage(java.util.List<ConnectedComponent> cocos, Mat binaryImage) {
        Mat largeCoCoImage = Mat.zeros(binaryImage.height(), binaryImage.width(), CV_32S);
        for (ConnectedComponent coco : cocos) {
            if (coco.getBitMap().getWidth() > binaryImage.width() / 10) {
                VisualizationHelper.colorize(largeCoCoImage, coco);

            }

            if (coco.getBitMap().getHeight() > binaryImage.width() / 10) {
                VisualizationHelper.colorize(largeCoCoImage, coco);
            }
        }
        if (OUTPUT_FILE.get()) {
            Imgcodecs.imwrite("/scratch/images/out-largeCoCoImage.png", largeCoCoImage);
        }
    }

    private static void splitByLine(java.util.List<ConnectedComponent> cocos, int height, int width) {
        Mat image = Mat.zeros(height, width, CV_32FC3);
        for (ConnectedComponent coco : cocos) {
            if (coco.getBitMap().getWidth() > image.width() / 10) {
                if (coco.getBitMap().getHeight() < coco.getBitMap().getWidth() / 25) {
                    java.util.List<ConnectedComponent> topCoCos = new ArrayList<>();
                    java.util.List<ConnectedComponent> bottomCoCos = new ArrayList<>();
                    for (ConnectedComponent candidate : cocos) {
                        if (candidate.getY() <= coco.getY() && candidate.getX() < coco.getX() + coco.getBitMap().getWidth() && candidate.getX() + candidate.getBitMap().getWidth() > coco.getX()) {
                            topCoCos.add(candidate);
                        } else {
                            bottomCoCos.add(candidate);
                        }
                    }
                    Color color = VisualizationHelper.getRandomColor();
                    for (ConnectedComponent subCoCo : topCoCos) {
                        subCoCo.setColor(color);
                    }
                    color = VisualizationHelper.getRandomColor();
                    for (ConnectedComponent subCoCo : bottomCoCos) {
                        subCoCo.setColor(color);
                    }
                    VisualizationHelper.colorize(image, topCoCos);
                    VisualizationHelper.colorize(image, bottomCoCos);
                    Imgproc.line(image, new Point(coco.getX(), coco.getY()), new Point(coco.getX() + coco.getBitMap().getWidth(), coco.getY() + coco.getBitMap().getHeight()), new Scalar(255, 255, 255), 3);
                    if (OUTPUT_FILE.get()) {
                        int globalcounter = GLOBAL_COUNTER.incrementAndGet();
                        LOG.debug("writing /scratch/images/out-clusterImage.png");
                        Imgcodecs.imwrite("/scratch/images/out-clusterImage" + globalcounter + ".png", image);
                    }
                }
            }

            if (coco.getBitMap().getHeight() > image.width() / 10) {
                if (coco.getBitMap().getWidth() < coco.getBitMap().getHeight() / 25) {
                    java.util.List<ConnectedComponent> leftCoCos = new ArrayList<>();
                    java.util.List<ConnectedComponent> rightCoCos = new ArrayList<>();
                    for (ConnectedComponent candidate : cocos) {
//                            if (candidate.getX()<= coco.getX()){
                        if (candidate.getX() <= coco.getX() && candidate.getY() < coco.getY() + coco.getBitMap().getHeight() && candidate.getY() + candidate.getBitMap().getHeight() > coco.getY()) {
                            leftCoCos.add(candidate);
                        } else {
                            rightCoCos.add(candidate);
                        }
                    }
                    Color color = VisualizationHelper.getRandomColor();
                    for (ConnectedComponent subCoCo : leftCoCos) {
                        subCoCo.setColor(color);
                    }
                    color = VisualizationHelper.getRandomColor();
                    for (ConnectedComponent subCoCo : rightCoCos) {
                        subCoCo.setColor(color);
                    }
                    VisualizationHelper.colorize(image, leftCoCos);
                    VisualizationHelper.colorize(image, rightCoCos);
                    Imgproc.line(image, new Point(coco.getX(), coco.getY()), new Point(coco.getX() + coco.getBitMap().getWidth(), coco.getY() + coco.getBitMap().getHeight()), new Scalar(255, 255, 255), 3);
                    if (OUTPUT_FILE.get()) {
                        int globalcounter = GLOBAL_COUNTER.incrementAndGet();
                        LOG.debug("writing /scratch/images/out-clusterImage.png");
                        Imgcodecs.imwrite("/scratch/images/out-clusterImage" + globalcounter + ".png", image);
                    }
                }
            }
        }
    }


    private static void getRunlengthLines(Mat binaryImage) {
        Mat verticalImage = LayoutProc.verticalRunlengthInt(binaryImage, 255);
        Mat horizontalImage = LayoutProc.horizontalRunlengthByte(binaryImage, 255);

        Mat combined = Mat.zeros(verticalImage.rows(), verticalImage.cols(), CV_32S);

        Core.addWeighted(horizontalImage, 0.5, verticalImage, 0.5, 0, combined, horizontalImage.depth());

        if (OUTPUT_FILE.get()) {
            Imgcodecs.imwrite("/scratch/images/out-runlengthLinesImage.png", combined);
            Imgcodecs.imwrite("/scratch/images/out-runlengthLinesImageVertical.png", verticalImage);
            Imgcodecs.imwrite("/scratch/images/out-runlengthLinesImageHorizontal.png", horizontalImage);
        }


    }

    private static java.util.List<ConnectedComponent> getHorizontalLines(java.util.List<ConnectedComponent> cocos, Mat image) {
        java.util.List<ConnectedComponent> horizontalLines = new ArrayList<>();
        for (ConnectedComponent coco : cocos) {
            if (coco.getBitMap().getWidth() > image.width() / 10) {
                if (coco.getBitMap().getHeight() < coco.getBitMap().getWidth() / 25) {
                    horizontalLines.add(coco);
                    LOG.debug("{} {} {} {}", coco.getX(), coco.getY(), coco.getX() + coco.getBitMap().getWidth(), coco.getY() + coco.getBitMap().getHeight());
                }
            }
        }
        return horizontalLines;
    }

    private static void getLines(java.util.List<ConnectedComponent> cocos, Mat image) {
        for (ConnectedComponent coco : cocos) {
            if (coco.getBitMap().getWidth() > image.width() / 10) {
                if (coco.getBitMap().getHeight() < coco.getBitMap().getWidth() / 25) {
                    Imgproc.line(image, new Point(coco.getX(), coco.getY()), new Point(coco.getX() + coco.getBitMap().getWidth(), coco.getY() + coco.getBitMap().getHeight()), new Scalar(255, 255, 255), 3);
                }
            }

            if (coco.getBitMap().getHeight() > image.width() / 10) {
                if (coco.getBitMap().getWidth() < coco.getBitMap().getHeight() / 25) {
                    Imgproc.line(image, new Point(coco.getX(), coco.getY()), new Point(coco.getX() + coco.getBitMap().getWidth(), coco.getY() + coco.getBitMap().getHeight()), new Scalar(255, 255, 255), 3);
                }
            }
        }
    }

    private static void getVerticalRunlengthHisto(Mat binaryImage) {
        Mat lines = new Mat();
        LOG.debug("starting hough");
        Imgproc.HoughLinesP(binaryImage, lines, 1, Math.PI / 180, 10, 0, 0);
        LOG.debug("hough done");

        Mat linesImg = Mat.zeros(binaryImage.height(), binaryImage.width(), CV_32SC3);

        for (int x = 0; x < lines.cols(); x++) {
            double[] vec = lines.get(0, x);
            double x1 = vec[0],
                    y1 = vec[1],
                    x2 = vec[2],
                    y2 = vec[3];
            Point start = new Point(x1, y1);
            Point end = new Point(x2, y2);

            int length = (int) Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));

            LOG.debug("length: {}", length);
            if (Math.abs(x1 - x2) / Math.abs(y1 - y2) < 0.1) {
                Imgproc.line(linesImg, start, end, new Scalar(0, 255, 0), 3);
            } else if (Math.abs(y1 - y2) / Math.abs(x1 - x2) < 0.1) {
                Imgproc.line(linesImg, start, end, new Scalar(0, 255, 0), 3);
            } else {
                Imgproc.line(linesImg, start, end, new Scalar(255, 0, 0), 3);
            }
        }
        lines = OpenCVWrapper.release(lines);
        linesImg = OpenCVWrapper.release(linesImg);
    }

    private static void doHoughLines(Mat binaryImage) {
        Mat lines = new Mat();
        LOG.debug("starting hough");
        Imgproc.HoughLinesP(binaryImage, lines, 1, Math.PI / 180, 10, binaryImage.width() / 10.0, 2);
        LOG.debug("hough done");

        Mat linesImg = Mat.zeros(binaryImage.height(), binaryImage.width(), CV_32SC3);

        for (int x = 0; x < lines.cols(); x++) {
            double[] vec = lines.get(0, x);
            double x1 = vec[0],
                    y1 = vec[1],
                    x2 = vec[2],
                    y2 = vec[3];
            Point start = new Point(x1, y1);
            Point end = new Point(x2, y2);

            if (Math.abs(x1 - x2) / Math.abs(y1 - y2) < 0.1) {
                Imgproc.line(linesImg, start, end, new Scalar(0, 255, 0), 3);
            } else if (Math.abs(y1 - y2) / Math.abs(x1 - x2) < 0.1) {
                Imgproc.line(linesImg, start, end, new Scalar(0, 255, 0), 3);
            } else {
                Imgproc.line(linesImg, start, end, new Scalar(255, 0, 0), 3);
            }

        }
        LOG.debug("writing /scratch/images/out-houghLines.png");
        Imgcodecs.imwrite("/scratch/images/out-houghLines.png", linesImg);
        lines= OpenCVWrapper.release(lines);
        linesImg = OpenCVWrapper.release(linesImg);
    }


    private static void createHistogram(Mat inputImage, DocumentPage documentPage) {
        Rect roi = new Rect(documentPage.getLeftMargin(), documentPage.getTopMargin(), documentPage.getRightMargin() - documentPage.getLeftMargin(), documentPage.getBottomMargin() - documentPage.getTopMargin());
        Mat cropped = new Mat(inputImage, roi);

        Mat histo = new Histogram().createRGBHistogram(cropped);
        Imgcodecs.imwrite("/scratch/images/out-histo.png", histo);
        histo = OpenCVWrapper.release(histo);
        cropped = OpenCVWrapper.release(cropped);
    }

    private static Element getDescriptionElement(Document document, DocumentPage documentPage) {
        Element description = document.createElement("Description");

        Element measurementUnit = document.createElement("MeasurementUnit");
        measurementUnit.appendChild(document.createTextNode("pixel"));
        description.appendChild(measurementUnit);

        Element sourceImageInformation = document.createElement("sourceImageInformation");
        Element fileName = document.createElement("fileName");
        fileName.appendChild(document.createTextNode(documentPage.getFilename()));
        sourceImageInformation.appendChild(fileName);
        description.appendChild(sourceImageInformation);

        Element processingSoftware = document.createElement("processingSoftware");
        Element softwareCreator = document.createElement("softwareCreator");
        softwareCreator.appendChild(document.createTextNode("Huygens ING, KNAW"));
        processingSoftware.appendChild(softwareCreator);

        Element softwareName = document.createElement("softwareName");
        softwareName.appendChild(document.createTextNode("nl.knaw.huygens.layoutanalyzer"));
        processingSoftware.appendChild(softwareName);

        Element softwareVersion = document.createElement("softwareVersion");
        softwareVersion.appendChild(document.createTextNode(getVersion()));
        //softwareVersion.appendChild(document.createTextNode(Main.class.getPackage().getImplementationVersion()));
        processingSoftware.appendChild(softwareVersion);

        description.appendChild(processingSoftware);
        return description;
    }

    private static void traceText(Mat gray, int x, int y, Mat colorized) throws Exception {
//        BufferedImage image = ImageConversionHelper.matToBufferedImage(gray);
//            for (int j = x; j < colorized.width(); j++) {
//                if (image.getRGB(j, i) != 0)){
//
//                }
//            }
//

        BufferedImage image = ImageConversionHelper.matToBufferedImage(gray);

        while (x < gray.width() - 1) {
            int up = image.getRGB(x, y - 1);
            int mid = image.getRGB(x, y);
            int down = image.getRGB(x, y + 1);

            if (up > mid) {
                y--;
            }
            if (down > mid) {
                y++;
            }
            LayoutProc.safePut(gray,y, x, 255);
            int[] color = {255, 255, 255};
            colorized.put(y, x, color);
            x++;
        }
        Imgcodecs.imwrite("/scratch/images/out-traced.png", gray);
    }

    private static Integer getInt(String source) {
        Integer result = null;
        try {
            result = Integer.parseInt(source);
        } catch (Exception ignored) {
        }
        return result;
    }

    private static Boolean getBool(String source) {
        Boolean result = null;
        try {
            result = Boolean.parseBoolean(source);
        } catch (Exception ignored) {
        }
        return result;
    }

    private static java.util.List<TestData> readTests() throws IOException {
        Reader reader = Files.newBufferedReader(Paths.get("/home/rutger/data/layout-tests.csv"));
        CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withDelimiter('\t'));
        ArrayList<TestData> testDatas = new ArrayList<>();
        for (CSVRecord csvRecord : csvParser) {
            String uri = csvRecord.get(0);
            Integer pages = getInt(csvRecord.get(1));
            Integer columns = getInt(csvRecord.get(2));
            Integer textLines = getInt(csvRecord.get(3));
            Integer borderLeft = getInt(csvRecord.get(4));
            Integer borderRight = getInt(csvRecord.get(5));
            Integer borderTop = getInt(csvRecord.get(6));
            Integer borderBottom = getInt(csvRecord.get(7));
            Boolean machineprint = getBool(csvRecord.get(8));

            TestData testData = new TestData();
            testData.setUri(uri);
            testData.setPages(pages);
            testData.setColumns(columns);
            testData.setTextLines(textLines);
            testData.setBorderLeft(borderLeft);
            testData.setBorderRight(borderRight);
            testData.setBorderTop(borderTop);
            testData.setBorderBottom(borderBottom);
            testData.setMachinePrint(machineprint);
            testDatas.add(testData);

        }
        return testDatas;
    }

    public static String getVersion() {
        //year/month/day/hour/minute
        return "2018" + "03" + "15" + "09" + "41";
    }

    public void testVersion() throws Exception {
        String uri = "/data/iisg/NL-HaNA_1.11.06.11_1105_0012.jpg";
//        uri= "/data/98_1_Staten-Generaal_1626-1651/00 Vervolg RSG/Pilot OCR 1725/Afbeeldingen/1740/NL-HaNA_1.01.02_3795_0184.jpg";
//      /  String md5 = Hash.getSHA512(uri);

        Mat image = OpenCVWrapper.imread(uri, Imgcodecs.IMREAD_UNCHANGED);

        LayoutConfiguration configuration = new LayoutConfiguration(image);
        LayoutProc.setOutputDebug(false);
        configuration.setOutputFile(false);
        configuration.setOutputDebug(false);
        LayoutConfiguration.setGlobal(configuration);

        ConnectedComponentProc coCoProc = new ConnectedComponentProc();
        DocumentPage documentPage = new DocumentPage(image, uri);
        java.util.List<ConnectedComponent> cocos = documentPage.getCocos();
        LOG.info("Connected components: {}", cocos.size());


        for (int i = 2; i < 100; i++) {
            Mat binary = documentPage.getDespeckledImage().clone();
            Imgproc.dilate(binary, binary, Imgproc.getStructuringElement(Imgproc.MORPH_DILATE, new Size(2 * i, i)));//Math.sqrt(i))));
            Imgproc.erode(binary, binary, Imgproc.getStructuringElement(Imgproc.MORPH_ERODE, new Size(i, i)));//Math.sqrt(i))));
            BufferedImage binImage = ImageConversionHelper.matToBufferedImage(binary);
            cocos = coCoProc.process(binImage, false);
            LayoutProc.deSpeckle(binary, cocos, i);
            Imgcodecs.imwrite(String.format("/scratch/out%s.png", i), binary);
            LOG.info("{} {}", i, cocos.size());
            binary = OpenCVWrapper.release(binary);
            System.gc();
        }

    }
}