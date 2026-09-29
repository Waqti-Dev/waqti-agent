package com.waqti.agent

/**
 * Tool description handed to the model and shared by the model boundary and the
 * tool layer. [parameters] is a JSON schema expressed as text.
 */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: String
)
