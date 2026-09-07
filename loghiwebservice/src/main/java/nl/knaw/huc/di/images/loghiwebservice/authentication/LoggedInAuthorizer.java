package nl.knaw.huc.di.images.loghiwebservice.authentication;

import io.dropwizard.auth.Authorizer;
import org.checkerframework.checker.nullness.qual.Nullable;

import jakarta.ws.rs.container.ContainerRequestContext;

public class LoggedInAuthorizer implements Authorizer<User> {
    public LoggedInAuthorizer() {

    }

    @Override
    public boolean authorize(User principal, String s, @Nullable ContainerRequestContext containerRequestContext) {
        return principal != null;
    }
}
