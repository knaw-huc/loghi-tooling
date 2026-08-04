package nl.knaw.huc.di.images.minions;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import nl.knaw.huc.di.images.imageanalysiscommon.UnicodeToAsciiTranslitirator;
import nl.knaw.huc.di.images.layoutds.models.HTRConfig;
import nl.knaw.huc.di.images.layoutds.models.Page.*;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.stringtools.StringTools;
import org.apache.commons.cli.*;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.transform.TransformerException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MinionPyLaiaMergePageXML extends BaseMinion implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(MinionPyLaiaMergePageXML.class);

    private final Path file;
    private final HTRConfig pyLaiaConfig;
    private static final Map<String, String> map = new HashMap<>();
    private final UnicodeToAsciiTranslitirator unicodeToAsciiTranslitirator;
    private String namespace;

    public MinionPyLaiaMergePageXML(Path file, HTRConfig pyLaiaConfig, String namespace) {
        this.file = file;
        this.pyLaiaConfig = pyLaiaConfig;
        this.unicodeToAsciiTranslitirator = new UnicodeToAsciiTranslitirator();
        this.namespace = namespace;
    }

    private void runFile(Path file) throws IOException, TransformerException {
        if (file.toString().endsWith(".xml")) {
            LOG.info(file + " processing...");
            String pageXml = StringTools.readFile(file.toAbsolutePath().toString());
            PcGts page = PageUtils.readPageFromString(pageXml);

            for (TextRegion textRegion : page.getPage().getTextRegions()) {
                for (TextLine textLine : textRegion.getTextLines()) {
                    String targetFileName = file.getFileName().toString();
                    String text = map.get(targetFileName + "-" + textLine.getId());
                    if (text == null) {
                        continue;
                    }
                    TextEquiv textEquiv = new TextEquiv(null, unicodeToAsciiTranslitirator.toAscii(text),text);
                    textLine.setTextEquiv(textEquiv);
                }
            }

            MetadataItem metadataItem = createProcessingStep(this.pyLaiaConfig);
            if (page.getMetadata().getMetadataItems() == null) {
                page.getMetadata().setMetadataItems(new ArrayList<>());
            }
            
            page.getMetadata().getMetadataItems().add(metadataItem);
            PageUtils.writePageToFile(page, this.namespace, file.toAbsolutePath());
        }
    }

    private MetadataItem createProcessingStep(HTRConfig htrConfig) {
        MetadataItem metadataItem = new MetadataItem();
        metadataItem.setType("processingStep");
        metadataItem.setName("htr");
        metadataItem.setValue("pylaia");
        Labels labels = new Labels();
        ArrayList<Label> labelsList = new ArrayList<>();
        final Label githashLabel = new Label();
        githashLabel.setType("githash");
        githashLabel.setValue(htrConfig.getGithash());
        labelsList.add(githashLabel);
        final Label modelLabel = new Label();
        modelLabel.setType("model");
        modelLabel.setValue(htrConfig.getModel());
        labelsList.add(modelLabel);
        final Label uuidLabel = new Label();
        uuidLabel.setType("uuid");
        uuidLabel.setValue("" + htrConfig.getUuid());
        labelsList.add(uuidLabel);
        if (htrConfig.getValues() != null) {
            for (String key : htrConfig.getValues().keySet()) {
                Label label = new Label();
                label.setType(key);
                Object value = htrConfig.getValues().get(key);
                label.setValue(String.valueOf(value));
                labelsList.add(label);
            }
        }
        labels.setLabel(labelsList);
        metadataItem.setLabels(labels);
        return metadataItem;
    }

    public static Options getOptions() {
        final Options options = new Options();

        options.addOption(Option.builder("input_path").hasArg(true).required(true)
                .desc("Page to be updated with the htr results").build()
        );

        options.addOption(Option.builder("results_file").hasArg(true).required(true)
                .desc("File with the htr results").build()
        );

        options.addOption(Option.builder("config_file").hasArg(true)
                .desc("The file that contains the configuration of the network").build()
        );
        options.addOption("config_file", true, "File with the htr config.");
        final Option whiteListOption = Option.builder("config_white_list").hasArgs()
                .desc("a list with properties that should be added to the PageXML")
                .build();
        options.addOption(whiteListOption);

        options.addOption("help", false, "prints this help dialog");
        options.addOption("use_2013_namespace", "set PageXML namespace to 2013, to avoid causing problems with Transkribus");

//        options.addOption("overwrite_existing_page", true, "true / false, default true");

        return options;
    }

    public static void main(String[] args) throws Exception {
        int numthreads = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(numthreads);
        Path inputPath = Paths.get("/media/rutger/DIFOR1/data/1.05.14/83/page");
        String resultsFile = "/tmp/output/results.txt";
        boolean overwriteExistingPage = true;
        final Options options = getOptions();
        final CommandLineParser parser = new DefaultParser();
        final CommandLine commandLine;

        try {
            commandLine = parser.parse(options, args);
        } catch (ParseException ex) {
            printHelp(options, "java "+ MinionPyLaiaMergePageXML.class.getName());
            return;
        }

        if (commandLine.hasOption("help")) {
            printHelp(options, "java "+ MinionPyLaiaMergePageXML.class.getName());
            return;
        }
        String namespace = commandLine.hasOption("use_2013_namespace") ? PageUtils.NAMESPACE2013: PageUtils.NAMESPACE2019;

        inputPath = Paths.get(commandLine.getOptionValue("input_path"));
        resultsFile = commandLine.getOptionValue("results_file");

        final List<String> configWhiteList;
        if (commandLine.hasOption("config_white_list")) {
            configWhiteList = Arrays.asList(commandLine.getOptionValues("config_white_list"));
        } else {
            configWhiteList = Lists.newArrayList("batch_size");
        }

        String configFile = "";
        if (commandLine.hasOption("config_file")) {
            configFile = commandLine.getOptionValue("config_file");
        }

        final HTRConfig pyLaiaConfig = readConfigFile(configFile, configWhiteList);

        readDictionary(resultsFile);

        DirectoryStream<Path> fileStream = Files.newDirectoryStream(inputPath);
        List<Path> files = new ArrayList<>();
        fileStream.forEach(files::add);
        files.sort(Comparator.comparing(Path::toString));

        for (Path file : files) {
            Runnable worker = new MinionPyLaiaMergePageXML(file, pyLaiaConfig, namespace);
            executor.execute(worker);
        }


        executor.shutdown();

        LOG.info("Finished all threads");
    }

    private static void readDictionary(String resultsFile) throws IOException {

        try (BufferedReader br = new BufferedReader(new FileReader(resultsFile, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] splitted = line.split(" ");
                String filename = splitted[0];
                String text = line.substring(filename.length() + 1);
                splitted = filename.split("/");
                filename = splitted[splitted.length - 1].replace(".jpg", "");
                text = text.replace(" ", "").replace("<space>", " ").trim();
                map.put(filename.trim(), text);
                LOG.debug(filename + " appended to dictionary");
            }
        }

    }

    public static HTRConfig readConfigFile(String configFile, List<String> configWhiteList) throws IOException, org.json.simple.parser.ParseException {
        final HTRConfig pyLaiaConfig = new HTRConfig();

        if (Strings.isNullOrEmpty(configFile) || !Files.exists(Paths.get(configFile))) {
            return pyLaiaConfig;
        }
        JSONObject jsonObject = (JSONObject) new JSONParser().parse(new FileReader(configFile));

        String gitHash = jsonObject.get("git_hash").toString();
        String model = jsonObject.get("model").toString();

        pyLaiaConfig.setModel(model);
        pyLaiaConfig.setGithash(gitHash);
        if (jsonObject.containsKey("uuid")) {
            pyLaiaConfig.setUuid(UUID.fromString(jsonObject.get("uuid").toString()));
        }

        Map<String, Object> values = new HashMap<>();

        Object argsObject = jsonObject.containsKey("args") ? jsonObject.get("args") : null;
        if (argsObject instanceof JSONObject) {
            JSONObject args = (JSONObject) argsObject;
            for (Object key : args.keySet()) {
                LOG.debug(String.valueOf(key));
                LOG.debug(String.valueOf(args.get(key)));
                if (args.get(key) != null && configWhiteList.contains(key)) {
                    values.put((String) key, String.valueOf(args.get(key)));
                }
            }
        }
        pyLaiaConfig.setValues(values);

        return pyLaiaConfig;
    }


    @Override
    public void run() {
        try {
            this.runFile(this.file);
        } catch (IOException | TransformerException e) {
            LOG.error("Unexpected error", e);
        }
    }
}
