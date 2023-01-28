package io.featurehub.tooling

import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import java.io.File

abstract class BaseReconciler : AbstractMojo() {
  @Parameter(name = "alwaysIncludeTagValues", required = false)
  private var alwaysIncludeTagValues: List<String> = listOf()

  @Parameter(name = "removeObjectExtensions", required = false)
  private var removeObjectExtensions: List<String> = listOf()

  @Parameter(name = "removePropertyExtensions", required = false)
  private var removePropertyExtensions: List<String> = listOf()

  @Parameter(name = "illegalExtensions", required = false)
  private var illegalExtensions: List<String> = listOf()

  @Parameter(name = "apiSource", required = true, defaultValue = "\${project.build.directory}/final.yaml")
  private var apiSource: File? = null;

  @Parameter(name = "releaseFolder", required = false)
  protected var releaseFolder: File? = null;

  protected fun setup(): ReleasePublisher {
    if (apiSource?.exists() != true) {
      throw MojoFailureException("There is no source API file to process")
    }

    val result =
      OpenAPIParser().readLocation(apiSource!!.absolutePath, emptyList(), ParseOptions())

    if (releaseFolder != null) {
      if (removeObjectExtensions.isEmpty()) removeObjectExtensions = listOf("x-package", "x-cloudevent-type", "x-cloudevent-subject")
      if (removePropertyExtensions.isEmpty()) removePropertyExtensions = listOf("x-basename")
      if (illegalExtensions.isEmpty()) illegalExtensions = listOf("x-property-ref")
    }

    Reconcile(
      result.openAPI,
      Extensions(alwaysIncludeTagValues,
        removeObjectExtensions,
        removePropertyExtensions,
        illegalExtensions),
    ).reconcile()

    return ReleasePublisher(result.openAPI)
  }
}

@Mojo(name = "publish",
  defaultPhase = LifecyclePhase.INITIALIZE,
  requiresDependencyCollection = ResolutionScope.NONE,
  requiresDependencyResolution = ResolutionScope.NONE,
  requiresProject = false,
  threadSafe = true)
class MavenPublisher : BaseReconciler() {
  override fun execute() {
    val releasePublisher = setup()

    releaseFolder?.let {
      releasePublisher.publish(it.absolutePath)
    }
  }
}

@Mojo(name = "reconcile",
	defaultPhase = LifecyclePhase.INITIALIZE,
	requiresDependencyCollection = ResolutionScope.NONE,
	requiresDependencyResolution = ResolutionScope.NONE,
	requiresProject = false,
	threadSafe = true)
class MavenReconciler : BaseReconciler() {
  @Parameter(name = "reconciledApi", required = false)
  private var reconciledApi: String? = null

  override fun execute() {
    val releasePublisher = setup()

    releaseFolder?.let {
      releasePublisher.updateRelease(it.absolutePath)
    }

    reconciledApi?.let {
      releasePublisher.writeReconciliation(it)
    }
  }
}