#!/usr/bin/env python3
"""
Student Management System - Backend Server (Python + MongoDB Atlas)
Provides a RESTful API and serves the frontend dashboard.
Data is synchronized in real-time with MongoDB Atlas and persisted in 'students.txt'.
"""

import os
import sys
import json
import mimetypes
import threading
from urllib.parse import urlparse, parse_qs
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn

# Optional MongoDB support via pymongo
try:
    import pymongo
    HAVE_PYMONGO = True
except ImportError:
    HAVE_PYMONGO = False

# Ensure UTF-8 output on Windows consoles
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

PORT = 8000
DATA_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "students.txt")
CONFIG_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "db_config.json")
PUBLIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "public")
file_lock = threading.Lock()

# MongoDB Global State
mongo_client = None
mongo_db = None
mongo_collection = None
mongo_connected = False
mongo_status_msg = "Initializing..."
mongo_config = {
    "mongodb_uri": "mongodb+srv://<db_username>:WQAMVP3SHpCwwuVy@cluster0.xyt8qur.mongodb.net/?retryWrites=true&w=majority",
    "db_username": "",
    "db_name": "student_management_db",
    "collection_name": "students"
}


def load_db_config():
    global mongo_config
    if os.path.isfile(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r", encoding="utf-8") as f:
                mongo_config.update(json.load(f))
        except Exception as e:
            print(f"[MongoDB] Could not read db_config.json: {e}")


def save_db_config():
    try:
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            json.dump(mongo_config, f, indent=2)
    except Exception as e:
        print(f"[MongoDB] Could not save db_config.json: {e}")


def connect_mongo():
    global mongo_client, mongo_db, mongo_collection, mongo_connected, mongo_status_msg
    if not HAVE_PYMONGO:
        mongo_connected = False
        mongo_status_msg = "pymongo not installed"
        return False

    load_db_config()
    raw_uri = mongo_config.get("mongodb_uri", "")
    username = mongo_config.get("db_username", "").strip()
    db_name = mongo_config.get("db_name", "student_management_db")
    coll_name = mongo_config.get("collection_name", "students")

    if not raw_uri:
        mongo_connected = False
        mongo_status_msg = "MongoDB URI not provided"
        return False

    effective_uri = raw_uri
    if "<db_username>" in effective_uri:
        if username:
            effective_uri = effective_uri.replace("<db_username>", username)
        else:
            mongo_connected = False
            mongo_status_msg = "MongoDB username required. Enter username in Settings."
            print(f"[MongoDB] {mongo_status_msg}")
            return False

    try:
        if mongo_client:
            try:
                mongo_client.close()
            except Exception:
                pass
        mongo_client = pymongo.MongoClient(effective_uri, serverSelectionTimeoutMS=3500)
        mongo_client.admin.command("ping")
        mongo_db = mongo_client[db_name]
        mongo_collection = mongo_db[coll_name]
        mongo_connected = True
        mongo_status_msg = f"Connected to MongoDB Atlas ({db_name}.{coll_name})"
        print(f"[MongoDB] 🟢 {mongo_status_msg}")
        return True
    except Exception as e:
        mongo_connected = False
        mongo_status_msg = f"Connection failed: {e}"
        print(f"[MongoDB] 🔴 {mongo_status_msg}")
        return False


def mongo_upsert(student):
    if not mongo_connected or mongo_collection is None:
        return False
    try:
        mongo_collection.replace_one(
            {"id": student["id"]},
            {
                "id": student["id"],
                "name": student["name"],
                "dept": student["dept"],
                "marks": student["marks"]
            },
            upsert=True
        )
        return True
    except Exception as e:
        print(f"[MongoDB] Upsert error: {e}")
        return False


def mongo_delete(student_id):
    if not mongo_connected or mongo_collection is None:
        return False
    try:
        mongo_collection.delete_one({"id": student_id})
        return True
    except Exception as e:
        print(f"[MongoDB] Delete error: {e}")
        return False


def mongo_sync_two_way():
    """Sync between MongoDB Atlas and students.txt."""
    if not mongo_connected or mongo_collection is None:
        return 0
    try:
        local_students = load_students()
        count = mongo_collection.count_documents({})
        synced = 0
        if count > 0:
            local_map = {s["id"]: s for s in local_students}
            for doc in mongo_collection.find():
                s_id = doc.get("id")
                if s_id is not None:
                    try:
                        s_id = int(s_id)
                        name = str(doc.get("name", ""))
                        dept = str(doc.get("dept", ""))
                        marks = float(doc.get("marks", 0))
                        if s_id not in local_map:
                            local_students.append({"id": s_id, "name": name, "dept": dept, "marks": marks})
                            synced += 1
                        else:
                            local_map[s_id]["name"] = name
                            local_map[s_id]["dept"] = dept
                            local_map[s_id]["marks"] = marks
                    except Exception:
                        pass
            save_students(local_students)
        else:
            for s in local_students:
                mongo_upsert(s)
                synced += 1
        return synced
    except Exception as e:
        print(f"[MongoDB] Sync error: {e}")
        return 0


class ThreadedHTTPServer(ThreadingMixIn, HTTPServer):
    """Handle requests in a separate thread."""
    daemon_threads = True


def load_students():
    """Load students from students.txt (format: id,name,dept,marks)."""
    students = []
    if not os.path.exists(DATA_FILE):
        return students

    with file_lock:
        try:
            with open(DATA_FILE, "r", encoding="utf-8") as f:
                for line in f:
                    line = line.strip()
                    if not line:
                        continue
                    parts = line.split(",")
                    if len(parts) >= 4:
                        try:
                            s_id = int(parts[0].strip())
                            name = parts[1].strip()
                            dept = parts[2].strip()
                            marks = float(parts[3].strip())
                            students.append({
                                "id": s_id,
                                "name": name,
                                "dept": dept,
                                "marks": marks
                            })
                        except (ValueError, IndexError):
                            continue
        except Exception as e:
            print(f"[Error] Failed to read {DATA_FILE}: {e}")
    return students


def save_students(students):
    """Save students list to students.txt."""
    with file_lock:
        try:
            with open(DATA_FILE, "w", encoding="utf-8") as f:
                for s in students:
                    f.write(f"{s['id']},{s['name']},{s['dept']},{s['marks']}\n")
            return True
        except Exception as e:
            print(f"[Error] Failed to write {DATA_FILE}: {e}")
            return False


def calculate_stats(students):
    """Compute aggregate analytics for the dashboard."""
    total = len(students)
    if total == 0:
        return {
            "total": 0,
            "average": 0.0,
            "highest": 0.0,
            "lowest": 0.0,
            "passed": 0,
            "failed": 0,
            "pass_rate": 0.0,
            "departments": {}
        }

    marks = [s["marks"] for s in students]
    passed = sum(1 for m in marks if m >= 50.0)
    failed = total - passed
    avg = sum(marks) / total
    highest = max(marks)
    lowest = min(marks)

    dept_counts = {}
    for s in students:
        d = s.get("dept", "Unknown")
        dept_counts[d] = dept_counts.get(d, 0) + 1

    return {
        "total": total,
        "average": round(avg, 2),
        "highest": round(highest, 2),
        "lowest": round(lowest, 2),
        "passed": passed,
        "failed": failed,
        "pass_rate": round((passed / total) * 100, 1),
        "departments": dept_counts
    }


class RequestHandler(BaseHTTPRequestHandler):
    def send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, Accept, X-Requested-With")

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_cors_headers()
        self.end_headers()

    def send_json(self, status_code, data):
        body = json.dumps(data, ensure_ascii=False, indent=2).encode("utf-8")
        self.send_response(status_code)
        self.send_cors_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def serve_static(self, path):
        if path == "/" or path == "":
            path = "/index.html"

        clean_path = os.path.normpath(path.lstrip("/"))
        file_path = os.path.join(PUBLIC_DIR, clean_path)

        if not os.path.isfile(file_path):
            file_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), clean_path)

        if not os.path.isfile(file_path):
            self.send_response(404)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"404 Not Found")
            return

        ext = os.path.splitext(file_path)[1].lower()
        mime_map = {
            ".html": "text/html; charset=utf-8",
            ".css": "text/css; charset=utf-8",
            ".js": "application/javascript; charset=utf-8",
            ".json": "application/json; charset=utf-8",
            ".svg": "image/svg+xml",
            ".png": "image/png",
            ".jpg": "image/jpeg",
            ".ico": "image/x-icon",
            ".txt": "text/plain; charset=utf-8"
        }
        mime_type = mime_map.get(ext) or mimetypes.guess_type(file_path)[0] or "application/octet-stream"

        try:
            with open(file_path, "rb") as f:
                content = f.read()
            self.send_response(200)
            self.send_cors_headers()
            self.send_header("Content-Type", mime_type)
            self.send_header("Content-Length", str(len(content)))
            self.end_headers()
            self.wfile.write(content)
        except Exception as e:
            self.send_response(500)
            self.end_headers()
            self.wfile.write(f"500 Internal Server Error: {e}".encode("utf-8"))

    def get_parsed_body(self):
        content_len = int(self.headers.get("Content-Length", 0))
        if content_len == 0:
            return {}
        post_data = self.rfile.read(content_len).decode("utf-8")
        try:
            return json.loads(post_data)
        except json.JSONDecodeError:
            return None

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")
        query = parse_qs(parsed.query)

        # Health check
        if path == "/api/health":
            self.send_json(200, {
                "status": "ok",
                "engine": "Python 3.14",
                "db_connected": mongo_connected,
                "db_message": mongo_status_msg,
                "message": "Server is running smoothly"
            })
            return

        # MongoDB Status
        if path == "/api/db/status":
            doc_count = 0
            if mongo_connected and mongo_collection is not None:
                try:
                    doc_count = mongo_collection.count_documents({})
                except Exception:
                    pass

            raw_uri = mongo_config.get("mongodb_uri", "")
            masked = raw_uri
            if ":" in masked and "@" in masked:
                parts = masked.split(":")
                end_parts = masked.split("@")
                if len(parts) >= 3:
                    masked = parts[0] + ":" + parts[1] + ":****@" + end_parts[-1]

            self.send_json(200, {
                "success": True,
                "connected": mongo_connected,
                "message": mongo_status_msg,
                "database": mongo_config.get("db_name", "student_management_db"),
                "collection": mongo_config.get("collection_name", "students"),
                "username": mongo_config.get("db_username", ""),
                "has_placeholder": "<db_username>" in raw_uri and not mongo_config.get("db_username"),
                "uri_masked": masked,
                "document_count": doc_count
            })
            return

        # View MongoDB Documents
        if path == "/api/db/documents":
            docs = []
            if mongo_connected and mongo_collection is not None:
                try:
                    for d in mongo_collection.find():
                        d["_id"] = str(d.get("_id", ""))
                        docs.append(d)
                except Exception as e:
                    print(f"[MongoDB] Fetch error: {e}")
            self.send_json(200, {
                "success": True,
                "connected": mongo_connected,
                "count": len(docs),
                "documents": docs
            })
            return

        # Aggregate statistics
        if path == "/api/stats":
            students = load_students()
            stats = calculate_stats(students)
            self.send_json(200, {"success": True, "stats": stats})
            return

        # List all students
        if path == "/api/students":
            students = load_students()
            q = query.get("search", [""])[0].strip().lower()
            dept = query.get("dept", [""])[0].strip().lower()

            filtered = students
            if q:
                filtered = [
                    s for s in filtered
                    if q in str(s["id"]).lower() or q in s["name"].lower() or q in s["dept"].lower()
                ]
            if dept and dept != "all":
                filtered = [s for s in filtered if s["dept"].lower() == dept]

            self.send_json(200, {
                "success": True,
                "count": len(filtered),
                "total": len(students),
                "db_connected": mongo_connected,
                "students": filtered
            })
            return

        # Single student lookup
        if path.startswith("/api/students/"):
            parts = path.split("/")
            if len(parts) == 4 and parts[3].isdigit():
                target_id = int(parts[3])
                students = load_students()
                for s in students:
                    if s["id"] == target_id:
                        self.send_json(200, {"success": True, "student": s})
                        return
                self.send_json(404, {"success": False, "message": f"Student with ID {target_id} not found"})
                return

        # Otherwise serve static files
        self.serve_static(parsed.path)

    def do_POST(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        # Configure DB
        if path == "/api/db/config":
            data = self.get_parsed_body() or {}
            username = data.get("username", "").strip()
            uri = data.get("uri", "").strip()
            db_name = data.get("database", "").strip()
            coll_name = data.get("collection", "").strip()

            if username:
                mongo_config["db_username"] = username
            if uri:
                mongo_config["mongodb_uri"] = uri
            if db_name:
                mongo_config["db_name"] = db_name
            if coll_name:
                mongo_config["collection_name"] = coll_name

            save_db_config()
            ok = connect_mongo()
            if ok:
                mongo_sync_two_way()
                self.send_json(200, {"success": True, "message": "Successfully connected to MongoDB Atlas! Data synchronized."})
            else:
                self.send_json(400, {"success": False, "message": mongo_status_msg})
            return

        # Sync DB
        if path == "/api/db/sync":
            if not mongo_connected:
                self.send_json(400, {"success": False, "message": f"MongoDB not connected. {mongo_status_msg}"})
                return
            synced = mongo_sync_two_way()
            self.send_json(200, {"success": True, "message": f"Synchronization complete ({synced} records synchronized)."})
            return

        # Add Student
        if path == "/api/students":
            data = self.get_parsed_body()
            if data is None:
                self.send_json(400, {"success": False, "message": "Invalid JSON payload"})
                return

            try:
                s_id = int(data.get("id"))
                name = str(data.get("name", "")).strip()
                dept = str(data.get("dept", "")).strip()
                marks = float(data.get("marks"))
            except (ValueError, TypeError):
                self.send_json(400, {"success": False, "message": "Invalid data types for ID or Marks"})
                return

            if not name:
                self.send_json(400, {"success": False, "message": "Student name cannot be empty"})
                return
            if not dept:
                self.send_json(400, {"success": False, "message": "Department cannot be empty"})
                return
            if marks < 0 or marks > 100:
                self.send_json(400, {"success": False, "message": "Marks must be between 0 and 100"})
                return

            students = load_students()
            for s in students:
                if s["id"] == s_id:
                    self.send_json(409, {"success": False, "message": f"Student with ID {s_id} already exists!"})
                    return

            new_student = {
                "id": s_id,
                "name": name,
                "dept": dept,
                "marks": marks
            }
            students.append(new_student)
            if save_students(students):
                mongo_upsert(new_student)
                db_note = " (Saved to MongoDB Atlas)" if mongo_connected else ""
                self.send_json(201, {
                    "success": True,
                    "message": f"Student added successfully!{db_note}",
                    "student": new_student
                })
            else:
                self.send_json(500, {"success": False, "message": "Failed to save student record"})
            return

        self.send_json(404, {"success": False, "message": "Endpoint not found"})

    def do_PUT(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        if path.startswith("/api/students/"):
            parts = path.split("/")
            if len(parts) == 4 and parts[3].isdigit():
                target_id = int(parts[3])
                data = self.get_parsed_body()
                if data is None:
                    self.send_json(400, {"success": False, "message": "Invalid JSON payload"})
                    return

                try:
                    name = str(data.get("name", "")).strip()
                    dept = str(data.get("dept", "")).strip()
                    marks = float(data.get("marks"))
                except (ValueError, TypeError):
                    self.send_json(400, {"success": False, "message": "Invalid input values"})
                    return

                if not name:
                    self.send_json(400, {"success": False, "message": "Name cannot be empty"})
                    return
                if not dept:
                    self.send_json(400, {"success": False, "message": "Department cannot be empty"})
                    return
                if marks < 0 or marks > 100:
                    self.send_json(400, {"success": False, "message": "Marks must be between 0 and 100"})
                    return

                students = load_students()
                found = False
                updated_student = None
                for s in students:
                    if s["id"] == target_id:
                        s["name"] = name
                        s["dept"] = dept
                        s["marks"] = marks
                        updated_student = s
                        found = True
                        break

                if not found:
                    self.send_json(404, {"success": False, "message": f"Student with ID {target_id} not found"})
                    return

                if save_students(students):
                    mongo_upsert(updated_student)
                    db_note = " (Updated in MongoDB Atlas)" if mongo_connected else ""
                    self.send_json(200, {
                        "success": True,
                        "message": f"Student updated successfully!{db_note}",
                        "student": updated_student
                    })
                else:
                    self.send_json(500, {"success": False, "message": "Failed to save student record"})
                return

        self.send_json(404, {"success": False, "message": "Endpoint not found"})

    def do_DELETE(self):
        parsed = urlparse(self.path)
        path = parsed.path.rstrip("/")

        if path.startswith("/api/students/"):
            parts = path.split("/")
            if len(parts) == 4 and parts[3].isdigit():
                target_id = int(parts[3])
                students = load_students()
                initial_count = len(students)
                students = [s for s in students if s["id"] != target_id]

                if len(students) == initial_count:
                    self.send_json(404, {"success": False, "message": f"Student with ID {target_id} not found"})
                    return

                if save_students(students):
                    mongo_delete(target_id)
                    db_note = " (Removed from MongoDB Atlas)" if mongo_connected else ""
                    self.send_json(200, {
                        "success": True,
                        "message": f"Student ID {target_id} deleted successfully!{db_note}"
                    })
                else:
                    self.send_json(500, {"success": False, "message": "Failed to save changes"})
                return

        self.send_json(404, {"success": False, "message": "Endpoint not found"})

    def log_message(self, format, *args):
        sys.stderr.write(f"[{self.log_date_time_string()}] {self.address_string()} - {format % args}\n")


def run_server():
    connect_mongo()
    if mongo_connected:
        mongo_sync_two_way()

    server_address = ("127.0.0.1", PORT)
    httpd = ThreadedHTTPServer(server_address, RequestHandler)
    print("=" * 60)
    print(" [ONLINE] STUDENT MANAGEMENT SYSTEM - WEB SERVER")
    print("=" * 60)
    print(f" * Server running on: http://localhost:{PORT}")
    print(f" * Connected API:     http://localhost:{PORT}/api/students")
    print(f" * Database Status:   {mongo_status_msg}")
    print(f" * Data File:         {DATA_FILE}")
    print(f" * Web Directory:     {PUBLIC_DIR}")
    print("=" * 60)
    print(" Press Ctrl+C in terminal to stop the server.\n")

    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[Server] Shutting down...")
        httpd.server_close()


if __name__ == "__main__":
    run_server()
