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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import javax.jms.BytesMessage;
import javax.jms.Connection;
//import javax.jms.ConnectionFactory;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.Destination;
import javax.jms.MessageConsumer;
import javax.jms.ObjectMessage;
import javax.jms.Session;
import javax.jms.TextMessage;

import org.apache.activemq.ActiveMQConnectionFactory;
import org.apache.activemq.ActiveMQConnection;
import org.apache.activemq.transport.TransportListener;

import com.esri.ges.core.component.ComponentException;
import com.esri.ges.core.component.RunningState;
import com.esri.ges.core.property.Property;
import com.esri.ges.core.validation.ValidationException;
import com.esri.ges.framework.i18n.BundleLogger;
import com.esri.ges.framework.i18n.BundleLoggerFactory;
import com.esri.ges.transport.InboundTransportBase;
import com.esri.ges.transport.TransportDefinition;
import com.esri.ges.transport.TransportException;
//import com.esri.ges.util.Validator;

public class ActiveMQInboundTransport extends InboundTransportBase implements Runnable
{
	private static final BundleLogger	LOGGER	= BundleLoggerFactory.getLogger(ActiveMQInboundTransport.class);

	private String										errorMessage;
	private volatile Connection							connection;
	private Session										session;
	private MessageConsumer						messageConsumer;
	private volatile Thread						starterThread;
	private final String								channelId;
	private final Object								syncLock = new Object();

	public ActiveMQInboundTransport(TransportDefinition definition) throws ComponentException
	{
		super(definition);
		// create a channel id per instance
		channelId = UUID.randomUUID().toString();
	}

	@Override
	public void run()
	{
		setRunningState(RunningState.STARTED);
		while (isRunning())
		{
			try
			{
				Message message = messageConsumer.receive(100);
				if (message != null)
				{
					if (message instanceof TextMessage)
					{
						TextMessage textMessage = (TextMessage) message;
						byte[] bytes = {};
						try
						{
							bytes = textMessage.getText().getBytes(StandardCharsets.UTF_8);
						}
						catch (Throwable error)
						{
							LOGGER.error("MESSAGE_DECODING_ERROR", error.getMessage());
							LOGGER.info(error.getMessage(), error);
						}
						receive(bytes);
					}
					else if (message instanceof BytesMessage)
					{
						BytesMessage bytesMessage = (BytesMessage) message;
						byte[] bytes = new byte[(int) bytesMessage.getBodyLength()];
						bytesMessage.readBytes(bytes);
						receive(bytes);
					}
					else if (message instanceof ObjectMessage)
					{
						ObjectMessage objectMessage = (ObjectMessage) message;
						Serializable object = objectMessage.getObject();
						ByteArrayOutputStream baos = null;
						ObjectOutput output = null;
						try
						{
							baos = new ByteArrayOutputStream();
							output = new ObjectOutputStream(baos);
							output.writeObject(object);
							receive(baos.toByteArray());
						}
						catch (IOException error)
						{
							LOGGER.error(error.getMessage(), error);
						}
						finally
						{
							if (output != null)
							{
								try
								{
									output.close();
								}
								catch (IOException ex)
								{
									;
								}
							}
							if (baos != null)
							{
								try
								{
									baos.close();
								}
								catch (IOException ex)
								{
									;
								}
							}
						}
					}
				}
			}
			catch (JMSException error)
			{
				LOGGER.error(error.getMessage(), error);
			}
		}
	}

	private void receive(byte[] bytes)
	{
		if (bytes != null && bytes.length > 0)
		{
			ByteBuffer bb = ByteBuffer.allocate(bytes.length);
			bb.put(bytes);
			bb.flip();
			byteListener.receive(bb, channelId);
			bb.clear();
		}
	}

	@Override
	public void start()
	{
		synchronized (syncLock) {
			cleanup();
			switch (getRunningState())
			{
				case STARTING:
				case STARTED:
					return;
			}
			if (starterThread == null || !starterThread.isAlive())
			{
				starterThread = new Thread(new ActiveMQByteInboundTransportStarter(this, 30000));
				starterThread.start();
			}
		}
	}

