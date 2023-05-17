import java.io.Serializable;

public class RequestResponseMessage implements Serializable {

    public int commitID;
    public String userNode;
    public String[] nodeSources;
    public String collageName;
    public MessageType messageType;
    public boolean userNodeVote = false;
    public byte[] collage;

    public RequestResponseMessage(  int commitID, 
                                    String userNode, 
                                    String[] nodeSources, 
                                    String collageName, 
                                    byte[] collage,
                                    MessageType messageType) {
        this.commitID = commitID;
        this.userNode = userNode;
        this.nodeSources = nodeSources;
        this.collageName = collageName;
        this.collage = collage;
        this.messageType = messageType;

    }

    public RequestResponseMessage(  int commitID, 
                                    String userNode, 
                                    boolean userNodeVote,
                                    MessageType messageType) {
        this.commitID = commitID;
        this.userNode = userNode;
        this.userNodeVote = userNodeVote;
        this.messageType = messageType;

    }

}