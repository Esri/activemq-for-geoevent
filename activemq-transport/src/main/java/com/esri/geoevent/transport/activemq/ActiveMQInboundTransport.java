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
	private Connection								connection;
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
					if (starterThread != null && RunningState.STARTING.equals(getRunningState()) && Thread.currentThread() == starterThread) {
						amqConn.setExceptionListener(new ExceptionListener() {
							@Override
														public void onException(JMSException exception) {
								synchronized (syncLock) {
									if (amqConn == connection && starterThread != null && starterThread.isAlive()) {
										errorMessage = exception.getMessage();
										LOGGER.error(errorMessage, exception);
										
										setRunningState(RunningState.STOPPING);
										cleanup();
										setRunningState(RunningState.ERROR);
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

						connection = amqConn; // by setting this first, it can be closed in cleanup(), if a new starterThread is run
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
				String destType = getProperty("destinationType").getValueAsString().toLowerCase();
				if ("topic".equals(destType)) {
					destination = localSession.createTopic(destName);
				} else if ("queue".equals(destType) || destType.isEmpty()) {
					destination = localSession.createQueue(destName);
				} else {
					throw new TransportException("ActiveMQ Input Transport validation error	 - only 'queue' and 'topic' are expected for Destination Type; '" + destType + "' was specified. Aborting");
				}
				localConsumer = localSession.createConsumer(destination);

				synchronized (syncLock) {
					// confirm consistent state before applying changes to internal object state
					if (amqConn == connection) {
						if (starterThread == Thread.currentThread() && RunningState.STARTING.equals(getRunningState())) {
							session = localSession;
							messageConsumer = localConsumer;
							return true;
						} else {
							cleanup(); // in addition to error message below and return false
						}
					}
					LOGGER.error("ActiveMQ Input Transport startup interupted before initialization completed. Releasing ActiveMQ session resources initialized in background.");
					return false; // no exception but finally block will execute
				}
			} finally {
				// quietly close any unreferenced resources before returning or proceeding to exception handler below
				
				if (localConsumer != null && localConsumer != messageConsumer) {
					localConsumer.close();
				}
				if (localSession != null && localSession != session) {
					localSession.close();
				}
				if (amqConn != connection && connection != null) {
					amqConn.close(); // if connection was cleared it was already closed by cleanup() above
				}
			}
		} catch (JMSException|TransportException e) {
			
			synchronized (syncLock) {
				if (starterThread != null && starterThread == Thread.currentThread() && connection != null) {
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
					messageConsumer.close();
					messageConsumer = null;
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
					session.close();
					session = null;
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
					connection.close();
					connection = null;
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
