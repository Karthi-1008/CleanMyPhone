package com.cleanmyphone.launcher.adblock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.cleanmyphone.launcher.MainActivity;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Built-in System-Level Ad-Blocking Service.
 *
 * Runs transparently as an integrated feature without any standalone app,
 * UI controls, or manual intervention. Intercepts DNS queries locally:
 * - Instantly sinks ad domains with NXDOMAIN (0ms latency).
 * - Forwards clean queries to upstream AdGuard/Cloudflare secure DNS.
 * - Leaves all non-DNS network traffic 100% direct at native Wi-Fi speeds.
 */
public class AdBlockService extends VpnService implements Runnable {
    private static final String TAG = "AdBlockService";
    private static final String CHANNEL_ID = "adblock_channel";
    private static final int NOTIF_ID = 9001;

    private static final String VPN_ADDRESS = "10.0.0.2";
    private static final int VPN_PREFIX = 32;

    private static final String[] UPSTREAM_DNS = {
            "94.140.14.14", // AdGuard Primary Ad-Blocking DNS
            "94.140.15.15", // AdGuard Secondary
            "1.1.1.2",       // Cloudflare Security DNS
            "8.8.8.8"        // Google DNS Fallback
    };

    private static final Set<String> AD_PATTERNS = new HashSet<>(Arrays.asList(
            "doubleclick.net", "googleads.", "adservice.google.", "pagead2.googlesyndication.com",
            "admob.com", "ads.google.com", "applovin.com", "unityads.unity3d.com",
            "adcolony.com", "vungle.com", "ironsrc.com", "chartboost.com",
            "flurry.com", "mopub.com", "inmobi.com", "taboola.com", "outbrain.com",
            "scorecardresearch.com", "quantserve.com", "adnxs.com", "criteo.com",
            "rubiconproject.com", "pubmatic.com", "smartadserver.com", "tapjoy.com",
            "startapp.com", "supersonicads.com", "fyber.com", "leadbolt.com",
            "mobfox.com", "smaato.net", "revcontent.com", "popads.net",
            "propellerads.com", "exoclick.com", "trafficjunky.com", "mgid.com",
            "ad-delivery.net", "crashlytics.com", "app-measurement.com"
    ));

    private ParcelFileDescriptor vpnInterface;
    private Thread workerThread;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public static void startSilently(Context context) {
        try {
            Intent prepareIntent = prepare(context);
            if (prepareIntent == null) {
                Intent serviceIntent = new Intent(context, AdBlockService.class);
                context.startService(serviceIntent);
                Log.d(TAG, "AdBlockService started silently");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not start AdBlockService: " + t.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning.get()) {
            isRunning.set(true);
            workerThread = new Thread(this, "AdBlock-Worker");
            workerThread.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopVpn();
    }

    private void stopVpn() {
        isRunning.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException ignored) {}
            vpnInterface = null;
        }
    }

