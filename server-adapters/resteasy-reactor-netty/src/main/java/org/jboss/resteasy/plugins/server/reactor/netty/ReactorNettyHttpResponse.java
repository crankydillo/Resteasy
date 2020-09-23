package org.jboss.resteasy.plugins.server.reactor.netty;

import io.netty.handler.codec.http.HttpMethod;
import org.jboss.resteasy.spi.HttpResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.NettyOutbound;
import reactor.netty.http.server.HttpServerResponse;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.CheckedOutputStream;

public class ReactorNettyHttpResponse implements HttpResponse {
    final HttpServerResponse resp;
    OutputStream out;

    ChunkOutputStream.EventListener listener;

    public ReactorNettyHttpResponse(
        final String method,
        HttpServerResponse resp,
        final Mono<Void> completionMono
    ) {
        this.resp = resp;

        this.out = (method == null || !method.equals(HttpMethod.HEAD)) ? new ChunkOutputStream(this, completionMono) : null; //[RESTEASY-1627]
        if (out instanceof ChunkOutputStream) {
            final ChunkOutputStream cout = (ChunkOutputStream)out;
        }
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
        out.close();
    }

    @Override
    public void flushBuffer() throws IOException {
        out.flush();
    }
}
