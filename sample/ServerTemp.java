/**
 * Represents the server class that communicates with the user nodes 
 * for the two phase commit.
 *
 * @author Hamza Khalid
 */

import java.util.concurrent.ConcurrentHashMap;
import java.util.*;
import java.io.*;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.Queue;

public class Server implements ProjectLib.CommitServing {
    
    /**
    * Represents a mapping of commit IDs to the number of votes received for that commit.
    **/
    private static ConcurrentHashMap<Integer, Integer> votes = new ConcurrentHashMap<>(); 
    /**
    * Represents a mapping of commit IDs to the message queue for that commit.
    **/
    public static ConcurrentHashMap<Integer, LinkedList<RequestResponseMessage>> msgQueues = new ConcurrentHashMap<Integer, LinkedList<RequestResponseMessage>>();
    /**
    * Represents a static instance of the ProjectLib class, which is used for communication with the user nodes.
    **/
    public static ProjectLib PL;
    /** 
    * Represents a private static integer that is incremented each time a new commit is made.
    **/
    private static int commitID = 0;
    /**
     * Constants
    **/
    private static final int VOTE_TIMEOUT = 6000;
    private static final int RETRANSMISSION_TIMEOUT = 3000;

    /**
    * Generates a unique commit ID for each commit made in a synchronized manner.
    * @return an integer value representing the generated commit ID
    */
    private synchronized int generateUniqueCommitID() {
        commitID++;
        return commitID;
    } 

    /**
    * Initiates a commit operation for the given file with the specified image and sources.
    * Each commit operation is executed in a separate thread.
    * @param filename The name of the collage to be committed.
    * @param img The image data (collage) to be committed.
    * @param sources The sources to be committed along with the file.
    */
    public void startCommit( String filename, byte[] img, String[] sources ) {

        // Generate unique commit ID.
        int commitID = generateUniqueCommitID();
        // Start a new commit runnable for each commit.
        Commit commitRunnable = new Commit(filename, img, sources, commitID, null);
        Thread thread = new Thread(commitRunnable);
        thread.start();

    }

    /**
    * Serializes the given RequestResponseMessage object into a byte array.
    * @param message The RequestResponseMessage object to be serialized.
    * @return The serialized byte array of the message object.
    * @throws Exception if an error occurs during the serialization process.
    */
    private static byte[] serializeMessage(RequestResponseMessage message){

        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(bos);

            oos.writeObject(message);
            oos.close();

            byte[] serializedMessage = bos.toByteArray();
            return serializedMessage;

        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;

    }

