package com.posassist;

/**
 * 版本字串。發佈流程（發佈.command）會同時更新這裡與 GitHub Release 的 manifest，
 * 啟動器則比對本機 VERSION 檔與遠端 manifest 決定要不要更新。
 */
public final class Version {

    public static final String NAME = "1.5.9";

    /** 更新來源。公開 repo，POS 機不需要任何憑證。 */
    public static final String MANIFEST_URL =
        "https://github.com/samwang38/posassist/releases/latest/download/manifest.txt";
    public static final String JAR_URL =
        "https://github.com/samwang38/posassist/releases/latest/download/posassist.jar";

    private Version() {
    }
}
