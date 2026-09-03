package dev.hunk.goland

import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.tools.ide.starter.product.goland.GoLand
import java.nio.file.Path

/** Keeps the remote Driver and its GoLand runtime on the same explicitly tested release line. */
internal fun goLandTestCase(project: Path): TestCase<LocalProjectInfo> =
    TestCase(IdeInfo.GoLand, LocalProjectInfo(project)).useRelease(
        System.getProperty("hunk.ideVersion", "2026.2.1.1"),
    )
