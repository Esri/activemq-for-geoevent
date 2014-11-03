/*
  Copyright 1995-2013 Esri

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

  For additional information, contact:
  Environmental Systems Research Institute, Inc.
  Attn: Contracts Dept
  380 New York Street
  Redlands, California, USA 92373

  email: contracts@esri.com
*/

package com.esri.geoevent.transport.activemq;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.esri.ges.core.property.PropertyDefinition;
import com.esri.ges.core.property.PropertyException;
import com.esri.ges.core.property.PropertyType;
import com.esri.ges.transport.TransportDefinitionBase;
import com.esri.ges.transport.TransportType;

public class ActiveMQInboundTransportDefinition extends TransportDefinitionBase
{
  final static private Log LOG = LogFactory.getLog(ActiveMQInboundTransportDefinition.class);

  public ActiveMQInboundTransportDefinition()
  {
    super(TransportType.INBOUND);
    try
    {
      propertyDefinitions.put("providerUrl", new PropertyDefinition("providerUrl", PropertyType.String, "tcp://localhost:61616", "Provider URL", "Provider URL", true, false));
      propertyDefinitions.put("destinationType", new PropertyDefinition("destinationType", PropertyType.String, "Queue", "JMS Destination Type", "JMS Destination Type", true, false));
      propertyDefinitions.put("destinationName", new PropertyDefinition("destinationName", PropertyType.String, null, "JMS Destination Name", "JMS Destination Name", true, false));
      propertyDefinitions.put("userName", new PropertyDefinition("userName", PropertyType.String, null, "User Name", "User Name", false, false));
      propertyDefinitions.put("password", new PropertyDefinition("password", PropertyType.Password, null, "Password", "Password", false, false));
    }
    catch (PropertyException e)
    {
      LOG.error("Failed to define properties of ActiveMQInboundTransportDefinition: ", e);
      throw new RuntimeException(e);
    }
  }

  @Override
  public String getName()
  {
    return "ActiveMQ";
  }

  @Override
  public String getLabel()
  {
    return "ActiveMQ Inbound Transport";
  }

  @Override
  public String getDomain()
  {
    return "com.esri.geoevent.transport.inbound";
  }

  @Override
  public String getDescription()
  {
    return "Esri JMS Inbound Transport for connecting to ActiveMQ message servers.";
  }
}
