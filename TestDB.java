import java.sql.*;

public class TestDB {
    public static void main(String[] args) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5432/codesentry", "postgres", "postgres");
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM pr_findings WHERE review_id = 5");
        ResultSetMetaData md = rs.getMetaData();
        while (rs.next()) {
            for (int i=1; i<=md.getColumnCount(); i++) {
                System.out.println(md.getColumnName(i) + ": " + rs.getString(i));
            }
        }
    }
}
