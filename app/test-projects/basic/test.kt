package app.ultradev.divineprison.util

import app.ultradev.divineprison.util.TestClass

class TestClass(
    val name: String,
) {
    val test: String = "test"

    class TestContainedClass(
        val innerTest: String = "innerTest"
    )
    inner class TestInnerClass(
        val innerTest: String = "innerTest"
    )

    companion object {
        val testCompanion: String = "testCompanion"

        val String.test get() = this + "test"
    }
}

val GLOBAL_TEST = "test"

object Test {
    val test: String = "test"
}

fun test() {
    val testList = listOf(1, 2, 3, 4, 5)
    val testClass = TestClass("test")

    testList.forEach {
        println(it)
    }

    println(testClass.name)

    TestClass.TestInnerClass

    for (i in 1..10) {
        println(i)
    }
}