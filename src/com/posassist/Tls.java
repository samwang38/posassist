package com.posassist;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * 只給預約連線用的 TLS 設定。
 *
 * 為什麼需要：EPB 綁的 Java 8u251，cacerts 裡一張 TWCA 根憑證都沒有（總共只有 95 張），
 * 而預約系統的憑證鏈是
 *   *.studioa.com.tw → TWCA SSL CA → TWCA CYBER Root CA → TWCA Global Root CA
 * 少了最上面那張就會 PKIX path building failed。curl 通得過是因為它走 macOS 的信任庫。
 *
 * 作法：**在** JVM 預設信任之外**多加**幾張根憑證，兩邊都做完整的 PKIX 驗證，
 * 任一邊過就算過。不是關掉驗證，也不改 JVM 或系統的信任設定 ——
 * 這條連線帶著門市帳密，絕不能用 trust-all。
 */
public final class Tls {

    private static final String BUNDLED = "/com/posassist/trust/twca-global-root.pem";
    private static final String EXTRA_DIR = "config/extra-ca";

    private static boolean resolved;
    private static SSLSocketFactory factory;

    private Tls() {
    }

    /** 取得含額外根憑證的 factory；沒有額外憑證或建不起來就回 null（呼叫端用預設）。 */
    public static synchronized SSLSocketFactory socketFactory() {
        if (resolved) {
            return factory;
        }
        resolved = true;
        try {
            List<X509Certificate> extras = loadExtraCertificates();
            if (extras.isEmpty()) {
                return null;
            }
            X509TrustManager defaultManager = defaultTrustManager();
            X509TrustManager extraManager = trustManagerFor(extras);
            if (defaultManager == null || extraManager == null) {
                return null;
            }
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null,
                new TrustManager[] { new EitherTrustManager(defaultManager, extraManager) },
                null);
            factory = context.getSocketFactory();
            PosLog.info("預約連線已加入 " + extras.size() + " 張額外根憑證");
        } catch (Throwable t) {
            PosLog.warn("建立 TLS 設定失敗，改用 JVM 預設（" + t.getClass().getSimpleName() + "）");
            factory = null;
        }
        return factory;
    }

    // -- 憑證來源 ----------------------------------------------------------

    private static List<X509Certificate> loadExtraCertificates() {
        List<X509Certificate> all = new ArrayList<X509Certificate>();
        readInto(all, Tls.class.getResourceAsStream(BUNDLED), "內建");

        // 之後若有別的門市走不同憑證鏈，把 PEM 丟進這個目錄就好，不必改程式
        File dir = new File(home(), EXTRA_DIR);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase().endsWith(".pem")) {
                    try {
                        readInto(all, new FileInputStream(file), file.getName());
                    } catch (Throwable t) {
                        PosLog.warn("讀不到額外憑證：" + file.getName());
                    }
                }
            }
        }
        return all;
    }

    private static void readInto(List<X509Certificate> target, InputStream in, String label) {
        if (in == null) {
            return;
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> parsed = cf.generateCertificates(in);
            for (Certificate certificate : parsed) {
                if (certificate instanceof X509Certificate) {
                    target.add((X509Certificate) certificate);
                }
            }
        } catch (Throwable t) {
            PosLog.warn("憑證解析失敗：" + label);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
                // 關不掉就算了
            }
        }
    }

    // -- TrustManager ------------------------------------------------------

    private static X509TrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory tmf =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return firstX509(tmf.getTrustManagers());
    }

    private static X509TrustManager trustManagerFor(List<X509Certificate> certificates)
            throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        for (int i = 0; i < certificates.size(); i++) {
            store.setCertificateEntry("extra-" + i, certificates.get(i));
        }
        TrustManagerFactory tmf =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        return firstX509(tmf.getTrustManagers());
    }

    private static X509TrustManager firstX509(TrustManager[] managers) {
        if (managers == null) {
            return null;
        }
        for (int i = 0; i < managers.length; i++) {
            if (managers[i] instanceof X509TrustManager) {
                return (X509TrustManager) managers[i];
            }
        }
        return null;
    }

    /** 預設信任先驗；不過再用額外根憑證驗一次。兩邊都是完整 PKIX 驗證。 */
    private static final class EitherTrustManager implements X509TrustManager {
        private final X509TrustManager primary;
        private final X509TrustManager secondary;

        EitherTrustManager(X509TrustManager primary, X509TrustManager secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            try {
                primary.checkServerTrusted(chain, authType);
            } catch (CertificateException notInDefault) {
                secondary.checkServerTrusted(chain, authType);
            }
        }

        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            primary.checkClientTrusted(chain, authType);
        }

        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] a = primary.getAcceptedIssuers();
            X509Certificate[] b = secondary.getAcceptedIssuers();
            X509Certificate[] all = new X509Certificate[a.length + b.length];
            System.arraycopy(a, 0, all, 0, a.length);
            System.arraycopy(b, 0, all, a.length, b.length);
            return all;
        }
    }

    /** 跟 PosLog / ReservationCache 用同一套路徑推算。 */
    private static File home() {
        String logDir = System.getProperty("posassist.logDir");
        if (logDir != null && logDir.trim().length() != 0) {
            return new File(logDir.trim()).getParentFile();
        }
        return new File(System.getProperty("user.dir"), "../PosAssist");
    }
}
