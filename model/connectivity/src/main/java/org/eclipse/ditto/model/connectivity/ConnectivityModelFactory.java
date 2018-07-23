/*
 * Copyright (c) 2017 Bosch Software Innovations GmbH.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-2.0/index.php
 *
 * Contributors:
 *    Bosch Software Innovations GmbH - initial contribution
 */
package org.eclipse.ditto.model.connectivity;

import static org.eclipse.ditto.model.connectivity.ImmutableSource.DEFAULT_CONSUMER_COUNT;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import org.eclipse.ditto.json.JsonObject;
import org.eclipse.ditto.model.base.auth.AuthorizationContext;
import org.eclipse.ditto.model.base.auth.AuthorizationModelFactory;

/**
 * Factory to create new {@link Connection} instances.
 */
@Immutable
public final class ConnectivityModelFactory {

    private ConnectivityModelFactory() {
        throw new AssertionError();
    }

    /**
     * Returns a new {@code ConnectionBuilder} with the required fields set.
     *
     * @param id the connection identifier.
     * @param connectionType the connection type.
     * @param connectionStatus the connection status.
     * @param uri the connection URI.
     * @return the ConnectionBuilder.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public static ConnectionBuilder newConnectionBuilder(final String id,
            final ConnectionType connectionType,
            final ConnectionStatus connectionStatus,
            final String uri) {
        return ImmutableConnection.getBuilder(id, connectionType, connectionStatus, uri);
    }

    /**
     * Returns a mutable builder with a fluent API for an immutable {@link Connection}. The builder is initialised with
     * the
     * values of the given Connection.
     *
     * @param connection the Connection which provides the initial values of the builder.
     * @return the new builder.
     * @throws NullPointerException if {@code connection} is {@code null}.
     */
    public static ConnectionBuilder newConnectionBuilder(final Connection connection) {
        return ImmutableConnection.getBuilder(connection);
    }

    /**
     * Creates a new {@code Connection} object from the specified JSON object.
     *
     * @param jsonObject a JSON object which provides the data for the Connection to be created.
     * @return a new Connection which is initialised with the extracted data from {@code jsonObject}.
     * @throws NullPointerException if {@code jsonObject} is {@code null}.
     * @throws org.eclipse.ditto.json.JsonParseException if {@code jsonObject} is not an appropriate JSON object.
     */
    public static Connection connectionFromJson(final JsonObject jsonObject) {
        return ImmutableConnection.fromJson(jsonObject);
    }

    /**
     * Creates a new {@code ConnectionMetrics} object from the specified JSON object.
     *
     * @param jsonObject a JSON object which provides the data for the Connection to be created.
     * @return a new ConnectionMetrics which is initialised with the extracted data from {@code jsonObject}.
     * @throws NullPointerException if {@code jsonObject} is {@code null}.
     * @throws org.eclipse.ditto.json.JsonParseException if {@code jsonObject} is not an appropriate JSON object.
     */
    public static ConnectionMetrics connectionMetricsFromJson(final JsonObject jsonObject) {
        return ImmutableConnectionMetrics.fromJson(jsonObject);
    }

    /**
     * Returns a new {@code ConnectionMetrics}.
     *
     * @param connectionStatus the ConnectionStatus of the metrics to create
     * @param connectionStatusDetails the optional details about the connection status
     * @param inConnectionStatusSince the instant since when the Client is in its current ConnectionStatus
     * @param clientState the current state of the Client performing the connection
     * @param sourcesMetrics the metrics of all sources of the Connection
     * @param targetsMetrics the metrics of all targets of the Connection
     * @return a new ConnectionMetrics which is initialised with the extracted data from {@code jsonObject}.
     * @throws NullPointerException if {@code connectionStatus} is {@code null}.
     */
    public static ConnectionMetrics newConnectionMetrics(final ConnectionStatus connectionStatus,
            final @Nullable String connectionStatusDetails, final Instant inConnectionStatusSince,
            final String clientState,
            final List<SourceMetrics> sourcesMetrics, final List<TargetMetrics> targetsMetrics) {
        return ImmutableConnectionMetrics.of(connectionStatus, connectionStatusDetails, inConnectionStatusSince,
                clientState, sourcesMetrics, targetsMetrics);
    }

    /**
     * Returns a new {@code SourceMetrics}.
     *
     * @param addressMetrics the AddressMetrics of all addresses in the source
     * @param consumedMessages the amount of consumed messages
     * @return a new SourceMetrics which is initialised with the extracted data from {@code jsonObject}.
     * @throws NullPointerException if {@code connectionStatus} is {@code null}.
     */
    public static SourceMetrics newSourceMetrics(final Map<String, AddressMetric> addressMetrics,
            final long consumedMessages) {
        return ImmutableSourceMetrics.of(addressMetrics, consumedMessages);
    }

