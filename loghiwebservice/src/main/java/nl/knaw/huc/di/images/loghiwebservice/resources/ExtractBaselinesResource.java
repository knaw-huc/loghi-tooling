package nl.knaw.huc.di.images.loghiwebservice.resources;

import com.codahale.metrics.annotation.Timed;
import nl.knaw.huc.di.images.layoutds.models.LaypaConfig;
import nl.knaw.huc.di.images.layoutds.models.P2PaLAConfig;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.minions.MinionExtractBaselines;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.pipelineutils.ErrorFileWriter;
import org.apache.commons.io.IOUtils;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.json.simple.parser.ParseException;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Path("/extract-baselines")
@Produces(MediaType.APPLICATION_JSON)
public class ExtractBaselinesResource extends LoghiWebserviceResource {
    private static final Logger LOG = LoggerFactory.getLogger(ExtractBaselinesResource.class);
    private final String serverUploadLocationFolder;
    private final StringBuffer minionErrorLog;
    private final String p2palaConfigFile;
    private final String laypaConfigFile;
    private final Supplier<String> queueUsageStatusSupplier;
    private final ErrorFileWriter errorFileWriter;

    private final int margin = 50;
    private final ExecutorService executorService;

    public ExtractBaselinesResource(ExecutorService executorService, String serverUploadLocationFolder,
                                    String p2palaConfigFile, String laypaConfigFile,
                                    Supplier<String> queueUsageStatusSupplier, int ledgerSize) {
        super(ledgerSize);
        this.p2palaConfigFile = p2palaConfigFile;
        this.laypaConfigFile = laypaConfigFile;
        this.queueUsageStatusSupplier = queueUsageStatusSupplier;
        this.serverUploadLocationFolder = serverUploadLocationFolder;
        this.executorService = executorService;
        this.minionErrorLog = new StringBuffer();
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
        if (!fields.containsKey("mask")) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"message\":\"missing field \\\"mask\\\"\"}").build();
        }

        if (!fields.containsKey("xml")) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"message\":\"missing field \\\"xml\\\"\"}").build();
        }

        if (!fields.containsKey("identifier")) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"message\":\"missing field \\\"identifier\\\"\"}").build();
        }

        if (!fields.containsKey("image")) {
            return Response.status(Response.Status.BAD_REQUEST).entity("{\"message\":\"missing field \\\"image\\\"\"}").build();
        }

        int margin = this.margin;
        if (fields.containsKey("margin")) {
            margin = multiPart.getField("margin").getValueAs(Integer.class);
        }

        String namespace = PageUtils.NAMESPACE2019;
        if (fields.containsKey("namespace")) {
            namespace = multiPart.getField("namespace").getValue();

            if (!PageUtils.NAMESPACE2013.equals(namespace) && !PageUtils.NAMESPACE2019.equals(namespace)) {
                final String namespaceException = "Unsupported page xml namespace use " + PageUtils.NAMESPACE2013 + " or " + PageUtils.NAMESPACE2019;
                return Response.status(400).entity("{\"message\":\"" + namespaceException + "\"}").build();
            }
        }
        boolean recalculateTextLineContoursFromBaselines = true;

        List<String> reorderRegionsList = new ArrayList<>();

        FormDataBodyPart imageUpload = multiPart.getField("image");
        FormDataBodyPart maskUpload = multiPart.getField("mask");
        InputStream imageInputStream = imageUpload.getValueAs(InputStream.class);
        InputStream maskInputStream = maskUpload.getValueAs(InputStream.class);
        FormDataContentDisposition maskContentDispositionHeader = maskUpload.getFormDataContentDisposition();
        String maskFile = maskContentDispositionHeader.getFileName();

        final byte[] imageArray;
        try {
            imageArray = IOUtils.toByteArray(imageInputStream);
        } catch (IOException e) {
            return Response.serverError().entity("{\"message\":\"Could not read image\"}").build();
        }

        final byte[] maskArray;
        try {
            maskArray = IOUtils.toByteArray(maskInputStream);
        } catch (IOException e) {
            return Response.serverError().entity("{\"message\":\"Could not read mask\"}").build();
        }
        Supplier<Mat> imageSupplier = () -> Imgcodecs.imdecode(new MatOfByte(imageArray), Imgcodecs.IMREAD_COLOR);

        Supplier<Mat> baseLineImageSupplier = () -> Imgcodecs.imdecode(new MatOfByte(maskArray), Imgcodecs.IMREAD_GRAYSCALE);


        FormDataBodyPart xmlUpload = multiPart.getField("xml");
        InputStream xmlInputStream = xmlUpload.getValueAs(InputStream.class);
        FormDataContentDisposition xmlContentDispositionHeader = xmlUpload.getFormDataContentDisposition();
        String xmlFile = xmlContentDispositionHeader.getFileName();

        final String xml_string;
        final String identifier = multiPart.getField("identifier").getValue();
        try {
            xml_string = IOUtils.toString(xmlInputStream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            errorFileWriter.write(identifier, e, "Could not read page xml");
            return Response.serverError().entity("{\"message\":\"Could not read page xml\"}").build();
        }

        Supplier<PcGts> pageSupplier = () -> PageUtils.readPageFromString(xml_string);

        int threshold = 32;
        final String outputFile = Paths.get(serverUploadLocationFolder, identifier, xmlFile).toAbsolutePath().toString();
        final boolean invertImage = fields.containsKey("invertImage") && multiPart.getField("invertImage").getValue().equals("true");
        final boolean addLaypaMetadata = fields.containsKey("addLaypaMetadata") && multiPart.getField("addLaypaMetadata").getValue().equals("true");
        final boolean splitBaselines = fields.containsKey("splitBaselines") && multiPart.getField("splitBaselines").getValue().equals("true");
        final int minimumBaselineThickness = 1;
        LaypaConfig laypaConfig = null;

        final List<String> whiteList;
        if (fields.containsKey("config_white_list")) {
            whiteList = fields.get("config_white_list").stream().map(FormDataBodyPart::getValue).collect(Collectors.toList());
        } else {
            whiteList = new ArrayList<>();
        }


        P2PaLAConfig p2palaconfig = null;
        if (invertImage) {
            try {
                if (fields.containsKey("p2pala_config")) {
                    final InputStream p2palaConfigInputStream = multiPart.getField("p2pala_config").getValueAs(InputStream.class);
                    p2palaconfig = MinionExtractBaselines.readP2PaLAConfigFile(p2palaConfigInputStream, whiteList);

                } else {
                    p2palaconfig = MinionExtractBaselines.readP2PaLAConfigFile(p2palaConfigFile, whiteList);
                }
            } catch (IOException | ParseException e) {
                LOG.error("Could not read p2palaConfig");
            }
        } else {
            if (addLaypaMetadata) {
                try {
                    if (fields.containsKey("laypa_config")) {
                        final InputStream laypaConfigInputStream = multiPart.getField("laypa_config").getValueAs(InputStream.class);
                        laypaConfig = MinionExtractBaselines.readLaypaConfigFile(laypaConfigInputStream, whiteList);
                    } else {
                        laypaConfig = MinionExtractBaselines.readLaypaConfigFile(laypaConfigFile, whiteList);
                    }
                } catch (IOException | ParseException e) {
                    LOG.error("Could not read laypaConfigFile");
                }
            }
        }
        Runnable job = new MinionExtractBaselines(identifier, pageSupplier, imageSupplier, outputFile,
                true, p2palaconfig, laypaConfig, baseLineImageSupplier, margin, invertImage,
                error -> minionErrorLog.append(error).append("\n"),
                threshold, reorderRegionsList, namespace, recalculateTextLineContoursFromBaselines,
                Optional.of(errorFileWriter), splitBaselines, 1.2, 1.5,
                minimumBaselineThickness);

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
        String output = "{\"filesUploaded\": [\"" + maskFile + "\", \"" + xmlFile + "\"]," +
                "\"queueStatus\": " + queueUsageStatusSupplier.get() + "," +
                "warnings: \"" + warnings + "\"}";
        return Response.ok(output).build();
    }
}
