import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import org.bson.Document;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Manages the MongoDB Atlas connection and student data synchronization.
 */
public class MongoManager {
    private static final String CONFIG_FILE = "db_config.json";
    private static MongoClient mongoClient = null;
    private static MongoDatabase database = null;
    private static MongoCollection<Document> collection = null;

    private static volatile boolean connected = false;
    private static volatile String statusMessage = "Not initialized";
    private static volatile String currentUri = "";
    private static volatile String currentUsername = "";
    private static volatile String currentDbName = "student_management_db";
    private static volatile String currentCollName = "students";

    public static synchronized void init() {
        loadConfigAndConnect();
    }

    public static synchronized void loadConfigAndConnect() {
        File f = new File(CONFIG_FILE);
        if (!f.exists()) {
            statusMessage = "db_config.json not found";
            connected = false;
            return;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(f), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            Map<String, Object> config = StudentHttpServer.MiniJson.parseObject(sb.toString());

            String rawUri = (String) config.getOrDefault("mongodb_uri", "");
            String user = (String) config.getOrDefault("db_username", "");
            String db = (String) config.getOrDefault("db_name", "student_management_db");
            String coll = (String) config.getOrDefault("collection_name", "students");

            currentUri = rawUri;
            currentUsername = user != null ? user.trim() : "";
            currentDbName = db != null ? db.trim() : "student_management_db";
            currentCollName = coll != null ? coll.trim() : "students";

            connectInternal(currentUri, currentUsername, currentDbName, currentCollName);
        } catch (Exception e) {
            connected = false;
            statusMessage = "Error loading db_config.json: " + e.getMessage();
            System.err.println("[MongoDB] " + statusMessage);
        }
    }

