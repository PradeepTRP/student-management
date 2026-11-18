import java.util.*;
import java.io.*;

public class StudentManagement {
    static Scanner sc = new Scanner(System.in);
    static ArrayList<Student> students = new ArrayList<>();
    static final String FILE = "students.txt";

    static class Student {
        int id;
        String name, dept;
        double marks;

        Student(int id, String name, String dept, double marks) {
            this.id = id;
            this.name = name;
            this.dept = dept;
            this.marks = marks;
        }
    }

    static void load() {
        try (Scanner file = new Scanner(new File(FILE))) {
            while (file.hasNextLine()) {
                String[] a = file.nextLine().split(",", -1);
                if (a.length == 4)
                    students.add(new Student(Integer.parseInt(a[0]),
                        a[1], a[2], Double.parseDouble(a[3])));
            }
        } catch (Exception e) { }
    }

    static void save() {
        try (PrintWriter pw = new PrintWriter(FILE)) {
            for (Student s : students)
                pw.println(s.id + "," + s.name + "," +
                           s.dept + "," + s.marks);
        } catch (Exception e) {
            System.out.println("Save error!");
        }
    }

    static Student find(int id) {
        for (Student s : students)
            if (s.id == id) return s;
        return null;
    }

    static void add() {
        System.out.print("Enter ID: ");
        int id = Integer.parseInt(sc.nextLine());
        if (find(id) != null) {
            System.out.println("ID already exists!");
            return;
        }
        System.out.print("Enter Name: ");
        String name = sc.nextLine();
        System.out.print("Enter Department: ");
        String dept = sc.nextLine();
        System.out.print("Enter Marks: ");
        double marks = Double.parseDouble(sc.nextLine());

        if (marks < 0 || marks > 100) {
            System.out.println("Invalid marks!");
            return;
        }
        students.add(new Student(id, name, dept, marks));
        save();
        System.out.println("Student added successfully!");
    }

    static void view() {
        if (students.isEmpty()) {
            System.out.println("No records found!");
            return;
        }
        System.out.println("ID\tName\tDepartment\tMarks");
        for (Student s : students)
            System.out.println(s.id + "\t" + s.name + "\t" +
                               s.dept + "\t\t" + s.marks);
    }

    static void search() {
        System.out.print("Enter ID: ");
        Student s = find(Integer.parseInt(sc.nextLine()));
        if (s == null) System.out.println("Student not found!");
        else System.out.println(s.id + " " + s.name + " " +
                                s.dept + " " + s.marks);
    }

    static void update() {
        System.out.print("Enter ID to update: ");
        Student s = find(Integer.parseInt(sc.nextLine()));
        if (s == null) {
            System.out.println("Student not found!");
            return;
        }
        System.out.print("New Name: ");
        s.name = sc.nextLine();
        System.out.print("New Department: ");
        s.dept = sc.nextLine();
        System.out.print("New Marks: ");
        double m = Double.parseDouble(sc.nextLine());
        if (m < 0 || m > 100) {
            System.out.println("Invalid marks!");
            return;
        }
        s.marks = m;
        save();
        System.out.println("Updated successfully!");
    }

    static void delete() {
        System.out.print("Enter ID to delete: ");
        Student s = find(Integer.parseInt(sc.nextLine()));
        if (s != null) {
            students.remove(s);
            save();
            System.out.println("Deleted successfully!");
        } else System.out.println("Student not found!");
    }

    public static void main(String[] args) {
        load();
        while (true) {
            System.out.println("\n=== STUDENT MANAGEMENT SYSTEM ===");
            System.out.println("1. Add Student");
            System.out.println("2. View Students");
            System.out.println("3. Search Student");
            System.out.println("4. Update Student");
            System.out.println("5. Delete Student");
            System.out.println("6. Exit");
            System.out.print("Enter choice: ");

            try {
                int ch = Integer.parseInt(sc.nextLine());
                switch (ch) {
                    case 1: add(); break;
                    case 2: view(); break;
                    case 3: search(); break;
                    case 4: update(); break;
                    case 5: delete(); break;
                    case 6: return;
                    default: System.out.println("Invalid choice!");
                }
            } catch (NumberFormatException e) {
                System.out.println("Enter valid numbers!");
            }
        }
    }
}