package nl.knaw.huc.di.images.minions;

import com.google.common.base.Stopwatch;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import org.apache.commons.cli.*;
import org.apache.commons.io.FilenameUtils;
import org.opencv.core.Core;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.activation.MimetypesFileTypeMap;
import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MinionShrinkTextLines extends BaseMinion implements Runnable, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(MinionShrinkTextLines.class);

    static {
        System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
    }

    private final Path imageFile;
    private final Path pageFile;
    private final String namespace;

    public MinionShrinkTextLines(Path imageFile, Path pageFile, String namespace) {
        this.imageFile = imageFile;
        this.pageFile = pageFile;
        this.namespace = namespace;
    }

    private static void shrinkTextLines(Path path, String namespace) throws IOException, InterruptedException {
        int numthreads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(numthreads);

        DirectoryStream<Path> fileStream = Files.newDirectoryStream(path);
        List<Path> files = new ArrayList<>();
        fileStream.forEach(files::add);
        files.sort(Comparator.comparing(Path::toString));

        final MimetypesFileTypeMap mimetypesFileTypeMap = new MimetypesFileTypeMap();

        for (Path imageFile : files) {
            if (!mimetypesFileTypeMap.getContentType(imageFile.toFile()).split("/")[0].equals("image")) {
                continue;
            }
            final Path pageFile = imageFile.toAbsolutePath().getParent().resolve("page").resolve(FilenameUtils.removeExtension(imageFile.getFileName().toString()) + ".xml");
            Runnable worker = new MinionShrinkTextLines(imageFile, pageFile, namespace);
            executor.execute(worker);//calling execute method of ExecutorService

        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.DAYS)) {
                LOG.warn("Timed out waiting for shrink textline workers to finish");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.error("Interrupted while waiting for shrink textline workers", e);
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
//        shrinkTextLines(Paths.get("/home/rutger/republic/randomprint2/"));
        String path = "/media/rutger/HDI0002/difor-data-hannah-divide6/";
//        String path = "/home/rutger/data/antal/baselines/";

        CommandLineParser parser = new DefaultParser();
        final CommandLine commandLine;
        try {
            commandLine = parser.parse(getOptions(), args);
        } catch (ParseException e) {
            printHelp(getOptions(), "java " + MinionShrinkTextLines.class.getName());
            return;
        }

        if (commandLine.hasOption("help")){
            printHelp(getOptions(), "java " + MinionShrinkTextLines.class.getName());
            return;
        }

        path = commandLine.getOptionValue("input");
        String namespace = commandLine.hasOption("use_2013_namespace") ? PageUtils.NAMESPACE2013: PageUtils.NAMESPACE2019;

        shrinkTextLines(Paths.get(path), namespace);
    }

    @Override
    public void run() {
        try {
            LOG.info("Shrinking lines for: " + this.imageFile.toAbsolutePath());
            Stopwatch stopwatch = Stopwatch.createStarted();
            PageUtils.shrinkTextLines(this.imageFile, pageFile, namespace);
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