    public static synchronized boolean updateConfig(String uri, String username, String dbName, String collName) {
        currentUri = uri != null ? uri.trim() : currentUri;
        currentUsername = username != null ? username.trim() : currentUsername;
        if (dbName != null && !dbName.trim().isEmpty()) currentDbName = dbName.trim();
        if (collName != null && !collName.trim().isEmpty()) currentCollName = collName.trim();

        // Save to file
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(CONFIG_FILE), StandardCharsets.UTF_8))) {
            pw.println("{");
            pw.println("  \"mongodb_uri\": \"" + escapeJson(currentUri) + "\",");
            pw.println("  \"db_username\": \"" + escapeJson(currentUsername) + "\",");
            pw.println("  \"db_name\": \"" + escapeJson(currentDbName) + "\",");
            pw.println("  \"collection_name\": \"" + escapeJson(currentCollName) + "\",");
            pw.println("  \"sync_with_file\": true");
            pw.println("}");
        } catch (Exception e) {
            System.err.println("[MongoDB] Could not write " + CONFIG_FILE + ": " + e.getMessage());
        }

        return connectInternal(currentUri, currentUsername, currentDbName, currentCollName);
    }

    private static boolean connectInternal(String rawUri, String username, String dbName, String collName) {
        close();

        if (rawUri == null || rawUri.trim().isEmpty()) {
            connected = false;
            statusMessage = "MongoDB URI not provided";
            return false;
        }

        String effectiveUri = rawUri.trim();
        if (effectiveUri.contains("<db_username>")) {
            if (username != null && !username.trim().isEmpty()) {
                effectiveUri = effectiveUri.replace("<db_username>", username.trim());
            } else {
                connected = false;
                statusMessage = "MongoDB username required. Set your database username in Settings.";
                System.out.println("[MongoDB] " + statusMessage);
                return false;
            }
        }

        try {
            System.out.println("[MongoDB] Connecting to cluster...");
            mongoClient = MongoClients.create(effectiveUri);
            database = mongoClient.getDatabase(dbName);
            collection = database.getCollection(collName);

            // Test connection by pinging database (timeout 4s)
            Document ping = new Document("ping", 1);
            database.runCommand(ping);

            connected = true;
            statusMessage = "Connected to MongoDB Atlas (" + dbName + "." + collName + ")";
            System.out.println("[MongoDB] 🟢 " + statusMessage);
            return true;
        } catch (Exception e) {
            connected = false;
            statusMessage = "Connection failed: " + e.getMessage();
            System.err.println("[MongoDB] 🔴 " + statusMessage);
            close();
            return false;
        }
    }

    public static boolean isConnected() {
        return connected;
    }

    public static String getStatusMessage() {
        return statusMessage;
    }

    public static Map<String, Object> getStatusInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("connected", connected);
        info.put("message", statusMessage);
        info.put("database", currentDbName);
        info.put("collection", currentCollName);
        info.put("username", currentUsername);
        info.put("has_placeholder", currentUri.contains("<db_username>") && currentUsername.isEmpty());
        info.put("uri_masked", maskUri(currentUri, currentUsername));
        long count = 0;
        if (connected && collection != null) {
            try { count = collection.countDocuments(); } catch (Exception ignored) { }
        }
        info.put("document_count", count);
        return info;
    }

    private static String maskUri(String raw, String user) {
        if (raw == null) return "";
        String s = raw;
        if (!user.isEmpty() && s.contains("<db_username>")) {
            s = s.replace("<db_username>", user);
        }
        return s.replaceAll(":(.*?)@", ":****@");
    }

    /**
     * Fetch all documents from MongoDB.
     */
    public static List<Document> getAllDocuments() {
        List<Document> list = new ArrayList<>();
        if (!connected || collection == null) return list;
        try {
            for (Document doc : collection.find()) {
                list.add(doc);
            }
        } catch (Exception e) {
            System.err.println("[MongoDB] Error fetching documents: " + e.getMessage());
        }
        return list;
    }

    /**
     * Upsert a student record in MongoDB.
     */
    public static boolean upsertStudent(int id, String name, String dept, double marks) {
        if (!connected || collection == null) return false;
        try {
            Document doc = new Document("id", id)
                    .append("name", name)
                    .append("dept", dept)
                    .append("marks", marks)
                    .append("updated_at", new Date().toString());

            ReplaceOptions opts = new ReplaceOptions().upsert(true);
            collection.replaceOne(Filters.eq("id", id), doc, opts);
            System.out.println("[MongoDB] Saved/Updated Student #" + id + " in collection " + currentCollName);
            return true;
        } catch (Exception e) {
            System.err.println("[MongoDB] Error saving student #" + id + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Delete a student record from MongoDB.
     */
    public static boolean deleteStudent(int id) {
        if (!connected || collection == null) return false;
        try {
            collection.deleteOne(Filters.eq("id", id));
            System.out.println("[MongoDB] Deleted Student #" + id + " from collection " + currentCollName);
            return true;
        } catch (Exception e) {
            System.err.println("[MongoDB] Error deleting student #" + id + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Two-way sync between MongoDB and in-memory/file list.
     * Imports from MongoDB if remote documents exist, or exports local students to MongoDB.
     */
    public static synchronized int syncTwoWay(List<StudentHttpServer.Student> localStudents) {
        if (!connected || collection == null) return 0;
        int synced = 0;
        try {
            long count = collection.countDocuments();
            if (count > 0) {
                // MongoDB has data: merge/populate from MongoDB
                Map<Integer, StudentHttpServer.Student> localMap = new HashMap<>();
                for (StudentHttpServer.Student s : localStudents) localMap.put(s.id, s);

                for (Document doc : collection.find()) {
                    try {
                        int id = doc.getInteger("id", -1);
                        if (id == -1 && doc.get("id") != null) {
                            id = Integer.parseInt(doc.get("id").toString());
                        }
                        String name = doc.getString("name");
                        String dept = doc.getString("dept");
                        Double marks = doc.getDouble("marks");
                        if (marks == null && doc.get("marks") != null) {
                            marks = Double.parseDouble(doc.get("marks").toString());
                        }

                        if (id > 0 && name != null && dept != null && marks != null) {
                            StudentHttpServer.Student existing = localMap.get(id);
                            if (existing == null) {
                                localStudents.add(new StudentHttpServer.Student(id, name, dept, marks));
                                synced++;
                            } else {
                                existing.name = name;
                                existing.dept = dept;
                                existing.marks = marks;
                            }
                        }
                    } catch (Exception ignored) { }
                }
            } else {
                // MongoDB is empty: push existing local students to MongoDB
                for (StudentHttpServer.Student s : localStudents) {
                    upsertStudent(s.id, s.name, s.dept, s.marks);
                    synced++;
                }
            }
        } catch (Exception e) {
            System.err.println("[MongoDB] Sync error: " + e.getMessage());
        }
        return synced;
    }

    public static synchronized void close() {
        if (mongoClient != null) {
            try {
                mongoClient.close();
            } catch (Exception ignored) { }
            mongoClient = null;
            database = null;
            collection = null;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
