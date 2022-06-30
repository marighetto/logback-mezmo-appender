package com.marighetto.logback;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class Appender extends UnsynchronizedAppenderBase<ILoggingEvent> {
  // Utilities
  private final ObjectMapper objectMapper;
  private final Logger logger;

  // Connection data
  private String ingestUrl;
  private String ingestKey;
  // Parameters
  private String hostname;
  private String appname;
  private String macAddress;
  private String ipAddress;
  // Connection config
  private int connectionTimeout = 5000;
  private int readTimeout = 10000;

  public Appender() {
    this.logger = LoggerFactory.getLogger(Appender.class);

    this.objectMapper =
        new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .setPropertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE);

    try {
      this.ipAddress = getIpAddress();
      this.macAddress = getMacAddress();

    } catch (Exception e) {
      logger.error("com.marighetto.logback.Appender : {}", e.getMessage());
    }
  }

  protected HttpURLConnection buildHttpConnection() throws Exception {

    HttpURLConnection conn =
        (HttpURLConnection)
            new URL(
                    String.format(
                        "%s?hostname=%s&&ip=%s&&now=%s",
                        this.ingestUrl,
                        this.hostname,
                        this.ipAddress,
                        Instant.now().getEpochSecond()))
                .openConnection();

    conn.setRequestProperty("User-Agent", this.hostname);
    conn.setRequestProperty("Accept", "application/json");
    conn.setRequestProperty("Content-Type", "application/json");
    conn.setRequestProperty("Charset", "UTF-8");
    conn.setRequestProperty("apikey", this.ingestKey);
    conn.setConnectTimeout(this.connectionTimeout);
    conn.setReadTimeout(this.readTimeout);
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setDoInput(true);
    return conn;
  }

  @Override
  protected void append(ILoggingEvent iLoggingEvent) {
    if (iLoggingEvent.getLoggerName().equals(Appender.class.getName())) {
      return;
    }

    try {
      HttpURLConnection httpConnection = buildHttpConnection();

      httpConnection.connect();

      try (OutputStream os = httpConnection.getOutputStream()) {
        byte[] input = buildPostData(iLoggingEvent).getBytes(StandardCharsets.UTF_8);
        os.write(input, 0, input.length);
        os.flush();
      }

      if (httpConnection.getResponseCode() != 200) {
        logger.error(
            "com.marighetto.logback.Appender : ({}) {} , {}",
            httpConnection.getResponseCode(),
            httpConnection.getResponseMessage(),
            new BufferedReader(new InputStreamReader(httpConnection.getErrorStream()))
                .lines()
                .collect(Collectors.joining("\n")));
      }

      httpConnection.disconnect();
    } catch (Exception e) {
      logger.error("com.marighetto.logback.Appender : {}", e.getMessage());
    }
  }

  protected String buildPostData(ILoggingEvent iLoggingEvent) throws Exception {
    Map<String, Object> postData = new HashMap<>();

    postData.put("timestamp", iLoggingEvent.getTimeStamp());
    postData.put("line", iLoggingEvent.getFormattedMessage());
    postData.put("app", this.appname);
    postData.put("level", iLoggingEvent.getLevel().toString());
    postData.put("meta", buildMetaData(iLoggingEvent));

    List<Object> lines = new ArrayList<>();
    lines.add(postData);

    Map<String, Object> body = new HashMap<>();
    body.put("lines", lines);

    return this.objectMapper.writeValueAsString(body);
  }

  protected String buildMetaData(ILoggingEvent iLoggingEvent) throws Exception {
    Map<String, Object> metaData = new HashMap<>();

    metaData.put("ip", this.ipAddress);
    metaData.put("mac", this.macAddress);
    metaData.put("app", this.appname);
    metaData.put("thread", iLoggingEvent.getThreadName());

    if (iLoggingEvent.hasCallerData()) {
      StackTraceElement[] callerData = iLoggingEvent.getCallerData();

      if (callerData.length > 0) {
        StackTraceElement callerContext = callerData[0];

        metaData.put("class", callerContext.getClassName());
        metaData.put("method", callerContext.getMethodName());
        metaData.put("file", callerContext.getFileName());
        metaData.put("line", callerContext.getLineNumber());
      }
    }

    return this.objectMapper.writeValueAsString(metaData);
  }

  protected String getIpAddress() throws Exception {
    URL checker = new URL("https://checkip.amazonaws.com");

    try (BufferedReader in = new BufferedReader(new InputStreamReader(checker.openStream()))) {

      String ipAddress = in.readLine();
      in.close();

      return ipAddress;
    }
  }

  protected String getMacAddress() throws Exception {
    InetAddress localHost = InetAddress.getLocalHost();
    NetworkInterface ni = NetworkInterface.getByInetAddress(localHost);
    byte[] hardwareAddress = ni.getHardwareAddress();

    String[] hexadecimal = new String[hardwareAddress.length];
    for (int i = 0; i < hardwareAddress.length; i++) {
      hexadecimal[i] = String.format("%02X", hardwareAddress[i]);
    }

    return String.join("-", hexadecimal);
  }

  public void setIngestUrl(String ingestUrl) {
    this.ingestUrl = ingestUrl;
  }

  public void setIngestKey(String ingestKey) {
    this.ingestKey = ingestKey;
  }

  public void setHostname(String hostname) {
    this.hostname = hostname;
  }

  public void setAppname(String appname) {
    this.appname = appname;
  }

  public void setConnectionTimeout(int connectionTimeout) {
    this.connectionTimeout = connectionTimeout;
  }

  public void setReadTimeout(int readTimeout) {
    this.readTimeout = readTimeout;
  }
}
