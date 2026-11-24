package cn.blockforge.generated.virtualmachine.computer;

import java.util.List;

/**
 * 「一个可以装进电脑的系统镜像」。
 * 模组自带的四个系统（{@link OsType}）和玩家从自己电脑上选的 ISO（{@link CustomIso}）都实现这个接口，
 * 这样装机流程、桌面、文件管理器都不用关心系统到底是从哪来的。
 */
public interface SystemImage {
    /** 存档和网络包里的唯一 id。 */
    String getId();

    /** 界面上显示的名字。 */
    String getDisplayName();

    MediaKind getMedia();

    /** 装机要花多少 tick。 */
    int getInstallTicks();

    /** 桌面壁纸的深色 / 浅色。 */
    int getDarkColor();

    int getLightColor();

    String getVersion();

    List<AppId> getApps();

    List<OsFile> getFiles();

    List<String> getBootLines();

    /** 装机进度条下面滚动显示的「正在复制 ...」文件列表。 */
    List<String> getInstallPaths();

    String getLicenseText();

    /** 按 id 找镜像：自定义 ISO 的 id 以 custom: 开头，其余按自带系统查。 */
    static SystemImage byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        if (id.startsWith(CustomIso.ID_PREFIX)) {
            return CustomIso.byId(id);
        }
        return OsType.byId(id);
    }
}
