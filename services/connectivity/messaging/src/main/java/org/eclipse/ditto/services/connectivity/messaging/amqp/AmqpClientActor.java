/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
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
package org.eclipse.ditto.services.connectivity.messaging.amqp;

import java.net.URI;
import java.text.MessageFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;

import org.apache.qpid.jms.JmsConnection;
import org.apache.qpid.jms.JmsConnectionListener;
import org.apache.qpid.jms.message.JmsInboundMessageDispatch;
import org.apache.qpid.jms.provider.ProviderFactory;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.ConnectionConfigurationInvalidException;
import org.eclipse.ditto.model.connectivity.ConnectivityModelFactory;
import org.eclipse.ditto.model.connectivity.ConnectivityStatus;
import org.eclipse.ditto.services.connectivity.messaging.BaseClientActor;
import org.eclipse.ditto.services.connectivity.messaging.BaseClientData;
import org.eclipse.ditto.services.connectivity.messaging.BaseClientState;
import org.eclipse.ditto.services.connectivity.messaging.config.ConnectivityConfig;
import org.eclipse.ditto.services.connectivity.messaging.internal.AbstractWithOrigin;
import org.eclipse.ditto.services.connectivity.messaging.internal.ClientConnected;
import org.eclipse.ditto.services.connectivity.messaging.internal.ClientDisconnected;
import org.eclipse.ditto.services.connectivity.messaging.internal.ConnectClient;
import org.eclipse.ditto.services.connectivity.messaging.internal.ConnectionFailure;
import org.eclipse.ditto.services.connectivity.messaging.internal.DisconnectClient;
import org.eclipse.ditto.services.connectivity.messaging.internal.ImmutableConnectionFailure;
import org.eclipse.ditto.services.utils.akka.LogUtil;
import org.eclipse.ditto.services.utils.config.InstanceIdentifierSupplier;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionFailedException;

import akka.actor.ActorRef;
import akka.actor.FSM;
import akka.actor.Props;
import akka.actor.Status;
import akka.event.DiagnosticLoggingAdapter;
import akka.japi.pf.FSMStateFunctionBuilder;
import akka.pattern.Patterns;

/**
 * Actor which manages a connection to an AMQP 1.0 server using the Qpid JMS client.
 * This actor delegates interaction with the JMS client to a child actor because the JMS client blocks in most cases
 * which does not work well with actors.
 */
public final class AmqpClientActor extends BaseClientActor implements ExceptionListener {

    private final JmsConnectionFactory jmsConnectionFactory;
    private final StatusReportingListener connectionListener;
    private final List<ConsumerData> consumers;

    @Nullable private JmsConnection jmsConnection;
    @Nullable private Session jmsSession;
    @Nullable private ActorRef amqpPublisherActor;

    @Nullable private ActorRef testConnectionHandler;
    @Nullable private ActorRef connectConnectionHandler;
    @Nullable private ActorRef disconnectConnectionHandler;

    private final Map<String, ActorRef> consumerByNamePrefix;

    /*
     * This constructor is called via reflection by the static method propsForTest.
     */
    @SuppressWarnings("unused")
    private AmqpClientActor(final Connection connection,
            final ConnectivityStatus connectionStatus,
            final ConnectivityConfig connectivityConfig,
            final JmsConnectionFactory jmsConnectionFactory,
            final ActorRef conciergeForwarder) {

        super(connection, connectionStatus, connectivityConfig, conciergeForwarder);
        this.jmsConnectionFactory = jmsConnectionFactory;
        connectionListener = new StatusReportingListener(getSelf(), connection.getId(), log);
        consumers = new LinkedList<>();
        consumerByNamePrefix = new HashMap<>();
    }

    /*
     * This constructor is called via reflection by the static method props(Connection, ActorRef).
     */
    @SuppressWarnings("unused")
    private AmqpClientActor(final Connection connection,
            final ConnectivityStatus connectionStatus,
            final ConnectivityConfig connectivityConfig,
            final ActorRef conciergeForwarder) {

        this(connection, connectionStatus, connectivityConfig, ConnectionBasedJmsConnectionFactory.getInstance(),
                conciergeForwarder);
    }

    /**
     * Creates Akka configuration object for this actor.
     *
     * @param connection the connection.
     * @param connectivityConfig the configuration settings of the Connectivity service.
     * @param conciergeForwarder the actor used to send signals to the concierge service.
     * @return the Akka configuration Props object.
     */
    public static Props props(final Connection connection, final ConnectivityConfig connectivityConfig,
            final ActorRef conciergeForwarder) {

        return Props.create(AmqpClientActor.class, validateConnection(connection), connection.getConnectionStatus(),
                connectivityConfig, conciergeForwarder);
    }

