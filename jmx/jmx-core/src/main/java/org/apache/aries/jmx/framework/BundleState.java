/**
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.aries.jmx.framework;

import static org.apache.aries.jmx.util.FrameworkUtils.getBundleDependencies;
import static org.apache.aries.jmx.util.FrameworkUtils.getBundleExportedPackages;
import static org.apache.aries.jmx.util.FrameworkUtils.getBundleImportedPackages;
import static org.apache.aries.jmx.util.FrameworkUtils.getBundleState;
import static org.apache.aries.jmx.util.FrameworkUtils.getDependentBundles;
import static org.apache.aries.jmx.util.FrameworkUtils.getFragmentIds;
import static org.apache.aries.jmx.util.FrameworkUtils.getHostIds;
import static org.apache.aries.jmx.util.FrameworkUtils.getRegisteredServiceIds;
import static org.apache.aries.jmx.util.FrameworkUtils.getServicesInUseByBundle;
import static org.apache.aries.jmx.util.FrameworkUtils.isBundlePendingRemoval;
import static org.apache.aries.jmx.util.FrameworkUtils.isBundleRequiredByOthers;
import static org.apache.aries.jmx.util.FrameworkUtils.resolveBundle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.management.MBeanNotificationInfo;
import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.Notification;
import javax.management.NotificationBroadcasterSupport;
import javax.management.ObjectName;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;

import org.apache.aries.jmx.JMXThreadFactory;
import org.apache.aries.jmx.Logger;
import org.apache.aries.jmx.codec.BundleData;
import org.apache.aries.jmx.codec.BundleEventData;
import org.apache.aries.jmx.codec.BundleData.Header;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.jmx.framework.BundleStateMBean;
import org.osgi.service.log.LogService;
import org.osgi.service.packageadmin.PackageAdmin;
import org.osgi.service.startlevel.StartLevel;

/**
 * Implementation of <code>BundleStateMBean</code> which emits JMX <code>Notification</code> on <code>Bundle</code>
 * state changes
 * 
 * @version $Rev$ $Date$
 */
public class BundleState extends NotificationBroadcasterSupport implements BundleStateMBean, MBeanRegistration {

    protected Logger logger;
    protected BundleContext bundleContext;
    protected PackageAdmin packageAdmin;
    protected StartLevel startLevel;

    protected ExecutorService eventDispatcher;
    protected BundleListener bundleListener;
    private AtomicInteger notificationSequenceNumber = new AtomicInteger(1);
    private Lock lock = new ReentrantLock();
    private AtomicInteger registrations = new AtomicInteger(0);

    // notification type description
    public static String BUNDLE_EVENT = "org.osgi.bundle.event";

