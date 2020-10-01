package org.jboss.resteasy.plugins.server.reactor.netty;

import org.jboss.resteasy.spi.AsyncOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.netty.http.server.HttpServerResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This is the output stream leveraged by {@link
 * ReactorNettyHttpResponse#getOutputStream}.  It provides the heavy lifting
 * for actually transfering the bytes written by RestEasy to a {@link
 * Flux<byte[]>}, which is what reactor-netty works with.  Most of the heavy
 * lifting occurs in {@link #asyncWrite(byte[], int, int)}.
 */
public class ChunkOutputStream extends AsyncOutputStream {

   private static final Logger log = LoggerFactory.getLogger(ChunkOutputStream.class);

   private static final boolean COMPLETED_SIGNAL = true;

   /**
    * Allows the sending of bytes to the client.
    */
   private final HttpServerResponse response;

   /**
    * A signal back to the 'main' Flux that writing bytes back to the client has finished.
    */
   private final MonoProcessor<Void> completionMono;

   /**
    * A sink that will be used to bridge bytes from RestEasy (i.e. this class)
    * to the `Flux<byte[]>` used in sendByteArray on {@link #response}.
    */
   private final AtomicReference<EventListener> listener = new AtomicReference<>();

   /**
    * This is used to stop the sending of bytes until the sink and {@link #listener} used
    * with {@link #out} has been fully established.
    */
   private final CompletableFuture<Boolean> sinkCreated = new CompletableFuture<>();

   /**
    * Indicates we've started sending bytes.
    */
   private boolean started; // want to eliminate this

   // TODO The use of this is very questionable!  I'm just throwing
   // this in at the very end before I try something radically different
   // for the entire adapter.
   private Duration timeout;

   /**
    * The {@link Flux<byte[]>} that is fed into {@link HttpServerResponse#sendByteArray}.
    */
   private final Flux<byte[]> out = Flux.create(sink -> {
      log.trace("Establishing sink and listener!");
      listener.set(new ChunkOutputStream.EventListener() {
         @Override
         public void data(byte[] bs) {
             log.trace("Sending some data!");
             sink.next(bs);
         }
         @Override
         public void finish() {
             sink.complete();
         }
      });
      sinkCreated.complete(COMPLETED_SIGNAL);
   });

   /**
    * The interface used to transfer data from an eventing API (i.e. {@link
    * #asyncWrite}) to a {@link Flux} ({@link #out} in this case).  See
    * documentation around {@link Flux#create} for more information on this.
    */
   interface EventListener {
      void data(byte[] s);
      void finish();
   }

   ChunkOutputStream(
       final HttpServerResponse response,
       final MonoProcessor<Void> completionMono
   ) {
      this.response = response;
      this.completionMono = completionMono;
   }

   @Override
   public void write(int b) {
      listener.get().data(new byte[] {(byte)b});
   }

   public void reset() {
      // TODO
   }

   @Override
   public void close() throws IOException {
      final EventListener el = listener.get();
      if (el != null) {
         el.finish();
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
      // call async flush?
      super.flush();
   }

   @Override
   public CompletionStage<Void> asyncFlush() {
      // TODO
      return CompletableFuture.completedFuture(null);
   }

   @Override
   public CompletableFuture<Void> asyncWrite(final byte[] bs, int offset, int length) {
      final CompletableFuture<Boolean> cf = new CompletableFuture<>();
      if (!started) {
         started = true;
         Flux<byte[]> actualOut =
             out.map(b -> {
                    cf.complete(COMPLETED_SIGNAL);
                    return b;
                }).doFinally(s -> cf.complete(COMPLETED_SIGNAL));
         if (timeout != null) {
            actualOut = actualOut.timeout(timeout);
         }
         response.sendByteArray(actualOut).subscribe(completionMono);
      }

      return Mono.fromFuture(sinkCreated)
          .map(ignore -> {
                 byte[] bytes = bs;
                 if (offset != 0 || length != bs.length) {
                    bytes = Arrays.copyOfRange(bs, offset, offset + length);
                 }
                 listener.get().data(bytes);
                 return ignore;
              })
              .flatMap(ignore -> Mono.fromFuture(cf))
              .then()
              .toFuture();
   }

   void setTimeout(final Duration timeout) {
      this.timeout = timeout;
   }
}