    /**
     * Creates Akka configuration object for this actor.
     *
     * @param connection connection parameters.
     * @param connectionStatus the desired status of the connection.
     * @param connectivityConfig the configuration settings of the Connectivity service.
     * @param conciergeForwarder the actor used to send signals to the concierge service.
     * @param jmsConnectionFactory the JMS connection factory.
     * @return the Akka configuration Props object.
     */
    static Props propsForTests(final Connection connection,
            final ConnectivityStatus connectionStatus,
            final ConnectivityConfig connectivityConfig,
            final ActorRef conciergeForwarder,
            final JmsConnectionFactory jmsConnectionFactory) {

        return Props.create(AmqpClientActor.class, validateConnection(connection), connectionStatus, connectivityConfig,
                jmsConnectionFactory, conciergeForwarder);
    }

    private static Connection validateConnection(final Connection connection) {
        try {
            ProviderFactory.create(
                    URI.create(ConnectionBasedJmsConnectionFactory.buildAmqpConnectionUriFromConnection(connection)));
            return connection;
        } catch (final Exception e) {
            final String msgPattern = "Failed to instantiate an amqp provider from the given configuration: {0}";
            throw ConnectionConfigurationInvalidException
                    .newBuilder(MessageFormat.format(msgPattern, e.getMessage()))
                    .description(e.getMessage())
                    .cause(e)
                    .build();
        }
    }

    @Override
    public void postStop() {
        ensureJmsConnectionClosed();
        super.postStop();
    }

    @Override
    protected FSMStateFunctionBuilder<BaseClientState, BaseClientData> inAnyState() {
        return super.inAnyState()
                .event(StatusReport.class, this::handleStatusReport);
    }

    @Override
    protected CompletionStage<Status.Status> doTestConnection(final Connection connection) {
        // delegate to child actor because the QPID JMS client is blocking until connection is opened/closed
        return Patterns.ask(getTestConnectionHandler(connection),
                new JmsConnect(getSender()), Duration.ofSeconds(TEST_CONNECTION_TIMEOUT))
                // compose the disconnect because otherwise the actor hierarchy might be stopped too fast
                .thenCompose(response -> {
                    log.debug("Closing JMS connection after testing connection.");
                    if (response instanceof JmsConnected) {
                        final JmsConnection jmsConnection = ((JmsConnected) response).connection;
                        final JmsDisconnect jmsDisconnect = new JmsDisconnect(ActorRef.noSender(), jmsConnection);
                        return Patterns.ask(getDisconnectConnectionHandler(connection), jmsDisconnect,
                                Duration.ofSeconds(TEST_CONNECTION_TIMEOUT))
                                // replace jmsDisconnected message with original response
                                .thenApply(jmsDisconnected -> response);
                    } else {
                        return CompletableFuture.completedFuture(response);
                    }
                })
                .handle((response, throwable) -> {
                    if (throwable != null || response instanceof Status.Failure || response instanceof Throwable) {
                        final Throwable ex =
                                response instanceof Status.Failure ? ((Status.Failure) response).cause() :
                                        response instanceof Throwable ? (Throwable) response : throwable;
                        final ConnectionFailedException failedException =
                                ConnectionFailedException.newBuilder(connectionId())
                                        .description("The requested Connection could not be connected due to '" +
                                                ex.getClass().getSimpleName() + ": " + ex.getMessage() + "'")
                                        .cause(ex).build();
                        return new Status.Failure(failedException);
                    } else if (response instanceof ConnectionFailure) {
                        return ((ConnectionFailure) response).getFailure();
                    } else {
                        return new Status.Success(response);
                    }
                });
    }

    @Override
    protected void doConnectClient(final Connection connection, @Nullable final ActorRef origin) {
        // delegate to child actor because the QPID JMS client is blocking until connection is opened/closed
        getConnectConnectionHandler(connection).tell(new JmsConnect(origin), getSelf());
    }

    @Override
    protected void doDisconnectClient(final Connection connection, @Nullable final ActorRef origin) {
        // delegate to child actor because the QPID JMS client is blocking until connection is opened/closed
        getDisconnectConnectionHandler(connection)
                .tell(new JmsDisconnect(origin, jmsConnection), getSelf());
    }

