load("@rules_java//java:java_library.bzl", "java_library")
load("//intellij_platform_sdk:build_defs.bzl", "select_for_plugin_api")

java_library(
    name = "toml_internal",
    visibility = ["//visibility:private"],
    exports = select_for_plugin_api({
        "intellij-2025.1": ["@toml_2025_1//:toml"],
        "intellij-2025.2": ["@toml_2025_2//:toml"],
        "intellij-ue-2025.1": ["@toml_2025_1//:toml"],
        "intellij-ue-2025.2": ["@toml_2025_2//:toml"],
        "default": [],
    }),
)

java_library(
    name = "toml_for_tests",
    testonly = 1,
    visibility = ["//python:__pkg__"],
    exports = [":toml_internal"],
)
