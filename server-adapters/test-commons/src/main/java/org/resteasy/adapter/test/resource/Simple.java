package org.resteasy.adapter.test.resource;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;

@Path("/simple")
public class Simple {

    @GET
    public String hello() {
        return "Hello World";
    }

    @POST
    public String echo(final String requestBody) {
        return requestBody;
    }

    @Path("/no-body")
    public static class NoBody {
        @GET
        public Response get() {
            return Response.status(204).build();
        }
    }
}