    public BundleState(BundleContext bundleContext, PackageAdmin packageAdmin, StartLevel startLevel, Logger logger) {
        this.bundleContext = bundleContext;
        this.packageAdmin = packageAdmin;
        this.startLevel = startLevel;
        this.logger = logger;
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getExportedPackages(long)
     */
    public String[] getExportedPackages(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return getBundleExportedPackages(bundle, packageAdmin);
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getFragments(long)
     */
    public long[] getFragments(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return getFragmentIds(bundle, packageAdmin);
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getHeaders(long)
     */
    @SuppressWarnings("unchecked")
    public TabularData getHeaders(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        List<Header> headers = new ArrayList<Header>();
        Dictionary<String, String> bundleHeaders = bundle.getHeaders();
        Enumeration<String> keys = bundleHeaders.keys();
        while (keys.hasMoreElements()) {
            String key = keys.nextElement();
            headers.add(new Header(key, bundleHeaders.get(key)));
        }
        TabularData headerTable = new TabularDataSupport(HEADERS_TYPE);
        for (Header header : headers) {
            headerTable.put(header.toCompositeData());
        }
        return headerTable;
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getHosts(long)
     */
    public long[] getHosts(long fragmentId) throws IOException, IllegalArgumentException {
        Bundle fragment = resolveBundle(bundleContext, fragmentId);
        return getHostIds(fragment, packageAdmin);
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getImportedPackages(long)
     */
    public String[] getImportedPackages(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return getBundleImportedPackages(bundleContext, bundle, packageAdmin);
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getLastModified(long)
     */
    public long getLastModified(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return bundle.getLastModified();
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getLocation(long)
     */
    public String getLocation(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return bundle.getLocation();
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getRegisteredServices(long)
     */
    public long[] getRegisteredServices(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return getRegisteredServiceIds(bundle);
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getRequiredBundles(long)
     */
    public long[] getRequiredBundles(long bundleIdentifier) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleIdentifier);
        return getBundleDependencies(bundleContext, bundle, packageAdmin);
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getRequiringBundles(long)
     */
    public long[] getRequiringBundles(long bundleIdentifier) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleIdentifier);
        return getDependentBundles(bundle, packageAdmin);
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getServicesInUse(long)
     */
    public long[] getServicesInUse(long bundleIdentifier) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleIdentifier);
        return getServicesInUseByBundle(bundle);
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getStartLevel(long)
     */
    public int getStartLevel(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return startLevel.getBundleStartLevel(bundle);
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getState(long)
     */
    public String getState(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return getBundleState(bundle);
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getSymbolicName(long)
     */
    public String getSymbolicName(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return bundle.getSymbolicName();
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#getVersion(long)
     */
    public String getVersion(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return bundle.getVersion().toString();
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#isFragment(long)
     */
    public boolean isFragment(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return (PackageAdmin.BUNDLE_TYPE_FRAGMENT == packageAdmin.getBundleType(bundle));
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#isPersistentlyStarted(long)
     */
    public boolean isPersistentlyStarted(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return startLevel.isBundlePersistentlyStarted(bundle);
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#isRemovalPending(long)
     */
    public boolean isRemovalPending(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return isBundlePendingRemoval(bundle, packageAdmin);
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#isRequired(long)
     */
    public boolean isRequired(long bundleId) throws IOException, IllegalArgumentException {
        Bundle bundle = resolveBundle(bundleContext, bundleId);
        return isBundleRequiredByOthers(bundle, packageAdmin);
    }

    /**
     * @see org.osgi.jmx.framework.BundleStateMBean#listBundles()
     */
    public TabularData listBundles() throws IOException {
        Bundle[] containerBundles = bundleContext.getBundles();
        List<BundleData> bundleDatas = new ArrayList<BundleData>();
        if (containerBundles != null) {
            for (Bundle containerBundle : containerBundles) {
                bundleDatas.add(new BundleData(bundleContext, containerBundle, packageAdmin, startLevel));
            } 
        }
        TabularData bundleTable = new TabularDataSupport(BUNDLES_TYPE);
        for (BundleData bundleData : bundleDatas) {
            bundleTable.put(bundleData.toCompositeData());
        }
        return bundleTable;
    }

    /**
     * @see javax.management.NotificationBroadcasterSupport#getNotificationInfo()
     */
    public MBeanNotificationInfo[] getNotificationInfo() {
        String[] types = new String[] { BUNDLE_EVENT };
        String name = Notification.class.getName();
        String description = "A BundleEvent issued from the Framework describing a bundle lifecycle change";
        MBeanNotificationInfo info = new MBeanNotificationInfo(types, name, description);
        return new MBeanNotificationInfo[] { info };
    }

    /**
     * @see javax.management.MBeanRegistration#postDeregister()
     */
    public void postDeregister() {
        if (registrations.decrementAndGet() < 1) {
            shutDownDispatcher();
        }
    }

    /**
     * @see javax.management.MBeanRegistration#postRegister(java.lang.Boolean)
     */
    public void postRegister(Boolean registrationDone) {
        if (registrationDone && registrations.incrementAndGet() == 1) {
            eventDispatcher = Executors.newSingleThreadExecutor(new JMXThreadFactory("JMX OSGi Bundle State Event Dispatcher"));
            bundleContext.addBundleListener(bundleListener);
        }
    }

    /**
     * @see javax.management.MBeanRegistration#preDeregister()
     */
    public void preDeregister() throws Exception {
        // No action
    }

    /**
     * @see javax.management.MBeanRegistration#preRegister(javax.management.MBeanServer, javax.management.ObjectName)
     */
    public ObjectName preRegister(MBeanServer server, ObjectName name) throws Exception {
        lock.lock();
        try {
            if (bundleListener == null) {
                bundleListener = new BundleListener() {
                    public void bundleChanged(BundleEvent event) {
                        final Notification notification = new Notification(EVENT, OBJECTNAME,
                                notificationSequenceNumber.getAndIncrement());
                        try {
                            notification.setUserData(new BundleEventData(event).toCompositeData());
                            eventDispatcher.submit(new Runnable() {
                                public void run() {
                                    sendNotification(notification);
                                }
                            });
                        } catch (RejectedExecutionException re) {
                            logger.log(LogService.LOG_WARNING, "Task rejected for JMX Notification dispatch of event ["
                                    + event + "] - Dispatcher may have been shutdown");
                        } catch (Exception e) {
                            logger.log(LogService.LOG_WARNING,
                                    "Exception occured on JMX Notification dispatch for event [" + event + "]", e);
                        }
                    }
                };
            }
        } finally {
            lock.unlock();
        }
        return name;
    }

    /*
     * Shuts down the notification dispatcher
     * [ARIES-259] MBeans not getting unregistered reliably
     */
    protected void shutDownDispatcher() {
        if (bundleListener != null) {
            try {
               bundleContext.removeBundleListener(bundleListener);
            }
            catch (Exception e) {
               // ignore
            }  
        }
        if (eventDispatcher != null) {
            eventDispatcher.shutdown(); 
        }
    }

    /*
     * Returns the ExecutorService used to dispatch Notifications
     */
    protected ExecutorService getEventDispatcher() {
        return eventDispatcher;
    }

}
