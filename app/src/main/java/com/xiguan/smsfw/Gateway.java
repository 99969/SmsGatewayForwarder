package com.xiguan.smsfw;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * cmcc-message-gateway 的 HTTP 客户端。
 * <p>
 * 网关路由：{@code POST /push}，JSON body {@code {"title":"...","body":"..."}}；
 * 鉴权支持 {@code Authorization: Bearer}、{@code X-API-Key}、
 * {@code ?token=}、{@code ?key=}，这里用最简单的 {@code ?token=}。
 * 另外还有 {@code GET /health} 可用于连通性自检。
 * <p>
 * 只用 JDK 自带的 HttpURLConnection，不引入 OkHttp / Retrofit，减少依赖与体积。
 */
public final class Gateway {

    public static final class Result {
        public final boolean ok;
        public final int code;
        public final String body;

        Result(boolean ok, int code, String body) {
            this.ok = ok;
            this.code = code;
            this.body = body;
        }

        public String summary() {
            if (code < 0) {
                return "失败: " + body;
            }
            return (ok ? "成功 " : "失败 ") + "HTTP " + code
                    + (body == null || body.isEmpty() ? "" : " · " + trim(body, 200));
        }
    }

    private Gateway() {
    }

    public static Result push(Settings s, String title, String body) {
        HttpURLConnection conn = null;
        try {
            String url = buildPushUrl(s.gateway, s.token);
            byte[] payload = buildJson(title, body).getBytes("UTF-8");

            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(s.timeoutMs);
            conn.setReadTimeout(s.timeoutMs);
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            if (s.token != null && !s.token.trim().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + s.token.trim());
            }

            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
            String resp = readAll(is);
            boolean ok = code >= 200 && code < 300;
            XLog.i("推送 " + (ok ? "成功" : "失败") + " HTTP " + code + " -> " + trim(resp, 300));
            return new Result(ok, code, resp);
        } catch (Throwable t) {
            XLog.e("推送异常", t);
            return new Result(false, -1, t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 连通性检查：GET {base}/health */
    public static Result health(Settings s) {
        HttpURLConnection conn = null;
        try {
            String base = normalizeBase(s.gateway);
            conn = (HttpURLConnection) new URL(base + "/health").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(s.timeoutMs);
            conn.setReadTimeout(s.timeoutMs);
            int code = conn.getResponseCode();
            String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            return new Result(code >= 200 && code < 300, code, resp);
        } catch (Throwable t) {
            return new Result(false, -1, t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /* ---------------- 内部工具 ---------------- */

    static String normalizeBase(String gateway) {
        String b = gateway == null ? "" : gateway.trim();
        if (b.isEmpty()) {
            b = Const.DEF_GATEWAY;
        }
        if (!b.startsWith("http://") && !b.startsWith("https://")) {
            b = "https://" + b;
        }
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        // 用户可能已经把 /push 写进地址里
        if (b.endsWith("/push")) {
            b = b.substring(0, b.length() - "/push".length());
        }
        return b;
    }

    static String buildPushUrl(String gateway, String token) throws Exception {
        String url = normalizeBase(gateway) + "/push";
        String t = token == null ? "" : token.trim();
        if (!t.isEmpty()) {
            url = url + "?token=" + URLEncoder.encode(t, "UTF-8");
        }
        return url;
    }

    static String buildJson(String title, String body) {
        return "{\"title\":\"" + escape(title) + "\",\"body\":\"" + escape(body) + "\"}";
    }

    static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 32);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    static String readAll(InputStream is) {
        if (is == null) {
            return "";
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                is.close();
            } catch (Throwable ignored) {
            }
        }
    }

    static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        s = s.replace('\n', ' ').replace('\r', ' ').trim();
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
