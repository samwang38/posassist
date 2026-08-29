package com.posassist;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 預約系統上游 API 的唯讀用戶端：登入取 token，再分頁抓日期區間的預約。
 *
 * 介面形狀取自預約站自己的前端程式（src/api/login.ts、src/api/reservation.ts），
 * 不是猜的。
 *
 * 安全：只接受 https；帳號、密碼、token、完整網址一律不進 log —— 例外訊息可能夾帶
 * 這些東西，所以這裡把所有例外收乾淨，只往上報「哪一步失敗、HTTP 幾號」。
 */
public final class ReservationClient {

    private static final String LOGIN_PATH = "shopcms/admin-user-login/login";
    private static final String LIST_PATH = "shopcms/reservation-activity/reservation-user-list";

    private static final int PAGE_SIZE = 500;
    private static final int MAX_PAGES = 40;          // 上限 20000 筆，防呆
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 30000;

    private final String baseUrl;
    private final String userName;
    private final String password;

    private int lastFetchedCount;
    private int lastUndatedCount;

    public ReservationClient(String baseUrl, String userName, String password) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.userName = userName;
        this.password = password;
    }

    // -- 結果 --------------------------------------------------------------

    public static final class Reservation {
        public final String vipId;
        public final String orderNo;
        public final String productName;
        public final String status;
        public final String registeredAt;   // 原字串，直接顯示
        public final long registeredMillis; // 排序用，解析不出來是 0

        Reservation(String vipId, String orderNo, String productName,
                    String status, String registeredAt, long registeredMillis) {
            this.vipId = vipId;
            this.orderNo = orderNo;
            this.productName = productName;
            this.status = status;
            this.registeredAt = registeredAt;
            this.registeredMillis = registeredMillis;
        }
    }

    /** 抓不到時丟這個，訊息只有階段與狀態碼，不含任何機密。 */
    public static final class FetchException extends Exception {
        FetchException(String message) {
            super(message);
        }
    }

    // -- 主流程 ------------------------------------------------------------

    /**
     * 抓預約並篩出「登記日期在最近 recentDays 天內」的。
     *
     * 注意 StartTime/EndTime 篩的是**梯次期間**，不是登記日期 —— 預約站前端自己
     * 也是用「過去 180 天～未來 180 天」把所有現行梯次撈回來再自己篩。
     * 直接拿 -30 天～今天去查會幾乎撈不到東西。
     */
    public List<Reservation> fetchRecent(int recentDays, int apiWindowDays)
            throws FetchException {
        String token = login();

        Date now = new Date();
        long window = (long) apiWindowDays * 24 * 60 * 60 * 1000L;
        SimpleDateFormat stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        String start = stamp.format(new Date(now.getTime() - window));
        String end = stamp.format(new Date(now.getTime() + window));

        List<Reservation> all = new ArrayList<Reservation>();
        int total = -1;
        for (int page = 0; page < MAX_PAGES; page++) {
            String query = "?StartTime=" + enc(start)
                + "&EndTime=" + enc(end)
                + "&DeliveryMethod=2"
                + "&SkipCount=" + all.size()
                + "&MaxResultCount=" + PAGE_SIZE;

            Object envelope = getJson(LIST_PATH + query, token);
            Object data = requireData(envelope, "清單");
            Object listNode = Json.obj(data, "userReservationListOutDtos");
            if (listNode == null) {
                throw new FetchException("清單格式非預期");
            }
            if (total < 0) {
                total = Json.num(listNode, "totalCount", 0);
            }

            Object items = Json.arr(listNode, "items");
            int size = Json.size(items);
            if (size == 0) {
                break;
            }
            for (int i = 0; i < size; i++) {
                Reservation row = toReservation(Json.at(items, i));
                if (row != null) {
                    all.add(row);
                }
            }
            if (all.size() >= total) {
                break;
            }
        }

        lastFetchedCount = all.size();
        long cutoff = now.getTime() - (long) recentDays * 24 * 60 * 60 * 1000L;
        List<Reservation> recent = new ArrayList<Reservation>();
        int undated = 0;
        for (Reservation row : all) {
            if (row.registeredMillis == 0L) {
                undated++;          // 日期解析不出來就無從判斷新舊，不納入
            } else if (row.registeredMillis >= cutoff) {
                recent.add(row);
            }
        }
        lastUndatedCount = undated;
        return recent;
    }

    /** 上一次抓回來的總筆數（未依登記日期篩之前），只給 log 用。 */
    public int lastFetchedCount() {
        return lastFetchedCount;
    }

    /** 上一次因日期無法解析而略過的筆數，只給 log 用。 */
    public int lastUndatedCount() {
        return lastUndatedCount;
    }

    /**
     * 只驗登入、不抓資料 —— 給設定視窗的「測試連線」用。
     * 整包 fetchRecent 會翻完 360 天的分頁，對只想確認帳密對不對來說太重。
     * 成功回 null，失敗回可讀原因（不含帳密）。
     */
    public String probeLogin() {
        try {
            login();
            return null;
        } catch (FetchException failure) {
            return failure.getMessage();
        } catch (Throwable t) {
            return "測試失敗（" + t.getClass().getSimpleName() + "）";
        }
    }

    private String login() throws FetchException {
        String body = "{\"userName\":" + quote(userName) + ",\"password\":" + quote(password) + "}";
        Object envelope = postJson(LOGIN_PATH, body);
        Object data = requireData(envelope, "登入");
        String token = Json.str(data, "token");
        if (token.length() == 0) {
            throw new FetchException("登入回應沒有 token");
        }
        return token;
    }

    /** 檢查 {code:200, data:{...}} 外層信封。 */
    private static Object requireData(Object envelope, String stage) throws FetchException {
        if (envelope == null) {
            throw new FetchException(stage + "回應無法解析");
        }
        int code = Json.num(envelope, "code", -1);
        if (code != 200) {
            // message 由伺服器產生，可能含帳號，不放進例外
            throw new FetchException(stage + "失敗，code=" + code);
        }
        Object data = Json.obj(envelope, "data");
        if (data == null) {
            throw new FetchException(stage + "回應沒有 data");
        }
        return data;
    }

    private Reservation toReservation(Object item) {
        if (item == null) {
            return null;
        }
        String vipId = Json.str(item, "vipId");
        if (vipId.length() == 0) {
            return null;
        }
        String registeredAt = Json.str(item, "reservationTimeValue");
        return new Reservation(
            vipId,
            Json.str(item, "orderSNo"),
            Json.str(item, "productName"),
            Json.str(item, "statusName"),
            registeredAt,
            parseTime(registeredAt));
    }

    private static long parseTime(String text) {
        if (text == null || text.length() < 10) {
            return 0L;
        }
        // 實測回傳的是 yyyy/MM/dd（斜線）；連字號版一併保留，避免哪天改格式
        String[] patterns = {
            "yyyy/MM/dd HH:mm:ss", "yyyy/MM/dd HH:mm", "yyyy/MM/dd",
            "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd",
        };
        for (int i = 0; i < patterns.length; i++) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(patterns[i]);
                format.setLenient(false);
                return format.parse(text).getTime();
            } catch (Throwable ignored) {
                // 換下一個格式
            }
        }
        return 0L;
    }

    // -- HTTP --------------------------------------------------------------

    private Object postJson(String path, String body) throws FetchException {
        return call("POST", path, null, body, "登入");
    }

    private Object getJson(String path, String token) throws FetchException {
        return call("GET", path, token, null, "清單");
    }

    private Object call(String method, String path, String token, String body, String stage)
            throws FetchException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(baseUrl + path);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new FetchException("只接受 https");
            }
            connection = (HttpURLConnection) url.openConnection();
            if (connection instanceof HttpsURLConnection) {
                SSLSocketFactory socketFactory = Tls.socketFactory();
                if (socketFactory != null) {
                    ((HttpsURLConnection) connection).setSSLSocketFactory(socketFactory);
                }
            }
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(body.getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            }

            int status = connection.getResponseCode();
            if (status != 200) {
                throw new FetchException(stage + " HTTP " + status);
            }
            return Json.parse(readAll(connection.getInputStream()));
        } catch (FetchException fetchFailure) {
            throw fetchFailure;
        } catch (SSLException tlsFailure) {
            // TLS 失敗的原因（憑證鏈、協定）不含帳密，留下來才查得動
            throw new FetchException(stage + " TLS 失敗：" + rootCause(tlsFailure));
        } catch (Throwable t) {
            // 其他例外訊息可能夾帶完整網址，只留類別名稱
            throw new FetchException(stage + "連線失敗（" + t.getClass().getSimpleName() + "）");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 只驗 TLS 交握，不做登入（用 GET 打登入端點，回什麼狀態碼都算交握成功）。
     * 成功回 null，失敗回可讀原因。
     */
    public static String probeTls(String baseUrl) {
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        HttpURLConnection connection = null;
        try {
            URL url = new URL(base + LOGIN_PATH);
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                return "不是 https";
            }
            connection = (HttpURLConnection) url.openConnection();
            if (connection instanceof HttpsURLConnection) {
                SSLSocketFactory socketFactory = Tls.socketFactory();
                if (socketFactory != null) {
                    ((HttpsURLConnection) connection).setSSLSocketFactory(socketFactory);
                }
            }
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(CONNECT_TIMEOUT_MS);
            connection.getResponseCode();
            return null;
        } catch (SSLException tlsFailure) {
            return "TLS " + rootCause(tlsFailure);
        } catch (Throwable t) {
            return "連線失敗（" + t.getClass().getSimpleName() + "）";
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 取最內層原因的類別與訊息。只用在 TLS 失敗，那類訊息不含機密。 */
    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName()
            + (message == null ? "" : ": " + message);
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Throwable t) {
            return value;
        }
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", Integer.valueOf(c)));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
