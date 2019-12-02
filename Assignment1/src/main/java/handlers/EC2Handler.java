package handlers;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import apps.Constants;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.s3.model.ObjectTagging;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.PutObjectResult;
import com.amazonaws.services.ec2.model.Tag;



public class EC2Handler {

    private AWSCredentialsProvider credentials;
    private AmazonEC2 ec2;

    /**
     * Create our credentials file at ~/.aws/credentials
     * Initialize a connection with our EC2
     */
    public EC2Handler() {
        this.credentials = new AWSStaticCredentialsProvider(new ProfileCredentialsProvider().getCredentials());
        this.ec2 = AmazonEC2ClientBuilder.standard()
                .withCredentials(credentials)
                .withRegion(Regions.US_EAST_1)
                .build();
    }

    public AWSCredentialsProvider getCredentials() {
        return credentials;
    }

    public AmazonEC2 getEc2() {
        return ec2;
    }

    /**
     * launch machine instances as requested in machineCount
     * @param tagName: instance of EC2
     * @param machineCount: number of machine instances to launch
     * @return List<Instance>: list of machines instances we launched
     */
    public List<Instance> launchEC2Instances(int machineCount, String tagName) {
        try {
            // launch instances
            RunInstancesRequest request = new RunInstancesRequest(Constants.AMI, machineCount, machineCount);
            request.setInstanceType(InstanceType.T2Micro.toString());
            List<Instance> instances = this.ec2.runInstances(request).getReservation().getInstances();

            // tag instances with the given tag


//            Tag tag = Tag.builder()
//                    .key("Name")
//                    .value(name)
//                    .build();

            for (Instance inst: instances) {

            }




            System.out.println("Launch instances: " + instances);
            System.out.println("You launched: " + instances.size() + " instances");
            return instances;

        } catch (AmazonServiceException ase) {
            printASEException(ase);
            return null;
        }
    }

    /**
     * terminate requested machine instance
     * @param ec2Client: instace of EC2
     * @return boolean: true  iff instance was terminated
     */
    public boolean terminateEC2Instance(String instanecID) {
        try {
            TerminateInstancesRequest terminateInstancesRequest = new TerminateInstancesRequest()
                    .withInstanceIds(instanecID);
            this.ec2.terminateInstances(terminateInstancesRequest)
                    .getTerminatingInstances()
                    .get(0)
                    .getPreviousState()
                    .getName();
            System.out.println("The Instance is terminated with id: "+ instanecID);
            return true;

        } catch (AmazonServiceException ase) {
            printASEException(ase);
            return false;
        }

    }

    /**
     * Go through the list of instances in search of a given tag
     * params: ec2, tag
     * returns: True: There is an instance with the requested tag , False: otherwise
     */
    public boolean isTagExists(String tag) {
        boolean done = false;   // done = True - when finished going over all the instances.
        DescribeInstancesRequest instRequest = new DescribeInstancesRequest();

        try {
            while (!done) {
                DescribeInstancesResult response = this.ec2.describeInstances(instRequest);

                for (Reservation reservation : response.getReservations()) {
                    for (Instance instance : reservation.getInstances()) {

                        Filter filter = new Filter().withName("resource-id").withValues(instance.getInstanceId());
                        DescribeTagsRequest tagRequest = new DescribeTagsRequest().withFilters(filter);
                        DescribeTagsResult tagResult = this.ec2.describeTags(tagRequest);
                        List<TagDescription> tags = tagResult.getTags();

                        for (TagDescription tagDesc: tags) {
                            if (tagDesc.getValue().equals(tag))
                                return true;
                        }

                        System.out.printf(
                                "Found instance with id %s, " +
                                        "AMI %s, " +
                                        "type %s, " +
                                        "state %s " +
                                        "and monitoring state %s",
                                instance.getInstanceId(),
                                instance.getImageId(),
                                instance.getInstanceType(),
                                instance.getState().getName(),
                                instance.getMonitoring().getState());
                    }
                }

                instRequest.setNextToken(response.getNextToken());
                if (response.getNextToken() == null) {
                    done = true;
                }
            }
        }

        catch (AmazonServiceException ase) {
            printASEException(ase);
        }

        return false;
    }

    /**
     * prints AmazonServiceException description
     * @param ase - AmazonServiceException
     */
    private void printASEException(AmazonServiceException ase) {
        System.out.println("Caught Exception: " + ase.getMessage());
        System.out.println("Response Status Code: " + ase.getStatusCode());
        System.out.println("Error Code: " + ase.getErrorCode());
        System.out.println("Request ID: " + ase.getRequestId());

    }
}
