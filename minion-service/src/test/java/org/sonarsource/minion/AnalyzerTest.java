/*
 * Copyright (C) 2018-2018 SonarSource SA
 * All rights reserved
 * mailto:info AT sonarsource DOT com
 */
package org.sonarsource.minion;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.assertj.core.api.SoftAssertions;
import org.junit.Ignore;
import org.junit.Test;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;

public class AnalyzerTest {

  private static final Gson GSON = new Gson();

  private Analyzer analyzer = new Analyzer(new FakeQualifier(), new CachedJiraInputConnector());

  @Test
  public void test_returned_message() {
    String answer = analyzer.analyze("{description:\"foo\"}").getMessage();
    assertThat(answer).isEqualTo("We didn't understand the error, could you please describe the error ?");

    // version in message
    answer = analyzer.analyze("{description:\"foo 6.2\"}").getMessage();
    assertThat(answer).isEqualTo("We didn't understand the error, could you please describe the error ?");

    answer = analyzer.analyze("{description:\"foo 6.2\", component:\"plop\"}").getMessage();
    assertThat(answer).isEqualTo("We didn't understand the error, could you please describe the error ?");
  }

  @Test
  public void test_gg_versions() throws IOException {
    String[][] expected = new String[][] {
      {"7.0", "7.1", "0.0.0.0", "2018.05.03"},
      {"47.4448709", "51.6451219"},
    };

    for (int i = 1; i <= 2; i++) {
      Analyzer.Message message = getMessage("src/test/resources/google_groups/input" + i + ".json");
      assertThat(analyzer.getVersions(message.description)).containsExactlyInAnyOrder(expected[i - 1]);
    }
  }

  @Test
  public void test_sd_versions() throws IOException {
    String[][] expected = new String[][] {
      {"6.7.1", "5.6"},
      {},
      {"2.0.0", "41.4657263", "5.9.0.1001", "41.3511383", "5.6"},
      {"300.000", "2.264.000"}
    };

    for (int i = 1; i <= 4; i++) {
      Analyzer.Message message = getMessage("src/test/resources/servicedesk/input" + i + ".json");
      assertThat(analyzer.getVersions(message.description)).containsExactlyInAnyOrder(expected[i - 1]);
    }
  }

  @Test
  public void test_gg_products() throws IOException {
    String[][] expected = new String[][] {
      {},
      {"SonarQube"},
      {},
      {}
    };

    for (int i = 1; i <= 2; i++) {
      Analyzer.Message message = getMessage("src/test/resources/google_groups/input" + i + ".json");
      assertThat(analyzer.getProducts(message.description)).containsExactlyInAnyOrder(expected[i - 1]);
    }
  }

  @Test
  public void test_sd_products() throws IOException {
    String[][] expected = new String[][] {
      {"SonarQube"},
      {},
      {"SonarQube"},
      {}
    };

    for (int i = 1; i <= 4; i++) {
      Analyzer.Message message = getMessage("src/test/resources/servicedesk/input" + i + ".json");
      assertThat(analyzer.getProducts(message.description)).containsExactlyInAnyOrder(expected[i - 1]);
    }
  }

  private static Analyzer.Message getMessage(String pathname) throws IOException {
    File file = new File(pathname);
    String content = getContent(file);
    return GSON.fromJson(content, Analyzer.Message.class);
  }

  private static String getContent(File file) throws IOException {
    return Files.readAllLines(file.toPath()).stream().collect(Collectors.joining("\n"));
  }

  @Test
  public void errorMessage() throws IOException {
    String[][] expected = new String[][] {
      {"	at org.sonar.server.computation.task.projectanalysis.component.VisitException.rethrowOrWrap(VisitException.java:44)",
        "	at org.sonar.server.computation.task.projectanalysis.measure.MeasureRepositoryImpl.add(MeasureRepositoryImpl.java:124)"},
      {},
      {},
      {},
      {},
      {},
      {},
      {}
    };
    for (int i = 1; i <= 8; i++) {
      Analyzer.Message message = getMessage("src/test/resources/servicedesk/input" + i + ".json");
      assertThat(analyzer.getErrorMessages(message.description)).containsExactlyInAnyOrder(expected[i - 1]);
    }
  }

  @Test
  public void test_get_version() {
    assertThat(analyzer.getVersion("SonarCOBOL", asList("3.9", "4.2"))).isEqualTo("4.2");
    assertThat(analyzer.getVersion("SonarCOBOL", asList("4.0.2", "4.2"))).isEqualTo("4.2");
    assertThat(analyzer.getVersion("SonarCOBOL", singletonList("3.9"))).isNull();
    assertThat(analyzer.getVersion("unknown", singletonList("3.9"))).isNull();
  }

  @Test
  public void test_get_errors_with_jira_request() throws IOException {
    SoftAssertions softly = new SoftAssertions();
    Qualifier qualifier = new Qualifier();
    for (File file : new File("src/test/resources/stacktraces").listFiles()) {
      List<String> errorMessages = analyzer.getErrorMessages(getContent(file));
      String expected = file.getName();
      if ("SONAR-10536.txt".equals(expected)) {
        // not supported yet : stack trace is rather incomplete
        continue;
      }
      expected = expected.substring(0, expected.length() - 4);

      Set<String> resp = qualifier.qualify(new HashSet<>(errorMessages));
      softly.assertThat(resp).contains(expected);
    }
    softly.assertAll();
  }

  @Test
  public void test_sd_input1_with_jira_request() throws IOException {
    File file = new File("src/test/resources/servicedesk/input1.json");
    Analyzer analyzer = new Analyzer(new Qualifier(), new CachedJiraInputConnector());

    Set<String> tickets = analyzer.analyze(getContent(file)).getJiraTickets();

    assertThat(tickets).contains("SONAR-9384");
  }

  @Test
  @Ignore
  public void test_SonarJava_tickets() throws IOException {
    Analyzer analyzer = new Analyzer(new Qualifier(), new CachedJiraInputConnector());
    SoftAssertions softly = new SoftAssertions();
    for (File file : new File("src/test/resources/javaTickets").listFiles()) {
      String expected = file.getName();
      if (expected.equals("report.txt")) {
        continue;
      }
      Set<String> resp = analyzer.analyze(getContent(file)).getJiraTickets();
      softly.assertThat(resp).contains(expected);
    }
    softly.assertAll();
  }

  private static final class FakeQualifier extends Qualifier {
    @Override
    public Set<String> qualify(Set<String> errorMessage) {
      return Collections.emptySet();
    }
  }

}
