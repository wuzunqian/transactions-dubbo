package com.sxb.lin.atomikos.dubbo.spring.jms;

import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.JMSException;
import javax.jms.Session;
import javax.jms.XAConnection;
import javax.jms.XAConnectionFactory;
import javax.jms.XASession;
import javax.transaction.xa.XAException;

import org.apache.activemq.pool.PooledConnection;
import org.apache.activemq.pool.PooledConnectionFactory;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronizationAdapter;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.Assert;

public abstract class XAConnectionFactoryUtils {
	
	private static final Log logger = LogFactory.getLog(XAConnectionFactoryUtils.class);

	public static Session doGetTransactionalSession(ConnectionFactory connectionFactory, 
			boolean startConnection, JtaJmsTemplate jmsTemplate) throws JMSException {
		
		Assert.notNull(connectionFactory, "ConnectionFactory must not be null");
		
		XAJmsResourceHolder resourceHolder =
				(XAJmsResourceHolder) TransactionSynchronizationManager.getResource(connectionFactory);
		
		if (resourceHolder != null) {
			Session session = resourceHolder.getSession();
			if (session != null) {
				if (startConnection) {
					Connection con = resourceHolder.getConnection();
					con.start();
				}
				return session;
			}
		}
		
		XASession session = null;
		Connection connection = null;
		try {
			if(connectionFactory instanceof PooledConnectionFactory){
				PooledConnection pooledConnection = (PooledConnection) connectionFactory.createConnection();
				connection = pooledConnection;
				XAConnection con = (XAConnection) pooledConnection.getConnection();
				session = con.createXASession();
				resourceHolder = new XAJmsResourceHolder(
						connectionFactory, pooledConnection, session, jmsTemplate.getDubboUniqueResourceName());
				
				if (startConnection) {
					pooledConnection.start();
				}
			}else if (connectionFactory instanceof XAConnectionFactory){
				XAConnection con = ((XAConnectionFactory)connectionFactory).createXAConnection();
				connection = con;
				session = con.createXASession();
				resourceHolder = new XAJmsResourceHolder(
						connectionFactory, con, session, jmsTemplate.getDubboUniqueResourceName());
				
				if (startConnection) {
					con.start();
				}
			}else {
				throw new JMSException(
						"ConnectionFactory must not be PooledConnectionFactory set XAConnection or XAConnectionFactory.");
			}
		}
		catch (JMSException ex) {
			if (session != null) {
				try {
					session.close();
				}
				catch (Throwable ex2) {
					// ignore
				}
			}
			if (connection != null) {
				try {
					connection.close();
				}
				catch (Throwable ex2) {
					// ignore
				}
			}
			throw ex;
		}
		
		TransactionSynchronizationManager.registerSynchronization(
				new XAJmsResourceSynchronization(resourceHolder, connectionFactory));
		resourceHolder.setSynchronizedWithTransaction(true);
		TransactionSynchronizationManager.bindResource(connectionFactory, resourceHolder);
		
		return session;
	}
	
	private static class XAJmsResourceSynchronization extends TransactionSynchronizationAdapter {
		
		private XAJmsResourceHolder resourceHolder;
		
		private ConnectionFactory connectionFactory;
		
		private boolean holderActive = true;

		public XAJmsResourceSynchronization(
				XAJmsResourceHolder resourceHolder, ConnectionFactory connectionFactory) {
			super();
			this.resourceHolder = resourceHolder;
			this.connectionFactory = connectionFactory;
		}

		@Override
		public int getOrder() {
			return DataSourceUtils.CONNECTION_SYNCHRONIZATION_ORDER;
		}
		
		@Override
		public void beforeCompletion() {
			TransactionSynchronizationManager.unbindResource(this.connectionFactory);
			this.holderActive = false;
			try {
				this.resourceHolder.end();
			} catch (XAException e) {
				logger.error(e.getMessage(), e);
			}
		}
		
		@Override
		public void afterCompletion(int status) {
			if (this.holderActive) {
				this.holderActive = false;
				TransactionSynchronizationManager.unbindResourceIfPossible(this.connectionFactory);
				try {
					this.resourceHolder.end();
				} catch (XAException e) {
					logger.error(e.getMessage(), e);
				}
			}
			this.resourceHolder.reset();
		}
	}
}
