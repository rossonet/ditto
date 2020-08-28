/*
 * Copyright (c) 2020 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.gateway.streaming.actors;


import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.eclipse.ditto.model.base.acks.AcknowledgementRequest;
import org.eclipse.ditto.model.base.acks.DittoAcknowledgementLabel;
import org.eclipse.ditto.model.base.common.HttpStatusCode;
import org.eclipse.ditto.model.base.exceptions.DittoRuntimeException;
import org.eclipse.ditto.model.base.headers.DittoHeaders;
import org.eclipse.ditto.model.base.json.JsonSchemaVersion;
import org.eclipse.ditto.model.base.json.Jsonifiable;
import org.eclipse.ditto.model.things.Thing;
import org.eclipse.ditto.model.things.ThingId;
import org.eclipse.ditto.protocoladapter.HeaderTranslator;
import org.eclipse.ditto.services.gateway.security.authentication.jwt.JwtAuthenticationResultProvider;
import org.eclipse.ditto.services.gateway.security.authentication.jwt.JwtValidator;
import org.eclipse.ditto.services.gateway.streaming.Connect;
import org.eclipse.ditto.services.gateway.streaming.IncomingSignal;
import org.eclipse.ditto.services.models.acks.config.DefaultAcknowledgementConfig;
import org.eclipse.ditto.services.models.concierge.pubsub.DittoProtocolSub;
import org.eclipse.ditto.signals.commands.base.CommandResponse;
import org.eclipse.ditto.signals.commands.things.exceptions.ThingNotAccessibleException;
import org.eclipse.ditto.signals.commands.things.modify.ModifyThing;
import org.eclipse.ditto.signals.commands.things.modify.ModifyThingResponse;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.Mockito;

import com.typesafe.config.ConfigFactory;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.pf.ReceiveBuilder;
import akka.stream.Attributes;
import akka.testkit.TestProbe;
import akka.testkit.javadsl.TestKit;
import scala.concurrent.duration.FiniteDuration;

/**
 * Test the interaction between timeout, response-required and requested-acks for
 * {@link org.eclipse.ditto.services.gateway.streaming.actors.StreamingSessionActor}.
 */
@RunWith(Parameterized.class)
public final class StreamingSessionActorHeaderInteractionTest {

    private static ActorSystem actorSystem;

    private static final List<Duration> TIMEOUT = List.of(Duration.ZERO, Duration.ofMinutes(1L));
    private static final List<Boolean> RESPONSE_REQUIRED = List.of(false, true);
    private static final List<List<AcknowledgementRequest>> REQUESTED_ACKS =
            List.of(List.of(), List.of(AcknowledgementRequest.of(DittoAcknowledgementLabel.TWIN_PERSISTED)));
    private static final List<Boolean> IS_SUCCESS = List.of(false, true);

    @Parameterized.Parameters(name = "timeout={0} response-required={1} requested-acks={2} is-success={3}")
    public static Collection<Object[]> getParameters() {
        return TIMEOUT.stream().flatMap(timeout ->
                RESPONSE_REQUIRED.stream().flatMap(responseRequired ->
                        REQUESTED_ACKS.stream().flatMap(requestedAcks ->
                                IS_SUCCESS.stream().map(isSuccess ->
                                        new Object[]{timeout, responseRequired, requestedAcks, isSuccess}
                                )
                        )
                )
        ).collect(Collectors.toList());
    }

    private final Duration timeout;
    private final boolean responseRequired;
    private final List<AcknowledgementRequest> requestedAcks;
    private final boolean isSuccess;

    private final List<ActorRef> createdActors = new ArrayList<>();
    private final TestProbe eventResponsePublisherProbe = TestProbe.apply("eventAndResponsePublisher", actorSystem);
    private final TestProbe commandRouterProbe = TestProbe.apply("commandRouter", actorSystem);
    private final TestProbe subscriptionManagerProbe = TestProbe.apply("subscriptionManager", actorSystem);
    private final DittoProtocolSub dittoProtocolSub = Mockito.mock(DittoProtocolSub.class);

    public StreamingSessionActorHeaderInteractionTest(final Duration timeout, final Boolean responseRequired,
            final List<AcknowledgementRequest> requestedAcks, final Boolean isSuccess) {
        this.timeout = timeout;
        this.responseRequired = responseRequired;
        this.requestedAcks = requestedAcks;
        this.isSuccess = isSuccess;
    }

    @BeforeClass
    public static void startActorSystem() {
        actorSystem = ActorSystem.create();
        actorSystem.eventStream().setLogLevel(Attributes.logLevelWarning());
    }

