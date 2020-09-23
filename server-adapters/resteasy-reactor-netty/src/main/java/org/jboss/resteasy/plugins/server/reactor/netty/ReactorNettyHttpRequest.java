package org.jboss.resteasy.plugins.server.reactor.netty;

import org.jboss.resteasy.core.AbstractAsynchronousResponse;
import org.jboss.resteasy.core.AbstractExecutionContext;
import org.jboss.resteasy.core.ResteasyContext;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.plugins.server.BaseHttpRequest;
import org.jboss.resteasy.specimpl.MultivaluedMapImpl;
import org.jboss.resteasy.specimpl.ResteasyHttpHeaders;
import org.jboss.resteasy.specimpl.ResteasyUriInfo;
import org.jboss.resteasy.spi.ResteasyAsynchronousContext;
import org.jboss.resteasy.spi.ResteasyAsynchronousResponse;
import org.jboss.resteasy.spi.RunnableWithException;
import reactor.netty.http.server.HttpServerRequest;

import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

class ReactorNettyHttpRequest extends BaseHttpRequest {
    private final HttpServerRequest req;
    private InputStream in;
    private final NettyExecutionContext executionContext;

    public ReactorNettyHttpRequest(
        ResteasyUriInfo uri,
        HttpServerRequest req,
        InputStream body,
        ReactorNettyHttpResponse response,
        SynchronousDispatcher dispatcher
    ) {
        super(uri);
        this.req = req;
        this.in = body;
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
        //return req.receive().asInputStream().reduce(SequenceInputStream::new).subscribeOn(Schedulers.elastic()).block();
        //return new ByteArrayInputStream("input".getBytes());
        return in;
    }

    @Override
    public void setInputStream(InputStream stream) {
        this.in = in;
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
        protected final ReactorNettyHttpRequest request;
        protected final ReactorNettyHttpResponse response;
        protected volatile boolean done;
        protected volatile boolean cancelled;
        protected volatile boolean wasSuspended;
        protected NettyExecutionContext.NettyHttpAsyncResponse asyncResponse;

        NettyExecutionContext(final ReactorNettyHttpRequest request, final ReactorNettyHttpResponse response, final SynchronousDispatcher dispatcher)
        {
            super(dispatcher, request, response);
            this.request = request;
            this.response = response;
            this.asyncResponse = new NettyExecutionContext.NettyHttpAsyncResponse(dispatcher, request, response);
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
            if(1 == 1) { // TODO if(!NettyUtil.isIoThread()) {
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

            private ReactorNettyHttpResponse nettyResponse;
            NettyHttpAsyncResponse(final SynchronousDispatcher dispatcher, final ReactorNettyHttpRequest request, final ReactorNettyHttpResponse response) {
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