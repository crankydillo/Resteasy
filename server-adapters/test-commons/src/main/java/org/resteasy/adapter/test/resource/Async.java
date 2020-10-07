package org.resteasy.adapter.test.resource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.container.Suspended;
import java.time.Duration;
import java.util.Optional;

@Path("/async")
public class Async {

    @GET
    public void async(
        @QueryParam("delay") Integer delay,
        @QueryParam("size") @DefaultValue("1") int payloadSize,
        @Suspended AsyncResponse resp
    ) {
        Mono<String> bodyLogic = Flux.range(0, payloadSize)
            .map(ignore -> "a")
            .collectList()
            .map(l -> String.join("", l));

        Mono<String> businessLogic =
            Optional.ofNullable(delay)
                .map(d -> bodyLogic.delayElement(Duration.ofMillis(d)))
                .orElse(bodyLogic);

        businessLogic.subscribe(
            resp::resume,
            resp::resume
        );
    }
}