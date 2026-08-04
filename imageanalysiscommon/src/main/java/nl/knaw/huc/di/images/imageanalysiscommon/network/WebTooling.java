package nl.knaw.huc.di.images.imageanalysiscommon.network;

import com.google.common.base.Strings;
import org.apache.commons.io.FilenameUtils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.imgcodecs.Imgcodecs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

public class WebTooling {
    private static final Logger LOG = LoggerFactory.getLogger(WebTooling.class);

    public static void readRemoteImageToStream(String remoteUri, OutputStream outputStream) throws IOException {
        if (!remoteUri.endsWith(".jpg") &&
                !remoteUri.endsWith(".jpeg") &&
                !remoteUri.endsWith(".png") &&
                !remoteUri.endsWith(".tif")
        ) {
            remoteUri += "/full/full/0/default.jpg";
            LOG.debug("getting remote resource: {}", remoteUri);
        }

        URL url = new URL(remoteUri);
        try (InputStream is = url.openStream()) {
            byte[] byteChunk = new byte[4096]; // Or whatever size you want to read in at a time.
            int n;

            while ((n = is.read(byteChunk)) > 0) {
                outputStream.write(byteChunk, 0, n);
            }
        }
    }

    public static ImageFile readRemoteImageAsStream(String uri) throws Exception {
        URL url = new URL(uri);
        for (int i = 0; i < 3; i++) {
            LOG.debug("opening connection");
            HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
            httpURLConnection.setConnectTimeout(60*1000); //set timeout to 60 seconds
            httpURLConnection.setReadTimeout(60*1000);

            int responseCode = httpURLConnection.getResponseCode();

            if (responseCode == HttpURLConnection.HTTP_OK) {
                String fileName = "";
                String disposition = httpURLConnection.getHeaderField("Content-Disposition");
                String extension;
                if (disposition != null) {
// extracts file name from header fieldreadRemoteImageAsStream
                    int index = disposition.indexOf("filename=");
                    if (index > 0) {
                        fileName = disposition.substring(index + 10,
                                disposition.length() - 1);
                    }
                } else {
// extracts file name from URL
                    fileName = uri.substring(uri.lastIndexOf("/") + 1);
                }
                extension = FilenameUtils.getExtension(fileName);
                if (Strings.isNullOrEmpty(extension) && uri.endsWith("/jpg")) {
                    extension = "jpg";
                }

                LOG.debug("opening url stream");
//                FileUtils.copyURL, File);
//
                try (InputStream inputStream = httpURLConnection.getInputStream()) {
                    byte[] byteChunk = new byte[4096]; // Or whatever size you want to read in at a time.
                    int n;
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

                    LOG.debug("reading chunks");
                    while ((n = inputStream.read(byteChunk)) > 0) {
                        byteArrayOutputStream.write(byteChunk, 0, n);
                    }
                    LOG.debug("done reading chunks");
                    ImageFile imageFile = new ImageFile(byteArrayOutputStream.toByteArray(), fileName, extension);
                    LOG.debug("closing bytearraystream");
                    byteArrayOutputStream.close();
                    LOG.debug("disconnecting");
                    httpURLConnection.disconnect();
                    LOG.debug("returning imagefile");
                    return imageFile;
                } catch (IOException e) {
                    LOG.error("Failed while reading bytes from {}", url.toExternalForm(), e);
                    // Perform any other exception handling that's appropriate.
                }
            } else {
                LOG.error(uri);
                LOG.error("responsecode not HTTP_OK");
            }
            // Something went wrong: just sleep for i seconds
            LOG.info("sleeping for " + i + " seconds");
            Thread.sleep(i * 1000);
        }
        throw new Exception("readRemoteImageAsStream too many errors");

    }

