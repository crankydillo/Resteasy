package org.jboss.resteasy.plugins.server.reactor.netty;

import org.jboss.resteasy.spi.AsyncOutputStream;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoProcessor;
import reactor.netty.http.server.HttpServerResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public class ChunkOutputStream extends AsyncOutputStream {

   interface EventListener {
      void data(byte[] s);
      void finish();
   }

   private final HttpServerResponse response;
   private boolean started;


   void pt(String msg) {
      //System.out.println("[" + Thread.currentThread().getName() + "] " + msg);
   }

   private final AtomicReference<EventListener> listener = new AtomicReference<>();
   private final CompletableFuture<String> sinkCreated = new CompletableFuture<>();

   Flux<byte[]> out = Flux.create(sink -> {
      pt("Setting listener!");
      listener.set(new ChunkOutputStream.EventListener() {
         @Override
         public void data(byte[] bs) {
            sink.next(bs);
         }
         @Override
         public void finish() {
            sink.complete();
         }
      });
      sinkCreated.complete("");
   });

   private final MonoProcessor<Void> completionMono;

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
      asyncWrite(bs, off, len);
   }

   @Override
   public void flush() throws IOException {
      super.flush();
   }

   @Override
   public CompletionStage<Void> asyncFlush()
   {
      return CompletableFuture.completedFuture(null);
   }

   @Override
   public CompletionStage<Void> asyncWrite(final byte[] bs, int offset, int length) {
      // TODO The big 'known' problem we have is that the 'listener' atomic value is not set
      // until subscription on 'out'.  These can happen on separate threads (i.e. if
      // user sets timeout on the Mono in business logic.  I'm still trying to reason
      // through these things.  Anyhow, ideally, we would set the subscription and establish
      // out and it's 'feed' (listener value) before we get to this point; however, I have
      // not found a hook point for that.
      //
      // The BIG problem is that the subscription to

      // The code below was just some quick hack to try and solve the problem above.
      // Another option is to couple with impl details (i.e. caller), but I don't want
      // to go there yet.

      // For now, I'm going to see what it would be like to change some of the framework
      // code.

      final CompletableFuture<String> cf2 = new CompletableFuture<>();
      if (!started) {
         started = true;
         response.sendByteArray(
             out.map(b -> {
                cf2.complete("");
                return b;
             }).doFinally(s -> cf2.complete(""))
         ).subscribe(completionMono);
      }

      pt("returning cf");
      return Mono.fromFuture(sinkCreated)
          .map(ignore -> {
                 pt("Sending data! - " + ignore);
                 byte[] bytes = bs;
                 if (offset != 0 || length != bs.length) {
                    bytes = Arrays.copyOfRange(bs, offset, offset + length);
                 }
                 listener.get().data(bytes);
                 return "";
              })
              .flatMap(ignore -> Mono.fromFuture(cf2))
              .then()
              .toFuture();
   }
}
