package org.jboss.resteasy.plugins.server.reactor.netty;

import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.plugins.server.embedded.EmbeddedJaxrsServer;
import org.jboss.resteasy.plugins.server.embedded.SecurityDomain;
import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.jboss.resteasy.util.EmbeddedServerHelper;
import org.jboss.resteasy.util.PortProvider;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import javax.net.ssl.SSLContext;

import java.io.IOException;
import java.io.InputStream;

public class ReactorNettyJaxrsServer implements EmbeddedJaxrsServer<ReactorNettyJaxrsServer> {

   final Logger log = LoggerFactory.getLogger(ReactorNettyJaxrsServer.class);

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

   @Override
   public ReactorNettyJaxrsServer start() {
      log.info("Starting RestEasy Reactor-based server!");
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
