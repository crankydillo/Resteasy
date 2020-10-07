package adhoc;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.resteasy.adapter.test.resource.Async;
import org.resteasy.adapter.test.resource.Simple;
import org.resteasy.adapter.test.resource.Streaming;
import org.resteasy.adapter.test.resource.Timeout;

import javax.ws.rs.core.Application;
import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class Server {
    public static void main(String[] args) throws Exception {
        final Tomcat tomcat = new Tomcat();
        tomcat.setPort(8083);
        tomcat.getConnector();
        final Context ctx = tomcat.addContext("/", new File(".").getAbsolutePath());
        ctx.addParameter("javax.ws.rs.Application", JaxrsApplication.class.getName());
        Tomcat.addServlet(ctx, "rest-easy-servlet", new HttpServletDispatcher());
        ctx.addServletMappingDecoded("/*", "rest-easy-servlet");
        tomcat.start();
        tomcat.getServer().await();
    }

    public static class JaxrsApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return new HashSet<>(Arrays.asList(
                Simple.class,
                Streaming.class,
                Timeout.class,
                Async.class
            ));
        }
    }
}
