package io.questdb.cairo.replication;

import java.io.Closeable;

import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.mp.RingQueue;
import io.questdb.mp.Sequence;
import io.questdb.std.IntList;
import io.questdb.std.Misc;
import io.questdb.std.ObjList;
import io.questdb.std.ObjectFactory;

abstract class ReplicationPeerDetails implements Closeable {
    private static final Log LOG = LogFactory.getLog(ReplicationPeerDetails.class);
    private long peerId = Long.MIN_VALUE;
    private int nWorkers;
    private final ConnectionJobQueue[] connectionConsumerQueues;
    private final ObjectFactory<PeerConnection> connectionFactory;
    private IntList nAssignedByWorkerId = new IntList();
    private ObjList<PeerConnection> connections;
    private ObjList<PeerConnection> connectionCache;

    interface PeerConnection extends Closeable {
        PeerConnection of(long peerId, long fd, int workerId);

        long getFd();

        int getWorkertId();

        void clear();
    }

    interface ConnectionJobEvent {
        void assignAddConnection(PeerConnection connection);
    }

    static class ConnectionJobQueue {
        private final Sequence producerSeq;
        private final Sequence consumerSeq;
        private final RingQueue<?> queue;

        <T> ConnectionJobQueue(Sequence producerSeq, Sequence consumerSeq, RingQueue<T> queue) {
            super();
            this.producerSeq = producerSeq;
            this.consumerSeq = consumerSeq;
            this.queue = queue;
        }

        public Sequence getProducerSeq() {
            return producerSeq;
        }

        public Sequence getConsumerSeq() {
            return consumerSeq;
        }

        @SuppressWarnings("unchecked")
        public <T> T getEvent(long seq) {
            return (T) queue.get(seq);
        }
    }

    ReplicationPeerDetails(long peerId, int nWorkers, ConnectionJobQueue[] connectionConsumerQueues, ObjectFactory<PeerConnection> connectionFactory) {
        super();
        this.peerId = peerId;
        this.nWorkers = nWorkers;
        this.connectionConsumerQueues = connectionConsumerQueues;
        this.connectionFactory = connectionFactory;
        nAssignedByWorkerId = new IntList(nWorkers);
        for (int nWorker = 0; nWorker < nWorkers; nWorker++) {
            nAssignedByWorkerId.add(0);
        }
        connections = new ObjList<>();
        connectionCache = new ObjList<>();
    }

    boolean tryAddConnection(long fd) {
        int nMinAssigned = Integer.MAX_VALUE;
        int workerId = Integer.MAX_VALUE;
        for (int nWorker = 0; nWorker < nWorkers; nWorker++) {
            int nAssigned = nAssignedByWorkerId.getQuick(nWorker);
            if (nAssigned < nMinAssigned) {
                nMinAssigned = nAssigned;
                workerId = nWorker;
            }
        }

        ConnectionJobQueue queue = connectionConsumerQueues[workerId];
        long seq = queue.producerSeq.next();
        if (seq >= 0) {
            try {
                ConnectionJobEvent event = queue.getEvent(seq);
                PeerConnection connection;
                if (connectionCache.size() > 0) {
                    int n = connectionCache.size() - 1;
                    connection = connectionCache.getQuick(n);
                    connectionCache.remove(n);
                } else {
                    connection = connectionFactory.newInstance();
                }
                connection.of(peerId, fd, workerId);
                event.assignAddConnection(connection);
                nAssignedByWorkerId.set(workerId, nAssignedByWorkerId.getQuick(workerId) + 1);
                connections.add(connection);
            } finally {
                queue.producerSeq.done(seq);
            }
            LOG.info().$("assigned connection [workerId=").$(workerId).$(", fd=").$(fd).$(']').$();
            return true;
        }

        return false;
    }

    void removeConnection(long fd) {
        for (int n = 0, sz = connections.size(); n < sz; n++) {
            PeerConnection slaveConnection = connections.get(n);
            if (slaveConnection.getFd() == fd) {
                connections.remove(n);
                nAssignedByWorkerId.set(slaveConnection.getWorkertId(), nAssignedByWorkerId.getQuick(slaveConnection.getWorkertId()) - 1);
                slaveConnection.clear();
                connectionCache.add(slaveConnection);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    <T extends PeerConnection> T getConnection(int concurrencyId) {
        int connectionId = concurrencyId % connections.size();
        return (T) connections.getQuick(connectionId);
    }

    @Override
    public void close() {
        if (null != connections) {
            peerId = Long.MIN_VALUE;
            Misc.freeObjList(connectionCache);
            connectionCache = null;
            Misc.freeObjList(connections);
            connections = null;
            nAssignedByWorkerId.clear();
            nAssignedByWorkerId = null;
        }
    }
}