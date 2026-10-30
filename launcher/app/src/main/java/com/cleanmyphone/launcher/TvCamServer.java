package com.cleanmyphone.launcher;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class TvCamServer {
    private static final String TAG = "TvCamServer";
    private final int port;
    private ServerSocket serverSocket;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger activeClients = new AtomicInteger(0);

    private byte[] latestJpeg = null;
    private final Object frameLock = new Object();

    public interface StatusListener {
        void onClientCountChanged(int count);
    }

    private StatusListener listener;

    public TvCamServer(int port) {
        this.port = port;
    }

    public void setListener(StatusListener listener) {
        this.listener = listener;
    }

    public void start() throws IOException {
        if (isRunning.get()) return;
        serverSocket = new ServerSocket(port);
        isRunning.set(true);

        new Thread(() -> {
            while (isRunning.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> handleConnection(socket)).start();
                } catch (IOException e) {
                    if (!isRunning.get()) break;
                }
            }
        }, "TvCam-Acceptor").start();
    }

    public void stop() {
        isRunning.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
    }

    public void pushFrame(byte[] jpegData) {
        synchronized (frameLock) {
            latestJpeg = jpegData;
            frameLock.notifyAll();
        }
    }

    private void handleConnection(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                socket.close();
                return;
            }

            String path = "/";
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2) {
                path = parts[1];
            }

            OutputStream os = new BufferedOutputStream(socket.getOutputStream());

            if (path.startsWith("/video")) {
                serveMjpeg(os);
            } else {
                serveHtml(os);
            }
        } catch (Exception e) {
            Log.e(TAG, "Connection error: " + e.getMessage());
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void serveHtml(OutputStream os) throws IOException {
        String html = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "  <meta charset=\"utf-8\">\n" +
                "  <title>Clean Phone - TV Camera</title>\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "  <style>\n" +
                "    body, html { margin: 0; padding: 0; width: 100%; height: 100%; background: #000; overflow: hidden; display: flex; align-items: center; justify-content: center; font-family: sans-serif; }\n" +
                "    #stream { width: 100%; height: 100%; object-fit: contain; }\n" +
                "    #hud { position: absolute; top: 20px; left: 24px; color: #00E676; font-size: 15px; background: rgba(18,22,34,0.85); padding: 8px 18px; border-radius: 20px; border: 1px solid rgba(0,230,118,0.4); }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <img id=\"stream\" src=\"/video\">\n" +
                "  <div id=\"hud\">🟢 TV Camera Connected · Low Latency (30 FPS)</div>\n" +
                "  <script>\n" +
                "    const img = document.getElementById('stream');\n" +
                "    img.onerror = () => setTimeout(() => img.src = '/video?t=' + Date.now(), 1000);\n" +
                "  </script>\n" +
                "</body>\n" +
                "</html>";

        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Connection: close\r\n\r\n";

        os.write(header.getBytes(StandardCharsets.US_ASCII));
        os.write(bytes);
        os.flush();
    }

    private void serveMjpeg(OutputStream os) throws IOException {
        String boundary = "frame";
        String header = "HTTP/1.0 200 OK\r\n" +
                "Server: CleanMyPhone-TvCam\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Expires: 0\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=--" + boundary + "\r\n\r\n";

        os.write(header.getBytes(StandardCharsets.US_ASCII));
        os.flush();

        int count = activeClients.incrementAndGet();
        if (listener != null) listener.onClientCountChanged(count);

        try {
            while (isRunning.get()) {
                byte[] frame;
                synchronized (frameLock) {
                    frameLock.wait(500);
                    frame = latestJpeg;
                }

                if (frame != null) {
                    StringBuilder part = new StringBuilder();
                    part.append("--").append(boundary).append("\r\n");
                    part.append("Content-Type: image/jpeg\r\n");
                    part.append("Content-Length: ").append(frame.length).append("\r\n\r\n");

                    os.write(part.toString().getBytes(StandardCharsets.US_ASCII));
                    os.write(frame);
                    os.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    os.flush();
                }
            }
        } catch (Exception ignored) {
        } finally {
            count = activeClients.decrementAndGet();
            if (listener != null) listener.onClientCountChanged(count);
        }
    }
}
