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
package org.apache.aries.application.impl;

import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;

import org.apache.aries.application.Content;
import org.apache.aries.application.ServiceDeclaration;

/**
 * this class represents the Import-Services and Export-Services
 * in the Application.mf file
 *
 */
public class ServiceDeclarationImpl implements ServiceDeclaration
{
  private static final String FILTER = "filter";
  private String interfaceName;
  private Filter filter;
  
  /**
   * construct the ServiceDeclaration from the service string
   * @param service A single service string value from the Import-Services or Export-Services header
   * @throws InvalidSyntaxException
   */
  public ServiceDeclarationImpl(String service) throws InvalidSyntaxException 
  {
    Content content = new ContentImpl(service);
    this.interfaceName = content.getContentName();
    String filterString = content.getAttribute(FILTER);
    if (filterString != null) {
      try {
        this.filter = FrameworkUtil.createFilter(filterString);
      } catch (InvalidSyntaxException ise) {        
        throw new InvalidSyntaxException("Failed to create filter for " + service, ise.getFilter(), ise.getCause());
      }
    }
  }
  
  /* (non-Javadoc)
 * @see org.apache.aries.application.impl.ServiceDeclaration#getInterfaceName()
 */
  public String getInterfaceName() 
  {
    return this.interfaceName;
  }
  
  /* (non-Javadoc)
 * @see org.apache.aries.application.impl.ServiceDeclaration#getFilter()
 */
  public Filter getFilter() 
  {
    return this.filter;
  }
}
