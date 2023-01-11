package com.blue1992256.crawl.imaxalarm.main;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.json.simple.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class Crawl {
  @Value("${fcm.server.token}")
  private String serverToken;
  @Value("${fcm.device.token}")
  private String deviceToken;
  private static HttpClient client = HttpClient.newHttpClient();
  private static boolean done;

  @PostConstruct
  private static void setUp() {
    try {
      done = false;
      buildClient();
    } catch (Exception e) {
      log.error("#setUp error : {}", e.getMessage());
    }
  }

  @Scheduled(fixedDelay = 102000)
  private void run() throws Exception {
    if (!done) {
      doRequest();
    }
  }

  private static void buildClient() {
    client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.ALWAYS)
        .build();
  }

  private void doRequest() throws Exception {
    String cookieToUse = getOuterCookies();
    HttpRequest request = buildInnerRequest(cookieToUse);
    String body = tryGetBody(request);
    checkIsOpen(body);
  }

  private void checkIsOpen(String body) {
    try {
      BufferedReader br = new BufferedReader(new StringReader(body));
      String line;
      while ((line = br.readLine()) != null) {
        if (line.matches(".*PLAY_YMD=" + "20230122" + ".*SCREEN_CD=018.*")) { // 날짜 + IMAX 관 검색
          log.info("예매 오픈 확인!");
          sendFcmAlarm();
          done = true;
          break;
        }
      }
    } catch (Exception e) {
      log.error("#checkIsOpen error : {}", e.getMessage());
    }
  }

  private static String tryGetBody(HttpRequest request) throws IOException, InterruptedException {
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    log.info("Fetched {} characters", response.body().length());
    return response.body();
  }

  private static String getOuterCookies() throws IOException, InterruptedException {
    //noinspection HttpUrlsUsage
    URI uri = URI.create("http://www.cgv.co.kr/theaters/?areacode=01&theaterCode=0013&date=20230122");

    HttpRequest request = HttpRequest.newBuilder(uri)
        .GET()
        .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    HttpHeaders headers = response.headers();

    return headers.allValues("Set-Cookie").stream()
        .map(cookie -> Arrays.stream(cookie.split(";")).findFirst().orElse(""))
        .collect(Collectors.joining("; "));
  }

  private static HttpRequest buildInnerRequest(String cookie) {
    //noinspection HttpUrlsUsage
    URI uri = URI.create("http://www.cgv.co.kr/common/showtimes/iframeTheater.aspx?areacode=01&theatercode=0013&date=20230122");
    return HttpRequest.newBuilder(uri)
        .GET()
        .headers(
            "Cookie", cookie,
            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9",
            "Accept-Language", "ko-KR,ko;q=0.9,ja;q=0.8,en-US;q=0.7,en;q=0.6",
            "Referer", "http://www.cgv.co.kr/theaters/?areacode=01&theaterCode=0013&date=20230122",
            "Upgrade-Insecure-Requests", "1"
        )
        .build();
  }

  private void sendFcmAlarm() {
    JSONObject fcmJSON = new JSONObject();
    fcmJSON.put("to", deviceToken);
    fcmJSON.put("priority", "high");

    JSONObject notificationJSON = new JSONObject();
    notificationJSON.put("title", "용산 IMAX 예매 알리미");
    notificationJSON.put("body", "IMAX 오픈!");
    fcmJSON.put("data", notificationJSON);

    try {
      RestTemplate restTemplate = new RestTemplate();
      org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
      headers.setBearerAuth(serverToken);
      headers.setContentType(MediaType.APPLICATION_JSON);
      HttpEntity<String> entity = new HttpEntity<>(fcmJSON.toJSONString(), headers);
      restTemplate.exchange("https://fcm.googleapis.com/fcm/send", HttpMethod.POST, entity, String.class);
    } catch (Exception e) {
      log.error("#sendFcmAlarm error : {}", e.getMessage());
    }

  }

}