	@Override
	public void stop()
	{
		synchronized (syncLock) {
			if (!RunningState.STOPPED.equals(getRunningState()))
			{
				final Thread oldStarterThread = starterThread;
				starterThread = null;
				
				setRunningState(RunningState.STOPPING);
				cleanup();
				setRunningState(RunningState.STOPPED);
				
				if (oldStarterThread != null && oldStarterThread != Thread.currentThread()) {
					if (oldStarterThread.isAlive() && !oldStarterThread.isInterrupted()) {
						oldStarterThread.interrupt();
					}
				}
			}
		}
	}

	public String getErrorMessage()
	{
		return errorMessage;
	}
	
	@Override
	public void validate() throws ValidationException {
		String destType = getProperty("destinationType").getValueAsString();
		if ("topic".equalsIgnoreCase(destType) || "queue".equalsIgnoreCase(destType) || destType == null || destType.isEmpty()) {
			String destName = getProperty("destinationName").getValueAsString();
			if (destName != null && !destName.isEmpty()) {
				int queryPos = destName.indexOf('?');
				if (queryPos >= 0) {
					try {
						URLDecoder.decode(destName.substring(queryPos), "UTF-8");
					} catch (Exception e) {
						throw new ValidationException("Optional parameters in Destination Name are not escaped properly and cannot be decoded.");
					}
				}
			} else {
				throw new ValidationException("Destination Name must be configured. Current value is null or blank.");
			}
		} else {
			throw new ValidationException("A valid Destination Type must be configured. Acceptable values are 'queue' and 'topic' (case insensitive), or else leave blank for default ('queue').");
		}
	}

	private boolean setup() throws TransportException {
		try {
			ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory(getProperty("providerUrl").getValueAsString());
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
									if (amqConn == connection) {
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

				// In ActiveMQ 5.13.4, next line may raise an IllegalArgumentException even though
				// the only declared exception type is JMSException - observed when the destination
				// name contained optional parameters with invalid characters that are not escaped.
				// Rather than trap for an unadvertised exception, this is prevented via validate().
				
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
		} catch (JMSException|TransportException e) {
			synchronized (syncLock) {
				if (starterThread == Thread.currentThread() && connection != null) {
					cleanup();
					setRunningState(RunningState.ERROR);
				}
			}
			
			TransportException containerException = new TransportException("ActiveMQ connection setup failed - " + e.getMessage());
			containerException.initCause(e);
			throw containerException;
		}
	}

	private void cleanup()
	{
		synchronized (syncLock) {
			try
			{
				if (messageConsumer != null)
				{
					MessageConsumer m = messageConsumer;
					messageConsumer = null;
					m.close();
				}
			}
			catch (Throwable ignore)
			{
				;
			}
			try
			{
				if (session != null)
				{
					Session s = session;
					session = null;
					s.close();
				}
			}
			catch (Throwable ignore)
			{
				;
			}
			try
			{
				if (connection != null)
				{
					Connection c = connection;
					connection = null;
					c.close();
				}
			}
			catch (Throwable ignore)
			{
				;
			}
		}
	}

	private class ActiveMQByteInboundTransportStarter implements Runnable
	{
		final private ActiveMQInboundTransport	transport;
		final private long						timeout;

		public ActiveMQByteInboundTransportStarter(ActiveMQInboundTransport transport, long timeout)
		{
			this.transport = transport;
			this.timeout = timeout;
		}

		@Override
		public void run()
		{
			while (starterThread != null && starterThread == Thread.currentThread())
			{
				switch (getRunningState())
				{
					case STOPPING:
						break;
					case STOPPED:
					case ERROR:
					{
						try
						{
							setRunningState(RunningState.STARTING);
							if (setup()) {
								if (RunningState.STARTING.equals(getRunningState())) {
									new Thread(transport).start();
								}
								// fall through to break out of switch and repeat monitor loop
							} else {
								// running state should have been set, or not, and cleanup called if appropriate
								return;
							}
						}
						catch (TransportException e)
						{
							errorMessage = e.getMessage();
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
		}

		private void sleep()
		{
			try
			{
				Thread.sleep(timeout);
			}
			catch (InterruptedException e)
			{
				;
			}
		}
	}
}
