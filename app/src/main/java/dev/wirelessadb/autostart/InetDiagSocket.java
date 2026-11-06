package dev.wirelessadb.autostart;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructTimeval;

import java.io.FileDescriptor;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Queries and closes established TCP/TCP6 sockets directly via Linux {@code NETLINK_INET_DIAG}
 * ({@code SOCK_DIAG_BY_FAMILY} and {@code SOCK_DESTROY}).
 *
 * <p>On Android 12–16+, SELinux {@code neverallow} blocks {@code system_server} from reading
 * {@code /proc/net/tcp*}, whereas {@code system_server} explicitly holds
 * {@code netlink_tcpdiag_socket { nlmsg_read nlmsg_write }} and {@code CAP_NET_ADMIN}.
 * This enables completely rootless active client IP discovery and socket disconnection.
 */
final class InetDiagSocket {
    private static final short SOCK_DIAG_BY_FAMILY = 20;
    private static final short SOCK_DESTROY = 21;
    private static final short NLMSG_ERROR = 2;
    private static final short NLMSG_DONE = 3;
    private static final short NLM_F_REQUEST = 0x01;
    private static final short NLM_F_ACK = 0x04;
    private static final short NLM_F_DUMP = 0x0300; // NLM_F_ROOT | NLM_F_MATCH
    private static final int TCP_ESTABLISHED = 1;
    private static final int TCPF_ESTABLISHED = (1 << TCP_ESTABLISHED);
    private static final int NLMSG_HDR_LEN = 16;
    private static final int INET_DIAG_REQ_V2_LEN = 56;
    private static final int INET_DIAG_SOCKID_LEN = 48;
    private static final int INET_DIAG_MSG_MIN_LEN = 88; // 16 hdr + 72 body

    static final class RawTcpSocket {
        final byte family;
        final int localPort;
        final int remotePort;
        final String remoteIp;
        final byte[] sockId; // verbatim 48-byte inet_diag_sockid

        RawTcpSocket(byte family, int localPort, int remotePort, String remoteIp, byte[] sockId) {
            this.family = family;
            this.localPort = localPort;
            this.remotePort = remotePort;
            this.remoteIp = remoteIp;
            this.sockId = sockId;
        }
    }

    private InetDiagSocket() {}

    /** Dumps all established TCP (AF_INET + AF_INET6) sockets matching tcpPort or tlsPort. */
    static List<RawTcpSocket> dumpEstablishedAdbSockets(int tcpPort, int tlsPort) throws Exception {
        FileDescriptor fd = openDiagSocket();
        try {
            List<RawTcpSocket> results = new ArrayList<>();
            dumpFamily(fd, (byte) OsConstants.AF_INET, 1, tcpPort, tlsPort, results);
            dumpFamily(fd, (byte) OsConstants.AF_INET6, 2, tcpPort, tlsPort, results);
            return results;
        } finally {
            closeQuietly(fd);
        }
    }

    /** Destroys matching established ADB client sockets via SOCK_DESTROY (returns closed count). */
    static int destroyMatchingSockets(String remoteIp, int remotePort, int localPort,
                                      boolean allPortsForIp) throws Exception {
        List<RawTcpSocket> candidates = dumpEstablishedAdbSockets(localPort, -1);
        if (candidates.isEmpty()) return 0;

        FileDescriptor fd = openDiagSocket();
        int closed = 0;
        int seq = 100;
        try {
            for (RawTcpSocket sock : candidates) {
                if (sock.localPort != localPort) continue;
                if (!remoteIp.equalsIgnoreCase(sock.remoteIp)) continue;
                if (!allPortsForIp && remotePort > 0 && sock.remotePort != remotePort) continue;

                if (sendDestroy(fd, sock, seq++)) {
                    closed++;
                }
            }
        } finally {
            closeQuietly(fd);
        }
        return closed;
    }

