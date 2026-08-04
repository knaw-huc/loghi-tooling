package nl.knaw.huc.di.images.minions;

import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.stringtools.StringTools;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MinionConvertPageToTxt {
    private static final Logger LOG = LoggerFactory.getLogger(MinionConvertPageToTxt.class);

    private static Options getOptions() {
        Options options = new Options();
        options.addOption("pagexmldir", true, "pagexmldir.");
        options.addOption("no_overwrite", false, "do not overwrite txt files.");
        options.addOption("overwrite_when_newer", false, "overwrite txt file only when the pagexml is newer than the existing txt file. Takes precedence over no_overwrite.");
        options.addOption("recurse_dir", false, "recursively search subdirectories for directories named 'page' and process all xml files found in them.");
        options.addOption("threads", true, "number of threads to use, default 4.");
        options.addOption("silent", false, "only output total number of xml files seen and number of txt files converted. Errors are always shown.");
        options.addOption("linebased", false, "use line based text extraction. Use a single line for each text line.");
        options.addOption("plaintext", false, "From the pageXml extract plainText instead of UTF8");
        return options;
    }

    private static List<Path> collectXmlFiles(Path rootDir, boolean recurseDir) throws IOException {
        List<Path> result = new ArrayList<>();
        if (!recurseDir) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootDir)) {
                for (Path path : stream) {
                    if (path.toString().endsWith(".xml")) {
                        result.add(path);
                    }
                }
            }
        } else {
            Files.walk(rootDir).forEach(path -> {
                if (path.toString().endsWith(".xml")
                        && path.getParent() != null
                        && path.getParent().getFileName() != null
                        && path.getParent().getFileName().toString().equals("page")) {
                    result.add(path);
                }
            });
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        String pagePathTxt = null;

        Options options = getOptions();
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        if (cmd.hasOption("pagexmldir")) {
            pagePathTxt = cmd.getOptionValue("pagexmldir");
        } else {
            LOG.error("No pagexmldir specified, exiting.");
            System.exit(1);
        }

        boolean noOverwrite = cmd.hasOption("no_overwrite");
        boolean overwriteWhenNewer = cmd.hasOption("overwrite_when_newer");
        boolean recurseDir = cmd.hasOption("recurse_dir");
        boolean silent = cmd.hasOption("silent");
        boolean lineBased = cmd.hasOption("linebased");
        boolean plaintext = cmd.hasOption("plaintext");
        int numThreads = 4;
        if (cmd.hasOption("threads")) {
            numThreads = Integer.parseInt(cmd.getOptionValue("threads"));
        }

        Path rootPath = Paths.get(pagePathTxt);
        List<Path> xmlFiles = collectXmlFiles(rootPath, recurseDir);

        AtomicInteger xmlFilesSeen = new AtomicInteger(0);
        AtomicInteger txtFilesConverted = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        for (Path xmlFile : xmlFiles) {
            executor.submit(() -> {
                xmlFilesSeen.incrementAndGet();
                String outputFile = FilenameUtils.removeExtension(xmlFile.toAbsolutePath().toString()) + ".txt";
                Path outputPath = Paths.get(outputFile);
                try {
                    if (Files.exists(outputPath)) {
                        if (overwriteWhenNewer) {
                            FileTime xmlTime = Files.getLastModifiedTime(xmlFile);
                            FileTime txtTime = Files.getLastModifiedTime(outputPath);
                            if (!(xmlTime.compareTo(txtTime) > 0)) {
                                if (!silent) {
                                    LOG.info("skipping (txt is up to date): " + outputFile);
                                }
                                return;
                            }
                        } else if (noOverwrite) {
                            if (!silent) {
                                LOG.info("skipping: " + outputFile);
                            }
                            return;
                        }
                    }

                    PcGts page = PageUtils.readPageFromFile(xmlFile, true);
                    if (page == null) {
                        LOG.error("Could not read page xml: " + xmlFile.toAbsolutePath());
                        return;
                    }
                    String text = PageUtils.convertToTxt(page, !lineBased, plaintext);
                    if (!silent) {
                        LOG.info("writing: " + outputFile);
                    }
                    StringTools.writeFile(outputFile, text);
                    txtFilesConverted.incrementAndGet();
                } catch (Exception e) {
                    LOG.error("Error processing file: " + xmlFile.toAbsolutePath() + " - " + e.getMessage(), e);
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);

        LOG.info("Processed {} xml files, converted {} txt files.", xmlFilesSeen.get(), txtFilesConverted.get());
    }
}