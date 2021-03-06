package apps;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

public class ClientInfo {

    // the map is build from: <input key, <output key, count done reviews in this file>>
    private ConcurrentMap<String, Map <String, Object>>  in2outMap;
    private AtomicInteger outputFilesLeft;
    private AtomicInteger inputFilesRecieved;
    private int reviewsPerWorker;

    public ClientInfo(int reviewsPerWorker, int numFiles) {
        this.outputFilesLeft = new AtomicInteger(numFiles);
        this.inputFilesRecieved = new AtomicInteger(0);
        this.reviewsPerWorker = reviewsPerWorker;
        this.in2outMap = new ConcurrentHashMap<>();
    }

    public String getLocalFileName(String inBucket, String inputKey){
        String outkey = getOutKey(inputKey);
        return inBucket + "_" + outkey;
    }

    public String getOutKey(String inputKey){
        return (String) in2outMap.get(inputKey).get(Constants.OUT_KEY);
    }

    public long getTotalFileReviews(String inputKey){
        return (long) in2outMap.get(inputKey).get(Constants.TOTAL_FILE_REVIEWS);
    }

    public void deleteLocalFile(String inBucket, String inputKey){
        if(getLockForInputKey(inputKey).isHeldByCurrentThread()){
            System.out.println("Entering deleteLocalFile with lock");
            getLockForInputKey(inputKey).unlock();
        }
        getLockForInputKey(inputKey).lock();
        boolean isDeleted = false;
        try {
            String localFileName = getLocalFileName(inBucket, inputKey);
            isDeleted = new File(localFileName).delete();
        }
        finally {
            getLockForInputKey(inputKey).unlock();
        }
    }

    private ReentrantLock getLockForInputKey(String inputKey) {
        return (ReentrantLock) in2outMap.get(inputKey).get(Constants.LOCK);
    }

    private boolean isNewMessage(String localFileName, String msg) {
        System.out.println("in new message");
        if(!new File(localFileName).isFile()){
            System.out.println("File doesn't exist, return true");
            return true;
        }
        return true;
    }

    private void appendToLocalFile(String localFileName, String msg){
        PrintWriter out = null;
        try {
            out = new PrintWriter(new BufferedWriter(new FileWriter(localFileName, true)));
            out.println(msg);
        } catch (IOException e) {
            System.err.println(e);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    public boolean updateLocalOutputFile(String inputBucket, String inputKey, String msg) {
        if(getLockForInputKey(inputKey).isHeldByCurrentThread()){
            System.out.println("Entering updateLocalOutputFile with lock");
            getLockForInputKey(inputKey).unlock();
        }
        boolean isUpdated = false;
        getLockForInputKey(inputKey).lock();
        try{
            String localFileName = getLocalFileName(inputBucket,inputKey);
            if (isNewMessage(localFileName, msg)){
                System.out.println("writing new message");
                appendToLocalFile(localFileName, msg);
                isUpdated = true;
            }

        }
        finally {
            getLockForInputKey(inputKey).unlock();
            return isUpdated;
        }
    }


    public long decOutputCounter(String inputKey) {
        if(getLockForInputKey(inputKey).isHeldByCurrentThread()){
            System.out.println("Entering decOutputCounter with lock");
            getLockForInputKey(inputKey).unlock();
        }
        long newCounter = -1; //default non zero value
        getLockForInputKey(inputKey).lock();

        try {
        newCounter = (Long) in2outMap.get(inputKey).get(Constants.COUNTER) -1;
        in2outMap.get(inputKey).put(Constants.COUNTER, newCounter);
        }
        finally {
            getLockForInputKey(inputKey).unlock();
            return newCounter ;
        }
    }

    public int decOutputFilesLeft() {
        return outputFilesLeft.decrementAndGet();
    }

    public int incInputFilesReceived() {
        return inputFilesRecieved.incrementAndGet();
    }

    public int getReviewsPerWorker() {
        return reviewsPerWorker;
    }

    public void putOutputKey(String inputKey, String outputKey, long counter) {
        Map outputDict = new HashMap<>();
        outputDict.put(Constants.OUT_KEY, outputKey);
        outputDict.put(Constants.COUNTER, counter);
        outputDict.put(Constants.TOTAL_FILE_REVIEWS, counter);
        outputDict.put(Constants.LOCK, new ReentrantLock());
        in2outMap.put(inputKey, outputDict);
    }

    @Override
    public String toString() {

        StringBuilder output = new StringBuilder();
        for (Map.Entry<String, Map <String, Object>> entry : in2outMap.entrySet()) {

            StringBuilder outputIn = new StringBuilder();
            for (Map.Entry<String, Object> entryIn : entry.getValue().entrySet()) {
                String pairIn = "         (" + entryIn.getKey() + ", " + entryIn.getValue() + ")\n";
                outputIn.append(pairIn);
            }

            String pair = "     * " + entry.getKey() + ":\n" + outputIn + "\n";
            output.append(pair);
        }

        return "ClientInfo{" +
                " \n    in2outMap=\n" + output +
                " \n    outputFilesLeft=" + outputFilesLeft.get() +
                " \n    inputFilesRecieved=" + outputFilesLeft.get() +
                ",\n    reviewsPerWorker=" + reviewsPerWorker +
                "}\n";
    }
}
