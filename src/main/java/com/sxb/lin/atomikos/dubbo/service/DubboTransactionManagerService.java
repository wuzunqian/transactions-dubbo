package com.sxb.lin.atomikos.dubbo.service;

import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import javax.transaction.xa.XAException;
import javax.transaction.xa.Xid;

public interface DubboTransactionManagerService {

	StartXid enlistResource(String remoteAddress,String tid,String localAddress,
			String uniqueResourceName) throws SystemException, RollbackException;
	
	int prepare(String remoteAddress, Xid xid) throws XAException;
	
	void commit(String remoteAddress, Xid xid, boolean onePhase) throws XAException;
	
	void rollback(String remoteAddress, Xid xid) throws XAException;
	
	Xid[] recover(String remoteAddress, int flag) throws XAException;
}
