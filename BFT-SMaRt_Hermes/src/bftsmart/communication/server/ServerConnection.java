/**
 * Copyright (c) 2007-2009 Alysson Bessani, Eduardo Alchieri, Paulo Sousa, and
 * the authors indicated in the
 *
 * @author tags
 *
 * This file is part of SMaRt.
 *
 * SMaRt is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 *
 * SMaRt is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * SMaRt. If not, see <http://www.gnu.org/licenses/>.
 */
package bftsmart.communication.server;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import bftsmart.communication.SystemMessage;
import bftsmart.paxosatwar.messages.PaxosMessage;
import bftsmart.reconfiguration.ServerViewManager;
import bftsmart.reconfiguration.TTPMessage;
import bftsmart.tom.ServiceReplica;
import bftsmart.tom.leaderchange.LCMessage;
import bftsmart.tom.util.Logger;
import bftsmart.tom.util.TOMUtil;
import hermes.runtime.HermesRuntime;
import hermes.serialization.HermesSerializableHelper;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.logging.Level;

/**
 * This class represents a connection with other server.
 *
 * ServerConnections are created by ServerCommunicationLayer.
 *
 * @author alysson
 */
public class ServerConnection {

    private static final String PASSWORD = "commsyst";
    private static final String MAC_ALGORITHM = "HmacMD5";
    private static final long POOL_TIME = 5000;
    //private static final int SEND_QUEUE_SIZE = 50;
    private ServerViewManager manager;
    private Socket socket;
    private DataOutputStream socketOutStream = null;
    private DataInputStream socketInStream = null;
    private int remoteId;
    private boolean useSenderThread;
    protected LinkedBlockingQueue<byte[]> outQueue;// = new LinkedBlockingQueue<byte[]>(SEND_QUEUE_SIZE);
    private HashSet<Integer> noMACs = null; // this is used to keep track of data to be sent without a MAC.
    // It uses the reference id for that same data
    private LinkedBlockingQueue<SystemMessage> inQueue;
    private SecretKey authKey;
    private Mac macSend;
    private Mac macReceive;
    private int macSize;
    private Lock connectLock = new ReentrantLock();
    /**
     * Only used when there is no sender Thread
     */
    private Lock sendLock;
    private boolean doWork = true;

