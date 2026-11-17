package moe.ryuu.kyokouauth.core.storage

import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import javax.tools.ForwardingJavaFileManager
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

/** Adapts the existing driverLoader seam to a controllable JDBC Driver without modifying production. */
internal object FaultDriverClasses {
    private val classes = ConcurrentHashMap<String, ByteArray>()

    fun loader(driverName: String, parent: ClassLoader): ClassLoader {
        val bytes = classes.computeIfAbsent(driverName, ::compile)
        return object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
                val type = findLoadedClass(name) ?: if (name == driverName) {
                    defineClass(name, bytes, 0, bytes.size)
                } else {
                    super.loadClass(name, false)
                }
                if (resolve) resolveClass(type)
                type
            }
        }
    }

    private fun compile(name: String): ByteArray {
        val packageName = name.substringBeforeLast('.')
        val simpleName = name.substringAfterLast('.')
        val source = """
            package $packageName;
            public final class $simpleName implements java.sql.Driver {
                public java.sql.Connection connect(String url, java.util.Properties properties) throws java.sql.SQLException {
                    return java.sql.DriverManager.getConnection("jdbc:kyokou-fault:" + properties.getProperty("fixture-id"), properties);
                }
                public boolean acceptsURL(String url) { return true; }
                public java.sql.DriverPropertyInfo[] getPropertyInfo(String url, java.util.Properties properties) {
                    return new java.sql.DriverPropertyInfo[0];
                }
                public int getMajorVersion() { return 1; }
                public int getMinorVersion() { return 0; }
                public boolean jdbcCompliant() { return false; }
                public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
            }
        """.trimIndent()
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "JDBC fault tests require a JDK" }
        val input = object : SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
        }
        val output = ByteArrayOutputStream()
        compiler.getStandardFileManager(null, null, null).use { standard ->
            val manager = object : ForwardingJavaFileManager<javax.tools.StandardJavaFileManager>(standard) {
                override fun getJavaFileForOutput(
                    location: javax.tools.JavaFileManager.Location,
                    className: String,
                    kind: JavaFileObject.Kind,
                    sibling: javax.tools.FileObject?,
                ): JavaFileObject = object : SimpleJavaFileObject(URI.create("bytes:///" + className.replace('.', '/') + kind.extension), kind) {
                    override fun openOutputStream() = output
                }
            }
            check(compiler.getTask(null, manager, null, listOf("--release", "17", "-proc:none"), null, listOf(input)).call())
        }
        return output.toByteArray()
    }
}
