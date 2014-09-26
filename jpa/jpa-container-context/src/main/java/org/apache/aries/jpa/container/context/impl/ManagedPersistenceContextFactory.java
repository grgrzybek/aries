/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIESOR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.jpa.container.context.impl;

import java.lang.reflect.Proxy;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.persistence.Cache;
import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;
import javax.persistence.PersistenceContextType;
import javax.persistence.PersistenceUnitUtil;
import javax.persistence.Query;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.metamodel.Metamodel;

import org.apache.aries.jpa.container.context.PersistenceContextProvider;
import org.apache.aries.jpa.container.context.impl.PersistenceContextManager.QuiesceTidyUp;
import org.apache.aries.jpa.container.context.transaction.impl.DestroyCallback;
import org.apache.aries.jpa.container.context.transaction.impl.JTAEntityManagerHandler;
import org.apache.aries.jpa.container.context.transaction.impl.JTAPersistenceContextRegistry;
import org.apache.aries.jpa.container.sync.Synchronization;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A factory that can lazily create managed persistence contexts.
 * This is registered in the Service registry to be looked up by blueprint.
 * The EntityManagerFactory interface is used to ensure a shared class space
 * with the client. Only the createEntityManager() method is supported.
 * <p/>
 * Also this class receives a callback on cleanup
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class ManagedPersistenceContextFactory implements Synchronization, EntityManagerFactory, DestroyCallback {
    /**
     * Logger
     */
    private static final Logger _logger = LoggerFactory.getLogger("org.apache.aries.jpa.container.context");

    private final ServiceReference emf;
    private final Map<String, Object> properties;
    private final JTAPersistenceContextRegistry registry;
    private final PersistenceContextType type;
    private final AtomicLong activeCount = new AtomicLong(0);
    private final String unitName;
    private final EntityManager em;

    private final AtomicReference<QuiesceTidyUp> tidyUp = new AtomicReference<QuiesceTidyUp>();

    public ManagedPersistenceContextFactory(String name, ServiceReference unit,
                                            Map<String, Object> props, JTAPersistenceContextRegistry contextRegistry) {
        unitName = name;
        emf = unit;
        //Take a copy of the Map so that we don't modify the original
        properties = new HashMap<String, Object>(props);
        registry = contextRegistry;
        //Remove our internal property so that it doesn't get passed on the createEntityManager call
        type = (PersistenceContextType) properties.remove(PersistenceContextProvider.PERSISTENCE_CONTEXT_TYPE);
        //Getting the BundleContext is a privileged operation that the
        //client might not be able to do.
        EntityManagerFactory factory = AccessController.doPrivileged(
                new PrivilegedAction<EntityManagerFactory>() {
                    public EntityManagerFactory run() {
                        return (EntityManagerFactory) emf.getBundle().getBundleContext().getService(emf);
                    }
                });
        JTAEntityManagerHandler invocationHandler = new JTAEntityManagerHandler(factory, properties, registry, activeCount, this);
        ClassLoader cl = this.getClass().getClassLoader();
        Class<?>[] ifAr = new Class[] { Synchronization.class, EntityManager.class }; 
        em = (EntityManager)Proxy.newProxyInstance(cl, ifAr, invocationHandler);
    }
    
    public EntityManager createEntityManager() {
        if (_logger.isDebugEnabled()) {
            _logger.debug("Creating a container managed entity manager for the perstence unit {} with the following properties {}",
                    new Object[]{emf, properties});
        }

        if (type == PersistenceContextType.TRANSACTION || type == null) {
            return em;
        } else {
            _logger.error(NLS.MESSAGES.getMessage("extended.em.not.supported"));
            return null;
        }

    }

    @Override
    public void preCall() {
        ((Synchronization)em).preCall();
    }

    @Override
    public void postCall() {
        ((Synchronization)em).postCall();
    }

    public void close() {
        throw new UnsupportedOperationException();
    }

    public EntityManager createEntityManager(Map arg0) {
        throw new UnsupportedOperationException();
    }

    public Cache getCache() {
        throw new UnsupportedOperationException();
    }

    public CriteriaBuilder getCriteriaBuilder() {
        throw new UnsupportedOperationException();
    }

    public Metamodel getMetamodel() {
        throw new UnsupportedOperationException();
    }

    public PersistenceUnitUtil getPersistenceUnitUtil() {
        throw new UnsupportedOperationException();
    }

    public Map<String, Object> getProperties() {
        throw new UnsupportedOperationException();
    }

    public boolean isOpen() {
        throw new UnsupportedOperationException();
    }

    /**
     * Register an async Quiesce operation with this peristence context
     *
     * @param tidyUp
     */
    public void quiesce(QuiesceTidyUp tidyUp) {
        this.tidyUp.compareAndSet(null, tidyUp);
        if (activeCount.get() == 0) {
            tidyUp.unitQuiesced(unitName);
        }
    }

    /**
     * Quiesce this unit after the last context is destroyed
     */
    public void callback() {
        if (tidyUp.get() != null && activeCount.get() == 0) {
            tidyUp.get().unitQuiesced(unitName);
        }
    }

    public void addNamedQuery(String arg0, Query arg1) {
        throw new UnsupportedOperationException();
    }

    public <T> T unwrap(Class<T> arg0) {
        throw new UnsupportedOperationException();
    }

}
