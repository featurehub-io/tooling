package io.featurehub.tooling

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.parameters.RequestBody
import io.swagger.v3.oas.models.responses.ApiResponses
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.lang.RuntimeException

data class IllegalExtension(val schema: Schema<*>, val extension: String) {
  override fun toString(): String {
    return "illegal extension: ${extension} in schema ${schema.`$schema`}"
  }
}

class IllegalExtensionDetected(illegalExtensions: List<IllegalExtension>) :
  RuntimeException("Illegal extensions detected: $illegalExtensions")

class Extensions(
  forceIncludeTagValues: List<String>,
  objectExtensions: List<String>,
  propertyExtensions: List<String>,
  illegalExtensions: List<String>
) {
  companion object {
    const val publishIncludeExtension = "x-publish-include"
  }
  val objectExtensions: Set<String>
  val propertyExtensions: Set<String>
  val illegalExtensions: Set<String>
  val forceIncludeTagValues: Set<String>

  init {
    this.illegalExtensions = illegalExtensions.toSet()
    this.objectExtensions = objectExtensions.toMutableSet()
    this.objectExtensions.add(publishIncludeExtension) // this one is specific to this code/plugin
    this.forceIncludeTagValues = forceIncludeTagValues.toSet()
    this.propertyExtensions = propertyExtensions.toSet()
  }
}

class Reconcile(private val api: OpenAPI, private val extensions: Extensions) {
  companion object {
    private val log: Logger = LoggerFactory.getLogger(Reconcile::class.java)
    const val schema_prefix = "#/components/schemas/"
    val schema_prefix_len = schema_prefix.length
  }

  private val foundRefs = mutableSetOf<String>()
  private var addFoundToRefs = true

  private fun strip(ref: String?): String? {
    if (ref == null) {
      return null
    }

    val name = if (ref.startsWith(schema_prefix)) {
      ref.substring(schema_prefix_len)
    } else {
      ref
    }
    if (addFoundToRefs) {
      foundRefs.add(name)
    }
    return name
  }

  private fun strip(schema: Schema<*>?): String? {
    if (schema == null) {
      return null
    }

    if (schema.`$ref` != null) {
      return strip(schema.`$ref`)
    }

    return null
  }

  private fun trackParams(params: List<io.swagger.v3.oas.models.parameters.Parameter>? ) {
    if (params == null) {
      return
    }

    params.forEach { param ->
      strip(param.schema.`$ref`)
    }
  }

  private fun trackRequestBody(requestBody: RequestBody?) {
    if (requestBody == null) {
      return
    }

    requestBody.content?.forEach { _, content ->
      strip(content?.schema)
      strip(content?.schema?.items)
    }
  }



  /**
   * Illegal Extensions are those that may appear in the API document before we do any processing on it. They must never appear,
   * if they have then we have included schema definitions that should not be included.
   */
  private fun detectIllegalExtensions() {
    val illegalExtensions = mutableListOf<IllegalExtension>()

    foundRefs.forEach { key ->
      detectIllegalExtensions(api.components.schemas[key], illegalExtensions)
    }

    if (illegalExtensions.isNotEmpty()) {
      throw IllegalExtensionDetected(illegalExtensions)
    }
  }

  private fun detectIllegalExtensions(schema: Schema<*>?, illegalExtensions: MutableList<IllegalExtension>) {
    if (schema == null) return

    schema.extensions?.forEach { (key, _) ->
      if (extensions.illegalExtensions.contains(key)) {
        illegalExtensions.add(IllegalExtension(schema, key))
      }
    }

    schema.properties?.forEach { (_, property) ->
      property.extensions?.forEach { key, _ ->
        if (extensions.illegalExtensions.contains(key)) {
          illegalExtensions.add(IllegalExtension(schema, key))
        }
      }

      property.additionalProperties?.let {
        detectIllegalExtensions(property.additionalProperties as Schema<*>, illegalExtensions)
      }
    }

    schema.allOf?.forEach { detectIllegalExtensions(it, illegalExtensions) }
    schema.oneOf?.forEach { detectIllegalExtensions(it, illegalExtensions) }
    schema.anyOf?.forEach { detectIllegalExtensions(it, illegalExtensions) }

  }

  private fun renameShortenedProperties() {
    api.components.schemas.forEach { (_, schema) ->
      renameShortenedPropertySchema(schema)
    }
  }

  /**
   * x-basename is a way of renaming a long property name to a short one. Ideally it should have actually been the other
   * way around. This causes problems for non-FeatureHub OpenAPI generators and when using Typescript in interface mode only (as there
   * is no serialisation into the right format).
   *
   * e.g.
   * required:
   *   - organisationId
   * properties:
   *    organisationId:
   *      x-basename: oId
   *      type: string
   *      format: uuid
   *
   * becomes
   * required:
   *   - oId
   * properties:
   *    oId:
   *      type: string
   *      description: organisationId
   *      format: uuid
   */
  private fun renameShortenedPropertySchema(schema: Schema<*>?) {
    if (schema == null) return

    if (schema.properties != null) {
      val keys = schema.properties.keys.toSet()
      keys.forEach { key ->
        val property = schema.properties[key]
        if (property?.extensions?.contains("x-basename") == true) {
          val newPropertyName = property.extensions["x-basename"].toString()

          schema.properties[newPropertyName] = property
          schema.properties.remove(key)

          // if the old key was required, remove it from the list and add the new property name
          if (schema.required.remove(key)) {
            schema.required.add(newPropertyName)
          }

          if (property.description != null) {
            property.description = "($key) - ${property.description}"
          } else {
            property.description = key
          }

          property.extensions.remove("x-basename")
        }
      }
    }

    // now see if additional properties has it (unlikely)
    schema.additionalProperties?.let { renameShortenedPropertySchema((it as Schema<*>?)) }

    // and see if there is some allOf/oneOf/anyOf further down that has it
    schema.allOf?.forEach { renameShortenedPropertySchema(it) }
    schema.oneOf?.forEach { renameShortenedPropertySchema(it) }
    schema.anyOf?.forEach { renameShortenedPropertySchema(it) }
  }

