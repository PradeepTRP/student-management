import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.Headers;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Robust, Full-Featured Java HTTP Web Server for the Student Management System.
 * - Handles RESTful API endpoints for complete Student CRUD operations.
 * - Serves frontend static files (HTML, CSS, JS) with proper MIME types.
 * - Connects to MongoDB Atlas for cloud persistence.
 * - Synchronizes with 'students.txt' for local persistence.
 */
public class StudentHttpServer {
    private static final int PORT = 8000;
    private static final String DATA_FILE = "students.txt";
    private static final String PUBLIC_DIR = "public";
    public static final List<Student> students = new ArrayList<>();
    private static final Object lock = new Object();

    // -----------------------------------------------------------------
    // Student Model
    // -----------------------------------------------------------------
    public static class Student {
        public int id;
        public String name;
        public String dept;
        public double marks;

        public Student(int id, String name, String dept, double marks) {
            this.id = id;
            this.name = name;
            this.dept = dept;
            this.marks = marks;
        }

        public String toJson() {
            return "{"
                + "\"id\":" + id + ","
                + "\"name\":\"" + escapeJson(name) + "\","
                + "\"dept\":\"" + escapeJson(dept) + "\","
                + "\"marks\":" + String.format(Locale.US, "%.1f", marks)
                + "}";
        }
    }

