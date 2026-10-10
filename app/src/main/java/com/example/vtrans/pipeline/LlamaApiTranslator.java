package com.example.vtrans.pipeline;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 通过 llama.cpp 的 OpenAI 兼容 API 进行英→中翻译。
 *
 * <p>性能要点：
 * <ul>
 *   <li>提示前缀放在 system 消息里，服务端可用 --cache-reuse 复用 KV，跳过大部分 prefill。</li>
 *   <li>连接不显式 disconnect，交由 JVM 的 keep-alive 池复用底层 socket，省 TCP 握手。</li>
 * </ul>
 */
public final class LlamaApiTranslator {

    private static final String TAG = "LlamaApiTranslator";
    /** 固定 system 提示：让 llama.cpp 侧 KV cache 命中 */
    private static final String SYSTEM_PROMPT = "将下面的英文翻译成中文，只输出译文，不要解释：";

    private final String baseUrl;

    public LlamaApiTranslator(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        this.baseUrl = baseUrl;
    }

    /**
     * 翻译单句英文为中文。
     * @return 译文；失败返回 null
     */
    public String translate(String text) {
        if (text == null || text.trim().isEmpty()) return null;

        String apiUrl = baseUrl + "/v1/chat/completions";
        HttpURLConnection conn = null;

        try {
            URL url = new URL(apiUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            // 显式声明 keep-alive：Android 默认 http.keepAlive=true，但加上请求头更稳
            conn.setRequestProperty("Connection", "keep-alive");
            conn.setUseCaches(false);
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(30000);

            String jsonBody = buildRequestBody(text);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.e(TAG, "API 返回 HTTP " + code);
                drainQuietly(conn);
                return null;
            }

            StringBuilder response = new StringBuilder();
            // BufferedInputStream 保证 JVM 能识别流已完整读完，keep-alive 才会真正复用 socket
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new BufferedInputStream(conn.getInputStream()),
                            StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                while ((n = br.read(buf)) > 0) {
                    response.append(buf, 0, n);
                }
            }

            String result = extractContent(response.toString());
            if (result != null) result = result.trim();
            return (result == null || result.isEmpty()) ? null : result;

        } catch (Exception e) {
            Log.e(TAG, "翻译请求失败", e);
            return null;
        } finally {
            // 不调用 conn.disconnect()：那会强制关闭底层 socket 破坏 keep-alive。
            // 让 JVM 的连接池管理；异常路径显式 drain 保证流被读完。
            if (conn != null) drainQuietly(conn);
        }
    }

    /**
     * 批量翻译（逐句顺序调用，保持顺序一致性）。
     */
    public String[] translateBatch(String[] texts) {
        if (texts == null || texts.length == 0) return null;
        String[] results = new String[texts.length];
        for (int i = 0; i < texts.length; i++) {
            results[i] = translate(texts[i]);
        }
        return results;
    }

    private String buildRequestBody(String userText) {
        // system 消息携带固定前缀；user 只放净文本，最大化服务端 KV 复用
        return "{\"model\":\"local\","
                + "\"messages\":["
                + "{\"role\":\"system\",\"content\":\"" + escapeJson(SYSTEM_PROMPT) + "\"},"
                + "{\"role\":\"user\",\"content\":\"" + escapeJson(userText) + "\"}"
                + "],"
                + "\"temperature\":0,\"max_tokens\":512}";
    }

    private static void drainQuietly(HttpURLConnection conn) {
        try {
            java.io.InputStream in = conn.getErrorStream();
            if (in == null) in = conn.getInputStream();
            if (in != null) {
                byte[] b = new byte[1024];
                while (in.read(b) > 0) { /* discard */ }
                in.close();
            }
        } catch (Exception ignored) {
            // no-op
        }
    }

    /**
     * 从 JSON 响应中提取 choices[0].message.content。
     * 简单字符串查找，不依赖 JSON 库。
     */
    private String extractContent(String json) {
        String key = "\"content\":\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        idx += key.length();

        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = idx; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'u': {
                        if (i + 4 < json.length()) {
                            String hex = json.substring(i + 1, i + 5);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append('u').append(hex);
                                i += 4;
                            }
                        }
                        break;
                    }
                    default: sb.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
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
}
