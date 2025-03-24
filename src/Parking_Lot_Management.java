import java.sql.*;
import java.util.Scanner;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class DatabaseConnection {
    private static final String URL = "jdbc:mysql://localhost:3306/PARKING_LOT_MANAGEMENT";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "root";

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USERNAME, PASSWORD);
    }

    public static void closeConnection(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
                System.out.println("Connection closed.");
            } catch (SQLException e) {
                System.err.println("Failed to close the database connection: " + e.getMessage());
            }
        }
    }
}

class UniqueIdGenerator {
    public static String generateUniqueId() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMddHHmm");
        String dateTime = dateFormat.format(new Date());
        return dateTime;
    }
}

class ParkingLotManager {
    private static final Lock lock = new ReentrantLock();
    private static int nextSpotId = 1; 

    public static void parkVehicle(Connection connection, String vehicleNumber, String vehicleCategory, String ownerDetails) {
        String uniqueId = UniqueIdGenerator.generateUniqueId();
        int parkingSpotId = getNextSpotId();
        String sql = "INSERT INTO vehicles (vehicle_number, vehicle_category, owner_details, parking_spot_id, unique_id, entry_timestamp) VALUES (?, ?, ?, ?, ?, NOW())";
        try {
            lock.lock(); 
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, vehicleNumber);
                statement.setString(2, vehicleCategory);
                statement.setString(3, ownerDetails);
                statement.setInt(4, parkingSpotId);
                statement.setString(5, uniqueId);
                int rowsInserted = statement.executeUpdate();
                if (rowsInserted > 0) {
                    System.out.println("Vehicle parked successfully. Unique ID: " + uniqueId);
                    System.out.println("Retrieved Spot ID: " + parkingSpotId);
                    updateParkingSpotAvailability(connection, parkingSpotId, true);
                    addTransaction(connection, vehicleNumber, parkingSpotId);
                } else {
                    System.err.println("Failed to park the vehicle.");
                }
            }
        } catch (SQLException e) {
            System.err.println("Error parking vehicle: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private static int getNextSpotId() {
        return nextSpotId++; 
    }

    private static void updateParkingSpotAvailability(Connection connection, int spotId, boolean availability) {
        String sql = "UPDATE parking_spots SET availability_status = ? WHERE spot_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setBoolean(1, availability);
            statement.setInt(2, spotId);
            int rowsUpdated = statement.executeUpdate();
            if (rowsUpdated > 0) {
                System.out.println("Parking spot availability updated successfully.");
            } else {
                System.err.println("Failed to update parking spot availability.");
            }
        } catch (SQLException e) {
            System.err.println("Error updating parking spot availability: " + e.getMessage());
        }
    }

    public static void unparkVehicle(Connection connection, String uniqueId) {
        String sql = "SELECT TIMESTAMPDIFF(HOUR, entry_timestamp, NOW()) AS parking_duration, vehicle_category FROM vehicles WHERE unique_id = ?";
        try {
            lock.lock();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uniqueId);
                ResultSet resultSet = statement.executeQuery();
                if (resultSet.next()) {
                    int parkingDuration = resultSet.getInt("parking_duration");
                    String vehicleCategory = resultSet.getString("vehicle_category");
                    double parkingFee = calculateFee(parkingDuration, vehicleCategory);
                    System.out.println("Parking fee for vehicle with unique ID " + uniqueId + " is: RS. " + parkingFee);

                    int spotId = getSpotId(connection, uniqueId);
                    updateParkingSpotAvailability(connection, spotId, false);
                    updateTransaction(connection, uniqueId);
                    markVehicleAsUnparked(connection, uniqueId);

                } else {
                    System.err.println("Failed to retrieve parking duration for the vehicle with unique ID: " + uniqueId);
                }
            }
        } catch (SQLException e) {
            System.err.println("Error unparking vehicle: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    private static void markVehicleAsUnparked(Connection connection, String uniqueId) {
        String sql = "UPDATE vehicles SET parking_spot_id = NULL WHERE unique_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uniqueId);
            int rowsUpdated = statement.executeUpdate();
            if (rowsUpdated > 0) {
                System.out.println("Vehicle with unique ID " + uniqueId + " has been marked as unparked.");
            } else {
                System.err.println("Failed to mark vehicle as unparked.");
            }
        } catch (SQLException e) {
            System.err.println("Error marking vehicle as unparked: " + e.getMessage());
        }
    }

    private static double calculateFee(int parkingDuration, String vehicleCategory) {
        double ratePerHour;
        if (vehicleCategory.equalsIgnoreCase("Truck")) {
            ratePerHour = 20.0; 
        } else if (vehicleCategory.equalsIgnoreCase("Car")) {
            ratePerHour = 15.0; 
        } else {
            ratePerHour = 10.0; 
        }
        return parkingDuration * ratePerHour;
    }

    private static int getSpotId(Connection connection, String uniqueId) throws SQLException {
        String sql = "SELECT parking_spot_id FROM vehicles WHERE unique_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uniqueId);
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                return resultSet.getInt("parking_spot_id");
            } else {
                throw new SQLException("Spot ID not found for unique ID: " + uniqueId);
            }
        }
    }

    private static int getNextTransactionId(Connection connection) throws SQLException {
        String sql = "SELECT MAX(transaction_id) AS max_id FROM transactions";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            ResultSet resultSet = statement.executeQuery();
            if (resultSet.next()) {
                int maxId = resultSet.getInt("max_id");
                return maxId + 1; 
            } else {
                return 1;
            }
        }
    }

    private static void addTransaction(Connection connection, String vehicleNumber, int parkingSpotId) {
        String sql = "INSERT INTO transactions (transaction_id, vehicle_number, parking_spot_id) VALUES (?, ?, ?)";
        try {
            int nextTransactionId = getNextTransactionId(connection);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, nextTransactionId); 
                statement.setString(2, vehicleNumber);
                statement.setInt(3, parkingSpotId);
                int rowsInserted = statement.executeUpdate();
                if (rowsInserted > 0) {
                    System.out.println("Transaction added successfully. Transaction ID: " + nextTransactionId);
                } else {
                    System.err.println("Failed to add transaction.");
                }
            }
        } catch (SQLException e) {
            System.err.println("Error adding transaction: " + e.getMessage());
        }
    }
    
    private static void updateTransaction(Connection connection, String uniqueId) {
        String sql = "UPDATE transactions SET exit_timestamp = NOW(), payment_status = true WHERE vehicle_number IN (SELECT vehicle_number FROM vehicles WHERE unique_id = ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, uniqueId);
            int rowsUpdated = statement.executeUpdate();
            if (rowsUpdated > 0) {
                System.out.println("Transaction updated successfully.");
            } else {
                System.err.println("Failed to update transaction.");
            }
        } catch (SQLException e) {
            System.err.println("Error updating transaction: " + e.getMessage());
        }
    }
}

