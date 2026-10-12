package kafkatorio.conventions

import io.kvision.gradle.KVisionExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.findByType
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension


object Versions {
  // typescript-to-lua 1.25.2+ (needed for typed-factorio 3.x / Factorio 2.0) requires Node >=16.10.0
  const val node = "24.21.0"
}


/**
 * `kotlin-js` adds a directory in the root-dir for the Yarn lock.
 * That's a bit annoying. It's a little neater if it's in the
 * gradle dir, next to the version-catalog.
 */
fun Project.relocateKotlinJsStore() {

  afterEvaluate {
    rootProject.extensions.configure<YarnRootExtension> {
      lockFileDirectory = project.rootDir.resolve("gradle/kotlin-js-store")
    }
  }

//  rootProject.plugins.withType(YarnPlugin::class) {
//    rootProject.the<YarnRootExtension>().lockFileDirectory =
//      project.rootDir.resolve("gradle/kotlin-js-store")
//  }

  // KVision tries to be clever too
  project.extensions.findByType<KVisionExtension>()?.apply {
    enableHiddenKotlinJsStore.set(false)
  }
}
