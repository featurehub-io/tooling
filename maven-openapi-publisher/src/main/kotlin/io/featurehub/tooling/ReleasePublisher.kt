package io.featurehub.tooling

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.swagger.v3.core.util.Yaml
import io.swagger.v3.oas.models.OpenAPI
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

/**
 * We want to take an OpenAPI document and pretty-print it and then export it to a location based on its version.
 * We want to update the releases.json file that exists in the same directory
 * If the version file already exists
 */

class Releases {
  var latest: String? = null // has to be a published version
  var versions: MutableList<String> = mutableListOf()
  var published: MutableList<String> = mutableListOf()

  companion object {
    var mapper = ObjectMapper().registerKotlinModule().setSerializationInclusion(JsonInclude.Include.NON_NULL)
    private fun releasesFile(folder: String) : File { return File("${folder}/releases.json") }

    fun read(folder: String): Releases {
      val releasesFile = releasesFile(folder)
      if (releasesFile.exists()) {
        return mapper.readValue(releasesFile, Releases::class.java)
      }

      return Releases()
    }
  }

  fun write(folder: String) {
    mapper.writerWithDefaultPrettyPrinter().writeValue(releasesFile(folder), this)
  }
}

class AlreadyPublished : RuntimeException("API has been published you cannot update it, you must change the version")
class ApiNotUpToDate : RuntimeException("API file has not been updated, it must be updated and committed before publishing")

class ReleasePublisher(private val api: OpenAPI) {
  companion object {
    private val log: Logger = LoggerFactory.getLogger(ReleasePublisher::class.java)
  }

  fun writeReconciliation(reconciledFile: String): ReleasePublisher {
    val data = Yaml.pretty(api)

    log.info("API ${api.info.title}:${api.info.version} written reconciled file ${reconciledFile}")
    File(reconciledFile).writeText(data)

    return this
  }

  fun updateRelease(folder: String): ReleasePublisher {
    val releases = Releases.read(folder)
    val data = Yaml.pretty(api)
    val apiFile = File("${folder}/${api.info.version}.yaml")

    if (apiFile.exists()) {
      val existingData = apiFile.bufferedReader().readText()
      if (existingData != data) {
        if (releases.published.contains(api.info.version)) {
          throw AlreadyPublished()
        }

        log.info("API ${api.info.title}:${api.info.version} has changed, updating")
        apiFile.writeText(data)
      } else {
        log.info("API ${api.info.title}:${api.info.version} has not changed")
      }
    } else {
      log.info("API ${api.info.title}:${api.info.version} is new, saving")
      apiFile.writeText(data)
    }

    if (!releases.versions.contains(api.info.version)) {
      log.info("API ${api.info.title}:${api.info.version} does not exist in releases.json file, updating")
      releases.versions.add(api.info.version)
      releases.write(folder)
    }

    return this
  }

  /**
   * Take the notified version, ensure it is in the published list and update the "latest" to that version.
   */
  fun publish(folder: String) {
    val data = Yaml.pretty(api)
    val apiFile = File("${folder}/${api.info.version}.yaml")

    if (apiFile.exists()) {
      val existingData = apiFile.bufferedReader().readText()
      if (existingData != data) {
        log.error("API ${api.info.title}:${api.info.version} file on disk is different from the currently reconciled file.")
        throw ApiNotUpToDate()
      }
    } else {
      log.error("API ${api.info.title}:${api.info.version} there is no file on disk at all.")
      throw ApiNotUpToDate() // doesn't exist at all
    }

    val releases = Releases.read(folder)

    if (!releases.published.contains(api.info.version)) {
      releases.published.add(api.info.version)
      releases.latest = api.info.version
      releases.write(folder)
    }
  }
}