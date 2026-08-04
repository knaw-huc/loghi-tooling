package nl.knaw.huc.di.images.loghiwebservice.resources;

import com.codahale.metrics.annotation.Timed;
import nl.knaw.huc.di.images.layoutanalyzer.layoutlib.LayoutProc;
import nl.knaw.huc.di.images.layoutds.models.Page.PcGts;
import nl.knaw.huc.di.images.minions.MinionCutFromImageBasedOnPageXMLNew;
import nl.knaw.huc.di.images.pagexmlutils.PageUtils;
import nl.knaw.huc.di.images.pipelineutils.ErrorFileWriter;
import org.apache.commons.io.IOUtils;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataMultiPart;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.PermitAll;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
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
import java.util.function.Supplier;

import static nl.knaw.huc.di.images.loghiwebservice.resources.FormMultipartHelper.getFieldOrDefaultValue;

@Path("cut-from-image-based-on-page-xml-new")
public class CutFromImageBasedOnPageXMLNewResource extends LoghiWebserviceResource {

    public static final Logger LOG = LoggerFactory.getLogger(CutFromImageBasedOnPageXMLNewResource.class);
    private final ExecutorService executorService;
    private final String uploadLocation;
    private final Supplier<String> queueUsageStatusSupplier;
    private final StringBuffer minionErrorLog;
    private final ErrorFileWriter errorFileWriter;

    public CutFromImageBasedOnPageXMLNewResource(ExecutorService executorService, String uploadLocation,
                                                 Supplier<String> queueUsageStatusSupplier, int ledgerSize) {
        super(ledgerSize);

        this.executorService = executorService;
        this.uploadLocation = uploadLocation;
        this.queueUsageStatusSupplier = queueUsageStatusSupplier;
        this.minionErrorLog = new StringBuffer();
        errorFileWriter = new ErrorFileWriter(uploadLocation);

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
        if (!fields.containsKey("image")) {
            return missingFieldResponse("image");
        }

        if (!fields.containsKey("page")) {
            return missingFieldResponse("page");
        }

        if (!fields.containsKey("identifier")) {
            return missingFieldResponse("identifier");
        }

        if (!fields.containsKey("output_type")) {
            return missingFieldResponse("output_type");
        }

        if (!fields.containsKey("channels")) {
            return missingFieldResponse("channels");
        }

        final String identifier = multiPart.getField("identifier").getValue();

        FormDataBodyPart maskUpload = multiPart.getField("image");
        InputStream maskInputStream = maskUpload.getValueAs(InputStream.class);
        FormDataContentDisposition maskContentDispositionHeader = maskUpload.getFormDataContentDisposition();
        String imageFile = maskContentDispositionHeader.getFileName();
        final byte[] array;
        try {
            array = IOUtils.toByteArray(maskInputStream);
        } catch (IOException e) {
            LOG.error("Could not read image for {}", maskUpload.getName(), e);
            errorFileWriter.write(identifier, e, "Could not read image.");
            return Response.serverError().entity("{\"message\":\"Could not read image\"}").build();
        }
        Supplier<Mat> imageSupplier = () -> Imgcodecs.imdecode(new MatOfByte(array), Imgcodecs.IMREAD_COLOR);

        FormDataBodyPart xmlUpload = multiPart.getField("page");
        InputStream xmlInputStream = xmlUpload.getValueAs(InputStream.class);
        FormDataContentDisposition xmlContentDispositionHeader = xmlUpload.getFormDataContentDisposition();
        String pageFile = xmlContentDispositionHeader.getFileName();

        final String xml_string;
        try {
            xml_string = IOUtils.toString(xmlInputStream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("Could not read page xml for image {}", maskUpload.getName(), e);
            errorFileWriter.write(identifier, e, "Could not read page xml for image.");
            return Response.serverError().entity("{\"message\":\"Could not read page xml\"}").build();
        }

        Supplier<PcGts> pageSupplier = () -> PageUtils.readPageFromString(xml_string);
        final String outputBase = Paths.get(uploadLocation, identifier).toAbsolutePath().toString();
        final String outputType = multiPart.getField("output_type").getValue();
        final int channels = multiPart.getField("channels").getValueAs(Integer.class);
        final boolean overwriteExistingPage = false;

        final int minWidth = getFieldOrDefaultValue(Integer.class, multiPart, fields, "min_width", 5);
        final int minHeight = getFieldOrDefaultValue(Integer.class, multiPart, fields, "min_height", 5);
        final int minWidthToHeight = getFieldOrDefaultValue(Integer.class, multiPart, fields, "min_width_to_height_ratio", 0);
        final boolean writeTextContents = getFieldOrDefaultValue(Boolean.class, multiPart, fields, "write_text_contents", false);
        final Integer rescaleHeight = getFieldOrDefaultValue(Integer.class, multiPart, fields, "rescale_height", null);
        final boolean outputConfFile = getFieldOrDefaultValue(Boolean.class, multiPart, fields, "output_conf_file", false);
        final boolean outputBoxFile = getFieldOrDefaultValue(Boolean.class, multiPart, fields, "output_box_file", true);
        final boolean outputTxtFile = getFieldOrDefaultValue(Boolean.class, multiPart, fields, "output_txt_file", true);
        final boolean recalculateTextLineContoursFromBaselines = getFieldOrDefaultValue(Boolean.class, multiPart, fields, "recalculate_text_line_contours_from_baselines", false);
        final Integer fixedXHeight = getFieldOrDefaultValue(Integer.class, multiPart, fields, "fixed_x_height", null);
        final int minimumXHeight = getFieldOrDefaultValue(Integer.class, multiPart, fields, "min_x_height", LayoutProc.MINIMUM_XHEIGHT);
        final boolean includeTextStyles = getFieldOrDefaultValue(Boolean.class, multiPart, fields, "include_text_styles", false);
        final int minimumInterlineDistance = 35;
        final boolean useTags = getFieldOrDefaultValue(Boolean.class, multiPart, fields, "use_tags", false);
        final int minimumBaselineThickness = getFieldOrDefaultValue(Integer.class, multiPart, fields, "minimum_baseline_thickness", 1);


        final MinionCutFromImageBasedOnPageXMLNew job = new MinionCutFromImageBasedOnPageXMLNew(
                identifier, imageSupplier, pageSupplier, outputBase, imageFile, overwriteExistingPage, minWidth,
                minHeight, minWidthToHeight, outputType, channels, writeTextContents, rescaleHeight, outputConfFile,
                outputBoxFile, outputTxtFile, recalculateTextLineContoursFromBaselines, fixedXHeight, minimumXHeight,
                false, false, false, error -> minionErrorLog.append(error).append("\n"), includeTextStyles, useTags,
                false, null, null, minimumInterlineDistance,
                MinionCutFromImageBasedOnPageXMLNew.DEFAULT_PNG_COMPRESSION_LEVEL,
                Optional.empty(),null, minimumBaselineThickness, 0);
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

        String output = "{\"filesUploaded\": [\"" + imageFile + "\", \"" + pageFile + "\"]," +
                "\"queueStatus\": "+ queueUsageStatusSupplier.get() + "}";
        return Response.ok(output).build();
    }

    private Response missingFieldResponse(String output_type) {
        return Response.status(Response.Status.BAD_REQUEST).entity("{\"message\":\"missing field \\\"" + output_type + "\\\"\"}").build();
    }

}
