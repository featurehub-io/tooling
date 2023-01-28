package io.featurehub.tooling

import io.swagger.parser.OpenAPIParser
import io.swagger.v3.parser.core.models.ParseOptions
import org.junit.Test
import java.io.File

class ReconcileTest {
  @Test
  fun basicReconcile() {
    val pwd = System.getProperty("user.dir")!!
    File("$pwd/target").mkdirs()
    val result =
      OpenAPIParser().readLocation( "$pwd/src/test/resources/enricher-api.yaml",
        emptyList(),
        ParseOptions()
      )

    val apiDoc = result.openAPI
    Reconcile(
      apiDoc,
      Extensions(
        mutableListOf("enricher"),
        mutableListOf("x-package", "x-cloudevent-type", "x-cloudevent-subject"),
        mutableListOf("x-basename", "x-property-ref"),
        mutableListOf("x-property-ref")
      )
    ).reconcile()

    ReleasePublisher(apiDoc).updateRelease(
      "target"
    )

    assert(File("target/releases.json").readText() == "{\n" +
        "  \"versions\" : [ \"1.1.1\" ],\n" +
        "  \"published\" : [ ]\n" +
        "}")

    val found = OpenAPIParser().readLocation( "target/1.1.1.yaml",
      emptyList(),
      ParseOptions()
    )

    assert(found.openAPI.components.schemas.size == 2)
    assert(found.openAPI.components.schemas.containsKey("EnrichedFeatures"))
    assert(found.openAPI.components.schemas.containsKey("PublishEnvironment"))
  }
}