    @AfterClass
    public static void shutdown() {
        TestKit.shutdownActorSystem(actorSystem);
    }

    @After
    public void stopActors() {
        createdActors.forEach(actorSystem::stop);
    }

    @Test
    public void run() {
        new TestKit(actorSystem) {{
            final ActorRef underTest = createStreamingSessionActor();
            final ModifyThing modifyThing = getModifyThing();
            underTest.tell(IncomingSignal.of(modifyThing), getRef());
            final Optional<HttpStatusCode> expectedStatusCode = getExpectedOutcome();
            final boolean isBadRequest = expectedStatusCode.filter(HttpStatusCode.BAD_REQUEST::equals).isPresent();
            if (!isBadRequest) {
                commandRouterProbe.expectMsg(modifyThing);
                // Regardless whether downstream sends reply, streaming session actor should not publish response
                // or error when response-required = false.
                commandRouterProbe.reply(getModifyThingResponse(modifyThing));
            }
            if (expectedStatusCode.isPresent()) {
                final SessionedResponseErrorOrAck response =
                        eventResponsePublisherProbe.expectMsgClass(SessionedResponseErrorOrAck.class);
                assertThat(getStatusCode(response)).isEqualTo(expectedStatusCode.get());
            } else {
                eventResponsePublisherProbe.expectNoMessage((FiniteDuration) FiniteDuration.apply("250ms"));
            }
        }};
    }

    private ActorRef createStreamingSessionActor() {
        final Connect connect =
                new Connect(eventResponsePublisherProbe.ref(), "connectionCorrelationId", "ws",
                        JsonSchemaVersion.V_2, null);
        final Props props = StreamingSessionActor.props(connect, dittoProtocolSub, commandRouterProbe.ref(),
                DefaultAcknowledgementConfig.of(ConfigFactory.empty()), HeaderTranslator.empty(),
                Props.create(TestProbeForwarder.class, subscriptionManagerProbe), Mockito.mock(JwtValidator.class),
                Mockito.mock(JwtAuthenticationResultProvider.class));
        final ActorRef createdActor = actorSystem.actorOf(props);
        createdActors.add(createdActor);
        return createdActor;
    }

    private ModifyThing getModifyThing() {
        return ModifyThing.of(ThingId.of("thing:id"), Thing.newBuilder().build(), null, DittoHeaders.newBuilder()
                .timeout(timeout)
                .responseRequired(responseRequired)
                .acknowledgementRequests(requestedAcks)
                .build());
    }

    private Object getModifyThingResponse(final ModifyThing modifyThing) {
        return isSuccess
                ? ModifyThingResponse.modified(modifyThing.getThingEntityId(), modifyThing.getDittoHeaders())
                : ThingNotAccessibleException.newBuilder(modifyThing.getThingEntityId())
                .dittoHeaders(modifyThing.getDittoHeaders())
                .build();
    }

    private Optional<HttpStatusCode> getExpectedOutcome() {
        final Optional<HttpStatusCode> status;
        final HttpStatusCode successCode = HttpStatusCode.NO_CONTENT;
        final HttpStatusCode errorCode = HttpStatusCode.NOT_FOUND;
        final HttpStatusCode badRequest = HttpStatusCode.BAD_REQUEST;
        if (timeout.isZero()) {
            status = (responseRequired || !requestedAcks.isEmpty()) ? Optional.of(badRequest) : Optional.empty();
        } else {
            if (!responseRequired && !requestedAcks.isEmpty()) {
                // WS special case: no acks without response possible
                status = Optional.of(badRequest);
            } else if (isSuccess) {
                status = responseRequired ? Optional.of(successCode) : Optional.empty();
            } else {
                status = responseRequired ? Optional.of(errorCode) : Optional.empty();
            }
        }
        return status;
    }

    private static HttpStatusCode getStatusCode(final SessionedResponseErrorOrAck sessionedResponseErrorOrAck) {
        final Jsonifiable<?> jsonifiable = sessionedResponseErrorOrAck.getJsonifiable();
        if (jsonifiable instanceof DittoRuntimeException) {
            return ((DittoRuntimeException) jsonifiable).getStatusCode();
        } else {
            return ((CommandResponse<?>) jsonifiable).getStatusCode();
        }
    }

    private static final class TestProbeForwarder extends AbstractActor {

        final TestProbe testProbe;

        private TestProbeForwarder(final TestProbe testProbe) {
            this.testProbe = testProbe;
        }

        @Override
        public Receive createReceive() {
            return ReceiveBuilder.create().matchAny(msg -> testProbe.ref().forward(msg, getContext())).build();
        }
    }
}