    public static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < ' ') {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------
    // File Persistence (students.txt)
    // -----------------------------------------------------------------
    public static void loadStudents() {
        synchronized (lock) {
            students.clear();
            File file = new File(DATA_FILE);
            if (!file.exists()) return;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    String[] parts = line.split(",", -1);
                    if (parts.length >= 4) {
                        try {
                            int id = Integer.parseInt(parts[0].trim());
                            String name = parts[1].trim();
                            String dept = parts[2].trim();
                            double marks = Double.parseDouble(parts[3].trim());
                            students.add(new Student(id, name, dept, marks));
                        } catch (Exception ignored) { }
                    }
                }
            } catch (Exception e) {
                System.err.println("[Error] Failed to read " + DATA_FILE + ": " + e.getMessage());
            }
        }
    }

    public static boolean saveStudents() {
        synchronized (lock) {
            try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(DATA_FILE), StandardCharsets.UTF_8))) {
                for (Student s : students) {
                    pw.println(s.id + "," + s.name + "," + s.dept + "," + String.format(Locale.US, "%.1f", s.marks));
                }
                return true;
            } catch (Exception e) {
                System.err.println("[Error] Failed to write " + DATA_FILE + ": " + e.getMessage());
                return false;
            }
        }
    }

    // -----------------------------------------------------------------
    // Main Entry Point
    // -----------------------------------------------------------------
    public static void main(String[] args) {
        loadStudents();

        // Initialize MongoDB Atlas connection
        try {
            MongoManager.init();
            if (MongoManager.isConnected()) {
                int synced = MongoManager.syncTwoWay(students);
                if (synced > 0) {
                    saveStudents();
                    System.out.println("[MongoDB] Initial sync completed: " + synced + " records updated.");
                }
            }
        } catch (Throwable t) {
            System.err.println("[MongoDB] Initialization note: " + t.getMessage());
        }

        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);

            // API Endpoints
            server.createContext("/api/", new ApiHandler());

            // Static Frontend Files
            server.createContext("/", new StaticFileHandler());

            server.setExecutor(Executors.newCachedThreadPool());
            System.out.println("==============================================================");
            System.out.println(" [ONLINE] JAVA STUDENT MANAGEMENT SYSTEM - WEB SERVER");
            System.out.println("==============================================================");
            System.out.println(" * Website URL:     http://localhost:" + PORT);
            System.out.println(" * Connected API:   http://localhost:" + PORT + "/api/students");
            System.out.println(" * Database Status: " + (MongoManager.isConnected() ? "🟢 MongoDB Connected" : "🟡 " + MongoManager.getStatusMessage()));
            System.out.println(" * Local File:      " + new File(DATA_FILE).getAbsolutePath());
            System.out.println(" * Loaded Students: " + students.size() + " records");
            System.out.println("==============================================================");
            System.out.println(" Ready to accept requests. Press Ctrl+C to stop.\n");

            server.start();
        } catch (IOException e) {
            System.err.println("Fatal: Could not start Java server on port " + PORT + ": " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------
    // REST API Request Handler
    // -----------------------------------------------------------------
    static class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                addCorsHeaders(exchange);
                String method = exchange.getRequestMethod().toUpperCase();
                String rawPath = exchange.getRequestURI().getPath();
                String path = rawPath.replaceAll("/+$", ""); // strip trailing slashes

                // Pre-flight CORS request
                if ("OPTIONS".equals(method)) {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                    return;
                }

                // 1. Health check
                if ("/api/health".equals(path) && "GET".equals(method)) {
                    sendJsonResponse(exchange, 200, "{"
                        + "\"status\":\"ok\","
                        + "\"engine\":\"Java 21\","
                        + "\"db_connected\":" + MongoManager.isConnected() + ","
                        + "\"db_message\":\"" + escapeJson(MongoManager.getStatusMessage()) + "\","
                        + "\"message\":\"Java Backend Server is running smoothly\""
                        + "}");
                    return;
                }

                // 2. Database Status
                if ("/api/db/status".equals(path) && "GET".equals(method)) {
                    Map<String, Object> info = MongoManager.getStatusInfo();
                    StringBuilder sb = new StringBuilder("{");
                    sb.append("\"success\":true,");
                    sb.append("\"connected\":").append(info.get("connected")).append(",");
                    sb.append("\"message\":\"").append(escapeJson(String.valueOf(info.get("message")))).append("\",");
                    sb.append("\"database\":\"").append(escapeJson(String.valueOf(info.get("database")))).append("\",");
                    sb.append("\"collection\":\"").append(escapeJson(String.valueOf(info.get("collection")))).append("\",");
                    sb.append("\"username\":\"").append(escapeJson(String.valueOf(info.get("username")))).append("\",");
                    sb.append("\"has_placeholder\":").append(info.get("has_placeholder")).append(",");
                    sb.append("\"uri_masked\":\"").append(escapeJson(String.valueOf(info.get("uri_masked")))).append("\",");
                    sb.append("\"document_count\":").append(info.get("document_count"));
                    sb.append("}");
                    sendJsonResponse(exchange, 200, sb.toString());
                    return;
                }

                // 3. Database Configure & Test
                if ("/api/db/config".equals(path) && "POST".equals(method)) {
                    String body = readBody(exchange);
                    Map<String, Object> data = MiniJson.parseObject(body);
                    String uri = getString(data, "uri");
                    String username = getString(data, "username");
                    String db = getString(data, "database");
                    String coll = getString(data, "collection");

                    boolean ok = MongoManager.updateConfig(uri, username, db, coll);
                    if (ok) {
                        // Sync existing students into MongoDB
                        loadStudents();
                        MongoManager.syncTwoWay(students);
                        saveStudents();
                        sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Successfully connected to MongoDB Atlas! Data synchronized.\"}");
                    } else {
                        sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"" + escapeJson(MongoManager.getStatusMessage()) + "\"}");
                    }
                    return;
                }

                // 4. View raw MongoDB Documents (to easily view & verify database)
                if ("/api/db/documents".equals(path) && "GET".equals(method)) {
                    List<org.bson.Document> docs = MongoManager.getAllDocuments();
                    StringBuilder sb = new StringBuilder("{\"success\":true,\"connected\":")
                        .append(MongoManager.isConnected())
                        .append(",\"count\":").append(docs.size())
                        .append(",\"documents\":[");

                    for (int i = 0; i < docs.size(); i++) {
                        org.bson.Document doc = docs.get(i);
                        sb.append("{");
                        sb.append("\"id\":").append(doc.get("id")).append(",");
                        sb.append("\"name\":\"").append(escapeJson(String.valueOf(doc.get("name")))).append("\",");
                        sb.append("\"dept\":\"").append(escapeJson(String.valueOf(doc.get("dept")))).append("\",");
                        sb.append("\"marks\":").append(doc.get("marks")).append(",");
                        sb.append("\"_id\":\"").append(doc.get("_id") != null ? doc.get("_id").toString() : "").append("\",");
                        sb.append("\"updated_at\":\"").append(escapeJson(String.valueOf(doc.get("updated_at")))).append("\"");
                        sb.append("}");
                        if (i < docs.size() - 1) sb.append(",");
                    }
                    sb.append("]}");
                    sendJsonResponse(exchange, 200, sb.toString());
                    return;
                }

                // 5. Trigger Two-Way Sync
                if ("/api/db/sync".equals(path) && "POST".equals(method)) {
                    if (!MongoManager.isConnected()) {
                        sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"MongoDB is not connected. " + escapeJson(MongoManager.getStatusMessage()) + "\"}");
                        return;
                    }
                    loadStudents();
                    int synced = MongoManager.syncTwoWay(students);
                    saveStudents();
                    sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Synchronization complete (" + synced + " records synchronized).\"}");
                    return;
                }

                // 6. Aggregate statistics
                if ("/api/stats".equals(path) && "GET".equals(method)) {
                    loadStudents();
                    int total = students.size();
                    double sum = 0.0;
                    double max = 0.0;
                    double min = total > 0 ? 100.0 : 0.0;
                    int passed = 0;

                    Map<String, Integer> deptCounts = new LinkedHashMap<>();
                    for (Student s : students) {
                        sum += s.marks;
                        if (s.marks > max) max = s.marks;
                        if (s.marks < min) min = s.marks;
                        if (s.marks >= 50.0) passed++;

                        String d = s.dept != null ? s.dept.trim() : "Unknown";
                        deptCounts.put(d, deptCounts.getOrDefault(d, 0) + 1);
                    }

                    double avg = total > 0 ? (sum / total) : 0.0;
                    int failed = total - passed;
                    double passRate = total > 0 ? (((double) passed / total) * 100.0) : 0.0;

                    StringBuilder deptJson = new StringBuilder("{");
                    int idx = 0;
                    for (Map.Entry<String, Integer> entry : deptCounts.entrySet()) {
                        deptJson.append("\"").append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
                        if (++idx < deptCounts.size()) deptJson.append(",");
                    }
                    deptJson.append("}");

                    String statsJson = String.format(Locale.US,
                        "{\"success\":true,\"stats\":{"
                        + "\"total\":%d,"
                        + "\"average\":%.2f,"
                        + "\"highest\":%.2f,"
                        + "\"lowest\":%.2f,"
                        + "\"passed\":%d,"
                        + "\"failed\":%d,"
                        + "\"pass_rate\":%.1f,"
                        + "\"departments\":%s"
                        + "}}",
                        total, avg, max, min, passed, failed, passRate, deptJson.toString());

                    sendJsonResponse(exchange, 200, statsJson);
                    return;
                }

                // 7. List all students (with search & filter support)
                if ("/api/students".equals(path) && "GET".equals(method)) {
                    loadStudents();
                    Map<String, String> queryParams = parseQueryParams(exchange.getRequestURI().getRawQuery());
                    String q = queryParams.getOrDefault("search", "").trim().toLowerCase();
                    String dept = queryParams.getOrDefault("dept", "").trim().toLowerCase();

                    List<Student> filtered = new ArrayList<>();
                    for (Student s : students) {
                        boolean matchQuery = q.isEmpty()
                            || String.valueOf(s.id).contains(q)
                            || (s.name != null && s.name.toLowerCase().contains(q))
                            || (s.dept != null && s.dept.toLowerCase().contains(q));

                        boolean matchDept = dept.isEmpty() || "all".equals(dept)
                            || (s.dept != null && s.dept.equalsIgnoreCase(dept));

                        if (matchQuery && matchDept) {
                            filtered.add(s);
                        }
                    }

                    StringBuilder sb = new StringBuilder();
                    sb.append("{\"success\":true,");
                    sb.append("\"count\":").append(filtered.size()).append(",");
                    sb.append("\"total\":").append(students.size()).append(",");
                    sb.append("\"db_connected\":").append(MongoManager.isConnected()).append(",");
                    sb.append("\"students\":[");
                    for (int i = 0; i < filtered.size(); i++) {
                        sb.append(filtered.get(i).toJson());
                        if (i < filtered.size() - 1) sb.append(",");
                    }
                    sb.append("]}");

                    sendJsonResponse(exchange, 200, sb.toString());
                    return;
                }

                // 8. Create new student
                if ("/api/students".equals(path) && "POST".equals(method)) {
                    String body = readBody(exchange);
                    Map<String, Object> data = MiniJson.parseObject(body);
                    if (data.isEmpty()) {
                        sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Invalid JSON request body\"}");
                        return;
                    }

                    int id = getInt(data, "id");
                    String name = getString(data, "name");
                    String dept = getString(data, "dept");
                    double marks = getDouble(data, "marks");

                    if (id <= 0) {
                        sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Student ID must be a positive integer.\"}");
                        return;
                    }
                    if (name.isEmpty()) {
                        sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Student name cannot be empty.\"}");
                        return;
                    }
                    if (dept.isEmpty()) {
                        sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Department cannot be empty.\"}");
                        return;
                    }
                    if (marks < 0 || marks > 100) {
                        sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Marks must be between 0 and 100.\"}");
                        return;
                    }

                    loadStudents();
                    for (Student s : students) {
                        if (s.id == id) {
                            sendJsonResponse(exchange, 409, "{\"success\":false,\"message\":\"Student with ID " + id + " already exists!\"}");
                            return;
                        }
                    }

                    Student newS = new Student(id, name, dept, marks);
                    students.add(newS);
                    if (saveStudents()) {
                        // Synchronize to MongoDB
                        MongoManager.upsertStudent(id, name, dept, marks);
                        String dbNote = MongoManager.isConnected() ? " (Saved to MongoDB Atlas)" : "";
                        sendJsonResponse(exchange, 201, "{\"success\":true,\"message\":\"Student added successfully!" + dbNote + "\",\"student\":" + newS.toJson() + "}");
                    } else {
                        sendJsonResponse(exchange, 500, "{\"success\":false,\"message\":\"Failed to write to students.txt.\"}");
                    }
                    return;
                }

                // 9. Single Student Operations: /api/students/<id>
                if (path.startsWith("/api/students/")) {
                    String idStr = path.substring("/api/students/".length());
                    int targetId;
                    try {
                        targetId = Integer.parseInt(idStr);
                    } catch (NumberFormatException e) {
                        sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Invalid Student ID format: " + idStr + "\"}");
                        return;
                    }

                    // GET single student
                    if ("GET".equals(method)) {
                        loadStudents();
                        for (Student s : students) {
                            if (s.id == targetId) {
                                sendJsonResponse(exchange, 200, "{\"success\":true,\"student\":" + s.toJson() + "}");
                                return;
                            }
                        }
                        sendJsonResponse(exchange, 404, "{\"success\":false,\"message\":\"Student with ID " + targetId + " not found.\"}");
                        return;
                    }

                    // PUT update student
                    if ("PUT".equals(method)) {
                        String body = readBody(exchange);
                        Map<String, Object> data = MiniJson.parseObject(body);
                        if (data.isEmpty()) {
                            sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Invalid JSON payload.\"}");
                            return;
                        }

                        String name = getString(data, "name");
                        String dept = getString(data, "dept");
                        double marks = getDouble(data, "marks");

                        if (name.isEmpty()) {
                            sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Student name cannot be empty.\"}");
                            return;
                        }
                        if (dept.isEmpty()) {
                            sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Department cannot be empty.\"}");
                            return;
                        }
                        if (marks < 0 || marks > 100) {
                            sendJsonResponse(exchange, 400, "{\"success\":false,\"message\":\"Marks must be between 0 and 100.\"}");
                            return;
                        }

                        loadStudents();
                        Student target = null;
                        for (Student s : students) {
                            if (s.id == targetId) {
                                target = s;
                                break;
                            }
                        }

                        if (target == null) {
                            sendJsonResponse(exchange, 404, "{\"success\":false,\"message\":\"Student with ID " + targetId + " not found.\"}");
                            return;
                        }

                        target.name = name;
                        target.dept = dept;
                        target.marks = marks;

                        if (saveStudents()) {
                            // Synchronize to MongoDB
                            MongoManager.upsertStudent(targetId, name, dept, marks);
                            String dbNote = MongoManager.isConnected() ? " (Updated in MongoDB Atlas)" : "";
                            sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Student updated successfully!" + dbNote + "\",\"student\":" + target.toJson() + "}");
                        } else {
                            sendJsonResponse(exchange, 500, "{\"success\":false,\"message\":\"Failed to save changes to data file.\"}");
                        }
                        return;
                    }

                    // DELETE student
                    if ("DELETE".equals(method)) {
                        loadStudents();
                        boolean removed = students.removeIf(s -> s.id == targetId);
                        if (removed) {
                            if (saveStudents()) {
                                // Synchronize to MongoDB
                                MongoManager.deleteStudent(targetId);
                                String dbNote = MongoManager.isConnected() ? " (Removed from MongoDB Atlas)" : "";
                                sendJsonResponse(exchange, 200, "{\"success\":true,\"message\":\"Student ID " + targetId + " deleted successfully!" + dbNote + "\"}");
                            } else {
                                sendJsonResponse(exchange, 500, "{\"success\":false,\"message\":\"Failed to save changes to data file.\"}");
                            }
                        } else {
                            sendJsonResponse(exchange, 404, "{\"success\":false,\"message\":\"Student with ID " + targetId + " not found.\"}");
                        }
                        return;
                    }
                }

                // If path did not match
                sendJsonResponse(exchange, 404, "{\"success\":false,\"message\":\"API endpoint not found: " + path + "\"}");
            } catch (Exception ex) {
                ex.printStackTrace();
                sendJsonResponse(exchange, 500, "{\"success\":false,\"message\":\"Internal Server Error: " + escapeJson(ex.getMessage()) + "\"}");
            }
        }
    }

    // -----------------------------------------------------------------
    // Static Files Handler (Serves frontend: index.html, style.css, app.js)
    // -----------------------------------------------------------------
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            addCorsHeaders(exchange);
            String rawPath = exchange.getRequestURI().getPath();
            if (rawPath == null || rawPath.isEmpty() || rawPath.equals("/")) {
                rawPath = "/index.html";
            }

            // Strip leading slashes to safely construct relative paths on Windows
            String cleanPath = rawPath.replaceFirst("^[/\\\\]+", "");

            // Prevent path traversal
            if (cleanPath.contains("..")) {
                sendError(exchange, 403, "403 Forbidden");
                return;
            }

            File file = new File(PUBLIC_DIR, cleanPath);
            if (!file.exists() || file.isDirectory()) {
                file = new File(".", cleanPath);
            }

            if (!file.exists() || file.isDirectory()) {
                sendError(exchange, 404, "404 Not Found: " + cleanPath);
                return;
            }

            String mimeType = getMimeType(file.getName());
            byte[] bytes = Files.readAllBytes(file.toPath());

            exchange.getResponseHeaders().set("Content-Type", mimeType);
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
            exchange.sendResponseHeaders(200, bytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private void sendError(HttpExchange exchange, int code, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    // -----------------------------------------------------------------
    // Robust MIME Detection (Fixes Windows registry text/plain bug)
    // -----------------------------------------------------------------
    private static String getMimeType(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    // -----------------------------------------------------------------
    // HTTP Utilities
    // -----------------------------------------------------------------
    private static void addCorsHeaders(HttpExchange exchange) {
        Headers headers = exchange.getResponseHeaders();
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        headers.set("Access-Control-Allow-Headers", "Content-Type, Authorization, Accept, X-Requested-With");
    }

    private static void sendJsonResponse(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toString(StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseQueryParams(String rawQuery) {
        Map<String, String> map = new HashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) return map;
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            try {
                if (eq > 0) {
                    String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8.name());
                    String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8.name());
                    map.put(k, v);
                } else if (!pair.isEmpty()) {
                    String k = URLDecoder.decode(pair, StandardCharsets.UTF_8.name());
                    map.put(k, "");
                }
            } catch (Exception ignored) { }
        }
        return map;
    }

    // -----------------------------------------------------------------
    // Helper Extractors for JSON
    // -----------------------------------------------------------------
    private static int getInt(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        if (val != null) {
            try { return (int) Double.parseDouble(val.toString().trim()); } catch (Exception ignored) { }
        }
        return -1;
    }

    private static double getDouble(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val instanceof Number) return ((Number) val).doubleValue();
        if (val != null) {
            try { return Double.parseDouble(val.toString().trim()); } catch (Exception ignored) { }
        }
        return -1.0;
    }

    private static String getString(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString().trim() : "";
    }

    // -----------------------------------------------------------------
    // Lightweight, Self-Contained JSON Parser (MiniJson)
    // -----------------------------------------------------------------
    public static class MiniJson {
        private final String src;
        private int pos = 0;

        public MiniJson(String src) {
            this.src = src != null ? src.trim() : "";
        }

        public static Map<String, Object> parseObject(String json) {
            if (json == null || json.trim().isEmpty()) return new HashMap<>();
            return new MiniJson(json).parseMap();
        }

        private void skipWhitespace() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }

        private Map<String, Object> parseMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (pos >= src.length() || src.charAt(pos) != '{') return map;
            pos++; // skip '{'

            while (pos < src.length()) {
                skipWhitespace();
                if (pos >= src.length()) break;
                if (src.charAt(pos) == '}') {
                    pos++;
                    break;
                }
                String key = parseString();
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ':') {
                    pos++;
                }
                skipWhitespace();
                Object val = parseValue();
                map.put(key, val);

                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') {
                    pos++;
                }
            }
            return map;
        }

        private String parseString() {
            skipWhitespace();
            if (pos >= src.length() || src.charAt(pos) != '"') return "";
            pos++; // skip opening '"'
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') break;
                if (c == '\\' && pos < src.length()) {
                    char next = src.charAt(pos++);
                    if (next == '"') sb.append('"');
                    else if (next == '\\') sb.append('\\');
                    else if (next == 'n') sb.append('\n');
                    else if (next == 'r') sb.append('\r');
                    else if (next == 't') sb.append('\t');
                    else sb.append(next);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= src.length()) return null;
            char c = src.charAt(pos);
            if (c == '"') {
                return parseString();
            }
            if (c == '{') {
                return parseMap();
            }
            // Number, boolean or null
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char ch = src.charAt(pos);
                if (ch == ',' || ch == '}' || Character.isWhitespace(ch)) break;
                sb.append(ch);
                pos++;
            }
            String raw = sb.toString().trim();
            if ("true".equalsIgnoreCase(raw)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(raw)) return Boolean.FALSE;
            if ("null".equalsIgnoreCase(raw)) return null;
            try {
                if (raw.contains(".")) return Double.parseDouble(raw);
                return Long.parseLong(raw);
            } catch (Exception e) {
                return raw;
            }
        }
    }
}
