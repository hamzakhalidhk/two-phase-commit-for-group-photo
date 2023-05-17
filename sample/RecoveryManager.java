/**
 * Represents the recovery manager to maintain the log files.
 *
 * @author Hamza Khalid
 */

import java.io.*;
public class RecoveryManager {

    private int commitID;
    private String[] userNodes;
    private File file;

    /**
     * Creates a new RecoveryManager object with the specified parameters.
     * @param commitID the ID of the commit.
     * @param collageName the name of the collage.
     * @param userNodes an array of user nodes.
     * @param sources an array of image sources.
     * @param img the collage associated with this recovery manager.
    */
    public RecoveryManager (int commitID, String collageName, String[] userNodes, String[] sources, byte[] img) {
        this.commitID = commitID;
        this.userNodes = userNodes;
        try {
            this.file = new File(commitID + ".tmp");
            if (!this.file.exists()) {
                this.file.createNewFile();
                writeLog(Integer.toString(commitID) + "," + collageName + "," + String.join(",", userNodes) + "\n" + String.join(" ", sources));
            }
            
        } catch (Exception e) {
            e.printStackTrace();
        }
       
    }

    /**
    * Writes a log message to a file.
    * @param log the log message to be written
    */
    public void writeLog(String log) {

       try {
            BufferedWriter writer = new BufferedWriter(new FileWriter(file, true));
            writer.append(log);
            writer.newLine();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
    * Deletes the log file associated with this object.
    * If the file cannot be deleted, an exception is printed to the standard error stream.
    */
    public void deleteLog() {
        
        try {
            this.file.delete();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
    }

    /**
    * Deletes the last decision from the log file associated with this object.
    * The last decision is either "COMMIT" or "ABORT".
    * If the file cannot be read or written to, an exception is printed to the standard error stream.
    */
    public void deleteLastDecision() {

        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            StringBuilder contentBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                contentBuilder.append(line).append("\n");
            }
            reader.close();
            String content = contentBuilder.toString();

            // Check if the last line is "COMMIT" or "ABORT"
            String[] lines = content.split("\n");
            String lastLine = lines[lines.length - 1].trim();
            Boolean spacer = false;

            if (lastLine.equals("COMMIT") || lastLine.equals("ABORT")) {
                // Remove the last line
                content = content.substring(0, content.lastIndexOf(lastLine)).trim();
                spacer = true;
            }

            // Write the updated content back to the file
            BufferedWriter writer = new BufferedWriter(new FileWriter(file));

            if (spacer) content = content + "\n";
            
            writer.write(content);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

    }
    
}