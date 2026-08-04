package nl.knaw.huc.di.images.loghiwebservice.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import nl.knaw.huc.di.images.layoutds.models.HTRConfig;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.minions.MinionLoghiHTRMergePageXML;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.pipelineutils.ErrorFileWriter;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.transform.TransformerException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.ws.rs.Path;
import javax.ws.rs.POST;
import javax.ws.rs.Consumes;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import javax.annotation.security.PermitAll;

@Path("loghi-htr-merge-page-xml")
public class LoghiHTRMergePageXMLResource extends LoghiWebserviceResource {

    public static final Logger LOG = LoggerFactory.getLogger(LoghiHTRMergePageXMLResource.class);
    /**
     * Shared, thread-safe Jackson {@link ObjectMapper}.
     * Re-using a single instance avoids the expensive per-request reflective
     * initialisation that the constructor performs (see SMELL-06 / EXT-SEC-03).
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final String uploadLocation;
    private final ExecutorService executorService;
    private final Supplier<String> queueUsageStatusSupplier;
    private final StringBuilder errorLog;
    private final ErrorFileWriter errorFileWriter;

    public LoghiHTRMergePageXMLResource(String uploadLocation, ExecutorService executorService,
                                        Supplier<String> queueUsageStatusSupplier, int ledgerSize) {
        super(ledgerSize);

        this.uploadLocation = uploadLocation;
        this.executorService = executorService;
        this.queueUsageStatusSupplier = queueUsageStatusSupplier;
        errorLog = new StringBuilder();
        errorFileWriter = new ErrorFileWriter(uploadLocation);
    }

    @PermitAll
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response schedule(FormDataMultiPart multiPart) {
        if (errorLog.length() > 0) {
            LOG.error("Minion is failing: " + errorLog);
            return Response.serverError().entity("Minion is failing: " + errorLog).build();
        }

        final Set<String> fieldNames = multiPart.getFields().keySet();

        if (!fieldNames.contains("page"))
            return missingFieldResponse("page");

        if (!fieldNames.contains("identifier"))
            return missingFieldResponse("identifier");

        // Check for either "results" file or "resultsString" parameter
        if (!fieldNames.contains("results") && !fieldNames.contains("resultsString"))
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"message\":\"missing field \\\"results\\\" or \\\"resultsString\\\"\"}").build();

        FormDataBodyPart xmlUpload = multiPart.getField("page");
        FormDataContentDisposition xmlContentDispositionHeader = xmlUpload.getFormDataContentDisposition();
        String pageFile = FilenameUtils.removeExtension(xmlContentDispositionHeader.getFileName());
        String comment;

        InputStream xmlInputStream = xmlUpload.getValueAs(InputStream.class);
        final String xmlString;
        final String identifier = multiPart.getField("identifier").getValue();

        try {
            xmlString = IOUtils.toString(xmlInputStream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            errorFileWriter.write(identifier,e, "Could not read page xml");
            return Response.serverError().entity("{\"message\":\"Could not read page xml\"}").build();
        }

        Supplier<PcGts> pageSupplier = () -> PageUtils.readPageFromString(xmlString);

        // Define HashMaps that will be filled
        final HashMap<String, String> fileTextLineMap = new HashMap<>();
        final HashMap<String, Double> confidenceMap = new HashMap<>();
        final HashMap<String, String> fileToConfigIndexMap = new HashMap<>();

        // Define the list of HTRConfigs that we will put in the PageXML
        List<HTRConfig> listOfConfigs = new ArrayList<>();

        final String resultsString;

        try {
            // Check which input method is being used
            if (fieldNames.contains("results")) {
                // File-based approach
                FormDataBodyPart resultsUpload = multiPart.getField("results");
                InputStream resultsInputStream = resultsUpload.getValueAs(InputStream.class);
                resultsString = IOUtils.toString(resultsInputStream, StandardCharsets.UTF_8);
            } else {
                // String-based approach
                resultsString = multiPart.getField("resultsString").getValue();
            }

            // Fill the maps and the listOfConfigs
            processResultsFile(resultsString, fileTextLineMap, confidenceMap, listOfConfigs, fileToConfigIndexMap);

            LOG.info("lines dictionary contains: " + fileTextLineMap.size());
        } catch (IOException e) {
            LOG.error("Could not read results", e);
            errorFileWriter.write(identifier,e, "Could not read results");
            return Response.serverError().entity("{\"message\":\"Could not read results\"}").build();
        }

        String namespace = fieldNames.contains("namespace")? multiPart.getField("namespace").getValue() : PageUtils.NAMESPACE2019;
        if (!PageUtils.NAMESPACE2013.equals(namespace) && ! PageUtils.NAMESPACE2019.equals(namespace)) {
            final String namespaceException = "Unsupported page xml namespace use " + PageUtils.NAMESPACE2013 + " or " + PageUtils.NAMESPACE2019;
            errorFileWriter.write(identifier, "Unsupported page xml namespace.");
            return Response.status(400).entity("{\"message\":\"" + namespaceException + "\"}").build();
        }

        final Consumer<PcGts> pageSaver = page -> {
            final java.nio.file.Path targetFile = Paths.get(uploadLocation, identifier, pageFile + ".xml");
            try {
                if (!Files.exists(targetFile.getParent())) {
                    Files.createDirectories(targetFile.getParent());
                }
                PageUtils.writePageToFileAtomic(page, namespace, targetFile);
            } catch (IOException e) {
                LOG.error("Could not save page: {}", targetFile, e);
                errorLog.append("Could not save page: ").append(targetFile).append("\n");
                errorFileWriter.write(identifier, e, "Could not save page");
            } catch (TransformerException e) {
                LOG.error("Could not transform page to 2013 version", e);
                errorLog.append("Could not transform page to 2013 version: ")
                        .append(e.getMessage());
                errorFileWriter.write(identifier, e, "Could not transform page to 2013 version");
            }
        };

        comment = FormMultipartHelper.getFieldOrDefaultValue(String.class, multiPart, multiPart.getFields(),
                "comment", "");

        Runnable job = new MinionLoghiHTRMergePageXML(identifier, pageSupplier, listOfConfigs, fileTextLineMap, fileToConfigIndexMap,
                confidenceMap, pageSaver, pageFile, comment, "", Optional.of(errorFileWriter));

        String warnings = "";
        try {
            if (identifier != null && statusLedger.containsKey(identifier)) {
                warnings = "Identifier already in use";
            }
            Future<?> future = executorService.submit(job);
            if (statusLedger.size() >= ledgerSize) {
                try {
                    while (statusLedger.size() >= ledgerSize) {
                        statusLedger.remove(statusLedger.firstKey());
                    }
                } catch (Exception e) {
                    LOG.error("Could not remove first key from lookupStatusQueue", e);
                }
            }
            statusLedger.put(identifier, future);

        } catch (RejectedExecutionException e) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS).entity("{\"message\":\"Queue is full\"}").build();
        }

        return Response.ok("{\"queueStatus\": " + queueUsageStatusSupplier.get() + "}").build();
    }

    private void processResultsFile(String resultsFile,
                                    Map<String, String> fileTextLineMap,
                                    Map<String, Double> confidenceMap,
                                    List<HTRConfig> listOfConfigs,
                                    Map<String, String> fileToConfigIndexMap) throws IOException {
        // Local set to hold unique metadata values
        Set<String> metadataValues = new HashSet<>();

        // Temporary map to hold metadata and their corresponding text lines for later processing
        Map<String, String> tempMetadataMap = new HashMap<>();

        try (BufferedReader br = new BufferedReader(new StringReader(resultsFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                ResultLine resultLine = getResultLine(line);
                if (resultLine == null) continue;

                fileTextLineMap.put(resultLine.filename, resultLine.text.toString().trim());
                confidenceMap.put(resultLine.filename, resultLine.confidence);
                tempMetadataMap.put(resultLine.filename, resultLine.metadata);
                metadataValues.add(resultLine.metadata); // Populate the set of unique metadata values

                LOG.debug(resultLine.filename + " appended to dictionary");
            }
        }

        // Re-use the shared, thread-safe ObjectMapper instance for efficiency
        ObjectMapper mapper = MAPPER;
        Map<String, Integer> metadataIndexMap = new HashMap<>();
        int index = 0;

        // Process metadataValues to populate listOfConfigs and metadataIndexMap
        for (String jsonString : metadataValues) {
            try {
                HTRConfig c = new HTRConfig();

                // Log the raw input for debugging
                LOG.debug("Raw JSON string: " + jsonString);

                // Check if the string has proper JSON format (starts and ends with curly braces)
                if (!jsonString.trim().startsWith("{") || !jsonString.trim().endsWith("}")) {
                    // Try to fix malformed JSON by replacing single quotes with double quotes
                    // Only do this if the JSON appears to be using single quotes
                    if (jsonString.contains("'")) {
                        jsonString = jsonString.replace("'", "\"");
                    }
                    LOG.debug("Modified JSON string: " + jsonString);
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> values = mapper.readValue(jsonString, Map.class);
                c.setValues(values);
                listOfConfigs.add(c);
                metadataIndexMap.put(jsonString, index++);
            } catch (Exception e) {
                LOG.error("Error parsing JSON metadata: " + jsonString, e);
                LOG.error("Exception details:", e);
            }
        }

        // Map filenames to the index of their HTRConfig in listOfConfigs
        for (Map.Entry<String, String> entry : tempMetadataMap.entrySet()) {
            String filename = entry.getKey();
            String metadata = entry.getValue()
                    .replace("\"", "\\\"")
                    .replace("'", "\"");
            Integer configIndex = metadataIndexMap.get(metadata);
            if (configIndex != null) {
                fileToConfigIndexMap.put(filename, "htr-" + configIndex);
            }
        }
    }


    public static ResultLine getResultLine(String line) {
        int tabCount = countTabs(line);

        String[] splitted = line.split("\t");

        if (tabCount < 3) {
            // tabCount should be 3
            LOG.warn("result line htr seems too short: " + line);
            return null;
        }

        String filename = splitted[0].split("/")[splitted[0].split("/").length - 1].replace(".png", "").trim();
        double confidence = 1.0;
        String metadata = "[]"; //set base value for metadata
        StringBuilder text = new StringBuilder();

        if (tabCount == 3) {
            // Format: filename\tmetadata\tconfidence\tpred_text (with pred_text potentially empty)
            metadata = splitted[1];
            try {
                confidence = Double.parseDouble(splitted[2]);
            } catch (Exception ex) {
                LOG.error("Unexpected error", ex);
                LOG.error(filename + ex.getMessage());
            }
            if (splitted.length > 3) { // Check if pred_text is not empty
                text.append(splitted[3]);
            }
        } else {
            throw new IllegalArgumentException("Input line does not match expected formats.");
        }

        return new ResultLine(filename, confidence, metadata, text);
    }

    private static int countTabs(String str) {
        int tabCount = 0;

        // Iterate over each character in the string
        for (int i = 0; i < str.length(); i++) {
            // Check if the current character is a tab
            if (str.charAt(i) == '\t') {
                tabCount++;
            }
        }

        return tabCount;
    }

    public static class ResultLine {
        private final String filename;
        private final double confidence;
        private final StringBuilder text;

        //Metadata init as null for default
        private String metadata = null;

        // Constructor without metadata
        public ResultLine(String filename, double confidence, StringBuilder text) {
            this.filename = filename;
            this.confidence = confidence;
            this.text = text;
        }

        // Constructor with metadata
        public ResultLine(String filename, double confidence, String metadata, StringBuilder text) {
            this(filename, confidence, text); // Calls the other constructor
            this.metadata = metadata; // Sets metadata
        }


        public String getFilename() {
            return filename;
        }

        public double getConfidence() {
            return confidence;
        }

        public StringBuilder getText() {
            return text;
        }

        public String getMetadata(){
            // Return "[]" if metadata is null, otherwise return metadata
            return metadata == null ? "[]" : metadata;
        }
    }

    private Response missingFieldResponse(String field) {
        return Response.status(Response.Status.BAD_REQUEST).entity("{\"message\":\"missing field \\\"" + field + "\\\"\"}").build();
    }
}