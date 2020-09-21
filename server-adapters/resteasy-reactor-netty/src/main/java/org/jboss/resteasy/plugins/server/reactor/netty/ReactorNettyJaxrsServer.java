package org.jboss.resteasy.plugins.server.netty;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.LastHttpContent;
import org.jboss.resteasy.core.AbstractAsynchronousResponse;
import org.jboss.resteasy.core.AbstractExecutionContext;
import org.jboss.resteasy.core.ResteasyContext;
import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.plugins.server.BaseHttpRequest;
import org.jboss.resteasy.plugins.server.embedded.EmbeddedJaxrsServer;
import org.jboss.resteasy.plugins.server.embedded.SecurityDomain;
import org.jboss.resteasy.specimpl.MultivaluedMapImpl;
import org.jboss.resteasy.specimpl.ResteasyHttpHeaders;
import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.jboss.resteasy.spi.Dispatcher;
import org.jboss.resteasy.spi.HttpRequest;
import org.jboss.resteasy.spi.HttpResponse;
import org.jboss.resteasy.spi.ResteasyAsynchronousContext;
import org.jboss.resteasy.spi.ResteasyAsynchronousResponse;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.jboss.resteasy.spi.RunnableWithException;
import org.jboss.resteasy.util.EmbeddedServerHelper;
import org.jboss.resteasy.util.PortProvider;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import javax.net.ssl.SSLContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.AbstractMultivaluedMap;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

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
      @GET
      public String get() {
         return "hello from foo!";
      }
   }

   public static void main(String[] args) {
      final ReactorNettyJaxrsServer server = new ReactorNettyJaxrsServer();
      final ResteasyDeployment deployment = new ResteasyDeploymentImpl();
      deployment.start();
      deployment.getRegistry().addSingletonResource(new Foo());
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
         final ResteasyResp resteasyResp = new ResteasyResp(resp);
         deployment.getDispatcher().invoke(new ResteasyReq(info, req, resteasyResp, (SynchronousDispatcher)deployment.getDispatcher()), resteasyResp);
         return resp.sendByteArray(Mono.just(resteasyResp.o1.toByteArray()));
      }
   }

   static class ResteasyResp implements HttpResponse {
      final HttpServerResponse resp;
      final ByteArrayOutputStream o1 = new ByteArrayOutputStream();
      OutputStream out = o1;

      public ResteasyResp(HttpServerResponse resp) {
         this.resp = resp;
      }

      @Override
      public int getStatus() {
         return resp.status().code();
      }

      @Override
      public void setStatus(int status) {
         resp.status(status);
      }

      @Override
      public MultivaluedMap<String, Object> getOutputHeaders() {
         return new MultivaluedHashMap<>();
      }

      @Override
      public OutputStream getOutputStream() throws IOException {
         return out;
      }

      @Override
      public void setOutputStream(OutputStream os) {
         out = os;
      }

      @Override
      public void addNewCookie(NewCookie cookie) {

      }

      @Override
      public void sendError(int status) throws IOException {

      }

      @Override
      public void sendError(int status, String message) throws IOException {

      }

      @Override
      public boolean isCommitted() {
         return false;
      }

      @Override
      public void reset() {

      }

      public void finish() throws IOException {
         if (out != null)
            out.flush();
      }

      @Override
      public void flushBuffer() throws IOException {
      }
   }

   static class ResteasyReq extends BaseHttpRequest {
      private final HttpServerRequest req;
      private final NettyExecutionContext executionContext;

      public ResteasyReq(ResteasyUriInfo uri, HttpServerRequest req, ResteasyResp response, SynchronousDispatcher dispatcher) {
         super(uri);
         this.req = req;
         this.executionContext = new NettyExecutionContext(this, response, dispatcher);
      }

      @Override
      public HttpHeaders getHttpHeaders() {
         final MultivaluedMap<String, String> map = new MultivaluedMapImpl<>();
         req.requestHeaders().forEach(e -> map.putSingle(e.getKey(), e.getValue()));
         return new ResteasyHttpHeaders(map);
      }

      @Override
      public MultivaluedMap<String, String> getMutableHeaders() {
         return null;
      }

      @Override
      public InputStream getInputStream() {
         return req.receive().asInputStream().blockFirst();
      }

      @Override
      public void setInputStream(InputStream stream) {

      }

      @Override
      public String getHttpMethod() {
         return req.method().name();
      }

      @Override
      public void setHttpMethod(String method) {

      }

      @Override
      public Object getAttribute(String attribute) {
         return null;
      }

      @Override
      public void setAttribute(String name, Object value) {

      }

      @Override
      public void removeAttribute(String name) {

      }

      @Override
      public Enumeration<String> getAttributeNames() {
         return null;
      }

      @Override
      public ResteasyAsynchronousContext getAsyncContext() {
         return executionContext;
      }

      @Override
      public void forward(String path) {

      }

      @Override
      public boolean wasForwarded() {
         return false;
      }

      @Override
      public String getRemoteAddress() {
         return null;
      }

      @Override
      public String getRemoteHost() {
         return null;
      }

      class NettyExecutionContext extends AbstractExecutionContext {
         protected final ResteasyReq request;
         protected final ResteasyResp response;
         protected volatile boolean done;
         protected volatile boolean cancelled;
         protected volatile boolean wasSuspended;
         protected NettyHttpAsyncResponse asyncResponse;

         NettyExecutionContext(final ResteasyReq request, final ResteasyResp response, final SynchronousDispatcher dispatcher)
         {
            super(dispatcher, request, response);
            this.request = request;
            this.response = response;
            this.asyncResponse = new NettyHttpAsyncResponse(dispatcher, request, response);
         }

         @Override
         public boolean isSuspended() {
            return wasSuspended;
         }

         @Override
         public ResteasyAsynchronousResponse getAsyncResponse() {
            return asyncResponse;
         }

         @Override
         public ResteasyAsynchronousResponse suspend() throws IllegalStateException {
            return suspend(-1);
         }

         @Override
         public ResteasyAsynchronousResponse suspend(long millis) throws IllegalStateException {
            return suspend(millis, TimeUnit.MILLISECONDS);
         }

         @Override
         public ResteasyAsynchronousResponse suspend(long time, TimeUnit unit) throws IllegalStateException {
            if (wasSuspended)
            {
               throw new IllegalStateException("oh nos!");
            }
            wasSuspended = true;
            return asyncResponse;
         }

         @Override
         public void complete() {
            if (wasSuspended) {
               asyncResponse.complete();
            }
         }

         @Override
         public CompletionStage<Void> executeAsyncIo(CompletionStage<Void> f) {
            // check if this CF is already resolved
            CompletableFuture<Void> ret = f.toCompletableFuture();
            // if it's not resolved, we may need to suspend
            if(!ret.isDone() && !isSuspended()) {
               suspend();
            }
            return ret;
         }

         @Override
         public CompletionStage<Void> executeBlockingIo(RunnableWithException f, boolean hasInterceptors) {
            if(1 == 2) { // todo
               //if(!NettyUtil.isIoThread()) {
               // we're blocking
               try {
                  f.run();
               } catch (Exception e) {
                  CompletableFuture<Void> ret = new CompletableFuture<>();
                  ret.completeExceptionally(e);
                  return ret;
               }
               return CompletableFuture.completedFuture(null);
            } else if(!hasInterceptors) {
               Map<Class<?>, Object> context = ResteasyContext.getContextDataMap();
               // turn any sync request into async
               if(!isSuspended()) {
                  suspend();
               }
               return CompletableFuture.runAsync(() -> {
                  try(ResteasyContext.CloseableContext newContext = ResteasyContext.addCloseableContextDataLevel(context)){
                     f.run();
                  } catch (RuntimeException e) {
                     throw e;
                  } catch (Exception e) {
                     throw new RuntimeException(e);
                  }
               });
            } else {
               CompletableFuture<Void> ret = new CompletableFuture<>();
               ret.completeExceptionally(new RuntimeException("Cannot use blocking IO with interceptors when we're on the IO thread"));
               return ret;
            }
         }

         /**
          * Netty implementation of {@link AsyncResponse}.
          *
          * @author Kristoffer Sjogren
          */
         class NettyHttpAsyncResponse extends AbstractAsynchronousResponse {
            private final Object responseLock = new Object();
            protected ScheduledFuture timeoutFuture;

            private ResteasyResp nettyResponse;
            NettyHttpAsyncResponse(final SynchronousDispatcher dispatcher, final ResteasyReq request, final ResteasyResp response) {
               super(dispatcher, request, response);
               this.nettyResponse = response;
            }

            @Override
            public void initialRequestThreadFinished() {
               // done
            }

            @Override
            public void complete() {
               synchronized (responseLock)
               {
                  if (done) return;
                  if (cancelled) return;
                  done = true;
                  nettyFlush();
               }
            }


            @Override
            public boolean resume(Object entity) {
               synchronized (responseLock)
               {
                  if (done) return false;
                  if (cancelled) return false;
                  done = true;
                  return internalResume(entity, t -> nettyFlush());
               }
            }

            @Override
            public boolean resume(Throwable ex) {
               synchronized (responseLock)
               {
                  if (done) return false;
                  if (cancelled) return false;
                  done = true;
                  return internalResume(ex, t -> nettyFlush());
               }
            }

            @Override
            public boolean cancel() {
               synchronized (responseLock)
               {
                  if (cancelled) {
                     return true;
                  }
                  if (done) {
                     return false;
                  }
                  done = true;
                  cancelled = true;
                  return internalResume(Response.status(Response.Status.SERVICE_UNAVAILABLE).build(), t -> nettyFlush());
               }
            }

            @Override
            public boolean cancel(int retryAfter) {
               synchronized (responseLock)
               {
                  if (cancelled) return true;
                  if (done) return false;
                  done = true;
                  cancelled = true;
                  return internalResume(Response.status(Response.Status.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, retryAfter).build(),
                      t -> nettyFlush());
               }
            }

            protected synchronized void nettyFlush()
            {
               //flushed = true;
               try
               {
                  nettyResponse.finish();
               }
               catch (IOException e)
               {
                  throw new RuntimeException(e);
               }
            }

            @Override
            public boolean cancel(Date retryAfter) {
               synchronized (responseLock)
               {
                  if (cancelled) return true;
                  if (done) return false;
                  done = true;
                  cancelled = true;
                  return internalResume(Response.status(Response.Status.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, retryAfter).build(),
                      t -> nettyFlush());
               }
            }

            @Override
            public boolean isSuspended() {
               return !done && !cancelled;
            }

            @Override
            public boolean isCancelled() {
               return cancelled;
            }

            @Override
            public boolean isDone() {
               return done;
            }

            @Override
            public boolean setTimeout(long time, TimeUnit unit) {
               synchronized (responseLock)
               {
                  if (done || cancelled) return false;
                  if (timeoutFuture != null  && !timeoutFuture.cancel(false)) {
                     return false;
                  }
                  Runnable task = new Runnable() {
                     @Override
                     public void run()
                     {
                        handleTimeout();
                     }
                  };
                  timeoutFuture = null;//ctx.executor().schedule(task, time, unit);
               }
               return true;
            }

            protected void handleTimeout()
            {
               if (timeoutHandler != null)
               {
                  timeoutHandler.handleTimeout(this);
               }
               if (done) return;
               resume(new ServiceUnavailableException());
            }
         }
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