    /**
     * Creates a new {@code SourceMetrics} object from the specified JSON object.
     *
     * @param jsonObject a JSON object which provides the data for the Connection to be created.
     * @return a new SourceMetrics which is initialised with the extracted data from {@code jsonObject}.
     * @throws NullPointerException if {@code jsonObject} is {@code null}.
     * @throws org.eclipse.ditto.json.JsonParseException if {@code jsonObject} is not an appropriate JSON object.
     */
    public static SourceMetrics sourceMetricsFromJson(final JsonObject jsonObject) {
        return ImmutableSourceMetrics.fromJson(jsonObject);
    }

    /**
     * Returns a new {@code TargetMetrics}.
     *
     * @param addressMetrics the AddressMetrics of all addresses in the target
     * @param publishedMessages the amount of published messages
     * @return a new SourceMetrics which is initialised with the extracted data from {@code jsonObject}.
     * @throws NullPointerException if {@code connectionStatus} is {@code null}.
     */
    public static TargetMetrics newTargetMetrics(final Map<String, AddressMetric> addressMetrics,
            final long publishedMessages) {
        return ImmutableTargetMetrics.of(addressMetrics, publishedMessages);
    }

    /**
     * Creates a new {@code TargetMetrics} object from the specified JSON object.
     *
     * @param jsonObject a JSON object which provides the data for the Connection to be created.
     * @return a new TargetMetrics which is initialised with the extracted data from {@code jsonObject}.
     * @throws NullPointerException if {@code jsonObject} is {@code null}.
     * @throws org.eclipse.ditto.json.JsonParseException if {@code jsonObject} is not an appropriate JSON object.
     */
    public static TargetMetrics targetMetricsFromJson(final JsonObject jsonObject) {
        return ImmutableTargetMetrics.fromJson(jsonObject);
    }

    /**
     * Returns a new {@code AddressMetric}.
     *
     * @param status the ConnectionStatus of the source metrics to create
     * @param statusDetails the optional details about the connection status
     * @param messageCount the amount of totally consumed/published messages
     * @param lastMessageAt the timestamp when the last message was consumed/published
     * @return a new AddressMetric which is initialised with the extracted data from {@code jsonObject}.
     * @throws NullPointerException if any parameter is {@code null}.
     */
    public static AddressMetric newAddressMetric(final ConnectionStatus status, @Nullable final String statusDetails,
            final long messageCount, @Nullable final Instant lastMessageAt) {
        return ImmutableAddressMetric.of(status, statusDetails, messageCount, lastMessageAt);
    }

    /**
     * Creates a new {@code AddressMetric} object from the specified JSON object.
     *
     * @param jsonObject a JSON object which provides the data for the Connection to be created.
     * @return a new AddressMetric which is initialised with the extracted data from {@code jsonObject}.
     * @throws NullPointerException if {@code jsonObject} is {@code null}.
     * @throws org.eclipse.ditto.json.JsonParseException if {@code jsonObject} is not an appropriate JSON object.
     */
    public static AddressMetric addressMetricFromJson(final JsonObject jsonObject) {
        return ImmutableAddressMetric.fromJson(jsonObject);
    }

    /**
     * Returns a new {@code MappingContext}.
     *
     * @param mappingEngine fully qualified classname of a mapping engine
     * @param options the mapping options required to instantiate a mapper
     * @return the created MappingContext.
     * @throws NullPointerException if any argument is {@code null}.
     */
    public static MappingContext newMappingContext(final String mappingEngine, final Map<String, String> options) {
        return ImmutableMappingContext.of(mappingEngine, options);
    }

    /**
     * Creates a new {@code MappingContext} object from the specified JSON object.
     *
     * @param jsonObject a JSON object which provides the data for the MappingContext to be created.
     * @return a new MappingContext which is initialised with the extracted data from {@code jsonObject}.
     * @throws NullPointerException if {@code jsonObject} is {@code null}.
     * @throws org.eclipse.ditto.json.JsonParseException if {@code jsonObject} is not an appropriate JSON object.
     */
    public static MappingContext mappingContextFromJson(final JsonObject jsonObject) {
        return ImmutableMappingContext.fromJson(jsonObject);
    }

    /**
     * Creates a new ExternalMessageBuilder initialized with the passed {@code headers}.
     *
     * @param headers the headers to initialize the builder with.
     * @return the builder.
     */
    public static ExternalMessageBuilder newExternalMessageBuilder(final Map<String, String> headers) {
        return new MutableExternalMessageBuilder(headers);
    }

    /**
     * Creates a new ExternalMessageBuilder based on the passed existing {@code externalMessage}.
     *
     * @param externalMessage the ExternalMessage initialize the builder with.
     * @return the builder.
     */
    public static ExternalMessageBuilder newExternalMessageBuilder(final ExternalMessage externalMessage) {
        return new MutableExternalMessageBuilder(externalMessage);
    }

