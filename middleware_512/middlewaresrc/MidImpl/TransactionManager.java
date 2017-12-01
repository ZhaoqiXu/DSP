package MidImpl;
import ResInterface.*;
import MidInterface.*;
import LockManager.*;

import java.util.*;

// import java.io.PrintWriter;
import java.io.Serializable;
import java.rmi.registry.Registry;
import java.rmi.registry.LocateRegistry;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.ConnectException;
import java.rmi.RMISecurityManager;


public class TransactionManager implements Serializable
{
    public final int MW_NUM = 0;
    public final int FLIGHT_NUM = 1;
    public final int CAR_NUM = 2;
    public final int ROOM_NUM = 3;
    public final int READ = 0;
    public final int WRITE = 1;
    protected LockManager mw_locks = new LockManager();
    protected int txn_counter = 0;
    public Hashtable<Integer,Transaction> active_txn = new Hashtable<Integer, Transaction>();
    public  Hashtable<Integer,LogFile> active_log = new Hashtable<Integer, LogFile>();
    public Hashtable<Integer, Boolean> all_vote_yes = new Hashtable<Integer, Boolean>();

    protected ResourceManager rm_flight = null;
    protected ResourceManager rm_car = null;
    protected ResourceManager rm_room = null;
    protected MiddleWare mw = null;
    protected int crash_mode = 0;
    public String tm_name = "TM";

    public TransactionManager() {
    }

    public TransactionManager(MiddleWare mw, ResourceManager rm_flight, ResourceManager rm_car, ResourceManager rm_room)
    {
        this.mw = mw;
        this.rm_flight = rm_flight;
        this.rm_car = rm_car;
        this.rm_room = rm_room;
    }

    public void setFlightRM(ResourceManager rm)
    {
        rm_flight = rm;
    }
    public void setCarRM(ResourceManager rm)
    {
        rm_car = rm;
    }
    public void setRoomRM(ResourceManager rm)
    {
        rm_room = rm;
    }
    public void setMW(MiddleWare mw)
    {
        this.mw = mw;
    }
    
    public int start() throws RemoteException {
        this.txn_counter ++;
        int id = this.txn_counter;
        synchronized(this.active_txn) {
            // while (this.active_txn.containsKey(id) || id == 1) {
            //     id = new Random().nextInt(100000) + 1; //+1 because can return 0
            // }
            Transaction txn = new Transaction(id);
            TimeThread tt = new TimeThread(this, id);
            tt.start();
            this.active_txn.put(id, txn);
            LogFile log = new LogFile(id);
            this.active_log.put(id, log);
            this.all_vote_yes.put(id, false);
        }
        IOTools.saveToDisk(this, "TransactionManager.txt");
        IOTools.saveToDisk(this.active_txn, "TMActive.txt");
        return id;
    }

