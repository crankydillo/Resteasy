package org.resteasy.adapter.test.resource;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Path("/timeout")
public class Timeout {

    @GET
    public void timeout(
        final InputStream in,
        @Suspended final AsyncResponse resp
    ) {
        // TODO this timeout stuff is all still to do..
        resp.setTimeout(2, TimeUnit.NANOSECONDS);
        //resp.resume(Response.ok(mkStream(in)));
    }
}