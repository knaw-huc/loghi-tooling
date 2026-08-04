package nl.knaw.huc.di.images.minions;

import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.pipelineutils.ErrorFileWriter;
import org.apache.commons.cli.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class MinionSplitPageXMLTextLineIntoWords implements Runnable, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(MinionSplitPageXMLTextLineIntoWords.class);

    private final String identifier;
    private final Supplier<PcGts> pageSupplier;
    private final String outputFile;
    private final Consumer<String> errorLog;
    private final String namespace;
    private final Optional<ErrorFileWriter> errorFileWriter;

    public MinionSplitPageXMLTextLineIntoWords(String identifier, Supplier<PcGts> pageSupplier, String outputFile, String namespace) {
        this(identifier, pageSupplier, outputFile, error -> {}, namespace, Optional.empty());
    }

    public MinionSplitPageXMLTextLineIntoWords(String identifier, Supplier<PcGts> pageSupplier, String outputFile, Consumer<String> errorLog, String namespace, Optional<ErrorFileWriter> errorFileWriter) {

        this.identifier = identifier;
        this.pageSupplier = pageSupplier;
        this.outputFile = outputFile;
        this.errorLog = errorLog;
        this.namespace = namespace;
        this.errorFileWriter = errorFileWriter;
    }

    public static Options getOptions() {
        final Options options = new Options();
        options.addOption(
                Option.builder("input_path").required(true).hasArg(true).desc("The folder that contains the PAGE files that should be updated").build()
        );
        options.addOption("help", false, "prints this help dialog");
        options.addOption("use_2013_namespace", "set PageXML namespace to 2013, to avoid causing problems with Transkribus");

        return options;
    }

    public static void printHelp(Options options, String callName) {
        final HelpFormatter helpFormatter = new HelpFormatter();

        helpFormatter.printHelp(callName, options, true);
    }

    public static void main(String[] args) throws Exception {
        int numthreads = 4;

        String input = "/scratch/limited/page";

        final Options options = getOptions();
        CommandLineParser commandLineParser = new DefaultParser();
        final CommandLine commandLine;
        try {
            commandLine = commandLineParser.parse(options, args);
        } catch (ParseException ex) {
            printHelp(options, "java " + MinionSplitPageXMLTextLineIntoWords.class.getName());
            return;
        }

        if (commandLine.hasOption("help")) {
            printHelp(options, "java " + MinionSplitPageXMLTextLineIntoWords.class.getName());
            return;
        }
        input = commandLine.getOptionValue("input_path");

        String namespace = commandLine.hasOption("use_2013_namespace") ? PageUtils.NAMESPACE2013: PageUtils.NAMESPACE2019;

        if (commandLine.hasOption("threads")) {
            numthreads = Integer.parseInt(commandLine.getOptionValue("threads"));
        }

        ExecutorService executor = Executors.newFixedThreadPool(numthreads);

        Path inputPath = Paths.get(input);
        DirectoryStream<Path> fileStream = Files.newDirectoryStream(inputPath);
        List<Path> files = new ArrayList<>();
        fileStream.forEach(files::add);
        files.sort(Comparator.comparing(Path::toString));

        for (Path file : files) {
            if (file.getFileName().toString().endsWith(".xml")) {
                LOG.info(file + ": processing");

                if (Files.exists(file)) {
                    final Supplier<PcGts> pageSupplier = () -> {
                        try {
                            return PageUtils.readPageFromFile(file);
                        } catch (IOException e) {
                            LOG.error("Cannot read page: " + e);
                            return null;
                        }
                    };
                    String outputFile = file.toString();

                    Runnable worker = new MinionSplitPageXMLTextLineIntoWords(outputFile, pageSupplier, outputFile, namespace);
                    executor.execute(worker);
                }
            }
        }

        executor.shutdown();

        LOG.info("Finished all threads");
    }

    public void splitIntoWords(Supplier<PcGts> pageSupplier, String outputFile, String namespace) throws IOException, TransformerException {
        PcGts page = pageSupplier.get();
        if (page == null) {
            throw new IOException("Could not load page.");
        }
        LayoutProc.splitLinesIntoWords(page);

        try {
            final Path outputFilePath = Paths.get(outputFile);
            final Path parent = outputFilePath.getParent();
            if (!Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            PageUtils.writePageToFileAtomic(page, namespace, outputFilePath);
        } catch (IOException ex) {
            errorLog.accept("Could not write '" + outputFile+"'");
            throw ex;
        } catch (TransformerException ex) {
            errorLog.accept("Could not transform xml to 2013 page: "+ ex.getMessage());
            throw ex;
        }
    }

    @Override
    public void run() {
        try {
            LOG.info(this.identifier);
            splitIntoWords(this.pageSupplier, outputFile, this.namespace);
        } catch (IOException | TransformerException e) {
            errorFileWriter.ifPresent(errorWriter -> errorWriter.write(identifier, e, "Could not process file"));
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
    }
}
