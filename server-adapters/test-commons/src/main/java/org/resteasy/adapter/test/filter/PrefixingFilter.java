package org.resteasy.adapter.test.filter;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import java.io.IOException;

/**
 * Adds a string to the start of response body.
 */
public class PrefixingFilter implements ContainerResponseFilter {
    @Override
    public void filter(
        final ContainerRequestContext containerRequestContext,
        final ContainerResponseContext containerResponseContext
    ) throws IOException {
        containerResponseContext.getEntityStream().write("Out filter - ".getBytes());
    }
}
