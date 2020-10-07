package adhoc;

import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.plugins.server.reactor.netty.ReactorNettyJaxrsServer;
import org.jboss.resteasy.reactor.FluxProvider;
import org.jboss.resteasy.reactor.MonoProvider;
import org.jboss.resteasy.spi.Registry;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.resteasy.adapter.test.resource.Async;
import org.resteasy.adapter.test.resource.Simple;
import org.resteasy.adapter.test.resource.Streaming;
import org.resteasy.adapter.test.resource.Timeout;
import org.slf4j.LoggerFactory;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.time.Duration;

public class Server {

    @Path("block")
    public static class Block {
        @GET
        public String get(@QueryParam("d") @DefaultValue("0") final int delay) throws Exception {
            Thread.sleep(delay * 1_000);
            return "I blocked!";
        }
    }
 
    @Path("monod")
    public static class MonoRes {
        private static final String DATA =
            Flux
                .range(0, 10_000)
                .map(i -> "a")
                .collectList()
                .map(l -> String.join("", l))
                .block();

        @GET
        public Mono<String> get(
            @QueryParam("delay") @DefaultValue("0") final int delay
        ) {
            return Mono.just(DATA)
                .delayElement(Duration.ofSeconds(delay));

//            return Flux.range(0, 50_000)
//                .map(i -> i + ". value")
                //.delayElements(Duration.ofSeconds(delay));
        }

        private void p(Object o) {
            System.out.println("[" + Thread.currentThread().getName() + "] " + o);
        }
    }

    public static void main(String[] args) {
        //BlockHound.install();
        final ReactorNettyJaxrsServer server = new ReactorNettyJaxrsServer();
        final ResteasyDeployment deployment = new ResteasyDeploymentImpl();
        deployment.start();
        deployment.getDispatcher().getProviderFactory().register(MonoProvider.class);
        deployment.getDispatcher().getProviderFactory().register(FluxProvider.class);
        final Registry reg = deployment.getRegistry();
        reg.addSingletonResource(new Simple());
        reg.addSingletonResource(new Streaming());
        reg.addSingletonResource(new Timeout());
        reg.addSingletonResource(new Async());
        reg.addSingletonResource(new Reactor());
        reg.addSingletonResource(new MonoRes());
        reg.addSingletonResource(new Block());
        server.setDeployment(deployment);
        server.setPort(8081);
        server.start();
    }

    @Path("/mono")
    public static class Reactor {
        @POST
        public Mono<String> echo(String requestBody) {
            return Mono.just(requestBody);
        }

        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public Mono<String> hello() {
            return Mono.just("Hello from mono!!");
        }

        @GET
        @Path("/timeout")
        public Mono monoTimeout() {
            return Mono.just(1)
                .delayElement(Duration.ofSeconds(5))
                .timeout(Duration.ofNanos(2));
        }
    }
}
