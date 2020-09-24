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
import java.util.concurrent.atomic.AtomicReference;

public class ChunkOutputStream extends AsyncOutputStream {

   interface EventListener {
      void data(byte[] s);
      void finish();
   }

   /*
   public static void main(String[] args) {
      final DisposableServer svr = HttpServer.create()
          .port(7000)
          .handle((req, resp) -> {
             final List<EventListener> listeners = new ArrayList<>();

             new Thread() {
                @Override
                public void run() {
                   Flux.range(0, 2000).delayElements(Duration.ofMillis(5))
                       .doOnNext(i -> {
                          System.out.println(Thread.currentThread().getName() + " - Event " + i);
                          listeners.forEach(l -> l.data(i + ""));
                       })
                       .subscribe(
                           i -> {},
                           Throwable::printStackTrace,
                           () -> listeners.forEach(EventListener::finish)
                       );
                }
             }.start();

             return resp.sendByteArray(Flux.<byte[]>create(sink -> {
                listeners.add(new EventListener() {
                   @Override
                   public void data(String s) {
                      System.out.println(Thread.currentThread().getName() + " - Emitting " + s);
                      sink.next(s.getBytes());
                   }
                   @Override
                   public void finish() {
                      sink.complete();
                   }
                });
             }).doOnNext(i -> {
                System.out.println(Thread.currentThread().getName() + " - Emitted " + i);
             }));
          }).bindNow();

      svr.onDispose().block();
   }
    */

   private final HttpServerResponse response;
   private boolean started;
   private final AtomicReference<EventListener> listener = new AtomicReference<>();

   Flux<byte[]> out = Flux.create(sink -> {
      ChunkOutputStream.EventListener l = new ChunkOutputStream.EventListener() {
         @Override
         public void data(byte[] bs) {
            sink.next(bs);
         }
         @Override
         public void finish() {
            sink.complete();
         }
      };
      listener.set(l);
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
   public CompletionStage<Void> asyncWrite(byte[] bs, int offset, int length)
   {
      if (!started) {
         started = true;
         response.sendByteArray(out).subscribe(completionMono);
      }
      byte[] bytes = bs;
      if (offset != 0 || length != bs.length) {
         bytes = Arrays.copyOfRange(bs, offset, offset + length);
      }
      listener.get().data(bytes);
      return Mono.empty().then().toFuture();
   }
}