    @Override
    protected void allocateResourcesOnConnection(final ClientConnected clientConnected) {
        if (clientConnected instanceof JmsConnected) {
            final JmsConnected c = (JmsConnected) clientConnected;
            log.info("Received JmsConnected");
            ensureJmsConnectionClosed();
            jmsConnection = c.connection;
            jmsConnection.addConnectionListener(connectionListener);
            jmsSession = c.session;
            consumers.clear();
            consumers.addAll(c.consumerList);
            // note: start order is important (publisher -> mapping -> consumer actor)
            startAmqpPublisherActor();
            startMessageMappingProcessor(connection().getMappingContext().orElse(null));
            startCommandConsumers(consumers);
        } else {
            log.info("ClientConnected was not JmsConnected as expected, ignoring as this probably was a reconnection");
        }
    }

    @Override
    protected void cleanupResourcesForConnection() {
        log.debug("cleaning up");
        stopCommandConsumers();
        stopMessageMappingProcessorActor();
        stopCommandProducer();
        // closing JMS connection closes all sessions and consumers
        ensureJmsConnectionClosed();
        jmsConnection = null;
        jmsSession = null;
        consumers.clear();
    }

    /*
     * Kill connection handlers on timeout to be able to handle the next command immediately.
     */
    @Override
    protected void cleanupFurtherResourcesOnConnectionTimeout(final BaseClientState currentState) {
        switch (currentState) {
            case CONNECTING:
                if (connectConnectionHandler != null) {
                    stopChildActor(connectConnectionHandler);
                    connectConnectionHandler = null;
                }
                break;
            case DISCONNECTING:
                if (disconnectConnectionHandler != null) {
                    stopChildActor(disconnectConnectionHandler);
                    disconnectConnectionHandler = null;
                }
                break;
            case TESTING:
            default:
                // no need to handle TESTING state because this actor stops after test timeout.
                break;
        }
        super.cleanupFurtherResourcesOnConnectionTimeout(currentState);
    }

    @Override
    protected ActorRef getPublisherActor() {
        return amqpPublisherActor;
    }

    @Override
    protected boolean isEventUpToDate(final Object event, final BaseClientState state, final ActorRef sender) {
        switch (state) {
            case CONNECTING:
                return Objects.equals(sender, connectConnectionHandler);
            case DISCONNECTING:
                return Objects.equals(sender, disconnectConnectionHandler);
            case TESTING:
            default:
                // no need to check testConnectionHandler because test runs only once during this actor's lifetime
                // ignore random events by default - they could come from a connection handler that is already dead
                return false;
        }
    }

    private void startCommandConsumers(final Iterable<ConsumerData> consumers) {
        final Optional<ActorRef> messageMappingProcessor = getMessageMappingProcessorActor();
        if (messageMappingProcessor.isPresent()) {
            if (isConsuming()) {
                stopCommandConsumers();
                consumers.forEach(consumer -> startCommandConsumer(consumer, messageMappingProcessor.get()));
                log.info("Subscribed Connection <{}> to sources: {}", connectionId(), consumers);
            } else {
                log.debug("Not starting consumers, no sources were configured");
            }
        } else {
            log.warning("The MessageMappingProcessor was not available and therefore no consumers were started!");
        }
    }

    private void startCommandConsumer(final ConsumerData consumer, final ActorRef messageMappingProcessor) {
        final String namePrefix = consumer.getActorNamePrefix();
        final Props props = AmqpConsumerActor.props(connectionId(), consumer.getAddress(),
                consumer.getMessageConsumer(),
                messageMappingProcessor, consumer.getSource());

        final ActorRef child = startChildActorConflictFree(namePrefix, props);
        consumerByNamePrefix.put(namePrefix, child);
    }

    private void startAmqpPublisherActor() {
        stopCommandProducer();
        final String namePrefix = AmqpPublisherActor.ACTOR_NAME;
        if (jmsSession != null) {
            final Props props = AmqpPublisherActor.props(connectionId(), getTargetsOrEmptyList(), jmsSession);
            amqpPublisherActor = startChildActorConflictFree(namePrefix, props);
        } else {
            throw ConnectionFailedException
                    .newBuilder(connectionId())
                    .message("Could not start publisher actor due to missing JMS session or connection!")
                    .build();
        }
    }

    private void stopCommandProducer() {
        if (amqpPublisherActor != null) {
            stopChildActor(amqpPublisherActor);
            amqpPublisherActor = null;
        }
    }

