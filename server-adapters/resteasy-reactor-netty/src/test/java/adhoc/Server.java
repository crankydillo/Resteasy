package adhoc;

import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.plugins.server.reactor.netty.ReactorNettyJaxrsServer;
import org.jboss.resteasy.reactor.MonoProvider;
import org.jboss.resteasy.spi.Registry;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.resteasy.adapter.test.resource.Simple;
import org.resteasy.adapter.test.resource.Streaming;
import org.resteasy.adapter.test.resource.Timeout;
import reactor.blockhound.BlockHound;
import reactor.core.publisher.Mono;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.time.Duration;

public class Server {
    public static void main(String[] args) {
        BlockHound.install();
        final ReactorNettyJaxrsServer server = new ReactorNettyJaxrsServer();
        final ResteasyDeployment deployment = new ResteasyDeploymentImpl();
        deployment.start();
        deployment.getDispatcher().getProviderFactory().register(MonoProvider.class);
        final Registry reg = deployment.getRegistry();
        reg.addSingletonResource(new Simple());
        reg.addSingletonResource(new Streaming());
        reg.addSingletonResource(new Timeout());
        reg.addSingletonResource(new Reactor());
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
