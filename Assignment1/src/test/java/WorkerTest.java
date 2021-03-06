import apps.Constants;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.sqs.model.Message;
import handlers.EC2Handler;
import handlers.SQSHandler;
import messages.Manager2Worker;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.*;
import java.util.List;

public class WorkerTest {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("To activate this test, put file path as first argument.");
            System.out.println("for example: /home/yaniv/workSpace/dsps/reviews/single_json");
            return;
        }
        String fileName = args[0];
        EC2Handler ec2 = new EC2Handler(true);
        System.out.println("connected to EC2");

        SQSHandler sqs = new SQSHandler(true);
        System.out.println("connected to sqs");

        String W2M_QueueURL = null;
        String M2W_QueueURL = null;

        try {
            Runnable worker = new RunnableWorker();
            Thread thread = new Thread(worker);
            System.out.println("Created Thread");

            M2W_QueueURL = sqs.createSQSQueue(Constants.MANAGER_TO_WORKERS_QUEUE, true);
            W2M_QueueURL = sqs.createSQSQueue(Constants.WORKERS_TO_MANAGER_QUEUE, false);

            System.out.println("Created Queues");

            thread.start();


            //test
            JSONParser parser = new JSONParser();
            BufferedReader reader;
            reader = new BufferedReader(new FileReader(fileName));
            String line = reader.readLine();
            while (line != null) {
                Object obj = parser.parse(line);
                JSONObject jsonObject = (JSONObject) obj;
                JSONArray reviewsList = (JSONArray) jsonObject.get(Constants.REVIEWS);
                for (Object review : reviewsList) {
                    JSONObject jsonReview = (JSONObject) review;
                    String text = (String) jsonReview.get(Constants.TEXT);
                    int rating = ((Long) jsonReview.get(Constants.RATING)).intValue();
                    sqs.sendMessage(M2W_QueueURL, new Manager2Worker(
                            "bucket_test",
                            "key_test",
                            text,
                            rating)
                            .stringifyUsingJSON());
                }

                // read next line
                line = reader.readLine();
            }
            reader.close();

            System.out.println("sent all messages");
            while(true){
                List<Message> workerMessages = sqs.receiveMessages(W2M_QueueURL, false, false);
                System.out.println("test recieved " + workerMessages.size() + " Messages");
                for (Message workerMsg : workerMessages) {
                    System.out.println(workerMsg.getBody());
                }

                //delete recieved messages
                if(!workerMessages.isEmpty())
                    sqs.deleteMessages(workerMessages, W2M_QueueURL);

            }

//            thread.interrupt();

//        } catch (InterruptedException consumed) {
//            System.out.println("thread exited");
        } catch (AmazonServiceException ase) {
            System.out.println("Caught an AmazonServiceException, which means your request made it " +
                    "to Amazon SQS, but was rejected with an error response for some reason.");
            System.out.println("Error Message:    " + ase.getMessage());
            System.out.println("HTTP Status Code: " + ase.getStatusCode());
            System.out.println("AWS Error Code:   " + ase.getErrorCode());
            System.out.println("Error Type:       " + ase.getErrorType());
            System.out.println("Request ID:       " + ase.getRequestId());
        } catch (AmazonClientException ace) {
            System.out.println("Caught an AmazonClientException, which means the client encountered " +
                    "a serious internal problem while trying to communicate with SQS, such as not " +
                    "being able to access the network.");
            System.out.println("Error Message: " + ace.getMessage());
        } catch (Exception ex) {
            System.out.println("Exception handled " + ex);
        } finally {
            if (sqs != null && M2W_QueueURL != null) {
                System.out.println("Deleting the test queue with url: " + M2W_QueueURL + ".\n");
                sqs.deleteQueue(M2W_QueueURL);
            }
            if (sqs != null && W2M_QueueURL != null) {
                System.out.println("Deleting the test queue with url: " + W2M_QueueURL + ".\n");
                sqs.deleteQueue(W2M_QueueURL);
            }
        }
    }
}




