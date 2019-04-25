package bgu.dsps.local;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.*;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;

//get list of ec2 instances
//check if one of them is bgu.dsps.manager tag
//if so start it
//if not initiate one
//Uploads the file to S3.
//Sends a message to an SQS queue, stating the location of the file on S3
//Checks an SQS queue for a message indicating the process is done and the response (the summary file) is available on S3.
//Downloads the summary file from S3, and create an html file representing the results.
// Sends a termination message to the Manager if it was supplied as one of its input arguments.

public class LocalApplication {

    //////////---------MACROS-------/////////////
    private static String IMAGE_NAME = "ami-0ff8a91507f77f867";
    private static String IAM_NAME = /*"dps_ass1_role_v2"*/ "Manager";
    private static String bucketName = /*"bucket-sf-helloworld"*/ "testbucket15993"; //TO BE IMPLEMENTED

    private static AmazonEC2 ec2;

    private static AmazonS3Client s3;

    private static AmazonSQS sqs;

    static String inputFileName;
    static String outputFileName;
    static int messagePerWorker;
    static boolean shouldTerminate;
    static String localApp2ManagerQueue;
    static String manager2LocalAppQueue;

    public static void main(String[] args){

        inputFileName = args[0];
        outputFileName = args[1];
        messagePerWorker = Integer.parseInt( args[2] );
        shouldTerminate = (args.length > 3) && (args[3].equals("terminate"));

        initAwsServices();

        wakeUpManagerIfNeeded();

        sendRequestToManager();

        receiveResponseFromManager();

        if(shouldTerminate)
            sendTerminateMessage();
    }

    private static void initAwsServices(){

        AWSCredentialsProvider credentialsProvider = new AWSStaticCredentialsProvider(
                new ProfileCredentialsProvider().getCredentials());

        ec2 = AmazonEC2ClientBuilder.standard()
                .withCredentials(credentialsProvider)
                .withRegion("us-east-1")
                .build();

        s3 = (AmazonS3Client) AmazonS3ClientBuilder.standard()
                .withCredentials(credentialsProvider)
                .withRegion("us-east-1")
                .build();

        sqs = AmazonSQSClientBuilder.standard()
                .withCredentials(credentialsProvider)
                .withRegion("us-east-1")
                .build();
    }

    private static void wakeUpManagerIfNeeded(){

        System.out.println("Wake up Manager if needed.\n");

        manager2LocalAppQueue = sqs.createQueue(new CreateQueueRequest(
                "Manager2LocalAppQueue"+ UUID.randomUUID())).getQueueUrl();

        DescribeInstancesResult result = ec2.describeInstances( new DescribeInstancesRequest()
                .withFilters(new Filter("tag:type", new ArrayList<String>(Collections.singletonList("Manager")))));

        boolean found = false;

        for (Reservation reservation : result.getReservations()){

            List<Instance> instances = reservation.getInstances();

            for (Instance instance : instances) {

                String instanceState = instance.getState().getName();

                if (instanceState.equals("terminated") || instanceState.equals("stopped"))
                    continue;

                found = true;

                for (Tag t : instance.getTags())
                    if (t.getKey().equals("inputQueue"))
                        localApp2ManagerQueue = t.getValue();
            }
        }
        if( !found ){

            localApp2ManagerQueue = sqs.createQueue(new CreateQueueRequest(
                    "LocalApp2ManagerQueue"+ UUID.randomUUID())).getQueueUrl();

            createManagerInstance(IMAGE_NAME, IAM_NAME);
        }
    }

    private static void createManagerInstance(String image, String IAMName){

        System.out.println("Create Manager Instance.\n");

        RunInstancesRequest request = new RunInstancesRequest(image, 1, 1)
                .withIamInstanceProfile(new IamInstanceProfileSpecification().withName(IAMName))
                .withInstanceType(InstanceType.T2Micro.toString())
                .withUserData(getManagerScript())
                //.withKeyName("testKey");
                .withKeyName("oshrir");

        Instance instance = ec2.runInstances(request).getReservation().getInstances().get(0);
        CreateTagsRequest createTagsRequest = new CreateTagsRequest()
                .withResources(instance.getInstanceId())
                .withTags(new Tag("type", "Manager"))
                .withTags(new Tag("inputQueue", localApp2ManagerQueue));
        ec2.createTags(createTagsRequest);
    }

    private static String uploadFileToS3(File file, String bucketName){

        System.out.println("Upload file to s3.\n");

        String key = file.getName().replace('\\', '_').replace('/', '_')
                .replace(':', '_');
        s3.putObject(new PutObjectRequest(bucketName, key, file).withCannedAcl(CannedAccessControlList.PublicRead));

        return key;
    }

