/*
 * Copyright (C) 2016-2020 ActionTech.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher.
 */

package com.actiontech.dble.backend.mysql.nio.handler.transaction.xa;

import com.actiontech.dble.DbleServer;
import com.actiontech.dble.alarm.AlarmCode;
import com.actiontech.dble.alarm.Alert;
import com.actiontech.dble.alarm.AlertUtil;
import com.actiontech.dble.backend.BackendConnection;
import com.actiontech.dble.backend.mysql.nio.MySQLConnection;
import com.actiontech.dble.backend.mysql.nio.handler.transaction.AbstractCommitNodesHandler;
import com.actiontech.dble.backend.mysql.xa.CoordinatorLogEntry;
import com.actiontech.dble.backend.mysql.xa.ParticipantLogEntry;
import com.actiontech.dble.backend.mysql.xa.TxState;
import com.actiontech.dble.backend.mysql.xa.XAStateLog;
import com.actiontech.dble.btrace.provider.XaDelayProvider;
import com.actiontech.dble.config.ErrorCode;
import com.actiontech.dble.net.mysql.ErrorPacket;
import com.actiontech.dble.net.mysql.OkPacket;
import com.actiontech.dble.route.RouteResultsetNode;
import com.actiontech.dble.server.NonBlockingSession;
import com.actiontech.dble.singleton.XASessionCheck;
import com.actiontech.dble.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class XACommitNodesHandler extends AbstractCommitNodesHandler {
    private static Logger logger = LoggerFactory.getLogger(XACommitNodesHandler.class);
    private static final int COMMIT_TIMES = 5;
    private int tryCommitTimes = 0;
    private int backgroundCommitTimes = 0;
    private ParticipantLogEntry[] participantLogEntry = null;
    private int participantLogSize = 0;
    byte[] sendData = OkPacket.OK;

    private Lock lockForErrorHandle = new ReentrantLock();
    private Condition sendFinished = lockForErrorHandle.newCondition();
    private volatile boolean sendFinishedFlag = false;
    private ConcurrentMap<Object, Long> xaOldThreadIds;

    public XACommitNodesHandler(NonBlockingSession session) {
        super(session);
        xaOldThreadIds = new ConcurrentHashMap<>(session.getTargetCount());
    }

    @Override
    public void commit() {
        participantLogSize = session.getTargetCount();
        if (participantLogSize <= 0) {
            session.getSource().write(session.getOkByteArray());
            session.multiStatementNextSql(session.getIsMultiStatement().get());
            return;
        }
        lock.lock();
        try {
            reset();
        } finally {
            lock.unlock();
        }
        int position = 0;
        //get session's lock before sending commit(in fact, after ended)
        //then the XA transaction will be not killed, if killed ,then we will not commit
        if (session.getXaState() != null && session.getXaState() == TxState.TX_ENDED_STATE) {
            if (!session.cancelableStatusSet(NonBlockingSession.CANCEL_STATUS_COMMITTING)) {
                return;
            }
        }

        try {
            sendFinishedFlag = false;
            unResponseRrns.addAll(session.getTargetKeys());
            List<MySQLConnection> conns = new ArrayList<>(session.getTargetCount());
            for (RouteResultsetNode rrn : session.getTargetKeys()) {
                final BackendConnection conn = session.getTarget(rrn);
                conn.setResponseHandler(this);
                conns.add((MySQLConnection) conn);
            }
            session.setDiscard(false);
            for (MySQLConnection con : conns) {
                if (!executeCommit(con, position++)) {
                    break;
                }
            }
            session.setDiscard(true);
        } finally {
            lockForErrorHandle.lock();
            try {
                sendFinishedFlag = true;
                sendFinished.signalAll();
            } finally {
                lockForErrorHandle.unlock();
            }
        }

    }

    @Override
    public void clearResources() {
        tryCommitTimes = 0;
        backgroundCommitTimes = 0;
        participantLogEntry = null;
        sendData = OkPacket.OK;
        implicitCommitHandler = null;
        xaOldThreadIds.clear();
        if (closedConnSet != null) {
            closedConnSet.clear();
        }
    }

    @Override
    protected boolean executeCommit(MySQLConnection mysqlCon, int position) {
        TxState state = session.getXaState();
        if (state == TxState.TX_STARTED_STATE) {
            if (participantLogEntry == null) {
                participantLogEntry = new ParticipantLogEntry[participantLogSize];
                CoordinatorLogEntry coordinatorLogEntry = new CoordinatorLogEntry(session.getSessionXaID(), participantLogEntry, session.getXaState());
                XAStateLog.flushMemoryRepository(session.getSessionXaID(), coordinatorLogEntry);
            }
            XAStateLog.initRecoveryLog(session.getSessionXaID(), position, mysqlCon);
            endPhase(mysqlCon);
        } else if (state == TxState.TX_ENDED_STATE) {
            if (position == 0) {
                if (!XAStateLog.saveXARecoveryLog(session.getSessionXaID(), TxState.TX_PREPARING_STATE)) {
                    String errMsg = "saveXARecoveryLog error, the stage is TX_PREPARING_STATE";
                    this.setFail(errMsg);
                    sendData = makeErrorPacket(errMsg);
                    nextParse();
                    return false;
                }
                this.debugCommitDelay();
            }
            preparePhase(mysqlCon);
        } else if (state == TxState.TX_PREPARED_STATE) {
            if (position == 0) {
                if (!XAStateLog.saveXARecoveryLog(session.getSessionXaID(), TxState.TX_COMMITTING_STATE)) {
                    String errMsg = "saveXARecoveryLog error, the stage is TX_COMMITTING_STATE";
                    this.setFail(errMsg);
                    sendData = makeErrorPacket(errMsg);
                    nextParse();
                    return false;
                }
                this.debugCommitDelay();
            }

            commitPhase(mysqlCon);
        } else if (state == TxState.TX_COMMIT_FAILED_STATE) {
            if (position == 0) {
                XAStateLog.saveXARecoveryLog(session.getSessionXaID(), TxState.TX_COMMIT_FAILED_STATE);
            }
            commitPhase(mysqlCon);
        } else if (state == TxState.TX_PREPARE_UNCONNECT_STATE) {
            LOGGER.warn("commit error and rollback the xa");
            if (decrementToZero(mysqlCon)) {
                DbleServer.getInstance().getComplexQueryExecutor().execute(new Runnable() {
                    @Override
                    public void run() {
                        if (session.isKilled()) {
                            XAStateLog.saveXARecoveryLog(session.getSessionXaID(), session.getXaState());
                            session.getSource().setTxInterrupt("Query is interrupted.");
                            setResponseTime(false);
                            session.clearSavepoint();
                            if (!session.closed()) {
                                ErrorPacket errPacket = new ErrorPacket();
                                errPacket.setErrNo(ErrorCode.ER_QUERY_INTERRUPTED);
                                errPacket.setMessage("Query is interrupted.".getBytes());
                                errPacket.setPacketId(++packetId);
                                session.getSource().write(errPacket.toBytes());
                            }
                        } else {
                            XAAutoRollbackNodesHandler nextHandler = new XAAutoRollbackNodesHandler(session, sendData, null, null);
                            nextHandler.rollback();
                        }
                    }
                });
            }
        }
        return true;
    }

    private byte[] makeErrorPacket(String errMsg) {
        ErrorPacket errPacket = new ErrorPacket();
        errPacket.setPacketId(1);
        errPacket.setErrNo(ErrorCode.ER_UNKNOWN_ERROR);
        errPacket.setMessage(StringUtil.encode(errMsg, session.getSource().getCharset().getResults()));
        return errPacket.toBytes();
    }

    private void endPhase(MySQLConnection mysqlCon) {
        if (mysqlCon.isClosed()) {
            mysqlCon.setXaStatus(TxState.TX_CONN_QUIT);
            XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
            setFail("Connection {DataHost[" + mysqlCon.getHost() + ":" + mysqlCon.getPort() + "],Schema[" + mysqlCon.getSchema() + "],threadID[" +
                    mysqlCon.getThreadId() + "]} was closed ");
            if (decrementToZero(mysqlCon)) {
                session.setXaState(TxState.TX_ENDED_STATE);
                nextParse();
            }
            return;
        }
        RouteResultsetNode rrn = (RouteResultsetNode) mysqlCon.getAttachment();
        String xaTxId = mysqlCon.getConnXID(session, rrn.getMultiplexNum().longValue());
        XaDelayProvider.delayBeforeXaEnd(rrn.getName(), xaTxId);
        if (logger.isDebugEnabled()) {
            logger.debug("XA END " + xaTxId + " to " + mysqlCon);
        }
        mysqlCon.execCmd("XA END " + xaTxId);
    }

    private void preparePhase(MySQLConnection mysqlCon) {
        if (mysqlCon.isClosed()) {
            mysqlCon.setXaStatus(TxState.TX_PREPARE_UNCONNECT_STATE);
            XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
            session.setXaState(TxState.TX_PREPARE_UNCONNECT_STATE);
            setFail("Connection {DataHost[" + mysqlCon.getHost() + ":" + mysqlCon.getPort() + "],Schema[" + mysqlCon.getSchema() + "],threadID[" +
                    mysqlCon.getThreadId() + "]} was closed ");
            if (decrementToZero(mysqlCon)) {
                nextParse();
            }
            return;
        }
        RouteResultsetNode rrn = (RouteResultsetNode) mysqlCon.getAttachment();
        String xaTxId = mysqlCon.getConnXID(session, rrn.getMultiplexNum().longValue());
        XaDelayProvider.delayBeforeXaPrepare(rrn.getName(), xaTxId);
        // update state of mysql conn to TX_PREPARING_STATE
        mysqlCon.setXaStatus(TxState.TX_PREPARING_STATE);
        XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
        if (logger.isDebugEnabled()) {
            logger.debug("XA PREPARE " + xaTxId + " to " + mysqlCon);
        }
        mysqlCon.execCmd("XA PREPARE " + xaTxId);

    }

    private void commitPhase(MySQLConnection mysqlCon) {
        if (session.getXaState() == TxState.TX_COMMIT_FAILED_STATE || mysqlCon.isClosed()) {
            mysqlCon.setXaStatus(TxState.TX_COMMIT_FAILED_STATE);
            MySQLConnection newConn = session.freshConn(mysqlCon, this);
            checkClosedConn(mysqlCon);
            if (!newConn.equals(mysqlCon)) {
                xaOldThreadIds.putIfAbsent(mysqlCon.getAttachment(), mysqlCon.getThreadId());
                mysqlCon = newConn;
            } else if (decrementToZero(mysqlCon)) {
                cleanAndFeedback(false);
                return;
            }
        }
        RouteResultsetNode rrn = (RouteResultsetNode) mysqlCon.getAttachment();
        String xaTxId = mysqlCon.getConnXID(session, rrn.getMultiplexNum().longValue());
        XaDelayProvider.delayBeforeXaCommit(rrn.getName(), xaTxId);
        if (logger.isDebugEnabled()) {
            logger.debug("XA COMMIT " + xaTxId + " to " + mysqlCon);
        }
        mysqlCon.execCmd("XA COMMIT " + xaTxId);
    }

    @Override
    public void okResponse(byte[] ok, BackendConnection conn) {
        this.waitUntilSendFinish();
        MySQLConnection mysqlCon = (MySQLConnection) conn;
        if (logger.isDebugEnabled()) {
            logger.debug("receive ok from " + mysqlCon);
        }
        conn.syncAndExecute();
        TxState state = mysqlCon.getXaStatus();
        if (state == TxState.TX_STARTED_STATE) {
            mysqlCon.setXaStatus(TxState.TX_ENDED_STATE);
            XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
            if (decrementToZero(mysqlCon)) {
                session.setXaState(TxState.TX_ENDED_STATE);
                nextParse();
            }
        } else if (state == TxState.TX_PREPARING_STATE) {
            //PREPARE OK
            mysqlCon.setXaStatus(TxState.TX_PREPARED_STATE);
            XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
            if (decrementToZero(mysqlCon)) {
                if (session.getXaState() == TxState.TX_ENDED_STATE) {
                    session.setXaState(TxState.TX_PREPARED_STATE);
                }
                nextParse();
            }
        } else if (state == TxState.TX_COMMIT_FAILED_STATE || state == TxState.TX_PREPARED_STATE) {
            //COMMIT OK
            // XA reset status now
            mysqlCon.setXaStatus(TxState.TX_COMMITTED_STATE);
            XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
            mysqlCon.setXaStatus(TxState.TX_INITIALIZE_STATE);
            if (decrementToZero(mysqlCon)) {
                if (session.getXaState() == TxState.TX_PREPARED_STATE) {
                    session.setXaState(TxState.TX_INITIALIZE_STATE);
                }
                cleanAndFeedback(true);
            }
        } else {
            LOGGER.info("Wrong session XA status " + mysqlCon);
        }
    }

    @Override
    public void errorResponse(byte[] err, BackendConnection conn) {
        this.waitUntilSendFinish();
        conn.syncAndExecute();
        ErrorPacket errPacket = new ErrorPacket();
        errPacket.read(err);
        String errMsg = new String(errPacket.getMessage());
        this.setFail(errMsg);
        sendData = makeErrorPacket(errMsg);
        if (conn instanceof MySQLConnection) {
            MySQLConnection mysqlCon = (MySQLConnection) conn;
            if (logger.isDebugEnabled()) {
                logger.debug("receive error [" + errMsg + "] from " + mysqlCon);
            }
            if (mysqlCon.getXaStatus() == TxState.TX_STARTED_STATE) {
                mysqlCon.close();
                mysqlCon.setXaStatus(TxState.TX_CONN_QUIT);
                XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
                if (decrementToZero(mysqlCon)) {
                    session.setXaState(TxState.TX_ENDED_STATE);
                    nextParse();
                }

                // 'xa prepare' error
            } else if (mysqlCon.getXaStatus() == TxState.TX_PREPARING_STATE) {
                mysqlCon.close();
                mysqlCon.setXaStatus(TxState.TX_CONN_QUIT);
                XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
                if (decrementToZero(mysqlCon)) {
                    if (session.getXaState() == TxState.TX_ENDED_STATE) {
                        session.setXaState(TxState.TX_PREPARED_STATE);
                    }
                    nextParse();
                }
                // 'xa commit' err
            } else if (mysqlCon.getXaStatus() == TxState.TX_PREPARED_STATE) { //TODO:service degradation?
                mysqlCon.setXaStatus(TxState.TX_COMMIT_FAILED_STATE);
                XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
                session.setXaState(TxState.TX_COMMIT_FAILED_STATE);
                if (decrementToZero(mysqlCon)) {
                    cleanAndFeedback(false);
                }
            } else if (mysqlCon.getXaStatus() == TxState.TX_COMMIT_FAILED_STATE) {
                if (errPacket.getErrNo() == ErrorCode.ER_XAER_NOTA) {
                    RouteResultsetNode rrn = (RouteResultsetNode) mysqlCon.getAttachment();
                    String xid = mysqlCon.getConnXID(session, rrn.getMultiplexNum().longValue());
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("check xid is " + xid);
                    }
                    XACheckHandler handler = new XACheckHandler(xid, mysqlCon.getSchema(), rrn.getName(), mysqlCon.getPool().getDbPool().getSource());
                    // if mysql connection holding xa transaction wasn't released, may result in ER_XAER_NOTA.
                    // so we need check xid here
                    handler.checkXid();
                    if (handler.isSuccess() && !handler.isExistXid()) {
                        // Unknown XID ,if xa transaction only contains select statement, xid will lost after restart server although prepared
                        mysqlCon.setXaStatus(TxState.TX_COMMITTED_STATE);
                        XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
                        mysqlCon.setXaStatus(TxState.TX_INITIALIZE_STATE);
                        if (decrementToZero(mysqlCon)) {
                            if (session.getXaState() == TxState.TX_PREPARED_STATE) {
                                session.setXaState(TxState.TX_INITIALIZE_STATE);
                            }
                            cleanAndFeedback(false);
                        }
                    } else {
                        if (handler.isExistXid()) {
                            // kill mysql connection holding xa transaction, so current xa transaction can be committed next time.
                            handler.killXaThread(xaOldThreadIds.get(mysqlCon.getAttachment()));
                        }
                        XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
                        session.setXaState(TxState.TX_COMMIT_FAILED_STATE);
                        if (decrementToZero(mysqlCon)) {
                            cleanAndFeedback(false);
                        }
                    }
                } else {
                    mysqlCon.setXaStatus(TxState.TX_COMMIT_FAILED_STATE);
                    XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
                    session.setXaState(TxState.TX_COMMIT_FAILED_STATE);
                    if (decrementToZero(mysqlCon)) {
                        cleanAndFeedback(false);
                    }
                }
            } else {
                LOGGER.info("Wrong session XA status " + mysqlCon);
            }
        }
    }

    @Override
    public void connectionError(Throwable e, BackendConnection conn) {
        this.waitUntilSendFinish();
        String errMsg = new String(StringUtil.encode(e.getMessage(), session.getSource().getCharset().getResults()));
        this.setFail(errMsg);
        sendData = makeErrorPacket(errMsg);
        boolean finished;
        lock.lock();
        try {
            errorConnsCnt++;
            finished = canResponse();
        } finally {
            lock.unlock();
        }
        innerConnectError(conn, finished, "backend connect " + e.toString());
    }

    @Override
    public void connectionClose(final BackendConnection conn, final String reason) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("connectionClose " + conn);
        }
        this.waitUntilSendFinish();
        if (checkClosedConn(conn)) {
            return;
        }
        String closeReason = "Connection {DataHost[" + conn.getHost() + ":" + conn.getPort() + "],Schema[" + conn.getSchema() + "],threadID[" +
                ((MySQLConnection) conn).getThreadId() + "]} was closed ,reason is [" + reason + "]";
        this.setFail(closeReason);
        sendData = makeErrorPacket(closeReason);
        boolean[] result = decrementToZeroAndCheckNode(conn);
        boolean finished = result[0];
        boolean justRemoved = result[1];
        if (justRemoved) {
            innerConnectError(conn, finished, "connectionClose");
        }
    }

    private void innerConnectError(BackendConnection conn, boolean finished, String reason) {
        if (conn instanceof MySQLConnection) {
            MySQLConnection mysqlCon = (MySQLConnection) conn;
            LOGGER.info(reason + mysqlCon);
            if (mysqlCon.getXaStatus() == TxState.TX_STARTED_STATE) {
                mysqlCon.close();
                mysqlCon.setXaStatus(TxState.TX_CONN_QUIT);
                XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
                if (finished) {
                    session.setXaState(TxState.TX_ENDED_STATE);
                    nextParse();
                }
            } else if (mysqlCon.getXaStatus() == TxState.TX_ENDED_STATE) {
                mysqlCon.close();
                mysqlCon.setXaStatus(TxState.TX_CONN_QUIT);
                XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
                if (finished) {
                    session.setXaState(TxState.TX_PREPARING_STATE);
                    nextParse();
                }
                //  'xa prepare' connectionClose,conn has quit
            } else if (mysqlCon.getXaStatus() == TxState.TX_PREPARING_STATE) {
                mysqlCon.setXaStatus(TxState.TX_PREPARE_UNCONNECT_STATE);
                XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
                session.setXaState(TxState.TX_PREPARE_UNCONNECT_STATE);
                if (finished) {
                    nextParse();
                }
                // 'xa commit' connectionClose
            } else if (mysqlCon.getXaStatus() == TxState.TX_COMMIT_FAILED_STATE || mysqlCon.getXaStatus() == TxState.TX_PREPARED_STATE) { //TODO:service degradation?
                mysqlCon.setXaStatus(TxState.TX_COMMIT_FAILED_STATE);
                XAStateLog.saveXARecoveryLog(session.getSessionXaID(), mysqlCon);
                session.setXaState(TxState.TX_COMMIT_FAILED_STATE);
                if (finished) {
                    cleanAndFeedback(false);
                }
            } else {
                LOGGER.info("Wrong session XA status " + mysqlCon);
            }
        }
    }

    protected void setResponseTime(boolean isSuccess) {
    }

    protected void nextParse() {
        if (this.isFail() && session.getXaState() != TxState.TX_PREPARE_UNCONNECT_STATE) {
            session.getSource().setTxInterrupt(error);
            session.getSource().write(sendData);
            LOGGER.info("nextParse failed:" + error);
        } else {
            commit();
        }
    }

    private void cleanAndFeedback(boolean isSuccess) {
        if (session.getXaState() == TxState.TX_INITIALIZE_STATE) { // clear all resources
            XAStateLog.saveXARecoveryLog(session.getSessionXaID(), TxState.TX_COMMITTED_STATE);
            session.cancelableStatusSet(NonBlockingSession.CANCEL_STATUS_INIT);
            session.clearResources(false);
            if (session.closed()) {
                return;
            }
            setResponseTime(isSuccess);
            byte[] send = sendData;
            session.getSource().write(send);

            // partially committed,must commit again
        } else if (session.getXaState() == TxState.TX_COMMIT_FAILED_STATE) {
            boolean isAllRelease = session.releaseNormalConns();
            if (session.isKilled()) {
                XAStateLog.saveXARecoveryLog(session.getSessionXaID(), session.getXaState());
                setResponseTime(false);
                session.clearSavepoint();
                session.getSource().write(sendData);
                session.clearResources(true);
                return;
            }
            if (!isAllRelease) {
                final String xaId = session.getSessionXaID();
                XAStateLog.saveXARecoveryLog(xaId, session.getXaState());
                if (DbleServer.getInstance().getConfig().getSystem().getUseSerializableMode() == 1 || ++tryCommitTimes < COMMIT_TIMES) {
                    // try commit several times
                    LOGGER.warn("fail to COMMIT xa transaction " + xaId + " at the " + tryCommitTimes + "th time!");
                    XaDelayProvider.beforeInnerRetry(tryCommitTimes, xaId);
                    commit();
                } else {
                    // close this session ,add to schedule job
                    session.getSource().close("COMMIT FAILED but it will try to COMMIT repeatedly in background until it is success!");
                    // kill xa or retry to commit xa in background
                    final int count = DbleServer.getInstance().getConfig().getSystem().getXaRetryCount();
                    if (!session.isRetryXa()) {
                        String warnStr = "kill xa session by manager cmd!";
                        LOGGER.warn(warnStr);
                        session.forceClose(warnStr);
                    } else if (count == 0 || ++backgroundCommitTimes <= count) {
                        String warnStr = "fail to COMMIT xa transaction " + xaId + " at the " + backgroundCommitTimes + "th time in background!";
                        LOGGER.warn(warnStr);
                        AlertUtil.alertSelf(AlarmCode.XA_BACKGROUND_RETRY_FAIL, Alert.AlertLevel.WARN, warnStr, AlertUtil.genSingleLabel("XA_ID", xaId));

                        XaDelayProvider.beforeAddXaToQueue(count, xaId);
                        XASessionCheck.getInstance().addCommitSession(session);
                        XaDelayProvider.afterAddXaToQueue(count, xaId);
                    }
                }
            } else {
                XAStateLog.saveXARecoveryLog(session.getSessionXaID(), TxState.TX_COMMITTED_STATE);
                session.setXaState(TxState.TX_INITIALIZE_STATE);
                session.cancelableStatusSet(NonBlockingSession.CANCEL_STATUS_INIT);
                byte[] toSend = OkPacket.OK;
                session.clearResources(false);
                AlertUtil.alertSelfResolve(AlarmCode.XA_BACKGROUND_RETRY_FAIL, Alert.AlertLevel.WARN, AlertUtil.genSingleLabel("XA_ID", session.getSessionXaID()));
                // remove session in background
                XASessionCheck.getInstance().getCommittingSession().remove(session.getSource().getId());
                if (!session.closed()) {
                    setResponseTime(isSuccess);
                    session.clearSavepoint();
                    session.getSource().write(toSend);
                }
            }

            // need to rollback;
        } else {
            XAStateLog.saveXARecoveryLog(session.getSessionXaID(), session.getXaState());
            setResponseTime(isSuccess);
            session.clearSavepoint();
            session.getSource().write(sendData);
            LOGGER.info("cleanAndFeedback:" + error);

        }
    }


    public void debugCommitDelay() {
        try {
            if (LOGGER.isDebugEnabled()) {
                long delayTime = 0;
                String xaStatus = "";
                //before the prepare command
                if (session.getXaState() == TxState.TX_ENDED_STATE) {
                    String prepareDelayTime = System.getProperty("PREPARE_DELAY");
                    delayTime = prepareDelayTime == null ? 0 : Long.parseLong(prepareDelayTime) * 1000;
                    xaStatus = "'XA PREPARED'";
                } else if (session.getXaState() == TxState.TX_PREPARED_STATE) {
                    String commitDelayTime = System.getProperty("COMMIT_DELAY");
                    delayTime = commitDelayTime == null ? 0 : Long.parseLong(commitDelayTime) * 1000;
                    xaStatus = "'XA COMMIT'";
                }
                //if using the debug log & using the jvm xa delay properties action will be delay by properties
                if (delayTime > 0) {
                    LOGGER.debug("before xa " + xaStatus + " sleep time = " + delayTime);
                    Thread.sleep(delayTime);
                    LOGGER.debug("before xa " + xaStatus + " sleep finished " + delayTime);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("before xa commit sleep error ");
        }

    }

    private void waitUntilSendFinish() {
        this.lockForErrorHandle.lock();
        try {
            if (!this.sendFinishedFlag) {
                this.sendFinished.await();
            }
        } catch (Exception e) {
            LOGGER.info("back Response is closed by thread interrupted");
        } finally {
            lockForErrorHandle.unlock();
        }
    }

}
