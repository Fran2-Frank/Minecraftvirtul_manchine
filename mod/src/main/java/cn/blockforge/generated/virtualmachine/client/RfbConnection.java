package cn.blockforge.generated.virtualmachine.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;

/** 只连接本机虚拟机的 RFB 3.8 客户端。解码与发送输入都在后台进行。 */
final class RfbConnection {
    record Frame(int width, int height, int[] pixels) {}
    private record Input(int type, int a, int b, int c) {}

    private static final int MAX_PIXELS = 4096 * 2160;
    private static final int RAW = 0;
    private static final int COPY_RECT = 1;
    private static final int DESKTOP_SIZE = -223;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ArrayBlockingQueue<Input> inputs = new ArrayBlockingQueue<>(512);
    private volatile Frame latest;
    private int width;
    private int height;
    private int[] pixels;

    RfbConnection(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(5000);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    Frame frame() {
        return latest;
    }

    boolean key(int keysym, boolean down) {
        return inputs.offer(new Input(4, down ? 1 : 0, keysym, 0));
    }

    boolean pointer(int x, int y, int mask) {
        return inputs.offer(new Input(5, mask, x, y));
    }

    void run() throws IOException {
        handshake();
        socket.setSoTimeout(0);
        Thread writer = Thread.ofVirtual().name("PC-Craft-VM-input").start(this::writeInputs);
        try {
            sendUpdateRequest(false);
            while (!socket.isClosed()) {
                switch (in.readUnsignedByte()) {
                    case 0 -> readFrame();
                    case 2 -> { /* 响铃。 */ }
                    case 3 -> {
                        in.skipNBytes(3);
                        skipText();
                    }
                    default -> throw new IOException("虚拟机画面返回了不支持的消息");
                }
            }
        } finally {
            writer.interrupt();
        }
    }

    private void handshake() throws IOException {
        byte[] version = in.readNBytes(12);
        if (!"RFB 003.008\n".equals(new String(version, StandardCharsets.US_ASCII))) {
            throw new IOException("虚拟机画面需要 RFB 3.8");
        }
        out.write(version);
        out.flush();
        int count = in.readUnsignedByte();
        if (count == 0) {
            throw new IOException("虚拟机拒绝连接画面");
        }
        boolean localMode = false;
        for (int i = 0; i < count; i++) {
            localMode |= in.readUnsignedByte() == 1;
        }
        if (!localMode) {
            throw new IOException("虚拟机画面未提供本地连接模式");
        }
        out.writeByte(1);
        out.flush();
        if (in.readInt() != 0) {
            throw new IOException("虚拟机拒绝本地画面连接");
        }
        out.writeByte(0);
        out.flush();
        resize(in.readUnsignedShort(), in.readUnsignedShort());
        in.skipNBytes(16);
        skipText();

        // 小端 RGBA。QEMU 的原始像素前三个字节依次为红、绿、蓝。
        out.write(new byte[] {0, 0, 0, 0, 32, 24, 0, 1, 0, (byte) 255, 0, (byte) 255,
            0, (byte) 255, 0, 8, 16, 0, 0, 0});
        out.writeByte(2);
        out.writeByte(0);
        out.writeShort(3);
        out.writeInt(COPY_RECT);
        out.writeInt(RAW);
        out.writeInt(DESKTOP_SIZE);
        out.flush();
    }

    private void skipText() throws IOException {
        int size = in.readInt();
        if (size < 0 || size > 1_048_576) {
            throw new IOException("虚拟机画面消息太大");
        }
        in.skipNBytes(size);
    }

    private void resize(int w, int h) throws IOException {
        if (w < 1 || h < 1 || w > 4096 || h > 4096 || (long) w * h > MAX_PIXELS) {
            throw new IOException("虚拟机画面分辨率超出支持范围");
        }
        width = w;
        height = h;
        pixels = new int[w * h];
    }

    private void readFrame() throws IOException {
        in.readUnsignedByte();
        int count = in.readUnsignedShort();
        boolean resized = false;
        for (int i = 0; i < count; i++) {
            int x = in.readUnsignedShort();
            int y = in.readUnsignedShort();
            int w = in.readUnsignedShort();
            int h = in.readUnsignedShort();
            int encoding = in.readInt();
            if (encoding == DESKTOP_SIZE) {
                resize(w, h);
                resized = true;
                continue;
            }
            if (x + w > width || y + h > height) {
                throw new IOException("虚拟机画面区域越界");
            }
            switch (encoding) {
                case RAW -> readRaw(x, y, w, h);
                case COPY_RECT -> copyRect(x, y, w, h);
                default -> throw new IOException("虚拟机画面返回了不支持的编码 " + encoding);
            }
        }
        if (count > 0) {
            latest = new Frame(width, height, pixels.clone());
        }
        sendUpdateRequest(!resized);
    }

    private void readRaw(int x, int y, int w, int h) throws IOException {
        byte[] row = new byte[w * 4];
        for (int dy = 0; dy < h; dy++) {
            in.readFully(row);
            int base = (y + dy) * width + x;
            for (int col = 0, b = 0; col < w; col++, b += 4) {
                pixels[base + col] = 0xFF000000 | (row[b] & 255) | ((row[b + 1] & 255) << 8)
                    | ((row[b + 2] & 255) << 16);
            }
        }
    }

    private void copyRect(int x, int y, int w, int h) throws IOException {
        int sx = in.readUnsignedShort();
        int sy = in.readUnsignedShort();
        if (sx + w > width || sy + h > height) {
            throw new IOException("虚拟机画面复制区域越界");
        }
        for (int row = 0; row < h; row++) {
            int dy = y > sy ? h - 1 - row : row;
            System.arraycopy(pixels, (sy + dy) * width + sx, pixels, (y + dy) * width + x, w);
        }
    }

    private void sendUpdateRequest(boolean incremental) throws IOException {
        synchronized (out) {
            out.writeByte(3);
            out.writeByte(incremental ? 1 : 0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(width);
            out.writeShort(height);
            out.flush();
        }
    }

    private void writeInputs() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                Input event = inputs.take();
                synchronized (out) {
                    out.writeByte(event.type());
                    out.writeByte(event.a());
                    if (event.type() == 4) {
                        out.writeShort(0);
                        out.writeInt(event.b());
                    } else {
                        out.writeShort(event.b());
                        out.writeShort(event.c());
                    }
                    out.flush();
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            try {
                socket.close();
            } catch (IOException alreadyClosed) {
                // 关闭连接后，画面读取也会结束。
            }
        }
    }
}