    private void stopCommandConsumers() {
        consumerByNamePrefix.forEach((namePrefix, child) -> {
            final String actorName = child.path().name();
            if (actorName.startsWith(AmqpConsumerActor.ACTOR_NAME_PREFIX)) {
                stopChildActor(child);
            }
        });
        consumerByNamePrefix.clear();
    }

    private ActorRef getTestConnectionHandler(final Connection connection) {
        if (testConnectionHandler == null) {
            testConnectionHandler = startConnectionHandlingActor("test", connection);
        }
        return testConnectionHandler;
    }

    private ActorRef getConnectConnectionHandler(final Connection connection) {
        if (connectConnectionHandler == null) {
            connectConnectionHandler = startConnectionHandlingActor("connect", connection);
        }
        return connectConnectionHandler;
    }

    private ActorRef getDisconnectConnectionHandler(final Connection connection) {
        if (disconnectConnectionHandler == null) {
            disconnectConnectionHandler = startConnectionHandlingActor("disconnect", connection);
        }
        return disconnectConnectionHandler;
    }

    private ActorRef startConnectionHandlingActor(final String suffix, final Connection connection) {
        final String namePrefix =
                JMSConnectionHandlingActor.ACTOR_NAME_PREFIX + escapeActorName(connectionId() + "-" + suffix);
        final Props props =
                JMSConnectionHandlingActor.propsWithOwnDispatcher(connection, this, jmsConnectionFactory);
        return startChildActorConflictFree(namePrefix, props);
    }

    /**
     * Close the JMS connection known to this actor in an isolated dispatcher because it is blocking.
     *
     * @return future where the closing operation executes.
     */
    @SuppressWarnings("UnusedReturnValue")
    private CompletableFuture<Void> ensureJmsConnectionClosed() {
        if (jmsConnection != null) {
            final JmsConnection jmsConnectionToClose = jmsConnection;
            final Runnable closeJmsConnectionRunnable = () -> {
                try {
                    jmsConnectionToClose.close();
                } catch (final Throwable error) {
                    // 'log' is final. It is okay to use it in a future.
                    log.error(error, "RESOURCE-LEAK: failed to close JMSConnection");
                    throw new RuntimeException(error);
                }
            };
            return CompletableFuture.runAsync(closeJmsConnectionRunnable,
                    JMSConnectionHandlingActor.getOwnDispatcher(getContext().system()));
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Set metrics for self and children when the status report listener sends a report.
     *
     * @param statusReport status report from status report listener.
     * @param currentData known data of the connection.
     * @return same FSM state with possibly updated connection status and status detail.
     */
    private FSM.State<BaseClientState, BaseClientData> handleStatusReport(final StatusReport statusReport,
            final BaseClientData currentData) {

        BaseClientData data = currentData;
        if (statusReport.isConnectionRestored()) {
            data = data.setConnectionStatus(ConnectivityStatus.OPEN)
                    .setConnectionStatusDetails("Connection restored");
        }

        final Optional<ConnectionFailure> possibleFailure = statusReport.getFailure();
        final Optional<MessageConsumer> possibleClosedConsumer = statusReport.getClosedConsumer();

        if (possibleFailure.isPresent()) {
            final ConnectionFailure failure = possibleFailure.get();
            final String message = MessageFormat.format("Failure: {0}, Description: {1}",
                    failure.getFailure().cause(), failure.getFailureDescription());
            data = data.setConnectionStatus(ConnectivityStatus.FAILED)
                    .setConnectionStatusDetails(message);
        }
        if (possibleClosedConsumer.isPresent()) {
            final MessageConsumer consumer = possibleClosedConsumer.get();

            consumers.stream()
                    .filter(c -> c.getMessageConsumer().equals(consumer))
                    .findFirst()
                    .ifPresent(c -> {
                        final ActorRef consumerActor = consumerByNamePrefix.get(c.getActorNamePrefix());
                        if (consumerActor != null) {
                            final Object message = ConnectivityModelFactory.newStatusUpdate(
                                    InstanceIdentifierSupplier.getInstance().get(),
                                    ConnectivityStatus.FAILED,
                                    c.getAddress(),
                                    "Consumer closed", Instant.now());
                            consumerActor.tell(message, ActorRef.noSender());
                        }
                    });
        }

        statusReport.getClosedProducer().ifPresent(p -> amqpPublisherActor.tell(statusReport, ActorRef.noSender()));

        return stay().using(data);
    }

    @Override
    public void onException(final JMSException exception) {
        log.warning("{} occurred: {}", exception.getClass().getName(), exception.getMessage());
    }

    /**
     * {@code Connect} message for internal communication with {@link JMSConnectionHandlingActor}.
     */
    static final class JmsConnect extends AbstractWithOrigin implements ConnectClient {

        JmsConnect(@Nullable final ActorRef origin) {
            super(origin);
        }

    }

    /**
     * {@code Disconnect} message for internal communication with {@link JMSConnectionHandlingActor}.
     */
    static final class JmsDisconnect extends AbstractWithOrigin implements DisconnectClient {

        @Nullable private final javax.jms.Connection connection;

        JmsDisconnect(@Nullable final ActorRef origin, @Nullable final javax.jms.Connection connection) {
            super(origin);
            this.connection = connection;
        }

        Optional<javax.jms.Connection> getConnection() {
            return Optional.ofNullable(connection);
        }

    }

    /**
     * Response to {@code Connect} message from {@link JMSConnectionHandlingActor}.
     */
    static final class JmsConnected extends AbstractWithOrigin implements ClientConnected {

        private final JmsConnection connection;
        private final Session session;
        private final List<ConsumerData> consumerList;

        JmsConnected(@Nullable final ActorRef origin,
                final JmsConnection connection,
                final Session session,
                final List<ConsumerData> consumerList) {

            super(origin);
            this.connection = connection;
            this.session = session;
            this.consumerList = consumerList;
        }
    }

    /**
     * Response to {@code Disconnect} message from {@link JMSConnectionHandlingActor}.
     */
    static class JmsDisconnected extends AbstractWithOrigin implements ClientDisconnected {

        JmsDisconnected(@Nullable final ActorRef origin) {
            super(origin);
        }

    }

    /**
     * Listener updates connection status for metrics reporting. Do not alter actor state.
     */
    private static final class StatusReportingListener implements JmsConnectionListener {

        private final ActorRef self;
        private final DiagnosticLoggingAdapter log;
        private final String connectionId;

        private StatusReportingListener(final ActorRef self, final String connectionId,
                final DiagnosticLoggingAdapter log) {

            this.self = self;
            this.connectionId = connectionId;
            this.log = log;
        }

        @Override
        public void onConnectionEstablished(final URI remoteURI) {
            log.info("Connection established: {}", remoteURI);
        }

        @Override
        public void onConnectionFailure(final Throwable error) {
            LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId);
            log.warning("Connection Failure: {}", error.getMessage());
            final ConnectionFailure failure =
                    new ImmutableConnectionFailure(ActorRef.noSender(), error, null);
            self.tell(StatusReport.failure(failure), ActorRef.noSender());
        }

        @Override
        public void onConnectionInterrupted(final URI remoteURI) {
            LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId);
            log.warning("Connection interrupted: {}", remoteURI);
            final ConnectionFailure failure =
                    new ImmutableConnectionFailure(ActorRef.noSender(), null, "JMS Interrupted");
            self.tell(StatusReport.failure(failure), ActorRef.noSender());
        }

        @Override
        public void onConnectionRestored(final URI remoteURI) {
            LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId);
            log.info("Connection restored: {}", remoteURI);
            self.tell(StatusReport.connectionRestored(), ActorRef.noSender());
        }

