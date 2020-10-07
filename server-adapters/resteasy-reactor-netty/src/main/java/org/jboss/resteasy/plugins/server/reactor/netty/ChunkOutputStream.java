package org.jboss.resteasy.plugins.server.reactor.netty;

import org.jboss.resteasy.spi.AsyncOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * This is the output stream leveraged by {@link
 * ReactorNettyHttpResponse#getOutputStream}.  It provides the heavy lifting
 * for actually transfering the bytes written by RestEasy to a {@link
 * Flux<byte[]>}, which is what reactor-netty works with.  Most of the heavy
 * lifting occurs in {@link #asyncWrite(byte[], int, int)}.
 */
public class ChunkOutputStream extends AsyncOutputStream {

   private static final Logger log = LoggerFactory.getLogger(ChunkOutputStream.class);

   private long totalBytesSent;

   // TODO The use of this is very questionable!  I'm just throwing
   // this in at the very end before I try something radically different
   // for the entire adapter.
   private Duration timeout;

    /**
     * This is the {@link Mono} that we return from
     * {@link ReactorNettyJaxrsServer.Handler#handle(HttpServerRequest, HttpServerResponse)}
     */
   private MonoProcessor<Void> completionMono;

    /**
     * Indicates that we've starting sending the response bytes.
     */
    private volatile boolean started;

    /**
     * This is ultimately think 'sink' that we write bytes to in {@link #asyncWrite(byte[], int, int)}.
     */
    private FluxSink<Tuple2<byte[], CompletableFuture<Void>>> byteSink;

    /**
     * This is used to establish {@link #byteSink} upon the first writing of bytes.
     */
   private final Supplier<FluxSink<Tuple2<byte[], CompletableFuture<Void>>>> byteSinkSupplier;

   ChunkOutputStream(
       final HttpServerResponse response,
       final MonoProcessor<Void> completionMono
   ) {
       this.completionMono = completionMono;
       this.byteSinkSupplier = () -> {
           final EmitterProcessor<Tuple2<byte[], CompletableFuture<Void>>> bytesEmitter = EmitterProcessor.create();
           final Flux<byte[]> out = bytesEmitter.map(tup -> {
                   log.trace("Submitting bytes to downstream");
                   tup.getT2().complete(null);
                   return tup.getT1();
               });
           final Flux<byte[]> actualOut = timeout != null ? out.timeout(timeout) : out;
           response.sendByteArray(actualOut).subscribe(completionMono);
           return bytesEmitter.sink();
       };
   }

   @Override
   public void write(int b) {
      byteSink.next(Tuples.of(new byte[] {(byte)b}, new CompletableFuture<>()));
   }

   @Override
   public void close() throws IOException {
       log.trace("Closing the ChunkOutputStream.");
       if (!started || byteSink == null) {
           Mono.<Void>empty().subscribe(completionMono);
       } else {
           byteSink.complete();
       }
       super.close();
   }

   @Override
   public void write(byte[] bs, int off, int len) {
       try {
           asyncWrite(bs, off, len).get();
       } catch (final InterruptedException ie) {
           Thread.currentThread().interrupt();
           throw new RuntimeException(ie);
       } catch (final ExecutionException ee) {
           throw new RuntimeException(ee);
       }
   }

   @Override
   public void flush() throws IOException {
       log.trace("Flush called on ChunkOutputStream");
      super.flush();
   }

   @Override
   public CompletionStage<Void> asyncFlush() {
      // TODO
      return CompletableFuture.completedFuture(null);
   }

    @Override
   public CompletableFuture<Void> asyncWrite(final byte[] bs, int offset, int length) {
        final CompletableFuture<Void> cf = new CompletableFuture<>();
        if (!started) {
            byteSink = byteSinkSupplier.get();
            started = true;
        }

        byte[] bytes = bs;
        if (offset != 0 || length != bs.length) {
            bytes = Arrays.copyOfRange(bs, offset, offset + length);
        }
        byteSink.next(Tuples.of(bytes, cf));
        return cf;
   }

   void setTimeout(final Duration timeout) {
      this.timeout = timeout;
   }
}