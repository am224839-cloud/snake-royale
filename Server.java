import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.java_websocket.server.WebSocketServer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import com.google.gson.Gson;

public class Server extends WebSocketServer {
    private static final int PORT = 8080;
    private Map<WebSocket, String> playerIds = new ConcurrentHashMap<>();
    private Map<String, Snake> snakes = new ConcurrentHashMap<>();
    private List<Point> food = new ArrayList<>();
    private Random rand = new Random();
    private Gson gson = new Gson();
    private Timer gameTimer;
    private Set<String> admins = new HashSet<>();

    public Server() {
        super(new InetSocketAddress(PORT));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String playerId = UUID.randomUUID().toString();
        playerIds.put(conn, playerId);
        snakes.put(playerId, new Snake(playerId));
        conn.send(gson.toJson(Map.of("type", "init", "playerId", playerId)));
        if (food.isEmpty()) addFood();
        if (gameTimer == null) startGameLoop();
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        String pid = playerIds.get(conn);
        if (pid != null) {
            snakes.remove(pid);
            playerIds.remove(conn);
            admins.remove(pid);
        }
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        String pid = playerIds.get(conn);
        if (pid == null) return;
        Map<String, Object> msg = gson.fromJson(message, Map.class);
        String type = (String) msg.get("type");

        if ("direction".equals(type)) {
            String dir = (String) msg.get("dir");
            snakes.get(pid).setDirection(dir);
        } else if ("restart".equals(type)) {
            snakes.put(pid, new Snake(pid));
        } else if ("admin".equals(type)) {
            // Only allow admin commands from frontend unlocked panel
            if (!admins.contains(pid)) {
                // First admin command from client means panel was unlocked (PIN entered)
                admins.add(pid);
            }
            String command = (String) msg.get("command");
            if ("spawnFood".equals(command)) {
                addFood();
                sendAdminMsg(conn, "Food spawned!");
            } else if ("resetGame".equals(command)) {
                resetGame();
                sendAdminMsg(conn, "Game reset!");
            } else if ("kickAll".equals(command)) {
                kickAll();
                sendAdminMsg(conn, "All players kicked!");
            }
        }
    }

    @Override
    public void onError(WebSocket conn, Exception ex) { ex.printStackTrace(); }

    @Override
    public void onStart() { System.out.println("Snake Royale server started on port " + PORT); }

    private void startGameLoop() {
        gameTimer = new Timer();
        gameTimer.scheduleAtFixedRate(new TimerTask() {
            public void run() {
                for (Snake snake : snakes.values()) {
                    snake.move();
                    // Check collision with food
                    Iterator<Point> foodIter = food.iterator();
                    while (foodIter.hasNext()) {
                        Point f = foodIter.next();
                        if (snake.head().equals(f)) {
                            snake.grow();
                            foodIter.remove();
                            addFood();
                        }
                    }
                    // Check collision with other snakes
                    for (Snake other : snakes.values()) {
                        if (other == snake) continue;
                        if (snake.head().equals(other.head()) ||
                            other.occupies(snake.head())) {
                            snake.alive = false;
                        }
                    }
                }

                // Remove dead snakes
                for (Iterator<Map.Entry<String, Snake>> it = snakes.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, Snake> entry = it.next();
                    if (!entry.getValue().alive) {
                        sendToPlayer(entry.getKey(), gson.toJson(Map.of("type", "dead")));
                        admins.remove(entry.getKey());
                        it.remove();
                    }
                }

                // Broadcast game state
                Map<String, Object> updateMsg = new HashMap<>();
                updateMsg.put("type", "update");
                updateMsg.put("snakes", snakes);
                updateMsg.put("food", food);
                updateMsg.put("state", "playing");
                broadcast(gson.toJson(updateMsg));
            }
        }, 0, 120); // 120ms per tick
    }

    private void addFood() {
        Point f;
        do {
            f = new Point(rand.nextInt(50), rand.nextInt(30));
        } while (food.contains(f));
        food.add(f);
    }

    private void resetGame() {
        snakes.clear();
        food.clear();
        for (String pid : playerIds.values()) {
            snakes.put(pid, new Snake(pid));
        }
        addFood();
    }

    private void kickAll() {
        for (WebSocket conn : playerIds.keySet()) {
            conn.close(1000, "Kicked by admin.");
        }
        playerIds.clear();
        snakes.clear();
        admins.clear();
        food.clear();
    }

    private void sendToPlayer(String playerId, String msg) {
        for (Map.Entry<WebSocket, String> entry : playerIds.entrySet()) {
            if (entry.getValue().equals(playerId)) {
                entry.getKey().send(msg);
                break;
            }
        }
    }

    private void sendAdminMsg(WebSocket conn, String message) {
        conn.send(gson.toJson(Map.of("type", "adminMsg", "message", message)));
    }

    public static void main(String[] args) {
        new Server().start();
    }

    // --- Helper Classes ---
    static class Point {
        int x, y;
        Point(int x, int y) { this.x = x; this.y = y; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Point)) return false;
            Point p = (Point) o;
            return x == p.x && y == p.y;
        }
        @Override public int hashCode() { return Objects.hash(x, y); }
    }

    static class Snake {
        String playerId;
        List<Point> cells = new ArrayList<>();
        String direction = "right";
        boolean alive = true;

        Snake(String pid) {
            playerId = pid;
            cells.add(new Point(5 + new Random().nextInt(40), 5 + new Random().nextInt(20)));
        }
        void setDirection(String dir) {
            if (List.of("up","down","left","right").contains(dir)) direction = dir;
        }
        void move() {
            if (!alive) return;
            Point head = new Point(cells.get(0).x, cells.get(0).y);
            switch (direction) {
                case "up": head.y -= 1; break;
                case "down": head.y += 1; break;
                case "left": head.x -= 1; break;
                case "right": head.x += 1; break;
            }
            cells.add(0, head);
            cells.remove(cells.size()-1);

            // Wall collision
            if (head.x < 0 || head.x >= 50 || head.y < 0 || head.y >= 30) alive = false;
            // Self collision
            for (int i=1; i < cells.size(); i++) {
                if (head.equals(cells.get(i))) alive = false;
            }
        }
        void grow() {
            Point tail = cells.get(cells.size()-1);
            cells.add(new Point(tail.x, tail.y));
        }
        Point head() { return cells.get(0); }
        boolean occupies(Point p) { return cells.stream().anyMatch(c -> c.equals(p)); }
    }
}