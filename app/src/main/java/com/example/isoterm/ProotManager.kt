package com.example.isoterm

import android.content.Context
import java.io.File

/**
 * Изоляция: все пути только внутри getFilesDir().
 * proot биндит только home:/root, tmp:/tmp, rootfs/tmp:/dev/shm.
 * /proc /sys /dev виртуалит сам proot. sdcard/storage НЕ биндятся никогда.
 *
 * proot: бери termux/proot (https://github.com/termux/proot), aarch64.
 * Упаковка: jniLibs/arm64-v8a/libproot.so -> nativeLibraryDir, chmod 0700.
 * rootfs: ubuntu-base noble arm64 с https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/
 */
object ProotManager {

    const val UBUNTU_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/noble/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
    // Fallback готового rootfs под proot (UserLAnd): https://github.com/CypherpunkArmory/UserLAnd-Assets-Ubuntu/releases
    const val PROOT_STATIC_URL =
        "https://github.com/skirsten/proot-portable-android-binaries/raw/master/proot-aarch64-static"

    fun filesDir(c: Context): File = c.filesDir
    fun rootfsDir(c: Context): File = File(filesDir(c), "ubuntu-noble-arm64")
    fun homeDir(c: Context): File = File(filesDir(c), "home").apply { mkdirs() }
    fun tmpDir(c: Context): File = File(filesDir(c), "tmp").apply { mkdirs() }
    fun prootFile(c: Context): File = File(filesDir(c), "proot")

    fun isInstalled(c: Context): Boolean {
        val r = rootfsDir(c)
        return r.isDirectory && File(r, "bin/bash").exists() && prootFile(c).canExecute()
    }

    /** Ищет proot: 1) files/proot 2) nativeLibraryDir/libproot.so 3) скачать static. */
    fun ensureProot(c: Context): File {
        val dst = prootFile(c)
        if (dst.canExecute()) return dst
        // 1) из nativeLibraryDir (положи proot как jniLibs/arm64-v8a/libproot.so)
        val native = File(c.applicationInfo.nativeLibraryDir, "libproot.so")
        if (native.exists()) {
            native.copyTo(dst, overwrite = true)
            dst.setExecutable(true)
            Runtime.getRuntime().exec(arrayOf("chmod", "0700", dst.absolutePath)).waitFor()
            return dst
        }
        // 2) скачать static — вызывается из корутины/потока, не из UI
        downloadToFile(PROOT_STATIC_URL, dst)
        dst.setExecutable(true)
        Runtime.getRuntime().exec(arrayOf("chmod", "0700", dst.absolutePath)).waitFor()
        return dst
    }

    /**
     * Строит команду proot. Только внутренние бинды.
     * Аналог `proot-distro login --isolated` (https://github.com/termux/proot-distro).
     */
    fun buildCmd(c: Context, prootBin: String): Array<String> {
        val rootfs = rootfsDir(c).absolutePath
        val home = homeDir(c).absolutePath
        val tmp = tmpDir(c).absolutePath
        return arrayOf(
            prootBin,
            "--link2symlink",
            "--kill-on-exit",
            "--kernel-release=6.1.0-fake",
            "--sysvipc",
            "-0",
            "-r", rootfs,
            "-b", "$home:/root",
            "-b", "$tmp:/tmp",
            "-b", "$rootfs/tmp:/dev/shm",
            "-w", "/root",
            "/bin/bash", "--login"
        )
    }

    fun buildEnv(c: Context): Array<String> {
        // env -i аналог: ничего хостового (LD_*, PROOT_*) не наследуем
        return arrayOf(
            "HOME=/root",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp",
            "container=proot-distro"
        )
    }

    fun downloadToFile(url: String, dst: File) {
        dst.parentFile?.mkdirs()
        val tmp = File(dst.parent, dst.name + ".part")
        java.net.URL(url).openStream().use { input ->
            tmp.outputStream().use { out -> input.copyTo(out) }
        }
        if (dst.exists()) dst.delete()
        tmp.renameTo(dst)
    }

    /** Распаковка ubuntu-base в files/. Требует /system/bin/tar (есть на Xiaomi). */
    fun unpackRootfs(tarGz: File, rootfs: File, log: (String) -> Unit): Int {
        rootfs.mkdirs()
        // ubuntu-base — tar.gz с пермишенами, распаковываем как есть
        val p = ProcessBuilder("tar", "xzpf", tarGz.absolutePath, "-C", rootfs.absolutePath)
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        log(out)
        val code = p.waitFor()
        // минимальный resolv.conf + dev/shm заглушка
        try {
            File(rootfs, "etc/resolv.conf").apply {
                parentFile?.mkdirs()
                writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            }
        } catch (_: Exception) {}
        return code
    }
}