    private static FileDescriptor openDiagSocket() throws Exception {
        FileDescriptor fd = Os.socket(
                OsConstants.AF_NETLINK,
                OsConstants.SOCK_DGRAM | OsConstants.SOCK_CLOEXEC,
                OsConstants.NETLINK_INET_DIAG);
        try {
            Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO,
                    StructTimeval.fromMillis(800L));
        } catch (Throwable ignored) { }
        return fd;
    }

    private static void dumpFamily(FileDescriptor fd, byte family, int seq,
                                   int tcpPort, int tlsPort, List<RawTcpSocket> out) throws Exception {
        byte[] req = new byte[NLMSG_HDR_LEN + INET_DIAG_REQ_V2_LEN];
        ByteBuffer buf = ByteBuffer.wrap(req).order(ByteOrder.nativeOrder());
        buf.putInt(req.length);
        buf.putShort(SOCK_DIAG_BY_FAMILY);
        buf.putShort((short) (NLM_F_REQUEST | NLM_F_DUMP));
        buf.putInt(seq);
        buf.putInt(0);
        // inet_diag_req_v2
        buf.put(family);
        buf.put((byte) OsConstants.IPPROTO_TCP);
        buf.put((byte) 0); // idiag_ext
        buf.put((byte) 0); // pad
        buf.putInt(TCPF_ESTABLISHED);
        // remaining 48 bytes of inet_diag_sockid are 0

        Os.write(fd, req, 0, req.length);

        byte[] recv = new byte[32768];
        boolean done = false;
        while (!done) {
            int n = Os.read(fd, recv, 0, recv.length);
            if (n <= 0) break;

            ByteBuffer rb = ByteBuffer.wrap(recv, 0, n).order(ByteOrder.nativeOrder());
            int offset = 0;
            while (offset + NLMSG_HDR_LEN <= n) {
                int nlLen = rb.getInt(offset);
                short nlType = rb.getShort(offset + 4);
                if (nlLen < NLMSG_HDR_LEN || offset + nlLen > n) {
                    break;
                }
                if (nlType == NLMSG_DONE) {
                    done = true;
                    break;
                }
                if (nlType == NLMSG_ERROR) {
                    done = true;
                    break;
                }
                if (nlType == SOCK_DIAG_BY_FAMILY && nlLen >= INET_DIAG_MSG_MIN_LEN) {
                    parseDiagMsg(recv, offset, tcpPort, tlsPort, out);
                }
                int aligned = (nlLen + 3) & ~3;
                if (aligned <= 0) break;
                offset += aligned;
            }
        }
    }

    private static void parseDiagMsg(byte[] buf, int msgOffset, int tcpPort, int tlsPort,
                                     List<RawTcpSocket> out) {
        byte fam = buf[msgOffset + 16];
        int state = buf[msgOffset + 17] & 0xFF;
        if (state != TCP_ESTABLISHED) return;

        int localPort = ((buf[msgOffset + 20] & 0xFF) << 8) | (buf[msgOffset + 21] & 0xFF);
        int remotePort = ((buf[msgOffset + 22] & 0xFF) << 8) | (buf[msgOffset + 23] & 0xFF);
        if (localPort <= 0 || remotePort <= 0) return;

        boolean matchesTcp = tcpPort > 0 && localPort == tcpPort;
        boolean matchesTls = tlsPort > 0 && localPort == tlsPort;
        if (!matchesTcp && !matchesTls) return;

        try {
            byte[] dstBytes;
            if (fam == OsConstants.AF_INET) {
                dstBytes = Arrays.copyOfRange(buf, msgOffset + 40, msgOffset + 44);
            } else if (fam == OsConstants.AF_INET6) {
                dstBytes = Arrays.copyOfRange(buf, msgOffset + 40, msgOffset + 56);
            } else {
                return;
            }
            InetAddress addr = InetAddress.getByAddress(dstBytes);
            String host = addr.getHostAddress();
            if (host == null) return;
            int pct = host.indexOf('%');
            if (pct > 0) host = host.substring(0, pct);

            byte[] sockId = Arrays.copyOfRange(buf, msgOffset + 20, msgOffset + 20 + INET_DIAG_SOCKID_LEN);
            out.add(new RawTcpSocket(fam, localPort, remotePort, host, sockId));
        } catch (Throwable ignored) { }
    }

    private static boolean sendDestroy(FileDescriptor fd, RawTcpSocket sock, int seq) {
        try {
            byte[] req = new byte[NLMSG_HDR_LEN + INET_DIAG_REQ_V2_LEN];
            ByteBuffer buf = ByteBuffer.wrap(req).order(ByteOrder.nativeOrder());
            buf.putInt(req.length);
            buf.putShort(SOCK_DESTROY);
            buf.putShort((short) (NLM_F_REQUEST | NLM_F_ACK));
            buf.putInt(seq);
            buf.putInt(0);
            buf.put(sock.family);
            buf.put((byte) OsConstants.IPPROTO_TCP);
            buf.put((byte) 0);
            buf.put((byte) 0);
            buf.putInt(0xFFFFFFFF);
            System.arraycopy(sock.sockId, 0, req, 24, INET_DIAG_SOCKID_LEN);

            Os.write(fd, req, 0, req.length);

            byte[] ack = new byte[256];
            int n = Os.read(fd, ack, 0, ack.length);
            if (n >= 20) {
                ByteBuffer ab = ByteBuffer.wrap(ack, 0, n).order(ByteOrder.nativeOrder());
                short nlType = ab.getShort(4);
                int err = ab.getInt(16);
                return nlType == NLMSG_ERROR && err == 0;
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static void closeQuietly(FileDescriptor fd) {
        if (fd != null) {
            try { Os.close(fd); } catch (Throwable ignored) { }
        }
    }
}
