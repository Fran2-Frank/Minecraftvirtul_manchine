package cn.blockforge.generated.virtualmachine.client;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/** 在游戏线程上显示来宾画面，并把鼠标和键盘事件送进虚拟机。 */
final class GuestDisplay {
    private final Set<Integer> pressed = new HashSet<>();
    private RfbConnection connection;
    private RfbConnection.Frame shown;
    private Identifier id;
    private NativeImageBackedTexture texture;
    private int left, top, width, height;
    private int mouseX, mouseY, buttons;

    void draw(MinecraftClient client, DrawContext context, int screenWidth, int screenHeight) {
        RfbConnection next = LocalVm.INSTANCE.display();
        if (next != connection) {
            release(client);
            connection = next;
        }
        if (!client.isWindowFocused()) {
            releaseInput();
        }
        RfbConnection.Frame frame = connection == null ? null : connection.frame();
        if (frame == null) {
            return;
        }
        if (texture == null || shown.width() != frame.width() || shown.height() != frame.height()) {
            destroyTexture(client);
            texture = new NativeImageBackedTexture(frame.width(), frame.height(), false);
            id = client.getTextureManager().registerDynamicTexture("pc_craft_vm", texture);
        }
        if (frame != shown) {
            NativeImage image = texture.getImage();
            int[] pixels = frame.pixels();
            for (int y = 0, index = 0; y < frame.height(); y++) {
                for (int x = 0; x < frame.width(); x++) {
                    image.setColor(x, y, pixels[index++]);
                }
            }
            texture.upload();
            shown = frame;
        }
        double scale = Math.min(Math.max(1, screenWidth - 12) / (double) frame.width(),
            Math.max(1, screenHeight - 44) / (double) frame.height());
        width = Math.max(1, (int) (frame.width() * scale));
        height = Math.max(1, (int) (frame.height() * scale));
        left = (screenWidth - width) / 2;
        top = 20 + (Math.max(1, screenHeight - 44) - height) / 2;
        context.drawTexture(id, left, top, width, height, 0f, 0f,
            frame.width(), frame.height(), frame.width(), frame.height());
    }

    boolean hasFrame() {
        return shown != null;
    }

    void release(MinecraftClient client) {
        releaseInput();
        connection = null;
        shown = null;
        destroyTexture(client);
    }

    private void destroyTexture(MinecraftClient client) {
        if (id != null) {
            client.getTextureManager().destroyTexture(id);
            id = null;
            texture = null;
        }
    }

    private void releaseInput() {
        if (connection != null) {
            for (int keysym : pressed) {
                connection.key(keysym, false);
            }
            if (buttons != 0) {
                connection.pointer(mouseX, mouseY, 0);
            }
        }
        pressed.clear();
        buttons = 0;
    }

    void key(int key, boolean down) {
        int keysym = keysym(key);
        if (shown != null && keysym != 0) {
            if (down && !pressed.contains(keysym) && connection.key(keysym, true)) {
                pressed.add(keysym);
            } else if (!down && pressed.contains(keysym) && connection.key(keysym, false)) {
                pressed.remove(keysym);
            }
        }
    }

    void move(double x, double y) {
        if ((inside(x, y) || buttons != 0) && updatePointer(x, y)) {
            connection.pointer(mouseX, mouseY, buttons);
        }
    }

    boolean click(double x, double y, int button, boolean down) {
        int bit = switch (button) {
            case GLFW.GLFW_MOUSE_BUTTON_LEFT -> 1;
            case GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> 2;
            case GLFW.GLFW_MOUSE_BUTTON_RIGHT -> 4;
            default -> 0;
        };
        if (bit == 0 || (down ? !inside(x, y) : (buttons & bit) == 0)) {
            return false;
        }
        int newButtons = down ? buttons | bit : buttons & ~bit;
        if (updatePointer(x, y) && connection.pointer(mouseX, mouseY, newButtons)) {
            buttons = newButtons;
        }
        return true;
    }

    void scroll(double x, double y, double amount) {
        if (amount != 0 && inside(x, y)) {
            updatePointer(x, y);
            int bit = amount > 0 ? 8 : 16;
            int steps = (int) Math.min(6, Math.ceil(Math.abs(amount)));
            for (int i = 0; i < steps; i++) {
                connection.pointer(mouseX, mouseY, buttons | bit);
                connection.pointer(mouseX, mouseY, buttons);
            }
        }
    }