    public boolean commit(int transactionId) 
        throws RemoteException, TransactionAbortedException, InvalidTransactionException
    {
        synchronized(this.active_txn) {
            if (transactionId < 1 || !this.active_txn.containsKey(transactionId)) {
                Trace.warn("RM::Commit failed--Invalid transactionId");
                throw new InvalidTransactionException(transactionId);
            }
            else
            {
                mw_locks.UnlockAll(transactionId);
                IOTools.saveToDisk(this, "TransactionManager.txt");
                IOTools.saveToDisk(this.active_txn, "TMActive.txt");
                Trace.info("TM::Committing transaction : " + transactionId);
                
                try {java.lang.Thread.sleep(100);}
                catch(Exception e) {}        
                String record = "BEFORE_SENDING_REQUEST";
                this.active_log.get(transactionId).record.add(record);
                IOTools.saveToDisk(this.active_log.get(transactionId), tm_name + "_" + Integer.toString(transactionId) + ".log");
                if (crash_mode == 4) return selfDestruct(crash_mode);
                // check rm_list
                try {
                    int answers = 0;
                    HashSet<Integer> rms = this.active_txn.get(transactionId).rm_list;
                    
                    if (rms.contains(MW_NUM))
                    {
                        answers += this.mw.prepare(transactionId);
                        if (!this.active_log.get(transactionId).record.contains("SOME_REPLIED"))
                        {
                            java.lang.Thread.sleep(100);
                            record = "SOME_REPLIED";
                            this.active_log.get(transactionId).record.add(record);
                            IOTools.saveToDisk(this.active_log.get(transactionId), tm_name + "_" + Integer.toString(transactionId) + ".log");
                            if (crash_mode == 5) return selfDestruct(crash_mode);
                        }
                    }
                    for (int rm_num : rms)
                    {
                        if (rm_num == FLIGHT_NUM) {
                            answers += this.rm_flight.prepare(transactionId);
                        }
                        else if (rm_num == CAR_NUM) { 
                            answers += this.rm_car.prepare(transactionId);
                        }
                        else if (rm_num == ROOM_NUM) {
                            answers += this.rm_room.prepare(transactionId);
                        }
                        if (!this.active_log.get(transactionId).record.contains("SOME_REPLIED"))
                        {
                            java.lang.Thread.sleep(100);
                            record = "SOME_REPLIED";
                            this.active_log.get(transactionId).record.add(record);
                            IOTools.saveToDisk(this.active_log.get(transactionId), tm_name + "_" + Integer.toString(transactionId) + ".log");
                            if (crash_mode == 5) return selfDestruct(crash_mode);
                        }
                    }
                    java.lang.Thread.sleep(100);
                    record = "AFTER_REPLIES_BEFORE_DECISION";
                    this.active_log.get(transactionId).record.add(record);
                    IOTools.saveToDisk(this.active_log.get(transactionId), tm_name + "_" + Integer.toString(transactionId) + ".log");
                    if (crash_mode == 6) return selfDestruct(crash_mode);

                    if (answers == rms.size())
                    {
                        this.all_vote_yes.put(transactionId, true);
                        IOTools.saveToDisk(this, "TransactionManager.txt");
                        IOTools.saveToDisk(this.active_txn, "TMActive.txt");
                        java.lang.Thread.sleep(100);
                        record = "BEFORE_COMMIT";
                        this.active_log.get(transactionId).record.add(record);
                        // if (this.active_log.get(transactionId) == null) System.out.println("RECORD NULL");
                        // else System.out.println("RECORD NOT NULL");
                        IOTools.saveToDisk(this.active_log.get(transactionId), tm_name + "_" + Integer.toString(transactionId) + ".log");
                        // LogFile tmp = (LogFile) IOTools.loadFromDisk(tm_name + "_" + Integer.toString(transactionId) + ".log");
                        // System.out.println("LogFile Size : " + tmp.record.size());
                        if (crash_mode == 7) return selfDestruct(crash_mode);

                        for (int rm_num : rms)
                        {
                            if (rm_num == MW_NUM) {
                                try {
                                    this.mw.local_commit(transactionId);
                                } catch (Exception e) {
                                    if (e instanceof ConnectException) 
                                    {
                                        System.out.println("Middleware Crashed");
                                    }
                                    if (e instanceof InvalidTransactionException)
                                    {
                                        System.out.println(e.getMessage());
                                    }
                                }
                            }
                            else if (rm_num == FLIGHT_NUM) {
                                try {
                                    this.rm_flight.commit(transactionId);
                                } catch (Exception e) {
                                    if (e instanceof ConnectException) 
                                    {
                                        System.out.println("Flight RM Crashed");
                                    }
                                    if (e instanceof InvalidTransactionException)
                                    {
                                        System.out.println(e.getMessage());
                                    }
                                }
                            }
                            else if (rm_num == CAR_NUM) {
                                try{
                                    this.rm_car.commit(transactionId);
                                } catch (Exception e) {
                                    if (e instanceof ConnectException) 
                                    {
                                        System.out.println("Car RM Crashed");
                                    }
                                    if (e instanceof InvalidTransactionException)
                                    {
                                        System.out.println(e.getMessage());
                                    }
                                }
                            }
                            else {
                                try {
                                    this.rm_room.commit(transactionId);
                                } catch (Exception e) {
                                    if (e instanceof ConnectException) 
                                    {
                                        System.out.println("Room RM Crashed");
                                    }
                                    if (e instanceof InvalidTransactionException)
                                    {
                                        System.out.println(e.getMessage());
                                    }
                                }
                            }
                            if (!this.active_log.get(transactionId).record.contains("SOME_COMMITTED"))
                            {
                                java.lang.Thread.sleep(100);
                                record = "SOME_COMMITTED";
                                this.active_log.get(transactionId).record.add(record);
                                IOTools.saveToDisk(this.active_log.get(transactionId), tm_name + "_" + Integer.toString(transactionId) + ".log");
                                if (crash_mode == 8) return selfDestruct(crash_mode);
                            }

                        }
                        java.lang.Thread.sleep(100);
                        record = "AFTER_COMMIT";
                        this.active_log.get(transactionId).record.add(record);
                        IOTools.saveToDisk(this.active_log.get(transactionId), tm_name + "_" + Integer.toString(transactionId) + ".log");
                        if (crash_mode == 9) return selfDestruct(crash_mode);
                        this.active_txn.remove(transactionId);
                        IOTools.saveToDisk(this, "TransactionManager.txt");
                        IOTools.saveToDisk(this.active_txn, "TMActive.txt");
                        IOTools.deleteFile(tm_name + "_" + Integer.toString(transactionId) + ".log");
                        this.active_log.remove(transactionId);
                    }
                    else
                    {
                        java.lang.Thread.sleep(100);
                        record = "BEFORE_ABORT";
                        this.active_log.get(transactionId).record.add(record);
                        IOTools.saveToDisk(this.active_log.get(transactionId), tm_name + "_" + Integer.toString(transactionId) + ".log");
                        if (crash_mode == 7) return selfDestruct(crash_mode);

                        for (int rm_num : rms)
                        {
                            if (rm_num == MW_NUM) {
                                try {
                                    this.mw.local_abort(transactionId);
                                }
                                catch (Exception e)
                                {
                                    if (e instanceof ConnectException) 
                                    {
                                        System.out.println("Middleware Crashed");
                                    }
                                    if (e instanceof InvalidTransactionException)
                                    {
                                        System.out.println(e.getMessage());
                                    }
                                }
                            }
                            else if (rm_num == FLIGHT_NUM) 
                            {
                                try {
                                    this.rm_flight.abort(transactionId);
                                }
                                catch (Exception e)
                                {
                                    if (e instanceof ConnectException) 
                                    {
                                        System.out.println("Flight RM Crashed");
                                    }
                                    if (e instanceof InvalidTransactionException)
                                    {
                                        System.out.println(e.getMessage());
                                    }
                                }
                            }
                            else if (rm_num == CAR_NUM) 
                            {
                                try {
                                    this.rm_car.abort(transactionId);
                                }
                                catch (Exception e)
                                {
                                    if (e instanceof ConnectException) 
                                    {
                                        System.out.println("Car RM Crashed");
                                    }
                                    if (e instanceof InvalidTransactionException)
                                    {
                                        System.out.println(e.getMessage());
                                    }
                                }
                            }
                            else
                            {
                                try {
                                    this.rm_room.abort(transactionId);
                                }
                                catch (Exception e)
                                {
                                    if (e instanceof ConnectException) 
                                    {
                                        System.out.println("Room RM Crashed");
                                    }
                                    if (e instanceof InvalidTransactionException)
                                    {
                                        System.out.println(e.getMessage());
                                    }
                                }
                            }
                            if (!this.active_log.get(transactionId).record.contains("SOME_ABORTED"))
                            {
                                java.lang.Thread.sleep(100);
                                record = "SOME_ABORTED";
                                this.active_log.get(transactionId).record.add(record);
                                IOTools.saveToDisk(this.active_log.get(transactionId), tm_name + "_" + Integer.toString(transactionId) + ".log");
                                if (crash_mode == 8) return selfDestruct(crash_mode);
                            }
                        }
                        java.lang.Thread.sleep(100);
                        record = "AFTER_ABORT";
                        this.active_log.get(transactionId).record.add(record);
                        IOTools.saveToDisk(this.active_log.get(transactionId), tm_name + "_" + Integer.toString(transactionId) + ".log");
                        if (crash_mode == 9) return selfDestruct(crash_mode);
                        this.active_txn.remove(transactionId);
                        IOTools.saveToDisk(this, "TransactionManager.txt");
                        IOTools.saveToDisk(this.active_txn, "TMActive.txt");
                        IOTools.deleteFile(tm_name + "_" + Integer.toString(transactionId) + ".log");
                        this.active_log.remove(transactionId);
                        return false;
                    }
                }
                catch (Exception e) {
                    // System.out.println("RM crashed : " + e.getMessage()); 
                    return false;
                }
                // this.active_txn.remove(transactionId);
                // IOTools.saveToDisk(this, "TransactionManager.txt");
                // IOTools.saveToDisk(this.active_txn, "TMActive.txt");
                // IOTools.deleteFile(tm_name + "_" + Integer.toString(transactionId) + ".log");
                // this.active_log.remove(transactionId);
            }
        }
        return true;
    }

