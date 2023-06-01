import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

class City {
    private int cityId;
    private List<Integer> neighbours;
    private Map<Integer, Coordinates> coordinates;
    private int port;
    private String data;
    private Lock dataLock;
    private Set<Integer> visited;
    private Map<Integer, Double> distance;
    private Lock distanceLock;
    private Condition distanceUpdated;
    private Queue<BFSNode> bfsQueue;

    public City(int cityId, List<Integer> neighbours, Map<Integer, Coordinates> coordinates, int port) {
        this.cityId = cityId;
        this.neighbours = neighbours;
        this.coordinates = coordinates;
        this.port = port;
        this.data = String.valueOf(cityId);
        this.dataLock = new ReentrantLock();
        this.visited = new HashSet<>();
        this.distance = new HashMap<>();
        this.distanceLock = new ReentrantLock();
        this.distanceUpdated = distanceLock.newCondition();
        this.bfsQueue = new LinkedList<>();

        // Initialize distances between cities
        for (int neighbour : neighbours) {
            this.distance.put(neighbour, calculateDistance(cityId, neighbour));
        }
    }

    private double calculateDistance(int city1, int city2) {
        Coordinates coords1 = coordinates.get(city1);
        Coordinates coords2 = coordinates.get(city2);

        double lat1 = coords1.getLatitude();
        double lon1 = coords1.getLongitude();
        double lat2 = coords2.getLatitude();
        double lon2 = coords2.getLongitude();

        // Convert degrees to radians
        double lat1Rad = Math.toRadians(lat1);
        double lon1Rad = Math.toRadians(lon1);
        double lat2Rad = Math.toRadians(lat2);
        double lon2Rad = Math.toRadians(lon2);

        // Haversine formula
        double dlon = lon2Rad - lon1Rad;
        double dlat = lat2Rad - lat1Rad;
        double a = Math.pow(Math.sin(dlat / 2), 2) +
                Math.cos(lat1Rad) * Math.cos(lat2Rad) * Math.pow(Math.sin(dlon / 2), 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        double distance = 6371 * c;  // Radius of the Earth in kilometers

        return distance;
    }

    public void broadcast() {
        for (int neighbour : neighbours) {
            if (neighbour != cityId && !visited.contains(neighbour)) {
                sendData(neighbour);
            }
        }
    }

    private void sendData(int neighbour) {
        for (int n : neighbours) {
            if (n != cityId && !visited.contains(n)) {
                try (Socket socket = new Socket("city_" + n, 8000)) {
                    dataLock.lock();
                    try {
                        if (!visited.contains(n)) {
                            OutputStream outputStream = socket.getOutputStream();
                            outputStream.write(String.valueOf(cityId).getBytes());
                            visited.add(n);
                        }
                    } finally {
                        dataLock.unlock();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleClient(Socket clientSocket, String address) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            String request = reader.readLine();
            dataLock.lock();
            try {
                StringBuilder newData = new StringBuilder(data);
                newData.append(request);
                data = newData.toString().replace("null", "");
                System.out.println("City " + cityId + ": connecting to port " + address);
                System.out.println("City " + cityId + ": add data " + data);
            } finally {
                dataLock.unlock();
            }
            visited.add(cityId);  // Add current city to the visited set

            // Check if all neighbors have been visited
            boolean allNeighborsVisited = true;
            for (int neighbor : neighbours) {
                if (neighbor != cityId && !visited.contains(neighbor)) {
                    allNeighborsVisited = false;
                    break;
                }
            }

            if (allNeighborsVisited) {
                broadcast();

                // Print distance between cities
                System.out.println("City " + cityId + ": Distance to other cities"); // แก้ฟอร์มผลลัพธ์
                for (Map.Entry<Integer, Double> entry : distance.entrySet()) {
                    int neighbor = entry.getKey();
                    double dist = entry.getValue();
                    System.out.println("City " + cityId + " to City " + neighbor + ": " + dist + " km");
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void updateDistance(int neighbour, double distance) {
        distanceLock.lock();
        try {
            double currentDistance = this.distance.get(neighbour);
            if (distance < currentDistance || currentDistance == Double.POSITIVE_INFINITY) {
                this.distance.put(neighbour, distance);
                double updatedDistance = this.distance.get(neighbour); // Store the updated distance
                System.out.println("City " + cityId + ": Updated distance to City " + neighbour + ": " + updatedDistance + " km"); 
                distanceUpdated.signalAll();
            }
        } finally {
            distanceLock.unlock();
        }
    }

    public void bfs() {
        while (true) {
            BFSNode node;
            distanceLock.lock();
            try {
                while (bfsQueue.isEmpty()) {
                    distanceUpdated.await();
                }
                node = bfsQueue.poll();
            } catch (InterruptedException e) {
                e.printStackTrace();
                return;
            } finally {
                distanceLock.unlock();
            }

            int neighbour = node.getNeighbour();
            double distance = node.getDistance();
            if (distance < this.distance.get(neighbour)) {
                updateDistance(neighbour, distance);
                for (int nextNeighbour : neighbours) {
                    if (nextNeighbour != cityId && !visited.contains(nextNeighbour)) {
                        bfsQueue.add(new BFSNode(nextNeighbour, distance + 1));
                    }
                }
            }
        }
    }

    public void runServer() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("City " + cityId + ": Listening"); // แก้ฟอร์มผลลัพธ์ เปลี่ยน listen เป็นอย่างอื่น
            while (true) {
                Socket clientSocket = serverSocket.accept();
                Thread clientHandler = new Thread(() -> handleClient(clientSocket, clientSocket.getInetAddress().getHostAddress()));
                clientHandler.start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void run() {
        Thread serverThread = new Thread(this::runServer);
        serverThread.start();
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        broadcast();

        // Start parallel BFS threads
        List<Thread> bfsThreads = new ArrayList<>();
        for (int neighbour : neighbours) {
            if (neighbour != cityId) {
                Thread thread = new Thread(this::bfs);
                bfsThreads.add(thread);
                thread.start();
            }
        }

        while (true) {
            distanceLock.lock();
            try {
                boolean noMoreUpdates = bfsQueue.isEmpty() && distanceUpdated.await(1, TimeUnit.SECONDS);
                if (noMoreUpdates) {
                    // No more distance updates, exit the loop
                    break;
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
                break;
            } finally {
                distanceLock.unlock();
            }
        }

        // Show distance between cities
        System.out.println("City " + cityId + ": Distance to other cities");
        for (Map.Entry<Integer, Double> entry : distance.entrySet()) {
            int neighbour = entry.getKey();
            double distance = entry.getValue();
            System.out.println("City " + cityId + " - City " + neighbour + ": " + distance + " km");
        }

        // Show final data of the city
        System.out.println("City " + cityId + ": Final data " + data);
    }

    public static void main(String[] args) {
        int cityId = Integer.parseInt(args[0]);
        List<Integer> neighbours = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            neighbours.add(Integer.parseInt(args[i]));
        }
        // ใช้ ืnode เป็น lat, log ของจังหวัด
        Map<Integer, Coordinates> coordinates = new HashMap<>();
        coordinates.put(1, new Coordinates(19.9071656, 99.8309));          // เชียงใหม่
        coordinates.put(2, new Coordinates(18.7877477, 98.9931));          // เชียงราย
        coordinates.put(3, new Coordinates(13.8199206, 100.0621));          // นครปฐม
        coordinates.put(4, new Coordinates(13.72917, 100.52389));          // นครนายก

        City city = new City(cityId, neighbours, coordinates, 8000);
        city.run();
    }
}

class Coordinates {
    private double latitude;
    private double longitude;

    public Coordinates(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double getLatitude() {
        return latitude;
    }

    public double getLongitude() {
        return longitude;
    }
}

class BFSNode {
    private int neighbour;
    private double distance;

    public BFSNode(int neighbour, double distance) {
        this.neighbour = neighbour;
        this.distance = distance;
    }

    public int getNeighbour() {
        return neighbour;
    }

    public double getDistance() {
        return distance;
    }
}
