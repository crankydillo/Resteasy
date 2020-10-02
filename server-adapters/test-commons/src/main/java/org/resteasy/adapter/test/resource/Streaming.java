package org.resteasy.adapter.test.resource;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@Path("/stream")
public class Streaming {

    @POST
    public Response echo(InputStream requestBody) {
        return Response.ok(mkStream(requestBody)).build();
    }

    @POST
    @Path("/2")
    public InputStream echo2(InputStream requestBody) {
        return requestBody;
    }


    private StreamingOutput mkStream(final InputStream in) {
        return new StreamingOutput() {
            @Override
            public void write(final OutputStream os) throws IOException, WebApplicationException {
                try (final OutputStream writer = new BufferedOutputStream(os)) {
                    final byte[] buf = new byte[5 * 1024];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        writer.write(buf, 0, len);
                    }
                    writer.flush();
                    in.close();
                }
            }
        };
    }
}
