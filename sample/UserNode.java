
/**
 * Represents a user node that communicates with the server for the 
 * two phase commit.
 *
 * @author Hamza Khalid
 */

import java.util.concurrent.ConcurrentHashMap;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.*;

public class UserNode implements ProjectLib.MessageHandling {

    private final String myId;
    private static ProjectLib PL;
	private static File logFile;
    private static ConcurrentHashMap<String, Integer> busyFiles = new ConcurrentHashMap<>();

    public UserNode( String id ) {
        myId = id;
    }

    /**
    * Invokes whenever we have an incoming message. Respond with the voting result, resources
    * deletion or updation based on the MessageType.
    * @param msg The message.
    * @return {@code true} if the message was successfully delivered, {@code false} otherwise.
    * @see MessageType
    * @see RequestResponseMessage
    */
    public boolean deliverMessage( ProjectLib.Message msg ) {
        
        RequestResponseMessage messageReceived = deserializeMessage(msg.body);
        MessageType messageType = messageReceived.messageType;

        if (messageType == MessageType.COMMIT) {

            respondWithCommitResult(msg.addr, messageReceived);

        } else if (messageType == MessageType.DISTRIBUTE) {

            deleteResources(msg.addr, messageReceived);

        } else if (messageType == MessageType.COMMIT_FAILURE) {
          
            updateResources(msg.addr, messageReceived);

        }

        return true;
    }

    /**
    * Deletes the resources (images) associated with a specific commit from the given server node in case of commit success 
    * and responds with an acknowledgement message.
    * @param server the server node to which the message is delivered
    * @param messageReceived the message received
    */
    private synchronized void deleteResources(String server, RequestResponseMessage messageReceived) {

        String userNode = messageReceived.userNode;
        String[] nodeSources = messageReceived.nodeSources;

        for (String nodeSource: nodeSources) {
            
            File file = new File(nodeSource);
            if (file.exists()) file.delete();
            if (busyFiles.containsKey(nodeSource)) busyFiles.remove(nodeSource);
        }

        RequestResponseMessage requestResponseMessage  = new RequestResponseMessage(messageReceived.commitID, userNode, nodeSources, messageReceived.collageName,
                                                                                    messageReceived.collage, MessageType.ACK);
        byte[] serializedBytes = serializeMessage(requestResponseMessage);
        PL.sendMessage(new ProjectLib.Message(server, serializedBytes));

    }

    /**
    * Updates the resources (images) associated with a specific commit from the given server node in case of commit failure 
    * and responds with an acknowledgement message.
    * @param server the server node to which the message is delivered
    * @param messageReceived the message received
    */
    private synchronized void updateResources(String server, RequestResponseMessage messageReceived) {
        String userNode = messageReceived.userNode;
        String[] nodeSources = messageReceived.nodeSources;

        for (String nodeSource: nodeSources) {
            if (busyFiles.containsKey(nodeSource)) {
                busyFiles.remove(nodeSource);
            }
        }

        RequestResponseMessage requestResponseMessage  = new RequestResponseMessage(messageReceived.commitID, userNode, nodeSources, messageReceived.collageName,
                                                                                    messageReceived.collage, MessageType.ACK);

        byte[] serializedBytes = serializeMessage(requestResponseMessage);
        PL.sendMessage(new ProjectLib.Message(server, serializedBytes));

    }

    /**
    * Responds to a message from a server asking for the vote by checking the existence and availability of files and
    * asking from the current user (PL.askUser).
    * @param server the server to send the commit result to
    * @param messageReceived the message received from the server
    */
    private synchronized void respondWithCommitResult(String server, RequestResponseMessage messageReceived) {
        String userNode = messageReceived.userNode;
        String[] nodeSources = messageReceived.nodeSources;
        boolean veto = false;

        // Check if the file exists on the user node
        for (String nodeSource: nodeSources) {
            File file = new File(nodeSource);

            if (!file.exists()) veto = true;
            else if (busyFiles.containsKey(nodeSource)) veto = true;

        }

        for (String fileName : nodeSources) {
			try {
				BufferedWriter writer = new BufferedWriter(new FileWriter(logFile, true));
				writer.append(fileName + "," + messageReceived.commitID);
				writer.newLine();
				writer.close();
				PL.fsync();
			} catch (IOException e) {
				e.printStackTrace();
			}

			busyFiles.put(fileName, messageReceived.commitID);
        }

        boolean vote = PL.askUser(messageReceived.collage, nodeSources) && !veto;
       
        RequestResponseMessage requestResponseMessage  = new RequestResponseMessage(messageReceived.commitID, 
                                                                                    messageReceived.userNode, 
                                                                                    vote, 
                                                                                    MessageType.VOTE);
        byte[] serializedBytes = serializeMessage(requestResponseMessage);
        PL.sendMessage(new ProjectLib.Message(server, serializedBytes));

    }

    /**
    * Deserializes a byte array into a {@link RequestResponseMessage} object.
    * @param serializedMessage The serialized message to be deserialized.
    * @return The deserialized {@link RequestResponseMessage} object.
    * @throws Exception if an error occurs while deserializing the message.
    */
    private RequestResponseMessage deserializeMessage(byte[] serializedMessage) {

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

    /**
    * Serializes the given RequestResponseMessage object into a byte array.
    * @param message The RequestResponseMessage object to be serialized.
    * @return The serialized byte array of the message object.
    * @throws Exception if an error occurs during the serialization process.
    */
    private byte[] serializeMessage(RequestResponseMessage message){

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
    

    public static void main ( String args[] ) throws Exception {

        if (args.length != 2) throw new Exception("Need 2 args: <port> <id>");
        UserNode UN = new UserNode(args[1]);

        // If a log file exists, start the recovery by reading the file and updating the 
        // busy files map.
        logFile = new File(args[1]);
		if (logFile.exists()) {
			//Recovery
			try (BufferedReader reader = new BufferedReader(new FileReader(args[1]))) {

				String line;
				while ((line = reader.readLine()) != null) {
					String[] row = line.split(",");
					busyFiles.put(row[0], Integer.parseInt(row[1]));
				}

			} catch (IOException e) {
				e.printStackTrace();
			}
            // Delete the file once the recovery is completed.
			logFile.delete();
		} 
        // Create a fresh log file after the recovery.
		logFile.createNewFile();

        PL = new ProjectLib( Integer.parseInt(args[0]), args[1], UN );
    }
}