    @Override
    public void run() {
        try {
            Builder builder = new Builder();
            builder.setSession("AdBlock System");
            builder.addAddress(VPN_ADDRESS, VPN_PREFIX);
            // Direct DNS to local VPN interface so port 53 traffic is filtered locally
            builder.addDnsServer(VPN_ADDRESS);
            builder.addRoute(VPN_ADDRESS, VPN_PREFIX);
            builder.setBlocking(true);

            vpnInterface = builder.establish();
            if (vpnInterface == null) {
                Log.e(TAG, "Failed to establish VPN interface");
                return;
            }

            Log.i(TAG, "System AdBlock VPN established. All ads blocked locally.");

            FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
            FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());

            DatagramSocket forwardSocket = new DatagramSocket();
            protect(forwardSocket);
            forwardSocket.setSoTimeout(2500);

            byte[] packet = new byte[2048];

            while (isRunning.get()) {
                int length = in.read(packet);
                if (length <= 0) continue;

                // Process IPv4 UDP Packet
                if (length >= 28 && (packet[0] >> 4) == 4 && packet[9] == 17) {
                    int ihl = (packet[0] & 0x0F) * 4;
                    int udpOffset = ihl;
                    int srcPort = ((packet[udpOffset] & 0xFF) << 8) | (packet[udpOffset + 1] & 0xFF);
                    int dstPort = ((packet[udpOffset + 2] & 0xFF) << 8) | (packet[udpOffset + 3] & 0xFF);

                    if (dstPort == 53) {
                        int dnsOffset = udpOffset + 8;
                        int dnsLen = length - dnsOffset;

                        if (dnsLen >= 12) {
                            String domain = parseDnsDomain(packet, dnsOffset, dnsLen);

                            if (domain != null && isAd(domain)) {
                                // Block Ad Domain: synthesize instant NXDOMAIN response
                                byte[] reply = createNxdomainResponse(packet, length, ihl);
                                out.write(reply);
                                continue;
                            }

                            // Clean Domain: Forward to upstream secure DNS
                            byte[] upstreamReply = forwardToUpstream(forwardSocket, packet, dnsOffset, dnsLen);
                            if (upstreamReply != null) {
                                byte[] fullPacket = wrapDnsResponse(packet, ihl, srcPort, dstPort, upstreamReply);
                                out.write(fullPacket);
                            }
                        }
                    }
                }
            }

            forwardSocket.close();
        } catch (Throwable t) {
            Log.e(TAG, "AdBlock VPN runtime error: " + t.getMessage(), t);
        } finally {
            stopVpn();
        }
    }

    private boolean isAd(String domain) {
        String lower = domain.toLowerCase();
        for (String pattern : AD_PATTERNS) {
            if (lower.contains(pattern)) {
                return true;
            }
        }
        if (lower.startsWith("ad.") || lower.startsWith("ads.") || lower.startsWith("adserver.")
                || lower.contains(".ads.") || lower.contains("telemetry") || lower.contains("analytics")) {
            return true;
        }
        return false;
    }

    private String parseDnsDomain(byte[] data, int dnsOffset, int dnsLen) {
        try {
            int pos = dnsOffset + 12;
            int end = dnsOffset + dnsLen;
            StringBuilder sb = new StringBuilder();

            while (pos < end) {
                int len = data[pos] & 0xFF;
                if (len == 0) break;
                pos++;
                if (pos + len > end) return null;
                if (sb.length() > 0) sb.append('.');
                for (int i = 0; i < len; i++) {
                    sb.append((char) data[pos + i]);
                }
                pos += len;
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] createNxdomainResponse(byte[] req, int reqLen, int ihl) {
        byte[] rep = Arrays.copyOf(req, reqLen);
        int udpOffset = ihl;
        int dnsOffset = ihl + 8;

        // Swap IP src and dst
        System.arraycopy(req, 12, rep, 16, 4);
        System.arraycopy(req, 16, rep, 12, 4);

        // Swap UDP ports
        rep[udpOffset] = req[udpOffset + 2];
        rep[udpOffset + 1] = req[udpOffset + 3];
        rep[udpOffset + 2] = req[udpOffset];
        rep[udpOffset + 3] = req[udpOffset + 1];

        // DNS Flags: Standard query response, Authoritative, Name error (NXDOMAIN)
        rep[dnsOffset + 2] = (byte) 0x81;
        rep[dnsOffset + 3] = (byte) 0x83;

        // Zero out Answer, Authority, Additional counts
        rep[dnsOffset + 6] = 0; rep[dnsOffset + 7] = 0;
        rep[dnsOffset + 8] = 0; rep[dnsOffset + 9] = 0;
        rep[dnsOffset + 10] = 0; rep[dnsOffset + 11] = 0;

        // Recompute IP Checksum
        rep[10] = 0; rep[11] = 0;
        int ipCksum = calcChecksum(rep, 0, ihl);
        rep[10] = (byte) (ipCksum >> 8);
        rep[11] = (byte) (ipCksum & 0xFF);

        // UDP Checksum (0 is valid in IPv4 UDP)
        rep[udpOffset + 6] = 0;
        rep[udpOffset + 7] = 0;

        return rep;
    }

    private byte[] forwardToUpstream(DatagramSocket socket, byte[] packet, int dnsOffset, int dnsLen) {
        byte[] query = Arrays.copyOfRange(packet, dnsOffset, dnsOffset + dnsLen);
        byte[] respBuf = new byte[1024];

        for (String dnsIp : UPSTREAM_DNS) {
            try {
                InetAddress addr = InetAddress.getByName(dnsIp);
                DatagramPacket sendPkt = new DatagramPacket(query, query.length, addr, 53);
                socket.send(sendPkt);

                DatagramPacket recvPkt = new DatagramPacket(respBuf, respBuf.length);
                socket.receive(recvPkt);

                return Arrays.copyOf(recvPkt.getData(), recvPkt.getLength());
            } catch (SocketTimeoutException ste) {
                // Try next upstream DNS server
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private byte[] wrapDnsResponse(byte[] origPacket, int ihl, int clientPort, int dnsPort, byte[] dnsPayload) {
        int udpLen = 8 + dnsPayload.length;
        int totalLen = ihl + udpLen;
        byte[] resp = new byte[totalLen];

        // Copy IP Header
        System.arraycopy(origPacket, 0, resp, 0, ihl);

        // Update Total Length in IP header
        resp[2] = (byte) (totalLen >> 8);
        resp[3] = (byte) (totalLen & 0xFF);

        // Swap IP src and dst
        System.arraycopy(origPacket, 12, resp, 16, 4);
        System.arraycopy(origPacket, 16, resp, 12, 4);

        // Recompute IP Checksum
        resp[10] = 0; resp[11] = 0;
        int ipCksum = calcChecksum(resp, 0, ihl);
        resp[10] = (byte) (ipCksum >> 8);
        resp[11] = (byte) (ipCksum & 0xFF);

        // Build UDP Header
        int udpOffset = ihl;
        resp[udpOffset] = (byte) (dnsPort >> 8);
        resp[udpOffset + 1] = (byte) (dnsPort & 0xFF);
        resp[udpOffset + 2] = (byte) (clientPort >> 8);
        resp[udpOffset + 3] = (byte) (clientPort & 0xFF);
        resp[udpOffset + 4] = (byte) (udpLen >> 8);
        resp[udpOffset + 5] = (byte) (udpLen & 0xFF);
        resp[udpOffset + 6] = 0; // Checksum optional in IPv4 UDP
        resp[udpOffset + 7] = 0;

        // Copy DNS Payload
        System.arraycopy(dnsPayload, 0, resp, udpOffset + 8, dnsPayload.length);

        return resp;
    }

    private static int calcChecksum(byte[] buf, int offset, int length) {
        int sum = 0;
        for (int i = 0; i < length; i += 2) {
            int b1 = buf[offset + i] & 0xFF;
            int b2 = (i + 1 < length) ? (buf[offset + i + 1] & 0xFF) : 0;
            sum += (b1 << 8) | b2;
        }
        while ((sum >> 16) > 0) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return ~sum & 0xFFFF;
    }
}
