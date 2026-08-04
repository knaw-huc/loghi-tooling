package nl.knaw.huc.di.images.loghiwebservice.resources;

import com.codahale.metrics.annotation.Timed;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutds.models.LaypaConfig;
import nl.knaw.huc.di.images.layoutds.models.P2PaLAConfig;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.minions.MinionCutFromImageBasedOnPageXMLNew;
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

import javax.annotation.security.PermitAll;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static nl.knaw.huc.di.images.loghiwebservice.resources.FormMultipartHelper.getFieldOrDefaultValue;

@Path("/extract-and-cut")
@Produces(MediaType.APPLICATION_JSON)
public class ExtractAndCutResource extends LoghiWebserviceResource {

    private static final Logger LOG = LoggerFactory.getLogger(ExtractAndCutResource.class);

    private final ExecutorService executorService;
    private final String uploadLocation;
    private final String p2palaConfigFile;
    private final String laypaConfigFile;
    private final Supplier<String> queueUsageStatusSupplier;
    private final StringBuffer minionErrorLog;
    private final ErrorFileWriter errorFileWriter;

    public ExtractAndCutResource(ExecutorService executorService, String uploadLocation,
                                 String p2palaConfigFile, String laypaConfigFile,
                                 Supplier<String> queueUsageStatusSupplier, int ledgerSize) {
        super(ledgerSize);
        this.executorService = executorService;
        this.uploadLocation = uploadLocation;
        this.p2palaConfigFile = p2palaConfigFile;
        this.laypaConfigFile = laypaConfigFile;
        this.queueUsageStatusSupplier = queueUsageStatusSupplier;
        this.minionErrorLog = new StringBuffer();
        this.errorFileWriter = new ErrorFileWriter(uploadLocation);
    }