    private static S3Object downloadFromS3(String key){

        return s3.getObject(new GetObjectRequest(bucketName, key));
    }

    private static Message receiveSingleMessage(String sqsUrl) {

        ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(sqsUrl)
                .withMaxNumberOfMessages(1)
                .withMessageAttributeNames("key")
                .withAttributeNames("");

        List<Message> messages = sqs.receiveMessage(receiveMessageRequest).getMessages();

        if (messages.isEmpty())
            return null;

        return messages.get(0);
    }

    private static void createHtmlFromSummaryFile(S3Object summaryFile){

        System.out.println("Create HTML summary file.\n");

        String result = readS3ObjectToText(summaryFile);

        final File file = new File(outputFileName);

        try {
            FileUtils.writeStringToFile(file, result, "ISO-8859-1");

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String readS3ObjectToText(S3Object obj){

        System.out.println("Convert S3 object to text.\n");

        BufferedReader reader = new BufferedReader(new InputStreamReader(obj.getObjectContent()));
        StringBuilder result = new StringBuilder();
        String header = "<!DOCTYPE html>\n<html>\n\t<head>\n\t\t<title>Conversion Output Summary" +
                "</title>\n\t</head>\n\n\t<body>\n";
        String footer = "\t</body>\n</html>";
        result.append(header);
        while (true) {
            String line;
            try {
                line = reader.readLine();

                if (line == null)
                    break;

                String[] splitLine = line.split("\\s+");
                splitLine[0] = "<strong>" + splitLine[0] + "</strong>";
                splitLine[1] = "<a href=" + splitLine[1] + ">" + splitLine[1] + "</a>";
                splitLine[2] = splitLine[2].startsWith("http") ?
                        "<a href=" + splitLine[2] + ">" + splitLine[2] + "</a>" : splitLine[2];
                String editedLine = "\t\t" + splitLine[0] + " " + splitLine[1] + "\t" + splitLine[2] + "<br>";

                result.append(editedLine).append("\n");

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        result.append(footer);
        return result.toString();
    }

    private static void sendRequestToManager(){

        System.out.println("Send request to Manager.\n");

        final String key = uploadFileToS3( new File( inputFileName ), bucketName);

        final String finalManager2LocalAppQueue = manager2LocalAppQueue;

        Map<String,MessageAttributeValue> attributes = new HashMap<String, MessageAttributeValue>(){{
            put("task_type", new MessageAttributeValue().withDataType("String").withStringValue("new_task"));
            put("key", new MessageAttributeValue().withDataType("String").withStringValue(key));
            put("output_sqs", new MessageAttributeValue().withDataType("String").withStringValue(finalManager2LocalAppQueue));
            put("n", new MessageAttributeValue().withDataType("Number").withStringValue(String.valueOf(messagePerWorker)));
        }};

        sqs.sendMessage(new SendMessageRequest(localApp2ManagerQueue, "new Task")
                .withMessageAttributes(attributes));
    }

    private static void receiveResponseFromManager(){

        System.out.println("Receive response from Manager.\n");

        while( true ) {

            Message message = receiveSingleMessage(manager2LocalAppQueue);

            if (message != null) {

                Map<String, MessageAttributeValue> msgAttributes = message.getMessageAttributes();
                String key = msgAttributes.get("key").getStringValue();

                S3Object summaryFile = downloadFromS3(key);

                createHtmlFromSummaryFile(summaryFile);

                sqs.deleteMessage(manager2LocalAppQueue, message.getReceiptHandle());

                sqs.deleteQueue(manager2LocalAppQueue);

                break;
            }
        }
    }

    private static String getManagerScript() {

        String keyName = "manager.jar";

        String scriptSplit =
                "#! /bin/bash \n" +
                        "wget https://s3.amazonaws.com/" + bucketName + "/" + keyName + " -O ./" + keyName + " \n" +
                        "java -jar " + keyName + " " + localApp2ManagerQueue + " " + bucketName;
        return new String(Base64.encodeBase64(scriptSplit.getBytes()));
    }

    private static void sendTerminateMessage(){

        System.out.println("Sends a terminate message to Manager.\n");

        Map<String,MessageAttributeValue> attributes = new HashMap<String, MessageAttributeValue>(){{
            put("task_type", new MessageAttributeValue().withDataType("String").withStringValue("terminate"));
        }};

        sqs.sendMessage(new SendMessageRequest(localApp2ManagerQueue, "terminate")
                .withMessageAttributes(attributes));
    }
}
