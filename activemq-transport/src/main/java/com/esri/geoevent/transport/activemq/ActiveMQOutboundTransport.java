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

import com.esri.ges.core.component.ComponentException;
import com.esri.ges.core.component.RunningState;
import com.esri.ges.framework.i18n.BundleLogger;
import com.esri.ges.framework.i18n.BundleLoggerFactory;
import com.esri.ges.transport.OutboundTransportBase;
import com.esri.ges.transport.TransportDefinition;
import com.esri.ges.transport.TransportException;
import com.esri.ges.util.Validator;
import org.apache.activemq.ActiveMQConnectionFactory;

import javax.jms.*;
import java.nio.ByteBuffer;

public class ActiveMQOutboundTransport extends OutboundTransportBase implements Runnable {
  private static final BundleLogger LOGGER = BundleLoggerFactory.getLogger(ActiveMQOutboundTransport.class);

  private boolean persistent = false;
  private boolean transacted = false;
  private long timeToLive = 0;
  private String errorMessage;
  private Connection connection;
  private Session session;
  private MessageProducer messageProducer;
  private Thread starterThread;

  public ActiveMQOutboundTransport(TransportDefinition definition) throws ComponentException {
    super(definition);
  }

  @Override
  public void run() {
    setRunningState(RunningState.STARTED);
    while (isRunning()) {
      try {
        Thread.sleep(1000);
      } catch (InterruptedException ex) {
        ;
      }
    }
  }

  @Override
  public synchronized void receive(final ByteBuffer buffer, String channelId) {
    if (isRunning())
      new Thread(new MessageSender(buffer)).start();
  }

  @SuppressWarnings("incomplete-switch")
  public synchronized void start() {
    switch (getRunningState()) {
      case STARTING:
      case STARTED:
        return;
    }
    if (starterThread == null || !starterThread.isAlive()) {
      starterThread = new Thread(new ActiveMQByteOutboundTransportStarter(this, 60000));
      starterThread.start();
    }
  }

  @Override
  public synchronized void stop() {
    if (!RunningState.STOPPED.equals(getRunningState())) {
      setRunningState(RunningState.STOPPING);
      cleanup();
      setRunningState(RunningState.STOPPED);
      starterThread = null;
    }
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  private synchronized void setup() throws TransportException {
    try {
      if (getProperty("userName") != null && getProperty("password") != null) {
        ConnectionFactory factory = new ActiveMQConnectionFactory(getProperty("userName").getValueAsString(), getProperty("password").getDecryptedValue(), getProperty("providerUrl").getValueAsString());
        connection = factory.createConnection(getProperty("userName").getValueAsString(), getProperty("password").getDecryptedValue());
      } else {
        ConnectionFactory factory = new ActiveMQConnectionFactory(getProperty("providerUrl").getValueAsString());
        connection = factory.createConnection();
      }
      if (connection == null)
        throw new TransportException(LOGGER.translate("JMS_CONNECTION_FAILURE", getProperty("providerUrl").getValueAsString()));
      connection.setExceptionListener(new ExceptionListener() {
        @Override
        public void onException(JMSException exception) {
          setRunningState(RunningState.ERROR);
          errorMessage = exception.getMessage();
          LOGGER.error(errorMessage, exception);
        }
      });
      connection.start();
      session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
      ActiveMQDestinationType type = Validator.valueOfIgnoreCase(ActiveMQDestinationType.class, getProperty("destinationType").getValueAsString(), ActiveMQDestinationType.Queue);
      messageProducer = session.createProducer(type.equals(ActiveMQDestinationType.Topic) ? session.createTopic(getProperty("destinationName").getValueAsString()) : session.createQueue(getProperty("destinationName").getValueAsString()));
      messageProducer.setDeliveryMode((persistent) ? DeliveryMode.PERSISTENT : DeliveryMode.NON_PERSISTENT);
      if (timeToLive != 0)
        messageProducer.setTimeToLive(timeToLive);
    } catch (Exception e) {
      throw new TransportException(e.getMessage());
    }
  }

  private synchronized void cleanup() {
    try {
      if (messageProducer != null) {
        messageProducer.close();
        messageProducer = null;
      }
    } catch (Throwable ignore) {
      ;
    }
    try {
      if (session != null) {
        session.close();
        session = null;
      }
    } catch (Throwable ignore) {
      ;
    }
    try {
      if (connection != null) {
        connection.close();
        connection = null;
      }
    } catch (Throwable ignore) {
      ;
    }
  }

  private class MessageSender implements Runnable {
    private ByteBuffer buffer;

    public MessageSender(ByteBuffer buffer) {
      this.buffer = (buffer != null) ? buffer.duplicate() : null;
    }

    @Override
    public void run() {
      if (buffer != null) {
        try {
          TextMessage message = session.createTextMessage();
          message.clearBody();
          message.setText(new String(buffer.array(), "UTF-8"));
          messageProducer.send(message);
          if (transacted) {
            try {
              session.commit();
            } catch (JMSException error) {
              LOGGER.error(error.getMessage(), error);
            }
          }
        } catch (Exception error) {
          LOGGER.error(error.getMessage(), error);
        }
      }
    }
  }

  private class ActiveMQByteOutboundTransportStarter implements Runnable {
    private ActiveMQOutboundTransport transport;
    private long timeout;

    public ActiveMQByteOutboundTransportStarter(ActiveMQOutboundTransport transport, long timeout) {
      this.transport = transport;
      this.timeout = timeout;
    }

    @Override
    public void run() {
      while (starterThread != null) {
        switch (getRunningState()) {
          case STOPPING:
            break;
          case STOPPED:
          case ERROR: {
            try {
              setRunningState(RunningState.STARTING);
              setup();
              new Thread(transport).start();
            } catch (TransportException error) {
              errorMessage = error.getMessage();
              LOGGER.error(errorMessage, error);
              setRunningState(RunningState.ERROR);
              cleanup();
            }
          }
          break;
          default:
            errorMessage = "";
            break;
        }
        sleep();
      }
    }

    private void sleep() {
      try {
        Thread.sleep(timeout);
      } catch (InterruptedException e) {
        ;
      }
    }
  }
}