        @Override
        public void onInboundMessage(final JmsInboundMessageDispatch envelope) {
            LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId);
            log.debug("Inbound message: {}", envelope);
            self.tell(StatusReport.consumedMessage(), ActorRef.noSender());
        }

        @Override
        public void onSessionClosed(final Session session, final Throwable cause) {
            LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId);
            log.warning("Session closed: {} - {}", session, cause.getMessage());
            final ConnectionFailure failure =
                    new ImmutableConnectionFailure(ActorRef.noSender(), cause, "JMS Session closed");
            self.tell(StatusReport.failure(failure), ActorRef.noSender());
        }

        @Override
        public void onConsumerClosed(final MessageConsumer consumer, final Throwable cause) {
            LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId);
            log.warning("Consumer <{}> closed due to {}: {}", consumer.toString(),
                    cause.getClass().getSimpleName(), cause.getMessage());
            self.tell(StatusReport.consumerClosed(consumer), ActorRef.noSender());
        }

        @Override
        public void onProducerClosed(final MessageProducer producer, final Throwable cause) {
            LogUtil.enhanceLogWithCustomField(log, BaseClientData.MDC_CONNECTION_ID, connectionId);
            log.warning("Producer <{}> closed due to {}: {}", producer, cause.getClass().getSimpleName(),
                    cause.getMessage());
            self.tell(StatusReport.producerClosed(producer), ActorRef.noSender());
        }

    }

}
