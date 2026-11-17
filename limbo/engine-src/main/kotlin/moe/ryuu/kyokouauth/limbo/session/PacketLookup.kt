package moe.ryuu.kyokouauth.limbo.session

import java.lang.reflect.Constructor
import java.lang.reflect.Method

/** Startup-only resolution of pinned upstream APIs, never argument-driven overload selection. */
internal class PacketLookup(private val protocol: Int) {
    data class Counts(val classes: Int, val constructors: Int, val methods: Int, val enums: Int, val arrays: Int, val fields: Int)

    private val classes = HashMap<String, Class<*>>()
    private var constructors = 0
    private var methods = 0
    private var enums = 0
    private var arrays = 0
    private var fields = 0

    fun counts() = Counts(classes.size, constructors, methods, enums, arrays, fields)

    private fun type(name: String): Class<*> = classes.getOrPut(name) { Class.forName(name) }

    fun <T> constructor(name: String, result: Class<T>, vararg parameters: Class<*>): Constructor<out T> =
        resolve("constructor $name(${parameters.joinToString { it.typeName }})") {
            constructors++
            type(name).asSubclass(result).getConstructor(*parameters)
        }

    fun method(owner: Class<*>, name: String, result: Class<*>, vararg parameters: Class<*>): Method =
        resolve("method ${owner.name}.$name(${parameters.joinToString { it.typeName }})") {
            methods++
            owner.getMethod(name, *parameters).also {
                check(it.returnType == result) { "Unexpected return type ${it.returnType.typeName}; expected ${result.typeName}" }
            }
        }

    fun emptyArray(component: String): Any = resolve("empty array $component[]") {
        arrays++
        java.lang.reflect.Array.newInstance(type(component), 0)
    }

    fun enumConstant(name: String, constant: String): Any = resolve("enum $name.$constant") {
        enums++
        val values = checkNotNull(type(name).enumConstants) { "Expected an enum" }
        checkNotNull(values.singleOrNull { (it as Enum<*>).name == constant }) { "Missing unique enum constant" }
    }

    fun <T> staticField(owner: String, name: String, result: Class<T>): T = resolve("field $owner.$name") {
        fields++
        val field = type(owner).getField(name)
        check(java.lang.reflect.Modifier.isStatic(field.modifiers)) { "Expected a static field" }
        result.cast(checkNotNull(field.get(null)))
    }

    fun singleton(name: String) = resolve("singleton $name.INSTANCE") {
        fields++
        val owner = type(name)
        val value = checkNotNull(owner.getField("INSTANCE").get(null)) { "Missing singleton instance" }
        check(owner.isInstance(value)) { "Unexpected singleton type ${value.javaClass.name}" }
    }

    private inline fun <T> resolve(description: String, action: () -> T): T = try {
        action()
    } catch (failure: Exception) {
        throw IllegalStateException("Pinned protocol $protocol has unexpected upstream $description", failure)
    }
}
