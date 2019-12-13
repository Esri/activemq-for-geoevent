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

import com.esri.ges.core.component.ClusterCommand;
import com.esri.ges.core.component.ComponentException;
import com.esri.ges.core.component.RunningState;
import com.esri.ges.core.property.Property;
import com.esri.ges.framework.i18n.BundleLogger;
import com.esri.ges.framework.i18n.BundleLoggerFactory;
import com.esri.ges.transport.InboundTransportBase;
import com.esri.ges.transport.TransportDefinition;
import com.esri.ges.transport.TransportException;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQSslConnectionFactory;
import org.apache.activemq.transport.TransportListener;

import javax.jms.*;
import java.io.*;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class ActiveMQInboundTransport extends InboundTransportBase implements Runnable {
  private static final BundleLogger LOGGER = BundleLoggerFactory.getLogger(ActiveMQInboundTransport.class);

  private volatile String errorMessage;
  private volatile Connection connection;
  private Session session;
  private volatile MessageConsumer messageConsumer;
  private volatile Thread starterThread;
  private final String channelId;
  private final Object syncLock = new Object();

  public ActiveMQInboundTransport(TransportDefinition definition) throws ComponentException {
    super(definition);
    // create a channel id per instance
    channelId = UUID.randomUUID().toString();
  }

  @Override
  public void run() {
    final MessageConsumer consumer = messageConsumer;
    if (consumer == null) {
      LOGGER.warn("ActiveMQ Message Consumer was found uninitialized when worker thread started. Aborting.");
      return;
    }

    setRunningState(RunningState.STARTED);
    while (isRunning() && !Thread.interrupted()) {
      try {
        if (consumer != messageConsumer) {
          // may occur when cleanup() is called from elsewhere before thread has stopped.
          if (isRunning() && !Thread.interrupted()) {
            LOGGER.warn("ActiveMQ Message Consumer was closed before worker thread was signalled to stop.");
          }
          return;
        }
        Message message = consumer.receive(2500); // interruptible, so a longer timeout should be OK now
        if (message != null) {
          if (message instanceof TextMessage) {
            TextMessage textMessage = (TextMessage) message;
            byte[] bytes = {};
            try {
              bytes = textMessage.getText().getBytes(StandardCharsets.UTF_8);
            } catch (Throwable error) {
              LOGGER.error("MESSAGE_DECODING_ERROR", error, error.getMessage());
              //LOGGER.info(error.getMessage(), error);
            }
            receive(bytes);
          } else if (message instanceof BytesMessage) {
            BytesMessage bytesMessage = (BytesMessage) message;
            byte[] bytes = new byte[(int) bytesMessage.getBodyLength()];
            bytesMessage.readBytes(bytes);
            receive(bytes);
          } else if (message instanceof ObjectMessage) {
            ObjectMessage objectMessage = (ObjectMessage) message;
            Serializable object = objectMessage.getObject();
            ByteArrayOutputStream baos = null;
            ObjectOutput output = null;
            try {
              baos = new ByteArrayOutputStream();
              output = new ObjectOutputStream(baos);
              output.writeObject(object);
              receive(baos.toByteArray());
            } catch (IOException error) {
              LOGGER.error(error.getMessage(), error);
            } finally {
              if (output != null) {
                try {
                  output.close();
                } catch (IOException ex) {
                  ;
                }
              }
              if (baos != null) {
                try {
                  baos.close();
                } catch (IOException ex) {
                  ;
                }
              }
            }
          }
        }
      } catch (JMSException error) {
        if ("java.lang.InterruptedException".equals(error.getMessage())) {
          return;
        }
        LOGGER.error(error.getMessage(), error);
      }
    }
  }

  private void receive(byte[] bytes) {
    if (bytes != null && bytes.length > 0) {
      ByteBuffer bb = ByteBuffer.allocate(bytes.length);
      bb.put(bytes);
      bb.flip();
      byteListener.receive(bb, channelId);
      bb.clear();
    }
  }

  @Override
  public void start() {
    synchronized (syncLock) {
      switch (getRunningState()) {
        case STARTING:
        case STARTED:
          LOGGER.warn("Ignoring a request to start an ActiveMQ Input when it was already in a starting or started state.");
          return;
        default:
          break;
      }

      String validationErrorMessage = checkPropertiesForErrors();
      if (validationErrorMessage != null) {
        // don't bother to launch starter / monitor thread if validation fails; it will just cause repeated errors.
        if (!validationErrorMessage.equals(errorMessage)) {
          LOGGER.error("Could not start ActiveMQ Input due to invalid properties: " + validationErrorMessage);
          errorMessage = validationErrorMessage;
        }
        resetStarterThread(0); // in case of any existing monitoring thread
        cleanup();
        setRunningState(RunningState.ERROR);
        return;
      }

      if (starterThread != null) {
        if (errorMessage != null && !errorMessage.isEmpty()) {
          resetStarterThread(1000);
        } else {
          LOGGER.warn("Ignoring a request to start an ActiveMQ Input when it already appears to be monitoring for recovery.");
          return;
        }
      }

      cleanup();
      errorMessage = "";
      setRunningState(RunningState.STARTING);
      starterThread = new Thread(new ActiveMQByteInboundTransportStarter(this, 30000));
      starterThread.start();
    }
  }

  private void resetStarterThread(long waitMillis) {
    final Thread oldStarterThread = starterThread;

    if (oldStarterThread != null) {
      oldStarterThread.interrupt(); // even if called from current thread

      synchronized (syncLock) {
        if (oldStarterThread == starterThread) {
          starterThread = null;
        } else if (starterThread != null) {
          LOGGER.warn("A new starter thread was launched while old starter thread was being reset.");
          return;
        }
      }

      if (waitMillis > 0 && oldStarterThread == Thread.currentThread()) {
        try {
          oldStarterThread.join(waitMillis);
          if (oldStarterThread.isAlive()) {
            LOGGER.warn("Wait timeout (" + waitMillis + " ms) expired after requesting interruption on old starter thread.");
          }
        } catch (InterruptedException e) {
          // ignore
        }
      }
    }
  }

  @Override
  public void stop() {
    resetStarterThread(1000);
    cleanup();
    setRunningState(RunningState.STOPPED);
  }

  public String getErrorMessage() {
    return errorMessage;
  }

	/*
  @Override
	final public void validate() throws ValidationException {
		super.validate();
		// It seems to cause issues if the default implementation is overridden
		// and does throw a ValidationException, although this is the normal way
		// to indicate a validation error. (See also checkPropertiesForErrors.)
		//
		// However, if validate() does not raise ValidationException, validation
		// errors are not displayed when editing an input in GeoEvent Manager.
	}
	*/

  private String checkPropertiesForErrors() {
    // This is used instead of overriding validate() since that gets called
    // from somewhere in the REST admin API implementation where it does not
    // properly trap for the ValidationException.
    //
    // If this issue is remedied, validate() can be implemented as follows:

    // String validationErrorMessage = checkPropertiesForErrors();
    // if (validationErrorMessage != null)
    //   throw new ValidationException(validationErrorMessage);

    try {
      String destType = getProperty("destinationType").getValueAsString();
      if ("topic".equalsIgnoreCase(destType) || "queue".equalsIgnoreCase(destType) || destType == null || destType.isEmpty()) {
        String destName = getProperty("destinationName").getValueAsString();
        if (destName != null && !destName.isEmpty()) {
          int queryPos = destName.indexOf('?');
          if (queryPos >= 0) {
            try {
              URLDecoder.decode(destName.substring(queryPos), "UTF-8");
            } catch (Exception e) {
              return "Optional parameters in Destination Name are not escaped properly and cannot be decoded.";
            }
          }
        } else {
          return "Destination Name must be configured. Current value is null or blank.";
        }
      } else {
        return "A valid Destination Type must be configured. Acceptable values are 'queue' and 'topic' (case insensitive), or else leave blank for default ('queue').";
      }
    } catch (Exception e) {
      return "An unexpected error occurred while checking properties: " + e.getMessage();
    }
    return null;
  }

  private boolean setup() throws JMSException, TransportException {
    String tempVal;
    try {
      ActiveMQSslConnectionFactory factory = new ActiveMQSslConnectionFactory(getProperty("providerUrl").getValueAsString());
      // tempVal = System.getenv("ARTEMIS_TS_PATH");
      tempVal = getProperty("artemisTSPath").getValueAsString();
      if (tempVal != null && !tempVal.isEmpty()){
         // If there's a truststore path, then this is an ssl connection.
        try {
	    factory.setTrustStore(tempVal);
	    //factory.setKeyStore(System.getenv("ARTEMIS_KS_PATH"));
	    factory.setKeyStore(getProperty("artemisKSPath").getValueAsString());
            // The password vars don't need to be set; they'll default to "passwd"
     	    tempVal = getProperty("artemisTSPasswd").getValueAsString();
            if (tempVal == null) tempVal = "password";
      	    factory.setTrustStorePassword(tempVal);
     	    //tempVal = System.getenv("ARTEMIS_KS_PASSWD");
     	    tempVal = getProperty("artemisKSPasswd").getValueAsString();
            if (tempVal == null) tempVal = "password";
	    factory.setKeyStorePassword(tempVal);
        } catch (Exception e) {
          throw new TransportException("Set trust/keystore failed - " + e.getMessage());
        }
      }
      Property userNameProp = getProperty("userName");
      Property passwordProp = getProperty("password");
      if (userNameProp != null && passwordProp != null) {
        try {
          factory.setUserName(userNameProp.getValueAsString()); // null or blank should be fine
        } catch (Exception e) {
          throw new TransportException("User name property access failed - " + e.getMessage());
        }
        try {
          factory.setPassword(passwordProp.getDecryptedValue());
        } catch (Exception e) {
          throw new TransportException("Password encrypted property access failed - " + e.getMessage());
        }
      }

      final ActiveMQConnection amqConn = (ActiveMQConnection) factory.createConnection();
      if (amqConn == null) {
        throw new TransportException(LOGGER.translate("JMS_CONNECTION_FAILURE", getProperty("providerUrl").getValueAsString()));
      }

      Session localSession = null;
      MessageConsumer localConsumer = null;

      try {
        synchronized (syncLock) {
          if (RunningState.STARTING.equals(getRunningState()) && starterThread == Thread.currentThread()) {
            amqConn.setExceptionListener(new ExceptionListener() {
              @Override
              public void onException(JMSException exception) {
                if (amqConn == connection) {
                  synchronized (syncLock) {
                    if (amqConn == connection) {
                      errorMessage = exception.getMessage();
                      LOGGER.error(errorMessage, exception);

                      setRunningState(RunningState.STOPPING);
                      // TODO: interrupt worker thread?
                      cleanup();
                      setRunningState(RunningState.ERROR);
                    }
                  }
                }
              }
            });

            if (amqConn.getTransport().isFaultTolerant()) {
              amqConn.addTransportListener(new TransportListener() {
                @Override
                public void onCommand(Object command) {
                  // ignore
                }

                @Override
                public void onException(IOException exception) {
                  if (amqConn == connection) {
                    LOGGER.warn("ActiveMQ input transport - IO exception - " + exception.getMessage());
                  }
                }

                @Override
                public void transportInterupted() {
                  if (amqConn == connection) {
                    LOGGER.warn("ActiveMQ input transport - connection interrupted");
                  }
                }

                @Override
                public void transportResumed() {
                  if (amqConn == connection && amqConn.isStarted()) {
                    LOGGER.warn("ActiveMQ input transport - connection resumed");
                  }
                }
              });
            }

            connection = amqConn; // by setting this first, it can be closed via cleanup() even if it blocks on amqConn.start()
          } else {
            LOGGER.error("ActiveMQ Transport startup interupted. Discarding any partially initialized ActiveMQ session resources.");
            return false;
          }
        }

        // keep the initialization outside of the synchronized block, as next line may take an indefinite amount of time
        try {
          amqConn.start();
        } catch (JMSException e) {
          if (e.getMessage().equals("Stopped.")) {
            LOGGER.warn("A JMS 'Stopped' exception occurred when starting the ActiveMQ connection. This probably indicates the GeoEvent input was stopped before initialization could complete.");
            return false;
          }
          throw e;
        }

        localSession = amqConn.createSession(false, Session.AUTO_ACKNOWLEDGE);

        Destination destination;
        String destName = getProperty("destinationName").getValueAsString();
        String destType = getProperty("destinationType").getValueAsString();

        // In ActiveMQ 5.13/14, next line may raise an IllegalArgumentException even though
        // the only declared exception type is JMSException - observed when the destination
        // name contained optional parameters with invalid characters that are not escaped.
        // (Not caught at compile time since IllegalArgumentException is a RuntimeException
        // and as such is unchecked / does not need to be delared. Still quite unexpected.)
        // This is prevented by checking the parameters ahead of time (not using validate).

        destination = "topic".equalsIgnoreCase(destType)
            ? localSession.createTopic(destName)
            : localSession.createQueue(destName);

        localConsumer = localSession.createConsumer(destination);

        synchronized (syncLock) {
          // Do not modify object state if starter thread is no longer current or state has changed.

          // Normally the framework would have sent a stop() before it ever launched another starter
          // thread, and the stop() would raise an exception in the old starter thread if it was stuck
          // in setup(). However, these extra integrity tests may protect against a very slight chance
          // that the old thread could be stopped and a new thread started at "just the wrong moment"
          // such that no exception has been caught, but the old starter thread has become defunct.

          if (amqConn == connection) {
            // confirm consistent state before applying changes to internal object state
            if (RunningState.STARTING.equals(getRunningState()) && starterThread == Thread.currentThread()) {
              session = localSession;
              messageConsumer = localConsumer;
              return true;
            } else {
              cleanup();
            }
          }
          // fall through to report error and return false, unless already returned true above
          LOGGER.error("ActiveMQ Input Transport startup was interupted before initialization completed. Releasing any ActiveMQ session that were initialized but not used.");
          return false; // no exception in this case, but finally block will still execute
        }
      } finally {
        if (localConsumer != null && localConsumer != messageConsumer) {
          localConsumer.close();
        }
        if (localSession != null && localSession != session) {
          localSession.close();
        }
        if (amqConn != connection && !amqConn.isClosing() && !amqConn.isClosed()) {
          amqConn.close(); // can't be null, but may have already been closed by cleanup()
        }
      }
    } catch (JMSException | TransportException e) {
      synchronized (syncLock) {
        if (starterThread == Thread.currentThread()) {
          if (connection != null) {
            cleanup();
          }
          setRunningState(RunningState.ERROR);
        }
      }
      throw e;
    }
  }

  private void cleanup() {
    synchronized (syncLock) {
      try {
        if (messageConsumer != null) {
          MessageConsumer m = messageConsumer;
          messageConsumer = null;
          m.close();
        }
      } catch (Throwable ignore) {
        ;
      }
      try {
        if (session != null) {
          Session s = session;
          session = null;
          s.close();
        }
      } catch (Throwable ignore) {
        ;
      }
      try {
        if (connection != null) {
          Connection c = connection;
          connection = null;
          c.close();
        }
      } catch (Throwable ignore) {
        ;
      }
    }
  }

  private class ActiveMQByteInboundTransportStarter implements Runnable {
    final private ActiveMQInboundTransport transport;
    final private long timeout;
    private volatile Thread worker;

    public ActiveMQByteInboundTransportStarter(ActiveMQInboundTransport transport, long timeout) {
      this.transport = transport;
      this.timeout = timeout;
    }

    @Override
    public void run() {
      try {
        errorMessage = "";

        while (starterThread != null && starterThread == Thread.currentThread() && !Thread.interrupted()) {
          switch (getRunningState()) {
            case STOPPING:
              break;
            case STOPPED:
              if (ClusterCommand.STOP.equals(getClusterCommand())) {
                errorMessage = "";
                return; // interrupt any worker and exit thread
              }
              // otherwise fall through (for initial try / retry?)
            case STARTED:
            case STARTING:
              if (worker != null && worker.isAlive()) {
                errorMessage = "";
                break; // repeat monitoring loop
              }
            case ERROR: {
              try {
                interruptWorker(500);
                if (Thread.interrupted()) { // starter thread interrupted while waiting
                  return; // fall through to finally block - don't start new worker
                }
                cleanup();
                setRunningState(RunningState.STARTING);

                String validationErrorMessage = checkPropertiesForErrors();
                if (validationErrorMessage == null) {
                  if (setup()) {
                    if (RunningState.STARTING.equals(getRunningState())) {
                      worker = new Thread(transport);
                      worker.start();
                    }
                    // fall through to break out of switch and repeat monitor loop
                  } else {
                    // running state should have been set, or not, and cleanup called if appropriate
                    return;
                  }
                } else {
                  if (!validationErrorMessage.equals(errorMessage)) {
                    LOGGER.error("Aborting initialization of ActiveMQ Input due to invalid properties: " + validationErrorMessage);
                    errorMessage = validationErrorMessage;
                  }
                  setRunningState(RunningState.ERROR);
                  return;
                }
              } catch (JMSException | TransportException e) {
                errorMessage = "ActiveMQ Input initialization failed with a " +
                    (e instanceof JMSException ? "JMS" : "Transport") +
                    " Exception - " + e.getMessage();
                LOGGER.error(errorMessage, e);
              }
              break;
            }
            default:
              errorMessage = "";
              break;
          }
          sleep();
        }
      } finally {
        interruptWorker(0);
        cleanup();
      }
    }

    private void sleep() {
      try {
        final Thread currentWorker = worker;
        if (currentWorker != null && currentWorker.isAlive()) {
          currentWorker.join(timeout);
        } else {
          Thread.sleep(timeout);
        }
      } catch (InterruptedException e) {
        ;
      }
    }

    private void interruptWorker(long waitMillis) {
      final Thread currentWorker = worker;
      if (currentWorker != null) {
        if (currentWorker.isAlive()) {
          currentWorker.interrupt();
          if (waitMillis > 0) {
            try {
              currentWorker.join(waitMillis);
              if (currentWorker.isAlive()) {
                LOGGER.warn("Wait timeout (" + waitMillis + " ms) expired after requesting interruption on old worker thread.");
              }
            } catch (InterruptedException e) {
              // do nothing - caller may want to check Thread.interrupted()
            }
          }
        }
      }
    }
  }
}
