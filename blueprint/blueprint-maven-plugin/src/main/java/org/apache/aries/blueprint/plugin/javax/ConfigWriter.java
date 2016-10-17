/**
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
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.blueprint.plugin.javax;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.aries.blueprint.api.config.Config;
import org.apache.aries.blueprint.plugin.spi.XmlWriter;

public class ConfigWriter implements XmlWriter {
    
    static final String CONFIG_NS = "http://aries.apache.org/blueprint/xmlns/blueprint-cm/v1.1.0";
    private Config config;

    public ConfigWriter(Config config) {
        this.config = config;
    }

    @Override
    public void write(XMLStreamWriter writer) throws XMLStreamException {
        writer.writeStartElement("property-placeholder");
        writer.writeDefaultNamespace(CONFIG_NS);
        writer.writeAttribute("persistent-id", config.pid());
        writer.writeAttribute("update-strategy", config.updatePolicy());
        writer.writeCharacters("\n");
        writer.writeEndElement();
    }

}
