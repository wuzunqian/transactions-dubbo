package com.sxb.lin.atomikos.dubbo.tm;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.XADataSource;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;
import javax.transaction.UserTransaction;

import org.springframework.jdbc.datasource.ConnectionHandle;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.JdbcTransactionObjectSupport;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.HeuristicCompletionException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.atomikos.icatch.CompositeTransaction;
import com.atomikos.icatch.CompositeTransactionManager;
import com.atomikos.icatch.config.Configuration;
import com.sxb.lin.atomikos.dubbo.InitiatorXATransactionLocal;
import com.sxb.lin.atomikos.dubbo.ParticipantXATransactionLocal;
import com.sxb.lin.atomikos.dubbo.service.DubboTransactionManagerService;
import com.sxb.lin.atomikos.dubbo.service.DubboTransactionManagerServiceProxy;
import com.sxb.lin.atomikos.dubbo.spring.XAAnnotationInfo;
import com.sxb.lin.atomikos.dubbo.spring.XAInvocationLocal;
import com.sxb.lin.atomikos.dubbo.spring.jdbc.InitiatorXADataSourceUtils;
import com.sxb.lin.atomikos.dubbo.spring.jdbc.XAConnectionHolder;

public class DataSourceTransactionManager extends org.springframework.jdbc.datasource.DataSourceTransactionManager {

	private static final long serialVersionUID = 1L;
	
	private transient UserTransaction userTransaction;
	
	private transient TransactionManager transactionManager;

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
		
