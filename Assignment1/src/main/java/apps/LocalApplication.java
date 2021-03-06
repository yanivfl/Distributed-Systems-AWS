package apps;

import messages.Client2Manager;
import messages.Client2Manager_terminate;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sqs.model.Message;
import handlers.EC2Handler;
import handlers.S3Handler;
import handlers.SQSHandler;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;


/** From the assignment description:
 * 1. Checks if a apps.Manager node is active on the EC2 cloud. If it is not, the application will start the manager node.
 * 2. Uploads the file to S3.
 * 3. Sends a message to an SQS queue, stating the location of the file on S3
 * 4. Checks an SQS queue for a message indicating the process is done and the response (the summary file) is available on S3.
 * 5. Downloads the summary file from S3, and create an html file representing the results.
 * 6. Sends a termination message to the apps.Manager if it was supplied as one of its input arguments.
 */

public class LocalApplication {

    /**
     * starts the manager instance and creates the queues
     * params: ec2, s3, sqs
     */
    public static void startManager(EC2Handler ec2, S3Handler s3, SQSHandler sqs) throws IOException {

        // start the queues
        sqs.createSQSQueue(Constants.CLIENTS_TO_MANAGER_QUEUE, false);
        sqs.createSQSQueue(Constants.MANAGER_TO_CLIENTS_QUEUE, false);
        sqs.createSQSQueue(Constants.WORKERS_TO_MANAGER_QUEUE, false);
        sqs.createSQSQueue(Constants.MANAGER_TO_WORKERS_QUEUE, false);

        // start the manager
        String managerArn = ec2.getRoleARN(Constants.MANAGER_ROLE);
        ec2.launchManager_EC2Instance(managerArn, Constants.USER_DATA_PATH);
    }

    /**
     * Create an html file representing the results from the summery.
     * params: appID, numOutput, summery
     */
    public static void createHtml(UUID appID, String htmlName, InputStream summery) throws IOException, ParseException {

        // create the string
        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html>\n<html>\n<head>\n<title>Page Title</title>\n</head>\n<body>\n<h1>Amazon Reviews - Sarcasm Detector</h1><ul>");

        // go through the summery output file line by line
        BufferedReader reader = new BufferedReader(new InputStreamReader(summery));
        JSONParser parser = new JSONParser();
        while(reader.ready()) {
            String line = reader.readLine();
            if(line == null) break;

            // parse line using JSON
            JSONObject obj = (JSONObject) parser.parse(line);

            if (Constants.TAGS.valueOf((String) obj.get("tag")) != Constants.TAGS.WORKER_2_MANAGER)
                throw new RuntimeException("LOCAL_APP: Got an unexpected message - couldn't create an HTML file");

            String review = (String) obj.get(Constants.REVIEW);
            long sentiment = (Long) obj.get(Constants.SENTIMENT);
            String entityList = (String) obj.get(Constants.ENTITIES);
            String isSarcastic;
            if ((Boolean) obj.get(Constants.IS_SARCASTIC))
                isSarcastic = "";
            else
                isSarcastic = "not";

            String li =
                    "<li>\n" +
                            "    <span style=\"color: "+ Constants.HTML_COLORS[(int)sentiment] +"\"> "+ review +"</span>\n" +
                            "    "+ entityList +"\n" +
                            "    - This is "+ isSarcastic +" a sarcastic review.\n" +
                    "</li><br>";
            html.append(li);
        }

        html.append("</ul>\n</body>\n</html>");

        // create the HTML file
        htmlName = htmlName.endsWith(".html")? htmlName : htmlName + ".html";

        new File(htmlName);
        Files.write(Paths.get(htmlName), html.toString().getBytes());
    }

