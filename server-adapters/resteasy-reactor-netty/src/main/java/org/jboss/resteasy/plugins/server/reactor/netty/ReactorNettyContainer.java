package org.jboss.resteasy.plugins.server.reactor.netty;

import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.plugins.server.embedded.SecurityDomain;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.jboss.resteasy.util.PortProvider;

public class ReactorNettyContainer {
    public static ReactorNettyJaxrsServer netty;

    public static ResteasyDeployment start() throws Exception
    {
        return start("");
    }

    public static ResteasyDeployment start(String bindPath) throws Exception
    {
        return start(bindPath, null);
    }

    public static void start(ResteasyDeployment deployment)
    {
        netty = new ReactorNettyJaxrsServer();
        netty.setDeployment(deployment);
        netty.setPort(PortProvider.getPort());
        netty.setRootResourcePath("");
        netty.setSecurityDomain(null);
        netty.start();
    }

    public static ResteasyDeployment start(String bindPath, SecurityDomain domain) throws Exception
    {
        ResteasyDeployment deployment = new ResteasyDeploymentImpl();
        deployment.setSecurityEnabled(true);
        return start(bindPath, domain, deployment);
    }

    public static ResteasyDeployment start(String bindPath, SecurityDomain domain, ResteasyDeployment deployment) throws Exception
    {
        netty = new ReactorNettyJaxrsServer();
        netty.setDeployment(deployment);
        netty.setPort(PortProvider.getPort());
        netty.setRootResourcePath(bindPath);
        netty.setSecurityDomain(domain);
        netty.start();
        return netty.getDeployment();
    }

    public static void stop()
    {
        if (netty != null)
        {
            try
            {
                netty.stop();
            }
            catch (Exception e)
            {

            }
        }
        netty = null;
    }

    public static void main(String[] args) throws Exception {
        start();
    }

}
