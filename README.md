Distributed PDF Converter
=========================
Stav Faran - 308096270, Oshri Rozenberg - 204354344

Short Description:
-----------------
The application takes a text file, where each line contains an action to perform on a PDF file, and the URL of this file. Afterwards, the actions are being performed by AWS EC2 instances, and once all actions are done a summary webpage (HTML) is being produced for the client.

Workflow Description:
--------------------
Once the program stars running on the client, it checks if there is a running manager node on AWS EC2 service of the given account (If there isn't, it starts a new one) and sends it a SQS message through a single queue, that is used by all clients to send requests to the manager. This message contains the S3 URL of the input file and the SQS URL of the response queue.
The manager starts 2 new threads - one checks for responses in an endless loop (until stopped), and one does status checks for the workers every minute. Afterwards, it uses a thread pool to operate on requests for clients and process each task (send messages to the workers). Once received a terminate message, the manager stops listening for new tasks, and waits until all the existing task accomplished. Once that happend, it terminates the workers, deletes all the queues and shutdown itself.
Every Worker node works in an endless loop until stopped, and takes messages from a single queue from the manager. Once a message has received, the worker operates on it according to the desired action, and sends an appropriate message to the manager once finished.

Running instructions:
--------------------
0. if needed, build the project using `mvn package` (manager.jar and worker.jar already uploaded to S3)
1. Store your AWS credentials in `~/.aws/credentials`
```
   [default]
   aws_access_key_id= ???
   aws_secret_access_key= ???
```
2. run using `java -jar PdfConverter.jar inputFileName.txt outputFileName.html n (terminate)` (terminate is optional)

Security:
---------
The manager and all of the workers get temporary credentials from their IAM role. Therefore, we don't transfer them credentials at all, particulary not as plain text.

Scalability:
------------
We ran a few applications at a time to test the program, they all worked properly, finished properly and the results were correct.
While checking our implementation, we noticed that there is a built-in limitation in AWS EC2 regarding the maximum amount of instances we are able to run - maximum of 20 instances at the same time. According to that, we added this limitation to our implementation, so the application won't crush in case that the manager tries to create instances to a total amount of 20 or more. With the given limitations the program will be able to perform on large amount of clients thanks to a thread pool mechanism we used in the manager.

Persistence:
------------
If one of the workers is impaired we implemented a fail mechanism that uses the SQS time-out functionallity to resend the message into the input queue of the workers and activated a reboot function so that a new worker will replace the impaired one. Morever, if the worker encounters an exception while working it will send the exception to the manager and regroup to handle a new message.

Threads:
--------
We used threads in our Manager - one thread which operates the thread pool for the clients, another thread that checks the status of the workers, and a thread pool that processes the responses from the workers. This is the only place where we thought it is neccessary to use threads in out application, so we could handle a big amount of clients at the same time.

Termination:
--------
The termination process is well managed, and everything is closed once requested - local app, manager, workers and queues are deleted.

System:
--------
All the workers are working equally, they have access to the input queue where they fetch an assignment, work on it, and send the resulting work back to the output queue. They know nothing more than they need and they are treated equally, The manager is resposible of assembling the data and distribute it.
