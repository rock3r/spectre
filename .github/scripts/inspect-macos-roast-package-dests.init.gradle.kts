// Inspects the real :cli package/verify task graph used by macos-release-artifacts.yml.
// Applied only by test-macos-roast-dual-package-graph.sh — not a production init script.
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import java.io.File

gradle.beforeProject {
    if (path != ":cli") {
        return@beforeProject
    }
    afterEvaluate {
        fun destDir(taskName: String): File {
            val task = tasks.getByName(taskName)
            val dest = task.property("destinationDirectory")
            val dir =
                when (dest) {
                    is DirectoryProperty -> dest.get().asFile
                    is File -> dest
                    else ->
                        throw GradleException(
                            "$taskName.destinationDirectory has unexpected type ${dest?.javaClass}"
                        )
                }
            return dir.canonicalFile
        }

        fun artifactFile(taskName: String): File {
            val task = tasks.getByName(taskName)
            val artifact = task.property("artifact")
            val file =
                when (artifact) {
                    is RegularFileProperty -> artifact.get().asFile
                    is File -> artifact
                    else ->
                        throw GradleException(
                            "$taskName.artifact has unexpected type ${artifact?.javaClass}"
                        )
                }
            return file.canonicalFile
        }

        fun finalizerNames(taskName: String): Set<String> {
            val task = tasks.getByName(taskName)
            return task.finalizedBy.getDependencies(task).map { it.name }.toSet()
        }

        val x64Dest = destDir("packageMacosX64")
        val armDest = destDir("packageMacosArm64")
        val x64Zip = artifactFile("verifyRoastCliNativeLayoutMacosX64")
        val armZip = artifactFile("verifyRoastCliNativeLayoutMacosArm64")
        val x64Finalizers = finalizerNames("packageMacosX64")
        val armFinalizers = finalizerNames("packageMacosArm64")

        println("SPECTRE_PACKAGE_MACOS_X64_DEST=$x64Dest")
        println("SPECTRE_PACKAGE_MACOS_ARM64_DEST=$armDest")
        println("SPECTRE_VERIFY_MACOS_X64_ZIP=$x64Zip")
        println("SPECTRE_VERIFY_MACOS_ARM64_ZIP=$armZip")
        println("SPECTRE_PACKAGE_MACOS_X64_FINALIZERS=${x64Finalizers.sorted().joinToString(",")}")
        println("SPECTRE_PACKAGE_MACOS_ARM64_FINALIZERS=${armFinalizers.sorted().joinToString(",")}")

        if ("verifyRoastCliNativeLayoutMacosX64" !in x64Finalizers) {
            throw GradleException(
                "packageMacosX64 must be finalizedBy verifyRoastCliNativeLayoutMacosX64 " +
                    "(layout gate must stay a hard package* finalizer; found $x64Finalizers)"
            )
        }
        if ("verifyRoastCliNativeLayoutMacosArm64" !in armFinalizers) {
            throw GradleException(
                "packageMacosArm64 must be finalizedBy verifyRoastCliNativeLayoutMacosArm64 " +
                    "(layout gate must stay a hard package* finalizer; found $armFinalizers)"
            )
        }

        if (x64Zip.parentFile != x64Dest) {
            throw GradleException(
                "verifyRoastCliNativeLayoutMacosX64 artifact $x64Zip is not in packageMacosX64 dest $x64Dest"
            )
        }
        if (armZip.parentFile != armDest) {
            throw GradleException(
                "verifyRoastCliNativeLayoutMacosArm64 artifact $armZip is not in packageMacosArm64 dest $armDest"
            )
        }
        if (x64Zip.name != "spectre-macosX64.zip" || armZip.name != "spectre-macosArm64.zip") {
            throw GradleException(
                "Roast zip names must stay spectre-\${target}.zip " +
                    "(found ${x64Zip.name}, ${armZip.name})"
            )
        }

        val shared =
            x64Dest == armDest ||
                x64Dest.path.startsWith(armDest.path + File.separator) ||
                armDest.path.startsWith(x64Dest.path + File.separator)
        if (shared) {
            throw GradleException(
                "dual-target implicit-dependency: packageMacosX64 and packageMacosArm64 share " +
                    "destinationDirectory $x64Dest (construo/distributions race). " +
                    "verifyRoastCliNativeLayoutMacosX64 reads that directory while " +
                    "packageMacosArm64 writes it. Isolate per-target distribution dirs."
            )
        }
    }
}
