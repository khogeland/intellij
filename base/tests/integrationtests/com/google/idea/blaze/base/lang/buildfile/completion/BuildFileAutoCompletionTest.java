/*
 * Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.idea.blaze.base.lang.buildfile.completion;

import static com.google.common.truth.Truth.assertThat;

import com.google.common.collect.ImmutableList;
import com.google.idea.blaze.base.lang.buildfile.BuildFileIntegrationTestCase;
import com.google.idea.blaze.base.lang.buildfile.psi.BuildFile;
import com.google.idea.blaze.base.model.primitives.WorkspacePath;
import com.google.idea.sdkcompat.testframework.fixtures.CompletionAutoPopupTesterAdapter;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.openapi.editor.Editor;
import com.intellij.util.ThrowableRunnable;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Tests for auto-popup code completion in BUILD files. */
@RunWith(JUnit4.class)
public class BuildFileAutoCompletionTest extends BuildFileIntegrationTestCase {

  private CompletionAutoPopupTesterAdapter completionTester;

  @Before
  public final void before() {
    completionTester = new CompletionAutoPopupTesterAdapter(testFixture);
  }

  /** Completion UI testing can't be run on the EDT. */
  @Override
  protected boolean runTestsOnEdt() {
    return false;
  }

  @Test
  public void testNoPopupAfterNumber() throws Throwable {
    // #api202: remove redundant cast "(ThrowableRunnable<Throwable>)"
    completionTester.runWithAutoPopupEnabled(
        (ThrowableRunnable<Throwable>)
            () -> {
              createBuildFile(new WorkspacePath("java/com/foo/BUILD"));
              BuildFile file =
                  createBuildFile(
                      new WorkspacePath("BUILD"),
                      "java_library(",
                      "    name = 'lib',",
                      "    testonly = ");

              Editor editor = editorTest.openFileInEditor(file.getVirtualFile());
              editorTest.setCaretPosition(editor, 2, "    testonly = ".length());

              completionTester.typeWithPauses("1");
              assertThat(currentLookupStrings()).isEmpty();
            });
  }

  private List<String> currentLookupStrings() {
    LookupImpl lookup = completionTester.getLookup();
    if (lookup == null) {
      return ImmutableList.of();
    }
    return lookup.getItems().stream()
        .map(LookupElement::getLookupString)
        .collect(Collectors.toList());
  }
}
