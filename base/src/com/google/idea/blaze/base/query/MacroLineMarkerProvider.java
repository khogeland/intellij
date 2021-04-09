/*
 * Copyright 2019 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.base.query;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.joining;

import com.google.common.collect.ImmutableList;
import com.google.idea.blaze.base.lang.buildfile.psi.BuildFile;
import com.google.idea.blaze.base.lang.buildfile.psi.BuildFile.BlazeFileType;
import com.google.idea.blaze.base.lang.buildfile.psi.FuncallExpression;
import com.google.idea.blaze.base.lang.buildfile.psi.LoadStatement;
import com.google.idea.blaze.base.lang.buildfile.psi.ReferenceExpression;
import com.google.idea.blaze.base.lang.buildfile.psi.util.PsiUtils;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.common.experiments.BoolExperiment;
import com.google.idea.sdkcompat.general.BaseSdkCompat.LineMarkerProviderAdapter;
import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.LowMemoryWatcher;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.tree.LeafPsiElement;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.annotation.Nullable;
import org.jetbrains.ide.PooledThreadExecutor;

/** A line marker provider for BUILD files, showing the list of targets generated by a macro. */
public class MacroLineMarkerProvider implements LineMarkerProviderAdapter {

  private static final BoolExperiment enabled =
      new BoolExperiment("macro.gutter.icons.enabled", false);

  @Nullable
  @Override
  @SuppressWarnings("rawtypes")
  public LineMarkerInfo getLineMarkerInfo(PsiElement element) {
    return null;
  }

  @Override
  public void doCollectSlowLineMarkers(
      List<? extends PsiElement> elements, Collection<? super LineMarkerInfo<?>> result) {
    if (!enabled.getValue()) {
      return;
    }
    BuildFile buildFile = getContainingFile(elements);
    if (buildFile == null || buildFile.getBlazeFileType() != BlazeFileType.BuildPackage) {
      return;
    }
    FileData fileData = FileDataProvider.getInstance(buildFile.getProject()).getFileData(buildFile);
    if (fileData == null) {
      return;
    }
    for (PsiElement element : elements) {
      ProgressManager.checkCanceled();
      if (!(element instanceof LeafPsiElement)) {
        continue;
      }
      FuncallExpression funcall = getDirectFuncallParent(element);
      if (funcall == null) {
        continue;
      }
      List<GeneratedTarget> genTargets = fileData.findTargetsGeneratedByMacro(buildFile, funcall);
      if (genTargets.isEmpty()) {
        continue;
      }
      // TODO(brendandouglas): calculate tooltip asynchronously?
      // TODO(brendandouglas): improve the tooltip UI
      String tooltip = getTooltip(genTargets);
      LineMarkerInfo<PsiElement> info =
          new LineMarkerInfo<>(
              element,
              element.getTextRange(),
              AllIcons.Hierarchy.MethodDefined,
              psi -> tooltip,
              null,
              GutterIconRenderer.Alignment.RIGHT);
      result.add(info);
    }
  }

  private static String getTooltip(List<GeneratedTarget> genTargets) {
    String targetRows =
        genTargets.stream()
            .sorted(MacroLineMarkerProvider::compare)
            .map(t -> tooltipText(t))
            .collect(joining("<br>"));
    return String.format(
        "<html><body><p>Targets generated by this macro</p>"
            + "<p style='margin-top:2pt'>"
            + "<table border=\"0\">"
            + "<tr><th>Rule Type</th><th>Target Name</th></tr>"
            + "%s</table>"
            + "</p></body></html>",
        targetRows);
  }

  private static String tooltipText(GeneratedTarget target) {
    return String.format(
        "<tr><td>%s</td><td style='margin-left:20pt'>%s</td></tr>",
        target.ruleType, target.label.targetName());
  }

  /** comparator implementation for GeneratedTarget. Order by rule type, then target name. */
  private static int compare(GeneratedTarget o1, GeneratedTarget o2) {
    // custom rules go last
    if (o1.ruleType.startsWith("_") != o2.ruleType.startsWith("_")) {
      return o1.ruleType.startsWith("_") ? 1 : -1;
    }
    int diff = o1.ruleType.compareToIgnoreCase(o2.ruleType);
    if (diff != 0) {
      return diff;
    }
    return o1.label.targetName().toString().compareToIgnoreCase(o2.label.targetName().toString());
  }

  @Nullable
  private static FuncallExpression getDirectFuncallParent(PsiElement element) {
    PsiElement parent = element.getParent();
    if (!(parent instanceof ReferenceExpression)) {
      return null;
    }
    parent = parent.getParent();
    return parent instanceof FuncallExpression ? (FuncallExpression) parent : null;
  }