		try {
			XAAnnotationInfo info = XAInvocationLocal.info();
			if(info.isNoXA()){
				this.doBegin(transaction, definition, false, info.isUseXA());
				return;
			}
			
			ParticipantXATransactionLocal current = ParticipantXATransactionLocal.current();
			if(current == null){
				boolean isActive = definition.isReadOnly() ? false : true;
				this.doBegin(transaction, definition, isActive, info.isUseXA());
			}else{
				if(definition.isReadOnly()){
					if(current.isActive()){
						throw new NotSupportedException("dubbo xa transaction not supported ReadOnly.");
					}
					this.doBegin(transaction, definition, false, info.isUseXA());
					return;
				}
				
				if(current.getIsActive() != null && current.getIsActive().booleanValue() == false){
					this.doBegin(transaction, definition, true, info.isUseXA());
					return;
				}
				
				if(definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_NESTED){
					throw new NestedTransactionNotSupportedException("dubbo xa transaction not supported PROPAGATION_NESTED.");
				}
				if(definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW){
					throw new NestedTransactionNotSupportedException("dubbo xa transaction not supported PROPAGATION_REQUIRES_NEW.");
				}
				
				current.active();
			}
		} catch (NotSupportedException e) {
			throw new CannotCreateTransactionException("JTA failure on begin", e);
		} catch(SystemException e) {
			throw new CannotCreateTransactionException("JTA failure on begin", e);
		} catch (SQLException e) {
			throw new CannotCreateTransactionException("JTA failure on begin", e);
		} 
	}
	
	private void doBegin(Object transaction, TransactionDefinition definition, boolean isActive, boolean isUseXA) 
			throws SQLException, NotSupportedException, SystemException{
		if(isUseXA){
			this.doJtaBegin(transaction, definition);
			this.newInitiatorXATransactionLocal(isActive);
		}else{
			this.checkInitiatorXATransactionLocal();
			super.doBegin(transaction, definition);
		}
	}
	
	protected void doJtaBegin(Object transaction, TransactionDefinition definition) 
			throws SQLException, NotSupportedException, SystemException {
		DubboTransactionManagerServiceProxy instance = DubboTransactionManagerServiceProxy.getInstance();
		if(!instance.isInit()){
			throw new CannotCreateTransactionException(
					"DubboTransactionManagerServiceProxy are not init,can not use this transactionManager to xa transaction.");
		}
		JdbcTransactionObjectSupport txObject = (JdbcTransactionObjectSupport) transaction;
		if (txObject.hasConnectionHolder()) {
			throw new CannotCreateTransactionException("can not create transaction,xa connection already exists");
		}
		
		int timeout = determineTimeout(definition);
		if (timeout != TransactionDefinition.TIMEOUT_DEFAULT) {
			userTransaction.setTransactionTimeout(timeout);
		}
		userTransaction.begin();
		
		XADataSource xaDataSource = (XADataSource) this.getDataSource();
		txObject.setConnectionHolder(InitiatorXADataSourceUtils.getXAConnection(xaDataSource));
	}
	
	@Override
	protected void doCommit(DefaultTransactionStatus status) {
		if(!ParticipantXATransactionLocal.isUseParticipantXATransaction()){
			if(this.isUseInitiatorXATransactionLocal()){
				this.doJtaCommit();
			}else{
				super.doCommit(status);
			}
		}
	}
	
	protected void doJtaCommit(){
		try {
			int jtaStatus = userTransaction.getStatus();
			if (jtaStatus == Status.STATUS_NO_TRANSACTION) {
				// Should never happen... would have thrown an exception before
				// and as a consequence led to a rollback, not to a commit call.
				// In any case, the transaction is already fully cleaned up.
				throw new UnexpectedRollbackException("JTA transaction already completed - probably rolled back");
			}
			if (jtaStatus == Status.STATUS_ROLLEDBACK) {
				// Only really happens on JBoss 4.2 in case of an early timeout...
				// Explicit rollback call necessary to clean up the transaction.
				// IllegalStateException expected on JBoss; call still necessary.
				try {
					userTransaction.rollback();
				}
				catch (IllegalStateException ex) {
					if (logger.isDebugEnabled()) {
						logger.debug("Rollback failure with transaction already marked as rolled back: " + ex);
					}
				}
				throw new UnexpectedRollbackException("JTA transaction already rolled back (probably due to a timeout)");
			}
			userTransaction.commit();
		}
		catch (RollbackException ex) {
			throw new UnexpectedRollbackException(
					"JTA transaction unexpectedly rolled back (maybe due to a timeout)", ex);
		}
		catch (HeuristicMixedException ex) {
			throw new HeuristicCompletionException(HeuristicCompletionException.STATE_MIXED, ex);
		}
		catch (HeuristicRollbackException ex) {
			throw new HeuristicCompletionException(HeuristicCompletionException.STATE_ROLLED_BACK, ex);
		}
		catch (IllegalStateException ex) {
			throw new TransactionSystemException("Unexpected internal transaction state", ex);
		}
		catch (SystemException ex) {
			throw new TransactionSystemException("JTA failure on commit", ex);
		}
	}

	@Override
	protected void doRollback(DefaultTransactionStatus status) {
		if(!ParticipantXATransactionLocal.isUseParticipantXATransaction()){
			if(this.isUseInitiatorXATransactionLocal()){
				this.doJtaRollback();
			}else{
				super.doRollback(status);
			}
		}
	}
	
	protected void doJtaRollback(){
		try {
			int jtaStatus = userTransaction.getStatus();
			if (jtaStatus != Status.STATUS_NO_TRANSACTION) {
				try {
					userTransaction.rollback();
				}
				catch (IllegalStateException ex) {
					if (jtaStatus == Status.STATUS_ROLLEDBACK) {
						// Only really happens on JBoss 4.2 in case of an early timeout...
						if (logger.isDebugEnabled()) {
							logger.debug("Rollback failure with transaction already marked as rolled back: " + ex);
						}
					}
					else {
						throw new TransactionSystemException("Unexpected internal transaction state", ex);
					}
				}
			}
		}
		catch (SystemException ex) {
			throw new TransactionSystemException("JTA failure on rollback", ex);
		}
	}
	
	@Override
	protected void doCleanupAfterCompletion(Object transaction) {
		if(!ParticipantXATransactionLocal.isUseParticipantXATransaction()){
			if(this.isUseInitiatorXATransactionLocal()){
				this.restoreThreadLocalStatus();
			}else{
				super.doCleanupAfterCompletion(transaction);
			}
		}
		
		XAInvocationLocal current = XAInvocationLocal.current();
		if(current != null){
			current.clear();
		}
	}
	
	@Override
	protected void prepareSynchronization(DefaultTransactionStatus status,
			TransactionDefinition definition) {
		super.prepareSynchronization(status, definition);
		if (status.isNewSynchronization()) {
			XADataSource xaDataSource = (XADataSource) this.getDataSource();
			InitiatorXADataSourceUtils.registerSynchronization(xaDataSource);
		}
	}

	@Override
	protected boolean shouldCommitOnGlobalRollbackOnly() {
		return true;
	}
	
	@Override
	protected void prepareForCommit(DefaultTransactionStatus status) {
		if(!ParticipantXATransactionLocal.isUseParticipantXATransaction()){
			return;
		}
		
		JdbcTransactionObjectSupport transaction = (JdbcTransactionObjectSupport) status.getTransaction();
		XADataSource xaDataSource = (XADataSource) this.getDataSource();
		Object resource = TransactionSynchronizationManager.getResource(xaDataSource);
		if(status.isNewTransaction() && resource == null){
			ConnectionHandle connectionHandle = new ConnectionHandle(){
				public Connection getConnection() {
					return null;
				}
				public void releaseConnection(Connection con) {
				}
			};
			ConnectionHolder holder = new ConnectionHolder(connectionHandle);
			transaction.setConnectionHolder(holder);
		}else if(resource instanceof XAConnectionHolder){
			XAConnectionHolder holder = (XAConnectionHolder) resource;
			transaction.setConnectionHolder(holder);
		}
	}

	
	
	private void restoreThreadLocalStatus(){
		InitiatorXATransactionLocal current = InitiatorXATransactionLocal.current();
		if(current != null){
			current.restoreThreadLocalStatus();
		}
	}
	
	protected boolean isUseInitiatorXATransactionLocal(){
		InitiatorXATransactionLocal current = InitiatorXATransactionLocal.current();
		if(current != null){
			return true;
		}
		return false;
	}
	
	protected void checkInitiatorXATransactionLocal() {
		if(this.isUseInitiatorXATransactionLocal()){
			throw new CannotCreateTransactionException("can not begin,dubbo xa transaction already exists.");
		}
	}

	protected void newInitiatorXATransactionLocal(boolean isActive) {
		DubboTransactionManagerServiceProxy instance = DubboTransactionManagerServiceProxy.getInstance();
		CompositeTransactionManager compositeTransactionManager = Configuration.getCompositeTransactionManager();
		CompositeTransaction compositeTransaction = compositeTransactionManager.getCompositeTransaction();
		
		String tid = compositeTransaction.getTid();
		long time = compositeTransaction.getTimeout() + System.currentTimeMillis() + DubboTransactionManagerService.ADD_TIME;
		
		InitiatorXATransactionLocal local = new InitiatorXATransactionLocal();
		local.setTid(tid);
		local.setTmAddress(instance.getLocalAddress());
		local.setTimeOut(time + "");
		local.setActive(isActive);
		local.bindToThread();
	}
	
	@Override
	protected boolean isExistingTransaction(Object transaction) {
		if(this.isUseInitiatorXATransactionLocal()){
			try {
				return userTransaction.getStatus() != Status.STATUS_NO_TRANSACTION;
			}
			catch (SystemException ex) {
				throw new TransactionSystemException("JTA failure on getStatus", ex);
			}
		}else{
			return super.isExistingTransaction(transaction);
		}
	}
	
	@Override
	public void afterPropertiesSet() {
		if (getDataSource() == null) {
			throw new IllegalArgumentException("Property 'dataSource' is required");
		}
		if (!(getDataSource() instanceof XADataSource)) {
			throw new IllegalArgumentException("Property 'dataSource' must be supported xaDataSource");
		}
		if (getUserTransaction() == null) {
			throw new IllegalArgumentException("Property 'userTransaction' is required");
		}
		if (getTransactionManager() == null) {
			throw new IllegalArgumentException("Property 'transactionManager' is required");
		}
	}

	public UserTransaction getUserTransaction() {
		return userTransaction;
	}

	public void setUserTransaction(UserTransaction userTransaction) {
		this.userTransaction = userTransaction;
	}

	public TransactionManager getTransactionManager() {
		return transactionManager;
	}

	public void setTransactionManager(TransactionManager transactionManager) {
		this.transactionManager = transactionManager;
	}

}