    /**
     * Creates a new {@link Source}.
     *
     * @param addresses the source addresses where messages are consumed from
     * @return the created {@link Source}
     */
    public static Source newSource(final Set<String> addresses) {
        return new ImmutableSource(addresses, DEFAULT_CONSUMER_COUNT, AuthorizationModelFactory.emptyAuthContext(), 0);
    }

    /**
     * Creates a new {@link Source}.
     *
     * @param addresses the source addresses where messages are consumed from
     * @param consumerCount how many consumer will consume of the new {@link Source}
     * @return the created {@link Source}
     */
    public static Source newSource(final Set<String> addresses, final int consumerCount) {
        return new ImmutableSource(addresses, consumerCount, AuthorizationModelFactory.emptyAuthContext(), 0);
    }

    /**
     * Creates a new {@link Source}.
     *
     * @param addresses the source addresses where messages are consumed from
     * @param consumerCount how many consumer will consume of the new {@link Source}
     * @param authorizationContext the authorization context
     * @return the created {@link Source}
     */
    public static Source newSource(final Set<String> addresses, final int consumerCount,
            final AuthorizationContext authorizationContext) {
        return new ImmutableSource(addresses, consumerCount, authorizationContext, 0);
    }

    /**
     * Creates a new {@link Source}.
     *
     * @param sources the sources where messages are consumed from
     * @return the created {@link Source}
     */
    public static Source newSource(final int index, final String... sources) {
        return new ImmutableSource(new HashSet<>(Arrays.asList(sources)), DEFAULT_CONSUMER_COUNT,
                AuthorizationModelFactory.emptyAuthContext(), index);
    }

    /**
     * Creates a new {@link Source}.
     *
     * @param consumerCount how many consumer will consume from this source
     * @param sources the sources where messages are consumed from
     * @return the created {@link Source}
     */
    public static Source newSource(final int consumerCount, final int index, final String... sources) {
        return new ImmutableSource(new HashSet<>(Arrays.asList(sources)), consumerCount,
                AuthorizationModelFactory.emptyAuthContext(), index);
    }

    /**
     * Creates a new {@link Source}.
     *
     * @param consumerCount how many consumer will consume from this source
     * @param authorizationContext the authorization context of the new {@link Source}
     * @param sources the sources where messages are consumed from
     * @return the created {@link Source}
     */
    public static Source newSource(final int consumerCount, final int index,
            final AuthorizationContext authorizationContext,
            final String... sources) {
        return new ImmutableSource(new HashSet<>(Arrays.asList(sources)), consumerCount, authorizationContext, index);
    }

    /**
     * Creates a new {@link Target}.
     *
     * @param address the address where the signals will be published
     * @param topics the topics for which this target will receive signals
     * @return the created {@link Target}
     */
    public static Target newTarget(final String address, final Set<Topic> topics) {
        return new ImmutableTarget(address, topics, AuthorizationModelFactory.emptyAuthContext());
    }

    /**
     * Creates a new {@link Target}.
     *
     * @param address the address where the signals will be published
     * @param topics the topics for which this target will receive signals
     * @param authorizationContext the authorization context of the new {@link Target}
     * @return the created {@link Target}
     */
    public static Target newTarget(final String address, final Set<Topic> topics,
            final AuthorizationContext authorizationContext) {
        return new ImmutableTarget(address, topics, authorizationContext);
    }

    /**
     * Creates a new {@link Target}.
     *
     * @param address the address where the signals will be published
     * @param requiredTopic the required topic that should be published via this target
     * @param additionalTopics additional set of topics that should be published via this target
     * @return the created {@link Target}
     */
    public static Target newTarget(final String address, final Topic requiredTopic, final Topic... additionalTopics) {
        return newTarget(address, AuthorizationModelFactory.emptyAuthContext(), requiredTopic, additionalTopics);
    }

    /**
     * Creates a new {@link Target} from existing target but different address.
     *
     * @param target the target
     * @param address the address where the signals will be published
     * @return the created {@link Target}
     */
    public static Target newTarget(final Target target, final String address) {
        return newTarget(address, target.getTopics(), target.getAuthorizationContext());
    }

    /**
     * Creates a new {@link Target}.
     *
     * @param address the address where the signals will be published
     * @param authorizationContext the authorization context of the new {@link Target}
     * @param requiredTopic the required topic that should be published via this target
     * @param additionalTopics additional set of topics that should be published via this target
     * @return the created {@link Target}
     */
    public static Target newTarget(final String address, final AuthorizationContext authorizationContext,
            final Topic requiredTopic, final Topic... additionalTopics) {
        final HashSet<Topic> topics = new HashSet<>(Collections.singletonList(requiredTopic));
        topics.addAll(Arrays.asList(additionalTopics));
        return new ImmutableTarget(address, topics, authorizationContext);
    }
}
