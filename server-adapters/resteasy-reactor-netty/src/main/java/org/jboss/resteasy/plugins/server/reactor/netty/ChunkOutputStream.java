package org.jboss.resteasy.plugins.server.reactor.netty;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;
import org.jboss.resteasy.core.SynchronousDispatcher;
import org.jboss.resteasy.spi.AsyncOutputStream;
import org.reactivestreams.Publisher;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import reactor.netty.DisposableServer;
import reactor.netty.NettyOutbound;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Class to help application that are built to write to an
 * OutputStream to chunk the content
 *
 * <pre>
 * {@code
 * DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
 * HttpHeaders.setTransferEncodingChunked(response);
 * response.headers().set(CONTENT_TYPE, "application/octet-stream");
 * //other headers
 * ctx.write(response);
 * // code of the application that use the ChunkOutputStream
 * // Don't forget to close the ChunkOutputStream after use!
 * ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(ChannelFutureListener.CLOSE);
 * }
 * </pre>
 * @author tbussier
 *
 */
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

   Flux<byte[]> out = Flux.<byte[]>create(sink -> {
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

   private final Mono<Void> completionMono;


      ChunkOutputStream(
       final ReactorNettyHttpResponse response,
       final Mono<Void> completionMono
   ) {
      this.response = response.resp;
      this.completionMono = completionMono;
   }

   @Override
   public void write(int b) throws IOException {
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
   public void write(byte[] bs, int off, int len) throws IOException {
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
         response.sendByteArray(out).then(completionMono).then().subscribe();
      }
      byte[] bytes = bs;
      if (offset != 0 || length != bs.length) {
         bytes = Arrays.copyOfRange(bs, offset, offset + length);
      }
      listener.get().data(bytes);
      return CompletableFuture.completedFuture(null);
   }
}
