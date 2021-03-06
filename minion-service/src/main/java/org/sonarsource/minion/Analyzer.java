/*
 * Copyright (C) 2018-2018 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package org.sonarsource.minion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.CheckForNull;

public class Analyzer {
  private static final Pattern VERSION_REGEX = Pattern.compile("\\d+\\.\\d+(\\.\\d+)*");
  private static final Pattern STACK_REGEX = Pattern.compile("(^\\d+\\) .+)|(^.+Exception: .+)|(^\\s+at .+)|(^\\s+... \\d+ more)|(^\\s*Caused by:.+)", Pattern.MULTILINE);
  private final Qualifier qualifier;

  private InputConnector inputConnector;

  public Analyzer(Qualifier qualifier, InputConnector inputConnector) {
    this.qualifier = qualifier;
    this.inputConnector = inputConnector;
  }

  public static class Message {
    String description;
    String component;
    String component_version;
    String message;

    public Message(String description, String component, String component_version, String message) {
      this.description = description;
      this.component = component;
      this.component_version = component_version;
      this.message = message;
    }
  }

  public Result analyze(String json) {
    Gson gson = new GsonBuilder().create();
    Message message = gson.fromJson(json, Message.class);
    return this.process(message);
  }

  public Result analyze(String description, String component, String component_version, String message) {
    return this.process(new Message(description, component, component_version, message));
  }

  public Result process(Message message) {
    if (message == null) {
      throw new IllegalArgumentException("Invalid json message");
    }
    String description = message.description;
    if (description == null || description.isEmpty()) {
      return processMessage(message);
    }
    List<String> errorMessages = getErrorMessages(description);
    return processError(errorMessages);
  }

  private Result processError(List<String> errorMessages) {
    if (errorMessages.isEmpty()) {
      return new Result().setMessage("We didn't understand the error, could you please describe the error ?");
    }
    Set<String> jiraTickets = qualifier.qualify(new HashSet<>(errorMessages));
    if (jiraTickets.isEmpty()) {
      return new Result().setMessage("No JIRA tickets has been found");
    }

    return new Result()
      .setJiraTickets(jiraTickets)
      .setErrorMessages(errorMessages);
  }

  private Result processMessage(Message message) {
    String textMessage = message.message;
    if (textMessage == null ||textMessage.isEmpty()) {
      return new Result().setMessage("Please provide your error message");
    }
    Set<String> versions = new HashSet<>();
    if (message.component_version == null || message.component_version.isEmpty()) {
      return new Result().setMessage("Seems like there is no product nor version in your question, could you clarify this information ?");
    } else {
      versions.add(message.component_version);
    }

    Set<String> products = new HashSet<>();
    if (message.component == null || message.component.isEmpty()) {
      return new Result().setMessage("Could you specify which component of the SonarQube ecosystem your question is about ?");
    } else {
      products = getProducts(message.component);
    }

    Map<String, String> productsVersions = getVersionsByProduct(products, versions);
    Set<String> jiraTickets = qualifier.qualify(textMessage);
    if (jiraTickets.isEmpty()) {
      return new Result().setMessage("No JIRA tickets has been found");
    }

    return new Result()
      .setJiraTickets(jiraTickets)
      .setProductsVersions(productsVersions);
  }

  Set<String> getVersions(String message) {
    Set<String> res = new HashSet<>();
    Matcher matcher = VERSION_REGEX.matcher(message);
    while (matcher.find()) {
      res.add(matcher.group());
    }
    return res;
  }

  Set<String> getProducts(String message) {
    Collection<String> knownProducts = inputConnector.findProducts();
    return knownProducts.stream()
      .filter(message::contains)
      .collect(Collectors.toSet());
  }

  List<String> getErrorMessages(String message) {
    List<String> res = new ArrayList<>();
    Matcher matcher = STACK_REGEX.matcher(message);
    while (matcher.find()) {
      res.add(matcher.group());
    }
    return IntStream.range(0, res.size())
      // keep the first element in case we dd
      .filter(i -> i == 0 || res.get(i).matches("(^.+Exception: .+)|(^\\s*Caused by:.+)"))
      .mapToObj(i -> {
        int next = i + 1;
        String line = null;
        while (next < res.size() && (line == null || !line.contains("sonar"))) {
          line = res.get(next);
          next++;
        }
        return line;
      }).filter(Objects::nonNull).collect(Collectors.toList());
  }

  @CheckForNull
  String getVersion(String product, Collection<String> versions) {
    List<String> knownVersions = new ArrayList<>(inputConnector.findSortedVersions(product));
    Collections.reverse(knownVersions);
    return knownVersions.stream()
      .filter(versions::contains)
      .findFirst().orElse(null);
  }

  private Map<String, String> getVersionsByProduct(Set<String> products, Set<String> versions) {
    return products.stream()
      .filter(p -> getVersion(p, versions) != null)
      .collect(Collectors.toMap(Function.identity(), p -> getVersion(p, versions)));
  }

  static class Result {
    private String message;
    private Set<String> jiraTickets = new HashSet<>();
    private Map<String, String> productsVersions = new HashMap<>();
    private List<String> errorMessages = new ArrayList<>();

    public String getMessage() {
      return message;
    }

    public Result setMessage(String message) {
      this.message = message;
      return this;
    }

    public Set<String> getJiraTickets() {
      return jiraTickets;
    }

    public Result setJiraTickets(Set<String> jiraTickets) {
      this.jiraTickets = jiraTickets;
      return this;
    }

    public Map<String, String> getProductsVersions() {
      return productsVersions;
    }

    public Result setProductsVersions(Map<String, String> productsVersions) {
      this.productsVersions = productsVersions;
      return this;
    }

    public List<String> getErrorMessages() {
      return errorMessages;
    }

    public Result setErrorMessages(List<String> errorMessages) {
      this.errorMessages = errorMessages;
      return this;
    }
  }
}
