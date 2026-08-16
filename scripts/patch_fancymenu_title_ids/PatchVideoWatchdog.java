import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Minecraft 26.2 does not call FancyMenu extractRenderState every title frame, so the
 * native-video watchdog treats the menu as idle and kills the MP4 after 11s.
 * Skip that auto-clear while the title is still the active screen (pausedBySystem=false).
 */
public final class PatchVideoWatchdog {
    static final String OWNER =
            "de/keksuccino/fancymenu/customization/background/backgrounds/video/nativevideo/NativeVideoMenuBackground";
    static final String ENTRY = OWNER + ".class";

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: PatchVideoWatchdog <fancymenu.jar>");
        }
        Path jar = Path.of(args[0]);
        Path tmp = jar.resolveSibling(jar.getFileName().toString() + ".watchdog.tmp");
        byte[] src;
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry(ENTRY);
            if (e == null) {
                throw new IllegalStateException("missing " + ENTRY);
            }
            src = zf.getInputStream(e).readAllBytes();
        }
        byte[] patched = patch(src);
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(jar));
                ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(tmp))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                ZipEntry ne = new ZipEntry(e.getName());
                ne.setTime(e.getTime());
                zout.putNextEntry(ne);
                if (ENTRY.equals(e.getName())) {
                    zout.write(patched);
                } else {
                    zin.transferTo(zout);
                }
                zout.closeEntry();
            }
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        System.out.println("patched watchdog skip: " + jar);
    }

    static byte[] patch(byte[] src) {
        ClassReader cr = new ClassReader(src);
        ClassWriter cw = new ClassWriter(cr, 0);
        final boolean[] touched = {false};
        cr.accept(
                new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                        if (!"shouldSkipWatchdogAutoClear".equals(name) || !"()Z".equals(desc)) {
                            return mv;
                        }
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                visitVarInsn(Opcodes.ALOAD, 0);
                                visitFieldInsn(Opcodes.GETFIELD, OWNER, "pausedBySystem", "Z");
                                Label cont = new Label();
                                visitJumpInsn(Opcodes.IFNE, cont);
                                visitInsn(Opcodes.ICONST_1);
                                visitInsn(Opcodes.IRETURN);
                                visitLabel(cont);
                                visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                                touched[0] = true;
                            }
                        };
                    }
                },
                0);
        if (!touched[0]) {
            throw new IllegalStateException("shouldSkipWatchdogAutoClear not found");
        }
        return cw.toByteArray();
    }
}
