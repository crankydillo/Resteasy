package org.jboss.resteasy.plugins.server.reactor.netty;

import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.plugins.server.embedded.EmbeddedJaxrsServer;
import org.jboss.resteasy.plugins.server.embedded.SecurityDomain;
import org.jboss.resteasy.reactor.MonoProvider;
import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.jboss.resteasy.spi.Registry;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.jboss.resteasy.util.EmbeddedServerHelper;
import org.jboss.resteasy.util.PortProvider;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import javax.net.ssl.SSLContext;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.container.Suspended;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.ext.Provider;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class ReactorNettyJaxrsServer implements EmbeddedJaxrsServer<ReactorNettyJaxrsServer> {

   final Logger log = LoggerFactory.getLogger(ReactorNettyJaxrsServer.class);

   @Path("/nobody")
   public static class NoBody {
      @GET
      public Response get() {
         return Response.status(205).build();
      }
   }

   @Path("/foo")
   public static class Foo {
      @POST
      public Mono<String> echo(String requestBody) {
         return Mono.just(requestBody);
      }

      @POST
      @Path("/stream")
      public Response echo(InputStream requestBody) {
         return Response.ok(mkStream(requestBody)).build();
      }

//      @POST
//      @Path("/bytes")
//      public Mono<byte[]> echo(byte[] requestBody) {
//         return Mono.just(requestBody);
//      }

      @GET
      @Produces(MediaType.TEXT_PLAIN)
      public Mono<String> hello() {
         return Mono.just("Hello from mono!!");
      }

      @GET
      @Path("block")
      public Response blockingHello() {
         return Response.ok("Hello!").header("Foo", "BAR").build();
      }

      @GET
      @Path("/timeout")
      public void timeout(
          final InputStream in,
          @Suspended final AsyncResponse resp
      ) {
         // TODO this timeout stuff is all still to do..
         resp.setTimeout(2, TimeUnit.NANOSECONDS);
         //resp.resume(Response.ok(mkStream(in)));
      }

      @GET
      @Path("/timeout/mono")
      public Mono monoTimeout() {
         return Mono.just(1)
             .delayElement(Duration.ofSeconds(5))
             .timeout(Duration.ofNanos(2));
      }

      @GET
      @Produces("text/plain")
      @Path("/err")
      public String err() {
         if (1 == 1) {
            throw new RuntimeException("oh nos!");
         }
         return "shouldn't get here!";
      }

      private StreamingOutput mkStream(final InputStream in) {
         return new StreamingOutput() {
            @Override
            public void write(OutputStream os) throws IOException, WebApplicationException {
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

   @Provider
   public static class OutFilter implements ContainerResponseFilter {
      @Override
      public void filter(ContainerRequestContext containerRequestContext, ContainerResponseContext containerResponseContext) throws IOException {
         containerResponseContext.getEntityStream().write("Out filter - ".getBytes());
      }
   }

   public static void main(String[] args) {
      BlockHound.install();
      final ReactorNettyJaxrsServer server = new ReactorNettyJaxrsServer();
      final ResteasyDeployment deployment = new ResteasyDeploymentImpl();
      deployment.start();
      deployment.getDispatcher().getProviderFactory().register(MonoProvider.class);
      final Registry reg = deployment.getRegistry();
      reg.addSingletonResource(new Foo());
      reg.addSingletonResource(new NoBody());
      server.setDeployment(deployment);
      server.setPort(8081);
      server.start();
   }

   protected String hostname = null;
   protected int configuredPort = PortProvider.getPort();
   protected int runtimePort = -1;
   protected ResteasyDeployment deployment;
   protected String root = "";
   protected SecurityDomain domain;
   private SSLContext sslContext;
   private EmbeddedServerHelper serverHelper = new EmbeddedServerHelper();

   private DisposableServer server;

   @Override
   public ReactorNettyJaxrsServer deploy() {
      return this;
   }

   @SuppressWarnings("unchecked")
   @Override
   public ReactorNettyJaxrsServer start() {
      log.info("Starting server!");
      serverHelper.checkDeployment(deployment);

      final HttpServer svrBuilder = HttpServer.create().port(configuredPort);
      if (hostname != null && !hostname.trim().isEmpty()) {
         svrBuilder.host(hostname);
      }
      final Handler handler = new Handler();
      server = svrBuilder
          .handle(handler::handle)
          .bindNow();
      runtimePort = server.port();

      server.onDispose().block();
      return this;
   }

   class Handler {

      private final Mono<InputStream> empty = Mono.just(new InputStream() {
         @Override
         public int read() {
            return -1;  // end of stream
         }
      });

      Publisher<Void> handle(final HttpServerRequest req, final HttpServerResponse resp) {

         if (1 == 2) {
            return resp.send(req.receive().retain());
         }

         final ResteasyUriInfo info = new ResteasyUriInfo(req.uri(), "/");

         // aggregate (and maybe? asInputStream) reads the entire request body into memory (direct?)
         // Can we stream it in some way?
         // https://stackoverflow.com/a/51801335/2071683 but requires a thread.  Isn't using a thread
         // per request even if from the elastic pool a big problem???  I mean we are trying to reduce
         // threads!
         // I honestly don't know what netty is doing here.  When I try to send a large body it says
         // "request payload too large".  I don't know if that's configurable or not..
         final MonoProcessor<Void> monoP = MonoProcessor.create();
         return req.receive()
             .aggregate()
             .asInputStream()
             .switchIfEmpty(empty)
             .flatMap(body -> {
                final ReactorNettyHttpResponse resteasyResp = new ReactorNettyHttpResponse(req.method().name(), resp, monoP);
                final ReactorNettyHttpRequest resteasyReq =
                    new ReactorNettyHttpRequest(info, req, body, resteasyResp, (SynchronousDispatcher) deployment.getDispatcher());
                deployment.getDispatcher().invoke(resteasyReq, resteasyResp);
                if (!resteasyReq.getAsyncContext().isSuspended()) {
                   try {
                      resteasyResp.finish();
                   } catch (IOException e) {
                      throw new RuntimeException(e);
                   }
                }
                return monoP;
             }).onErrorResume(t -> {
                resp.status(500).sendString(Mono.just(t.getLocalizedMessage())).subscribe(monoP);
                return monoP;
             })
             .doFinally(s -> {
                log.info("Finally - " + s);
             });
      }
   }

   @Override
   public void stop()
   {
      runtimePort = -1;
      server.disposeNow();
      if (deployment != null) {
         deployment.stop();
      }
   }

   @Override
   public ResteasyDeployment getDeployment() {
      if (deployment == null)
      {
         deployment = new ResteasyDeploymentImpl();
      }
      return deployment;
   }

   @Override
   public ReactorNettyJaxrsServer setDeployment(ResteasyDeployment deployment)
   {
      this.deployment = deployment;
      return this;
   }

   @Override
   public ReactorNettyJaxrsServer setPort(int port) {
      this.configuredPort = port;
      return this;
   }

   public int getPort() {
      return runtimePort > 0 ? runtimePort : configuredPort;
   }

   @Override
   public ReactorNettyJaxrsServer setHostname(String hostname) {
      this.hostname = hostname;
      return this;
   }

   public String getHostname() {
      return hostname;
   }

   @Override
   public ReactorNettyJaxrsServer setRootResourcePath(String rootResourcePath)
   {
      root = rootResourcePath;
      if (root != null && root.equals("/")) {
         root = "";
      } else if (!root.startsWith("/")) {
         root = "/" + root;
      }
      return this;
   }

   @Override
   public ReactorNettyJaxrsServer setSecurityDomain(SecurityDomain sc)
   {
      this.domain = sc;
      return this;
   }



   public ReactorNettyJaxrsServer setSSLContext(SSLContext sslContext)
   {
      this.sslContext = sslContext;
      return this;
   }

}
