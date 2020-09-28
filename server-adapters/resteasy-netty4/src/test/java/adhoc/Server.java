package adhoc;

import org.jboss.resteasy.plugins.server.netty.NettyContainer;
import org.jboss.resteasy.spi.Registry;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.resteasy.adapter.test.resource.Simple;
import org.resteasy.adapter.test.resource.Streaming;

import org.resteasy.adapter.test.resource.Timeout;
import reactor.blockhound.BlockHound;

public class Server {
    public static void main(final String[] args) throws Exception {
        BlockHound.install();
        System.setProperty("org.jboss.resteasy.port", "8082");
        final ResteasyDeployment deployment = NettyContainer.start();
        deployment.start();
        //deployment.getDispatcher().getProviderFactory().register(OutFilter.class);
        final Registry reg = deployment.getRegistry();
        reg.addSingletonResource(new Simple());
        reg.addSingletonResource(new Streaming());
        reg.addSingletonResource(new Timeout());
    }
}
