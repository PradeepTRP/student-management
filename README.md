# 🎓 EduTrack - Student Management System (Java + MongoDB Atlas)

A full-stack Student Management Web Application with a **Java 21 RESTful backend**, **MongoDB Atlas cloud database synchronization**, and real-time local file persistence (`students.txt`).

---

## 🌟 MongoDB Atlas Integration

The system synchronizes all student operations in real-time between your web frontend, Java backend, and MongoDB Atlas database.

### 🍃 Database Configuration (`db_config.json`)
Your cluster URI is configured in [`db_config.json`](file:///c:/Users/PRADEEP/OneDrive/Desktop/student%20management/db_config.json):
```json
{
  "mongodb_uri": "mongodb+srv://<db_username>:WQAMVP3SHpCwwuVy@cluster0.xyt8qur.mongodb.net/?retryWrites=true&w=majority",
  "db_username": "",
  "db_name": "student_management_db",
  "collection_name": "students",
  "sync_with_file": true
}
```

### 🔐 Connecting to Your Atlas Cluster
In the connection string provided:
`mongodb+srv://<db_username>:WQAMVP3SHpCwwuVy@cluster0.xyt8qur.mongodb.net/`
1. Notice that `<db_username>` is a placeholder from MongoDB Atlas.
2. In the web interface, click the **"MongoDB"** button or the top status pill.
3. Enter your Atlas database username (the user you created in MongoDB Atlas for cluster `cluster0.xyt8qur.mongodb.net`) and click **"Test & Save Connection"**.
4. The backend connects directly to MongoDB Atlas, synchronizes all existing students, and turns the indicator to **`🟢 MongoDB Connected`**.

---

## 🔄 Two-Way Data Synchronization

Every action you take in the application is synchronized:
- **Add Student**: Saves the student in [`students.txt`](file:///c:/Users/PRADEEP/OneDrive/Desktop/student%20management/students.txt) and immediately inserts/upserts into MongoDB collection `students`.
- **Edit Student**: Updates details in [`students.txt`](file:///c:/Users/PRADEEP/OneDrive/Desktop/student%20management/students.txt) and updates the document in MongoDB Atlas.
- **Delete Student**: Removes the student from [`students.txt`](file:///c:/Users/PRADEEP/OneDrive/Desktop/student%20management/students.txt) and deletes the document in MongoDB Atlas.
- **Data Persistence**: Data remains available after refreshing the browser or restarting the computer.
- **View & Verify Live MongoDB Documents**: Click the **"MongoDB"** button in the navbar and select **"View MongoDB Records"** to inspect the live documents directly from your MongoDB Atlas database (including MongoDB `_id` and timestamps).

---

## 🚀 How to Run

### Method 1: One-Click Launch (Recommended)
Double-click **`start.bat`** (or **`run-java.bat`**).
It compiles the Java classes with the MongoDB driver in `lib/`, launches the server, and opens **`http://localhost:8000`** in your browser.

### Method 2: Command Line (Java)
```bash
javac -cp "lib/*;." MongoManager.java StudentHttpServer.java
java -cp "lib/*;." StudentHttpServer
```

### Method 3: Command Line (Python)
```bash
py server.py
```

---

## 📡 REST API Endpoints Reference

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/api/health` | Health check & engine status |
| `GET` | `/api/db/status` | Current MongoDB Atlas connection status & doc count |
| `POST` | `/api/db/config` | Update Atlas username/URI & test connection |
| `GET` | `/api/db/documents` | Retrieve raw documents directly from MongoDB Atlas |
| `POST` | `/api/db/sync` | Trigger two-way sync between MongoDB & local file |
| `GET` | `/api/students` | List all students (supports search & filter) |
| `GET` | `/api/students/<id>` | Fetch single student record |
| `POST` | `/api/students` | Add student (syncs to MongoDB + students.txt) |
| `PUT` | `/api/students/<id>` | Update student (syncs to MongoDB + students.txt) |
| `DELETE` | `/api/students/<id>` | Delete student (syncs to MongoDB + students.txt) |
| `GET` | `/api/stats` | Analytics (total, average, highest, pass rate) |

---

## 📂 Project Structure

```
student management/
├── lib/                              # MongoDB Official Java Driver JARs
│   ├── mongodb-driver-sync-5.1.4.jar
│   ├── mongodb-driver-core-5.1.4.jar
│   └── bson-5.1.4.jar
├── public/
│   ├── index.html                    # Frontend UI with MongoDB controls
│   ├── style.css                     # Modern UI styles & DB badges
│   └── app.js                        # Client logic & MongoDB sync connector
├── MongoManager.java                 # Java MongoDB Atlas driver & sync engine
├── StudentHttpServer.java            # Main Java HTTP Web & API Server
├── server.py                         # Python alternative with pymongo support
├── db_config.json                    # MongoDB database configuration & URI
├── students.txt                      # Local persistent data file
├── start.bat                         # 1-Click launcher
├── run-java.bat                      # Dedicated Java launcher
└── README.md                         # Project documentation
```
