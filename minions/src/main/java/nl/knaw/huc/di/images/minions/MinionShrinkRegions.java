package nl.knaw.huc.di.images.minions;

import com.google.common.base.Stopwatch;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import org.apache.commons.cli.*;
import org.apache.commons.io.FilenameUtils;
import org.opencv.core.Core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MinionShrinkRegions extends BaseMinion implements Runnable, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(MinionShrinkRegions.class);

    static {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    private final Path imageFile;
    private final Path pageFile;
    private final String namespace;

    public MinionShrinkRegions(Path imageFile, Path pageFile, String namespace) {
        this.imageFile = imageFile;
        this.pageFile = pageFile;
        this.namespace = namespace;
    }

    private static void shrinkRegions(Path imagePath, String namespace) throws IOException, InterruptedException {
        int numthreads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(numthreads);

        try (DirectoryStream<Path> imageFiles = Files.newDirectoryStream(imagePath)) {
            for (Path imageFile : imageFiles) {
                if (Files.isDirectory(imageFile) || !imageFile.toString().endsWith(".jpg")) {
                    continue;
                }
                Path pagePath = imageFile.toAbsolutePath().getParent().resolve("page").resolve(FilenameUtils.removeExtension(imageFile.getFileName().toString()) + ".xml");
                Runnable worker = new MinionShrinkRegions(imageFile, pagePath, namespace);
                executor.execute(worker);//calling execute method of ExecutorService

            }
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.DAYS)) {
                LOG.warn("Timed out waiting for shrink region workers to finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while waiting for shrink region workers", e);
        }

        LOG.info("All tasks completed.");
    }

    public static Options getOptions() {
        final Options options = new Options();

        options.addOption(Option.builder("input").hasArg(true).required(true)
                .desc("input folder that contains the images and page-folder that has to be updated").build());
        options.addOption("help", false, "prints this help dialog");
        options.addOption("use_2013_namespace", "set PageXML namespace to 2013, to avoid causing problems with Transkribus");

        return options;
    }

    public static void main(String[] args) throws Exception {
        String path = "/data/statengeneraalhandwritten/";

        CommandLineParser parser = new DefaultParser();
        final CommandLine commandLine;
        try {
            commandLine = parser.parse(getOptions(), args);
        } catch (ParseException e) {
            printHelp(getOptions(), "java " + MinionShrinkRegions.class.getName());
            return;
        }

        if (commandLine.hasOption("help")){
            printHelp(getOptions(), "java " + MinionShrinkRegions.class.getName());
            return;
        }
        String namespace = commandLine.hasOption("use_2013_namespace") ? PageUtils.NAMESPACE2013: PageUtils.NAMESPACE2019;

        path = commandLine.getOptionValue("input");

        shrinkRegions(Paths.get(path), namespace);
    }

    @Override
    public void run() {
        try {
            LOG.info("Shrinking regions for: " + this.imageFile.toAbsolutePath());
            Stopwatch stopwatch = Stopwatch.createStarted();
            PageUtils.shrinkRegions(this.imageFile, this.pageFile, this.namespace);
            LOG.debug(this.imageFile.toAbsolutePath() + " took " + stopwatch.elapsed(TimeUnit.MILLISECONDS) + " milliseconds");
        } catch (IOException | TransformerException e) {
            LOG.error("Unexpected error", e);
        } finally {
            try {
                this.close();
            } catch (Exception e) {
                LOG.error("Unexpected error", e);
            }
        }
    }

    @Override
    public void close() throws Exception {
        System.gc();
    }
}