    public ServerConnection(ServerViewManager manager, Socket socket, int remoteId,
            LinkedBlockingQueue<SystemMessage> inQueue, ServiceReplica replica) {

        this.manager = manager;

        this.socket = socket;

        this.remoteId = remoteId;

        this.inQueue = inQueue;

        this.outQueue = new LinkedBlockingQueue<byte[]>(this.manager.getStaticConf().getOutQueueSize());

        this.noMACs = new HashSet<Integer>();
        //******* EDUARDO BEGIN **************//
        // Connect to the remote process or just wait for the connection?
        if (isToConnect()) {
            //I have to connect to the remote server
            try {
                //System.out.println("**********");
                //System.out.println(remoteId);
                //System.out.println(this.manager.getStaticConf().getServerToServerPort(remoteId));
                //System.out.println(this.manager.getStaticConf().getHost(remoteId));
                this.socket = new Socket(this.manager.getStaticConf().getHost(remoteId),
                        this.manager.getStaticConf().getServerToServerPort(remoteId));
                ServersCommunicationLayer.setSocketOptions(this.socket);
                new DataOutputStream(this.socket.getOutputStream()).writeInt(this.manager.getStaticConf().getProcessId());

            } catch (UnknownHostException ex) {
                ex.printStackTrace();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
        //else I have to wait a connection from the remote server
        //******* EDUARDO END **************//

        if (this.socket != null) {
            try {
                socketOutStream = new DataOutputStream(this.socket.getOutputStream());
                socketInStream = new DataInputStream(this.socket.getInputStream());
            } catch (IOException ex) {
                Logger.println("Error creating connection to " + remoteId);
                ex.printStackTrace();
            }
        }

        //******* EDUARDO BEGIN **************//
        this.useSenderThread = this.manager.getStaticConf().isUseSenderThread();

        if (useSenderThread && (this.manager.getStaticConf().getTTPId() != remoteId)) {
            new SenderThread().start();
        } else {
            sendLock = new ReentrantLock();
        }
        authenticateAndEstablishAuthKey();

        if (!this.manager.getStaticConf().isTheTTP()) {
            if (this.manager.getStaticConf().getTTPId() == remoteId) {
                //Uma thread "diferente" para as msgs recebidas da TTP
                new TTPReceiverThread(replica).start();
            } else {
                new ReceiverThread().start();
            }
        }
        //******* EDUARDO END **************//
    }

    public SecretKey getSecretKey() {
        return authKey;
    }

    /**
     * Stop message sending and reception.
     */
    public void shutdown() {
        Logger.println("SHUTDOWN for " + remoteId);

        doWork = false;
        closeSocket();
    }

    /**
     * Used to send packets to the remote server.
     */
    public final void send(byte[] data, boolean useMAC) throws InterruptedException {
        if (useSenderThread) {
            //only enqueue messages if there queue is not full
            if (!useMAC) {
                Logger.println("(ServerConnection.send) Not sending defaultMAC " + System.identityHashCode(data));
                noMACs.add(System.identityHashCode(data));
            }

            if (!outQueue.offer(data)) {
                Logger.println("(ServerConnection.send) out queue for " + remoteId + " full (message discarded).");
            }
        } else {
            sendLock.lock();
            sendBytes(data, useMAC);
            sendLock.unlock();
        }
    }

    /**
     * try to send a message through the socket if some problem is detected, a
     * reconnection is done
     */
    //TODO: ROL MODIFY THIS TO ALLOW ATTACK 1
    private final void sendBytes(byte[] messageData, boolean useMAC) {
        int i = 0;
        boolean abort = false;
        do {
            if (abort) {
                return; // if there is a need to reconnect, abort this method
            }
            if (socket != null && socketOutStream != null) {
                try {
                    //do an extra copy of the data to be sent, but on a single out stream write
                    byte[] mac = (useMAC && this.manager.getStaticConf().getUseMACs() == 1) ? macSend.doFinal(messageData) : null;
                    byte[] data = new byte[5 + messageData.length + ((mac != null) ? mac.length : 0)];
                    int value = messageData.length;
                    //ROL: crash 0 or 1 with 7,2 will hang protocol  
                    // ROL: attack 1, forge payload size, allows overflow; negative size, and len < real_size
                    // ROL craches receive thread, but alg. progresses
                    //boolean attack1 = false; //ROL
//                    int tempValue = value;
//                    boolean attack4 = false; //ROL
//                    //ROL: forging mac algorithm goes in infinite loop
//                    //on a second request from client
//                    boolean attack5 = false;
//                    boolean attack6 = false;
//                    boolean attack7 = false;
//                    boolean attack9 = false;
//                    int duplicationPerInvocation = 100;
//                    Integer a = (Integer) HermesRuntime.getInstance().getContext().getObject("attack");
//                    //if(this.remoteId !=0 && remoteId!=1){
//                    if (a != null) {
//                        String[] m = (String[]) HermesRuntime.getInstance().getContext().getObject("f");
//                        if (!HermesSerializableHelper.isMalicious(remoteId, m)) {
//                            switch (a) {
//                                case 4: {
//                                    System.out.println("attack Corrupt "+a+":" + HermesRuntime.getInstance().getRuntimeID());
//                                    attack4 = true;
//                                    value = 1024 * 1024 * 1250; //deploys memory
//                                    break;
//                                }
//                                case 5: {
//                                    System.out.println("attack Corrupt "+a+":" + HermesRuntime.getInstance().getRuntimeID());
//                                    attack5 = true;
//                                    value = -1;
//                                    break;
//                                }
//                                case 6:
//                                case 7:
//                                case 8: { //and 7
//                                    System.out.println("attack Corrupt "+a+":" + HermesRuntime.getInstance().getRuntimeID());
//                                    attack6 = true;
//                                    for (int ii = 0; ii < messageData.length; ii++) {
//                                        messageData[ii] = 0;
//                                    }
//
//                                    break;
//                                }
//
//                                case 9: { //and 7
//                                    System.out.println("attack Corrupt 9:" + HermesRuntime.getInstance().getRuntimeID());
//                                    attack9 = true;                                    
//                                    break;
//                                }
//                                default:
//                                    break;
//                            }
//                        }
//                    }
                    //}

                    //ROL attack3, delay send
                    //on a second request from client
                    //the affected process does not recover (catch-up) - confirm!
                    //int tempValue = value;
                    //if(attack1_1 && remoteId == 1 && manager.getStaticConf().getProcessId()==2){
//                    if (attack4 && manager.getStaticConf().getProcessId() == 2) {
//                        //value = 2147483647; // just blows out receiver thread
//                        value = 1024 * 1024 * 1250; //deploys memory
//                        //value = 15; //blocks receive thread, no impact on algorithm
//                        //value = -1;
//                    }
//                    //No problem for sleep,
//                    if (attack5 && (manager.getStaticConf().getProcessId() == 0 || manager.getStaticConf().getProcessId() == 1)) {
//                        System.out.println("ATTACK " + remoteId + " " + manager.getStaticConf().getProcessId());
//                        try {
//                            //mac[0] = (byte)(mac[0] >> 1);
//                            //mac[5] = (byte)(mac[5] >> 2);
//                            //for(int ii=0; ii < messageData.length; ii++){
//                            //messageData[ii] = 0;
//                            //}
//                            Thread.sleep(0);
//                        } catch (InterruptedException ex) {
//                            java.util.logging.Logger.getLogger(ServerConnection.class.getName()).log(Level.SEVERE, null, ex);
//                        }
//                    }
                    System.arraycopy(new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value}, 0, data, 0, 4);
                    //value = tempValue; //ROL
                    System.arraycopy(messageData, 0, data, 4, messageData.length);
                    if (mac != null) {
                        //System.arraycopy(mac,0,data,4+messageData.length,mac.length);
                        System.arraycopy(new byte[]{(byte) 1}, 0, data, 4 + messageData.length, 1);
                        System.arraycopy(mac, 0, data, 5 + messageData.length, mac.length);
                    } else {
                        System.arraycopy(new byte[]{(byte) 0}, 0, data, 4 + messageData.length, 1);
                    }

                    socketOutStream.write(data);
//                    if(attack9){
//                        System.out.println("attack Corrupt 9 100 packets per invocation:" + HermesRuntime.getInstance().getRuntimeID());
//                        for(int attacki=0; attacki < duplicationPerInvocation-1; attacki++){
//                             socketOutStream.write(data);
//                        }
//                    }

                    return;
                } catch (IOException ex) {
                    closeSocket();
                    waitAndConnect();
                    abort = true;
                }
            } else {
                waitAndConnect();
                abort = true;
            }
            //br.ufsc.das.tom.util.Logger.println("(ServerConnection.sendBytes) iteration " + i);
            i++;
        } while (doWork);
    }

    //TODO: ROL MODIFY THIS TO ALLOW ATTACK 1
    public final void aspectSendBytes(Integer a, byte[] messageData, boolean useMAC) {
        int i = 0;
        boolean abort = false;
        do {
            if (abort) {
                return; // if there is a need to reconnect, abort this method
            }
            if (socket != null && socketOutStream != null) {
                try {
                    //do an extra copy of the data to be sent, but on a single out stream write
                    byte[] mac = (useMAC && this.manager.getStaticConf().getUseMACs() == 1) ? macSend.doFinal(messageData) : null;
                    byte[] data = new byte[5 + messageData.length + ((mac != null) ? mac.length : 0)];
                    int value = messageData.length;
                    //ROL: crash 0 or 1 with 7,2 will hang protocol  
                    // ROL: attack 1, forge payload size, allows overflow; negative size, and len < real_size
                    // ROL craches receive thread, but alg. progresses
                    //boolean attack1 = false; //ROL
                    int tempValue = value;
                    boolean attack4 = false; //ROL
                    //ROL: forging mac algorithm goes in infinite loop
                    //on a second request from client
                    boolean attack5 = false;
                    boolean attack6 = false;
                    boolean attack7 = false;
                    boolean attack9 = false;
                    int duplicationPerInvocation = 100;
                    //Integer a = (Integer) HermesRuntime.getInstance().getContext().getObject("attack");
                    //if(this.remoteId !=0 && remoteId!=1){
                    if (a != null) {
                        String[] m = (String[]) HermesRuntime.getInstance().getContext().getObject("f");
                        if (!HermesSerializableHelper.isMalicious(remoteId, m)) {
                            switch (a) {
                                case 4: {
                                    System.out.println("attack Corrupt " + a + ":" + HermesRuntime.getInstance().getRuntimeID());
                                    attack4 = true;
                                    value = 1024 * 1024 * 1250; //deploys memory
                                    break;
                                }
                                case 5: {
                                    System.out.println("attack Corrupt " + a + ":" + HermesRuntime.getInstance().getRuntimeID());
                                    attack5 = true;
                                    value = -1;
                                    break;
                                }
                                case 6:
                                case 7:
                                case 8: { //and 7
                                    System.out.println("attack Corrupt " + a + ":" + HermesRuntime.getInstance().getRuntimeID());
                                    attack6 = true;
                                    for (int ii = 0; ii < messageData.length; ii++) {
                                        messageData[ii] = 0;
                                    }

                                    break;
                                }

                                case 9: { //and 7
                                    System.out.println("attack Corrupt 9:" + HermesRuntime.getInstance().getRuntimeID());
                                    attack9 = true;
                                    break;
                                }
                                default:
                                    break;
                            }
                        }
                    }
                    //}

                    //ROL attack3, delay send
                    //on a second request from client
                    //the affected process does not recover (catch-up) - confirm!
                    //int tempValue = value;
                    //if(attack1_1 && remoteId == 1 && manager.getStaticConf().getProcessId()==2){
//                    if (attack4 && manager.getStaticConf().getProcessId() == 2) {
//                        //value = 2147483647; // just blows out receiver thread
//                        value = 1024 * 1024 * 1250; //deploys memory
//                        //value = 15; //blocks receive thread, no impact on algorithm
//                        //value = -1;
//                    }
//                    //No problem for sleep,
//                    if (attack5 && (manager.getStaticConf().getProcessId() == 0 || manager.getStaticConf().getProcessId() == 1)) {
//                        System.out.println("ATTACK " + remoteId + " " + manager.getStaticConf().getProcessId());
//                        try {
//                            //mac[0] = (byte)(mac[0] >> 1);
//                            //mac[5] = (byte)(mac[5] >> 2);
//                            //for(int ii=0; ii < messageData.length; ii++){
//                            //messageData[ii] = 0;
//                            //}
//                            Thread.sleep(0);
//                        } catch (InterruptedException ex) {
//                            java.util.logging.Logger.getLogger(ServerConnection.class.getName()).log(Level.SEVERE, null, ex);
//                        }
//                    }
                    System.arraycopy(new byte[]{(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value}, 0, data, 0, 4);
                    value = tempValue; //ROL
                    System.arraycopy(messageData, 0, data, 4, messageData.length);
                    if (mac != null) {
                        //System.arraycopy(mac,0,data,4+messageData.length,mac.length);
                        System.arraycopy(new byte[]{(byte) 1}, 0, data, 4 + messageData.length, 1);
                        System.arraycopy(mac, 0, data, 5 + messageData.length, mac.length);
                    } else {
                        System.arraycopy(new byte[]{(byte) 0}, 0, data, 4 + messageData.length, 1);
                    }

                    socketOutStream.write(data);
                    if (attack9) {
                        System.out.println("attack Corrupt 9 100 packets per invocation:" + HermesRuntime.getInstance().getRuntimeID());
                        for (int attacki = 0; attacki < duplicationPerInvocation - 1; attacki++) {
                            socketOutStream.write(data);
                        }
                    }

                    return;
                } catch (IOException ex) {
                    closeSocket();
                    waitAndConnect();
                    abort = true;
                }
            } else {
                waitAndConnect();
                abort = true;
            }
            //br.ufsc.das.tom.util.Logger.println("(ServerConnection.sendBytes) iteration " + i);
            i++;
        } while (doWork);
    }

    //******* EDUARDO BEGIN **************//
    //return true of a process shall connect to the remote process, false otherwise
    private boolean isToConnect() {
        if (this.manager.getStaticConf().getTTPId() == remoteId) {
            //Need to wait for the connection request from the TTP, do not tray to connect to it
            return false;
        } else if (this.manager.getStaticConf().getTTPId() == this.manager.getStaticConf().getProcessId()) {
            //If this is a TTP, one must connect to the remote process
            return true;
        }
        boolean ret = false;
        if (this.manager.isInCurrentView()) {
            boolean me = this.manager.isInLastJoinSet(this.manager.getStaticConf().getProcessId());
            boolean remote = this.manager.isInLastJoinSet(remoteId);

            //either both endpoints are old in the system (entered the system in a previous view),
            //or both entered during the last reconfiguration
            if ((me && remote) || (!me && !remote)) {
                //in this case, the node with higher ID starts the connection
                if (this.manager.getStaticConf().getProcessId() > remoteId) {
                    ret = true;
                }
                //this process is the older one, and the other one entered in the last reconfiguration
            } else if (!me && remote) {
                ret = true;

            } //else if (me && !remote) { //this process entered in the last reconfig and the other one is old
            //ret=false; //not necessary, as ret already is false
            //}
        }
        return ret;
    }
    //******* EDUARDO END **************//

    /**
     * (Re-)establish connection between peers.
     *
     * @param newSocket socket created when this server accepted the connection
     * (only used if processId is less than remoteId)
     */
    protected void reconnect(Socket newSocket) {

        connectLock.lock();

        if (socket == null || !socket.isConnected()) {

            try {

                //******* EDUARDO BEGIN **************//
                if (isToConnect()) {

                    socket = new Socket(this.manager.getStaticConf().getHost(remoteId),
                            this.manager.getStaticConf().getServerToServerPort(remoteId));
                    ServersCommunicationLayer.setSocketOptions(socket);
                    new DataOutputStream(socket.getOutputStream()).writeInt(this.manager.getStaticConf().getProcessId());

                    //******* EDUARDO END **************//
                } else {
                    socket = newSocket;
                }
            } catch (UnknownHostException ex) {
                ex.printStackTrace();
            } catch (IOException ex) {

                System.out.println("Impossible to reconnect to replica " + remoteId);
                //ex.printStackTrace();
            }

            if (socket != null) {
                try {
                    socketOutStream = new DataOutputStream(socket.getOutputStream());
                    socketInStream = new DataInputStream(socket.getInputStream());
                } catch (IOException ex) {
                    ex.printStackTrace();
                }
            }

            //authenticateAndEstablishAuthKey();
        }

        connectLock.unlock();
    }

    //TODO!
    public void authenticateAndEstablishAuthKey() {
        if (authKey != null) {
            return;
        }

        try {
            //if (conf.getProcessId() > remoteId) {
            // I asked for the connection, so I'm first on the auth protocol
            //DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            //} else {
            // I received a connection request, so I'm second on the auth protocol
            //DataInputStream dis = new DataInputStream(socket.getInputStream());
            //}

            SecretKeyFactory fac = SecretKeyFactory.getInstance("PBEWithMD5AndDES");
            PBEKeySpec spec = new PBEKeySpec(PASSWORD.toCharArray());
            authKey = fac.generateSecret(spec);

            macSend = Mac.getInstance(MAC_ALGORITHM);
            macSend.init(authKey);
            macReceive = Mac.getInstance(MAC_ALGORITHM);
            macReceive.init(authKey);
            macSize = macSend.getMacLength();
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void closeSocket() {
        if (socket != null) {
            try {
                socketOutStream.flush();
                socket.close();
            } catch (IOException ex) {
                Logger.println("Error closing socket to " + remoteId);
            }

            socket = null;
            socketOutStream = null;
            socketInStream = null;
        }
    }

    private void waitAndConnect() {
        if (doWork) {
            try {
                Thread.sleep(POOL_TIME);
            } catch (InterruptedException ie) {
            }

            outQueue.clear();
            reconnect(null);


        }
    }

    /**
     * Thread used to send packets to the remote server.
     */
    private class SenderThread extends Thread {

        public SenderThread() {
            super("Sender for " + remoteId);
        }

        @Override
        public void run() {
            byte[] data = null;

            while (doWork) {
                //get a message to be sent
                try {
                    data = outQueue.poll(POOL_TIME, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                }

                if (data != null) {
                    //sendBytes(data, noMACs.contains(System.identityHashCode(data)));
                    int ref = System.identityHashCode(data);
                    boolean sendMAC = !noMACs.remove(ref);
                    Logger.println("(ServerConnection.run) " + (sendMAC ? "Sending" : "Not sending") + " MAC for data " + ref);
                    sendBytes(data, sendMAC);
                }
            }

            Logger.println("Sender for " + remoteId + " stopped!");
        }
    }

    /**
     * Thread used to receive packets from the remote server.
     */
    protected class ReceiverThread extends Thread {

        public ReceiverThread() {
            super("Receiver for " + remoteId);
        }

        @Override
        public void run() {
            byte[] receivedMac = null;
            try {
                receivedMac = new byte[Mac.getInstance(MAC_ALGORITHM).getMacLength()];
            } catch (NoSuchAlgorithmException ex) {
                ex.printStackTrace();
            }

            while (doWork) {
                if (socket != null && socketInStream != null) {
                    try {
                        //read data length
                        int dataLength = socketInStream.readInt();
                        byte[] data = new byte[dataLength];

                        //read data
                        int read = 0;
                        do {
                            read += socketInStream.read(data, read, dataLength - read);
                        } while (read < dataLength);

                        //read mac
                        boolean result = true;

                        byte hasMAC = socketInStream.readByte();
                        if (manager.getStaticConf().getUseMACs() == 1 && hasMAC == 1) {
                            read = 0;
                            do {
                                read += socketInStream.read(receivedMac, read, macSize - read);
                            } while (read < macSize);

                            result = Arrays.equals(macReceive.doFinal(data), receivedMac);
                        }

                        if (result) {
                            SystemMessage sm = (SystemMessage) (new ObjectInputStream(new ByteArrayInputStream(data)).readObject());
                            sm.authenticated = (manager.getStaticConf().getUseMACs() == 1 && hasMAC == 1);

                            if (sm.getSender() == remoteId) {
                                if (!inQueue.offer(sm)) {
                                    Logger.println("(ReceiverThread.run) in queue full (message from " + remoteId + " discarded).");
                                    System.out.println("(ReceiverThread.run) in queue full (message from " + remoteId + " discarded).");
                                }
                            }
                        } else {
                            //TODO: violation of authentication... we should do something
                            Logger.println("WARNING: Violation of authentication in message received from " + remoteId);
                            reconfigure();
                            doWork = false;
                            closeSocket();
                            return;

                        }
                    } catch (ClassNotFoundException ex) {
                        //invalid message sent, just ignore;
                    } catch (IOException ex) {
                        if (doWork) {
                            Logger.println("Closing socket and reconnecting");
                            reconfigure();                            
                            closeSocket();
                            waitAndConnect();
                        }
                    }
                } else {
                    waitAndConnect();
                }
            }
        }

        private void reconfigure() {
            int[] myself = new int[1];
            myself[0] = manager.getStaticConf().getProcessId();
            //manager.
            LCMessage lcMessage = new LCMessage(-1, TOMUtil.TRIGGER_LC_LOCALLY, -1, null);
            if (manager.tomLayer.lm.getCurrentLeader() == remoteId) {
                System.out.println("WARNING: Trigger run_lc_protocol " + remoteId);
                while (!inQueue.offer(lcMessage)) {
                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ex) {
                        java.util.logging.Logger.getLogger(ServerConnection.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
                System.out.println("WARNING: After Trigger run_lc_protocol " + remoteId);
            }
        }
    }

//******* EDUARDO BEGIN: special thread for receiving messages indicating the entrance into the system, coming from the TTP **************//
// Simly pass the messages to the replica, indicating its entry into the system
//TODO: Ask eduardo why a new thread is needed!!! 
//TODO2: Remove all duplicated code
    /**
     * Thread used to receive packets from the remote server.
     */
    protected class TTPReceiverThread extends Thread {

        private ServiceReplica replica;

        public TTPReceiverThread(ServiceReplica replica) {
            super("TTPReceiver for " + remoteId);
            this.replica = replica;
        }

        @Override
        public void run() {
            byte[] receivedMac = null;
            try {
                receivedMac = new byte[Mac.getInstance(MAC_ALGORITHM).getMacLength()];
            } catch (NoSuchAlgorithmException ex) {
            }

            while (doWork) {
                if (socket != null && socketInStream != null) {
                    try {
                        //read data length
                        int dataLength = socketInStream.readInt();

                        byte[] data = new byte[dataLength];

                        //read data
                        int read = 0;
                        do {
                            read += socketInStream.read(data, read, dataLength - read);
                        } while (read < dataLength);

                        //read mac
                        boolean result = true;

                        byte hasMAC = socketInStream.readByte();
                        if (manager.getStaticConf().getUseMACs() == 1 && hasMAC == 1) {

                            System.out.println("TTP CON USEMAC");
                            read = 0;
                            do {
                                read += socketInStream.read(receivedMac, read, macSize - read);
                            } while (read < macSize);

                            result = Arrays.equals(macReceive.doFinal(data), receivedMac);
                        }

                        if (result) {
                            SystemMessage sm = (SystemMessage) (new ObjectInputStream(new ByteArrayInputStream(data)).readObject());

                            if (sm.getSender() == remoteId) {
                                //System.out.println("Mensagem recebia de: "+remoteId);
                                /*if (!inQueue.offer(sm)) {
                                 bftsmart.tom.util.Logger.println("(ReceiverThread.run) in queue full (message from " + remoteId + " discarded).");
                                 System.out.println("(ReceiverThread.run) in queue full (message from " + remoteId + " discarded).");
                                 }*/
                                this.replica.joinMsgReceived((TTPMessage) sm);
                            }
                        } else {
                            //TODO: violation of authentication... we should do something
                            Logger.println("WARNING: Violation of authentication in message received from " + remoteId);
                        }
                    } catch (ClassNotFoundException ex) {
                        ex.printStackTrace();
                    } catch (IOException ex) {
                        //ex.printStackTrace();
                        if (doWork) {
                            closeSocket();
                            waitAndConnect();
                        }
                    }
                } else {
                    waitAndConnect();
                }
            }
        }
    }
//******* EDUARDO END **************//
}
