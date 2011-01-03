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

import static org.osgi.jmx.framework.ServiceStateMBean.OBJECTNAME;

import javax.management.NotCompliantMBeanException;
import javax.management.StandardMBean;

import org.apache.aries.jmx.Logger;
import org.apache.aries.jmx.MBeanHandler;
import org.osgi.framework.BundleContext;
import org.osgi.jmx.framework.ServiceStateMBean;
import org.osgi.service.log.LogService;

/**
 * <p>
 * Implementation of <code>MBeanHandler</code> which manages the <code>ServiceState</code>
 * MBean implementation
 * @see MBeanHandler
 * </p> 
 *
 * @version $Rev$ $Date$
 */
public class ServiceStateMBeanHandler implements MBeanHandler {

    private String name;
    private StandardMBean mbean;
    private ServiceState serviceStateMBean;
    private BundleContext bundleContext;
    private Logger logger;
    
    
    public ServiceStateMBeanHandler(BundleContext bundleContext, Logger logger) {
        this.bundleContext = bundleContext;
        this.logger = logger;
        this.name = OBJECTNAME;
    }

    /**
     * @see org.apache.aries.jmx.MBeanHandler#open()
     */
    public void open() {
        serviceStateMBean = new ServiceState(bundleContext, logger);
        try {
            mbean = new RegistrableStandardEmitterMBean(serviceStateMBean, ServiceStateMBean.class);
        } catch (NotCompliantMBeanException e) {
            logger.log(LogService.LOG_ERROR, "Failed to instantiate MBean for " + ServiceStateMBean.class.getName(), e);
        }
    }
    
    /**
     * @see org.apache.aries.jmx.MBeanHandler#getMbean()
     */
    public StandardMBean getMbean() {
        return mbean;
    }

    /**
     * @see org.apache.aries.jmx.MBeanHandler#getName()
     */
    public String getName() {
        return name;
    }

    /**
     * @see org.apache.aries.jmx.MBeanHandler#close()
     */
    public void close() {
       // ensure dispatcher is shutdown even if postDeRegister is not honored
       if (serviceStateMBean != null) {
           serviceStateMBean.shutDownDispatcher();
       }
    }
    
    

}