public class Parking_Lot_Management {
    public static void main(String[] args) {
        try (Connection connection = DatabaseConnection.getConnection();
             Scanner scanner = new Scanner(System.in)) {
            boolean exit = false;
            while(!exit) {
                System.out.println("\nParking Lot Management System");
                System.out.println("1. Park a Vehicle");
                System.out.println("2. Unpark a Vehicle");
                System.out.println("3. Exit");
                System.out.print("Enter your choice: ");
                int choice = scanner.nextInt();
                switch (choice) {
                    case 1:
                        parkVehicle(connection, scanner);
                        break;
                    case 2:
                        unparkVehicle(connection, scanner);
                        break;
                    case 3:
                        exit = true;
                        System.out.println("Exiting the program.");
                        break;
                    default:
                        System.out.println("Invalid choice. Please enter a valid option.");
                }
            }
        } catch (SQLException e) {
            System.err.println("SQL Error: " + e.getMessage());
        }
    }

    private static void parkVehicle(Connection connection, Scanner scanner) {
        try {
            System.out.print("Enter vehicle number : ");
            String vehicleNumber = scanner.next();
            scanner.nextLine();
            System.out.print("Enter Vehicle Category (Truck, Car, or Bike) : ");
            String category = scanner.nextLine();
            System.out.print("Enter owner details : ");
            String ownerDetails = scanner.nextLine();
            ParkingLotManager.parkVehicle(connection, vehicleNumber, category, ownerDetails);
        } catch (Exception e) {
            System.err.println("Error parking vehicle: " + e.getMessage());
        }
    }

    private static void unparkVehicle(Connection connection, Scanner scanner) {
        try {
            System.out.print("Enter unique ID: ");
            String uniqueId = scanner.next();
            ParkingLotManager.unparkVehicle(connection, uniqueId);
        } catch (Exception e) {
            System.err.println("Error unparking vehicle: " + e.getMessage());
        }
    }
}
