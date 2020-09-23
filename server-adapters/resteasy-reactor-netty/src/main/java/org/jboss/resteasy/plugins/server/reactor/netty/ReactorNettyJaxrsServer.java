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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.NettyOutbound;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import javax.net.ssl.SSLContext;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.ext.Provider;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.SequenceInputStream;
import java.io.Writer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An HTTP server that sends back the content of the received HTTP request
 * in a pretty plaintext form.
 *
 * @author <a href="http://www.jboss.org/netty/">The Netty Project</a>
 * @author Andy Taylor (andy.taylor@jboss.org)
 * @author <a href="http://gleamynode.net/">Trustin Lee</a>
 * @author Norman Maurer
 * @version $Rev: 2080 $, $Date: 2010-01-26 18:04:19 +0900 (Tue, 26 Jan 2010) $
 */
public class ReactorNettyJaxrsServer implements EmbeddedJaxrsServer<ReactorNettyJaxrsServer>
{

   @Path("/foo")
   public static class Foo {
      @POST
      public Mono<String> echo(String requestBody) {
         return Mono.just(requestBody);
      }

      @POST
      @Path("/stream")
      public Mono<InputStream> echo(InputStream requestBody) {
         return Mono.just(requestBody);
         /*
         StreamingOutput stream = new StreamingOutput() {
            @Override
            public void write(OutputStream os) throws IOException, WebApplicationException {
               try (final OutputStream writer = new BufferedOutputStream(os)) {
                  final byte[] buf = new byte[5 * 1024];
                  int len;
                  while ((len = requestBody.read(buf)) > 0) {
                     writer.write(buf, 0, len);
                  }
                  writer.flush();
                  requestBody.close();
               }
            }
         };
         return Response.ok(stream).build();
          */
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
      public String blockingHello() {
         return "hi!";
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
//      System.out.println(Mono.just(42).doOnEach(i -> System.out.println("produced!")).then(Mono.just(24)).block());
//      if (1 == 1) {
//         System.exit(42);
//      }
      final ReactorNettyJaxrsServer server = new ReactorNettyJaxrsServer();
      final ResteasyDeployment deployment = new ResteasyDeploymentImpl();
      deployment.start();
      //deployment.getDispatcher().getProviderFactory().register(OutFilter.class);
      deployment.getDispatcher().getProviderFactory().register(MonoProvider.class);
      final Registry reg = deployment.getRegistry();
      reg.addSingletonResource(new Foo());
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
   private int ioWorkerCount = Runtime.getRuntime().availableProcessors() * 2;
   private int executorThreadCount = 16;
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

      Publisher<Void> handle(final HttpServerRequest req, final HttpServerResponse resp) {
         final ResteasyUriInfo info = new ResteasyUriInfo(req.uri(), "/");

         return req.receive().asInputStream()
             .collectList()
             .map(l -> {
                if (l.isEmpty()) return new ByteArrayInputStream(new byte[] {});
                if (l.size() == 1) return l.get(0);
                return new SequenceInputStream(Collections.enumeration(l));
             })
             .flatMap(body -> {
                final Mono<Void> completionMono = Mono.empty();
                final ReactorNettyHttpResponse resteasyResp = new ReactorNettyHttpResponse(req.method().name(), resp, completionMono);
                deployment.getDispatcher().invoke(
                    new ReactorNettyHttpRequest(info, req, body, resteasyResp, (SynchronousDispatcher) deployment.getDispatcher()),
                    resteasyResp
                );
                return completionMono.doOnTerminate(() -> {
                   System.out.println("FINISHED!");
                });
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
