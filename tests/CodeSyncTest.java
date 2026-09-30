package com.posassist;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/** 兩台機器共用一個同步資料夾：拉取、推送、衝突、離線待上傳、釘選合併、壞檔不收。不需要 EPB。 */
public final class CodeSyncTest {
    private static int checks;

    static void check(boolean ok, String name) {
        checks++;
        if (!ok) throw new AssertionError(name);
    }

    static File root;

    static File dir(String name) {
        return new File(root, name);
    }

    static List<CodeItem> items(String... lines) {
        List<CodeItem> items = new java.util.ArrayList<CodeItem>();
        for (String line : lines) {
            boolean pinned = line.endsWith("*");
            items.add(CodeItem.parse(pinned ? line.substring(0, line.length() - 1) : line).withPinned(pinned));
        }
        return items;
    }

    static String text(List<CodeItem> items) {
        StringBuilder out = new StringBuilder();
        for (CodeItem item : items) out.append(item.toLine()).append(item.pinned ? "*" : "").append('\n');
        return out.toString();
    }

    static String local(File dir) {
        return text(CodeStore.loadFrom(dir));
    }

    static void write(File file, String content) throws Exception {
        FileOutputStream out = new FileOutputStream(file);
        try { out.write(content.getBytes("UTF-8")); } finally { out.close(); }
    }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("posassist-sync").toFile();
        System.setProperty("posassist.logDir", dir("logs").getPath());
        pushAndPull();
        conflict();
        offline();
        offlineConflict();
        pins();
        badRemote();
        System.out.println("CodeSyncTest: " + checks + " checks passed");
    }

    static void pushAndPull() {
        File remote = dir("r1");
        remote.mkdirs();
        CodeSync a = new CodeSync(remote, dir("a1")), b = new CodeSync(remote, dir("b1"));
        check(b.pull().outcome == CodeSync.Outcome.UNCHANGED, "empty folder is not an update");
        List<CodeItem> list = items("常用|紙袋|001", "配件/袋類|提袋|002*");
        check(a.push(list, a.syncedFingerprint(), false).outcome == CodeSync.Outcome.SAVED, "first push to empty folder");
        check(new File(remote, "codes.txt").isFile() && new File(remote, "codes.pins.txt").isFile(), "remote files written");
        check(local(dir("a1")).equals(text(list)), "pusher's local cache saved");
        CodeSync.Result pulled = b.pull();
        check(pulled.outcome == CodeSync.Outcome.UPDATED && pulled.localChanged(), "other machine pulls");
        check(local(dir("b1")).equals(text(list)), "pulled codes, subs and pins match");
        check(b.pull().outcome == CodeSync.Outcome.UNCHANGED, "second pull unchanged");
        check(a.pull().outcome == CodeSync.Outcome.UNCHANGED, "own push is not re-pulled");

        new File(remote, "codes.txt").delete();
        check(b.pull().outcome == CodeSync.Outcome.UNCHANGED && local(dir("b1")).equals(text(list)), "missing remote file never wipes local");
    }

    static void conflict() {
        File remote = dir("r2");
        remote.mkdirs();
        CodeSync a = new CodeSync(remote, dir("a2")), b = new CodeSync(remote, dir("b2"));
        a.push(items("常用|紙袋|001"), a.syncedFingerprint(), false);
        b.pull();
        String bBase = b.syncedFingerprint();
        check(a.push(items("常用|紙袋|001", "常用|A 加的|003"), a.syncedFingerprint(), false).outcome == CodeSync.Outcome.SAVED, "A saves");
        CodeSync.Result result = b.push(items("常用|B 改的|001"), bBase, false);
        check(result.outcome == CodeSync.Outcome.CONFLICT, "B gets conflict");
        check(text(CodeStore.loadFrom(remote)).contains("A 加的") && !local(dir("b2")).contains("B 改的"), "conflict writes nothing");
        check(b.push(items("常用|B 改的|001"), bBase, true).outcome == CodeSync.Outcome.SAVED, "B overwrites when forced");
        check(a.pull().outcome == CodeSync.Outcome.UPDATED && local(dir("a2")).contains("B 改的"), "A follows forced write");
    }

    static void offline() throws Exception {
        File remote = dir("r3");
        CodeSync a = new CodeSync(remote, dir("a3"));
        CodeSync.Result result = a.push(items("常用|紙袋|001"), a.syncedFingerprint(), false);
        check(result.outcome == CodeSync.Outcome.OFFLINE && a.hasPending(), "unreachable folder saves locally as pending");
        check(local(dir("a3")).contains("紙袋"), "pending edit kept locally");
        check(a.pull().outcome == CodeSync.Outcome.OFFLINE, "still offline");
        remote.mkdirs();
        check(a.pull().outcome == CodeSync.Outcome.SAVED && !a.hasPending(), "pending uploaded once reachable");
        check(text(CodeStore.loadFrom(remote)).contains("紙袋"), "remote received pending edit");
    }

    static void offlineConflict() throws Exception {
        File remote = dir("r4"), moved = dir("r4-away");
        remote.mkdirs();
        CodeSync a = new CodeSync(remote, dir("a4")), b = new CodeSync(remote, dir("b4"));
        a.push(items("常用|紙袋|001"), a.syncedFingerprint(), false);
        b.pull();
        check(remote.renameTo(moved), "take folder offline");
        check(a.push(items("常用|A 離線改|001"), a.syncedFingerprint(), false).outcome == CodeSync.Outcome.OFFLINE, "A edits offline");
        check(moved.renameTo(remote), "folder back");
        check(b.push(items("常用|B 線上改|001"), b.syncedFingerprint(), false).outcome == CodeSync.Outcome.SAVED, "B edits online meanwhile");
        CodeSync.Result result = a.pull();
        check(result.outcome == CodeSync.Outcome.CONFLICT && !a.hasPending(), "A's pending edit conflicts");
        check(local(dir("a4")).contains("B 線上改"), "folder version wins");
        File[] kept = dir("a4").listFiles((d, name) -> name.startsWith("codes.txt.conflict-"));
        check(kept != null && kept.length == 1 && new String(Files.readAllBytes(kept[0].toPath()), "UTF-8").contains("A 離線改"), "A's version kept aside");
        check(text(CodeStore.loadFrom(remote)).contains("B 線上改"), "remote untouched by conflict");
    }

    static void pins() {
        File remote = dir("r5");
        remote.mkdirs();
        CodeSync a = new CodeSync(remote, dir("a5")), b = new CodeSync(remote, dir("b5"));
        a.push(items("常用|紙袋|001", "常用|提袋|002", "常用|包膜|003"), a.syncedFingerprint(), false);
        b.pull();
        check(a.setPinned("001", true).outcome == CodeSync.Outcome.SAVED, "A pins 001");
        check(b.setPinned("003", true).outcome == CodeSync.Outcome.SAVED, "B pins 003 without pulling first");
        check(CodeStore.loadPinsFrom(remote).equals(new java.util.LinkedHashSet<String>(Arrays.asList("001", "003"))), "both pins kept");
        check(CodeStore.loadPinsFrom(dir("b5")).size() == 2, "B local has both pins");
        a.pull();
        check(CodeStore.loadPinsFrom(dir("a5")).size() == 2, "A follows");
        check(a.setPinned("001", false).outcome == CodeSync.Outcome.SAVED && !CodeStore.loadPinsFrom(remote).contains("001"), "unpin");
    }

    static void badRemote() throws Exception {
        File remote = dir("r6");
        remote.mkdirs();
        CodeSync a = new CodeSync(remote, dir("a6")), b = new CodeSync(remote, dir("b6"));
        a.push(items("常用|紙袋|001"), a.syncedFingerprint(), false);
        b.pull();
        write(new File(remote, "codes.txt"), "常用|紙袋|001\n這行壞掉\n");
        CodeSync.Result result = b.pull();
        check(result.outcome == CodeSync.Outcome.FAILED && local(dir("b6")).equals(text(items("常用|紙袋|001"))), "invalid remote not adopted");
        check(CodeSync.invalid("# 註解\n\n常用|紙袋|001\n".getBytes("UTF-8")) == null, "comments and blanks are fine");
    }
}
