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

import com.esri.ges.core.property.LabeledValue;
import com.esri.ges.core.property.PropertyDefinition;
import com.esri.ges.core.property.PropertyException;
import com.esri.ges.core.property.PropertyType;
import com.esri.ges.framework.i18n.BundleLogger;
import com.esri.ges.framework.i18n.BundleLoggerFactory;
import com.esri.ges.transport.TransportDefinitionBase;
import com.esri.ges.transport.TransportType;

import java.util.ArrayList;
import java.util.List;

public class ActiveMQOutboundTransportDefinition extends TransportDefinitionBase {
  private static final BundleLogger LOGGER = BundleLoggerFactory.getLogger(ActiveMQOutboundTransportDefinition.class);

  public ActiveMQOutboundTransportDefinition() {
    super(TransportType.OUTBOUND);
    try {
      propertyDefinitions.put("providerUrl", new PropertyDefinition("providerUrl", PropertyType.String, "tcp://localhost:61616", "${com.esri.geoevent.transport.activemq-transport.TRANSPORT_OUT_PROVIDER_URL_LBL}", "${com.esri.geoevent.transport.activemq-transport.TRANSPORT_OUT_PROVIDER_URL_DESC}", true, false));
      List<LabeledValue> allowedValues = new ArrayList<>(2);
      allowedValues.add(new LabeledValue("Queue", "Queue"));
      allowedValues.add(new LabeledValue("Topic", "Topic"));
      propertyDefinitions.put("destinationType", new PropertyDefinition("destinationType", PropertyType.String, "Queue", "${com.esri.geoevent.transport.activemq-transport.TRANSPORT_OUT_JMS_DESTINATION_TYPE_LBL}", "${com.esri.geoevent.transport.activemq-transport.TRANSPORT_OUT_JMS_DESTINATION_TYPE_DESC}", true, false, allowedValues));
      propertyDefinitions.put("destinationName", new PropertyDefinition("destinationName", PropertyType.String, null, "${com.esri.geoevent.transport.activemq-transport.TRANSPORT_OUT_JMS_DESTINATION_NAME_LBL}", "${com.esri.geoevent.transport.activemq-transport.TRANSPORT_OUT_JMS_DESTINATION_NAME_DESC}", true, false));
      propertyDefinitions.put("userName", new PropertyDefinition("userName", PropertyType.String, null, "${com.esri.geoevent.transport.activemq-transport.TRANSPORT_OUT_USERNAME_LBL}", "${com.esri.geoevent.transport.activemq-transport.TRANSPORT_OUT_USERNAME_DESC}", false, false));
      propertyDefinitions.put("password", new PropertyDefinition("password", PropertyType.Password, null, "${com.esri.geoevent.transport.activemq-transport.TRANSPORT_OUT_PASSWORD_LBL}", "${com.esri.geoevent.transport.activemq-transport.TRANSPORT_OUT_PASSWORD_DESC}", false, false));
    } catch (PropertyException error) {
      String errorMsg = LOGGER.translate("OUT_INIT_ERROR", error.getMessage());
      LOGGER.error(errorMsg, error);
      throw new RuntimeException(errorMsg, error);
    }
  }

  @Override
  public String getName() {
    return "ActiveMQ";
  }

  @Override
  public String getDomain() {
    return "com.esri.geoevent.transport.outbound";
  }

  @Override
  public String getVersion() {
    return "10.3.0";
  }

  @Override
  public String getLabel() {
    return "${com.esri.geoevent.transport.activemq-transport.TRANSPORT_OUT_LABEL}";
  }

  @Override
  public String getDescription() {
    return "${com.esri.geoevent.transport.activemq-transport.TRANSPORT_OUT_DESC}";
  }
}
