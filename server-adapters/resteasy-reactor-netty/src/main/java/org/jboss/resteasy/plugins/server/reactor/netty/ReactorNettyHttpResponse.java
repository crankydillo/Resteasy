package org.jboss.resteasy.plugins.server.reactor.netty;

import io.netty.handler.codec.http.HttpMethod;
import org.jboss.resteasy.spi.HttpResponse;
import reactor.core.publisher.MonoProcessor;
import reactor.netty.http.server.HttpServerResponse;

import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.NewCookie;
import java.io.IOException;
import java.io.OutputStream;

public class ReactorNettyHttpResponse implements HttpResponse {
    private final HttpServerResponse resp;
    private OutputStream out;

    public ReactorNettyHttpResponse(
        final String method,
        HttpServerResponse resp,
        final MonoProcessor<Void> completionMono
    ) {
        this.resp = resp;
        this.out = (method == null || !method.equals(HttpMethod.HEAD)) ? new ChunkOutputStream(resp, completionMono) : null; //[RESTEASY-1627]
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