  private fun stripExtensions() {
    api.components.schemas.forEach { (_, schema) ->
      stripSchemaExtensions(schema)
    }
  }

  private fun stripSchemaExtensions(schema: Schema<*>?) {
    if (schema == null) {
      return
    }

    extensions.objectExtensions.forEach {
      schema.extensions?.remove(it)
    }

    if (extensions.propertyExtensions.isNotEmpty()) {
      schema.properties?.forEach { (_, property) ->
        extensions.propertyExtensions.forEach { extension ->
          property.extensions?.remove(extension)
        }
      }

      schema.additionalProperties?.let { stripSchemaExtensions((it as Schema<*>?)) }

      schema.allOf?.forEach { stripSchemaExtensions(it) }
      schema.oneOf?.forEach { stripSchemaExtensions(it) }
      schema.anyOf?.forEach { stripSchemaExtensions(it) }
    }
  }

  private fun deleteRemainingSchemas(schemaObjects: MutableSet<String>) {
    val schemas = api.components.schemas
    schemaObjects.toSet().forEach {
      schemas.remove(it)
    }
  }

  private fun checkIfRemainingSchemasHaveAlwaysIncludeTags(
    schemaObjects: MutableSet<String>,
  ) {
    schemaObjects.forEach { schema ->
      if (api.components.schemas[schema]?.extensions?.contains(Extensions.publishIncludeExtension) == true) {
        val pubExt = api.components.schemas[schema]?.extensions?.get(Extensions.publishIncludeExtension)?.toString()
        if (pubExt != null) {
          if (pubExt == "true") {
            searchComponentForRefs(schema, api.components.schemas)
          } else if (pubExt.splitToSequence(",").any { extensions.forceIncludeTagValues.contains(it.trim()) }) {
            searchComponentForRefs(schema, api.components.schemas)
          }
        }
      }
    }
  }

  private fun processComponent(obj: Schema<*>?, schemas: Map<String, Schema<*>>) {
    if (obj == null) {
      return
    }

    // the object may simply be a reference to another one (because of inheritance)
    strip(obj.`$ref`)?.let {
      discoverRef(it, schemas)
    }

    if (obj.type == null || obj.type == "object") {
      obj.properties?.forEach { name, property ->
        discoverRef(strip(property.`$ref`), schemas)

        strip(property.items)?.let {
          discoverRef(it, schemas)
        }

        if (property.additionalProperties != null) {
          strip(property.additionalProperties as Schema<*>)?.let {
            discoverRef(it, schemas)
          }
        }
      }

      obj.allOf?.forEach { processComponent(it, schemas) }
      obj.oneOf?.forEach { processComponent(it, schemas) }
      obj.anyOf?.forEach { processComponent(it, schemas) }
    }
  }

  private fun searchComponentForRefs(ref: String?, schemas: Map<String, Schema<*>>) {
    if (ref == null) {
      return
    }

    val obj = schemas[ref]

    if (obj == null) {
      return
    }

    foundRefs.add(ref)

    processComponent(obj, schemas)
  }

  private fun discoverRef(ref: String?, schemas: Map<String, Schema<*>>) {
    if (ref == null || foundRefs.contains(ref)) {
      return
    }

    searchComponentForRefs(ref, schemas)
  }

  private fun spelunkRefsFromApi(schemas: Map<String, Schema<*>>) {
    val existingRefs = foundRefs.toList()
    existingRefs.forEach { ref ->
      searchComponentForRefs(ref, schemas)
    }
  }

  private fun trackResponseBody(responses: ApiResponses?) {
    if (responses == null) {
      return
    }

    responses.forEach { _, response ->
      response.content?.forEach { (_, content) ->
        strip(content?.schema)
        strip(content?.schema?.items)
      }
    }
  }

  fun reconcile() {
    val schemaObjects = api.components.schemas.keys.toMutableSet()

    api.paths.forEach { _, details ->
      trackParams(details.parameters)
      trackParams(details.get?.parameters)
      trackParams(details.post?.parameters)
      trackParams(details.put?.parameters)
      trackParams(details.delete?.parameters)

      trackRequestBody(details.post?.requestBody)
      trackRequestBody(details.put?.requestBody)

      trackResponseBody(details.get?.responses)
      trackResponseBody(details.post?.responses)
      trackResponseBody(details.put?.responses)
      trackResponseBody(details.delete?.responses)
    }

    addFoundToRefs = false
    spelunkRefsFromApi(api.components.schemas)

    // if schemas have forceIncludeTag then include those schemas as well,
    checkIfRemainingSchemasHaveAlwaysIncludeTags(schemaObjects)

    // "forceInclude"" may have collected some more to keep
    schemaObjects.removeAll(foundRefs)

    deleteRemainingSchemas(schemaObjects)

    detectIllegalExtensions()

    renameShortenedProperties() // x-basename
    stripExtensions()

    log.info("API ${api.info.title}:${api.info.version} has unused schema objects $schemaObjects")
  }
}