load("@rules_java//java:java_library.bzl", "java_library")
load("//intellij_platform_sdk:build_defs.bzl", "select_for_plugin_api")

package(default_visibility = ["//visibility:public"])

licenses(["notice"])

java_library(
    name = "scala_internal",
    visibility = ["//visibility:private"],
    exports = select_for_plugin_api({
        "intellij-2025.1": ["@scala_2025_1//:scala"],
        "intellij-2025.2": ["@scala_2025_2//:scala"],
        "intellij-ue-2025.1": ["@scala_2025_1//:scala"],
        "intellij-ue-2025.2": ["@scala_2025_2//:scala"],
        "default": [],
    }),
)

java_library(
    name = "scala_for_tests",
    testonly = 1,
    exports = [":scala_internal"],
)

java_library(
    name = "scala",
    neverlink = 1,
    exports = [":scala_internal"],
)
