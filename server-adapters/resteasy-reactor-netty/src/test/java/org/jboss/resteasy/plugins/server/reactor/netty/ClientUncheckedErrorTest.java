package org.jboss.resteasy.plugins.server.reactor.netty;

import static org.jboss.resteasy.test.TestPortProvider.generateURL;
import static org.junit.Assert.assertEquals;
import java.util.Random;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import org.jboss.resteasy.spi.Registry;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ClientUncheckedErrorTest {

    private static Client client;
    private static ResteasyDeployment deployment;

    @BeforeClass
    public static void setup() throws Exception {
        deployment = ReactorNettyContainer.start();
        final Registry registry = deployment.getRegistry();
        registry.addPerRequestResource(MyResource.class);
        client = ClientBuilder.newClient();
    }

    @AfterClass
    public static void end() {
        client.close();
        ReactorNettyContainer.stop();
    }

    @Test
    public void testClientUncheckedException() {
        final WebTarget target = client.target(generateURL("/resource/out-of-bounds/" + new Random().nextInt()));
        final Response resp = target.request().get();
        assertEquals(500, resp.getStatus());
        assertEquals("", resp.readEntity(String.class));
    }

    @Test
    public void testClientUncheckedMappedException() {
        deployment.getProviderFactory().registerProviderInstance(new MyExceptionMapper());
        final WebTarget target = client.target(generateURL("/resource/out-of-bounds/" + new Random().nextInt()));
        final Response resp = target.request().get();
        assertEquals(202, resp.getStatus());
        assertEquals("Try again later", resp.readEntity(String.class));

    }

    @Path("/resource")
    public static class MyResource {
        @GET
        @Path("/out-of-bounds/{index}")
        @Produces("text/plain")
        public Response outOfBounds(@PathParam("index") Integer index) {

            int[] arr = {};
            return Response.ok("Value at index " + index + " is " + arr[0]).build();

        }
    }

    @Provider
    public class MyExceptionMapper implements ExceptionMapper<ArrayIndexOutOfBoundsException> {

        @Override
        public Response toResponse(ArrayIndexOutOfBoundsException ex) {
          return Response.status(202)
              .entity("Try again later")
              .build();
        }
    }

}
