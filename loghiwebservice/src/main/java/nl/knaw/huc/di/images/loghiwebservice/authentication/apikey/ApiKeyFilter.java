package nl.knaw.huc.di.images.loghiwebservice.authentication.apikey;

import io.dropwizard.auth.AuthFilter;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import java.io.IOException;
import java.security.Principal;
import java.util.UUID;
import java.util.regex.Pattern;

public class ApiKeyFilter<P extends Principal> extends AuthFilter<UUID, P> {

    public static final Logger LOG = LoggerFactory.getLogger(ApiKeyFilter.class);
    private static final Pattern UUID_REGEX =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Override
    public void filter(ContainerRequestContext containerRequestContext) throws IOException {
        String token = containerRequestContext.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (StringUtils.isBlank(token) || !UUID_REGEX.matcher(token).matches()) {
            throw new WebApplicationException(unauthorizedHandler.buildResponse(prefix, realm));
        }

        final UUID credentials = UUID.fromString(token);

        if (!this.authenticate(containerRequestContext, credentials, "ApiKey")) {
            throw this.unauthorizedHandler.buildException(this.prefix, this.realm);
        }
    }

    public static class Builder<P extends Principal> extends AuthFilterBuilder<UUID, P, ApiKeyFilter<P>> {
        @Override
        protected ApiKeyFilter<P> newInstance() {
            return new ApiKeyFilter<>();
        }
    }
}
