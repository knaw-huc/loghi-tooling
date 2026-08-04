package nl.knaw.huc.di.images.minions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import jakarta.ws.rs.core.UriBuilder;
import nl.knaw.huc.di.images.layoutds.models.DocumentImage;
import nl.knaw.huc.di.images.layoutds.models.OCRJob;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;


public class BaseMinion {

    private static final Logger LOG = LoggerFactory.getLogger(BaseMinion.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // SMELL-07: bound the retry loops so a dead server cannot hang/thrash a minion forever.
    private static final int MAX_RETRIES = 10;
    private static final long INITIAL_BACKOFF_MS = 500L;
    private static final long MAX_BACKOFF_MS = 30_000L;


    private static String serverUri = "http://localhost:9006/";
    // SEC-01: no secret in source. Key comes from CLI arg #2 or the LOGHI_API_KEY env var.
    private static String apiKey = System.getenv("LOGHI_API_KEY");
    public static int maxRandom = 1000;

    public static String getServerUri() {
        return serverUri;
    }


    public static String processArgs(String[] args) {
        if (args.length > 0) {
            setServerUri(args[0]);
        }
        if (args.length >= 2) {
            setApiKey(args[1]);
        }
        return null;
    }



    private static void setServerUri(String serverUri) {
        BaseMinion.serverUri = serverUri;
    }

    public static DocumentImage getDocumentImage(String serverPath) throws IOException, InterruptedException {
        final String urlString = serverUri + serverPath;
        final URL url = new URL(urlString);
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            URLConnection conn = url.openConnection();
            StringBuilder stringBuilder = new StringBuilder();
            try (BufferedReader bufferedReader =
                         new BufferedReader(new InputStreamReader(conn.getInputStream(), Charsets.UTF_8))) {
                String inputLine;
                while ((inputLine = bufferedReader.readLine()) != null) {
                    stringBuilder.append(inputLine);
                }
                return MAPPER.readValue(stringBuilder.toString(), DocumentImage.class);
            } catch (IOException ex) {
                lastError = ex;
                long backoff = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS * (1L << (attempt - 1)));
                LOG.warn("getDocumentImage from {} failed (attempt {}/{}), retrying in {} ms",
                        urlString, attempt, MAX_RETRIES, backoff, ex);
                Thread.sleep(backoff);
            }
        }
        throw new IOException("getDocumentImage failed after " + MAX_RETRIES + " attempts: " + urlString, lastError);
    }

    public static OCRJob getOCRJob(String serverPath, String apiKey, OCRJob.OcrSystem ocrSystem)
            throws InterruptedException, IOException {
        final String urlString = serverUri + serverPath + "?ocr=" + ocrSystem;
        IOException lastError = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try (final CloseableHttpClient client = HttpClientBuilder.create().build()) {
                final String sessionId = logIn(client, serverUri, apiKey);
                final HttpGet httpGet = new HttpGet(urlString);
                httpGet.addHeader("Authorization", sessionId);
                try (final CloseableHttpResponse response = client.execute(httpGet)) {
                    if (response.getStatusLine().getStatusCode() == 200) {
                        return MAPPER.readValue(response.getEntity().getContent(), OCRJob.class);
                    }
                    LOG.error("Could not retrieve OCRJob: {}", response.getStatusLine().getReasonPhrase());
                    return null; // non-200 is an application-level "no job", not a transport failure
                }
            } catch (IOException ex) {
                lastError = ex;
                long backoff = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS * (1L << (attempt - 1)));
                LOG.warn("getOCRJob from {} failed (attempt {}/{}), retrying in {} ms",
                        urlString, attempt, MAX_RETRIES, backoff, ex);
                Thread.sleep(backoff);
            }
        }
        throw new IOException("getOCRJob failed after " + MAX_RETRIES + " attempts: " + urlString, lastError);
    }

    public static String logIn(CloseableHttpClient client, String serverUri, String apiKey) throws IOException {
        final URI uri = UriBuilder.fromUri(serverUri).path("api/apikeylogin").build();
        final HttpPost httpPost = new HttpPost(uri);
        httpPost.addHeader("Authorization", apiKey);
        try (final CloseableHttpResponse execute = client.execute(httpPost)) {
            if (execute.getStatusLine().getStatusCode() == 204) {
                final Header x_auth_token = execute.getHeaders("X_AUTH_TOKEN")[0];
                return x_auth_token.getValue();
            } else {
                throw new IllegalStateException("Could not login: " + execute.getStatusLine().getReasonPhrase());
            }
        }
    }

    public static void setApiKey(String apiKey) {
        BaseMinion.apiKey = apiKey;
    }

    public static String getApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "No API key configured. Pass it as the second CLI argument or set the "
                + "LOGHI_API_KEY environment variable.");
        }
        return apiKey;
    }

    public static void printHelp(Options options, String callName) {
        final HelpFormatter helpFormatter = new HelpFormatter();

        helpFormatter.printHelp(callName, options, true);
    }
}
