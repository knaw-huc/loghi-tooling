package nl.knaw.huc.di.images.loghiwebservice.resources;

import com.codahale.metrics.annotation.Timed;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.minions.MinionSplitPageXMLTextLineIntoWords;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.pipelineutils.ErrorFileWriter;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Path("split-page-xml-text-line-into-words")
public class SplitPageXMLTextLineIntoWordsResource extends LoghiWebserviceResource {

    public static final Logger LOG = LoggerFactory.getLogger(SplitPageXMLTextLineIntoWordsResource.class);
    private final String serverUploadLocationFolder;
    private final ErrorFileWriter errorFileWriter;
    private ExecutorService executorService;
    private final Supplier<String> queueUsageStatusSupplier;
    private final StringBuffer minionErrorLog;


    public SplitPageXMLTextLineIntoWordsResource(ExecutorService executorService, String serverUploadLocationFolder,
                                                 Supplier<String> queueUsageStatusSupplier, int ledgerSize) {
        super(ledgerSize);

        this.serverUploadLocationFolder = serverUploadLocationFolder;
        this.executorService = executorService;
        this.queueUsageStatusSupplier = queueUsageStatusSupplier;
        this.minionErrorLog = new StringBuffer();
        AtomicLong counter = new AtomicLong();
        errorFileWriter = new ErrorFileWriter(serverUploadLocationFolder);
    }

    @PermitAll
    @POST
    @Timed
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadFile(FormDataMultiPart multiPart) {
        if (minionErrorLog.length() > 0) {
            return Response.serverError().entity("Minion is failing: " + minionErrorLog).build();
        }

        final Map<String, List<FormDataBodyPart>> fields = multiPart.getFields();

        if (!fields.containsKey("xml")) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"message\":\"missing field \\\"xml\\\"\"}").build();
        }

        if (!fields.containsKey("identifier")) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"message\":\"missing field \\\"identifier\\\"\"}").build();
        }

        String namespace = PageUtils.NAMESPACE2019;
        if (fields.containsKey("namespace")) {
            namespace = multiPart.getField("namespace").getValue();
            if (!PageUtils.NAMESPACE2013.equals(namespace) && ! PageUtils.NAMESPACE2019.equals(namespace)) {
                final String namespaceException = "Unsupported page xml namespace use " + PageUtils.NAMESPACE2013 + " or " + PageUtils.NAMESPACE2019;
                return Response.status(400).entity("{\"message\":\"" + namespaceException + "\"}").build();
            }
        }

        FormDataBodyPart xmlUpload = multiPart.getField("xml");
        InputStream xmlInputStream = xmlUpload.getValueAs(InputStream.class);
        FormDataContentDisposition xmlContentDispositionHeader = xmlUpload.getFormDataContentDisposition();
        String xmlFile = xmlContentDispositionHeader.getFileName();

        final String identifier = multiPart.getField("identifier").getValue();
        final String xml_string;
        try {
            xml_string = IOUtils.toString(xmlInputStream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            errorFileWriter.write(identifier, e, "Could not read page xml");
            return Response.serverError().entity("{\"message\":\"Could not read page xml\"}").build();
        }

        Supplier<PcGts> pageSupplier = () -> PageUtils.readPageFromString(xml_string);

        final String outputFile = Paths.get(serverUploadLocationFolder, identifier, xmlFile).toAbsolutePath().toString();

        Runnable job = new MinionSplitPageXMLTextLineIntoWords(identifier, pageSupplier, outputFile,
                error -> minionErrorLog.append(error).append("\n"), namespace, Optional.of(errorFileWriter));

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

        return Response.ok("{\"queueStatus\": "+ queueUsageStatusSupplier.get() + "}").build();
    }
}
