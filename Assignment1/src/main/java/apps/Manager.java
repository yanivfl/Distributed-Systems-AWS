package apps;

import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Tag;
import handlers.EC2Handler;
import handlers.S3Handler;
import handlers.SQSHandler;

import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Manager {

    private static EC2Handler ec2;
    private static S3Handler s3;
    private static SQSHandler sqs;

    private static final int MAX_THREADS_PER_GROUP = 10;
    private static final int INITIAL_THREADS = 3;

    private static ConcurrentMap<String, ClientInfo> clientsInfo;
    private static AtomicInteger filesCount;
    private static AtomicInteger regulerWorkersCount;
    private static AtomicBoolean terminate;
    private static AtomicInteger extraWorkersCount;
    private static PriorityQueue<Integer> maxWorkersPerFile;

    private static LinkedList<Thread> clientsThreads;
    private static LinkedList<Thread> workersThreads;
    private static int clientsThreadCount;
    private static int workersThreadCount;

    private static final Object waitingObject = new Object();


    public static void initialConfigurations(boolean isClient) {

        // initial configurations
        ec2 = new EC2Handler(isClient);
        s3 = new S3Handler(isClient);
        sqs = new SQSHandler(isClient);

        filesCount = new AtomicInteger(0);
        regulerWorkersCount = new AtomicInteger(0);
        terminate = new AtomicBoolean(false);
        extraWorkersCount = new AtomicInteger(0);
        maxWorkersPerFile = new PriorityQueue<>(Collections.reverseOrder());

        clientsThreads = new LinkedList<>();
        workersThreads = new LinkedList<>();
        clientsThreadCount = 0;
        workersThreadCount = 0;

        clientsInfo = new ConcurrentHashMap<>();
    }

    public static void main(String[] args) throws InterruptedException {
        initialConfigurations(Constants.DEBUG_MODE);

        //launch first worker!
        ec2.launchWorkers_EC2Instances(1,ec2.getRoleARN(Constants.WORKERS_ROLE), Constants.USER_DATA_PATH);
        regulerWorkersCount.incrementAndGet();

        Thread workersThread;
        Thread clientsThread;

        Thread.sleep(500);

        // start the first threads (INITIAL_THREADS for workers and INITIAL_THREADS for clients)
        for (int i = 0; i < INITIAL_THREADS ; i++) {
            workersThread = new Thread(new ManageWorkers(clientsInfo, filesCount, regulerWorkersCount, extraWorkersCount,
                    maxWorkersPerFile,terminate, waitingObject, ec2, s3, sqs));
            clientsThread = new Thread(new ManageClients(clientsInfo, filesCount, regulerWorkersCount, extraWorkersCount,
                    maxWorkersPerFile, terminate, waitingObject, ec2, s3, sqs));

            workersThreads.add(workersThread);
            clientsThreads.add(clientsThread);

            workersThreadCount++;
            clientsThreadCount++;
            workersThread.setName("Manage-Workers-Thread");
            workersThread.start();

            clientsThread.setName("Manage-clients-Thread");
            clientsThread.start();
        }

        while (!terminate.get()) {

            if(Constants.DEBUG_MODE){
                Constants.printDEBUG("DEBUG MANAGER: number of manage-clients threads: " + clientsThreadCount);
                Constants.printDEBUG("DEBUG MANAGER: number of manage-workers threads: " + workersThreadCount);
                Constants.printDEBUG("DEBUG MANAGER: number of filesCount threads: " + filesCount.get());
            }

            synchronized (waitingObject) {

                // wakes up when:
                // 1. new worker - in ManageClients
                // 2. new client - in ManageClients
                // 3. client is done - in ManageWorkers
                // 4. worker is done  - in ManageWorkers
                waitingObject.wait();
                if(terminate.get()){
                    break;
                }

                // filesCount can increase only on waitingObject synchronization
                while (clientsThreadCount < (filesCount.get() + INITIAL_THREADS)  && clientsThreadCount < MAX_THREADS_PER_GROUP) {
                    clientsThread = new Thread(new ManageClients(clientsInfo, filesCount, regulerWorkersCount,
                            extraWorkersCount, maxWorkersPerFile, terminate, waitingObject, ec2, s3, sqs));
                    clientsThreads.add(clientsThread);
                    clientsThreadCount++;
                    clientsThread.setName("Manage-clients-Thread");
                    Constants.printDEBUG("DEBUG MANAGER: Creating Manage-clients thread. count is: " +clientsThreadCount);
                    clientsThread.start();
                }

                // workersCount can increase only on waitingObject synchronization
                while (workersThreadCount < (regulerWorkersCount.get() + INITIAL_THREADS)  && workersThreadCount < MAX_THREADS_PER_GROUP) {
                    workersThread = new Thread(new ManageWorkers(clientsInfo, filesCount, regulerWorkersCount,
                            extraWorkersCount, maxWorkersPerFile, terminate, waitingObject, ec2, s3, sqs));
                    workersThreads.add(workersThread);
                    workersThreadCount++;
                    workersThread.setName("Manage-Workers-Thread");
                    Constants.printDEBUG("DEBUG MANAGER: Creating Manage-workers thread. count is: " +workersThreadCount);
                    workersThread.start();
                }

                // clientsCount can decrease only on waitingObject synchronization
                while (clientsThreadCount > (filesCount.get() + INITIAL_THREADS)  && clientsThreadCount > 1) {
                    Thread toInterrupt = clientsThreads.poll();
                    clientsThreadCount--;
                    Constants.printDEBUG("DEBUG MANAGER: interrupting Manage-clients thread");
                    toInterrupt.interrupt();
                }

                // workersCount can decrease only on waitingObject synchronization
                while (workersThreadCount > (regulerWorkersCount.get() + INITIAL_THREADS) && workersThreadCount > 1) {
                    Thread toInterrupt = workersThreads.poll();
                    workersThreadCount--;
                    Constants.printDEBUG("DEBUG MANAGER: interrupting Manage-workers thread");
                    toInterrupt.interrupt();
                }
            }
        }

        // wait for all clients to be serves (and the workers to finish their jobs)
        Constants.printDEBUG("DEBUG MANAGER: Manager self-destruct in 5");
        for (Thread thread: clientsThreads) {
            thread.join();
        }
        Constants.printDEBUG("DEBUG MANAGER: Manager self-destruct in 4");
        for (Thread thread: workersThreads) {
            thread.join();
        }

        // all workers are finished, terminate them
        List<Instance> instances = ec2.listInstances(false);
        Instance managerInstance = null;

        Constants.printDEBUG("Creating statistics");
        createStatistics();

        for (Instance instance: instances) {

            for (Tag tag: instance.getTags()) {
                if (tag.getValue().equals(Constants.INSTANCE_TAG.WORKER.toString())) {
                    ec2.terminateEC2Instance(instance.getInstanceId());
                    Constants.printDEBUG("DEBUG MANAGER: WORKER terminated");
                }
                else {
                    if (tag.getValue().equals(Constants.INSTANCE_TAG.MANAGER.toString())) {
                        managerInstance = instance;
                    }
                }
            }
        }

        // get queues (URLs)
        try {
            String M2C_QueueURL = sqs.getURL(Constants.MANAGER_TO_CLIENTS_QUEUE);
            String C2M_QueueURL = sqs.getURL(Constants.CLIENTS_TO_MANAGER_QUEUE);
            String W2M_QueueURL = sqs.getURL(Constants.WORKERS_TO_MANAGER_QUEUE);
            String M2W_QueueURL = sqs.getURL(Constants.MANAGER_TO_WORKERS_QUEUE);

            Constants.printDEBUG("let clients collect data, waiting 2 minutes");
            Thread.sleep(120000); //2 minutes more than enough time for all clients to collect data

            Constants.printDEBUG("deleting all queues");
            // delete all queues
            sqs.deleteQueue(M2C_QueueURL);
            sqs.deleteQueue(C2M_QueueURL);
            sqs.deleteQueue(W2M_QueueURL);
            sqs.deleteQueue(M2W_QueueURL);

            Constants.printDEBUG("DEBUG MANAGER: Kaboom");

        }
        catch (Exception e){
            e.printStackTrace();
        }
        finally {
            // terminate - after this the program must end!
            if (!Constants.DEBUG_MODE) {
                ec2.terminateEC2Instance(managerInstance.getInstanceId());
            }

            Constants.printDEBUG("DEBUG MANAGER: Manager Terminated. (doesn't have to reach this line");
        }

    }

    private static void createStatistics() {
        String statistics = ec2.getStat();

        String fileName = "StatisticsFile";
        File statFile = new File(fileName);
        PrintWriter out = null;
        try {
            out = new PrintWriter(new BufferedWriter(new FileWriter(statFile, true)));
            out.println(statistics);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally
        {
            if (out != null) {
                out.close();
                String bucketName = "statisticsBucket";
                String bucketKey = s3.createBucket(bucketName);
                s3.uploadFileToS3(bucketKey, fileName);
            }
        }
    }
}