    public void abort(int transactionId) throws RemoteException, InvalidTransactionException
    {
        synchronized(this.active_txn) {
            if (transactionId < 1 || !this.active_txn.containsKey(transactionId)) {
                Trace.warn("TM::Abort failed--Invalid transactionId");
                throw new InvalidTransactionException(transactionId);
            }
            else
            {
                Trace.info("TM::Aborting transaction : " + transactionId);
                mw_locks.UnlockAll(transactionId);
                IOTools.saveToDisk(this, "TransactionManager.txt");
                IOTools.saveToDisk(this.active_txn, "TMActive.txt");
                try {
                    for (int rm_num : this.active_txn.get(transactionId).rm_list)
                    {
                        if (rm_num == FLIGHT_NUM) 
                        {
                            try {
                                this.rm_flight.abort(transactionId);
                            }
                            catch (Exception e)
                            {
                                if (e instanceof ConnectException) 
                                {
                                    System.out.println("Flight RM Crashed");
                                }
                                if (e instanceof InvalidTransactionException)
                                {
                                    System.out.println(e.getMessage());
                                }
                            }
                        }
                        else if (rm_num == CAR_NUM) 
                        {
                            try {
                                this.rm_car.abort(transactionId);
                            }
                            catch (Exception e)
                            {
                                if (e instanceof ConnectException) 
                                {
                                    System.out.println("Car RM Crashed");
                                }
                                if (e instanceof InvalidTransactionException)
                                {
                                    System.out.println(e.getMessage());
                                }
                            }
                        }
                        else if(rm_num == ROOM_NUM)
                        {
                            try {
                                this.rm_room.abort(transactionId);
                            }
                            catch (Exception e)
                            {
                                if (e instanceof ConnectException) 
                                {
                                    System.out.println("Room RM Crashed");
                                }
                                if (e instanceof InvalidTransactionException)
                                {
                                    System.out.println(e.getMessage());
                                }
                            }
                        }
                    }
                    if (this.active_txn.get(transactionId).rm_list.contains(MW_NUM)) {
                        try {
                            this.mw.local_abort(transactionId);
                        }
                        catch (Exception e)
                        {
                            if (e instanceof ConnectException) 
                            {
                                System.out.println("Middleware Crashed");
                            }
                            if (e instanceof InvalidTransactionException)
                            {
                                System.out.println(e.getMessage());
                            }
                        }
                    }  
                }
                catch (Exception e) {}
                this.active_txn.remove(transactionId);
                // IOTools.saveToDisk(this, "TransactionManager.txt");                
            }
        }
    }

