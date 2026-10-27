plugins {
    id("com.android.application") version "8.10.1" apply false
    // miuix 0.8.8 以 Kotlin 2.3.20 编译，旧编译器读不了它的元数据，所以这里同步升到 2.3.20。
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
