package me.zed_0xff.zb_exhume_41;

import me.zed_0xff.zombie_buddy.Patch;

import java.nio.file.Files;
import java.nio.file.Path;

@Patch(className = "zombie.ZomboidFileSystem", methodName = "getModVersionDirName")
public class Patch_ZomboidFileSystem {
    public static boolean _tracePrinted = false;

    @Patch.OnExit
    public static void exit(Path modDir, @Patch.Return(readOnly = false) String result) {
        if (
                !Files.isDirectory(modDir.resolve(result)) && 
                !Files.isDirectory(modDir.resolve("common")) &&
                Files.exists(modDir.resolve("mod.info"))
                ) {
            result = "";
        }
    }
}