    public static void main(String[] args) {

        // initial configurations
        EC2Handler ec2 = new EC2Handler(true);
        S3Handler s3 = new S3Handler(true);
        SQSHandler sqs = new SQSHandler(true);

        try{
            // extract input file name, output file names and optional termination message from args
            // example args: inputFileName1… inputFileNameN outputFileName1… outputFileNameN n terminate(optional)
            boolean terminate = (args.length % 2 == 0);
            int num_files = (args.length-1)/2;
            int reviewsPerWorker;       // (n)

            if (terminate)
                reviewsPerWorker = Integer.parseInt(args[args.length-2]);
            else
                reviewsPerWorker =Integer.parseInt(args[args.length-1]);

            // Check if a Manager node is active on the EC2 cloud. If it is not, the application will start the manager node and create the queues
            if (!ec2.isTagExists(Constants.INSTANCE_TAG.MANAGER)) {
                Constants.printDEBUG("DEBUG APP: Starting Manager!");
                startManager(ec2, s3, sqs);
            }

            // Create a bucket for this local application - the bucket name is unique for this local app
            UUID appID = UUID.randomUUID();
            String myBucket = s3.createBucket(appID.toString());

            // Get the (Clients -> Manager), (Manager -> Clients) SQS queues URLs
            String C2M_QueueURL = sqs.getURL(Constants.CLIENTS_TO_MANAGER_QUEUE);
            String M2C_QueueURL = sqs.getURL(Constants.MANAGER_TO_CLIENTS_QUEUE);

            // Upload all the input files to S3
            String[] keyNamesIn = new String[num_files];
            String[] keyNamesOut = new String[num_files];
            String[] htmlNames = new String[num_files];

            for (int i=0; i<num_files; i++) {
                String fileName = args[i];

                // upload the input file
                keyNamesIn[i] = s3.uploadFileToS3(myBucket, fileName);

                htmlNames[i] = args[i+num_files];

                // this will be the keyName of the output file
                keyNamesOut[i] = s3.getAwsFileName(fileName) + "out";
            }

            // Send a message to the (Clients -> apps.Manager) SQS queue, stating the location of the files on S3
            for (int i=0; i<num_files; i++) {
                Client2Manager messageClientToManager = new Client2Manager(myBucket, keyNamesIn[i], keyNamesOut[i], reviewsPerWorker, num_files);
                sqs.sendMessage(C2M_QueueURL, messageClientToManager.stringifyUsingJSON());
            }

            // Check on the (Manager -> Clients) SQS queue for a message indicating the process is done and the response
            // (the summary file) is available on S3.
            boolean done = false;
            List<Message> doneLst = new LinkedList<>();
            while (!done) {
                List<Message> doneMessages = sqs.receiveMessages(M2C_QueueURL, false, false);
                for (Message msg: doneMessages) {
                    JSONObject msgObj= Constants.validateMessageAndReturnObj(msg , Constants.TAGS.MANAGER_2_CLIENT, true);
                    if (msgObj != null) {
                        boolean isDoneJson = (boolean) msgObj.get(Constants.IS_DONE);
                        String inBucketJson = (String) msgObj.get(Constants.IN_BUCKET);
                        if (isDoneJson && inBucketJson.equals(myBucket)) {
                            doneLst.add(msg);
                            done = true;
                        }
                    }
                }
                //delete received messages (after handling them)
                if(!doneLst.isEmpty())
                    sqs.deleteMessages(doneLst, M2C_QueueURL);
            }

            // Download the summary file from S3
            for (int i=0; i<num_files; i++) {
                String keyNameOut = keyNamesOut[i];
                S3Object object = s3.getS3().getObject(new GetObjectRequest(myBucket, keyNameOut));
                createHtml(appID, htmlNames[i], object.getObjectContent());
            }

            // Send a termination message to the Manager if it was supplied as one of its input arguments.
            if (terminate) {
                Client2Manager_terminate terminateMsg = new Client2Manager_terminate(myBucket);
                sqs.sendMessage(C2M_QueueURL, terminateMsg.stringifyUsingJSON());
            }

            // delete all input files, output files and the bucket from S3 for this local application
            for (int i=0; i<num_files; i++) {
                s3.deleteFile(myBucket, keyNamesIn[i]);
                s3.deleteFile(myBucket, keyNamesOut[i]);
            }
            s3.deleteBucket(myBucket);

            Constants.printDEBUG("finished getting all Output Files :)");
        }

        catch (Exception e){
            System.out.println("Server is Down. deleting User buckets.");
            e.printStackTrace();
            if (s3.listBucketsAndObjects().isEmpty()) {
                System.out.println("User has No buckets");
            }
        }
    }
}