    /**
    * Deserializes a byte array into a {@link RequestResponseMessage} object.
    * @param serializedMessage The serialized message to be deserialized.
    * @return The deserialized {@link RequestResponseMessage} object.
    * @throws Exception if an error occurs while deserializing the message.
    */
    private static RequestResponseMessage deserializeMessage(byte[] serializedMessage) {

        try {
            ByteArrayInputStream bis = new ByteArrayInputStream(serializedMessage);
            ObjectInputStream ois = new ObjectInputStream(bis);
            RequestResponseMessage deserializedMessage = (RequestResponseMessage) ois.readObject();
            ois.close();
            bis.close();

            return deserializedMessage;
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
        
    }

    public static class Commit implements Runnable {

        private String filename;
        private byte[] img;
        private String[] userNodes;
        private int commitID;
        private RecoveryManager recoveryManager;
        private ConcurrentHashMap<String, List<String>> userImageMap = new ConcurrentHashMap<>();
		private String mode = null;

        public Commit(String filename, byte[] img, String[] sources, int commitID, String mode) {
            this.filename = filename;
            this.img = img;
            this.commitID = commitID;
            this.userImageMap = getSourcesByNodeMap(sources);
            this.userNodes = userImageMap.keySet().toArray(new String[0]);
            this.recoveryManager = new RecoveryManager(this.commitID, this.filename, this.userNodes, sources, this.img);
            PL.fsync();
        }

        /**
        * Returns a ConcurrentHashMap that maps a user to a list of image names,
        * based on an input array of source strings in the format "user:imageName".
        * @param sources an array of source strings in the format "user:imageName".
        * @return a ConcurrentHashMap that maps a user to a list of image names.
        */
        private ConcurrentHashMap<String, List<String>> getSourcesByNodeMap(String[] sources) {

            ConcurrentHashMap<String, List<String>> userImageMap = new ConcurrentHashMap<>();

            for (String image : sources) {
                String[] tokens = image.split(":");
                String user = tokens[0];
                String imageName = tokens[1];

                if (!userImageMap.containsKey(user)) {
                    userImageMap.put(user, new ArrayList<>());
                }
                userImageMap.get(user).add(imageName);
            }

            return userImageMap;

        }

        public void run() {

            // If the mode variable is set to either DISTRIBUTE or COMMIT_FAILURE, it indicates
            // that we are in the recovery mode. 
            
            // DISTRIBUTE indicates that the commit was successful before the crash and now we 
            // have to distribute the voting decisions to the user nodes and get their
            // acknowledgements. 
			if (mode != null && mode.equals("DISTRIBUTE")) {

				distributeDecisions(MessageType.DISTRIBUTE);
				getAcknowledgements(MessageType.DISTRIBUTE);

			} else if (mode != null && mode.equals("COMMIT_FAILURE")) {

                // COMMIT_FAILURE indicates that a commit was not successful before the crash now 
                // we have to distribute the voting decisions to the user nodes and get their
                // acknowledgements. 
				distributeDecisions(MessageType.COMMIT_FAILURE);
				getAcknowledgements(MessageType.COMMIT_FAILURE);

			} else { // Else we are not in the recovery mode.

				prepare();

                // If all nodes vote 'yes'
				if (allNodesVoted()) {

                    // Save the collage on the server.
                    saveCollage();
                    // Update log file.
                    recoveryManager.deleteLastDecision();
                    recoveryManager.writeLog("COMMIT");
                    PL.fsync();
                    // Distribute the successful commit message among user nodes.
                    distributeDecisions(MessageType.DISTRIBUTE);
                    // Wait for acknowledgements from the user nodes.
					getAcknowledgements(MessageType.DISTRIBUTE);
                    
				} else { // If any of the nodes vote 'no'

                    // Update log file.
                    recoveryManager.deleteLastDecision();
                    recoveryManager.writeLog("ABORT");
                    PL.fsync();
                    // Distribute the failure commit message among user nodes.
                    distributeDecisions(MessageType.COMMIT_FAILURE);
                    // Wait for acknowledgements from the user nodes.
					getAcknowledgements(MessageType.COMMIT_FAILURE);
                    
				}

                // Delete the log file if every thing goes as expected.
                recoveryManager.deleteLog();
                PL.fsync();
				
			}
            

        }

        /**
        * Saves the current collage as an image file with the given filename.
        * @param filename the name of the collage.
        * @throws IOException if an I/O error occurs while writing the file.
        */
        private void saveCollage() {

            try {

                FileOutputStream out = new FileOutputStream(filename);
                out.write(this.img);
                out.close();

            } catch (Exception e) {
                e.printStackTrace();
            }

        }

        /**
        * Distributes decisions to all user nodes for a given message type.
        * @param messageType The type of message to distribute decisions for.
        */
        private void distributeDecisions(MessageType messageType) {
            
            for (String userNode: userNodes) {
                
                List<String> nodeSources =  userImageMap.get(userNode);
                String[] nodeSourcesArray = nodeSources.toArray(new String[nodeSources.size()]);
                RequestResponseMessage requestResponseMessage  = new RequestResponseMessage(this.commitID, userNode, nodeSourcesArray, filename,
                                                                                            this.img, messageType);
                byte[] serializedBytes = serializeMessage(requestResponseMessage);
                PL.sendMessage(new ProjectLib.Message(userNode, serializedBytes));
                
            }
            
        }

        /**
        * Retrieves acknowledgements from all the user nodes in the system for the given message type by looping through
        * the message queues associated with the current commit ID and waiting for ACK messages from each user node. If an ACK
        * message is received, the corresponding user node is removed from the {@code userNodesSet} collection. If no ACK message
        * is received for a user node within the retransmission timeout, a new {@code RequestResponseMessage} instance is created
        * for that user node and sent to it via the {@code PL.sendMessage()} method. This process continues until all user nodes
        * have acknowledged the message, or until the {@code userNodesSet} collection becomes empty.
        * @param messageType The type of message to retrieve acknowledgements for.
        * @see RequestResponseMessage
        * @see ProjectLib
        */
        private void getAcknowledgements(MessageType messageType) {

            HashSet<String> userNodesSet = new HashSet<>(Arrays.asList(this.userNodes));

            while (!userNodesSet.isEmpty()) {

                long t1 = System.currentTimeMillis();
                long t2 = System.currentTimeMillis();

                while ((t2 - t1) < RETRANSMISSION_TIMEOUT && !userNodesSet.isEmpty()) {

                    RequestResponseMessage response = msgQueues.get(this.commitID).poll();
                    if (response == null || response.messageType != MessageType.ACK) {
                        t2 = System.currentTimeMillis();
                        continue;
                    } else if (response != null && response.messageType == MessageType.ACK){
                        userNodesSet.remove(response.userNode);
                    }
                    
                    t2 = System.currentTimeMillis();
                }

                if (userNodesSet.isEmpty()) break;

                for (String userNode: userNodesSet) {
                    List<String> nodeSources =  userImageMap.get(userNode);
                    String[] nodeSourcesArray = nodeSources.toArray(new String[nodeSources.size()]);
                    RequestResponseMessage requestResponseMessage  = new RequestResponseMessage(this.commitID, userNode, nodeSourcesArray, filename,
                                                                                                this.img, messageType);
                    byte[] serializedBytes = serializeMessage(requestResponseMessage);
                    PL.sendMessage(new ProjectLib.Message(userNode, serializedBytes));

                }
                
            }

        }

        /**
        * Checks if all the user nodes have voted for the current commit by polling for {@code RequestResponseMessage} objects
        * with the {@code MessageType.VOTE} type from the message queue associated with the commit ID. It updates the number of
        * votes received in the {@code votes} map for the commit ID whenever it receives a valid vote from a user node.
        * @return {@code true} if all user nodes have voted for the current commit, {@code false} otherwise.
        * @throws NullPointerException If the message queue associated with the commit ID is null.
        * @see RequestResponseMessage
        */
        private boolean allNodesVoted() {
            long t1 = System.currentTimeMillis();
            long t2 = System.currentTimeMillis();
            votes.put(this.commitID, 0);

            while ((votes.get(this.commitID) < userNodes.length) && ((t2 - t1) < VOTE_TIMEOUT)) {

                if (msgQueues.get(this.commitID) == null) {
                    t2 = System.currentTimeMillis();
                    continue;
                }
                RequestResponseMessage response = msgQueues.get(this.commitID).poll();

                if (response == null || response.messageType != MessageType.VOTE) {
                    t2 = System.currentTimeMillis();
                    continue;
                }

                if (response.userNodeVote) {
                    votes.put(response.commitID, votes.getOrDefault(response.commitID, 0) + 1);
                }
                else return false;

                if (votes.getOrDefault(this.commitID, 0) == userNodes.length) return true;

                t2 = System.currentTimeMillis();
            }

            return votes.getOrDefault(this.commitID, 0) >= userNodes.length;

        }

        /**
        * Prepares a commit message for all the user nodes in the system by creating a {@code RequestResponseMessage}
        * instance for each user node, serializing it into a byte array, and sending it as a {@code ProjectLib.Message}
        * to the corresponding user node using the {@code PL.sendMessage()} method.
        * @see RequestResponseMessage
        * @see ProjectLib
        **/
        private void prepare() {

            for (String userNode: userNodes) {
            
                List<String> nodeSources =  userImageMap.get(userNode);
                String[] nodeSourcesArray = nodeSources.toArray(new String[nodeSources.size()]);
                RequestResponseMessage requestResponseMessage  = new RequestResponseMessage(commitID, userNode, nodeSourcesArray, filename,
                                                                                            this.img, MessageType.COMMIT);
                byte[] serializedBytes = serializeMessage(requestResponseMessage);
                PL.sendMessage(new ProjectLib.Message(userNode, serializedBytes));

            }
        }

    }

    /**
    * Returns a list of the absolute file paths of all the files in the current directory
    * that have the extension ".tmp".
    * @return A list of absolute file paths of all the files with extension ".tmp" in the current directory.
    * @throws SecurityException If a security manager exists and its checkRead method denies read access to the file.
    * @see File#listFiles()
    */
    private static List<String> listLogFiles() {

        List<String> filesWithExtension = new ArrayList<>();
        File folder = new File(".");
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".tmp")) {
                    filesWithExtension.add(file.getAbsolutePath());
                }
            }
        }
        return filesWithExtension;
    }

    /**
    * Reads the commit recovery information from the specified file, creates and starts a new thread to execute the
    * corresponding commit or commit failure process, and adds the associated message queue to the {@code msgQueues} map.
    * @param fileName The name of the collage.
    * @throws NullPointerException If the file name is null.
    * @throws IllegalArgumentException If the specified file does not exist or cannot be read.
    * @see Commit
    * @see RequestResponseMessage
    */
    private static void startCommitRecovery(String fileName) {

		Commit commitSuccessRunnable = null;
		Commit commitFailureRunnable = null;
        String collageName = null;
		boolean isSuccess = false;
		String[] sources = null;
        Thread thread = null;
        byte[] img = null;
        int commitID = 0;

        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {

            String line;
            // Read the log file and see if the commit before the crash is a success or failure.
            while ((line = reader.readLine()) != null) {

                // The first line of the log contains the commit ID and the collage name.
                String[] row = line.split(",");
                commitID = Integer.parseInt(row[0]);
                collageName = row[1];

                // The second line of the log contains the img sources.
                line = reader.readLine();
                sources = line.split(" ");
                
                // The third line of the log contains the last commit decision message.
                line = reader.readLine();

                if (line == null) {
                    isSuccess = false;
                    break;
                } else if (line.equals("COMMIT")) {
                    isSuccess = true;
                    break;
                } else if (line.equals("ABORT")) { 
                    isSuccess = false;
                    break;
                }

            }

            // Initialize the message queue for the recovery thread.
            if (!msgQueues.containsKey(commitID)) {
                msgQueues.put(commitID, new LinkedList<RequestResponseMessage>());
            }

            // If the commit before the crash is successful, start recovery thread.
			if (isSuccess) {

				commitSuccessRunnable = new Commit(collageName, img, sources, commitID, "DISTRIBUTE");
				thread = new Thread(commitSuccessRunnable);
				thread.start();

			} else {
                // If the commit before the crash is a failure, start failure thread.
				commitFailureRunnable = new Commit(collageName, img, sources, commitID, "COMMIT_FAILURE");
				thread = new Thread(commitFailureRunnable);
				thread.start();

			}

        } catch (IOException e) {
            e.printStackTrace();
        }
        
    }
    
    public static void main ( String args[] ) throws Exception {

        if (args.length != 1) throw new Exception("Need 1 arg: <port>");
        Server srv = new Server();
        PL = new ProjectLib( Integer.parseInt(args[0]), srv );

        // List the log files (if any).
        List<String> logFiles = listLogFiles();
        // Start recovery for each log file.
        for (String logFile: logFiles) {
            startCommitRecovery(logFile);
        }

        // main loop
        while (true) {
            // Get messages from the user nodes and deserialize them.
            ProjectLib.Message msg = PL.getMessage();
            RequestResponseMessage deserializedMessage = deserializeMessage(msg.body);
            int commitID = deserializedMessage.commitID;
            // Initialize the message queue for a specific commit.
            if (!msgQueues.containsKey(commitID)) {
                msgQueues.put(commitID, new LinkedList<RequestResponseMessage>());
            }
            // Put the deserialized messages to the message queue. 
            msgQueues.get(commitID).add(deserializedMessage);

        }

    }
}


