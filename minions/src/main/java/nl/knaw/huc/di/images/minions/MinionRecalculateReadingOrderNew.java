package nl.knaw.huc.di.images.minions;

import com.google.common.base.Strings;
import nl.knaw.huc.di.images.imageanalysiscommon.StringConverter;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutds.models.Page.*;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.pipelineutils.ErrorFileWriter;
import nl.knaw.huc.di.images.stringtools.StringTools;
import org.apache.commons.cli.*;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;


public class MinionRecalculateReadingOrderNew implements Runnable, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MinionRecalculateReadingOrderNew.class);

    private final double interlineClusteringMultiplier;
    private final String identifier;
    private final PcGts page;
    private final Consumer<PcGts> pageSaver;
    private final boolean cleanBorders;
    private final int borderMargin;
    private final boolean asSingleRegion;
    private final double dubiousSizeWidthMultiplier;
    private final Double dubiousSizeWidth;
    private final Optional<ErrorFileWriter> errorFileWriter;

    private final List<String> readingOrderList;

    private static final class TextLineGeometry {
        private final List<Point> baselinePoints;
        private final Point start;
        private final Point end;
        private final Rect baselineRect;
        private final Rect coordsRect;

        private TextLineGeometry(TextLine textLine) {
            this.baselinePoints = StringConverter.stringToPoint(textLine.getBaseline().getPoints());
            this.start = baselinePoints.get(0);
            this.end = baselinePoints.get(baselinePoints.size() - 1);
            this.baselineRect = LayoutProc.getBoundingBox(baselinePoints);
            this.coordsRect = LayoutProc.getBoundingBox(StringConverter.stringToPoint(textLine.getCoords().getPoints()));
        }
    }

    public MinionRecalculateReadingOrderNew(String identifier, PcGts page, Consumer<PcGts> pageSaver,
                                            boolean cleanBorders, int borderMargin, boolean asSingleRegion,
                                            double interlineClusteringMultiplier, double dubiousSizeWidthMultiplier,
                                            Double dubiousSizeWidth, List<String> readingOrderList,
                                            Optional<ErrorFileWriter> errorFileWriter) {
        this.identifier = identifier;
        this.page = page;
        this.pageSaver = pageSaver;
        this.cleanBorders = cleanBorders;
        this.borderMargin = borderMargin;
        this.asSingleRegion= asSingleRegion;
        this.interlineClusteringMultiplier = interlineClusteringMultiplier;
        this.dubiousSizeWidthMultiplier = dubiousSizeWidthMultiplier;
        this.dubiousSizeWidth = dubiousSizeWidth;
        this.errorFileWriter = errorFileWriter;
        if (readingOrderList==null || readingOrderList.isEmpty()){
            readingOrderList = new ArrayList<>();
            readingOrderList.add(null);
        }
        this.readingOrderList =readingOrderList;
    }

    private static Options getOptions() {
        Options options = new Options();

        options.addOption(Option.builder("input_dir").required(true).hasArg(true)
                .desc("directory of the page files that should be processed").build()
        );
        options.addOption("threads", true, "threads to use, default 2");
        options.addOption("clean_borders", false, "when true removes the small baselines, that are visible on the piece of the adjacent that is visible in the scan (default value is false)");
        options.addOption("border_margin", true, "border_margin, default 200");
        options.addOption("help", false, "prints this help dialog");
        options.addOption("as_single_region", false, "as single region");
        options.addOption("dubious_size_width", true, "the minimum length in pixels the baseline must have to be a valid baseline connected to the side of the iamge, default 5% of the image width");
        options.addOption("dubious_size_width_multiplier", true, "calculate the dubious_size_width, when this property is used the dubious_size_width is used, default 0.05");
        options.addOption("interline_clustering_multiplier", true,  "helps to calculate the maximum cluster distance between two lines, default 1.5");
        options.addOption("reading_order_list", true, "reading_order_list");
        options.addOption("use_2013_namespace", "set PageXML namespace to 2013, to avoid causing problems with Transkribus");

        return options;
    }

    public static void printHelp(Options options, String callName) {
        final HelpFormatter helpFormatter = new HelpFormatter();

        helpFormatter.printHelp(callName, options, true);
    }

    public static void main(String[] args) throws IOException, ParseException, InterruptedException {
        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();
        CommandLine commandLine;

        try {
            commandLine = parser.parse(options, args);
        } catch (ParseException ex ) {
            printHelp(options, "java " + MinionRecalculateReadingOrderNew.class.getName());
            return;
        }

        if (commandLine.hasOption("help")) {
            printHelp(options, "java " + MinionRecalculateReadingOrderNew.class.getName());
            return;
        }

        final String inputDir ;
        if (commandLine.hasOption("input_dir")) {
            inputDir = commandLine.getOptionValue("input_dir");
            LOG.info("input_dir: " + inputDir);
        }else{
            printHelp(options, "java " + MinionRecalculateReadingOrderNew.class.getName());
            return;
        }
        int numthreads = 2;
        if (commandLine.hasOption("threads")) {
            numthreads = Integer.parseInt(commandLine.getOptionValue("threads"));
        }
        boolean cleanBorders = commandLine.hasOption("clean_borders");
        int borderMargin = 200;
        if (commandLine.hasOption("border_margin")) {
            borderMargin = Integer.parseInt(commandLine.getOptionValue("border_margin"));
        }

        boolean asSingleRegion = commandLine.hasOption("as_single_region");

        double interlineClusteringMultiplier = 1.5;
        if (commandLine.hasOption("interline_clustering_multiplier")) {
            interlineClusteringMultiplier = Double.parseDouble(commandLine.getOptionValue("interline_clustering_multiplier"));
        }

        double dubiousSizeWidthMultiplier = 0.05;
        if (commandLine.hasOption("dubious_size_width_multiplier")) {
            dubiousSizeWidthMultiplier = Double.parseDouble(commandLine.getOptionValue("dubious_size_width_multiplier"));
        }

        Double dubiousSizeWidth = null;
        if (commandLine.hasOption("dubious_size_width")) {
            dubiousSizeWidth = Double.parseDouble(commandLine.getOptionValue("dubious_size_width"));
        }

        List<String> readingOrderList = new ArrayList<>();
        if (commandLine.hasOption("reading_order_list")) {
            readingOrderList.addAll(Arrays.asList(commandLine.getOptionValue("reading_order_list").split(",")));
        }

        String namespace = commandLine.hasOption("use_2013_namespace") ? PageUtils.NAMESPACE2013: PageUtils.NAMESPACE2019;

        ExecutorService executor = Executors.newFixedThreadPool(numthreads);
        DirectoryStream<Path> fileStream = Files.newDirectoryStream(Paths.get(inputDir));
        List<Path> files = new ArrayList<>();
        fileStream.forEach(files::add);
        files.sort(Comparator.comparing(Path::toString));

        for (Path file : files) {
            if (!file.toFile().isFile()) {
                continue;
            }
            if (file.getFileName().toString().endsWith(".xml")) {
                LOG.info(file.toAbsolutePath().toString());
                final String pageFile = file.toAbsolutePath().toString();
                String pcGtsString = StringTools.loadStringFromFile(pageFile);
                PcGts page = PageUtils.readPageFromString(pcGtsString);

                Consumer<PcGts> pageSaver = newPage -> {
                    try {
                        PageUtils.writePageToFile(newPage, namespace,  Paths.get(pageFile));
                    } catch (IOException e) {
                        LOG.error("Could not save updated page", e);
                    } catch (TransformerException e) {
                        LOG.error("Could not transform page to 2013 version", e);
                    }
                };



                Runnable worker = new MinionRecalculateReadingOrderNew(pageFile, page, pageSaver, cleanBorders,
                        borderMargin, asSingleRegion, interlineClusteringMultiplier, dubiousSizeWidthMultiplier,
                        dubiousSizeWidth, readingOrderList, Optional.empty());
                executor.execute(worker);
            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.DAYS)) {
                LOG.warn("Timed out waiting for reading-order workers to finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while waiting for reading-order workers", e);
        }
        LOG.info("Finished all threads");
    }

    @Override
    public void close() throws Exception {
        System.gc();
    }

    public PcGts runPage(String id, PcGts page, boolean cleanBorders, int borderMargin, boolean asSingleRegion,
                         List<String> readingOrderList) {
        // Minimal length of baseline that is connected to the border of the image
        double dubiousSizeWidth = this.dubiousSizeWidth != null ? this.dubiousSizeWidth : page.getPage().getImageWidth() * dubiousSizeWidthMultiplier;
        List<TextLine> allLines = new ArrayList<>();
        for (TextRegion textRegion : page.getPage().getTextRegions()) {
            allLines.addAll(textRegion.getTextLines());
        }
        if (asSingleRegion){
            return getPcGtsWithSingleRegion(page, allLines);
        }

        List<TextLineGeometry> allLineGeometries = new ArrayList<>(allLines.size());
        Map<TextLine, TextLineGeometry> geometryByTextLine = new IdentityHashMap<>();
        for (TextLine textLine : allLines) {
            TextLineGeometry textLineGeometry = new TextLineGeometry(textLine);
            allLineGeometries.add(textLineGeometry);
            geometryByTextLine.put(textLine, textLineGeometry);
        }

        double interlinemedian = interlineMedian(allLineGeometries, 10);
        LOG.info(id + " interlinemedian: " + interlinemedian);

        List<TextLine> linesToRemove = new ArrayList<>();
        page.getPage().getTextRegions().clear();
        if (cleanBorders) {
            cleanBorders(page, borderMargin, allLines, dubiousSizeWidth, linesToRemove, geometryByTextLine);
        }

        Set<TextLine> removedLines = Collections.newSetFromMap(new IdentityHashMap<>());
        for (TextLine textLine : allLines) {
            if (removedLines.contains(textLine)) {
                continue;
            }
            TextLineGeometry textLineGeometry = geometryByTextLine.get(textLine);
            List<TextLine> cluster = new ArrayList<>();
            cluster.add(textLine);
            Rect regionPoints = textLineGeometry.coordsRect;
            removedLines.add(textLine);
            Set<TextLine> checkedTextLines = Collections.newSetFromMap(new IdentityHashMap<>());

            boolean newLinesAdded = true;
            while (newLinesAdded) {
                newLinesAdded = false;
                ArrayList<TextLine> toCheck = new ArrayList<>(cluster.size());
                for (TextLine clusteredTextLine : cluster) {
                    if (!checkedTextLines.contains(clusteredTextLine)) {
                        toCheck.add(clusteredTextLine);
                    }
                }
                for (TextLine mainTextLine : toCheck) {
                    checkedTextLines.add(mainTextLine);
//                    System.out.println("new mainTextLine: " + stopwatch.elapsed(TimeUnit.MILLISECONDS));
                    TextLineGeometry mainTextLineGeometry = geometryByTextLine.get(mainTextLine);
//                    double mainTextLineOrientation = LayoutProc.getMainAngle(mainPoints);
                    Point mainTextLineStart = mainTextLineGeometry.start;
                    Point mainTextLineEnd = mainTextLineGeometry.end;

                    for (TextLine subTextLine : allLines) {
                        if (removedLines.contains(subTextLine)) {
                            continue;
                        }
//                        double subTextLineOrientation = LayoutProc.getMainAngle(StringConverter.stringToPoint(subTextLine.getBaseline().getPoints()));
//                if subTextLine same orientation
//                if (LayoutProc.distance())
//                && closeby
//                        System.out.println("new subtextline: " + stopwatch.elapsed(TimeUnit.MILLISECONDS));
                        TextLineGeometry subTextLineGeometry = geometryByTextLine.get(subTextLine);
                        Point subTextLineStart = subTextLineGeometry.start;
                        Point subTextLineEnd = subTextLineGeometry.end;
                        Rect textLineRect = subTextLineGeometry.baselineRect;
                        // if textline inside region already
                        if (regionPoints.x< textLineRect.x &&
                                regionPoints.x + regionPoints.width > textLineRect.x + textLineRect.width &&
                                regionPoints.y< textLineRect.y &&
                                regionPoints.y + regionPoints.height > textLineRect.y + textLineRect.height){
                            cluster.add(subTextLine);
                            regionPoints = growCluster(regionPoints, subTextLineGeometry.coordsRect);

                            removedLines.add(subTextLine);
                            newLinesAdded = true;
                            continue;
                        }
                        //add to cluster if starts are close together
                        double maxDistance = interlinemedian * interlineClusteringMultiplier;
                        if (StringConverter.distance(mainTextLineStart, subTextLineStart) < maxDistance) {
                            cluster.add(subTextLine);
                            regionPoints = growCluster(regionPoints, subTextLineGeometry.coordsRect);
                            removedLines.add(subTextLine);
                            newLinesAdded = true;
                        } else {
                            //add to cluster if centers are vertically close together
                            double horizontalSubPointX = ((subTextLineStart.x + subTextLineEnd.x) / 2);
                            double averageSubPointY = ((subTextLineStart.y + subTextLineEnd.y) / 2);
                            double mainTextLineY = ((mainTextLineStart.y + mainTextLineEnd.y) / 2);
                            if (horizontalSubPointX > mainTextLineStart.x && horizontalSubPointX < mainTextLineEnd.x) {
                                // and y-distance is less than interlineClusteringMultiplier * interline
                                if (Math.abs(averageSubPointY - mainTextLineY) < maxDistance) {
                                    cluster.add(subTextLine);
                                    regionPoints = growCluster(regionPoints, subTextLineGeometry.coordsRect);
                                    removedLines.add(subTextLine);
                                    newLinesAdded = true;
                                }
                            }
                        }
                    }
                    if (newLinesAdded) {
                        break;
                    }
                }
            }

            TextRegion textRegion = new TextRegion();
            textRegion.setId(UUID.randomUUID().toString());
            Coords coords = new Coords();
            textRegion.setCustom("structure {type:Text;}");
            coords.setPoints(StringConverter.pointToString(regionPoints));
            textRegion.setCoords(coords);

            Rect finalRegionPoints = regionPoints;
            cluster.sort(Comparator.comparing(textLine1 ->
                    distanceFromRegionStart(finalRegionPoints, geometryByTextLine.get(textLine1))));
            int counter = 0;
            for (TextLine textLine1 : cluster) {
//                TODO: maybe use TextLineCustom here instead.
                String oldCustom = getOldCustom(textLine1);
                String newCustom = "readingOrder {index:" + counter + ";} "+ (oldCustom != null ? oldCustom.trim(): "");
                newCustom = newCustom.trim();
                textLine1.setCustom(newCustom);
                counter++;
            }
            textRegion.setTextLines(cluster);


            page.getPage().getTextRegions().add(textRegion);
        }

        LayoutProc.reorderRegions(page, readingOrderList);

        page.getMetadata().setLastChange(new Date());

        if (page.getMetadata().getMetadataItems() == null) {
            page.getMetadata().setMetadataItems(new ArrayList<>());
        }
        MetadataItem metadataItem = new MetadataItem();
        metadataItem.setType("processingStep");
        metadataItem.setName("reading-order");
        metadataItem.setValue("loghi-htr-tooling");

        page.getMetadata().getMetadataItems().add(metadataItem);

        return page;
    }

    private static double distanceFromRegionStart(Rect regionPoints, TextLineGeometry textLineGeometry) {
        Point baselineStart = textLineGeometry.start;
        return StringConverter.distance(new Point(regionPoints.x, regionPoints.y),
                new Point(regionPoints.x + (baselineStart.x - regionPoints.x) / 10, baselineStart.y));
    }

    private static String getOldCustom(TextLine textLine1) {
        String oldCustom = textLine1.getCustom();
        if (!Strings.isNullOrEmpty(oldCustom) && oldCustom.contains("readingOrder {index:")){
            // remove old reading order
            String prefix = oldCustom.substring(0, oldCustom.indexOf("readingOrder {index:"));
            String suffix = oldCustom.substring(oldCustom.indexOf("readingOrder"));
            suffix = suffix.substring(suffix.indexOf("}") + 1);
            oldCustom = prefix + suffix;
        }
        return oldCustom;
    }

    private static void cleanBorders(PcGts page, int borderMargin, List<TextLine> allLines, double dubiousSizeWidth,
                                     List<TextLine> linesToRemove, Map<TextLine, TextLineGeometry> geometryByTextLine) {
        for (TextLine textLine : allLines) {
            TextLineGeometry textLineGeometry = geometryByTextLine.get(textLine);
            Point textLineStart = textLineGeometry.start;
            Point textLineEnd = textLineGeometry.end;
            if (Math.abs(textLineEnd.x - page.getPage().getImageWidth()) < borderMargin) {
                if (StringConverter.distance(textLineStart, textLineEnd) < dubiousSizeWidth) {
                    linesToRemove.add(textLine);
                }
            }
            if (textLineStart.x < borderMargin) {
                if (StringConverter.distance(textLineStart, textLineEnd) < dubiousSizeWidth) {
                    linesToRemove.add(textLine);
                }
            }

        }
        allLines.removeAll(linesToRemove);
    }

    private static double interlineMedian(List<TextLineGeometry> textLineGeometries, double minValue) {
        double interlineDistance = interlineMedian(textLineGeometries);
        return Math.max(interlineDistance, minValue);
    }

    private static double interlineMedian(List<TextLineGeometry> textLineGeometries) {
        if (textLineGeometries.isEmpty()) {
            return 0;
        }

        List<Point> allPoints = new ArrayList<>();
        for (TextLineGeometry textLineGeometry : textLineGeometries) {
            allPoints.addAll(textLineGeometry.baselinePoints);
        }

        ArrayList<Double> distances = new ArrayList<>(textLineGeometries.size());
        for (TextLineGeometry textLineGeometry : textLineGeometries) {
            ArrayList<Point> otherPoints = new ArrayList<>(allPoints);
            otherPoints.removeAll(textLineGeometry.baselinePoints);
            double distance = LayoutProc.findClosestDistance(otherPoints, textLineGeometry.baselinePoints);
            distances.add(distance);
        }
        return median(distances);
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        values.sort(Double::compareTo);
        int size = values.size();
        if (size % 2 == 0) {
            return (values.get((size / 2) - 1) + values.get(size / 2)) / 2.0;
        }
        return values.get(size / 2);
    }

    private static Rect growCluster(Rect region, Rect textLineBoundingBox) {
        int xStart = Math.min(region.x, textLineBoundingBox.x);
        int yStart = Math.min(region.y, textLineBoundingBox.y);
        int xStop = Math.max(region.x + region.width, textLineBoundingBox.x + textLineBoundingBox.width);
        int yStop = Math.max(region.y + region.height, textLineBoundingBox.y + textLineBoundingBox.height);
        return new Rect(xStart, yStart, xStop - xStart, yStop - yStart);
    }

    private static PcGts getPcGtsWithSingleRegion(PcGts page, List<TextLine> allLines) {
        TextRegion textRegion = new TextRegion();
        textRegion.setId(UUID.randomUUID().toString());
        Coords coords = new Coords();
        Rect regionPoints = LayoutProc.getBoundingBoxTextLines(allLines);
        coords.setPoints(StringConverter.pointToString(regionPoints));
        textRegion.setCoords(coords);
        textRegion.setTextLines(allLines);
        page.getPage().getTextRegions().clear();
        page.getPage().getTextRegions().add(textRegion);
        return page;
    }

    @Override
    public void run() {
        try {
            PcGts newPage = runPage(identifier, page, cleanBorders, borderMargin, asSingleRegion, readingOrderList);
            pageSaver.accept(newPage);
        } catch (Exception e) {
            errorFileWriter.ifPresent(errorWriter -> errorWriter.write(identifier, e, "Could not process file"));
        } finally {
            try {
                this.close();
            } catch (Exception e) {
                errorFileWriter.ifPresent(errorWriter -> errorWriter.write(identifier, e, "Could not close"));
            }
        }
    }
}