    public Stack getHistory(int transactionId)
    {
        synchronized(this.active_txn) {
            Transaction t = this.active_txn.get(transactionId);
            if (t!= null) return t.txn_hist;
            else return null;
        }
    }

    public boolean shutdown() throws RemoteException
    {
        synchronized(this.active_txn) {
            if (!this.active_txn.isEmpty()) {
                Trace.warn("TM::Shutdown failed--transaction active");
                // System.out.println(this.active_txn.size());
                return false;
            }
        }
        return true;
    }

    public boolean requestLock(int xid, String strData, int lockType)
    {
        synchronized(this.active_txn) {
            Transaction t = this.active_txn.get(xid);
            t.op_count++;
            this.active_txn.put(xid, t);
        }
        //System.out.println(active_txn.get(xid).op_count);
        int rm_num = 0;
        switch (strData.charAt(0)) {
            case 'c':
                if (strData.charAt(1) == 'u') rm_num = MW_NUM;
                else rm_num = CAR_NUM;
                break;
            case 'f':
                rm_num = FLIGHT_NUM;
                break;
            case 'r':
                rm_num = ROOM_NUM;
                break;
            default:
                break;
        }
        try {
            if (mw_locks.Lock(xid, strData, lockType))
            {
                enlist(xid, rm_num);
                return true;
            }
            else return false;
        }
        catch (DeadlockException dle) {
            Trace.warn("TM::Lock failed--Deadlock exist");
            try {
                abort(xid);
            }
            catch (Exception e){
                System.out.println(e.getMessage());
                e.printStackTrace();
            }
            Trace.warn("TM::Transaction ID aborted");
            return false;
        }
    }

