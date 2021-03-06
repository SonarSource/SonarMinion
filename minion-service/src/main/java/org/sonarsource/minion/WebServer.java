/*
 * Copyright (C) 2018-2018 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */

package org.sonarsource.minion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.ModelAndView;
import spark.template.velocity.VelocityTemplateEngine;

import static spark.Spark.exception;
import static spark.Spark.get;
import static spark.Spark.port;
import static spark.Spark.post;
import static spark.Spark.redirect;
import static spark.Spark.staticFiles;

public class WebServer {

  private static final int DEFAULT_PORT = 9001;

  private static final Logger LOGGER = LoggerFactory.getLogger(WebServer.class);

  private final InputConnector jiraInputConnector = new CachedJiraInputConnector();
  private final Qualifier qualifier = new Qualifier();
  private final Analyzer analyzer = new Analyzer(qualifier, jiraInputConnector);

  public void start(int port) {
    port(port);
    LOGGER.info("Listening on port {}", port);

    staticFiles.location("img");

    get("/", (request, response) -> {
      Map<String, Object> model = new HashMap<>();
      model.put("message", "Velocity World");

      // The vm files are located under the resources directory
      return new ModelAndView(model, "hello.vm");
    }, new VelocityTemplateEngine());

    redirect.get("*", "/minion.png");

    post("/analyze", (request, response) -> {
      String description = request.queryParams("description");
      String component = request.queryParams("component");
      String component_version = request.queryParams("component_version");
      String message = request.queryParams("message");

      if ((description != null && !description.isEmpty()) || (message != null && !message.isEmpty())) {
        return resultToString(analyzer.analyze(description, component, component_version, message));
      }

      String body = request.body();
      if (body == null || body.trim().isEmpty()) {
        throw new IllegalArgumentException("Body should not be empty");
      } else {
        return resultToString(analyzer.analyze(body));
      }
    });

    post("/process_message", (request, response) -> {
      String payload = request.body();
      if (payload == null || payload.trim().isEmpty()) {
        throw new IllegalArgumentException("Body should not be empty");
      }

      JsonObject post = new JsonParser().parse(payload).getAsJsonObject().get("post").getAsJsonObject();
      String raw_post = post.get("cooked").getAsString().replace("\\n", "\n");
      String topic = post.get("topic_id").getAsString();

      RequestBody requestBody = new MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("raw", raw_post)
        // TODO set API key from external files
        .addFormDataPart("api_key", "TO_BE_SET")
        .addFormDataPart("topic_id", "75")
        .build();
      OkHttpClient client = new OkHttpClient();
      Request request1 = new Request.Builder()
        .post(requestBody)
        .url("https://community.sonarsource.org/posts?&raw=" + resultToString(analyzer.analyze(raw_post, "", "", ""))
          + "&api_key=TO_BE_SET&topic_id=" + topic)
        .build();
      try {
        Response response1 = client.newCall(request1).execute();
        System.out.println(response1.message());
      } catch (IOException e) {
        throw new IllegalStateException("Error", e);
      }
      return "200";
    });

    exception(IllegalArgumentException.class, (e, req, res) -> {
      res.status(400);
      res.body(e.getMessage());
    });

    exception(JsonSyntaxException.class, (e, req, res) -> {
      res.status(400);
      res.body(e.getMessage());
    });
  }

  public String resultToString(Analyzer.Result result) {
    String message = result.getMessage();
    if (message != null) {
      return result.getMessage();
    } else {
      StringBuilder s = new StringBuilder();
      s.append("JIRA tickets found : " + result.getJiraTickets().stream().map(t -> "<a href=\"https://jira.sonarsource.com/browse/"+t+"\">"+t+"</a>").collect(Collectors.joining("<br/>")));
      s.append("<br/>");
      s.append("Products found : " + result.getProductsVersions().entrySet().stream().map(entry -> entry.getKey() + " - " + entry.getValue()).collect(Collectors.joining("<br/>")));
      s.append("<br/>");
      s.append("Errors found : " + result.getErrorMessages().stream().collect(Collectors.joining("<br/>")));
      return s.toString();
    }
  }

  public static void main(String[] args) {
    if (args.length > 0) {
      String port = args[0];
      new WebServer().start(Integer.parseInt(port));
      return;
    }
    new WebServer().start(DEFAULT_PORT);
  }
}
