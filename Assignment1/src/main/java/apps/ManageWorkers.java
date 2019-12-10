package apps;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.sqs.model.Message;
import handlers.EC2Handler;
import handlers.S3Handler;
import handlers.SQSHandler;
import messages.Manager2Client;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
    After the manger receives response messages from the workers on all the files on an input file, then it:
        Creates a summary output file accordingly,
        Uploads the output file to S3,
        Sends a message to the application with the location of the file.
*/

public class ManageWorkers implements Runnable {
    private ConcurrentMap<String, ClientInfo> clientsInfo;
    private AtomicInteger filesCount;
    private AtomicInteger regulerWorkersCount;
    private AtomicInteger extraWorkersCount;
    private PriorityQueue<Integer> maxWorkersPerFile;
    private Object waitingObject;
    private EC2Handler ec2;
    private S3Handler s3;
    private SQSHandler sqs;
    private ReentrantLock workersSqsLock;

    public ManageWorkers(ConcurrentMap<String, ClientInfo> clientsInfo, AtomicInteger filesCount, AtomicInteger regulerWorkersCount, AtomicInteger extraWorkersCount, PriorityQueue<Integer> maxWorkersPerFile, Object waitingObject,
                         EC2Handler ec2, S3Handler s3, SQSHandler sqs) {
        this.clientsInfo = clientsInfo;
        this.filesCount = filesCount;
        this.regulerWorkersCount = regulerWorkersCount;
        this.extraWorkersCount = extraWorkersCount;
        this.maxWorkersPerFile = maxWorkersPerFile;
        this.waitingObject = waitingObject;
        this.ec2 = ec2;
        this.s3 = s3;
        this.sqs = sqs;
        this.workersSqsLock = new ReentrantLock();
    }

    @Override
    public void run() {
        System.out.println("Manage-workers: started running");
        JSONParser jsonParser = new JSONParser();

        // Get the (Worker -> Manager) ( Manager -> Clients) SQS queues URLs
        String W2M_QueueURL = sqs.getURL(Constants.WORKERS_TO_MANAGER_QUEUE);
        String M2C_QueueURL = sqs.getURL(Constants.MANAGER_TO_CLIENTS_QUEUE);

        while (true){
            List<Message> workerMessages = new LinkedList<>();
            try{
                workerMessages = sqs.receiveMessages(W2M_QueueURL,false, true);
            }catch (Exception e) {
                if (Thread.interrupted()) {
                    System.out.println("Thread interrupted, killing it softly");
                    break;
                } else {
                    e.printStackTrace();
                }
            }

            System.out.println("Manager received " + workerMessages.size() + " Messages from W2M Queue");

            for (Message workerMsg : workerMessages) {

                // parse json
                JSONObject msgObj= Constants.validateMessageAndReturnObj(workerMsg , Constants.TAGS.WORKER_2_MANAGER, true);
                if (msgObj == null){
                    System.out.println("DEBUG Manage WORKERs: couldn't parse this message!!!");
                    continue;
                }

                String inBucket = (String) msgObj.get(Constants.IN_BUCKET);
                String inKey = (String) msgObj.get(Constants.IN_KEY);

                ClientInfo clientInfo = clientsInfo.get(inBucket);
                if(clientInfo == null){
                    if (Constants.DEBUG_MODE){
                        System.out.println("DEBUG Manage WORKERs: clientInfo is null!!!");
                    }
                    continue;
                }

                boolean isUpdated = clientInfo.updateLocalOutputFile(inBucket,inKey, msgObj.toJSONString());
                if (isUpdated) {
                    if (Constants.DEBUG_MODE){
                        System.out.println("clientInfo: "+ clientInfo.toString());
                        System.out.println("files: " + filesCount.get());
                        System.out.println("reguler workers: " +regulerWorkersCount.get());
                        System.out.println("extra workers: " +extraWorkersCount.get());
                        System.out.println("pq: " + maxWorkersPerFile.toString());
                    }


                    // check if there are more reviews for this file
                   long reviewsLeft = clientInfo.decOutputCounter(inKey);
                   if (reviewsLeft == 0){
                       String outKey = clientInfo.getOutKey(inKey);
                       System.out.println("DEBUG Manage Workers: {inBucket: " +inBucket + ", inKey: " + inKey + ", outKey: " + outKey + "}");
                       s3.uploadLocalToS3(inBucket, clientInfo.getLocalFileName(inBucket,inKey), outKey);
                       clientInfo.deleteLocalFile(inBucket, inKey);
                       filesCount.decrementAndGet();
                       removeWorkersIfNeeded(clientInfo, inKey);

                      // check if there are no more files for this client
                      int outputFilesLeft = clientInfo.decOutputFilesLeft();
                      if (outputFilesLeft == 0){
                          System.out.println("sending done mail to client");
                          sqs.sendMessage(M2C_QueueURL,
                                  new Manager2Client(true, inBucket )
                                          .stringifyUsingJSON());

                          clientsInfo.remove(inBucket);
                      }
                   }
                }
            }

            // delete received messages (after handling them)
            if (!workerMessages.isEmpty()){
                try {
                    sqs.deleteMessages(workerMessages, W2M_QueueURL);
                }
                catch (Exception e) {
                    if (Thread.interrupted()) {
                        sqs.deleteMessages(workerMessages, W2M_QueueURL);
                        break;
                    }
                    else{
                        e.printStackTrace();
                    }
                }
            }

        }

    }

    private void removeWorkersIfNeeded(ClientInfo clientInfo, String inKey) {
        // tell the manager there is one less client to serve
        synchronized (waitingObject) {

            int extraWorkersToTerminate= 0;
            // add scalability
            int totalExtraWorkers = filesCount.get() / Constants.ADD_EXTRA_WORKER; //extra worker for every 3 files
            while(extraWorkersCount.get() > totalExtraWorkers ){
                extraWorkersCount.decrementAndGet();
                extraWorkersToTerminate++;
            }

            // terminate unneeded workers
            long workersPerClient = clientInfo.getTotalFileReviews(inKey) / clientInfo.getReviewsPerWorker();
            maxWorkersPerFile.remove((int)workersPerClient);
            Integer currMax = maxWorkersPerFile.peek();
            if (currMax==null){
                waitingObject.notifyAll();
                return;
            }

            int regulerWorkersToTerminate = Math.max(regulerWorkersCount.get() - currMax,0);
            int numberOfWorkersToTerminate = regulerWorkersToTerminate + extraWorkersToTerminate;

            if (numberOfWorkersToTerminate > 0) {
                List<Instance> instances = ec2.listInstances(false);
                for (Instance instance: instances) {
                    for (Tag tag: instance.getTags()) {
                        if (tag.getValue().equals(Constants.INSTANCE_TAG.WORKER.toString())
                                && instance.getState().getName().equals("running")) {
                            ec2.terminateEC2Instance(instance.getInstanceId(), Constants.DEBUG_MODE);
                            numberOfWorkersToTerminate --;
                            break;
                        }
                    }
                    if (numberOfWorkersToTerminate == 0){
                        break;
                    }
                }
            }
            regulerWorkersCount.set(regulerWorkersCount.get() - regulerWorkersToTerminate);
            waitingObject.notifyAll();
        }
    }

}
