package nl.knaw.huc.di.images.loghiwebservice.resources;

import nl.knaw.huc.di.images.loghiwebservice.authentication.SessionManager;
import nl.knaw.huc.di.images.loghiwebservice.authentication.User;
import nl.knaw.huc.di.images.loghiwebservice.authentication.apikey.JsonApiKeyUserNameManager;

import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import java.util.UUID;

@Path("apikey")
public class ApiKeyResource {
    private final SessionManager sessionManager;
    private final JsonApiKeyUserNameManager jsonStringApiKeyManager;

    public ApiKeyResource(SessionManager sessionManager, JsonApiKeyUserNameManager jsonStringApiKeyManager) {
        this.sessionManager = sessionManager;
        this.jsonStringApiKeyManager = jsonStringApiKeyManager;
    }

    @POST
    @Path("login")
    public Response login(@HeaderParam(HttpHeaders.AUTHORIZATION) UUID authorization) {
        if (authorization == null) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        final Optional<User> userByApiKey = jsonStringApiKeyManager.getUserByApiKey(authorization);

        if (userByApiKey.isEmpty()) {
            return Response.status(Response.Status.UNAUTHORIZED).build();
        }

        UUID sessionId = UUID.randomUUID();

        sessionManager.register(sessionId, userByApiKey.get());

        return Response.noContent().header("X_AUTH_TOKEN", sessionId).build();
    }
}