    private boolean inside(double x, double y) {
        return shown != null && x >= left && x < left + width && y >= top && y < top + height;
    }

    private boolean updatePointer(double x, double y) {
        if (shown == null) {
            return false;
        }
        mouseX = Math.clamp((int) ((x - left) * shown.width() / width), 0, shown.width() - 1);
        mouseY = Math.clamp((int) ((y - top) * shown.height() / height), 0, shown.height() - 1);
        return true;
    }

    private static int keysym(int key) {
        if (key >= GLFW.GLFW_KEY_A && key <= GLFW.GLFW_KEY_Z) {
            return key + ('a' - 'A');
        }
        if (key >= GLFW.GLFW_KEY_F1 && key <= GLFW.GLFW_KEY_F12) {
            return 0xffbe + key - GLFW.GLFW_KEY_F1;
        }
        if (key >= GLFW.GLFW_KEY_KP_0 && key <= GLFW.GLFW_KEY_KP_9) {
            return 0xffb0 + key - GLFW.GLFW_KEY_KP_0;
        }
        if (key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_GRAVE_ACCENT) {
            return key;
        }
        return switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> 0xff1b;
            case GLFW.GLFW_KEY_ENTER -> 0xff0d;
            case GLFW.GLFW_KEY_TAB -> 0xff09;
            case GLFW.GLFW_KEY_BACKSPACE -> 0xff08;
            case GLFW.GLFW_KEY_INSERT -> 0xff63;
            case GLFW.GLFW_KEY_DELETE -> 0xffff;
            case GLFW.GLFW_KEY_RIGHT -> 0xff53;
            case GLFW.GLFW_KEY_LEFT -> 0xff51;
            case GLFW.GLFW_KEY_DOWN -> 0xff54;
            case GLFW.GLFW_KEY_UP -> 0xff52;
            case GLFW.GLFW_KEY_PAGE_UP -> 0xff55;
            case GLFW.GLFW_KEY_PAGE_DOWN -> 0xff56;
            case GLFW.GLFW_KEY_HOME -> 0xff50;
            case GLFW.GLFW_KEY_END -> 0xff57;
            case GLFW.GLFW_KEY_CAPS_LOCK -> 0xffe5;
            case GLFW.GLFW_KEY_SCROLL_LOCK -> 0xff14;
            case GLFW.GLFW_KEY_NUM_LOCK -> 0xff7f;
            case GLFW.GLFW_KEY_PRINT_SCREEN -> 0xff61;
            case GLFW.GLFW_KEY_PAUSE -> 0xff13;
            case GLFW.GLFW_KEY_KP_DECIMAL -> 0xffae;
            case GLFW.GLFW_KEY_KP_DIVIDE -> 0xffaf;
            case GLFW.GLFW_KEY_KP_MULTIPLY -> 0xffaa;
            case GLFW.GLFW_KEY_KP_SUBTRACT -> 0xffad;
            case GLFW.GLFW_KEY_KP_ADD -> 0xffab;
            case GLFW.GLFW_KEY_KP_ENTER -> 0xff8d;
            case GLFW.GLFW_KEY_KP_EQUAL -> 0xffbd;
            case GLFW.GLFW_KEY_LEFT_SHIFT -> 0xffe1;
            case GLFW.GLFW_KEY_RIGHT_SHIFT -> 0xffe2;
            case GLFW.GLFW_KEY_LEFT_CONTROL -> 0xffe3;
            case GLFW.GLFW_KEY_RIGHT_CONTROL -> 0xffe4;
            case GLFW.GLFW_KEY_LEFT_ALT -> 0xffe9;
            case GLFW.GLFW_KEY_RIGHT_ALT -> 0xffea;
            case GLFW.GLFW_KEY_LEFT_SUPER -> 0xffeb;
            case GLFW.GLFW_KEY_RIGHT_SUPER -> 0xffec;
            case GLFW.GLFW_KEY_MENU -> 0xff67;
            default -> 0;
        };
    }
}
