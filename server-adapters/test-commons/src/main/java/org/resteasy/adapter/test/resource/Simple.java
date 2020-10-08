package org.resteasy.adapter.test.resource;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

@Path("/simple")
public class Simple {

    @GET
    @Produces("text/plain")
    public String hello() {
        return "Hello World";
    }

    @POST
    @Produces("text/plain")
    public String echo(final String requestBody) {
        return requestBody;
    }

    @GET
    @Path("/no-body")
    public Response get() {
        return Response.status(204).build();
        }
}