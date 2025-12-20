package com.example.servermonitor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DiscordClient {
    private final String webhookUrl;
    private final HttpClient client;
    private static final Pattern ID_PATTERN = Pattern.compile("\"id\"\s*:\s*\"(\\d+)\"");

    public DiscordClient(String webhookUrl) {
        this.webhookUrl = webhookUrl;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public String createMessage(String jsonBody) throws IOException, InterruptedException {
        String url = webhookUrl + "?wait=true";
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            Matcher m = ID_PATTERN.matcher(response.body());
            if (m.find()) return m.group(1);
        }
        throw new IOException("Failed to create message: " + response.statusCode());
    }

    public boolean editMessage(String messageId, String jsonBody) throws IOException, InterruptedException {
        String url = webhookUrl + "/messages/" + messageId;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }
}