    public void enlist(int xid, int rm_num)
    {
        synchronized (this.active_txn) {
            Transaction t = this.active_txn.get(xid);
            try {
                if (!t.rm_list.contains(rm_num))
                {
                    if (rm_num == MW_NUM)
                    {
                        this.mw.start(xid);
                    }
                    else if (rm_num == FLIGHT_NUM)
                    {
                        this.rm_flight.start(xid);
                    }
                    else if (rm_num == CAR_NUM)
                    {
                        this.rm_car.start(xid);
                    }
                    else
                    {
                        this.rm_room.start(xid);
                    }
                    t.rm_list.add(rm_num);
                    this.active_txn.put(xid, t);
                }
            }
            catch (Exception e) {}
        }
    }

    public void setCrashMode(int mode)
    {
        crash_mode = mode;
    }

    private boolean selfDestruct(int mode)
    {
        new Thread() {
            @Override
            public void run() {
                System.out.println("Self Destructing ...");
            //   try {
            //     // sleep(1000);
            //   } catch (InterruptedException e) {
            //     // I don't care
            //   }
                System.out.println("done");
                System.exit(mode);
            }
        
            }.start();
        return false;
    }
}

class TimeThread extends Thread {
    TransactionManager tm;
    int txnid;
    long time_to_live = 120000;

    public TimeThread (TransactionManager tm, int txnid) {
        this.tm = tm;
	    this.txnid = txnid;
    }

    public void run () {
        long start = 0;
        long end = 0;
        long elapsedTimeMills = 0;
        int old_count = 0;
        while(true){
	    if (tm.active_txn.get(txnid) == null) return;
            start = System.currentTimeMillis();
            try {
                synchronized (tm.active_txn) {
                    time_to_live = time_to_live - elapsedTimeMills;                    
                    if (time_to_live < 0){
                            try {
                                tm.abort(txnid); //time out
                                break;
                            }
                            catch (Exception e)
                            {
                                System.out.println(e.getMessage());
                            }
                    }
                    else if (time_to_live > 0 && tm.active_txn.get(txnid).op_count > old_count) {
                        time_to_live = 60000;
                        old_count = tm.active_txn.get(txnid).op_count;
                    }
                }
                end = System.currentTimeMillis();
                elapsedTimeMills = end - start;
                }
            catch (NullPointerException npe) {
                return;
            }
        }
    }
}
