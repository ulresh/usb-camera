package com.github.ulresh.usb_camera;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/** HTTP-сервер: каждому подключившемуся клиенту отдаёт непрерывный поток MPEG-TS. */
final class HttpStreamServer {
    interface Listener {
        void onClientsChanged(int count);
    }

    private static final int QUEUE_LIMIT = 300;

    private final int port;
    private final Listener listener;
    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private ServerSocket server;
    private volatile boolean running;

    HttpStreamServer(int port, Listener listener) {
        this.port = port;
        this.listener = listener;
    }

    void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        running = true;
        Thread t = new Thread(this::acceptLoop, "http-accept");
        t.start();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                new Client(s).start();
            } catch (IOException e) {
                if (running) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ex) {
                        return;
                    }
                }
            }
        }
    }

    void broadcast(byte[] data, boolean key) {
        for (Client c : clients) c.offer(data, key);
    }

    void stop() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
        }
        for (Client c : clients) c.close();
    }

    static List<String> localAddresses() {
        List<String> res = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) res.add(a.getHostAddress());
                }
            }
        } catch (IOException ignored) {
        }
        return res;
    }

    private final class Client extends Thread {
        private final Socket socket;
        private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private volatile boolean waitKey = true;

        Client(Socket socket) {
            super("http-client");
            this.socket = socket;
        }

        void offer(byte[] data, boolean key) {
            if (waitKey) {
                if (!key) return;
                waitKey = false;
            }
            if (queue.size() > QUEUE_LIMIT) {
                // клиент не успевает: сбрасываем накопленное и ждём ближайший ключевой кадр
                queue.clear();
                waitKey = true;
                return;
            }
            queue.add(data);
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void run() {
            try {
                socket.setTcpNoDelay(true);
                socket.setSoTimeout(5000);
                BufferedReader r = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                String line;
                while ((line = r.readLine()) != null && !line.isEmpty()) {
                    // заголовки запроса игнорируем
                }
                OutputStream out = socket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: video/MP2T\r\n"
                        + "Cache-Control: no-cache\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
                clients.add(this);
                listener.onClientsChanged(clients.size());
                while (running) {
                    byte[] d = queue.take();
                    out.write(d);
                }
            } catch (IOException | InterruptedException ignored) {
            } finally {
                clients.remove(this);
                close();
                if (running) listener.onClientsChanged(clients.size());
            }
        }
    }
}
