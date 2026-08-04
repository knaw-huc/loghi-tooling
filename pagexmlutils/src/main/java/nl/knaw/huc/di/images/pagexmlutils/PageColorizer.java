package nl.knaw.huc.di.images.pagexmlutils;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import nl.knaw.huc.di.images.imageanalysiscommon.StringConverter;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.layoutds.models.Page.TextLine;
import nl.knaw.huc.di.images.layoutds.models.Page.TextRegion;
import nl.knaw.huc.di.images.stringtools.StringTools;
import org.apache.commons.io.FilenameUtils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class PageColorizer {

    private static final Logger LOG = LoggerFactory.getLogger(PageColorizer.class);
    /**
     * Shared, thread-safe {@link XmlMapper}. Re-using one instance avoids the
     * reflective per-call initialisation cost (SMELL-06).
     */
    private static final XmlMapper XML_MAPPER = new XmlMapper();
    static {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }
    public static void colorizeTextLine(Mat colorized, TextLine textLine) {
        int thickness = 3;
        Scalar color = new Scalar(0, 0, 255);
        List<Point> points = StringConverter.stringToPoint(textLine.getCoords().getPoints());
        if (points.size()==0){
            return;
        }
        Point first = points.get(0);
        for (int i = 1; i < points.size(); i++) {
            Point second = points.get(i);
            Imgproc.line(colorized, first, second, color, thickness);

            first = second;
        }
        Imgproc.line(colorized, points.get(0), points.get(points.size() - 1), color, thickness);

        color = new Scalar(255,255,255);
        points = StringConverter.stringToPoint(textLine.getBaseline().getPoints());
        if (points.size()==0){
            return;
        }
        first = points.get(0);
        for (int i = 1; i < points.size(); i++) {
            Point second = points.get(i);
            Imgproc.line(colorized, first, second, color, thickness);

            first = second;
        }

    }
    public static void colorizeTextRegion(Mat colorized, TextRegion textRegion, boolean colorizeTextLines) {
        int thickness = 3;
        Scalar color = new Scalar(255, 0, 255);

        List<Point> points = StringConverter.stringToPoint(textRegion.getCoords().getPoints());

        List<String> marginaList = new ArrayList<>();
        marginaList.add("marginalia");
        marginaList.add("Marginalia");
        List<String> dateList = new ArrayList<>();
        dateList.add("date");
        dateList.add("Datum");
        List<String> pagenumberList = new ArrayList<>();
        pagenumberList.add("page-number");

        List<String> resolutionList = new ArrayList<>();
        resolutionList.add("resolution");



        String type = null;
        if (textRegion.getCustom() != null) {
            String[] splitted = textRegion.getCustom().split(" ");
            for (int i = 0; i < splitted.length; i++) {
                if (splitted[i].equals("structure")) {
                    type = splitted[i + 1]
                            .replace("{", "")
                            .replace("}", "")
                            .replace(";", "")
                            .split(":")[1];

                }
            }
        }


        if (marginaList.contains(textRegion.getRegionType()) ||marginaList.contains(type)) {
            color = new Scalar(0, 0, 255);
        }
        if (dateList.contains(textRegion.getRegionType()) ||dateList.contains(type)) {
            color = new Scalar(255, 0, 0);
        }
        if (pagenumberList.contains(textRegion.getRegionType()) ||pagenumberList.contains(type)) {
            color = new Scalar(0, 255, 0);
        }
        if (resolutionList.contains(textRegion.getRegionType()) ||resolutionList.contains(type)) {
            color = new Scalar(0, 255, 255);
        }

        if ("firstline".equalsIgnoreCase(type)) {
            color = new Scalar(255, 0, 0);
        }
        if ("singleline".equalsIgnoreCase(type)) {
            color = new Scalar(0, 255, 0);
        }
        if ("lastline".equalsIgnoreCase(type)) {
            color = new Scalar(0, 0, 255);
        }

        Point first = points.get(0);
        for (int i = 1; i < points.size(); i++) {
            Point second = points.get(i);
            Imgproc.line(colorized, first, second, color, thickness);

            first = second;
        }
        Imgproc.line(colorized, points.get(0), points.get(points.size() - 1), color, thickness);

        if (colorizeTextLines) {
            for (TextLine textLine : textRegion.getTextLines()) {
                colorizeTextLine(colorized, textLine);
            }
        }

    }

    public static Mat colorize(Mat mat, PcGts pagexml) {
        Mat colorized = mat.clone();

        for (TextRegion textRegion : pagexml.getPage().getTextRegions()) {
            colorizeTextRegion(colorized, textRegion, true);

        }
        return colorized;
    }

    private static boolean isImage(Path path) throws IOException {
        String mimetype = java.nio.file.Files.probeContentType(path);
        if (mimetype == null) {
            return false;
        }
        String type = mimetype.split("/")[0];
        return type.equals("image");
    }


    private static List<Path> getImages(Path path) throws IOException {
        List<Path> images = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(path)) {
            for (Path file : files) {

                if (isImage(file)) {
                    images.add(file);
                }
                if (new File(file.toAbsolutePath().toString()).isDirectory()) {
                    images.addAll(getImages(file));
                }
            }
        } catch (AccessDeniedException adx) {
            LOG.warn("access denied: {}", path.toAbsolutePath(), adx);
        }
        return images;
    }

    private static List<Path> getXmls(Path path) throws IOException {
        List<Path> xmls = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(path)) {
            for (Path file : files) {
                if (file.getFileName().toString().endsWith(".xml")) {
                    xmls.add(file);
                }
                if (new File(file.toAbsolutePath().toString()).isDirectory()) {
                    xmls.addAll(getImages(file));
                }
            }
        } catch (AccessDeniedException adx) {
            LOG.warn("access denied: {}", path.toAbsolutePath(), adx);
        }
        return xmls;
    }

    public static void main(String[] args) throws IOException {
//        String inputdir = "/home/rutger/src/P2PaLA/work_zones_BL_firstline/results/prod";
//        String inputdir = "/home/rutger/src/P2PaLA/work_zones_BL_firstline/results/prod/page";
        String inputdir = "/home/rutger/src/P2PaLA/work_multiline_republic/results/prod/page";
//        List<Path> images = getImages(Paths.get(inputdir));
        List<Path> xmls = getXmls(Paths.get(inputdir));

        for (Path xml : xmls) {
            String imageFile = "/data/statengeneraalall/"+ FilenameUtils.removeExtension(xml.getFileName().toString()) + ".jpg";

            if (Files.notExists(Paths.get(imageFile))){
                continue;
            }
            Mat input = Imgcodecs.imread(imageFile);
            Mat result = null;
            try {
                String pageXmlString = StringTools.readFile(xml.toAbsolutePath().toString());
                PcGts page;
                try {
                    page = XML_MAPPER.readValue(pageXmlString, PcGts.class);
                } catch (Exception ex) {
                    LOG.warn("Could not parse PAGE-XML {}", xml, ex);
                    continue;
                }

                result = colorize(input, page);
                Imgcodecs.imwrite("/tmp/colorized/" + FilenameUtils.removeExtension(xml.getFileName().toString()) + ".jpg", result);
            } finally {
                if (input != null) {
                    input.release();
                }
                if (result != null) {
                    result.release();
                }
            }
        }
//        String xmlFile = "/media/rutger/bf31fede-7650-4556-884c-2b0ed365db77/ijsberg/notarieel/NL-HlmNHA_1617_1665_0430.xml";
//        String imageFile = "/media/rutger/bf31fede-7650-4556-884c-2b0ed365db77/ijsberg/notarieel/NL-HlmNHA_1617_1665_0430.jpg";
//
//        Mat input = Imgcodecs.imread(imageFile);
//
//        XmlMapper mapper = new XmlMapper();
//        String pageXml = StringTools.readFile(xmlFile, StringTools.CHARSET_UTF8);
//        PcGts page = null;
//        try {
//            page = mapper.readValue(pageXml, PcGts.class);
//        } catch (Exception ex) {
//
//        }
//
//        Mat result = colorize(input, page);
//
//        Imgcodecs.imwrite("/tmp/colorized.jpg", result);
    }

}