    /**
     * Loads an image from a local path or remote URI.
     * <p>
     * <strong>Ownership:</strong> The returned {@link Mat} is owned by the
     * caller. The caller <em>must</em> call {@link Mat#release()} (or
     * preferably {@code OpenCVWrapper.release(...)} from the {@code
     * layoutanalyzer} module) once the {@code Mat} is no longer needed.
     * Failure to do so leaks native memory that the JVM garbage collector
     * cannot reclaim.
     * <p>
     * The returned Mat may be {@link Mat#empty()} when OpenCV could not decode
     * the file (e.g. corrupt or unsupported format). Callers should check.
     *
     * @see <a href="https://docs.opencv.org/4.x/d4/da8/group__imgcodecs.html">OpenCV imread documentation</a>
     */
    public static Mat readImage(String uri, UUID uuid, boolean isIIIF) throws Exception {
        LOG.debug("readImage: {}", uri);
        if (uri.startsWith("http")) {
            return readRemoteImage(uri, uuid, false, isIIIF);
        }
        return Imgcodecs.imread(uri);
    }

    /**
     * Loads an image from a remote URI, optionally caching it on disk under
     * {@code /scratch/preloaded/}.
     * <p>
     * <strong>Ownership:</strong> The returned {@link Mat} is owned by the
     * caller and must be released with {@link Mat#release()}.
     */
    public static Mat readRemoteImage(String uri, UUID uuid, boolean localOnly, boolean isIIIF) throws Exception {
        LOG.debug("readRemoteImage: {}", uri);
        int errorCount = 0;
        String baseDir = "/scratch/preloaded/";
        String targetFileWithoutExtension = null;
        if (uuid != null) {
            baseDir += uuid.toString().substring(0, 2) + "/";
            if (!Files.exists(Paths.get(baseDir))) {
                new File(baseDir).mkdir();
            }
            baseDir += uuid.toString().substring(2, 4) + "/";
            if (!Files.exists(Paths.get(baseDir))) {
                new File(baseDir).mkdir();
            }

            targetFileWithoutExtension = baseDir + uuid.toString();
            File jpgFile = new File(targetFileWithoutExtension + ".jpg");
            if (jpgFile.exists()) {
                LOG.debug("reading preloaded image: {}.jpg", targetFileWithoutExtension);
                return Imgcodecs.imread(jpgFile.getAbsolutePath());
            }

            File pngFile = new File(targetFileWithoutExtension + ".png");
            if (pngFile.exists()) {
                LOG.debug("reading preloaded image: {}.png", targetFileWithoutExtension);
                return Imgcodecs.imread(pngFile.getAbsolutePath());
            }
        }
        if (!localOnly) {
            // guess the iiif uri of the image
            if (uri != null && !uri.endsWith(".jpg") &&
                    !uri.endsWith(".jpeg") &&
                    !uri.endsWith(".png") &&
                    !uri.endsWith(".tif") &&
                    !uri.endsWith("/jpg") &&
                    isIIIF
            ) {
                uri += "/full/full/0/default.jpg";
            }

            try {
                ImageFile imageFile = readRemoteImageAsStream(uri);
                errorCount++;
                MatOfByte matOfByte = new MatOfByte(imageFile.getBytes());
                Mat image = Imgcodecs.imdecode(matOfByte, 1);
                matOfByte.release();
                if (image.width() == 0 || image.height() == 0) {
                    LOG.error("image {} has zero height and/or width", uri);
                    image.release();
                    throw new Exception(String.format("image %s has zero height and/or width", uri));
                }
                if (new File(baseDir).exists() && targetFileWithoutExtension != null) {
                    String extension = imageFile.getExtension();
                    if (Strings.isNullOrEmpty(extension)) {
                        extension = "png";
                    }
                    Files.write(Paths.get(targetFileWithoutExtension + "." + extension), imageFile.getBytes());
                }
                return image;
            } catch (Exception ex) {
                LOG.error("Failed to read remote image {}", uri, ex);
            }
        }
        throw new Exception("Too many errors");
    }

}
