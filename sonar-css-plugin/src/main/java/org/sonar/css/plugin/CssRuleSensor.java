/*
 * SonarCSS
 * Copyright (C) 2018-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.css.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.rule.CheckFactory;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.batch.sensor.issue.NewIssue;
import org.sonar.api.batch.sensor.issue.NewIssueLocation;
import org.sonar.api.rule.RuleKey;
import org.sonar.css.plugin.CssRules.StylelintConfig;
import org.sonar.css.plugin.StylelintReport.Issue;
import org.sonar.css.plugin.StylelintReport.IssuesPerFile;
import org.sonar.css.plugin.bundle.BundleHandler;

public class CssRuleSensor implements Sensor {

  private final BundleHandler bundleHandler;
  private final CssRules cssRules;
  private final LinterCommandProvider linterCommandProvider;

  public CssRuleSensor(BundleHandler bundleHandler, CheckFactory checkFactory, LinterCommandProvider linterCommandProvider) {
    this.bundleHandler = bundleHandler;
    this.linterCommandProvider = linterCommandProvider;
    this.cssRules = new CssRules(checkFactory);
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor
      .onlyOnLanguage(CssLanguage.KEY)
      .name("SonarCSS Rules");
  }

  @Override
  public void execute(SensorContext context) {
    File deployDestination = context.fileSystem().workDir();
    bundleHandler.deployBundle(deployDestination);

    String[] commandParts = linterCommandProvider.commandParts(deployDestination, context);
    ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
    String command = String.join(" ", commandParts);

    try {
      createConfig(deployDestination);
      Process process = processBuilder.start();

      try (InputStreamReader inputStreamReader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
        IssuesPerFile[] issues = new Gson().fromJson(inputStreamReader, IssuesPerFile[].class);
        saveIssues(context, cssRules, issues);
      }

    } catch (IOException e) {
      throw new IllegalStateException(String.format("Failed to run external process '%s'", command), e);

    } catch (JsonSyntaxException e) {
      throw new IllegalStateException(String.format("Failed to parse json result of external process execution '%s'. To diagnose, try to run it manually.", command), e);
    }
  }

  private void createConfig(File deployDestination) throws IOException {
    String configPath = linterCommandProvider.configPath(deployDestination);
    StylelintConfig config = cssRules.getConfig();
    String configAsJson = new Gson().toJson(config);
    Files.write(Paths.get(configPath), Collections.singletonList(configAsJson), StandardCharsets.UTF_8);
  }

  private static void saveIssues(SensorContext context, CssRules cssRules, IssuesPerFile[] issues) {
    FileSystem fileSystem = context.fileSystem();

    for (IssuesPerFile issuesPerFile : issues) {
      InputFile inputFile = fileSystem.inputFile(fileSystem.predicates().hasAbsolutePath(issuesPerFile.source));

      if (inputFile != null) {
        for (Issue issue : issuesPerFile.warnings) {
          NewIssue sonarIssue = context.newIssue();

          NewIssueLocation location = sonarIssue.newLocation()
            .on(inputFile)
            .at(inputFile.selectLine(issue.line))
            .message(issue.text);

          RuleKey ruleKey = cssRules.getActiveSonarKey(issue.rule);
          if (ruleKey == null) {
            throw new IllegalStateException("Unknown stylelint rule or rule not enabled " + issue.rule);
          }
          sonarIssue
            .at(location)
            .forRule(ruleKey)
            .save();
        }
      }
    }
  }

}
