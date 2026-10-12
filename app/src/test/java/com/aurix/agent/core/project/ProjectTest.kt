package com.aurix.agent.core.project

import com.aurix.agent.core.tools.ToolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProjectTest {
    private fun allowed(c: String) = CommandPolicy.check(c) is CommandCheck.Allowed
    private fun denied(c: String) = CommandPolicy.check(c) is CommandCheck.Denied

    @Test fun commandPolicyAllowsRealBuildAndTestCommands() {
        assertTrue(allowed("./gradlew testDebugUnitTest --stacktrace"))
        assertTrue(allowed("gradle assembleDebug"))
        assertTrue(allowed("npm test"))
        assertTrue(allowed("npm run build"))
        assertTrue(allowed("python3 -m pytest -q"))
        assertTrue(allowed("go test ./..."))
        assertTrue(allowed("git status --porcelain"))
    }

    @Test fun commandPolicyRefusesDestructiveNetworkedAndChainedCommands() {
        assertTrue(denied("rm -rf /"))
        assertTrue(denied("gradlew test; rm -rf /"))
        assertTrue(denied("gradlew test | sh"))
        assertTrue(denied("gradlew test && curl http://evil.example"))
        assertTrue(denied("gradlew publish"))
        assertTrue(denied("gradlew clean"))
        assertTrue(denied("git push origin main"))
        assertTrue(denied("git reset --hard"))
        assertTrue(denied("npm install left-pad"))
        assertTrue(denied("curl http://example.com"))
        assertTrue(denied("pytest ../secrets"))
        assertTrue(denied("gradlew test \$(whoami)"))
        assertTrue(denied(""))
        assertTrue(denied("x".repeat(400)))
    }

    @Test fun parserNeverTurnsAMissingOrEmptyRunIntoPass() {
        assertEquals(RunSummary.PASS, BuildOutputParser.parse(0, "BUILD SUCCESSFUL in 3s\n12 tests completed, 0 failed").result)
        assertEquals(RunSummary.FAIL, BuildOutputParser.parse(1, "BUILD FAILED in 2s").result)
        assertEquals(RunSummary.FAIL, BuildOutputParser.parse(0, "BUILD FAILED").result)
        assertEquals(RunSummary.UNKNOWN, BuildOutputParser.parse(0, "collected 0 items").result)
        assertEquals(RunSummary.UNKNOWN, BuildOutputParser.parse(0, "no tests ran").result)
        assertTrue(BuildOutputParser.parse(1, "e: A.kt:1:1 boom\ne: B.kt:2:2 bam").detail.contains("compile_errors=2"))
    }

    private fun fixture(): Triple<File, File, ProjectService> {
        val tmp = Files.createTempDirectory("aurix-proj").toFile()
        val proj = File(tmp, "proj").apply { mkdirs() }
        File(proj, "A.kt").writeText("fun a() = 1\n")
        val svc = ProjectService(File(tmp, "state"))
        svc.open(proj.path)
        return Triple(tmp, proj, svc)
    }

    private fun refused(block: () -> Unit): String = try { block(); fail("expected the operation to be refused"); "" } catch (e: ToolException) { e.message.orEmpty() }

    @Test fun editRequiresReadSnapshotsVerifiesAndReverts() {
        val (_, proj, svc) = fixture()
        assertTrue(refused { svc.edit("A.kt", "1", "2", false, false, false) }.contains("Read"))
        svc.read("A.kt", 1, 50)
        val out = svc.edit("A.kt", "= 1", "= 2", false, false, false)
        assertTrue(out, out.contains("READBACK=OK"))
        assertEquals("fun a() = 2\n", File(proj, "A.kt").readText())
        val d = svc.diff()
        assertTrue(d, d.contains("fun a() = 1") && d.contains("fun a() = 2"))
        assertEquals(listOf("A.kt"), svc.changedFiles())
        svc.revert("A.kt")
        assertEquals("fun a() = 1\n", File(proj, "A.kt").readText())
    }

    @Test fun staleReadsAmbiguousAndMissingTextAreRefused() {
        val (_, proj, svc) = fixture()
        File(proj, "B.kt").writeText("x\nx\n")
        svc.read("B.kt", 1, 10)
        assertTrue(refused { svc.edit("B.kt", "x", "y", false, false, false) }.contains("matches 2 times"))
        assertTrue(refused { svc.edit("B.kt", "zzz", "y", false, false, false) }.contains("not found"))
        File(proj, "B.kt").writeText("changed behind our back\n") // the user edits the file after we read it
        assertTrue(refused { svc.edit("B.kt", "changed", "y", false, false, false) }.contains("changed since you read it"))
    }

    @Test fun uncommittedUserChangesAreProtected() {
        val (_, proj, svc) = fixture()
        svc.setDirty(listOf("A.kt"))
        svc.read("A.kt", 1, 10)
        assertTrue(refused { svc.edit("A.kt", "= 1", "= 2", false, false, false) }.contains("UNCOMMITTED"))
        svc.edit("A.kt", "= 1", "= 2", false, false, true) // only after the user agreed (allow_dirty)
        assertEquals("fun a() = 2\n", File(proj, "A.kt").readText())
    }

    @Test fun secretsTraversalAndGeneratedDirsAreOffLimits() {
        val (_, proj, svc) = fixture()
        File(proj, "local.properties").writeText("sdk.dir=/x\nstore.pass=abc")
        File(proj, "build").mkdirs(); File(proj, "build/out.txt").writeText("x")
        refused { svc.read("local.properties", 1, 10) }
        refused { svc.read("../outside.txt", 1, 10) }
        refused { svc.edit("build/out.txt", "x", "y", false, false, false) }
        refused { svc.edit("local.properties", "abc", "def", false, false, true) }
        assertTrue(ProjectGuard.isSecret("keystore.properties") && ProjectGuard.isSecret("app/.env") && ProjectGuard.isSecret("release.jks"))
        assertFalse(ProjectGuard.isSecret("src/Main.kt"))
        assertTrue(ProjectGuard.isSensitive("app/src/main/AndroidManifest.xml"))
    }

    @Test fun creatingFilesWorksAndNeverOverwrites() {
        val (_, proj, svc) = fixture()
        svc.edit("New.kt", "", "val n = 1\n", true, false, false)
        assertEquals("val n = 1\n", File(proj, "New.kt").readText())
        refused { svc.edit("A.kt", "", "boom", true, false, false) }
    }

    @Test fun systemLocationsCannotBeOpenedAsProjects() {
        val tmp = Files.createTempDirectory("aurix-proj2").toFile()
        val svc = ProjectService(File(tmp, "state"), forbidden = listOf(File(tmp, "private").apply { mkdirs() }))
        refused { svc.open(File(tmp, "private").path) }
        refused { svc.open("/") }
    }

    @Test fun diffShowsRealChangesAndEmptyWhenEqual() {
        val d = UnifiedDiff.diff("f.txt", "a\nb\nc\n", "a\nB\nc\n")
        assertTrue(d, d.contains("-b") && d.contains("+B"))
        assertEquals("", UnifiedDiff.diff("f.txt", "same", "same"))
    }
}
