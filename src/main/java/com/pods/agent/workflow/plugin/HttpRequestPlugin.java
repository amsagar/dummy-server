package com.pods.agent.workflow.plugin;

import tools.jackson.databind.ObjectMapper;
import com.pods.agent.workflow.joget.plugin.ApplicationPlugin;
import com.pods.agent.workflow.plugin.descriptor.DescribablePlugin;
import com.pods.agent.workflow.plugin.descriptor.PluginDescriptor;
import com.pods.agent.workflow.plugin.descriptor.PluginPropertyDescriptor;
import com.pods.agent.workflow.plugin.descriptor.PluginPropertyDescriptor.Option;
import com.pods.agent.workflow.plugin.descriptor.PluginPropertyDescriptor.Props;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Workflow plugin that issues an HTTP request and returns the response.
 *
 * <p>Properties:
 * <ul>
 *   <li>{@code method} (optional, default {@code GET}).</li>
 *   <li>{@code url} (required).</li>
 *   <li>{@code headers} (optional) — Map of header name to value.</li>
 *   <li>{@code body} (optional) — String, or a Map/List that will be
 *       serialized as JSON.</li>
 *   <li>{@code timeoutMs} (optional, default 30000).</li>
 * </ul>
 *
 * <p>Output: a Map with {@code status}, {@code headers}, {@code body}.
 * Non-2xx responses are NOT thrown — the plugin returns the response so the
 * activity graph can branch on the status code.
 */
@Component
@Slf4j
public class HttpRequestPlugin implements ApplicationPlugin, DescribablePlugin {

    @Override
    public PluginDescriptor describe() {
        return PluginDescriptor.of(
                "HttpRequestPlugin",
                "HTTP Request",
                "Performs an HTTP request and returns the response. Non-2xx responses are returned, not thrown.",
                "globe",
                "Tool",
                List.of(
                        Props.options("method", "Method", false, "GET", List.of(
                                Option.of("GET"), Option.of("POST"), Option.of("PUT"),
                                Option.of("PATCH"), Option.of("DELETE"))),
                        Props.string("url", "URL", true)
                                .withDescription("Target URL. Supports SecureSpel expressions, e.g. #{'https://api/' + #region}")
                                .withPlaceholder("https://api.example.com/resource"),
                        Props.collection("headers", "Headers", List.of(
                                Props.string("name", "Name", true),
                                Props.string("value", "Value", true))),
                        Props.json("body", "Body", false)
                                .withDescription("JSON object or raw string. Maps are sent as application/json.")
                                .visibleWhen("method", "POST", "PUT", "PATCH"),
                        Props.number("timeoutMs", "Timeout (ms)", false, 30000)
                ));
    }


    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper;

    public HttpRequestPlugin(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Object execute(Map<String, Object> props) {
        String method = stringOrDefault(props.get("method"), "GET").toUpperCase();
        String url = stringRequired(props, "url");
        long timeoutMs = longOrDefault(props.get("timeoutMs"), 30_000L);

        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs));

        Object headersObj = props.get("headers");
        if (headersObj instanceof Map<?, ?> hm) {
            for (Map.Entry<?, ?> e : hm.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    rb.header(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }

        HttpRequest.BodyPublisher body = bodyPublisher(props.get("body"));
        rb.method(method, body);

        try {
            HttpResponse<String> resp = httpClient.send(rb.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", resp.statusCode());
            result.put("headers", resp.headers().map());
            result.put("body", resp.body());
            return result;
        } catch (Exception e) {
            throw new RuntimeException("HTTP request failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage(), e);
        }
    }

    private HttpRequest.BodyPublisher bodyPublisher(Object bodyObj) {
        if (bodyObj == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        if (bodyObj instanceof String s) {
            return HttpRequest.BodyPublishers.ofString(s, StandardCharsets.UTF_8);
        }
        try {
            String json = objectMapper.writeValueAsString(bodyObj);
            return HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("HttpRequestPlugin could not serialize body: "
                    + e.getMessage(), e);
        }
    }

    private static String stringRequired(Map<String, Object> props, String key) {
        Object v = props.get(key);
        if (v == null) {
            throw new IllegalArgumentException("HttpRequestPlugin requires '" + key + "' property");
        }
        return String.valueOf(v);
    }

    private static String stringOrDefault(Object v, String def) {
        return v == null ? def : String.valueOf(v);
    }

    private static long longOrDefault(Object v, long def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
