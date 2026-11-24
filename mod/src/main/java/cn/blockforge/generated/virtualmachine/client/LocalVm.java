package cn.blockforge.generated.virtualmachine.client;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 一台仅在玩家本机运行的 x86 虚拟机。世界和服务端不接收镜像或画面。 */
public final class LocalVm {
    static final LocalVm INSTANCE = new LocalVm();
    private static final boolean WINDOWS = File.separatorChar == '\\';
    private static final String QEMU = "qemu-system-x86_64" + (WINDOWS ? ".exe" : "");
    private static final String IMG = "qemu-img" + (WINDOWS ? ".exe" : "");
    private static final String QEMU_SETTING = "qemu-directory.txt";
    private static final String ANDROID_QEMU = "检测到安卓模拟器自带的 QEMU。请安装独立版 QEMU，再点「选择 QEMU」选择安装目录。";

    private record QemuTools(Path system, Path image) {
    }

    private volatile Session active;
    private volatile String status = "选择 ISO / IMG 安装系统，或从本机虚拟硬盘启动。";

    private static final class Session {
        volatile boolean stopping;
        volatile Process process;
        volatile Process preparingDisk;
        volatile RfbConnection display;
        volatile Socket socket;
        volatile ServerSocket listener;
    }

    private LocalVm() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::onGameExit, "PC-Craft-VM-exit"));
    }

    private void onGameExit() {
        Session session = active;
        if (session != null) {
            session.stopping = true;
            terminate(session.preparingDisk);
            endGuest(session.process);
        }
    }

    boolean isActive() {
        return active != null;
    }

    String status() {
        return status;
    }

    RfbConnection display() {
        Session session = active;
        return session == null ? null : session.display;
    }

    synchronized boolean start(Path directory, Path media) {
        if (active != null) {
            return false;
        }
        Session session = new Session();
        status = "正在准备虚拟硬盘并启动 QEMU…";
        active = session;
        Thread.ofVirtual().name("PC-Craft-VM").start(() -> run(session, directory, media));
        return true;
    }

    void powerButton() {
        Session session = active;
        Process process = session == null ? null : session.process;
        if (process != null && process.isAlive()) {
            Thread.ofVirtual().start(() -> sendQmp(process, "system_powerdown"));
        }
    }

    synchronized void stop() {
        Session session = active;
        if (session == null || session.stopping) {
            return;
        }
        session.stopping = true;
        status = "正在关闭虚拟机…";
        Thread.ofVirtual().start(() -> {
            close(session.listener);
            terminate(session.preparingDisk);
            endGuest(session.process);
            close(session.socket);
        });
    }

    private void run(Session session, Path directory, Path media) {
        directory = directory.toAbsolutePath();
        Path log = directory.resolve("qemu.log");
        Path temporaryMedia = null;
        String result;
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            session.listener = listener;
            Path disk = directory.resolve("system.qcow2");
            boolean needsDisk = Files.notExists(disk);
            QemuTools tools = findQemu(directory, needsDisk);
            Files.createDirectories(directory);
            if (media != null) {
                media = media.toAbsolutePath();
                String extension = validateMedia(media);
                if (WINDOWS && needsSafeMediaName(directory, media)) {
                    status = "正在准备安装镜像的临时副本…";
                    temporaryMedia = Files.createTempFile(directory, "installer-", extension);
                    copyMedia(session, media, temporaryMedia);
                    media = temporaryMedia;
                }
            }
            if (session.stopping) {
                return;
            }
            List<String> mediaOptions = mediaOptions(directory, media);
            if (needsDisk) {
                prepareDisk(session, disk, log, tools.image());
            }
            if (session.stopping) {
                return;
            }
            if (!Files.isRegularFile(disk) || !Files.isReadable(disk) || Files.size(disk) == 0) {
                throw new IOException("无法读取虚拟硬盘，请检查 pc-craft-vm/system.qcow2 是否存在且可读。");
            }
            Process guest = launchGuest(disk, mediaOptions, listener.getLocalPort(), log, tools.system());
            session.process = guest;
            Thread.ofVirtual().name("PC-Craft-VM-control").start(() -> drainQmp(guest));
            if (session.stopping) {
                return;
            }
            try (Socket socket = acceptGuest(listener, guest)) {
                session.socket = socket;
                listener.close();
                if (session.stopping) {
                    return;
                }
                session.display = new RfbConnection(socket);
                status = "正在读取系统的画面…";
                session.display.run();
            }
            result = "虚拟机已关闭。";
        } catch (IOException ex) {
            Process guest = session.process;
            if (guest != null && waitFor(guest, 250) && guest.exitValue() == 0) {
                result = "虚拟机已关闭。";
            } else {
                result = ex.getMessage() + (guest == null ? "" : "（详情见 pc-craft-vm/qemu.log）");
            }
        } catch (RuntimeException ex) {
            result = "无法准备虚拟机：" + ex.getMessage();
        } finally {
            endGuest(session.process);
            if (temporaryMedia != null) {
                try {
                    Files.deleteIfExists(temporaryMedia);
                } catch (IOException ex) {
                    temporaryMedia.toFile().deleteOnExit();
                }
            }
            if (session.stopping) {
                finish(session, "虚拟机已关闭。");
            }
        }
        finish(session, result);
    }

    private synchronized void finish(Session session, String result) {
        if (active == session) {
            status = result;
            active = null;
        }
    }

    private static void prepareDisk(Session session, Path disk, Path log, Path imageTool) throws IOException {
        Path temp = Files.createTempFile(disk.getParent(), "system-", ".qcow2");
        try {
            if (session.stopping) {
                return;
            }
            Process process = launch(List.of(imageTool.toString(), "create", "-f", "qcow2",
                temp.getFileName().toString(), "32G"), disk.getParent(), log);
            session.preparingDisk = process;
            if (session.stopping) {
                terminate(process);
                return;
            }
            if (!waitFor(process, 15000) || process.exitValue() != 0) {
                throw new IOException("无法建立虚拟硬盘，请检查剩余空间和 pc-craft-vm/qemu.log");
            }
            if (!session.stopping) {
                Files.move(temp, disk);
            }
        } finally {
            terminate(session.preparingDisk);
            session.preparingDisk = null;
            Files.deleteIfExists(temp);
        }
    }

    private static String validateMedia(Path media) throws IOException {
        if (!Files.isRegularFile(media) || !Files.isReadable(media) || Files.size(media) == 0) {
            throw new IOException("无法读取所选镜像，请确认文件仍在原位置。");
        }
        String name = media.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".iso")) {
            return ".iso";
        }
        if (name.endsWith(".img")) {
            return ".img";
        }
        throw new IOException("请选择 .iso 或 .img 文件。");
    }

    private static Path qemuMediaPath(Path directory, Path media) {
        return directory.equals(media.getParent()) ? media.getFileName() : media;
    }

    private static boolean needsSafeMediaName(Path directory, Path media) {
        String path = qemuMediaPath(directory, media).toString();
        return path.length() >= 260 || path.chars().anyMatch(ch -> ch > 127);
    }

    private static void copyMedia(Session session, Path source, Path target) throws IOException {
        try (InputStream input = Files.newInputStream(source); OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            int count;
            while (!session.stopping && (count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        } catch (IOException ex) {
            throw new IOException("无法复制安装镜像，请检查原文件能否读取以及游戏磁盘的剩余空间。", ex);
        }
    }

    private static List<String> mediaOptions(Path directory, Path media) throws IOException {
        if (media == null) {
            return List.of("-boot", "order=c,menu=on");
        }
        String name = media.getFileName().toString().toLowerCase(Locale.ROOT);
        String file = "file=" + escape(qemuMediaPath(directory, media)) + ",format=raw";
        if (name.endsWith(".iso")) {
            return List.of("-drive", file + ",if=ide,index=2,media=cdrom,readonly=on",
                "-boot", "order=c,once=d,menu=on");
        }
        long size = Files.size(media);
        if (size == 368640 || size == 737280 || size == 1228800 || size == 1474560 || size == 2949120) {
            return List.of("-drive", file + ",if=floppy,readonly=on", "-boot", "order=c,once=a,menu=on");
        }
        // 硬盘型 IMG 的写入落到临时副本，系统请安装到另一块持久硬盘。
        return List.of("-drive", file + ",if=none,id=installer,snapshot=on",
            "-device", "ide-hd,drive=installer,bus=ide.0,unit=1,bootindex=1", "-boot", "menu=on");
    }

    private static Process launchGuest(Path disk, List<String> media, int port, Path log, Path emulator) throws IOException {
        List<String> command = new ArrayList<>(List.of(emulator.toString(), "-no-user-config", "-nodefaults",
            "-machine", "pc,accel=tcg", "-m", "2048", "-smp", "2", "-vga", "std", "-nic", "none",
            "-device", "qemu-xhci", "-device", "usb-tablet", "-monitor", "none", "-serial", "none",
            "-qmp", "stdio", "-display", "none", "-vnc", "127.0.0.1:" + port + ",reverse=on",
            "-drive", "file=" + escape(disk.getFileName()) + ",format=qcow2,if=ide,index=0"));
        command.addAll(media);
        return launch(command, disk.getParent(), log);
    }

    private static String escape(Path path) {
        return path.toString().replace(",", ",,");
    }

    static void selectQemuDirectory(Path vmDirectory, Path selected) throws IOException {
        Path directory = selected.toAbsolutePath().normalize();
        requireQemu(directory, true);
        Files.createDirectories(vmDirectory);
        Path temp = Files.createTempFile(vmDirectory, "qemu-directory-", ".tmp");
        try {
            Files.writeString(temp, directory.toString());
            Path setting = vmDirectory.resolve(QEMU_SETTING);
            try {
                Files.move(temp, setting, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, setting, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static QemuTools findQemu(Path vmDirectory, boolean needsDisk) throws IOException {
        Path setting = vmDirectory.resolve(QEMU_SETTING);
        if (Files.exists(setting)) {
            Path directory;
            try {
                directory = Path.of(Files.readString(setting));
            } catch (IOException | InvalidPathException ex) {
                throw new IOException("无法读取保存的 QEMU 路径，请重新点「选择 QEMU」。", ex);
            }
            return requireQemu(directory, needsDisk);
        }

        List<Path> directories = new ArrayList<>();
        if (WINDOWS) {
            for (String key : List.of("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)")) {
                Path programFiles = absolutePath(System.getenv(key));
                if (programFiles != null) {
                    directories.add(programFiles.resolve("qemu"));
                }
            }
        } else {
            directories.addAll(List.of(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"),
                Path.of("/opt/local/bin"), Path.of("/usr/bin"), Path.of("/bin")));
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
                Path directory = absolutePath(entry.trim().replace("\"", ""));
                if (directory != null) {
                    directories.add(directory);
                }
            }
        }

        boolean foundAndroid = false;
        Path system = null;
        Path image = null;
        for (Path directory : directories) {
            if (isAndroidEmulator(directory)) {
                foundAndroid = true;
                continue;
            }
            Path candidateSystem = directory.resolve(QEMU);
            Path candidateImage = directory.resolve(IMG);
            if (system == null && isExecutable(candidateSystem)) {
                system = candidateSystem;
            }
            if (needsDisk && image == null && isExecutable(candidateImage)) {
                image = candidateImage;
            }
            if (system != null && (!needsDisk || image != null)) {
                return new QemuTools(system, image);
            }
        }
        if (foundAndroid) {
            throw new IOException(ANDROID_QEMU);
        }
        throw new IOException("找不到 " + (system == null ? QEMU : IMG)
            + "。请安装独立版 QEMU，然后点「选择 QEMU」选安装目录。");
    }

    private static Path absolutePath(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            Path path = Path.of(text);
            return path.isAbsolute() ? path.normalize() : null;
        } catch (InvalidPathException ex) {
            return null;
        }
    }

    private static QemuTools requireQemu(Path directory, boolean needsDisk) throws IOException {
        if (isAndroidEmulator(directory)) {
            throw new IOException(ANDROID_QEMU);
        }
        Path system = directory.resolve(QEMU);
        Path image = directory.resolve(IMG);
        if (!directory.isAbsolute() || !isExecutable(system) || (needsDisk && !isExecutable(image))) {
            throw new IOException("所选目录缺少可运行的 " + (!isExecutable(system) ? QEMU : IMG)
                + "。请点「选择 QEMU」重新选择安装目录。");
        }
        return new QemuTools(system, image);
    }

    private static boolean isExecutable(Path file) {
        return Files.isRegularFile(file) && Files.isExecutable(file);
    }

    private static boolean isAndroidEmulator(Path directory) {
        Path parent = directory.getParent();
        String suffix = WINDOWS ? ".exe" : "";
        if (parent != null && parent.endsWith("qemu") && parent.getParent() != null) {
            directory = parent.getParent();
        }
        return Files.isRegularFile(directory.resolve("emulator" + suffix))
            && Files.isDirectory(directory.resolve("qemu"));
    }

    private static Process launch(List<String> command, Path directory, Path log) throws IOException {
        try {
            // 通过进程工作目录传递游戏路径，让 QEMU 用短文件名打开本地磁盘。
            return new ProcessBuilder(command).directory(directory.toFile()).redirectError(log.toFile()).start();
        } catch (IOException ex) {
            throw new IOException("已找到 " + Path.of(command.getFirst()).getFileName()
                + "，但系统无法启动它。请重新选择独立版 QEMU 的安装目录。" + ex.getMessage(), ex);
        }
    }

    private static Socket acceptGuest(ServerSocket listener, Process guest) throws IOException {
        listener.setSoTimeout(250);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            try {
                return listener.accept();
            } catch (SocketTimeoutException ex) {
                if (!guest.isAlive()) {
                    throw new IOException("QEMU 启动失败");
                }
            }
        } while (System.nanoTime() < deadline);
        throw new IOException("等待虚拟机画面超时");
    }

    private static void drainQmp(Process process) {
        try (BufferedReader output = process.inputReader(StandardCharsets.UTF_8)) {
            if (output.readLine() != null) {
                sendQmp(process, "qmp_capabilities");
                while (output.readLine() != null) {
                    // 消费控制台事件，防止输出管道堵塞。
                }
            }
        } catch (IOException ignored) {
            // 虚拟机关闭后管道随之关闭。
        }
    }

    private static void sendQmp(Process process, String command) {
        OutputStream input = process.getOutputStream();
        synchronized (input) {
            try {
                input.write(("{\"execute\":\"" + command + "\"}\n").getBytes(StandardCharsets.UTF_8));
                input.flush();
            } catch (IOException ignored) {
                // 虚拟机可能已经关闭。
            }
        }
    }

    private static void endGuest(Process process) {
        if (process != null && process.isAlive()) {
            sendQmp(process, "quit");
            if (!waitFor(process, 800)) {
                terminate(process);
            }
        }
    }

    private static void terminate(Process process) {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!waitFor(process, 500)) {
                process.destroyForcibly();
                waitFor(process, 1500);
            }
        }
    }

    private static boolean waitFor(Process process, long millis) {
        try {
            return process.waitFor(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return !process.isAlive();
        }
    }

    private static void close(AutoCloseable socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception ignored) {
                // 已关闭。
            }
        }
    }
}
