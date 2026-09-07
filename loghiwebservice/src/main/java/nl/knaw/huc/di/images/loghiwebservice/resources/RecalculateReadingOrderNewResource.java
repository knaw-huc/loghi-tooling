package nl.knaw.huc.di.images.loghiwebservice.resources;

import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.minions.MinionRecalculateReadingOrderNew;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import org.apache.commons.io.FilenameUtils;
import nl.knaw.huc.di.images.pipelineutils.ErrorFileWriter;
import org.apache.commons.io.IOUtils;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Path("recalculate-reading-order-new")
public class RecalculateReadingOrderNewResource extends LoghiWebserviceResource {

    public static final Logger LOG = LoggerFactory.getLogger(RecalculateReadingOrderNewResource.class);
    private final ExecutorService executorService;
    private final String uploadLocation;
    private final Supplier<String> queueUsageStatusSupplier;
    private final StringBuilder errorLog;
    private final ErrorFileWriter errorFileWriter;

    public RecalculateReadingOrderNewResource(ExecutorService executorService, String uploadLocation,
                                              Supplier<String> queueUsageStatusSupplier, int ledgerSize) {
        super(ledgerSize);

        this.executorService = executorService;
        this.uploadLocation = uploadLocation;
        this.queueUsageStatusSupplier = queueUsageStatusSupplier;
        errorLog = new StringBuilder();
        errorFileWriter = new ErrorFileWriter(uploadLocation);
        // TODO implement errorFileWriter usage
    }

    @PermitAll
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response schedule(FormDataMultiPart form) {
        if (errorLog.length() > 0) {
            return Response.serverError().entity("Minion is failing: " + errorLog).build();
        }

        final Set<String> fields = form.getFields().keySet();

        if(!fields.contains("page")) {
            return missingFieldResponse("page");
        }

        if (!fields.contains("border_margin")) {
           return missingFieldResponse("border_margin");
        }

        if (!fields.contains("identifier")) {
            return missingFieldResponse("identifier");
        }

        final String borderMarginString = form.getField("border_margin").getValue();
        if (!borderMarginString.matches("\\d+")) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"message\":\"" + borderMarginString +" is not a valid value for \\\"border_margin\\\"\"}").build();
        }
        final int borderMargin = Integer.parseInt(borderMarginString);
        final String identifier = form.getField("identifier").getValue();
        String namespace = fields.contains("namespace")? form.getField("namespace").getValue() : PageUtils.NAMESPACE2019;
        if (!PageUtils.NAMESPACE2013.equals(namespace) && ! PageUtils.NAMESPACE2019.equals(namespace)) {
            final String namespaceException = "Unsupported page xml namespace use " + PageUtils.NAMESPACE2013 + " or " + PageUtils.NAMESPACE2019;
            return Response.status(400).entity("{\"message\":\"" + namespaceException + "\"}").build();
        }

        FormDataBodyPart xmlUpload = form.getField("page");
        final String pageFile = xmlUpload.getFormDataContentDisposition().getFileName();
        InputStream xmlInputStream = xmlUpload.getValueAs(InputStream.class);
        final String xmlString;
        try {
            xmlString = IOUtils.toString(xmlInputStream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Response.serverError().entity("{\"message\":\"Could not read page xml\"}").build();
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
                errorFileWriter.write(identifier, e, "Could not save page");
            } catch (TransformerException e) {
                LOG.error("Could not transform page to 2013 version", e);
                errorLog.append("Could not transform page to 2013 version: ")
                        .append(e.getMessage());
                errorFileWriter.write(identifier, e, "Could not transform page to 2013 version");
            }
        };

        final PcGts page = PageUtils.readPageFromString(xmlString);

        java.nio.file.Path errorlocation = Paths.get(uploadLocation, identifier, FilenameUtils.removeExtension(pageFile) +".error");
        final double interlineClusteringMultiplier =  fields.contains("interline_clustering_multiplier") ? form.getField("interline_clustering_multiplier").getValueAs(Double.class) :  1.5;
        final double dubiousSizeWidthMultiplier = fields.contains("dubious_size_width_multiplier") ? form.getField("dubious_size_width_multiplier").getValueAs(Double.class): 0.05;
        final Double dubiousSizeWidth = fields.contains("dubious_size_width") ? form.getField("dubious_size_width").getValueAs(Double.class): null;
        final MinionRecalculateReadingOrderNew job = new MinionRecalculateReadingOrderNew(identifier, page, pageSaver,
                false, borderMargin,false, interlineClusteringMultiplier,
                dubiousSizeWidthMultiplier, dubiousSizeWidth, null, Optional.of(errorFileWriter));
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

    private Response missingFieldResponse(String field) {
        return Response.status(Response.Status.BAD_REQUEST).entity("{\"message\":\"missing field \\\"" + field + "\\\"\"}").build();
    }
}
