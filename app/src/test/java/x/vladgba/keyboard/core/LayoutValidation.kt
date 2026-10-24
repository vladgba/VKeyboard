package x.vladgba.keyboard.core

import java.io.File

/** Standalone validation command and negative-fixture checks, without a test library. */
fun main(args: Array<String>) {
    check(LayoutValidator.validate("(((\"key\":\"a\")))").isEmpty())
    check(LayoutValidator.validate("(((\"key\":\"a\")").isNotEmpty())
    check(LayoutValidator.validate("(((\"key\":\"a\",\"width\":\"NaN\")))").isNotEmpty())
    check(LayoutValidator.validate("(((\"key\":\"a\",\"width\":\"0\")))").isNotEmpty())
    check(LayoutValidator.validate("(((\"code\":\"bad\")))").isNotEmpty())
    check(LayoutValidator.validate("(((\"key\":\"go\",\"layout\":\"missing\")))", setOf("en")).isNotEmpty())
    check(LayoutValidator.validate("((()))").isNotEmpty())
    val folder = File(args.single())
    require(folder.isDirectory) { "Layout directory does not exist: $folder" }
    val files = folder.listFiles().orEmpty().filter { it.extension == "txt" && it.nameWithoutExtension !in setOf("settings", "blank") }.sortedBy { it.name }
    require(files.isNotEmpty()) { "No layouts found in $folder" }
    val names = files.map { it.nameWithoutExtension }.toSet()
    var failures = 0
    for (file in files) {
        val problems = LayoutValidator.validate(file.readText(), names)
        for (problem in problems) { println("${file.name}: $problem"); failures++ }
    }
    check(failures == 0) { "$failures validation errors in ${files.size} layouts" }
    println("PASS layout validation: ${files.size} files; malformed/geometry/action/reference fixtures checked")
}
