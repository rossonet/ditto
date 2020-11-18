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
package org.eclipse.ditto.services.utils.pubsub.actors;

import java.util.Map;

import akka.actor.Address;
import scala.collection.immutable.Set;

/**
 * Notification that the distributed data changed.
 */
final class DDataChanged {

    private final Map<Address, Set<String>> multimap;

    DDataChanged(final Map<Address, Set<String>> multimap) {
        this.multimap = multimap;
    }

    /**
     * The changed distributed multimap as a Java map.
     *
     * @return the changed distributed data.
     */
    public Map<Address, Set<String>> getMultiMap() {
        return multimap;
    }
}