    @PermitAll
    @POST
    @Timed
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response schedule(FormDataMultiPart multiPart) {
        if (minionErrorLog.length() > 0) {
            return Response.serverError().entity("Minion is failing: " + minionErrorLog).build();
        }

        final Map<String, List<FormDataBodyPart>> fields = multiPart.getFields();
        if (!fields.containsKey("image"))      return missingField("image");
        if (!fields.containsKey("mask"))       return missingField("mask");
        if (!fields.containsKey("xml"))        return missingField("xml");
        if (!fields.containsKey("identifier")) return missingField("identifier");
        if (!fields.containsKey("output_type")) return missingField("output_type");
        if (!fields.containsKey("channels"))   return missingField("channels");

        final String identifier = multiPart.getField("identifier").getValue();

        // --- image ---
        FormDataBodyPart imageUpload = multiPart.getField("image");
        FormDataContentDisposition imageDisposition = imageUpload.getFormDataContentDisposition();
        String imageFile = imageDisposition.getFileName();
        final byte[] imageArray;
        try {
            imageArray = IOUtils.toByteArray(imageUpload.getValueAs(InputStream.class));
        } catch (IOException e) {
            errorFileWriter.write(identifier, e, "Could not read image");
            return Response.serverError().entity("{\"message\":\"Could not read image\"}").build();
        }
        Supplier<Mat> imageSupplier = () -> Imgcodecs.imdecode(new MatOfByte(imageArray), Imgcodecs.IMREAD_COLOR);

        // --- mask ---
        FormDataBodyPart maskUpload = multiPart.getField("mask");
        String maskFile = maskUpload.getFormDataContentDisposition().getFileName();
        final byte[] maskArray;
        try {
            maskArray = IOUtils.toByteArray(maskUpload.getValueAs(InputStream.class));
        } catch (IOException e) {
            errorFileWriter.write(identifier, e, "Could not read mask");
            return Response.serverError().entity("{\"message\":\"Could not read mask\"}").build();
        }
        Supplier<Mat> maskSupplier = () -> Imgcodecs.imdecode(new MatOfByte(maskArray), Imgcodecs.IMREAD_GRAYSCALE);

        // --- xml ---
        FormDataBodyPart xmlUpload = multiPart.getField("xml");
        String xmlFile = xmlUpload.getFormDataContentDisposition().getFileName();
        final String xmlString;
        try {
            xmlString = IOUtils.toString(xmlUpload.getValueAs(InputStream.class), StandardCharsets.UTF_8);
        } catch (IOException e) {
            errorFileWriter.write(identifier, e, "Could not read page xml");
            return Response.serverError().entity("{\"message\":\"Could not read page xml\"}").build();
        }

        // --- extract-baselines params ---
        String namespace = PageUtils.NAMESPACE2019;
        if (fields.containsKey("namespace")) {
            namespace = multiPart.getField("namespace").getValue();
            if (!PageUtils.NAMESPACE2013.equals(namespace) && !PageUtils.NAMESPACE2019.equals(namespace)) {
                return Response.status(400).entity("{\"message\":\"Unsupported namespace\"}").build();
            }
        }
        final int margin = fields.containsKey("margin")
                ? multiPart.getField("margin").getValueAs(Integer.class) : 50;
        final boolean invertImage = fields.containsKey("invertImage")
                && "true".equals(multiPart.getField("invertImage").getValue());
        final boolean addLaypaMetadata = fields.containsKey("addLaypaMetadata")
                && "true".equals(multiPart.getField("addLaypaMetadata").getValue());
        final boolean splitBaselines = fields.containsKey("splitBaselines")
                && "true".equals(multiPart.getField("splitBaselines").getValue());
        final List<String> whiteList = fields.containsKey("config_white_list")
                ? fields.get("config_white_list").stream().map(FormDataBodyPart::getValue).collect(Collectors.toList())
                : new ArrayList<>();

        P2PaLAConfig p2palaConfig = null;
        LaypaConfig laypaConfig = null;
        if (invertImage) {
            try {
                p2palaConfig = fields.containsKey("p2pala_config")
                        ? MinionExtractBaselines.readP2PaLAConfigFile(multiPart.getField("p2pala_config").getValueAs(InputStream.class), whiteList)
                        : MinionExtractBaselines.readP2PaLAConfigFile(p2palaConfigFile, whiteList);
            } catch (IOException | ParseException e) {
                LOG.error("Could not read p2palaConfig", e);
            }
        } else if (addLaypaMetadata) {
            try {
                laypaConfig = fields.containsKey("laypa_config")
                        ? MinionExtractBaselines.readLaypaConfigFile(multiPart.getField("laypa_config").getValueAs(InputStream.class), whiteList)
                        : MinionExtractBaselines.readLaypaConfigFile(laypaConfigFile, whiteList);
            } catch (IOException | ParseException e) {
                LOG.error("Could not read laypaConfig", e);
            }
        }

        // --- cut-from-image params ---
        final String outputType = multiPart.getField("output_type").getValue();
        final int channels = multiPart.getField("channels").getValueAs(Integer.class);
        final int minWidth = getFieldOrDefaultValue(Integer.class, multiPart, fields, "min_width", 5);
        final int minHeight = getFieldOrDefaultValue(Integer.class, multiPart, fields, "min_height", 5);
        final int minWidthToHeight = getFieldOrDefaultValue(Integer.class, multiPart, fields, "min_width_to_height_ratio", 0);
        final boolean writeTextContents = getFieldOrDefaultValue(Boolean.class, multiPart, fields, "write_text_contents", false);
        final Integer rescaleHeight = getFieldOrDefaultValue(Integer.class, multiPart, fields, "rescale_height", null);
        final boolean outputConfFile = getFieldOrDefaultValue(Boolean.class, multiPart, fields, "output_conf_file", false);
        final boolean outputBoxFile = getFieldOrDefaultValue(Boolean.class, multiPart, fields, "output_box_file", true);
        final boolean outputTxtFile = getFieldOrDefaultValue(Boolean.class, multiPart, fields, "output_txt_file", true);
        final Integer fixedXHeight = getFieldOrDefaultValue(Integer.class, multiPart, fields, "fixed_x_height", null);
        final int minimumXHeight = getFieldOrDefaultValue(Integer.class, multiPart, fields, "min_x_height", LayoutProc.MINIMUM_XHEIGHT);
        final boolean includeTextStyles = getFieldOrDefaultValue(Boolean.class, multiPart, fields, "include_text_styles", false);
        final boolean useTags = getFieldOrDefaultValue(Boolean.class, multiPart, fields, "use_tags", false);
        final int minimumBaselineThickness = getFieldOrDefaultValue(Integer.class, multiPart, fields, "minimum_baseline_thickness", 1);

        // --- output paths ---
        final String outputXmlFile = Paths.get(uploadLocation, identifier, xmlFile).toAbsolutePath().toString();
        final String outputBase = Paths.get(uploadLocation, identifier).toAbsolutePath().toString();
        final String finalNamespace = namespace;
        final P2PaLAConfig finalP2palaConfig = p2palaConfig;
        final LaypaConfig finalLaypaConfig = laypaConfig;

        // --- fused job ---
        // The capturing supplier lets MinionExtractBaselines modify the page in-place.
        // After its run() completes, pageHolder contains the fully updated page
        // (baselines extracted + contours recalculated), which we pass directly to the
        // cut minion — no disk round-trip, no duplicate seam DP.
        final AtomicReference<PcGts> pageHolder = new AtomicReference<>();
        final Supplier<PcGts> capturingPageSupplier = () -> {
            PcGts page = PageUtils.readPageFromString(xmlString);
            pageHolder.set(page);
            return page;
        };

        Runnable job = () -> {
            new MinionExtractBaselines(identifier, capturingPageSupplier, imageSupplier,
                    outputXmlFile, true, finalP2palaConfig, finalLaypaConfig, maskSupplier,
                    margin, invertImage,
                    error -> minionErrorLog.append(error).append("\n"),
                    32, new ArrayList<>(), finalNamespace, true,
                    Optional.of(errorFileWriter), splitBaselines, 1.2, 1.5,
                    minimumBaselineThickness)
                    .run();

            PcGts updatedPage = pageHolder.get();
            if (updatedPage == null) {
                LOG.error("{}: extract-baselines did not produce a page, skipping cut step", identifier);
                return;
            }

            new MinionCutFromImageBasedOnPageXMLNew(
                    identifier, imageSupplier, () -> updatedPage,
                    outputBase, imageFile, false,
                    minWidth, minHeight, minWidthToHeight, outputType, channels,
                    writeTextContents, rescaleHeight, outputConfFile, outputBoxFile, outputTxtFile,
                    false, // recalculate=false: contours are already fresh from extract step
                    fixedXHeight, minimumXHeight,
                    false, false, false,
                    error -> minionErrorLog.append(error).append("\n"),
                    includeTextStyles, useTags, false, null, null,
                    MinionCutFromImageBasedOnPageXMLNew.DEFAULT_MINIMUM_INTERLINE_DISTANCE,
                    MinionCutFromImageBasedOnPageXMLNew.DEFAULT_PNG_COMPRESSION_LEVEL,
                    Optional.empty(), null, minimumBaselineThickness, 0)
                    .run();
        };

        try {
            if (identifier != null && statusLedger.containsKey(identifier)) {
                LOG.warn("Identifier {} already in use", identifier);
            }
            Future<?> future = executorService.submit(job);
            if (statusLedger.size() >= ledgerSize) {
                try {
                    while (statusLedger.size() >= ledgerSize) {
                        statusLedger.remove(statusLedger.firstKey());
                    }
                } catch (Exception e) {
                    LOG.error("Could not trim status ledger", e);
                }
            }
            statusLedger.put(identifier, future);
        } catch (RejectedExecutionException e) {
            return Response.status(Response.Status.TOO_MANY_REQUESTS)
                    .entity("{\"message\":\"Queue is full\"}").build();
        }

        String output = "{\"filesUploaded\": [\"" + maskFile + "\", \"" + xmlFile + "\", \"" + imageFile + "\"]," +
                "\"queueStatus\": " + queueUsageStatusSupplier.get() + "}";
        return Response.ok(output).build();
    }

    private Response missingField(String field) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"message\":\"missing field \\\"" + field + "\\\"\"}").build();
    }
}
