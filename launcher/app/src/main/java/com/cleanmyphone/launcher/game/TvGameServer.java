package com.cleanmyphone.launcher.game;

import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ultra-low-latency HTTP & MJPEG Game Streamer for TV Web Browsers.
 * 100% local, zero internet dependency. Streams the 60 FPS live game
 * and receives TV remote key commands over Wi-Fi.
 */
public class TvGameServer {
    private static final String TAG = "TvGameServer";
    private final int port;
    private ServerSocket serverSocket;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicInteger activeClients = new AtomicInteger(0);

    private byte[] latestJpeg = null;
    private final Object frameLock = new Object();

    public interface StatusListener {
        void onClientCountChanged(int count);
    }

    public interface GameInputListener {
        void onTvInput(String key);
    }

    private StatusListener statusListener;
    private GameInputListener inputListener;

    public TvGameServer(int port) {
        this.port = port;
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    public void setInputListener(GameInputListener listener) {
        this.inputListener = listener;
    }

    public boolean hasActiveClients() {
        return activeClients.get() > 0;
    }

    public int getActiveClientCount() {
        return activeClients.get();
    }

    public void start() throws IOException {
        if (isRunning.get()) return;
        serverSocket = new ServerSocket(port);
        serverSocket.setReuseAddress(true);
        isRunning.set(true);

        new Thread(() -> {
            while (isRunning.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> handleConnection(socket), "TvGame-Client").start();
                } catch (IOException e) {
                    if (!isRunning.get()) break;
                }
            }
        }, "TvGame-Acceptor").start();

        Log.d(TAG, "TvGameServer started on port " + port);
    }

    public void stop() {
        isRunning.set(false);
        try {
            if (serverSocket != null) {
                serverSocket.close();
                serverSocket = null;
            }
        } catch (IOException ignored) {}
    }

    public void pushFrame(byte[] jpegData) {
        if (jpegData == null || activeClients.get() <= 0) return;
        synchronized (frameLock) {
            latestJpeg = jpegData;
            frameLock.notifyAll();
        }
    }

    private void handleConnection(Socket socket) {
        try {
            socket.setTcpNoDelay(true); // Disable Nagle's algorithm for sub-10ms delivery
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
            } else if (path.startsWith("/input")) {
                handleInput(path, os);
            } else {
                serveHtml(os);
            }
        } catch (Exception ignored) {
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private void handleInput(String path, OutputStream os) throws IOException {
        try {
            int qIdx = path.indexOf("key=");
            if (qIdx != -1) {
                String encodedKey = path.substring(qIdx + 4);
                int ampIdx = encodedKey.indexOf('&');
                if (ampIdx != -1) encodedKey = encodedKey.substring(0, ampIdx);
                String key = URLDecoder.decode(encodedKey, "UTF-8");
                if (inputListener != null) {
                    inputListener.onTvInput(key);
                }
            }
        } catch (Exception ignored) {}

        String response = "HTTP/1.1 204 No Content\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
        os.write(response.getBytes(StandardCharsets.US_ASCII));
        os.flush();
    }

    private void serveHtml(OutputStream os) throws IOException {
        String html = "<!DOCTYPE html>\n" +
                "<html>\n" +
                "<head>\n" +
                "  <meta charset=\"utf-8\">\n" +
                "  <title>Motion Runner - TV Screen</title>\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no\">\n" +
                "  <style>\n" +
                "    * { box-sizing: border-box; }\n" +
                "    html, body {\n" +
                "      margin: 0; padding: 0;\n" +
                "      width: 100vw; height: 100vh;\n" +
                "      background: #090B10;\n" +
                "      overflow: hidden;\n" +
                "      display: flex; align-items: center; justify-content: center;\n" +
                "      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;\n" +
                "    }\n" +
                "    #stream {\n" +
                "      width: 100vw;\n" +
                "      height: 100vh;\n" +
                "      object-fit: contain;\n" +
                "      display: block;\n" +
                "      background: #000;\n" +
                "    }\n" +
                "    #statusBadge {\n" +
                "      position: absolute;\n" +
                "      top: 18px; left: 24px;\n" +
                "      background: rgba(14, 18, 28, 0.90);\n" +
                "      border: 1px solid #00E676;\n" +
                "      color: #00E676;\n" +
                "      padding: 8px 18px;\n" +
                "      border-radius: 20px;\n" +
                "      font-size: 14px;\n" +
                "      font-weight: bold;\n" +
                "      letter-spacing: 0.5px;\n" +
                "      pointer-events: none;\n" +
                "      box-shadow: 0 4px 16px rgba(0,0,0,0.6);\n" +
                "      transition: opacity 1s ease;\n" +
                "    }\n" +
                "    #controlsHelp {\n" +
                "      position: absolute;\n" +
                "      bottom: 16px; left: 50%;\n" +
                "      transform: translateX(-50%);\n" +
                "      background: rgba(14, 18, 28, 0.90);\n" +
                "      border: 1px solid rgba(255,255,255,0.2);\n" +
                "      color: #FFF;\n" +
                "      padding: 8px 22px;\n" +
                "      border-radius: 16px;\n" +
                "      font-size: 13px;\n" +
                "      font-weight: 500;\n" +
                "      pointer-events: none;\n" +
                "      box-shadow: 0 4px 16px rgba(0,0,0,0.6);\n" +
                "    }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <img id=\"stream\" src=\"/video\" alt=\"Live Motion Game\">\n" +
                "  <div id=\"statusBadge\">🟢 TV STREAM CONNECTED · 60 FPS ULTRA-LOW LATENCY</div>\n" +
                "  <div id=\"controlsHelp\">🎮 Body Motion Active via Phone Camera • TV Remote: ◀ Left | Right ▶ | ▲ Jump | ▼ Slide</div>\n" +
                "  <script>\n" +
                "    // TV Remote & Keyboard Controls\n" +
                "    window.addEventListener('keydown', function(e) {\n" +
                "      var key = e.key || e.code;\n" +
                "      fetch('/input?key=' + encodeURIComponent(key));\n" +
                "    });\n" +
                "    // Reconnect on hiccup\n" +
                "    var img = document.getElementById('stream');\n" +
                "    img.onerror = function() {\n" +
                "      setTimeout(function() { img.src = '/video?t=' + Date.now(); }, 1000);\n" +
                "    };\n" +
                "    // Fade badge after 6 seconds\n" +
                "    setTimeout(function() {\n" +
                "      var badge = document.getElementById('statusBadge');\n" +
                "      if (badge) badge.style.opacity = '0.4';\n" +
                "    }, 6000);\n" +
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
                "Server: CleanPhone-TvGame\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Expires: 0\r\n" +
                "Pragma: no-cache\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=--" + boundary + "\r\n\r\n";

        os.write(header.getBytes(StandardCharsets.US_ASCII));
        os.flush();

        int count = activeClients.incrementAndGet();
        if (statusListener != null) statusListener.onClientCountChanged(count);

        try {
            while (isRunning.get()) {
                byte[] frame;
                synchronized (frameLock) {
                    frameLock.wait(400);
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
            if (statusListener != null) statusListener.onClientCountChanged(count);
        }
    }
}
