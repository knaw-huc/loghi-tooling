package nl.knaw.huc.di.images.loghiwebservice.resources;

import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.minions.MinionDetectLanguageOfPageXml;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.pipelineutils.ErrorFileWriter;
import nl.knaw.huygens.pergamon.nlp.langident.Model;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import javax.xml.transform.TransformerException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Path("detect-language-of-page-xml")
public class DetectLanguageOfPageXmlResource extends LoghiWebserviceResource {
    public static final Logger LOG = LoggerFactory.getLogger(DetectLanguageOfPageXmlResource.class);
    private final String uploadLocation;
    private final ExecutorService executorService;
    private final Supplier<String> queueUsageStatusSupplier;
    private final ErrorFileWriter errorFileWriter;
    private final StringBuilder errorLog;

    public DetectLanguageOfPageXmlResource(String uploadLocation, ExecutorService executorService,
                                           Supplier<String> queueUsageStatusSupplier,int ledgerSize) {
        super(ledgerSize);
        this.uploadLocation = uploadLocation;
        this.executorService = executorService;
        this.queueUsageStatusSupplier = queueUsageStatusSupplier;
        this.errorLog = new StringBuilder();
        errorFileWriter = new ErrorFileWriter(uploadLocation);
    }

    @PermitAll
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response schedule(FormDataMultiPart form) {
        final Map<String, List<FormDataBodyPart>> fields = form.getFields();

        if (!fields.containsKey("identifier")) {
            return missingFieldResponse("identifier");
        }

        if (!fields.containsKey("page")) {
            return missingFieldResponse("page");
        }

        final Model model;
        if (fields.containsKey("training_data")) {
            Map<String, String> trainingData = new HashMap<>();
            fields.get("training_data").forEach(trainingFile -> {
                final String fileName = trainingFile.getFormDataContentDisposition().getFileName();
                final String language = FilenameUtils.removeExtension(fileName);
                String data;
                try {
                    data = new String(trainingFile.getValueAs(InputStream.class).readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    LOG.error("Could not process training data for: {}", fileName, e);
                    data = "";
                }
                trainingData.put(language, data);
            });
            if (trainingData.isEmpty()) {
                // Make sure no wrong data is used to train a language detection model
                return Response.status(Response.Status.BAD_REQUEST).entity("{\"message\":\"Could not use 'train_data' to train a language detection model\"}").build();
            }

            model = MinionDetectLanguageOfPageXml.trainModel(trainingData);
        } else {
            try {
                model = MinionDetectLanguageOfPageXml.trainModelWithDefaultData();
            } catch (IOException | URISyntaxException e) {
                LOG.error("Could not train default model.", e);
                return Response.serverError().entity("{\"message\":\"Could not train default language detection model\"}").build();
            }
        }

        FormDataBodyPart xmlUpload = form.getField("page");
        InputStream xmlInputStream = xmlUpload.getValueAs(InputStream.class);
        FormDataContentDisposition xmlContentDispositionHeader = xmlUpload.getFormDataContentDisposition();
        String pageFile = xmlContentDispositionHeader.getFileName();

        final String identifier = form.getField("identifier").getValue();
        final String xml_string;
        try {
            xml_string = IOUtils.toString(xmlInputStream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            errorFileWriter.write(identifier, e, "Could not read page");
            return Response.serverError().entity("{\"message\":\"Could not read page xml\"}").build();
        }

        Supplier<PcGts> pageSupplier = () -> PageUtils.readPageFromString(xml_string);

        String namespace = fields.containsKey("namespace")? form.getField("namespace").getValue() : PageUtils.NAMESPACE2019;

        if (!PageUtils.NAMESPACE2013.equals(namespace) && ! PageUtils.NAMESPACE2019.equals(namespace)) {
            final String namespaceException = "Unsupported page xml namespace use " + PageUtils.NAMESPACE2013 + " or " + PageUtils.NAMESPACE2019;
            return Response.status(400).entity("{\"message\":\"" + namespaceException + "\"}").build();
        }

        final Consumer<PcGts> pageSaver = page -> {
            final java.nio.file.Path targetFile = Paths.get(uploadLocation, identifier, pageFile);
            try {
                if (!Files.exists(targetFile.getParent())) {
                    Files.createDirectories(targetFile.getParent());
                }
                PageUtils.writePageToFileAtomic(page, namespace, targetFile);
            } catch (IOException e) {
                LOG.error("Could not save page: {}", targetFile, e);
                errorLog.append("Could not save page: ").append(targetFile).append("\n");
                errorFileWriter.write(identifier, e, "Could not write page");
            } catch (TransformerException e) {
                LOG.error("Could not transform page to 2013 version", e);
                errorLog.append("Could not transform page to 2013 version: ").append(e.getMessage());
                errorFileWriter.write(identifier, e, "Could not transform page to 2013 version");
            }
        };

        final MinionDetectLanguageOfPageXml job = new MinionDetectLanguageOfPageXml(identifier, pageSupplier, pageSaver, model);
        try {
            Future<?> future = executorService.submit(job);
            if (statusLedger.size() >= ledgerSize) {
                try {
                    while (statusLedger.size() >= ledgerSize) {
                        statusLedger.remove(statusLedger.firstKey());
                    }
                }catch (Exception e){
                    LOG.error("Could not remove first key from lookupStatusQueue", e);
                }
            }
            statusLedger.put(identifier, future);

        } catch (RejectedExecutionException e) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS).entity("{\"message\":\"Queue is full\"}").build();
        }

        return Response.ok("{\"queueStatus\": "+ queueUsageStatusSupplier.get() + "}").build();
    }

    private Response missingFieldResponse(String field) {
        return Response.status(Response.Status.BAD_REQUEST).entity("{\"message\":\"missing field \\\"" + field + "\\\"\"}").build();
    }
}
