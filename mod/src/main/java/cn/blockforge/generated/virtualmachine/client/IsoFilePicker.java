package cn.blockforge.generated.virtualmachine.client;

import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

/** 在系统文件窗口中选择本机镜像，结果回到游戏线程。 */
public final class IsoFilePicker {
    public interface Callback {
        void onPicked(Path image);
        void onFailed(String reason);
        void onCancelled();
    }

    private static volatile boolean busy;

    private IsoFilePicker() {
    }

    public static boolean isBusy() {
        return busy;
    }

    public static void open(MinecraftClient client, Callback callback) {
        openDialog(client, callback, () -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer filters = stack.pointers(stack.UTF8("*.iso"), stack.UTF8("*.img"),
                    stack.UTF8("*.ISO"), stack.UTF8("*.IMG"));
                String picked = TinyFileDialogs.tinyfd_openFileDialog("选择你自己电脑的 ISO / IMG", "",
                    filters, "ISO / IMG", false);
                return picked == null ? null : Path.of(picked);
            }
        });
    }

    static void openQemuDirectory(MinecraftClient client, Path vmDirectory, Callback callback) {
        openDialog(client, callback, () -> {
            String picked = TinyFileDialogs.tinyfd_selectFolderDialog("选择独立版 QEMU 的安装目录", "");
            if (picked == null) {
                return null;
            }
            Path directory = Path.of(picked);
            LocalVm.selectQemuDirectory(vmDirectory, directory);
            return directory;
        });
    }

    @FunctionalInterface
    private interface Picker {
        Path pick() throws IOException;
    }

    private static void openDialog(MinecraftClient client, Callback callback, Picker picker) {
        if (busy) {
            return;
        }
        busy = true;
        Thread.ofPlatform().daemon().name("PC-Craft-File-Picker").start(() -> {
            try {
                Path selected = picker.pick();
                client.execute(() -> {
                    busy = false;
                    if (selected == null) {
                        callback.onCancelled();
                    } else {
                        callback.onPicked(selected);
                    }
                });
            } catch (IOException | RuntimeException | LinkageError ex) {
                client.execute(() -> {
                    busy = false;
                    callback.onFailed(ex instanceof IOException ? ex.getMessage() : ex.getClass().getSimpleName());
                });
            }
        });
    }
}