  @Nullable
  private static BuildFile getContainingFile(List<? extends PsiElement> elements) {
    PsiFile file = elements.isEmpty() ? null : elements.get(0).getContainingFile();
    return file instanceof BuildFile ? (BuildFile) file : null;
  }

  private static class FileData {
    private final ImmutableList<GeneratedTarget> targets;
    /** The time this data was calculated. Used for per-file query rate limiting. */
    private final long queryTimeMillis;

    private FileData(ImmutableList<GeneratedTarget> targets, long queryTimeMillis) {
      this.targets = targets;
      this.queryTimeMillis = queryTimeMillis;
    }

    /**
     * Looks for targets matching the given funcall expression. Doesn't rely on line numbers being
     * unchanged.
     *
     * <p>First tries to match on 'name' attribute + function name, falling back to line number +
     * function name if the 'name' attribute isn't set.
     */
    ImmutableList<GeneratedTarget> findTargetsGeneratedByMacro(
        BuildFile file, FuncallExpression funcall) {
      String functionName = funcall.getFunctionName();
      if (functionName == null) {
        return ImmutableList.of();
      }
      String nameAttr = funcall.getNameArgumentValue();
      if (nameAttr != null) {
        return targets.stream()
            .filter(
                t ->
                    Objects.equals(t.macro.macroFunction, functionName)
                        && Objects.equals(t.macro.name, nameAttr))
            .collect(toImmutableList());
      }
      Integer lineIndex = getLineIndex(file, funcall);
      if (lineIndex != null) {
        return targets.stream()
            .filter(
                t ->
                    Objects.equals(t.macro.macroFunction, functionName)
                        && t.macro.lineNumber == lineIndex + 1)
            .collect(toImmutableList());
      }
      return ImmutableList.of();
    }

    /** Returns the 0-based line index of the given element within the build file. */
    @Nullable
    private static Integer getLineIndex(BuildFile file, PsiElement element) {
      Document doc = file.getViewProvider().getDocument();
      return doc != null ? doc.getLineNumber(element.getTextOffset()) : null;
    }
  }

  static class FileDataProvider {
    static FileDataProvider getInstance(Project project) {
      return ServiceManager.getService(project, FileDataProvider.class);
    }

    private static final Logger logger = Logger.getInstance(FileDataProvider.class);
    private static final Duration RECOMPUTE_TIME = Duration.ofSeconds(15);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ConcurrentMap<Label, FileData> cache = new ConcurrentHashMap<>();

    private final Project project;

    private FileDataProvider(Project project) {
      this.project = project;
      LowMemoryWatcher.register(cache::clear, project);
    }

    @Nullable
    FileData getFileData(BuildFile buildFile) {
      if (!hasLoadStatement(buildFile) || hasSyntaxError(buildFile)) {
        return null;
      }
      Label label = buildFile.getBuildLabel();
      if (label == null) {
        return null;
      }
      return cache.compute(
          label,
          (f, oldData) -> {
            if (oldData != null && !recomputeData(oldData)) {
              return oldData;
            }
            FileData newData = getDataWithTimeout(f);
            return newData != null ? newData : oldData;
          });
    }

    @Nullable
    private FileData getDataWithTimeout(Label buildLabel) {
      Future<ImmutableList<GeneratedTarget>> future =
          PooledThreadExecutor.INSTANCE.submit(
              () -> MacroTargetProvider.findTargetsGeneratedByMacros(project, buildLabel));
      long startTimeMillis = System.currentTimeMillis();
      while (true) {
        ProgressManager.checkCanceled();
        try {
          ImmutableList<GeneratedTarget> targets = future.get(50, TimeUnit.MILLISECONDS);
          return targets.isEmpty() ? null : new FileData(targets, startTimeMillis);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return null;
        } catch (ExecutionException e) {
          logger.error(e);
          return null;
        } catch (TimeoutException e) {
          if (System.currentTimeMillis() - startTimeMillis > TIMEOUT.toMillis()) {
            logger.debug("Macro expansion query timed out", e);
            return null;
          }
        }
      }
    }

    private static boolean recomputeData(FileData data) {
      return System.currentTimeMillis() - data.queryTimeMillis > RECOMPUTE_TIME.toMillis();
    }

    private static boolean hasLoadStatement(BuildFile file) {
      return file.findChildByClass(LoadStatement.class) != null;
    }

    private static boolean hasSyntaxError(BuildFile file) {
      return PsiUtils.findFirstChildOfClassRecursive(file, PsiErrorElement.class) != null;
    }
  }
}
